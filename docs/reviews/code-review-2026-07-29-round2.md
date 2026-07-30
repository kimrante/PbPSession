# 코드 리뷰 보고서 2차 (2026-07-29) — `e05dff1` 재점검

1차 수정 커밋 `e05dff1`(P0~P3 일괄 반영)과 신규 기능(`f484a00` 방 로그 초기화, `37400f5` 스페이싱 토큰)에 대한 재점검 결과.
**다른 세션에서 이 문서만 보고 수정 작업을 진행할 수 있도록** 항목마다 위치·재현 시나리오·수정 방향을 명시한다.
기준 커밋: `e05dff1` (main 최신). 라인 번호는 이 커밋 기준.

1차 수정의 대부분은 정상 반영이 확인됐다(문서 말미 "검증 정상" 참조). 이 문서의 항목은 **1차 수정이 새로 만든 회귀(R)**, **신규 기능·수정의 결함(N)**, **클린업(C)** 세 그룹이다.

권장 수정 순서: **R1~R5 → N1~N4 → N5~N9 → C**.

---

## R — 1차 수정 커밋이 만든 회귀 (최우선)

### R1. 데스크톱 아바타가 영영 로드되지 않음 (P3-15 수정의 역효과)

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:700-719`
- **원인**: `LaunchedEffect(avatarId)`가 `if (avatarId != null && !avatarCache.containsKey(avatarId))` 조건문 **안에** 있는데, 이펙트 첫 줄이 in-flight 표식으로 `avatarCache[avatarId] = null`을 기록한다. `avatarCache`는 `mutableStateMapOf`라 이 쓰기가 즉시 리컴포지션을 유발 → 조건이 false → 이펙트가 컴포지션에서 이탈 → **fetch 코루틴이 취소**된다. `withContext`가 CancellationException을 던지므로 결과 저장(`:714`)도 실패 시 `remove()`(`:717`)도 실행되지 않는다.
- **결과**: 캐시에 `null`이 영구 잔류, 모든 아바타가 이모지 폴백으로 고정. 수정 전(중복 fetch)보다 나쁨.
- **수정 방향**: `LaunchedEffect(avatarId)`를 조건 밖(항상 컴포지션)에 두고, in-flight 추적은 스냅샷 상태가 아닌 일반 컬렉션(예: 앱 수준 `ConcurrentHashMap.newKeySet<String>()`)으로 분리. 또는 캐시 값을 `sealed class`(Loading/Loaded/Failed)로 바꾸고 분기를 이펙트 본문 안에서 처리.

### R2. shareRoom 재시도가 inviteCodes 규칙에 막혀 방이 "죽은 초대코드" 상태로 영구 고착

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:183-185` vs `firestore.rules:55`(`inviteCodes`: `allow update, delete: if false`), UI 재호출 부재 `app/src/main/java/com/pbp/app/ui/roomsettings/RoomSettingsScreen.kt:254-258`
- **원인**: shareRoom이 매번 무조건 `inviteCodes/{code}`에 `set()`을 한다. 문서가 이미 있으면 규칙상 update → **permission-denied** → 예외로 shareRoom이 백필/attach 전에 중단. 게다가 `setRemote`(`:178`)가 방 문서 생성 직후 실행되므로, 이후 단계(ensureMembership·inviteCodes·백필)가 실패해도 UI는 `room.inviteCode != null`이라 코드를 표시만 하고 `shareRoom`을 다시 부르지 않는다. `start()`의 memberFix는 1회성 전역 플래그라 복구 경로가 없다.
- **재현**: 공유 중 네트워크 끊김(inviteCodes 쓰기 실패) → 화면엔 초대코드가 보이지만 매핑 문서가 없어 아무도 참가 불가, 마스터의 멤버 문서도 없으면 이후 모든 push/listen이 permission-denied. 재시도 시엔 R2 자체(기존 매핑 문서에 set → 거부)로 더 일찍 죽음.
- **수정 방향**: ① inviteCodes 쓰기를 조건부로 — `get()` 후 문서가 없을 때만 `set()`(있으면 roomId 일치 확인). ② `RoomSettingsScreen`에서 inviteCode가 있어도 공유 버튼/진입 시 `share()`를 재호출해 멱등 복구(ensureMembership·매핑·백필 재실행). 규칙 완화(같은 roomId면 update 허용)도 대안이나 클라이언트 수정이 더 안전.

