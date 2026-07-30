# 코드 리뷰 보고서 — 메모리 · 성능 · 서버 폴링/읽기 비용 (2026-07-30)

> **반영 현황 (같은 날 처리)**: 전 항목 완료 — P1~P9, M1~M3(캐시 상한·트레이 훅 포함),
> F1~F3(아바타 인코딩 memoize·uploadedAvatars 영속화·FCM 토큰 방별 쓰기·알림 아바타
> 캐시·ChatScreen remember·주석 정정), P3 근본 수정(방별 파일 캐시 — 재시작에도
> 커서 유지, `~/.pbp-desktop/cache/`, 로그 초기화·방 나가기 시 삭제).
> 콘솔 확인: DB 리전 = asia-northeast3 (사용자 확인, firebase-security.md 기록).
> 폴 주기 구현값: 활성(최근 2분 송수신) 2.5초 / 유휴 20초 / 미포커스 30초,
> 전송·포커스 복귀 시 1초 내 즉시 복귀. 윈도 = 주기×2. 메타 폴 60초.

Firebase 배포(익명 인증 + 규칙) 완료 이후의 main 최신 기준으로, **메모리 릭 · 성능 · 과도한 서버 폴링/Firestore 읽기 비용**
세 관점을 집중 점검한 결과. **다른 세션에서 이 문서만 보고 수정 작업을 진행할 수 있도록** 항목마다 위치·영향 추정·수정
방향을 명시한다. 기준 커밋: `06db423`. 라인 번호는 이 커밋 기준.

## 요약 — 비용의 지배 요인 2개

현재 구조의 Firestore 읽기 비용은 사실상 두 가지가 지배한다. 나머지는 전부 소음 수준이다.

| 원인 | 비용 (2인 기준) | 해결 |
|---|---|---|
| **P1** Android 메모리 전용 캐시 → 콜드 스타트마다 모든 방 전체 히스토리 재다운로드 | 5,000건 방 × 하루 3회 시작 × 2인 ≈ **60,000 read/일** | PersistentCacheSettings 전환 (사실상 1줄) |
| **P2** 데스크톱 고정 2.5초 폴링 (유휴·미포커스 무관) | 방 1개 열어두면 **1,800 read/시간/클라이언트** = 켜두기만 해도 2인 86,400 read/일 | 활동 기반 백오프 + 미포커스 감속 |

무료 티어(50,000 read/일)는 **메시지를 한 건도 보내지 않아도** 초과된다. P1+P2+P3만 반영하면 정상 상태 읽기의
95% 이상이 제거된다. 메모리 릭은 심각한 것이 없고(전부 낮음), 렌더링 성능도 기존 수정(파싱 memoize, 상태 교체
가드)이 잘 작동하고 있음이 확인됐다.

권장 수정 순서: **P1 → P2 → P3 → P4 → P5~P9 → M → F**.

---

## P — 폴링 / Firestore 읽기·쓰기 비용

### P1. [최우선] Android 메모리 전용 캐시 — 콜드 스타트마다 전체 히스토리 재다운로드

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:112-117`(`MemoryCacheSettings` 명시 설정 —
  주석: "로컬 Room DB가 이미 소스이므로 디스크 캐시는 이중 저장") + `:435-437`(리스너가 `orderBy("createdAt")`
  전체 컬렉션, `limit()`/커서 없음) + `PbpApp.kt:69` → `start()`가 모든 공유 방에 attach.
- **문제**: 디스크 캐시가 없으므로 SDK가 스냅샷을 이어받지 못해, **프로세스 시작마다**(안드로이드는 백그라운드
  프로세스를 수시로 죽이므로 하루 여러 번) 모든 방의 initial snapshot이 서버에서 전량 전송·전량 과금된다.
  recoverAuth 재attach, 로그 초기화 후 reattach도 동일.
- **영향**: 5,000건 방 1개, 하루 3회 콜드 스타트, 2인 = 하루 약 60,000 read (월 180만). 캠페인이 길어질수록
  선형 증가. 시스템 전체에서 가장 큰 read 발생원.
- **수정 방향**: `MemoryCacheSettings` 오버라이드를 제거해 **기본 `PersistentCacheSettings`로 전환**.
  - "이중 저장" 우려는 수 MB 디스크 비용일 뿐이고, 전환 후에는 재attach 시 변경분만 과금된다.
  - **reconcile 로직은 그대로 동작**: 첫 서버 스냅샷의 `allIds`는 캐시 사용 시에도 전체 집합이며 재과금되지 않는다.
  - 대안(메모리 캐시 유지 + `startAfter` 커서)은 삭제 reconcile 기준선 설계를 깨므로 권장하지 않음.

### P2. [최우선] 데스크톱 고정 2.5초 폴링 — 유휴·미포커스 무관, 백오프 없음

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:215-272`(폴 루프, `:270` `delay(2500)`),
  `:248`(`tick % 4` 메타 폴 = 10초), `Firestore.kt:439-466`(runQuery). `windowFocused`(`Main.kt:115`)는
  트레이 알림 발화 판단에만 쓰이고 폴링 속도에는 미사용.
