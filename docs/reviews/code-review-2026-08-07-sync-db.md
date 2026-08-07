# 코드 리뷰 보고서 — 서버 비동기 · 메시지 잘림 · DB · 저장 꼬임 (2026-08-07, v0.14.1)

**v0.14.1(`d7b1606`)** 기준으로 사용자 지정 4관점(서버 비동기 / 메시지 잘림·유실 / DB 관련 /
저장 꼬임)을 집중 점검한 결과. v0.11~v0.14 신규 코드(시나리오 뷰어·TXT/PDF 내보내기·
상단 바 개편)는 이번이 첫 리뷰다. **다른 세션에서 이 문서만 보고 수정 작업을 진행할 수
있도록** 항목마다 위치·재현·수정 방향을 명시한다. 라인 번호는 `d7b1606` 기준.
항목 번호는 이전 라운드(A~F)와 겹치지 않게 **G/H/I/K**를 쓴다.

## 총평

- **이전 라운드(2026-08-02 A~F) 반영분 전수 재검증 완료** — A3·A4·A5·A6·B2·B3·B5·B6·
  E4·E13·E15·L3·L4·M1·N5·V6 전부 현재 코드에 올바르게 살아 있다. 스레딩 규약(상태는
  Main, 파일은 IO)과 멱등 파이프라인은 이번 정독에서도 견고했고, **본문을 자르는 길이
  캡은 파이프라인 어디에도 없음**을 확인했다(메시지 잘림 = 유실 문제이지 절단 문제가 아님).
- **즉시 수정 4건(G)** — ① 시나리오 뷰어가 Android 13 미만에서 **즉사 크래시**(API 33
  메서드 사용), ② 데스크톱 `syncAt`이 로컬 시계라 **시계 오차만큼 메시지 씹힘·유실**
  (두 리뷰 라인이 독립적으로 동일 결론 — 이번 라운드 최대 리스크), ③ 모바일 리스너가
  PERMISSION_DENIED 외 오류로 죽으면 재시작까지 동기화 무증상 정지, ④ 판정 연타 시
  결과 2건 삽입.
- **저장 꼬임은 저확률·자가회복 성격이 대부분**(config.json 세대 역전, 아바타 tmp 경합,
  RoomCache EDT 쓰기) — 실사용 파손 위험은 낮지만 각각 몇 줄로 닫힌다.
- **신규 코드 품질은 양호**하나 내보내기 계열에 수명 관리 구멍 2건(화면 이탈 시 깨진
  파일, WebView 누수)과 시나리오 뷰어의 무통보 잘림이 있다.

권장 수정 순서: **G1 → G2(+H2 같은 시계 계열) → G3 → G4 → H1 → K1·K2·K5 → 나머지 H·I·K**.
G1·G4·K5는 각각 몇 줄이라 한 커밋에 묶어도 된다.

---

## G — 즉시 수정 (실질 버그)

### G1. [크래시] 시나리오 뷰어가 Android 13 미만에서 NoSuchMethodError로 즉사

- **위치**: `app/src/main/java/com/pbp/app/data/ScenarioFetcher.kt:69` —
  `stream.readNBytes(MAX_BYTES)`.
- **재현**: `InputStream.readNBytes(int)`는 **Android API 33 추가** 메서드. `minSdk = 26`
  (app/build.gradle.kts:15)이고 coreLibraryDesugaring 미설정(전체 확인됨). API 26~32
  기기의 GM이 링크를 넣고 확인을 누르는 순간 `NoSuchMethodError`(Error라
  `catch (IOException)`에 안 걸림) → 앱 크래시.
- **수정**: 8KB 버퍼 수동 루프로 교체 — `ByteArrayOutputStream`에 `read()` 반복, 누계가
  `MAX_BYTES`에 닿으면 중단. desugaring 활성화보다 이 교체가 싸다. **K3(잘림 안내)과
  같은 함수라 함께 수정할 것.**

### G2. [높음] 데스크톱 syncAt이 로컬 시계 — 시계 오차만큼 상대 메시지 씹힘, 10분 넘으면 영구 유실

- **위치**: `desktop/.../RoomSync.kt:40`(messageValues)·`:69`(systemMessageValues),
  `desktop/.../data/Firestore.kt:276-281`(ServerTime 인코딩)·`:683`(updateMessage) —
  전부 `System.currentTimeMillis()`를 timestampValue로 인코딩(이름과 달리 서버 변환
  아님). 커서 전진은 `Main.kt:331-333`(`fetched.maxOf { it.syncAt }`).