### R3. 첫 스냅샷 삭제 대조(P1-1)가 방금 업로드한 내 메시지를 삭제하는 레이스

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:132-140`(attach 후 아웃박스 푸시), `:426-433`(reconcile)
- **원인**: reconcile이 "현재 DB의 uploaded=1 remoteId 집합 − 첫 서버 스냅샷의 allIds"를 삭제하는데, 첫 스냅샷은 attach 직후(아웃박스 업로드 **전**) 생성될 수 있다. 스냅샷 생성 후 `setUploaded`가 먼저 실행되고 reconcile이 그 뒤에 돌면, 새 remoteId가 allIds에 없어 로컬 행이 삭제된다. 이후 도착하는 해당 문서의 ADDED 이벤트는 본인 작성자 필터(`:382`)에 걸려 복구되지 않는다.
- **재현**: 앱 시작 직후(또는 재접속 직후) 메시지 전송 → 간헐적으로 그 메시지가 내 화면에서 사라짐(서버·상대에겐 존재).
- **수정 방향**: 삭제 후보 기준선을 **attach 시점**에 캡처(consumer 코루틴 시작 시 `listRemoteIdsForRoom` 스냅샷)하고 첫 이벤트 대조는 그 기준선과만 비교. 또는 `start()`에서 아웃박스 푸시를 attach보다 먼저 실행.

### R4. 데스크톱: 토큰 갱신의 일시 실패가 새 익명 계정을 만들어 세션을 잠금

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/data/Firestore.kt:71-77`
- **원인**: `currentToken()`이 `refreshIdToken` 실패 원인을 구분하지 않고(타임아웃, 5xx, 순간 오프라인 포함) 무조건 `signUpAnonymous()`로 폴백. 새 refresh token이 `config.save()`로 기존 것을 덮어써 **이전 UID가 영구 복구 불가**. 규칙 배포 후 새 UID는 어느 방의 멤버도 아니므로 폴링은 조용히 null만 받고(채팅 정지), `ensureMember`는 시작/참가 시에만 돌아 재시작 전까지 잠긴다.
- **수정 방향**: `signUpAnonymous()`는 `refreshToken == null`일 때만. 갱신 실패 시 HTTP 400의 `invalid_grant`/`TOKEN_EXPIRED`(토큰 폐기 → 재가입)와 네트워크/5xx(토큰 유지, null 반환 후 다음 요청에서 재시도)를 구분.

### R5. leaveRoom의 멤버 문서 삭제 순서가 거꾸로 — 유령 FCM 알림 재발 (P2-2 미완)

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:474-479` vs `firestore.rules:46`
- **원인**: `members/{myUid}`를 먼저 삭제하면 그 순간 `isMember()`가 false. 이어지는 레거시 `members/{deviceId}` 삭제는 규칙(`isMember(roomId) || request.auth.uid == memberId`)에서 두 조건 모두 불충족 → 거부(내부 runCatching이 삼킴). deviceId 문서의 fcmToken이 남아 방을 나가도 푸시가 계속 온다.
- **수정 방향**: deviceId 문서를 **먼저** 삭제하고 본인 문서를 나중에.

---

## N — 신규 기능·수정의 결함

### N1. 방 로그 초기화가 화면 스코프에서 실행 — 뒤로가기로 파괴적 작업이 중간 취소

- **위치**: `app/src/main/java/com/pbp/app/ui/roomsettings/RoomSettingsScreen.kt:87-89`(viewModelScope), `app/src/main/java/com/pbp/app/data/PbpRepository.kt:175-191`
- **재현**: 초기화 확인 → 화면 이탈 → VM 소멸로 코루틴이 임의의 `await()`에서 취소 → 로컬은 지워졌는데 서버는 부분 삭제(450건 배치 중 일부만), 공지·토스트·재시도 없음.
- **수정 방향**: 앱 수준 스코프(예: `SyncManager.scope`)로 이관하거나 repo 본문을 `withContext(NonCancellable)`로 감싸기.

### N2. 방 로그 초기화가 로컬 선삭제 — 서버 wipe 실패 시 "상대 절반만 남는" 영구 분기

- **위치**: `app/src/main/java/com/pbp/app/data/PbpRepository.kt:177-180` + `app/src/main/java/com/pbp/app/sync/SyncManager.kt:248-258, 380-383`
- **재현**: 오프라인에서 초기화 → 로컬 전체 삭제, `wipeMessages` false, 끝. 다음 시작 때 리스너가 서버 생존 문서를 ADDED로 재전달 → 상대 작성분만 필터를 통과해 **재삽입**, 내 메시지는 서버·상대에겐 남고 내 쪽만 없음. 재시도 경로 없음.
- **수정 방향**: 순서 반전(서버 wipe 성공 후 로컬 삭제). 오프라인 지원이 필요하면 pending-wipe 플래그를 저장하고 `SyncManager.start()`에서 재시도.

### N3. 데스크톱: 다이스 후속 전송 실패 시 본문 메시지 중복 + 복원이 새 입력을 덮어씀

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:180-195`(TEXT 성공 후 DICE 실패도 `ok=false`), `:856-861`(무조건 `input = text` 복원)
- **재현**: ① TEXT 전송 성공, DICE 후속 실패 → 실패 배너 + 입력 복원 → 사용자가 재전송 → **TEXT가 서버에 2건**(docId가 달라 dedup 통과). ② 전송이 오래 걸리는 사이(타임아웃 최대 30초×2) 새 메시지를 타이핑 → 늦게 도착한 실패 콜백이 그 텍스트를 덮어쓰고 잡담 토글도 되돌림.
- **수정 방향**: 입력 복원은 **TEXT 자체 실패 시에만**. DICE 실패는 별도 에러 표시(또는 DICE만 재시도). 복원 시 `if (input.isEmpty())` 가드로 새 입력 보호.