- **영향 (방 1개 열어둔 클라이언트당)**:
  - 유휴: 빈 쿼리도 최소 1 read + 규칙 `isMember` exists() 1 read → 메시지 폴 ~2,880 + 메타 폴 360 ≈
    **시간당 ~3,240 read**. 24시간 켜두면 클라이언트당 69k+/일 — 단독으로 무료 티어 초과.
  - 활성: 메시지 1건이 30초 중복 윈도 안에 ~12회 폴에 걸려 **건당 ~12 read** 재과금 (P5).
- **수정 방향** (모두 `Main.kt:215-272` 루프 내 국소 수정):
  1. **활동 기반 인터벌**: 마지막 송수신 후 2분 이내만 2.5초, 이후 15~30초로 백오프. 전송·수신 시 즉시 2.5초로
     복귀. (~10줄)
  2. **미포커스 감속**: `windowFocused`가 false면 30~60초로. **정지는 금지** — 트레이 알림이 폴링에 의존하므로
     감속만. 플레이-바이-포스트 특성상 30~60초 알림 지연은 허용 범위.
  3. 1+2 적용 시 유휴 2인 합계 ≈ 3~6k read/일로 무료 티어 안에 안착.

### P3. 데스크톱 방 전환·재시작마다 전체 히스토리 재다운로드 (로컬 캐시 없음)

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:215-219` — 방 선택 변경 시
  `messages = emptyList(); lastCreatedAt = 0` → 다음 사이클에 `listMessages`(`Firestore.kt:412-431`)가
  전체 컬렉션 재조회(300건/페이지). 로컬에 아무것도 저장하지 않으므로 앱 재시작도 동일.
- **영향**: 5,000건 방이면 전환·시작마다 5,000 read. 2,000건 방 2개를 하루 10번 오가면 40k read.
- **수정 방향**: 최소 수정은 **세션 내 방별 캐시** — `remoteId → (messages, lastCreatedAt, deletedDocIds)`
  맵을 두고 재선택 시 증분 커서로 재개. 근본 수정은 방별 메시지 로컬 파일 캐시(재시작에도 유지) — Android의
  Room처럼 데스크톱도 마지막 커서와 목록을 config 옆 파일에 저장.

### P4. Android 콜드 스타트 시 무의미한 UPDATE 홍수

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:501-511` — initial snapshot의 모든 문서가
  ADDED로 도착하는데, 로컬에 이미 있는 문서를 전부 "떨어져 있던 사이의 편집"으로 간주해 건별
  `updateBodyByRemoteId` 실행.
- **영향**: 5,000건 방 콜드 스타트마다 상대 작성분 ~2,500건의 개별 UPDATE(각각 별도 트랜잭션). 매 쓰기가
  `messages` 테이블을 무효화해 `observeLatestForRoom`·`observeLastPerRoom`·`observeUnreadCounts`가 시작
  동안 수천 번 재실행 — 시작 직후 채팅 화면 버벅임의 원인. **P1 수정 후에도 발생**(새 리스너의 첫 스냅샷은
  항상 전체 ADDED).
