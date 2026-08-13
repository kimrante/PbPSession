# 코드 리뷰 지시서 — 데스크톱 크래시·서버 폴링 (2026-08-15, HEAD 99d4235)

데스크톱 모듈에서 보고된 크래시(방금 고친 `Dispatchers.Main` 부재 = 코루틴 내부 조용한
사망)를 계기로, **데스크톱 전 소스(6,841줄)를 크래시·서버 폴링 관점으로 3회 루프 정밀
리뷰**한 결과. 각 항목을 라운드 간 교차·적대적 검증했다. **다른 세션이 이 문서만 보고
수정할 수 있도록** 위치·트리거·수정을 명시한다. 기준 커밋: `99d4235`. 라인 번호는 이 커밋 기준.

## 리뷰 방법 (3회 루프)

- **1회차** — Main·폴링 루프 / Firestore·UI·데이터 두 갈래 광범위 스윕.
- **2회차** — 1회차 후보를 실제 코드로 검증·심화 + 1회차가 스킵한 파일(ChatPane·Overlays·
  ProfileOverlays·RoomListPane·CaptureBar) 정독.
- **3회차** — 최상위 확정 건 적대적 재검증(반증 시도·수정안 안전성 확인) + 아직 안 본
  크래시/폴링 부류(창 수명·트레이 스레딩·ImageGc 경합·config 저장 순서·빠른 방 전환) 완결성 점검.

## 총평

- **고친 버그의 부류는 사실상 봉인됨.** `coroutines-swing` 의존성 + `MainDispatcherTest`로
  `Dispatchers.Main`이 못 박혔고, `FirestoreRest`의 모든 공개 메서드와 파일 IO 헬퍼가 내부
  `runCatching`으로 예외를 삼켜 null/false를 반환한다. 폴 루프 1회 전체가 `runCatching`으로
  격리돼 예외 1건이 폴링을 영구 정지시키지 않는다. **"IO에서 일하고 Main으로 복귀하다 조용히
  죽는" 남은 삼킴 사이트는 없다**(전 22개 `scope.launch` 본문 감사 완료).
- **그러나 확정 크래시 1건(P0)** — 참여 버튼 더블클릭이 LazyColumn 중복 키로 앱 전체를
  죽인다. 정상 사용자의 흔한 조작 하나로 재현되며 상류 가드가 전무하다.
- **확정 스톨 1건(P1)** — 기형/부분쓰기 문서 1건이 수신을 영구 정지시키는 배치 단위
  `runCatching`(두 곳). 현재 양 클라이언트가 정상 스키마로 쓰는 한 미발현이나, 발현 시 회복 불능.
- 나머지는 중복 생성·시계 스큐·구조적 취약성 등 P2와 무해 P3. **config 저장 순서 역전
  (직전 리뷰 I2)은 세대 번호로 이미 해결됐음을 확인**했다.

권장 수정 순서: **DC1(P0) → DC2(P1) → DC3·DC5 → DC4 → DC6·DC7 → P3 선택**.
DC3·DC6은 몇 줄이라 DC1과 한 커밋에 묶어도 된다.

---

## DC1. [P0, 확정] 참여 버튼 더블클릭 → 중복 remoteId → LazyColumn 키 충돌로 앱 전체 크래시

- **크래시 지점**: `desktop/.../RoomListPane.kt:137` — `items(rooms, key = { it.remoteId })`.
  중복 remoteId가 들어오면 컴포지션 중 `IllegalArgumentException("Key ... was already used")` →
  **하드 크래시**(runCatching 밖, 컴포지션 스레드).
- **트리거·경로**:
  - `desktop/.../Overlays.kt:256-273` `JoinOverlay` — `참여` 버튼(:269)과 엔터(:262)가 모두
    `onJoin`을 부르는데 **in-flight 가드가 전혀 없다**(`failed` 플래그만 있음).
  - `overlay = null`은 네트워크 왕복 끝(`Main.kt:1024`)에서만 세팅 → 왕복 수 초간 오버레이가
    열려 있어 재클릭 창이 넓다.
  - `onJoin`(`Main.kt:995`)이 `existing = rooms.find { it.remoteId == meta.remoteId }`를 **IO
    진입 시점에 캡처**하고, 마지막 `withContext(Dispatchers.Main)` 블록(:1020-1021)에서 그
    **캡처된 `existing`을 그대로 재사용**한다(`if (existing == null) rooms = rooms + joined`) —
    Main 블록에서 rooms를 재조회하지 않는다. 더블클릭 시 두 launch가 모두 `existing==null`을
    보고 둘 다 삽입 → 같은 remoteId 2개.
