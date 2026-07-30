# 코드 리뷰 보고서 3차 (2026-07-29) — 크래시 · 로그 보존 · 동기화

> **처리 결과 (2026-07-29 반영)**: L1~L4, L6, S1~S6, C1~C3 수정 완료. 배포 예고 2건(재인증
> 백오프, 데스크톱 mine 판정 통일)도 선반영.
> - **L5 (오프라인 편집/삭제)**: 한계로 수용 — docs/architecture.md "알려진 한계"에 문서화.
> - **S7 (데스크톱 편집/삭제 반영 범위)**: 한계로 수용 — 같은 문서에 기록 (주기 재조회는 read 과금 문제로 보류).
> - **S3**: 이 문서 작성 시점과 반영 시점 사이 커밋(caba79d)에서 이미 수정됨 (messages.last 기준).

2차 수정 커밋 `f843546`(R1~R5, N1~N10, 클린업 반영)까지 적용된 main 최신 기준으로,
**크래시 위험 · 로그 보존(메시지 유실/중복/기기 간 분기) · 동기화 정합성** 세 관점에 집중한 재점검 결과.
**다른 세션에서 이 문서만 보고 수정 작업을 진행할 수 있도록** 항목마다 위치·재현 시나리오·수정 방향을 명시한다.
기준 커밋: `f843546`. 라인 번호는 이 커밋 기준.

**범위 제외**: P0-1 잔여 수동 작업(콘솔에서 익명 로그인 활성화 · 규칙 배포 · API 키 제한)은 아직 미진행 상태다.
따라서 **규칙이 배포되어야만 나타나는 문제는 이 문서에서 제외**했고, 현재 현실(규칙 미배포·인증 미활성)에서
실제로 발생 가능한 것만 담았다. 배포 시점에 함께 처리할 2건은 말미 "배포 시 함께 볼 것"에 예고로만 남긴다.

권장 수정 순서: **L1 → L2 → C1 → L4 → 나머지 L → S → C → 낮음**.

---

## L — 로그 보존 (유실·중복·분기)

### L1. 로그 초기화의 "실패 시 로컬 보존" 보장이 자기 자신의 리스너에 의해 깨짐 — 부분 삭제가 영구화

- **위치**: `app/src/main/java/com/pbp/app/data/PbpRepository.kt:181-200`(resetLogs) ×
  `app/src/main/java/com/pbp/app/sync/SyncManager.kt:269-279`(wipeMessages) ×
  `:461-463`(REMOVED 핸들러)
- **원인**: `wipeMessages`가 450건 배치로 서버 문서를 지우는 동안, **초기화를 실행한 기기 자신의 attach된
  리스너가 커밋된 배치마다 REMOVED 이벤트를 받아 `deleteByRemoteId`로 로컬을 즉시 삭제**한다.
  resetLogs의 "serverOk 아니면 로컬은 건드리지 않는다" 로직은 이 경로를 막지 못한다.
- **재현 A (확실)**: 1,000건 공유 방에서 초기화 → 배치 1(450건) 커밋 후 네트워크 단절 → 배치 2 실패 →
  토스트는 "서버 삭제에 실패해 초기화를 취소했습니다"인데 450건은 이미 서버·상대·로컬 모두에서 영구 삭제.
  재시도/재개 경로 없음.
- **재현 B (오프라인, 더 나쁨)**: Firestore 쓰기는 타임아웃이 없어 오프라인이면 `batch.commit().await()`가
  무한 대기(NonCancellable이라 토스트도 안 뜸). 지연 보상 REMOVED 이벤트(삭제 문서는 보통
  `hasPendingWrites`로 걸러지지 않음)가 로컬만 먼저 지운 상태에서 프로세스가 죽으면: 서버·상대에는 전부
  남고 **내 작성분만 로컬에서 소실**(재수신 ADDED는 본인 작성자 필터 `:422`에 걸리고, reconcile 기준선에도
  이미 없어 복구 불가).