- **수정 방향**: dedup 청크 조회(`:498-499`)에서 `remoteId`뿐 아니라 `body, editedAt`도 함께 가져와
  **변경 없으면 UPDATE 스킵**. 실제 변경분은 `db.withTransaction`으로 묶어 일괄 처리.

### P5. 데스크톱 30초 중복 윈도 — 메시지당 ~12배 재과금

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/data/Firestore.kt:441`(`since - 30_000`)
- **수정 방향**: 5초로 축소(폴 주기 2회분 여유 — 시계 오차·커밋 재정렬 흡수에 충분). 건당 재과금 ~12× → ~2-3×.
  dedup(`Main.kt:230`)이 이미 있으므로 동작 변화 없음. P2의 백오프 도입 시 윈도는 "인터벌 × 2"로 동적 계산 권장.

### P6. 데스크톱 메타 폴 10초 — 과도

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:248`(`tick % 4`)
- **수정 방향**: 60초 이상으로(테마·배경 변경 반영이 1분 늦어도 무방). 장기적으로는 방 메타 변경을 SYSTEM
  메시지로 메시지 스트림에 실어 메타 폴 자체를 제거 가능(이미 폴링 중인 쿼리에 편승).

### P7. wipeMessages가 삭제 전에 전체 컬렉션을 read

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:321-331` — `messages.get()`(N read) 후 N 삭제.
- **영향**: 5,000건 방 로그 초기화 = 5,000 read + 5,000 delete ≈ 1만 과금 op + 양쪽 리스너의 REMOVED 5,000건.
- **수정 방향**: 사전 `get()` 대신 **로컬이 아는 `listRemoteIdsForRoom` id 목록으로 직접 삭제**(read 0).
  로컬이 모르는 잔여 문서는 초기화 후 reattach의 reconcile이 처리한다.

### P8. 아웃박스 드레인 시 FCM 푸시 폭주 — 함수에 오래된 메시지 가드 추가

- **위치**: `functions/index.js:20-43`(모든 onDocumentCreated마다 members 조회 + 푸시) ×
  `app/src/main/java/com/pbp/app/sync/SyncManager.kt:148-152`(시작 시 미전송분 건별 push).
- **영향**: 오프라인에서 수십 건 작성 후 복귀하면 상대 기기가 건당 1회씩 고우선 푸시로 연쇄 웨이크업(알림 표시는
  같은 ID로 합쳐지지만 웨이크업·함수 호출·members read는 건별 발생). 공유 백필은 상대가 아직 멤버가 아니라
  푸시 0건임을 확인했으나 members read는 건별 발생.
- **수정 방향**: `notifyNewMessage` 진입부에 `data.createdAt < now - 2분이면 return` 1줄 가드 — 백필·아웃박스
  드레인 모두 무력화. (부수: 함수 내 members 조회를 모듈 스코프 30~60초 TTL 캐시로 memoize — 선택.)

### P9. 데스크톱 아바타가 실행마다 재다운로드 (메모리 캐시만)

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:163`(`avatarCache` — 프로세스 메모리만),
  `Firestore.kt:504-508`. 아바타 문서는 base64 내장(~30-300KB)이라 read당 대역폭도 문서 크기만큼 과금.
  HTML 내보내기도 별도로 재fetch(`Main.kt:388-389`).
- **비교**: Android는 `filesDir/avatars/remote-{hash}.jpg` 디스크 캐시로 설치당 1회만 다운로드
  (`SyncManager.kt:724-726`) — 정상 확인.
- **수정 방향**: Android와 동일하게 해시 키 디스크 캐시(config 옆 디렉터리), fetch 전 디스크 확인.

---

## M — 메모리

