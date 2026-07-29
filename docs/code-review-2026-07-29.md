# 코드 리뷰 보고서 (2026-07-29)

> **처리 결과 (2026-07-29 반영)**: P0-1, P1-1~P1-8, P2-1~P2-8, P3-1, P3-3~P3-9, P3-11~P3-16 수정 완료.
> - **P3-2 (서버 타임스탬프)**: 보류 — 정렬·폴링·표시가 모두 얽힌 스키마 전환이라 별도 작업 권장. P1-4의 여유 윈도우(30초 재조회)로 실질 위험은 완화됨.
> - **P3-10 ({{}} 표시 스푸핑)**: 수용 — 2인 신뢰 기반 세션 도구로, 상대를 속이는 위협 모델을 방어 대상으로 두지 않음.
> - **P0-1 잔여 수동 작업**: `docs/firebase-security.md` 참조 (익명 로그인 활성화 → 규칙 배포 → API 키 제한).

전체 코드베이스(Android `app`, Compose Desktop `desktop`, Cloud Functions `functions`) 점검 결과.
**다른 세션에서 이 문서를 보고 수정 작업을 진행할 수 있도록** 항목마다 위치·재현 시나리오·수정 방향을 명시한다.
기준 커밋: `8aedbae` (main과 동일). 라인 번호는 이 커밋 기준이므로 수정 시 주변 코드로 재확인할 것.

권장 수정 순서: **P0 → P1 → P2 → P3**. 같은 우선순위 안에서는 위에서부터.

---

## P0 — 치명적 (보안)

### P0-1. Firestore 보안 규칙·인증 전무 — DB 전체가 외부에서 읽기/쓰기 가능

- **위치**:
  - `firebase.json` — `functions` 항목만 있고 `firestore`/`rules` 설정 없음. 저장소 어디에도 `firestore.rules` 없음.
  - `app/src/main/java/com/pbp/app/sync/SyncManager.kt:48` — 신원이 자체 발급 UUID(SharedPreferences)뿐, Firebase Auth 미사용.
  - `desktop/src/main/kotlin/com/pbp/desktop/data/Firestore.kt` — 모든 REST 요청이 `?key=API_KEY`만 사용. ID 토큰 없음.
  - `desktop/src/test/kotlin/com/pbp/desktop/FirestoreRestLiveTest.kt:14-17` — 프로젝트 ID(`pbp-session-1195c`), API 키, 실제 운영 방 문서 ID가 저장소에 커밋되어 있음.
- **문제**: 데스크톱 클라이언트가 비인증 REST로 동작한다는 것 자체가 규칙이 전체 공개라는 뜻. APK에서 추출 가능한 API 키만으로 누구나 모든 방 열람, 메시지 위조·수정·삭제, `authorUid` 사칭, `members/{deviceId}` 덮어쓰기(FCM 토큰 하이재킹)가 가능.
- **구조적 제약**: 초대코드 참가가 `rooms` 컬렉션 전체 `whereEqualTo("inviteCode", code)` 쿼리(`SyncManager.kt:144`)에 의존하므로, 컬렉션 읽기를 잠그면 참가가 깨진다.
- **수정 방향**:
  1. Firebase **Anonymous Auth** 도입(양쪽 클라이언트). 데스크톱은 REST `signUp` + ID 토큰 갱신 필요.
  2. 초대코드 조회를 별도 경로로 분리: `inviteCodes/{code}` 조회 컬렉션 또는 Callable Function.
  3. `firestore.rules` 작성: 방 멤버(UID 기반)만 해당 방 읽기/쓰기, 메시지 수정·삭제는 `authorUid == request.auth.uid`일 때만.
  4. `FirestoreRestLiveTest.kt`의 커밋된 자격증명·방 ID 제거(P2-8과 함께 처리). **키가 이미 노출되었으므로 콘솔에서 API 키 제한(Android 앱 제한 등) 적용도 문서화할 것.**