- **수정 방향**: resetLogs에서 `detach(roomId)` → `wipeMessages` → (성공/실패 무관) 재`attach` 순서로 변경.
  재attach의 reconcile이 서버 상태 기준으로 올바르게 수렴한다(전부 지워졌으면 로컬 정리, 부분이면 남은 것 유지).
  부분 실패 시 "일부만 삭제되었습니다"를 정직하게 알리고 재시도 버튼 제공(또는 pending-wipe 플래그를 저장해
  `start()`에서 재개).

### L2. 참여/공유 버튼 더블탭 가드 부재 — 방 분열·메시지 중복

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:230-263`(joinRoom),
  `app/src/main/java/com/pbp/app/ui/roomlist/RoomListScreen.kt:463`(참여 버튼 — 진행 중 비활성화 없음),
  `app/src/main/java/com/pbp/app/ui/roomsettings/RoomSettingsScreen.kt:253-263`(share 행 — 매 탭 호출)
- **재현 (참여)**: "참여" 더블탭(또는 느린 1차 시도 중 재탭) → 두 코루틴 모두 `findByInviteCode == null`
  통과 → **같은 원격 방에 로컬 방 2개 + 리스너 2개** 생성. `messages.remoteId`가 글로벌 유니크 인덱스라
  수신 문서가 두 방 중 경쟁에서 이긴 쪽에만 삽입(진 쪽은 insert IGNORE → -1) → 양쪽 방에 히스토리가
  임의로 갈라져 들어가는 영구 분열.
- **재현 (공유)**: 미공유 방에서 더블탭 → 원격 방 문서 2개 + 초대코드 2개, 두 번째 `setRemote`가 로컬을
  덮어써 다이얼로그의 코드가 고아 문서를 가리킬 수 있음(R2가 고치려던 "죽은 초대코드" 재현).
  이미 공유된 방에서 더블탭 → 백필 2개가 동시에 `listUnsent`를 읽어 같은 메시지에 서로 다른 원격 문서를
  생성 → 상대 화면에 미전송분 전부 중복.
- **수정 방향**: joinRoom/shareRoom에 단일 실행 가드 — SyncManager 안에 roomId(또는 코드) 키의
  Mutex/in-flight 맵, UI에서는 진행 중 버튼 비활성화(VM에 `joining`/`sharing` 플래그).
  join은 방 문서 해석 후 `findByRemoteId` 재확인도 추가.

### L3. 공유 백필 × 동시 전송의 remoteId 재발급 레이스 — 서버에 같은 메시지 2건

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:209-223`(백필: 스테일 `remoteId == null`
  스냅샷을 읽고 무조건 `setRemoteId`) vs `:294-307`(pushMessage가 동시에 같은 일을 함)
- **재현**: 이미 공유된 방에서 "방 공유" 재탭(R2의 의도된 멱등 복구 경로). 직전에 보낸 메시지가
  pushMessage 진행 중(문서 A 생성, uploaded 미설정)일 때 백필의 `listUnsent`가 그 메시지를 보고
  메모리 사본의 `remoteId = null`이므로 문서 B를 발급·커밋 → 서버에 A·B 공존, 상대는 remoteId가 달라
  dedup 없이 둘 다 삽입. `start()`의 아웃박스 루프와 동시 전송 사이에도 같은 레이스 존재.
- **수정 방향**: remoteId 선점을 원자화 — `UPDATE messages SET remoteId = :rid WHERE id = :mid AND
  remoteId IS NULL`(영향 행 0이면 스킵) 형태의 DAO 쿼리로 교체하고, 백필·pushMessage 모두 이 경로 사용.