### M1. Android 리스너의 스냅샷 뷰 상주 + 매 이벤트 전체 ID 집합 재구성

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:447-453` — 매 스냅샷 이벤트마다
  `snapshot.documents.mapTo(mutableSetOf()) { it.id }`로 전체 ID 집합을 만들지만, 실제로는 첫 서버 스냅샷의
  reconcile(`:465, 546-550`)에서만 사용. 또한 무제한 쿼리 리스너라 SDK가 방 전체 결과 뷰(~1KB/문서)를 앱
  수명 내내 상주시킴 — 5,000건 방 ≈ 방당 ~5MB.
- **수정 방향**: `allIds` 구성을 reconcile 완료 전까지만(불리언 가드). 상주 뷰 자체는 P1의 영향이 아니라
  무제한 리스너 설계의 비용 — 수용 가능하나, 방 수가 늘면 백그라운드 진입 시 비활성 방 detach를 고려.

### M2. 데스크톱 커스텀 배경의 중복 디코드·중복 보관

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:853-875`(`BackgroundLayer`) — 인스턴스마다
  `produceState`로 원본 해상도(≤1600px ≈ 6-10MB N32) 디코드·보관. 채팅 패널 + 48dp 방 목록 썸네일이 각각
  별도 디코드 → 같은 이미지가 2회 이상 상주(~10-20MB).
- **수정 방향**: `avatarCache`처럼 경로→ImageBitmap 공용 캐시 1개, 썸네일용은 축소 디코드.

### M3. 낮음 (수용 가능, 여유 있을 때)

- `avatarCache`(`Main.kt:163`)·`uploadedAvatarKeys`(`:2332`)·`avatarsInFlight`(`:704`) 무제한 — 2인 규모에선
  실질 수 MB 이하. LRU 상한(~50)이면 원칙적으로도 닫힘.
- 트레이 아이콘 미해제(`desktop/.../notify/DesktopNotifier.kt:16-24`) — JVM 종료 시 회수되나 일부 리눅스
  트레이에서 잔상. 셧다운 훅 `SystemTray.remove` 1줄.
- 폰트(고운바탕 8.2MB×2 + 프리텐다드 6.7MB)는 1회 로드 고정 비용 — 정상, 조치 불요.
- 방 전환 시 이전 폴의 블로킹 `http.send`가 최대 30초 겹침(취소 불가) — 일시 이중 트래픽, 릭 아님.

---

## F — 성능

### F1. 채팅 페이징 쿼리 복합 인덱스 부재 — 삽입마다 방 전체 정렬

- **위치**: `app/src/main/java/com/pbp/app/data/Entities.kt:78`(인덱스: `roomId`, `remoteId`뿐) vs
  `Daos.kt:102-108`(`WHERE roomId ORDER BY createdAt DESC, id DESC LIMIT`), `:110-111`, `:160-166`.
- **영향**: 인덱스가 없어 1만 건 방이면 삽입·무효화마다 1만 행 수집·정렬 후 200건 추출. P4의 UPDATE 홍수와
  곱해져 시작 직후 부하 증폭.
- **수정 방향**: `Index(value = ["roomId", "createdAt", "id"])` 추가(마이그레이션 v8). DESC LIMIT 서브쿼리가
  인덱스 순 스캔으로 바뀐다.

### F2. GmSpeech.split / quoteContent가 컴포지션에서 매번 실행 (양 클라이언트)

- **위치**: Android `app/src/main/java/com/pbp/app/ui/chat/ChatScreen.kt:543, 561, 655`; 데스크톱
  `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:1123, 1142`.
- **영향**: 목록 emission마다 보이는 행 전부가 정규식 재분해(행당 µs — 소액이지만 P4 홍수 때 곱해짐).
  참고로 `PbpMarkup.parse`는 양쪽 모두 remember 처리 확인(정상).
- **수정 방향**: `remember(message.body) { GmSpeech.split(...) }` 패턴으로 통일.

### F3. 소소한 낭비 (여유 있을 때 일괄)

- 데스크톱 전송마다 아바타 재인코딩·재해시(`Main.kt:291-305`, IO 스레드라 UI 영향 없음, ~10-50ms CPU) —
  경로→해시 memoize.
- Android `uploadedAvatars` 메모리 한정(`SyncManager.kt:692-722`) — 프로세스 시작 후 첫 전송마다 방당 1회
  대형 문서(50-300KB) 재쓰기. SharedPreferences에 `room/hash` 키 영속화.