- **재현**: 모바일 syncAt은 진짜 서버 시각(`FieldValue.serverTimestamp()`)인데 데스크톱만
  로컬 시계다. PC 시계가 N초 빠르면 데스크톱이 전송할 때마다 폴 커서가 서버 시각보다
  N초 앞으로 점프 → 활성 폴 윈도는 5초뿐이라 **N>5초면 그 사이 도착한 상대 메시지가
  증분 질의에서 통째로 빠진다**. 10분 레거시 스윕이 주워 오기 전까지 수신 정지, 시계
  오차가 10분을 넘으면 스윕도 놓쳐 **영구 유실**. 시계가 느리면 자기 발신분이 자기
  화면에 안 뜬다. 오염된 커서는 `RoomCacheStore`에 영속된다. `RoomSync.kt:38-39`의
  "둘이 같은 의미다(V1)" 주석은 시계 정확 가정 위에서만 참.
- **수정(권장 ②, 보강으로 ① 병행 가능)**:
  ① `runQuery` 응답 각 결과의 `readTime`으로 커서 상한 클램프 —
  `lastCreatedAt = min(maxSyncAt, readTime)`.
  ② 쓰기를 `documents:commit` 엔드포인트로 바꿔
  `updateTransforms: [{fieldPath:"syncAt", setToServerValue:"REQUEST_TIME"}]`로 서버
  시각 기록(쓰기 1회, 과금 동일). postMessage·systemMessage·updateMessage 세 경로 모두.
  이후 데스크톱 시계는 커서 계산에서 완전히 빠진다.

### G3. [높음] 모바일 메시지 리스너가 PERMISSION_DENIED 외 종단 오류로 죽으면 재시작까지 동기화 무증상 정지

- **위치**: `app/.../sync/SyncManager.kt:719-726`(메시지 리스너 error 콜백),
  `:883-886`(방 문서 리스너 — 로그만 찍음).
- **재현**: Firestore 리스너는 오류 1회로 등록이 종료된다(SDK 계약). 현재 복구는
  `PERMISSION_DENIED → recoverAuth`뿐이라 `RESOURCE_EXHAUSTED`(무료 쿼터 소진)·
  `INTERNAL`·`FAILED_PRECONDITION` 등이 오면 리스너 영구 사망 — **FCM 알림은 오는데
  열면 메시지가 없는** 상태로, 앱 재시작까지 지속. `observePeerState`는 B7 반영으로
  `retryWhen` 재구독하는 것과 대조적.
- **수정**: 오류 코드 무관 "리스너 오류 = 종단"으로 취급하는 `recoverListener`(백오프
  재attach)로 일반화하고 재인증(`authUid = null`)은 PERMISSION_DENIED 분기에만.
  `attachRoomDoc`도 같은 경로에 합류.

### G4. [중간] 판정 카드 연타 시 다이스 결과 2건 삽입·전파 — check-then-insert 비원자

- **위치**: `app/.../data/PbpRepository.kt:268`(`hasJudgeResult`) ↔ `:291`(insert),
  호출부 `ChatScreen.kt:174`(`viewModelScope.launch` — 탭마다 코루틴).
- **재현**: 더블탭이면 두 코루틴이 모두 `hasJudgeResult=false`를 통과해 각각 insert →
  결과 2건이 로컬에 남고 둘 다 상대에게 push. `:267` "연타로 두 번 들어와도 결과는
  1건" 주석이 코드로 보장되지 않는다.
- **수정**: 검사+insert를 `db.withTransaction { }`으로 묶기(최소 수정). 또는
  `(roomId, judgeRef)` 유니크 인덱스 + IGNORE insert(기존 remoteId 패턴과 동형 —
  단 스키마 마이그레이션 필요).

---

## H — 서버 비동기·전파

### H1. [높음] 폴링 본선 runQuery가 토큰 무효화-재시도 보호 밖 — 폴링 최대 1시간 조용히 정지

- **위치**: `desktop/.../data/Firestore.kt:647-657`(`listMessagesSince` — `http.send`
  직접 호출), `:344-354`(`legacyFindRoomByCode`) — 둘 다 `sendWithRetry`(:210-217)를
  타지 않는다. 가장 빈번한 요청이 정작 보호 밖.