### N4. 위로 스크롤 중 내 메시지를 전송해도 화면이 안 내려감 (P1-7 수정의 부작용)

- **위치**: `app/src/main/java/com/pbp/app/ui/chat/ChatScreen.kt:190-194`(`firstVisibleItemIndex <= 1` 게이트가 본인 전송까지 억제)
- **재현**: 이전 대화를 읽다가 답장 전송 → 입력창은 비워지는데 목록이 그대로 → 메시지가 화면 밖(아래)에 있어 "전송 실패처럼" 보임.
- **수정 방향**: 본인 전송을 명시 신호로 — `onSend` 람다에서 `sendTick++` 후 `LaunchedEffect(sendTick) { listState.scrollToItem(0) }`. 수신 메시지 억제 로직은 유지.

### N5. 시작 시 익명 인증 실패에 복구 경로 없음 + FCM 스킵이 장애를 은폐

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:124-144`(ensureAuth 실패해도 attach 진행), `app/src/main/java/com/pbp/app/notify/FcmService.kt:25`(`isAttached`가 리스너 "등록" 여부만 반영)
- **재현**: 오프라인 첫 실행 → 인증 실패 상태로 attach → 온라인 복귀 후 모든 listen이 permission-denied(로그만 남음), 쓰기 전까지 재인증 없음. 그동안 `isAttached=true`라 FCM 알림도 스킵 → 동기화·알림 둘 다 조용히 죽음.
- **수정 방향**: 리스너 permission-denied 시 `ensureAuth` 재시도 + re-attach. 또는 리스너가 영구 오류 상태면 `isAttached`를 false로.

### N6. deviceId 시절 메시지는 영구 편집 불가 + push/pushEdit/pushDelete 실패 무로그

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:302-311`(pushEdit 등 예외 무로그 삼킴) vs `firestore.rules:32`(`authorUid == request.auth.uid`)
- **재현**: deviceId 폴백 시절(또는 인증 전 백필) 업로드된 메시지 편집 → 로컬만 반영, 원격 거부, 로그 없음 → 기기 간 조용한 분기.
- **수정 방향**: 최소한 실패 로그 추가. 가능하면 본인 방에 대해 authorUid를 auth UID로 1회 백필(규칙상 본인 문서만 update 가능하므로 deviceId 작성분은 규칙 예외 또는 Function 필요 — 로그+수용도 선택지).

### N7. SyncManager 공유 상태의 스레드 안전성 부재

- **위치**: `SyncManager.kt` — `listeners`, `roomListeners`, `attachedRemotes`, `eventChannels`, `uploadedAvatars`, `avatarBytesCache`가 일반 HashMap/Set인데 여러 IO 스레드에서 변경, `isAttached`(`:50`)는 FCM 바인더 스레드에서 읽음.
- **수정 방향**: `ConcurrentHashMap`/`Collections.synchronizedMap` 전환. 드문 CME/맵 손상 예방.