### L4. R3 잔여 밀리초 레이스 — 기준선 조회 전에 리스너가 먼저 등록됨 (5줄 재배치)

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:368-371`(기준선 조회가 consumer 코루틴
  안에서 IO로 실행) vs `:386`(리스너 등록은 호출 스레드에서 즉시)
- **재현**: 재attach(recoverAuth)나 attach와 동시 전송이 겹칠 때, 리스너 등록(첫 서버 스냅샷 시점 확정)과
  기준선 조회 사이 밀리초 틈에 어떤 메시지의 서버 커밋+`setUploaded`가 끼면 — 기준선에는 있고 첫 스냅샷에는
  없어 reconcile이 로컬 삭제. 이후 ADDED는 본인 작성자 필터에 걸려 복구 불가. (R3와 같은 클래스, 창만 좁아짐)
- **수정 방향**: 같은 코루틴 안에서 **기준선 조회 → 리스너 등록** 순서로 재배치. 이벤트는 UNLIMITED 채널에
  쌓이므로 등록을 코루틴 안으로 옮겨도 유실 없음.

### L5. 오프라인 편집/삭제는 재시도 없음 — 조용한 영구 분기 (설계 결정 필요)

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:326-348`(pushEdit/pushDelete — 실패 시
  로그만), `app/src/main/java/com/pbp/app/data/PbpRepository.kt:150-169`
- **현상**: 전송(신규 메시지)은 `uploaded=0` 아웃박스로 재시도되지만 편집·삭제는 실패하면 끝. 로컬 반영은
  이미 됐으므로 기기 간 분기가 영구화된다(본인 재수신은 필터, 삭제분은 기준선에 남아 reconcile도 못 고침).
- **수정 방향**: 편집/삭제용 pending 플래그(예: `pendingOp` 컬럼) + `start()` 재시도. 또는 "오프라인
  편집/삭제는 상대에게 반영되지 않을 수 있음"을 한계로 문서화하고 수용 — 어느 쪽인지 결정만 명확히.

### L6. UI 텍스트 소실 2건 (작성 중 내용 보호)

- **편집 다이얼로그**: `app/src/main/java/com/pbp/app/ui/chat/ChatScreen.kt:190, 389-399` —
  `editTarget = messages.find { it.id == editTargetId }`가 매 리컴포지션 재해석되므로, 수정 작성 중 상대가
  그 메시지를 삭제(또는 로그 초기화)하면 다이얼로그가 무통보로 사라지며 작성 내용 소실. 프로세스 사후 복원
  시 페이지가 200으로 리셋되어 저장된 id가 로드 범위 밖이면 역시 소실. → 대상이 사라지면 작성 중이던
  텍스트를 입력창으로 옮기거나 토스트로 알림.
- **전송 경로**: `ChatScreen.kt:124-128, 837-841` — `doSend`가 입력을 먼저 비우고, 코루틴에서 활성 프로필
  조회가 null이면 `return@launch`로 텍스트가 조용히 버려짐(프로필 삭제 직후 전송하는 좁은 레이스).
  → 해당 경로에서 입력 복원 또는 토스트.

---

## S — 동기화

### S1. 데스크톱: 인증 미활성 상태에서 매 REST 호출마다 실패할 signUp을 락 안에서 재시도 — 전 트래픽 직렬화

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/data/Firestore.kt:74-93`(currentToken — 실패
  네거티브 캐시 없음), `:163-166`(auth 헤더 생략 폴백)
- **현상**: 다행히 토큰이 없으면 Authorization 헤더를 생략하고 무인증으로 진행하므로 **막히지는 않는다**
  (검증 완료). 그러나 실패가 캐시되지 않아 2.5초 폴링·전송·아바타 fetch 각각이 identitytoolkit 왕복을
  한 번씩 더 하고, 이것이 `tokenLock` 안에서 실행되어 **모든 요청이 인증 시도 뒤로 직렬화**된다.
  identitytoolkit이 느리거나 블랙홀이면(연결 10s/요청 30s 타임아웃) 전송 클릭이 폴링의 락에 막혀 수십 초
  지연 → 늦은 실패 콜백이 N3 타이핑 가드와 겹치며 텍스트를 잃을 수 있다. 지속 signUp 스팸은 429 위험도.
- **수정 방향**: 인증 실패를 재시도 금지 시각과 함께 네거티브 캐시(예: 60초). 인증 HTTP는 락 밖에서 수행.

### S2. 데스크톱: 방 입장 시 최신 메시지로 스크롤되지 않음 (C9 통일 과정의 회귀)

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:542-546`
- **재현**: 방 전환 시 `messages`가 `[]` → N으로 바뀌는데, 직전(빈) 레이아웃 기준 `lastVisible = -1`이라
  `-1 >= N-2`가 false(N≥3) → 스크롤 안 함. 데스크톱 목록은 Android와 달리 **가장 오래된 메시지가 맨 위**라,
  방을 열 때마다 히스토리 맨 위에서 시작한다. 이전 커밋까지는 항상 하단 강제 스크롤이었으므로 명백한 회귀.