- **재현**: 서버가 로컬 만료 전에 토큰을 거부하면(절전 복귀 후 NTP 역보정 등) 401이
  와도 `invalidateToken()`이 안 불려 죽은 토큰으로 매 폴 실패 → 커서 미전진 →
  **수신만 하는 쪽은 자연 만료(최대 1시간)까지 무증상 정지**. 전송은 sendWithRetry라
  멀쩡해서 비대칭.
- **수정**: 두 경로를 `sendWithRetry { ... }`로 교체(이미 빌더-람다 패턴이라 기계적).

### H2. [높음] logsClearedAt 필터가 작성자 시계(createdAt) 비교 + 60초마다 영구 재적용 — 리셋 직후 메시지 증발

- **위치**: `desktop/.../Main.kt:367-375`.
- **재현**: 리셋 기기 시계로 찍힌 `clearedAt`과 각 메시지의 **작성 기기 시계**
  (`createdAt`)를 비교하고, 방 문서의 `logsClearedAt`은 영구 잔존이라 60초 메타 폴마다
  재적용된다. 리셋한 폰 시계가 2분 빠르면 리셋 후 2분간 상대가 보낸 메시지는 폴로
  받았다가 다음 메타 폴에서 반복 삭제(깜빡 떴다 사라짐) — 화면·세션·파일 캐시 전부.
- **수정**: 세션에 `lastSeenClearedAt`을 두고 **값이 바뀐 회차에만 1회 적용**, 비교
  기준도 syncAt(서버 시각)으로 통일. G2를 먼저 하면 후자가 자연히 안전해진다.

### H3. [중간] IO 스레드 → Compose 상태 콜백 잔존 (E4 잔여) — 전송 실패 콜백이 입력창과 경합

- **위치**(콜백이 IO에서 호출됨): `Main.kt:466` `onResult` → `ChatPane.kt:953-968`이
  `input`/`oocOn`/`errorMessage`를 IO에서 읽고-쓰기. `Main.kt:504` `onDone` →
  `Overlays.kt:436-441`. `Main.kt:914` `onFail` → `Overlays.kt:233`. 같은 부류:
  `ProfileOverlays.kt:146-153`·`:620-627`, `Overlays.kt:379-386`(이미지 픽커 결과),
  `Main.kt:573-583`(캡처 프리페치의 `avatarCache[id]=` IO 쓰기).
- **재현**: `ChatPane.kt:955`의 `if (input.isEmpty())` 판정과 사용자가 치는 중인
  `input` 갱신이 두 스레드에서 교차하면 방금 친 글자가 실패 복원 텍스트에 덮일 수 있다.
  코드베이스 자체 규약(E4·M2 "상태 변경은 UI 스코프")과 불일치.
- **수정**: 콜백 호출부를 `withContext(Dispatchers.Main) { onResult(...) }`로 감싸기
  (sendMessage·resetRoomLogs·onJoin·이미지 픽커 결과 반영·프리페치 캐시 쓰기).

### H4. [중간] resetLogs가 reattach를 로컬 삭제보다 먼저 — wipe 생존 메시지가 창에서 로컬만 소실

- **위치**: `app/.../data/PbpRepository.kt:354`(reattach) → `:357`(deleteForRoom).
- **재현**: detach~wipe 사이 상대 발신분이 reattach 초기 스냅샷으로 삽입됐다가 `:357`
  `deleteForRoom`에 지워지면 서버엔 있고 내 로컬에만 없는 분기(재시작 attach에서 자가
  치유되긴 함).
- **수정**: `serverOk`일 때 deleteForRoom → notice 삽입 → **그 다음** reattach 순서로.

### H5. [낮음] attach/detach 잔여 TOCTOU 창 + attachRoomDoc은 세대 가드 없음

- **위치**: `SyncManager.kt:740`(세대 확인) ↔ `:745`(listeners 등록) 사이,
  `:880-882`(attachRoomDoc — attach 동기 구간 등록, 가드 없음).
- **재현**: 밀리초 창에 detach가 끼면 맵 밖 유령 리스너(read 과금 누수, 재시작까지
  지속). 확률 낮음.
- **수정**: `:745` 직후 세대 재확인 → 어긋나면 즉시 `registration.remove()`;
  attachRoomDoc 등록을 launch 안(세대 확인 뒤)으로. 근본적으로는 방별 Mutex 직렬화.

### H6. [낮음] joinRoom의 로컬 방 생성 ↔ setRemote 비원자 — 크래시 시 고아 방 + 재참여 중복 방