---

## P1 — 높음 (데이터 유실·중복·크래시)

### P1-1. 오프라인 중 편집/삭제가 영구 유실 (기기 간 영구 분기)

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:76-79` (메모리 전용 캐시), `:248-263` (MODIFIED/REMOVED를 라이브 `documentChanges`로만 처리), `:235-236` (remoteId dedup).
- **재현**: A가 메시지를 편집/삭제하는 동안 B의 프로세스가 죽어 있음 → B 재시작 시 리스너가 새로 붙어 편집된 문서는 `ADDED`로 도착 → dedup에 걸려 새 본문 미반영. 삭제된 문서는 아예 안 나타나 `REMOVED` 이벤트가 없음 → B에는 영원히 남음.
- **수정 방향**: `ADDED`인데 로컬에 이미 있는 remoteId면 스킵하지 말고 본문/상태를 **업서트**. 삭제는 tombstone 필드(`deleted: true`)로 소프트 삭제하거나, 리스너 attach 시 로컬 remoteId 집합과 스냅샷 전체를 대조해 없어진 문서를 정리.

### P1-2. 전송 중 크래시 시 메시지 중복 전송

- **위치**: `app/src/main/java/com/pbp/app/sync/SyncManager.kt:172-180` (`doc.set().await()` 후 `setRemoteId`), 아웃박스 재전송 `:95`.
- **재현**: `set()`이 서버에 커밋된 뒤 `setRemoteId` 전에 프로세스 종료 → 재시작 시 `listUnsent`가 같은 메시지를 **새 자동 ID**로 다시 push → 상대 기기에 2건 도착(둘 다 dedup 통과).
- **수정 방향**: `document()`로 ID를 먼저 확보 → `setRemoteId(message.id, doc.id)`를 **먼저** 저장 → 그 후 `doc.set()`. 재전송 시 remoteId가 있으면 같은 문서에 `set`(멱등). P3-1(remoteId 유니크 인덱스)과 함께 하면 DB 차원에서도 차단됨.

### P1-3. 메시지 1,000개 이상 방 재참가 시 앱 크래시 (SQLite 변수 999개 제한)

- **위치**: `app/src/main/java/com/pbp/app/data/Daos.kt:99-100` (`WHERE remoteId IN (:ids)` 무분할), 호출부 `SyncManager.kt:233`, 예외 처리 없는 `scope.launch` `:226`.
- **재현**: 재설치/재참가로 초기 스냅샷에 1,000+ 문서가 전부 ADDED로 도착 → `SQLiteException: too many SQL variables` → 코루틴 미처리 예외로 앱 크래시, 히스토리 0건.
- **수정 방향**: `ids`를 900개 단위로 chunk해 조회 후 합치기. 스냅샷 처리 코루틴에 `runCatching`/`CoroutineExceptionHandler` 추가. 방 삭제 직후 in-flight 코루틴의 FK 예외(`:244`)도 같은 핸들러로 흡수.

### P1-4. 데스크톱 폴링이 메시지를 영구 누락 (타임스탬프 경합)

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/data/Firestore.kt:219` (`createdAt`에 `GREATER_THAN`), `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:120` (`lastCreatedAt = maxOf(...)`).
- **재현**: (a) 상대 기기 시계가 데스크톱보다 몇 초 느림 → 상대 메시지가 항상 `<= lastCreatedAt` → 영원히 미수신. (b) 커밋 순서 ≠ 타임스탬프 순서인 근접 쓰기 → 이른 타임스탬프 쪽 영구 누락. (c) 같은 밀리초 2건(데스크톱 자신이 `Main.kt:145`/`155`에서 TEXT+DICE 연속 전송 시 실제 발생) 사이에 폴링이 끼면 두 번째 건 스킵.
- **수정 방향**: 최소 수정은 `GREATER_THAN_OR_EQUAL` + 기존 `knownIds` dedup(`Main.kt:115-116`) 활용 + **여유 윈도우**(예: `since - 30_000`)로 재조회. 근본 해결은 `createdAt`을 서버 타임스탬프로 전환(Android 쪽 `SyncMapping.kt:23`도 함께 — P3-2 참조).