- **수정 방향**: 방별 "첫 비어있지 않은 로드" 플래그를 두고 그때는 무조건 최하단으로. 함께: 본인 전송 시
  스크롤(Android N4의 절반)도 미이식 — 폴링으로 도착한 마지막 메시지의 `authorUid`가 본인이면 스크롤
  (또는 전송 성공 시 sendTick).

### S3. Android: 자동 스크롤 키가 최신이 아니라 가장 오래된 메시지 (2차 검증 오판 정정)

- **위치**: `app/src/main/java/com/pbp/app/ui/chat/ChatScreen.kt:205` vs
  `app/src/main/java/com/pbp/app/data/Daos.kt:82-85`
- **현상**: `observeLatestForRoom`은 **오름차순**(오래된 것부터) 반환이므로 `messages.firstOrNull()?.id`는
  주석("키는 최신 메시지 id만")과 반대로 가장 오래된 항목이다. 새 메시지가 와도(윈도 미포화 시) 키가 안 바뀌어
  이펙트가 재발화하지 않고, 200건 포화 후에는 반대로 오래된 항목 탈락 때마다 발화한다. 바닥 고정 시에는
  LazyColumn의 index-0 특례로 증상이 가려져 테스트에서 안 보였을 것.
- **수정 방향**: 키를 `messages.lastOrNull()?.id`로 교체. (직접 확인 완료 — 실제 코드/쿼리 대조)

### S4. attach()/attachRoomDoc() 확인-후-등록 레이스 — 리스너 중복 등록·누수

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:362`(containsKey 확인)과 `:386`(등록) 사이,
  같은 패턴 `:497`
- **재현**: `start()`(앱 스코프)가 느린 아웃박스 처리 중일 때 사용자가 방 설정에서 공유 탭 →
  shareRoom(viewModelScope)의 attach가 동시 진입 → 둘 다 containsKey 통과 → 리스너 2개·consumer 2개,
  맵에는 두 번째만 남아 첫 번째는 detach 불가(영구 누수, 이중 스냅샷 처리 — 삽입은 유니크 인덱스로 막히지만
  리스너는 계속 돈다).
- **수정 방향**: `putIfAbsent`로 자리 선점 후 실패 시 반환(플레이스홀더 패턴), 또는 attach/detach를 뮤텍스로.

### S5. start()의 아웃박스 직렬 처리 — 오프라인 시작 시 전체 방 attach 무기한 지연

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:133-144`
- **재현**: 미전송 메시지를 가진 채 오프라인으로 앱 시작 → `pushMessage`의 `set().await()`는 오프라인에서
  해소되지 않음(쓰기 타임아웃 없음) → 그 방의 attach는 물론 **이후 모든 방의 attach·memberFix·FCM 토큰
  등록이 연결 복구까지 전부 보류**. 또한 전체를 감싼 단일 runCatching이라 진짜 실패 1건이 그 방의 나머지
  아웃박스를 다음 시작까지 스킵(C7의 건별 패턴과 불일치).
- **수정 방향**: 방별 `launch` + 메시지별 runCatching(push()와 동일 패턴). attach는 아웃박스와 독립적으로
  진행하되 L4의 기준선 순서를 지킬 것.