- **위치**: `SyncManager.kt:534-541`.
- **수정**: `createLocalRoom` 람다에 remoteId·inviteCode를 넘겨 `createRoom`의
  `db.withTransaction` 안에서 함께 기록.

### H7. [낮음] 원격 아바타 resolve 실패 시 그 메시지 아바타 영구 누락

- **위치**: `SyncManager.kt:806-812` — 최초 insert 직전 1회뿐, 실패 시 재시도 없음.
- **수정**: reconcile 완료 후 `senderImagePath IS NULL AND incoming=1` 최근분 한정
  재시도(또는 표시 시점 lazy-resolve).

---

## I — DB·저장 꼬임

### I1. [권장] exportSchema=false — 마이그레이션 10개인데 스키마 JSON이 없어 마이그레이션 테스트 불가

- **위치**: `app/.../data/AppDatabase.kt:14`.
- **수정**: `exportSchema = true` + KSP `room.schemaLocation` 설정, `schemas/` 커밋.
  이후 `MigrationTestHelper` 테스트 작성 가능.

### I2. [중간] 데스크톱 persist()의 IO 디스패치 순서 미보장 — config.json이 한 세대 되돌 수 있음

- **위치**: `desktop/.../Main.kt:192-197` — `replaceAndSnapshot`으로 json을 굳힌 뒤
  `scope.launch(Dispatchers.IO) { config.writeSnapshot(json) }`. `writeSnapshot`은
  `@Synchronized`지만 락 획득 순서는 launch 순서와 무관.
- **재현**: 연속 두 번의 persist에서 IO 워커 둘이 역순으로 락을 잡으면 옛 json이
  최종본. 다음 persist가 스스로 고치지만 그 전에 종료하면 마지막 변경(방 참여·프로필
  편집) 유실.
- **수정**: 스냅샷에 단조 증가 세대 번호를 붙여 구세대 쓰기를 스킵하거나, 쓰기를
  `Dispatchers.IO.limitedParallelism(1)`로 직렬화.

### I3. [낮음] logsClearedAt 정리 경로가 RoomCache 저장(직렬화+파일 쓰기)을 Main(EDT)에서 수행

- **위치**: `Main.kt:373` — 바로 위 스로틀 저장(:339)은 IO로 옮겨졌는데 이 한 줄만 잔존.
  수천 건 로그 방이면 UI 정지 체감. → `withContext(Dispatchers.IO) { ... }` 한 줄.

### I4. [낮음] 캡처 프리페치가 avatarsInFlight 가드 밖 + tmp 파일명 고정 — Windows에서 캐시 경합

- **위치**: `Main.kt:573-584` ↔ `ChatPane.kt:863-890`(동시에 같은 avatarId 가능),
  `DesktopImages.kt:138-140`(`"$avatarId.tmp"` 고정 → renameTo 공유 위반).
- **수정**: 프리페치도 `avatarsInFlight` 가드, tmp 파일명에 난수 suffix.

### I5. [낮음] 방 목록 미리보기가 MAX(id) 기준 — createdAt 정렬과 불일치

- **위치**: `app/.../data/Daos.kt:125-126`(`observeLastPerRoom`), 사용처
  `RoomListScreen.kt:87`. 백필처럼 "오래된 createdAt이 높은 id로" 들어오면 목록
  미리보기와 채팅 마지막 메시지가 어긋난다.
- **수정**: 방별 `createdAt DESC, id DESC` 1건 형태로(복합 인덱스가 이미 있어 비용 0).

### I6. [낮음] isForeground 카운터가 FCM 바인더 스레드에서 비-volatile 읽기

- **위치**: `app/.../PbpApp.kt:51-52` ↔ `notify/FcmService.kt:25`. → `@Volatile` 한 줄.

### I7. [위생] 스키마 소소 2건

- `AppDatabase.kt:105-118` — MIGRATION_9_10과 10_11의 doc 주석이 서로 뒤바뀌어 있음.
- `Entities.kt:85` — `Index("roomId")`는 복합 인덱스 `(roomId, createdAt, id)`의
  접두라 중복(쓰기 비용만). 다음 스키마 변경 때 제거.

---

## K — 내보내기·시나리오 뷰어 (v0.11~v0.14 신규 코드)

### K1. [높음] 내보내기(TXT/HTML/PDF)가 viewModelScope — 화면 이탈 시 부분 기록된 깨진 파일