### P1-5. 데스크톱: 전송 실패 시 메시지가 무통보 소실

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:144-151` (`postMessage` 반환값 무시), `:807-809` (클릭 즉시 입력창 비움).
- **재현**: 오프라인/5xx에서 전송 클릭 → POST 실패는 stderr 로그뿐 → 입력창은 이미 비워졌고 화면에 아무것도 안 남음.
- **수정 방향**: `postMessage` 실패 시 입력창 텍스트 복원 + 에러 표시. 다이스 후속 전송과 `switchProfile`의 SYSTEM 메시지도 동일 처리.

### P1-6. 데스크톱: 부분 페이지 실패가 증분 커서를 오염

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/data/Firestore.kt:54-60` (모든 에러를 `null`로), `:203` (`get(url) ?: break` — 페이지네이션 중단을 정상 종료처럼 처리), `Main.kt:120`.
- **재현**: 초기 로드 중 429/503 → 부분 리스트 반환 → `lastCreatedAt`이 부분 최대값으로 전진 → 미수신 페이지의 메시지들이 증분 쿼리에서 영원히 제외.
- **수정 방향**: `get()`이 에러와 빈 결과를 구분하도록 변경(예: 예외 전파 또는 Result 타입). 실패한 로드에서는 커서를 전진시키지 않기.

### P1-7. 채팅 목록이 목록 변경 시마다 최신으로 강제 스크롤 — "이전 대화 불러오기" 무력화

- **위치**: `app/src/main/java/com/pbp/app/ui/chat/ChatScreen.kt:183-185` — `LaunchedEffect(messages.firstOrNull()?.id, messages.size)`.
- **재현**: (a) "이전 대화 불러오기"(`:286`) 탭 → limit 증가 → size 변경 → 즉시 index 0(최신)으로 점프, 페이징 사용 불가. (b) 위로 스크롤해 읽던 중 새 메시지 도착 또는 메시지 삭제 → 바닥으로 끌려감.
- **수정 방향**: 키를 **최신 메시지 id만**으로(`messages.firstOrNull()?.id` — reverseLayout이므로 first가 최신) 하고 `size` 키 제거. 사용자가 위로 스크롤 중이면(예: `listState.firstVisibleItemIndex > 0`) 자동 스크롤 생략.

### P1-8. 자동완성 팔레트 중복 키 크래시

- **위치**: `app/src/main/java/com/pbp/app/ui/chat/ChatScreen.kt:757` (`items(suggestions, key = { it })`), `app/src/main/java/com/pbp/app/ui/profile/ProfileEditScreen.kt:299` (스탯 이름 중복 검사 없음).
- **재현**: 같은 이름 스탯 2개("은신 50" 두 번 — 수동 추가나 클립보드 가져오기로 가능) → "은" 입력 → LazyRow 키 충돌 `IllegalArgumentException` → 입력 중 크래시.
- **수정 방향**: `suggestions.distinct()` 적용(즉효약) + `ProfileStats.paletteSuggestions`에서 dedup. 근본적으로는 스탯 추가/가져오기 시 이름 중복 방지.

---

## P2 — 중간

### P2-1. 스냅샷 이벤트 비직렬 처리 — 빠른 편집/삭제 유실 레이스

- **위치**: `SyncManager.kt:226` — 스냅샷 콜백마다 `scope.launch(Dispatchers.IO)`, 직렬화 없음.
- **재현**: 상대가 전송 직후 1초 내 편집 → ADDED 코루틴보다 MODIFIED 코루틴이 먼저 실행 → `updateBodyByRemoteId` 0건 매치 → 이후 ADDED가 **원본** 본문 삽입 → 편집 영구 미반영.
- **수정 방향**: 방별 직렬 처리(단일 `Channel`/actor 또는 `Mutex`)로 스냅샷 순서 보장.