### S6. 스냅샷 중간 예외 시 나머지 문서가 조용히 소실되고 reconcile 기회도 소진

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:375-383`
- **재현**: 첫 스냅샷의 ADDED 루프 중간에 DB 예외 → 그 스냅샷의 나머지 문서는 리스너가 재전달하지 않아
  다음 attach까지 로컬에 없음. 그런데 `needReconcile`은 이미 false가 되어 이번 세션의 오프라인 삭제 정리도
  건너뜀. (방향은 안전 — 잘못 지우지는 않음 — 하지만 무통보)
- **수정 방향**: `processSnapshot`이 성공했을 때만 `needReconcile = false`. ADDED 루프는 문서별 runCatching.

### S7. 데스크톱 편집/삭제 반영의 설계 한계 명시 (C10 후속)

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:143-154`
- **현상**: 편집 업서트는 정상(중복 없음 — 검증 완료)이나, 30초 윈도보다 오래된 메시지의 편집은 데스크톱에
  영영 반영되지 않고, 삭제는 방 재입장 전까지 보이지 않는다. 활성 대화 중 과거 메시지를 고치는 TRPG 사용
  패턴상 체감될 수 있음.
- **수정 방향**: 주기적(예: 60초) 전체 재조회 1회로 편집·삭제를 따라잡거나, 한계로 문서화·수용 결정.

---

## C — 크래시

### C1. 데스크톱 `config.rooms` 동시 변경 — CME로 앱 크래시 또는 코루틴 스코프 사망(전송 전면 무반응)

- **위치**:
  - `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:103-107` — 시작 시 `config.rooms.forEach { runCatching { ensureMember } }`가
    **라이브 리스트**를 IO에서 순회. runCatching은 본문만 감싸고 순회는 못 감싼다. S1 때문에 ensureMember가
    수 초씩 걸려 창이 크다 — 그 사이 UI 스레드의 `persist()`가 `rooms.clear()/addAll()`을 하면 CME →
    LaunchedEffect 밖으로 전파 → 앱 크래시.
  - `Main.kt:270-287, 292-311` — 참가/생성 오버레이 핸들러가 `scope.launch(Dispatchers.IO)` 안에서
    `persist()` 호출: "직렬화는 호출(UI) 스레드에서"라는 N8 전제를 위반. 폴 루프의 메타 변경 경로(UI 스레드,
    `:165`)와 겹치면 `snapshot()`의 `rooms.toList()`와 clear/addAll이 교차 → IO 워커에서 CME →
    `rememberCoroutineScope`의 Job 취소 → **이후 모든 send/persist/join이 조용히 무반응**.
- **수정 방향**: ① 시작 루프는 `config.rooms.toList().forEach`. ② 변경+스냅샷을 원자화 —
  예: `AppConfig`에 `@Synchronized fun replaceAndSnapshot(rooms, profiles): String`을 두고 persist는
  그 반환 JSON만 IO에서 쓰기. 오버레이 핸들러는 변경을 Main 스레드로 홉.

### C2. 데스크톱 폴 루프에 파싱 예외 무방비 — 기형 응답 1건에 방 폴링 영구 정지

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/data/Firestore.kt:253`(`docId`의 `get("name").asString` —
  필드 부재 시 NPE), `:377`(`asJsonObject` 캐스트), `:409-411`(`mapNotNull` 내 캐스트) — 모두 runCatching 밖.
  폴 LaunchedEffect(`Main.kt:138-170`)에도 try/catch 없음.
- **재현**: 프록시 오류 페이지·부분 응답 등 기형 JSON 1건 → 파싱 예외가 LaunchedEffect로 전파 → 해당 방
  폴링이 방 재선택까지 정지(최악 앱 크래시).
- **수정 방향**: 응답 파싱 전체를 `runCatching { ... }.getOrNull()`로 감싸 null 반환 — 기존 "null이면 커서
  미전진" 계약(P1-6)이 그대로 흡수한다.

### C3. 낮음 (크래시·소실 방어 소소 2건)

- `app/src/main/java/com/pbp/app/ui/chat/ChatScreen.kt:274` — `exportLauncher.launch`가 문서 프로바이더
  없는 기기에서 `ActivityNotFoundException` 가능. runCatching + 토스트.
- `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:877-899` — TEXT 실패 시 사용자가 이미 새 입력을 타이핑한
  경우 원본 텍스트가 복구 불가로 소실(가드는 정상 동작, 원문이 갈 곳이 없음). 에러 라인에 원문 포함 권장.

---

## 배포(P0-1 진행) 시 함께 볼 것 — 이번 범위에서는 제외, 예고

1. **재인증 루프 백오프 부재**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:476-493` — 규칙 배포 후
   permission-denied가 지속되면(예: 익명 로그인을 켜기 전에 규칙만 배포) 3초 간격 무한 detach/re-attach.
   지수 백오프 상한 추가.