- **수정(두 가지 다 적용 — 3회차에서 둘 다 필요함을 확인)**:
  1. `JoinOverlay`에 `var joining by remember { mutableStateOf(false) }` — 진입 시
     `if (joining) return`·`joining = true`, 성공/실패 콜백에서 해제, 버튼 `enabled = !joining`.
     (두 번째 launch 자체를 막아 **중복 참여 인사·`consumeInviteCode` 중복 전송**까지 차단.)
  2. 삽입을 같은 Main 블록에서 재확인: `if (rooms.none { it.remoteId == meta.remoteId }) rooms = rooms + joined`
     (크래시 자체의 최종 방어). 참여 인사 postMessage도 이 재확인 뒤로 옮기면 중복 인사도 사라짐.
  - 정상 재참여(이미 가입된 방 코드) 흐름은 `existing != null` 경로가 처리하므로 수정이 깨지 않음.

## DC2. [P1, 확정(잠재)] 기형/부분쓰기 문서 1건이 수신을 영구 정지 — 배치 단위 runCatching

- **위치**: `desktop/.../data/Firestore.kt:709-714`(`listMessagesSince`)와 **`:648-654`
  (`listMessages`) 두 곳** — 배치 전체를 하나의 `runCatching { res.mapNotNull { ... } }.getOrNull()`로
  감싼다.
- **thrower 특정**: `parseMessage`가 쓰는 `str`/`bool` 헬퍼(`Firestore.kt:313-328`)의
  `getAsJsonObject("fields")?.getAsJsonObject(name)` — Gson의 `getAsJsonObject(member)`는 **무검사
  캐스트**라, 필드가 존재하되 Value 래퍼(`{stringValue:…}`)가 아닌 원시값/`JsonNull`이면
  **ClassCastException**을 던진다(부분 쓰기 중 크래시·스키마 드리프트로 발생 가능).
  `timestamp`/`long`은 내부 runCatching·`toLongOrNull`로 안전.
- **정지 메커니즘**: 그런 문서가 syncAt 쿼리 윈도에 있으면 → 매 폴이 예외 → 배치 전체 null →
  `Main.kt:305-306`의 "null=오류, 커서 미전진" 계약 → 문서가 영원히 윈도에 남음 →
  **수신 무증상 영구 정지**. 특히 최초 진입(cursor=0)은 `listMessagesSince`가 `listMessages`로
  위임(`:681`)하므로, 기형 문서 1건이면 **방 로드 자체가 매 폴 실패해 빈 방으로 고착**.
- **수정**: 문서 단위 runCatching으로 바꿔 나쁜 문서 1건만 skip하고 나머지·커서는 살린다 —
  ```kotlin
  res.mapNotNull { el ->
      runCatching {
          el.asJsonObject.getAsJsonObject("document")?.let { parseMessage(it) }
              ?.takeIf { it.docId.isNotEmpty() }
      }.getOrNull()
  }
  ```
  `listMessages`의 `forEach`도 동일하게. 네트워크/HTTP 오류는 여전히 상위(`:706-707`)에서 null로
  남아 커서 미전진 계약은 유지된다.
- **발생 확률**: 양 클라이언트가 정상 스키마로 쓰는 한 미발현(잠재). P1 등급 근거는 "발현 시
  영구 정지 + 초기 로드 고착"이라는 심각도.

## DC3. [P2, 확정] 방 만들기 버튼 더블클릭 → 서버·로컬에 방 중복 생성 (고아 방)

- **위치**: `desktop/.../Overlays.kt:280·288`(`CreateOverlay` 엔터·버튼, 가드 없음) →
  `Main.kt:1032-1057` `onCreate` → `firestore.createRoom`(:1035)이 매 호출마다 **새 remoteId 발급**.
- **결과**: 더블클릭 → 서로 다른 remoteId의 방 2개가 서버·로컬(`rooms = rooms + joined` 두 번)에
  생성. remoteId가 달라 **DC1 같은 키 크래시는 없고** 동일 이름 방이 목록에 둘 뜨는 중복 생성.
- **수정**: DC1과 동일한 `creating` in-flight 가드.