### P2-2. 방 삭제 후 유령 FCM 알림 + 백그라운드 이중 알림

- **위치**: 유령 알림 — `app/src/main/java/com/pbp/app/data/PbpRepository.kt:58-61` (`deleteRoom`이 로컬 정리만), `SyncManager.kt:335-343`, `functions/index.js:30-41`. 이중 알림 — `app/src/main/java/com/pbp/app/PbpApp.kt:38-40` (리스너 경로, ID=로컬 `roomId.toInt()`) vs `app/src/main/java/com/pbp/app/notify/FcmService.kt:22-25` (FCM 경로, ID=원격 roomId `.hashCode()`).
- **수정 방향**: `deleteRoom`/방 나가기 시 원격 `members/{deviceId}` 문서 삭제. 알림 ID를 한 가지 기준(원격 roomId)으로 통일하고, 프로세스 생존+백그라운드일 때는 한 경로만 알림을 담당하도록 조건 정리.

### P2-3. EXIF 회전 미처리 — 카메라 세로 사진이 90° 돌아감

- **위치**: `app/src/main/java/com/pbp/app/data/Images.kt:31-43`, `SyncManager.kt:392-412` (`downscaleToJpeg`), `app/src/main/java/com/pbp/app/ui/common/ImageCrop.kt:176-187` (`loadBitmap`), `MessageNotifier.kt:67`.
- **수정 방향**: 디코드 직후 `androidx.exifinterface.media.ExifInterface`로 Orientation 읽어 Matrix 회전 적용. 공용 헬퍼 하나로 통일.

### P2-4. 화면 회전 시 입력 상태 전부 소실 (`rememberSaveable` 미사용)

- **위치**: `ChatScreen.kt:693-694` (`input`, `oocOn`), `:172-175` (다이얼로그 상태), `:913` (편집 중 텍스트), `ProfileEditScreen.kt:112-133` (폼 전체 — `LaunchedEffect(profileId)`가 재실행되며 DB 값으로 덮어씀).
- **수정 방향**: 텍스트/토글류는 `rememberSaveable`로 전환. ProfileEditScreen은 DB 로드를 최초 1회만 하도록 가드(예: saveable 플래그).

### P2-5. 클라이언트 동작 불일치 — 스탯 매크로 치환이 데스크톱에 없음

- **위치**: 데스크톱 `Main.kt:139-165` (원문 그대로 파싱) vs Android `app/src/main/java/com/pbp/app/data/PbpRepository.kt:107,125` (`ProfileStats.substitute()` 선적용).
- **재현**: 데스크톱에서 `1d100<={은신}` 입력(Android 자동완성이 생성하는 형식) → `DiceBot.parse` 실패 → 다이스 판정 자체가 실행 안 됨, 본문에 `{은신}` 원문 저장.
- **수정 방향**: `ProfileStats`의 substitute 로직을 `desktop/logic/Logic.kt`에 이식(공유 파서들은 이미 바이트 동일 사본으로 검증됨), 전송 경로에서 Android와 동일 순서로 적용. 데스크톱 프로필에 스탯 개념이 없다면 최소한 `{...}` 포함 시 동작을 정의할 것.

### P2-6. 짝 없는 `**`가 메시지에서 조용히 삭제됨 (실행 검증된 버그)