2. **데스크톱 mine 판정 불일치**: `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:254, 659` — 전송
   authorUid는 `firestore.uid ?: deviceId`(C3 반영)인데 mine 비교는 여전히 `config.deviceId`. 익명 인증을
   켜는 순간 데스크톱 본인 메시지가 상대편(왼쪽)으로 렌더링된다. 비교를 `authorUid()`로 통일(한 줄).

---

## 검증 정상 (재확인 불필요)

- **R3 본체**: 아웃박스가 attach보다 먼저(`start()`), 기준선은 `uploaded=1`만 포함(미전송분은 reconcile
  삭제 불가), `SyncReconcile` 순수 함수 추출 + ReconcileTest 3케이스 — 남은 것은 L4의 밀리초 창뿐.
- **N1/N2 본체**: 앱 스코프 + NonCancellable, 서버 우선, `remoteId == null` 방 처리, 공지 메시지의 아웃박스
  재시도 — 남은 것은 L1의 리스너 간섭뿐. **R5** 삭제 순서, **N5** 재인증 단일 실행·이중 등록 없음,
  **N7** 동시성 컬렉션 전환(잔여는 S4의 확인-후-등록), **C6**(insert -1 시 알림 억제), **C7**(건별 runCatching).
- **데스크톱**: R1 아바타(무조건 이펙트 + 비스냅샷 in-flight, finally 정리 — fetch 1회·실패 재시도·누수 없음),
  R4(폐기 vs 일시 오류 구분, 예외는 TRANSIENT), C15(유효 토큰 시 락 프리, 401 1회 재시도), C12(pageToken
  인코딩), C11(중괄호 정리), C8(로드 IO 이동, 백업 유지), N3(빈 입력일 때만 복원, 다이스 실패 구분 — 중복
  전송 차단 확인), C10 업서트(fresh/edited 집합 분리로 중복 불가), P1-6 커서 규율 유지, 방 전환 시 스테일
  상태 유입 없음.
- **UI 크래시 체크리스트 전부 통과**: N10 스테일 id는 `find`라 no-op(크래시 아님), Lazy 키 유니크(팔레트
  distinct 포함), rememberSaveable 전부 Bundle-safe(listSaver 포함), N9 게이트(프로세스 사후 포함),
  ImageCrop(0나눗셈 없음·IO 스레드·중복 적용 방지), DiceBot 입력 방어, 색상 파싱 방어, export 실패 격리.
- **내보내기 충실도**: 전체(비페이징) 목록 사용, 이스케이프 완전, 편집 표시·OOC·다이스·GM 분리·날짜 구분
  보존 — 유실 경로 없음.

## 테스트 권고

- L1 수정 시: "부분 wipe 실패 → 재attach reconcile 수렴" 시나리오를 SyncReconcile 수준 단위 테스트로.
- L2/L3 수정 시: joinRoom/shareRoom 단일 실행 가드는 뮤텍스 도입 후 동시 호출 테스트(runTest 두 코루틴)로.
- S3 수정 시: 스크롤 키는 UI 테스트가 어려우므로 최소한 주석을 실제 동작과 일치시킬 것.