### N8. 데스크톱: persist()의 리스트 변경과 동시 실행 중인 save()의 순회 충돌 — CME로 스코프 전체 사망 가능

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:119-124`(UI 스레드에서 clear/refill 후 IO로 save) vs `desktop/src/main/kotlin/com/pbp/desktop/data/Config.kt:82-83`(`@Synchronized save`가 persist의 변경까지 막지 못함, `tmp.writeText` 예외 미처리)
- **재현**: 이전 save가 `rooms.toList()` 순회 중 persist가 리스트 변경 → CME → `rememberCoroutineScope` 자식의 미처리 예외로 **스코프 Job 취소** → 이후 모든 `scope.launch`(전송·저장·참가)가 무반응. 디스크 풀 IOException도 동일 경로.
- **수정 방향**: 불변 `Saved` 스냅샷을 호출 스레드에서 만들어 IO에서는 파일 쓰기만(runCatching으로 감싸기). 또는 변경과 save를 같은 락으로 동기화.

### N9. ProfileEdit: 비동기 로드 완료 전 저장하면 프로필 복제

- **위치**: `app/src/main/java/com/pbp/app/ui/profile/ProfileEditScreen.kt:115`(`existing`이 일반 remember), `:142-154, 193`
- **재현**: 프로세스 사후 복원 직후(폼은 saveable이라 즉시 렌더링, `existing`은 아직 null) 저장 탭 → `id=0` 신규 insert → 프로필 복제. 창은 짧지만 실재.
- **수정 방향**: `existing != null || profileId == 0L`일 때만 저장 버튼 활성화.

### N10 (경미). 채팅 다이얼로그는 여전히 회전 시 소실 (P2-4 부분 반영)

- **위치**: `ChatScreen.kt:177-180`(`editTarget` 등 일반 remember — 내부 `EditMessageDialog`의 saveable 본문도 함께 소멸해 사실상 사문)
- **수정 방향**: 대상을 saveable한 `Long?`(메시지 id)로 저장하고 Message는 목록에서 조회. 또는 "다이얼로그는 회전 시 닫힘"을 의도로 문서화하고 수용.

---

## C — 클린업 (동작 영향 없음/미미)

| # | 항목 | 위치 | 조치 |
|---|---|---|---|
| C1 | 규칙 배포 후 100% 실패하는 레거시 초대코드 폴백 (지연만 추가) | `SyncManager.kt:220-222`, `desktop/.../Firestore.kt:222-242` | 규칙 배포 확인 후 제거 |
| C2 | deviceId 신원 폴백 — 규칙 배포 후엔 permission-denied 소음만 생성 | `SyncManager.kt:88`(`myUid = authUid ?: deviceId`), `:477-479, 534-540` | 전환기 후 제거 계획 (N5 해결과 연계) |
| C3 | 데스크톱 authorUid가 auth UID가 아닌 deviceId — 향후 편집 기능이 규칙에 막힘 | `desktop/.../Main.kt:177, 189, 214` | `firestore.uid ?: config.deviceId`로 전환 + 주석 |
| C4 | 호출자 없는 `countByRemoteId` (1차 수정 전부터 미사용) | `app/.../data/Daos.kt:99-100` | 제거 |
| C5 | `members` read 규칙이 `signedIn()` — 방 ID를 아는 누구나 FCM 토큰 열람 가능 | `firestore.rules:42` | `isMember(roomId)`로 조이기 (클라이언트는 members를 읽지 않음, Functions는 admin이라 무관) |
| C6 | 중복 알림 방지 미세 누락: insert(IGNORE) 충돌 시에도 알림 발화 | `SyncManager.kt:404-405` | `insert(...) != -1L` 확인 후 알림 |
| C7 | push() 배치 중 1건 실패 시 나머지(짝 DICE) 미시도 | `SyncManager.kt:261-268` | runCatching을 forEach 안쪽으로 |
| C8 | 데스크톱 `AppConfig.load()`(읽기+즉시 쓰기)가 컴포지션 중 UI 스레드 실행 — P3-16 반쪽 반영 | `Main.kt:86`, `Config.kt:61` | IO로 이동 |
| C9 | 데스크톱은 여전히 size 변경마다 최하단 강제 스크롤 (Android P1-7 수정과 불일치) | `Main.kt:514-516` | Android와 동일 로직 이식 |
| C10 | 데스크톱 편집/삭제 미반영 — 30초 윈도우가 편집 문서를 재수신하지만 docId dedup이 버림 | `Main.kt:140` | `editedAt` 다르면 업서트(편집 동기화가 거의 공짜) |
| C11 | 데스크톱 스탯 값 무정리 — `{`/`}` 포함 값이 마커 파싱 실패 (Android P3-11 반영과 불일치) | `Logic.kt` substitute / config 로드 | 로드 시 정리 |
| C12 | 데스크톱 `pageToken` URL 미인코딩 — `+`/`=` 포함 토큰이면 초기 로드 영구 실패 | `Firestore.kt:323` | URL 인코딩 |
| C13 | 데스크톱 `createInviteCode`/`ensureMember` 실패 무시 — 죽은 초대코드/읽기 불가 참가 무통보 | `Main.kt:281, 258` | 실패 표시 (R2와 연계) |
| C14 | `switchProfile` SYSTEM 메시지 실패 여전히 무통보 (P1-5 잔여) | `Main.kt:207-218` | N3과 동일 처리 |
| C15 | `currentToken()`이 인스턴스 락을 쥔 채 블로킹 HTTP(최악 60초) — 폴링·전송 직렬화; 401 반응형 처리 없음 | `Firestore.kt:71-77` | R4 수정 시 함께 (락 밖 refresh, 401 시 1회 무효화+재시도) |
| C16 | 토큰 리팩토링이 놓친 하드코딩 dp 9곳 | `ChatScreen.kt:297, 444, 453-455, 456, 483, 793, 811`, `ImageCrop.kt:123`, `RoomSettingsScreen.kt:329, 401` | PbpDimens로 교체 |
| C17 | `forEachIndexed { _, ... }` 잔재, 완전수식 `rememberSaveable` 반복 | `ProfileEditScreen.kt:278`, ChatScreen/ProfileEditScreen | `forEach`/import로 정리 |
| C18 | FcmService가 메시지마다 `MessageNotifier` 새로 생성(채널 재생성), PbpApp이 `!isForeground` 대신 수동 재계산 | `FcmService.kt:27`, `PbpApp.kt:40` | 재사용/통일 |

---

## 검증 정상 (재확인 불필요)

- **마이그레이션 6→7**: 중복 정리(MIN(id)) → 유니크 인덱스 순서 정확, 인덱스명 Room 규약 일치, 레거시 v6 행의 `uploaded=1` 판정 근거 확인(구코드는 set 성공 후에만 remoteId 기록).
- **멱등 전송**: remoteId 선고정으로 중복 창 봉쇄, 크래시 후 재전송은 같은 문서에 덮어쓰기, 수신 메시지는 `uploaded=true`라 아웃박스 미진입.
- **IN 청크(900)**, **방별 직렬 채널**(detach 시 정리, 누수 없음), **알림 ID 통일**(양 경로 `remoteRoomId.hashCode()`).
- **규칙-실쿼리 정합**: 방 get/create/update, messages 전 작업(로그 초기화의 타인 메시지 delete 포함), members, avatars, inviteCodes get/create, 데스크톱 runQuery — 전부 규칙과 대조 완료. 복합 인덱스 불요.
- **데스크톱 폴링**: `>=` + 30초 윈도우 + docId dedup + 오류 시 커서 미전진 + 부분 페이지 실패 시 전체 중단 — P1-4/P1-6 정상.
- **타임아웃**(connect 10s/request 30s, 인증 포함 전 요청), **Bearer 헤더 전 호출부**, **config 원자적 쓰기**(`Files.move ATOMIC_MOVE`+폴백, 같은 디렉터리 tmp, 파싱 실패 시 .bak 백업).
- **스탯 치환** 양 클라이언트 로직 동일(적용 순서 포함), **짝 없는 `**` 수정** 수기 추적으로 문자 소실 케이스 없음(잔여 특이 케이스는 스타일링 차이뿐), **GmSpeech 빈 인용 가드** 동일.
- **EXIF-크롭**: 회전 보정이 표시·저장 행렬에 동일 적용, IO 이동 시 값 캡처로 stale 참조 없음, `downscaleToJpeg`의 EXIF 생략은 내부 JPEG만 다루므로 올바름.
- **자동 스크롤(P1-7)·이전 대화 버튼(P3-7)·팔레트 dedup(P1-8)·saveable 폼(listSaver)** 정상. **스페이싱 토큰 전수 대조** — 크기 오류 없음. **자격증명 제거** 완료(잔존 `AIza…`는 의도된 공개 클라이언트 설정, 키 제한 안내는 docs/firebase-security.md).
- **functions/index.js**: 새 멤버 문서 형태에 수정 불요(admin 실행, 데스크톱 멤버 문서는 fcmToken 없음).

## 테스트 권고

- R3(레이스)·N2(재부활)는 재현 테스트가 어렵지만, reconcile 기준선 로직과 resetLogs 순서는 단위 테스트 가능 — SyncManager에서 해당 로직을 순수 함수로 추출해 커버 권장.
- N3 수정 시 데스크톱 전송 실패 경로(TEXT 실패/DICE만 실패/타이핑 중 늦은 실패)를 수동 시나리오로 확인.
