# 코드 리뷰 보고서 — 읽음 확인 기능 점검 (2026-07-30, v0.5.3)

읽음 확인이 포함된 v0.5.3 커밋(`896c095`)에 대한 집중 점검 결과 — ① 기능 구현이 설계 계약대로인지,
② **폴링/read 비용이 이전 대비 과도하게 늘지 않았는지**, ③ 크래시 위험. 같은 커밋에 묶인 부수 변경
(말풍선 글씨색·루비 신문법·참여 인원 제거·시간 접기·길게 눌러 복사)도 함께 점검했다.
**다른 세션에서 이 문서만 보고 수정 작업을 진행할 수 있도록** 항목마다 위치·재현·수정 방향을 명시한다.
기준 커밋: `896c095`. 라인 번호는 이 커밋 기준.

## 총평

**비용 우려는 해소, 크래시 경로 없음, 기능 결함 소수.**

- **비용 (핵심 질문에 대한 답)**: 읽기/쓰기 **핑퐁 증폭 없음** 확인 — 내 lastReadAt 쓰기는 상대의 UI에만
  전달되고 상대의 쓰기를 유발하지 않는다. 증분은 메시지당 선형(+1 write + ~2 read, **채팅 화면이 열려
  있을 때만**), 유휴 비용 증가 ~0, 그리고 참여 인원 표시 제거로 **데스크톱은 오히려 시간당 120 read
  절감** — 대기 시간이 긴 사용자 기준 v0.5.3이 이전보다 싸다.
- **크래시**: Room v10 마이그레이션(엔티티-SQL 정합 확인), 구버전 상호운용(senderTextColor 양방향 무해),
  루비 구문법 제거(기존 메시지는 리터럴 렌더링 — 테스트로 고정, 파서 선형이라 행·크래시 없음), 읽음 배지
  널/빈 목록/회전 안전성 — 전부 정적 추적으로 안전 확인. 크래시 경로 0건.
- **수정 대상**: 중간 2건(R1 리스너 churn, R2 데스크톱 시간 접기 사문화)과 낮음 4건.

설계 계약("members/{uid}에 platform·lastReadAt, 상대가 android일 때만 표시, 데스크톱은 platform만,
화면 열림 중만 구독, 같은 값 재쓰기 안 함")은 절별 검증 결과 본질적으로 충실히 구현됐다. 이탈은 R1(구독이
메시지마다 재생성)과 R5(백그라운드에서 구독 유지) 두 가지다.

## 비용 실측 요약 (2인 android↔android 방, 사용자·시간당)

| 시나리오 | v0.4.1 | v0.5.3 | 증감 |
|---|---|---|---|
| 활성 채팅(분당 2건) — 쓰기 | 60 (메시지) | 60 + ~61 (읽음 영수증) | **+61/h (~2배)** |
| 활성 채팅 — 읽기 | ~60-120 | +60-120 (members 리스너: 상대 쓰기 + 내 쓰기 에코) | **+60-120/h (~2배)** |
| 유휴(방 열어둠) | 0 | 0 (진입 시 초기 스냅샷 2 read + 쓰기 1회) | **~0** |
| 데스크톱(방 열어둠) | countMembers 60초 폴 = 120 read/h | 제거됨, 신규 쿼리 없음 | **−120/h** |

금액으로는 최악 케이스 시간당 ~$0.0001 수준 — 무시 가능. 중요한 성질(증폭 없음, 유휴 무료, 데스크톱 순절감)이
전부 성립한다. **단 R1을 고치지 않으면 churn 재구독으로 활성 시 최대 +240 read/h가 얹힐 수 있다.**

---

## R — 수정 항목

### R1. [중간] members 리스너가 markRead마다 해제·재생성됨 (1줄 수정)

- **위치**: `app/src/main/java/com/pbp/app/ui/chat/ChatScreen.kt:127-129` —
  `peerReadAt = room.flatMapLatest { repo.observePeerReadAt(it?.remoteId) }`