## DC4. [P2, 잠재 — 3회차에서 영향·수정 정정됨] 비-supervisor 스코프 + 방어 없는 thrower 1곳

- **위치**: `Main.kt:128` `rememberCoroutineScope()`는 SupervisorJob이 아니다(일반 Job).
  유일하게 실질 도달 가능한 unguarded thrower는 `Firestore.kt:369`
  `res.firstOrNull { it.asJsonObject.has("document") }`(`legacyFindRoomByCode`의 runCatching
  **밖**) — runQuery 응답 요소가 객체가 아니면 throw.
- **정정된 영향**(1·2회차 서술 교정): 폴링은 이 스코프가 아니라 **독립
  `LaunchedEffect(selected?.remoteId)`(`Main.kt:237`)**에서 돌아 살아남는다 → "폴링이 정지한다"는
  틀림. 실제로는 `scope`의 자식 하나가 미포착 예외를 던지면 **전 `scope.launch`(전송·편집·삭제·
  참여·생성·타이핑·아바타 업로드)가 영구 무력화되거나 예외가 recomposer로 전파돼 앱 전체
  크래시**. "화면(수신)은 되는데 아무것도 못 보냄" 또는 크래시.
- **정정된 수정**(제안 무효화): `rememberCoroutineScope { SupervisorJob() }`는 **동작하지 않는다**
  (Compose 계약 위반 → 취소된 스코프). 올바른 선택:
  - (a, 외과적·최소) `Firestore.kt:369`를 기존 runCatching 안에 넣거나 안전 캐스트로 방어 —
    현재 유일한 실 thrower라 이것만으로 실질 위험 제거.
  - (b, 근본) 전용 스코프 `remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }` +
    `DisposableEffect`로 취소. 각 `scope.launch`에 `CoroutineExceptionHandler`를 두는 것도 방법.
  - (a)를 먼저 권장. 이 수정은 1·2회차가 별건으로 본 F2(스피너 영구 고착)도 함께 없앤다 —
    F2는 스코프 취소 시에만 나타나는 2차 증상임이 3회차에서 확인됨.

## DC5. [P2, 확정] logsClearedAt 시계 스큐 — 로그 초기화 안내가 조용히 걸러짐

- **위치**: 쓰기 `Firestore.kt:584-588`(`setLogsClearedAt`)에 `Main.kt:515`가 **로컬 시계**
  `System.currentTimeMillis()`를 넘김. 적용 `Main.kt:381` `session.messages.filter { it.syncAt > clearedAt }`
  — **서버 시각(syncAt) vs 리셋 기기 로컬 시각(clearedAt)** 비교.
- **재현**: 리셋 기기 시계가 서버보다 앞서면 `clearedAt ≈ 서버시각 + skew`. 직후 포스트되는
  LOGS_RESET 안내(`Main.kt:517`)의 syncAt(서버시각) < clearedAt → 수신 측에서 **"로그가
  초기화되었습니다" 안내가 걸러진다**(로그는 지워지는데 안내만 사라짐). 반대로 뒤처지면
  지워져야 할 옛 메시지가 일부 잔류. 크래시·정지 아님, 안내 UX 손실.
- **수정**: `logsClearedAt`도 서버 시각으로 기록(`setToServerValue: REQUEST_TIME` 커밋)하고
  수신 비교를 서버 timestamp 대 syncAt(둘 다 서버 시각)으로 통일. (직전 보안/동기화 라운드에서
  같은 부류로 syncAt을 서버 시각화하기로 한 방향과 일치 — 함께 처리 권장.)

## DC6. [P2, 구조] 폴 setup 코드가 `try {` 밖 → 폴 루프 미진입(고친 버그와 동일 클래스)

- **위치**: `Main.kt:241-262`(`RoomCacheStore.load`, `messages =`, `pushCharacters`)가
  `try {`(**:273**) **밖**이다. 여기서 예외가 새면 `while (isActive)` 폴 루프(:274)에 **진입조차
  못 해 그 방 폴링이 무증상으로 시작 안 함** — "방 선택 → 메시지 안 뜨고 오류도 없음", 방금 고친
  사일런트 스톨과 정확히 같은 부류.
- **현 확률**: 하위 호출들이 개별 `runCatching`으로 가드돼 실제 throw 가능성은 좁다. 그러나
  구조적 결함은 실재하고 수정이 사소해(그 클래스 통째 제거) 반영 권장.