- **위치**: `app/src/main/java/com/pbp/app/text/PbpMarkup.kt:84` (원인은 `:96-97`의 `hasClosing`과의 상호작용), 데스크톱 사본 `desktop/logic/Logic.kt`도 동일.
- **재현**: `parse("별 ** 하나")` → `**` 소실("별 " + " 하나"). `parse("**굵게")` → 별 소실, 볼드 없음. `**` 첫 별이 이탤릭 분기로 떨어지고 두 번째 별이 닫힘으로 오인돼 빈 쌍으로 소비됨. `~~`는 정상(비대칭이 의도 아님의 근거). 문서화된 규칙(`:59` "짝 없는 구분자는 리터럴 유지") 위반.
- **수정 방향**: 이탤릭 분기 진입 전에 "`*` 다음 문자가 또 `*`인 경우"를 리터럴로 처리하거나, `hasClosing` 탐색 시작점을 조정. **양쪽 사본 동시 수정** + `PbpMarkupTest`에 짝 없는 `**`/`~~` 케이스 추가.

### P2-7. 내보낸 HTML 로그에서 줄바꿈 소실

- **위치**: `app/src/main/java/com/pbp/app/export/LogExporter.kt:209-214` (`escape`가 `\n` 그대로 방출), `:151`/`:154` (`.lbubble`/`.lnarr` CSS에 `white-space` 없음).
- **수정 방향**: `.lbubble`, `.lnarr`에 `white-space:pre-wrap` 추가(가장 안전). `LogExporterTest`에 다중행 본문 케이스 추가.

### P2-8. 데스크톱 안정성 3건

1. **HTTP 타임아웃 없음** — `Firestore.kt:50` (`HttpClient.newHttpClient()`), 요청별 `.timeout()`도 없음 → TCP 블랙홀 시 폴링 루프 영구 정지. → connect/request 타임아웃(예: 10s/30s) 설정.
2. **config 손상 시 무통보 초기화** — `desktop/src/main/kotlin/com/pbp/desktop/data/Config.kt:38-46` — 읽기/파싱 실패를 삼키고 기본값 생성 후 `:62-68`의 비원자적 쓰기로 즉시 덮어씀 → 방 목록·프로필·deviceId 전부 소실. → 임시 파일 후 `move`(원자적 쓰기), 파싱 실패 시 `.bak` 백업 후 사용자 통지.
3. **라이브 테스트가 운영 DB에 쓰기** — `FirestoreRestLiveTest.kt:20-48` — 빌드마다 실제 방에 메시지 전송, 정리 없음, 네트워크 없으면 빌드 실패. → 태그 분리(`@Tag("live")` + gradle 기본 제외) 또는 삭제. P0-1의 자격증명 제거와 함께 처리.

---

## P3 — 낮음