- **원인** *(직접 확인)*: `room`은 **전체 `ChatRoom` 엔티티** Flow인데 이 엔티티에 로컬 `lastReadAt`이
  포함된다(`Entities.kt:33`). `markRead()`가 진입·전송·수신 메시지마다 `setLastReadAt(roomId, now)`를
  쓰므로(`PbpRepository.kt:110-115`) 매번 새 `ChatRoom`이 방출 → `flatMapLatest`가 `callbackFlow`를 취소 →
  **Firestore members 리스너가 메시지마다 제거·재등록**된다. resume token 덕에 과금은 대부분 절약되지만
  건마다 네트워크 왕복이 생기고 최악 churn당 2 read(활성 시 최대 +240 read/h)가 추가된다.
- **수정**: `room.map { it?.remoteId }.distinctUntilChanged().flatMapLatest { repo.observePeerReadAt(it) }` —
  remoteId는 공유 시 1회만 바뀌므로 리스너가 화면당 1회만 등록된다.

### R2. [중간] 데스크톱 "동일 시각 시간 접기"가 사문화 — 기능 미동작

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/ChatPane.kt:365`(`sharesTimeLabel` 정의),
  `:209-212`(유일한 `MessageBlock` 호출부 — `showTime`을 계산·전달하지 않아 기본값 `true` 고정)
- **현상**: 커밋 메시지는 "동일 시각 연속 메시지 시간 접기 (**양 플랫폼**)"이라고 주장하지만 데스크톱은
  항상 시간이 표시된다. 크래시는 아니고 기능 회귀.
- **수정** (items 루프에서, 목록은 오름차순이므로 "다음" = `index + 1`):
  ```kotlin
  val showTime = !sharesTimeLabel(message, messages.getOrNull(index + 1))
  MessageBlock(message, myUid, room, avatarCache, firestore, grouped, showTime = showTime, onLongPress = onMessageLongPress)
  ```

### R3. [낮~중간] "읽음" 배지가 표시되지 않는 케이스 2종

- **위치**: `app/src/main/java/com/pbp/app/ui/chat/ChatScreen.kt:331-333`(`readMarkId` — 타입 무관 최신 본인
  메시지 선택), `MessageBlock.kt:129-198`(SYSTEM·OOC·DICE 분기와 GM 서술 마감 분기는 `showRead` 무시),
  배지가 `TimeStamp` 내부에서만 렌더링되어 `showTime=false`(동일 시각 접힘)면 함께 숨겨짐.
- **재현**: ① 내 마지막 메시지가 다이스 판정이면 상대가 읽어도 "읽음"이 어디에도 안 뜬다(이전 말풍선으로
  폴백하지 않음). ② 읽음 대상 메시지가 같은 분 연속 전송의 중간이면 접힌 시간과 함께 배지도 숨겨져, 상대가
  그 연속의 마지막까지 읽기 전엔 표시가 없다.
- **수정 방향**: `readMarkId`를 **배지 렌더링 가능한 메시지**(TEXT 말풍선·GM 인용 마감) 중 최신으로 선택
  하거나, 배지를 `TimeStamp`에서 분리해 `showTime`과 독립적으로 렌더링. 의도적으로 "말풍선에만 표시"라면
  그 결정을 주석으로 명시(현재는 의도인지 누락인지 구분 불가).

### R4. [낮음] GM 다중 인용 메시지에 "읽음" 배지 중복 표시

- **위치**: `app/src/main/java/com/pbp/app/ui/chat/MessageBlock.kt:204-217` — GM 분기가 `parts.forEach`에서
  **모든** Quote 파트에 `showRead`를 전달. 캐릭터 분기는 `index == parts.lastIndex` 게이트가 있음(`:242`).
- **재현**: 읽음 대상이 인용부 2개 이상 포함 GM 메시지면 인용 말풍선마다 "읽음"이 붙는다.
- **수정**: 캐릭터 분기와 동일하게 마지막 Quote 파트에만 전달.

### R5. [낮음] 백그라운드에서 members 구독 유지 — "화면 열림 중만" 계약 이탈

- **위치**: `ChatScreen.kt`의 `peerReadAt` 소비 — `collectAsState`(컴포지션 스코프)라 채팅 화면을 위에 둔 채
  홈으로 나가면 리스너가 프로세스 종료까지 유지되어 상대 영수증 변경마다 read 과금.
- **판단**: 메시지 리스너도 (의도적으로) 앱 수명 유지이므로 영향은 증분뿐. `collectAsStateWithLifecycle`로
  전환하거나, 계약 문구를 "화면이 컴포지션에 있는 동안"으로 수정해 수용 — 어느 쪽이든 코드와 문서 일치시킬 것.

### R6. [낮음, 기록] 재쓰기 방지 가드가 프로세스 수명 한정

- **위치**: `SyncManager.kt:196-197, 208` — `pushedReadAt`이 메모리 맵이라 재시작 후 첫 방 진입 시 동일 값
  1회 중복 쓰기. 방·프로세스당 1 write라 수용 가능 — 기록만. (동시 진입+수신 레이스의 이중 쓰기도 멱등·단조라 무해.)

### 문서 잔재 (1줄씩)

- 루비 구문법: `shared/src/main/kotlin/com/pbp/shared/PbpMarkup.kt:6` 파일 헤더, `docs/architecture.md:26`,
  `docs/PbP-design-spec.md:62`가 제거된 `|漢字《독음》` 문법을 여전히 설명 — 신문법으로 갱신.

---

## 수용 확인된 엣지 (수정 불요, 알려진 한계로 인지만)

- **본인의 두 번째 Android 기기**: uid가 설치별이라 같은 사람의 폰 2대가 서로 "상대"로 계산되어 거짓 읽음이
  가능. 1:1 설계상 수용. (>2인 방에선 `maxOrNull()`이 "누군가 읽음" 의미가 됨 — 동일하게 설계 범위 밖.)
- **레거시 member 문서**(platform 필드 없음): 상대의 첫 영수증 쓰기가 필드를 자가 치유할 때까지 배지 미표시 —
  의도된 점진 전환, 닭-달걀 없음(영수증 쓰기는 내 플랫폼과 무관하게 실행됨을 확인).
- **오프라인**: 영수증이 SDK 영구 캐시에 큐잉되어 온라인 복귀 시 전달(유실 아님), 가드는 ack 후 갱신이라 정합.
- **members 리스너의 PERMISSION_DENIED 복구 없음**: 메시지 리스너와 달리 recoverAuth 미연동 — 배지만
  사라지고 화면 재진입으로 복구. 수용.

## 검증 정상 (재확인 불필요 — 추적 완료)

- **계약 절별**: platform·lastReadAt 기록(`SyncManager.kt:207-222`, `ensureMembership :183-194`), android
  필터(`observePeerReadAt :228-246`), 데스크톱은 `updateMask`로 platform만 패치(lastReadAt 클로버 불가,
  `desktop/data/Firestore.kt:332-346`)·읽지도 않음, `WhileSubscribed(5000)` 화면 이탈 ~5초 후 해제,
  동일 값 가드(프로세스 내), 참여 인원 코드 완전 제거(grep 잔재 0).
- **핑퐁 없음**: markRead 트리거는 메시지 삽입·화면 진입뿐 — member 문서 변경으로는 절대 발화하지 않음.
- **시맨틱**: lastReadAt = 수신 메시지의 `createdAt`(발신자 시계)이라 배지 비교가 **단일 시계 도메인** —
  시계 오차 무영향(우아한 설계). 배지 이동·편집·삭제 폴백 전부 정상.
- **규칙**: members `read: isMember` — 컬렉션 리스너 허용, 영수증은 본인 문서만 쓰기. 정합.
- **크래시 스윕**: Room v10(ALTER 정합·등록 확인), OwnerProfile/RecentColors prefs 양방향 무해,
  senderTextColor 널 관통(구버전 상대 양방향 무해, `Color(Long)` 마스킹으로 크래시 불가, export 조건부),
  루비 신규 정규식 선형(백트래킹 폭주 없음)·테스트 갱신 완료(제거 동작 단언하는 테스트 없음),
  읽음 UI `lastOrNull`·널 안전·회전 안전(`stateIn` + VM 보존), 길게 눌러 복사 양 플랫폼 안전,
  Android 시간 접기 널 안전, 데스크톱 config Gson 양방향 관용.

## 테스트 권고

- R1 수정 후: 화면 진입 → 메시지 10건 수신 시 members 리스너 등록이 1회뿐인지 로그로 확인.
- R3 수정 시: "마지막 본인 메시지가 DICE" 케이스의 배지 위치를 명시하는 UI 스냅샷/수동 시나리오 추가.
- 재쓰기 가드는 `pushedReadAt` 로직을 순수 함수로 빼면 단위 테스트 가능(선택).