- **수정**: `try {`를 :241 앞으로 끌어올려 setup을 포함(finally의 `roomSessions.containsKey`
  가드가 이미 있어 안전).

## DC7. [P2, 확정] 방이 서버에서 삭제되면 무통보로 매 주기 무한 재시도

- **위치**: `Firestore.kt:240-242`(`get`)·`sendWithRetry`가 **404와 일시 오류를 똑같이 null로
  붕괴**시킨다. `getRoom`(:561)·`listMessagesSince`가 null → `Main.kt:349` 메타 폴 블록 전부 skip
  → 폴이 2.5~30초마다 영구 실패만 반복하고 화면엔 옛 메시지가 그대로 남아 사용자는 방 삭제를 알 수 없음.
- **수정(추가 read 0건 — 상태 코드는 이미 응답에 있고 버려질 뿐)**: `get`/`getRoom`을 sealed
  결과(Found/NotFound/Error)로 바꿔 404를 위로 전달, **연속 N회 404**에서만 "방이 삭제되었습니다"
  배너·목록 제거(일시 404 오판 방지). 우선순위 낮음 — 현재 무해하나 무통보.

## P3 — 무해·선택 (기록)

- **DC8** 무제한 증가 컬렉션 — `session.deletedDocIds`(`RoomSync.kt:17`)·`avatarEncodeCache`·
  `uploadedAvatarKeys`. 2인 규모 상계가 작아 실사용 무해. 매우 긴 세션에서 `deletedDocIds`의
  폴당 `filterNot` 비용만 서서히 증가 — 필요 시 상한/트림.
- **DC9** 창 종료 시 `finally` 캐시 저장(`Main.kt:403-412`, NonCancellable+IO)이 `exitApplication`
  JVM 종료와 경합 — 최대 마지막 ~30초 신선도 손실(30초 스로틀 저장 + 재시작 증분 재개로 흡수).
- **DC10** `metaFreezeUntil`(`Main.kt:187`)이 App 전역이라, 방 A에서 테마 적용 후 방 B로 전환하면
  B의 메타 폴도 유예 시간(15초)만큼 이름·테마 갱신 지연. 자가 치유, 실피해 미미.

## 검증됨 — 문제 없음 (재조사 불요)

- **config 저장 순서 역전(직전 리뷰 I2)** — 세대 번호(`Config.kt:216` `if (generation < writtenGeneration) return`)로
  이미 해결. 재현 안 됨.
- **빠른 방 전환** — `LaunchedEffect(selected?.remoteId)` 취소 시 stale 루프가 `withContext`
  재개 지점에서 CancellationException으로 죽어 전역 `messages` 대입 전 종료 → 커서/메시지 누수 없음.
  `finally`의 `containsKey` 가드가 나간 방 캐시 부활 차단.
- **트레이/알림 스레딩** — `DesktopNotifier`는 Main(EDT)에서 호출, `isSupported` 확인 + 전
  호출 `runCatching`. 안전.
- **ImageGc ↔ 폴 아바타 경합** — sweep은 시작 1회, 대상이 BACKGROUNDS/AVATARS_LOCAL/OWNER뿐이라
  원격 아바타 캐시(`avatars-remote`)를 안 건드림. 24h grace + 난수 tmp+rename. 안전.
- **오프-메인 상태 변경 / 토큰 read 경로 복구 / 폴 윈도·커서 산술 / 메타-메시지 레이스 /
  CaptureBar `messages.last()`(상단 `firstOrNull` 가드) / CaptureRenderer `!!`·`first()`
  (makeCapture runCatching 봉인)** — 전부 확인, 문제 없음.

## 검증

- DC1: 참여 오버레이에서 버튼 빠르게 두 번 클릭 → 크래시하지 않고 방이 하나만 추가되는지.
- DC2: syncAt 윈도에 기형 문서(예: `stringValue` 대신 원시값 필드)를 심어 수신이 멈추지 않고
  그 문서만 건너뛰는지(에뮬레이터/수동 문서 주입).
- DC3: 방 만들기 버튼 두 번 클릭 → 방이 하나만 생기는지.
- DC5: 데스크톱 시계를 +5분 틀어 로그 초기화 → 상대 화면에 "초기화됨" 안내가 남는지.
- 수정 후: `gradlew :desktop:test`(MainDispatcherTest 포함) 통과. **검증용 폴링·에뮬레이터는
  확인 후 즉시 종료.**