| # | 항목 | 위치 | 수정 방향 |
|---|---|---|---|
| P3-1 | `messages.remoteId` 유니크 인덱스 없음 — dedup이 조회-후-삽입뿐, 조회도 풀스캔 | `app/.../data/Entities.kt:69-77` | 유니크 인덱스 추가 + 마이그레이션 v7 (P1-2 보강) |
| P3-2 | 메시지 정렬·읽음 처리가 클라이언트 시계 의존 | `app/.../sync/SyncMapping.kt:23`, `PbpRepository.kt:82` | 서버 타임스탬프 전환 (P1-4와 묶어 처리) |
| P3-3 | `shareRoom` 부분 실패 시 원격 방 문서 고아화 + 재시도 시 이중 생성 | `SyncManager.kt:102-138` | `setRemote`를 원격 생성 직후로 이동, 재시도 시 기존 문서 재사용 |
| P3-4 | 스냅샷 리스너 에러 무시(`_`) — 동기화가 죽어도 무신호 | `SyncManager.kt:223,273` | 로그 + 재시도/사용자 표시 |
| P3-5 | 크롭 저장이 메인 스레드 (ANR 위험) | `app/.../ui/common/ImageCrop.kt:136` | `cropToFile`을 `Dispatchers.IO`로 |
| P3-6 | 스탯 행 삭제의 stale index — 연타 시 IndexOutOfBounds/오삭제 | `ProfileEditScreen.kt:275` | index 대신 항목 참조로 제거 |
| P3-7 | 메시지 정확히 PAGE_SIZE개일 때 유령 "이전 대화" 버튼 + 탭 시 깜빡임 | `ChatScreen.kt:273` | 총 개수 쿼리로 hasMore 판정 |
| P3-8 | 공백만 있는 GM 인용부가 빈 "???" 말풍선 생성 | `app/.../text/GmSpeech.kt:24` | trim 후 비어 있으면 Quote 미생성 |
| P3-9 | 이미지 매직바이트 2바이트 판별 — 오탐 시 깨진 `<img>` | `LogExporter.kt:223-228` | 전체 시그니처 검사 (RIFF+WEBP 등) |
| P3-10 | `{{90}}` 수동 입력으로 판정값 위장 가능 (표시 스푸핑) | `PbpMarkup.kt:29`, `LogExporter.kt:183` | 입력 시 이스케이프 또는 수용(설계 판단) |
| P3-11 | 스탯 값의 제어문자(0x1E/0x1F)·중괄호로 인코딩/치환 깨짐 | `app/.../data/ProfileStats.kt:17-19,53-55` | 저장 시 해당 문자 제거 |
| P3-12 | 데스크톱 `findRoomByCode` JSON 인젝션 (`"` 포함 코드 → 400) | `desktop/.../data/Firestore.kt:127` | 값 이스케이프 또는 JSON 빌더 사용 |
| P3-13 | 데스크톱 프로필 빈 배열 시 첫 전송에서 크래시 | `desktop/.../Main.kt:143` | `firstOrNull` + 기본 프로필 생성 |
| P3-14 | 데스크톱 메타 폴링이 마스터의 테마 변경을 되돌림 (PATCH 착지 전 폴링) | `Main.kt:122-132` vs `:278-288` | 로컬 변경 후 일정 시간 폴링 반영 유예 |
| P3-15 | 데스크톱 아바타 중복 fetch + 실패 시 영구 음성 캐시 | `Main.kt:667-677` | in-flight 공유 + 실패 시 캐시 미저장 |
| P3-16 | 데스크톱 UI 스레드 파일 I/O (`load`가 컴포지션 중, `persist`가 이벤트 핸들러에서) | `Main.kt:85,97-101,131,176` | IO 디스패처로 이동 |

---

## 문제없음이 확인된 부분 (재검토 불필요)

- `DiceBot.kt` — 다이스 수학, d66, 비교 연산, 정규식 앵커링 모두 정상. Android/데스크톱 사본 바이트 동일.
- `CharacterCodec.kt` — 크래시 경로 없음(모든 Gson 예외가 `runCatching`에 흡수), 따옴표 이중화 폴백 일관성 확인.
- `LogExporter.kt` — HTML 인젝션 없음(모든 사용자 입력이 `escape()` 경유). 결함은 P2-7, P3-9뿐.
- Room 마이그레이션 1→6 — 가산적 ALTER, NOT NULL DEFAULT 정합, 파괴적 폴백 없음.
- 최근 커밋의 키보드 인셋 수정(`consumeWindowInsets` + `imePadding` + `adjustResize`) — 정확함.
- `ImageCrop.kt` 팬/줌/저장 변환 수학 — 화면 표시와 저장 매트릭스 등가 확인 (EXIF 문제 P2-3만 별도).
- `SyncMapping.kt` — 방어적 타입 강제 변환, 왕복 테스트 적절.
- `functions/index.js` — 자체 로직은 정상(작성자 제외, 본문 미포함, 만료 토큰 개별 catch). 노출은 P0-1에서 상속.

## 테스트 공백 (수정 시 함께 추가)

- `PbpMarkupTest` — 짝 없는 `**`, `~~` 케이스 (P2-6 은폐 중)
- `LogExporterTest` — 다중행 본문 (P2-7 은폐 중)
- `GmSpeechTest` — 공백만 있는 인용부 (P3-8 은폐 중)
- `SyncManager` 경로는 테스트 자체가 없음 — P1-1~P1-3 수정 시 최소한 매핑/청크 로직은 단위 테스트 추가 권장