- `registerFcmToken(force=true)`가 공유/참가 시 **모든** 방에 쓰기(`SyncManager.kt:256, 305, 667-685`) —
  해당 방만 쓰도록.
- 알림마다 아바타 파일 디코드+원형 합성(`MessageNotifier.kt:62-74`) — 경로 키 캐시.
- ChatScreen 컴포지션 내 O(N) 스캔(`:195-197` find ×3, `:212` count) — `remember(messages)`.
- 데스크톱 `runBlockingIo`(`Main.kt:136, 700-701`) — `runBlocking(Dispatchers.IO)`는 호출 스레드를 여전히
  블록함(주석의 완화 효과 없음). 현재 수 ms라 실해는 없으나 주석 정정 또는 실제 비동기화.

---

## 콘솔 확인 1건 (코드 아님)

- **Firestore DB 리전 확인**: 함수는 `asia-northeast3` 고정(`functions/index.js:18`)인데 저장소 어디에도 DB
  리전 기록이 없음. 콘솔에서 DB 위치가 `asia-northeast3`인지 확인 — 다르면 함수 호출마다 교차 리전 지연
  (+100-150ms)과 이그레스 발생. 같으면 조치 불요(확인 결과를 docs/firebase-security.md에 한 줄 기록 권장).

---

## 검증 정상 (재확인 불필요)

- **폴링 외 트래픽**: Android는 폴링 루프 없음(전부 리스너/푸시 구동), FCM 토큰 쓰기는 변경 시에만,
  memberFix는 uid당 1회, 함수 `maxInstances: 3` 캡·SYSTEM 조기 반환(로그 초기화 공지는 푸시 0건),
  **로그 초기화 wipe는 onDelete 트리거가 없어 함수 미발화**, 규칙 `isMember`는 쿼리당 +1 read(2배 아님 —
  요청 내 캐시), 공유 백필은 상대가 아직 멤버가 아니라 푸시 폭주 없음(단 members read는 건별 — P8 가드로 해소).
- **메모리/수명**: Android 리스너·채널 detach 정리 정상, Activity 컨텍스트 보유 없음, 데스크톱 폴 코루틴
  방 전환·창 종료 시 취소 정상, 빈 폴은 Compose 상태 무접촉(재구성 없음), config 저장 스코프 정상.
- **렌더링**: `PbpMarkup.parse` 양쪽 remember 처리, 입력 상태는 InputZone에 격리(타이핑이 목록 재구성 유발
  안 함), 이미지는 임포트 시 축소(배경 1600px, 아바타 512/256px), Coil 사이즈드 로딩, 데스크톱 HTTP 클라이언트
  단일 인스턴스·커넥션 재사용, 내보내기 메모리 사용 한정적.
- **Android 아바타 다운로드**: 디스크 캐시로 설치당 1회 — 정상 (P9는 데스크톱만 해당).

## 예상 효과 요약

| 조치 | 절감 |
|---|---|
| P1 (Persistent 캐시) | Android 콜드 스타트 read 전량 제거 — 최대 항목 |
| P2 (백오프+미포커스 감속) | 데스크톱 유휴 read ~95% 절감 (86k → 3~6k/일) |
| P3 (방별 캐시) | 방 전환·재시작 전체 재로드 제거 |
| P4+F1 (UPDATE 스킵+인덱스) | 시작 직후 버벅임 제거, DB 부하 수천 배 감소 |
| P5 (윈도 5초) | 활성 채팅 재과금 12× → 2-3× |
| P7 (wipe 사전 get 제거) | 로그 초기화 비용 절반 |

## 테스트 권고

- P1 전환 후: 에뮬레이터에서 프로세스 강제 종료 → 재시작 시 기존 방이 재다운로드 없이 뜨는지(로그로 initial
  snapshot의 `isFromCache` 확인) + 편집/삭제 reconcile이 여전히 수렴하는지.
- P4: 콜드 스타트 시 UPDATE 실행 횟수 로그로 전/후 비교.
- P2/P5: 폴 주기·윈도 변경 후 두 클라이언트 간 메시지 도달(활성 2.5초/유휴 백오프)과 시계 오차 시나리오 재확인.