- **위치**: `app/.../ui/roomsettings/RoomSettingsScreen.kt:93-137`(`exportTo`).
- **재현**: SAF로 파일 선택 후 내보내기 중 설정 화면을 벗어나면 코루틴 취소 — 특히
  PDF는 suspend 지점이 많아 깨진 파일이 남고 완료 토스트도 안 뜬다. 같은 파일의
  `resetLogs`(:143)는 정확히 이 이유로 `runInAppScope`를 쓴다.
- **수정**: `exportTo`도 `app.syncManager.runInAppScope` 패턴으로(N1과 동일).

### K2. [높음] PdfExporter가 WebView를 destroy하지 않음 — 내보내기마다 누수

- **위치**: `app/.../export/PdfExporter.kt:41-55, 58-72` — 성공·실패·취소 어느 경로에도
  `destroy()` 없음, 실패 시 `adapter.onFinish()`도 건너뜀.
- **수정**: `try/finally`로 `webView.stopLoading(); webView.destroy()` (Main 스레드라
  그대로 가능), 실패 경로에서 adapter 정리.

### K3. [중간] 시나리오 1MB 상한에서 무통보 잘림 + UTF-8 문자 중간 절단 + 5xx를 "권한 없음"으로 안내

- **위치**: `ScenarioFetcher.kt:45, 63, 69`.
- **재현**: 1MB에서 조용히 잘리고 안내가 없다. 멀티바이트 한글이 경계에서 잘리면 마지막
  문장에 U+FFFD 잔존. `responseCode !in 200..299 → NO_ACCESS`는 5xx·429에도 "공유
  설정을 확인하라"는 엉뚱한 안내.
- **수정**: `MAX_BYTES+1` 읽어 초과 판별 → "문서가 커서 일부만 표시합니다" 안내, 꼬리의
  불완전 UTF-8 시퀀스 제거, 5xx·429는 NETWORK로 분기. **G1과 같은 함수 — 한 커밋으로.**

### K4. [중간] ScenarioState.Viewing.pages가 get() — 접근마다 문서 전체 재조립

- **위치**: `ChatScreen.kt:284` — 복사 버튼·본문·NavRow·scenarioStep이 각각 읽을 때마다
  chunked+joinToString 재생성. 1MB 문서면 리컴포지션당 ~1MB 문자열 수 벌 할당.
- **수정**: pages를 Viewing의 저장 필드로(로드·설정 변경 시에만 계산) 또는
  `remember(sentences, linesPerView)` 캐시.

### K5. [중간] 본문이 "공백만 든 따옴표"인 GM 메시지 — 화면·모든 내보내기에서 통째로 소실

- **위치**: `shared/.../GmSpeech.kt:19-33`(split이 빈 리스트 반환) — 소비처
  `MessageBlock.kt:288-317`, `LogExport.kt:74-98·112-114`,
  `MessageBlock.kt:761-764`(renderedPartCount는 1로 세어 캡처 인덱스에 유령 조각).
- **재현**: 본문 `" "`는 sendMessage의 trim을 통과해 저장되지만 화면에 아무것도 안
  그려지고 HTML/PDF에서 해당 메시지가 완전히 빠진다.
- **수정**: `split()` 끝에 `if (parts.isEmpty() && text.isNotBlank())
  parts += Narration(text.trim())` 한 줄 + :shared 테스트 1건.

### K6. [기록만] ProfileDrawer 퇴장 애니메이션 데드 코드

- `ProfileDrawer.kt:58, 67-68` — `if (!visible) return` 뒤 `AnimatedVisibility(visible =
  true)`라 퇴장 애니메이션이 실행될 수 없음. 동작 문제는 없어 기록만.

---

## 잔존·수용 확인 (재보고 아님)

- 개별 메시지 삭제의 데스크톱 전파 없음 — A6에서 "이번엔 로그 초기화만"으로 의식적 유보, 현행 동일.
- syncAt 없는 구버전 문서가 커서보다 10분 이상 과거로 커밋되면 스윕도 놓침 — B6에서 10분 경계로 수용.
- 리셋 중 도착한 상대 메시지의 60초 내 블립 — 자가 해소 확인.

## 검증

- 수정 후: `gradlew assembleDebug testDebugUnitTest :shared:test` 통과.
- G1·K3은 **API 33 미만 실기기/에뮬레이터**에서 시나리오 불러오기 1회 확인
  (검증 후 에뮬레이터 즉시 종료).
- G2는 데스크톱 시계를 의도적으로 ±5분 틀어 놓고 모바일↔데스크톱 송수신으로 재현·확인.
