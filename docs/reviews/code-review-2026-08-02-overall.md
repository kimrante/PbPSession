# 코드 리뷰 보고서 — 전반 (코드·유지보수·설계) (2026-08-02, v0.10.2)

> **처리 상태 (v0.10.4)** — A 전부 · B 전부 · C 전부 · E 전부 · F 전부 반영.
> **D(구조 리팩터링)는 미착수** — 이 문서가 "기능 변경 없이 별도 PR 권장"이라고 한 대로
> 남겨 두었다. 아래 본문은 지시서 원문 그대로 두고, 개별 항목의 수정 근거는 코드 주석에
> 항목 번호(A1·B3·C1 …)로 달아 두었다.
>
> 지시서와 다르게 처리한 것 하나: **E3**(여러 장 캡처의 2패스 렌더)은 완성 비트맵 위에
> `Canvas.drawText`로 낙관을 찍는 대신, **추정 장수를 먼저 믿고 1패스로 그린 뒤 실측
> 재분할로 장수가 달라졌을 때만 다시 그리는** 방식으로 했다. 목적(전 페이지 2회 렌더 제거)은
> 같고, 낙관을 픽셀로 덧그리지 않아 화면과 결과가 갈라질 여지가 없다.
>
> **배포 필요**: B1(messages create 규칙)과 E8(푸시 필터 기준)은 각각
> `firestore:rules`와 `functions` 배포를 해야 실제로 적용된다.

main 최신(`6215a6a`) 기준으로 **프로젝트 전체**(:shared / :app / :desktop / functions / firestore.rules)를
4개 영역으로 나눠 점검한 결과. **다른 세션에서 이 문서만 보고 수정 작업을 진행할 수 있도록**
항목마다 위치·재현·수정 방향을 명시한다. 기준 커밋: `6215a6a`. 라인 번호는 이 커밋 기준.

## 총평

- **기반 설계: 건강함.** "Room이 유일한 화면 소스, Firestore는 전송 채널" 원칙 일관 유지.
  remoteId 원자 선점 + 유니크 인덱스 + attach 기준선 reconcile의 멱등 전송 조합은 견고하고,
  read 과금 의식(증분 커서·적응형 폴링·members 리스너 합승)이 코드 전반에 배어 있다.
  빌드 구성(버전 카탈로그·R8)과 저장소 위생도 양호.
- **실질 버그 6건(A)** — 캡처 모드 탭 히트테스트(판정 오발 포함), 회전 중 캡처 렌더 실패,
  ImageGc↔아바타 수신 레이스, 초대 코드 충돌 고착, 데스크톱 SYSTEM 메시지 누락(1줄 수정),
  데스크톱 삭제 전파 사각지대.
- **구조적 약점 3갈래** — ① "같아야 한다" 주석 규율에 의존한 모바일↔데스크톱 축자 복제 5곳+
  (이미 한 번 겪은 드리프트 사고의 재발 후보), ② 갓 파일 3개(SyncManager 9책임 960줄 /
  ChatScreen 본체 ~480줄 / 데스크톱 `App()` 상태 var 25개+폴링 루프), ③ 문서화된 한계(S7)보다
  실제로 넓은 폴링 전파 구멍 — 단 B3(편집 PATCH에 syncAt 1필드)로 S7 자체는 공짜로 해소된다.
- **테스트의 유일한 큰 구멍** — 양 플랫폼 공용이자 shared 최대 파일인 LogExport(266줄)에 테스트 전무.

권장 수정 순서: **A1 → A5 → A3 → A4 → A2 → A6 → B 전부 → C(공유화) → E·F → D(리팩터링)**.
A5·B3·B4·B5는 각각 몇 줄짜리라 한 커밋에 묶어도 된다. D는 기능 변경 없이 별도 PR 권장.

---

## A — 실질 버그 (우선 수정)

### A1. [높음] 캡처 모드 탭이 말풍선 본체에서 안 먹힘 + 캡처 중 판정 카드 오발

- **위치**: `app/.../ui/chat/MessageBlock.kt:210`(바깥 래퍼의 `clickable(onTap)`) vs 내부
  `combinedClickable(onClick = {})` 240·374·491행, JudgeCard `clickable` 669행.
  `app/.../ui/chat/ChatScreen.kt:563`(`onJudgeTap`이 `capturing` 게이트 없음).
- **재현**: 캡처 모드 진입 → "끝 메시지를 탭하세요" 안내 → 말풍선·서술·잡담의 **표시 영역을
  탭하면 자식 클릭 핸들러가 탭을 소비**해 범위 선택이 안 되고 행의 빈 여백만 반응.
  더 나쁘게는 캡처 중 MyTurn 판정 카드를 탭하면 **주사위가 굴러가 버린다**.
- **수정**: MessageBlock에서 `onTap != null`(캡처 모드)일 때 내부 combinedClickable/JudgeCard
  clickable을 붙이지 않도록 분기 + ChatScreen의 `onJudgeTap`을 `if (!capturing)`으로 게이트.

### A2. [높음] 캡처 렌더 중 회전 → "높이 0" 실패 + Activity 누수 (설계 의도 R7 미달성)

- **위치**: `app/.../export/CaptureRenderer.kt:256-259`(클릭 시점 `activity.window.decorView`에
  ComposeView 부착), `app/.../ui/chat/CapturePreviewScreen.kt:68·80-96`(`busy`가 화면 로컬 remember),
  `app/.../export/CaptureHolder.kt:25`(프로세스 수명 scope). `ChatScreen.kt:199-236`의 최초 렌더도 동일.
- **재현**: 재렌더(배경/잡담 토글) 중 회전 → 파괴된 액티비티의 분리된 decorView에 붙어 컴포지션이
  시작되지 않고 8프레임 후 "레이아웃 높이가 0" 실패. 코루틴이 끝날 때까지 구 Activity 유지.
  `busy`도 회전 후 false로 초기화돼 이전 렌더 진행 중 저장·공유·재렌더가 겹칠 수 있다.
- **수정**: 렌더 진행 상태(busy 포함)를 CaptureHolder 전역으로 이동. Activity는 최신 것을
  조회(WeakReference 갱신)하거나, 실패 시 현재 액티비티로 1회 자동 재시도.

### A3. [높음] ImageGc가 방금 수신한 아바타 파일을 지울 수 있음 (FCM 콜드 스타트 창)

- **위치**: `app/.../data/ImageGc.kt:24`(avatars 디렉터리 sweep) + `app/.../PbpApp.kt:71-75`
  (sweep과 `syncManager.start()`가 onCreate에서 동시에 비동기 실행).
- **재현**: FCM으로 깨어난 콜드 스타트 직후 AvatarStore.resolve의 "파일 저장 → DB insert" 사이에
  sweep이 끼면 참조 집합에 없는 새 파일이 삭제된다. resolve는 최초 insert 때만 불리므로 그
  메시지의 아바타가 **영구 소실**. "시작 시점이라 편집 중인 파일이 없다"는 주석의 전제가
  sync 동시 시작으로 깨져 있다.
- **수정**: sweep에서 `lastModified()`가 최근(24시간 이내)인 파일은 건너뛰는 유예 —
  데스크톱 `desktop/.../data/ImageGc.kt`에도 동일 적용 검토.

### A4. [높음] 초대 코드 충돌 시 그 방은 영구 공유 불능

- **위치**: `app/.../sync/SyncManager.kt:443-456`(shareRoomInternal) —
  `setRemote(roomId, roomDoc.id, code)`로 코드를 **inviteCodes 매핑 검증 전에** 로컬 영구 저장.
- **재현**: 코드가 이미 다른 방에 매핑돼 있으면(자연 충돌 또는 규칙상
  `inviteCodes create: if signedIn()`이라 제3자 선점도 가능) error로 실패하는데, 재시도는
  `room.inviteCode ?: randomCode()`로 **항상 같은 충돌 코드를 재사용** → 회복 불능.
- **수정**: 매핑 충돌 분기에서 새 코드를 생성해 교체 후 재시도하거나, inviteCodes 문서 생성
  성공 후에만 로컬 저장.

### A5. [높음, 1줄] 데스크톱 SYSTEM 메시지가 상대 데스크톱에 영구 누락

- **위치**: `desktop/.../RoomSync.kt:161`(`systemMessageValues`) — `syncAt` 필드 없음.
  일반 메시지용 `messageValues`(:135)와 Android `SyncMapping.kt:29`는 모두 넣는다.
- **재현**: 데스크톱이 올리는 SYSTEM 메시지(참여 인사 `Main.kt:928`, 로그 초기화 안내 `:518`)는
  상대 데스크톱의 syncAt 증분 질의(`Firestore.kt:621-626`)에 아예 걸리지 않는다.
- **수정**: `messageValues`처럼 `"syncAt" to FirestoreRest.ServerTime(...)` 추가.

### A6. [높음] 데스크톱은 상대의 삭제·로그 초기화를 영영 반영 못 함 (문서화된 한계보다 넓음)

- **위치**: `desktop/.../Main.kt:497-499`(resetRoomLogs 주석 "상대 기기의 REMOVED 리스너로 전파" —
  그건 모바일 SDK 이야기) + 폴링 모델 전반 + `desktop/.../data/RoomCache.kt`(유령 메시지 영구 보존).
- **재현**: architecture.md는 "삭제는 방 재입장 시 반영"이라 하지만, 폴링은 문서 부재를 감지할 수
  없고 파일 캐시가 재입장 후에도 유령을 복원한다. 데스크톱↔데스크톱 조합에서 두 로그가 조용히
  갈라진다.
- **수정(추가 read 0건)**: 리셋 시 방 문서에 `logsClearedAt` 타임스탬프를 쓰고, 60초 메타 폴
  (`Main.kt:384`)에서 감지해 그 시각 이전 로컬 메시지·캐시를 비우기. 개별 메시지 삭제까지
  전파하려면 tombstone이 필요하니 이번엔 로그 초기화만이라도.

---

## B — 동기화·보안

### B1. [높음] messages create가 authorUid 사칭을 허용

- **위치**: `firestore.rules:30` — `allow read, create: if isMember(roomId);`
- **문제**: 멤버가 상대 UID를 authorUid로 넣은 메시지를 만들 수 있다. update 규칙("작성자
  본인만 수정")의 근거인 authorUid 자체가 신뢰 불가가 되고, 수신 측 "내 발신" 필터와
  Functions의 발신자 제외 판정도 우회된다.
- **수정**: `allow create: if isMember(roomId) && request.resource.data.authorUid == request.auth.uid;`
  (구버전 deviceId 발신 하위호환 필요 여부는 배포 시점에 확인.) 규칙 배포 절차는
  docs/firebase-security.md.

### B2. [높음] attach/detach 세대 혼선 → 유령 리스너 = read 과금 누수

- **위치**: `app/.../sync/SyncManager.kt:696-702` — 리스너 등록 후 `attachedRemotes.containsKey`로만
  취소 판단.
- **재현**: 등록과 검사 사이에 detach→reattach가 끼면 키가 (새 attach 것으로) 다시 존재해 구
  registration이 제거되지 않고, `listeners[localRoomId] = registration`이 새 attach의 것을 덮어써
  이후 detach가 엉뚱한 쪽을 제거 → **방 전체 스냅샷을 계속 받는 유령 리스너**(로그 초기화 연타로
  재현 가능).
- **수정**: attach마다 세대 토큰(증가 카운터)을 만들고 등록 후 "내 세대가 현역인가"를 비교해
  아니면 즉시 remove; listeners 저장도 같은 조건으로.

### B3. [권장, 몇 줄] 편집 PATCH에 syncAt 갱신 → 문서화된 한계 S7이 공짜로 해소

- **위치**: `desktop/.../data/Firestore.kt:651-656`(updateMessage — body·editedAt만 갱신) +
  모바일 pushEdit 동일.
- **근거**: 편집 시 `syncAt = ServerTime(now)`를 함께 밀면 상대 데스크톱의 증분 질의에 다시
  걸린다. editedAt 병합 로직(C10, `Main.kt:354-358`)이 이미 있어 재수신은 안전(멱등).
  architecture.md의 "데스크톱은 30초 윈도 밖 편집을 반영 못 한다"(S7) 항목 삭제 가능.

### B4. [중간] 캐릭터 emoji·nameColor만 바꾸면 상대에게 전파 안 됨

- **위치**: `app/.../sync/SyncManager.kt:326-330`(pushCharacters) — 중복 억제 signature가
  NAME·STATS만 포함.
- **수정**: signature에 EMOJI·NAME_COLOR 포함(payload 전체 직렬화 비교가 안전).

### B5. [중간] wipeMessages가 uploaded=0·remoteId 확정 문서를 남김

- **위치**: `app/.../sync/SyncManager.kt:549-569` + `Daos.kt:154` — 삭제 대상이 `uploaded=1`인
  remoteId뿐. "set() 성공 직후·setUploaded 직전 크래시"로 남은 문서가 서버 고아로 상대에게만 보인다.
- **수정**: wipe용 조회는 `uploaded` 조건 없이 `remoteId IS NOT NULL` 전체(삭제는 멱등).

### B6. [중간] 데스크톱 레거시 스윕의 윈도가 좁아 안전망에 구멍

- **위치**: `desktop/.../Main.kt:331-340` + `ui/Dimens.kt:70` — 10분 주기 스윕이 일반 폴과 같은
  윈도(`max(폴 주기, 직전 공백)×2`, 보통 5~60초)를 쓴다. syncAt 없는 구버전 메시지가 커서보다
  윈도 이상 과거로 밀리면 스윕으로도 영구 누락.
- **수정**: `legacySweep` 회차에는 `windowMs = LEGACY_SWEEP_MS`(600초)를 사용.

### B7. [낮음] members 리스너가 죽으면 읽음·타이핑 표시가 조용히 사망

- **위치**: `app/.../sync/SyncManager.kt:253-292`(observePeerState) — error 콜백이 `PeerState()`만
  방출하고 끝. 메시지 리스너의 recoverAuth(N5) 같은 복구 경로 없음.
- **수정**: error 시 flow를 close(error)하고 호출부 `retryWhen` 재구독, 또는 recoverAuth 훅 공용화.

---

## C — 공유화 (:shared 이동, 드리프트 방지)

전부 플랫폼 의존 없는 순수 함수/상수인데 양쪽에 축자 복제돼 있고 "같아야 한다" 주석으로만
동기화를 강제한다. GmSpeech·LogExport를 옮긴 것과 같은 방식으로 이동.

### C1. [높음] 캡처 페이지 분할 계산 — 모바일은 이미 한 번 튜닝돼 드리프트 이력 있음

- **위치**: `desktop/.../export/CaptureRenderer.kt:144-174` ↔ `app/.../export/CaptureRenderer.kt:160-208`
  — `splitByHeight`·`estimate`·`lines`·`CHROME_DP`·`SHEET_WIDTH_DP`·`RENDER_DENSITY`·`MAX_HEIGHT_PX` 복제.
- **수정**: shared에 `CaptureLayout` 오브젝트를 만들어 `(type, isOoc, senderIsGm, body)` 기반
  estimate/splitByHeight/상수를 이동. `formatDateRange`/`dateOnly`(app:423, desktop:305)도 함께.

### C2. [높음] captureRangeAfterTap + timeRangeLabel — 테스트는 앱 쪽에만 존재

- **위치**: `app/.../ui/chat/CaptureBar.kt:185-195` ↔ `desktop/.../CaptureBar.kt:164-174`.
- **수정**: shared `Capture.rangeAfterTap(range, tapped)`로 이동, 기존 CaptureRangeTest도 :shared로.

### C3. [중간] CAPTURE_MAX = 200 리터럴 — 주석으로만 모바일 PAGE_SIZE와 결속

- **위치**: `desktop/.../CaptureBar.kt:28` ↔ `app/.../ui/chat/ChatScreen.kt:109`.
- **수정**: shared 상수(`CAPTURE_MAX_MESSAGES`)로 승격.

### C4. [중간] 모바일 backgroundPresets가 shared 선언과 리터럴 복제

- **위치**: `app/.../ui/theme/Tokens.kt:151` — 바로 위 141행 주석이 ":shared Palette가 단일
  출처"라 선언하는데 5개 리터럴 복제. 데스크톱(`ui/Theme.kt:72`)은 shared 참조라 shared 값이
  바뀌면 모바일만 조용히 갈라진다.
- **수정**: `val backgroundPresets = com.pbp.shared.Palette.backgroundPresets` 한 줄로 교체.

### C5. [낮음] 데스크톱 ChatPane의 표시 규칙 함수들도 후보

- **위치**: `desktop/.../ChatPane.kt:579-604·798`의 `captureMarkOf`·`isContinuation`·
  `sharesTimeLabel`·`quoteContent` ↔ `app/.../ui/chat/MessageBlock.kt:108-568`.
- **수정**: C1·C2 작업 시 함께 이동 검토(전부 순수 함수).

---

## D — 구조 리팩터링 (기능 변경 없음, 별도 PR 권장)

### D1. SyncManager(960줄, 책임 9개) 분할

- **위치**: `app/.../sync/SyncManager.kt` — 익명 인증·멤버십·읽음·타이핑·캐릭터 명단·FCM 토큰·
  공유/참여·메시지 송수신 reconcile·로그 wipe. 상태 맵 8개.
- **수정**: AvatarStore(B5) 분리 전례대로 **PresenceSync**(읽음·타이핑·캐릭터 — members 문서 담당) /
  **FcmRegistrar**(토큰) / **RoomSharing**(share/join/leave/wipe)을 떼고 SyncManager는 메시지
  리스너·아웃박스만. Repository↔SyncManager의 `var ...? = null` 지연 배선(미배선 시 조용한
  no-op)도 이때 생성자 주입으로.

### D2. 데스크톱 App() 갓 컴포저블 — 폴링 루프를 컴포지션에서 분리

- **위치**: `desktop/.../Main.kt:154-1211` — 상태 var 약 25개 + 전 도메인 로직. 폴링 루프(279-422)는
  UI와 무관한 순수 동기화인데 컴포지션 수명에 결박돼 단위 테스트 불가.
- **수정**: UI 없는 상태 홀더(예: `ChatController` — rooms/messages/sessions 소유, 폴 루프는
  suspend fun)로 추출하고 App()은 배선만.

### D3. ChatScreen 캡처 상태 기계 분리

- **위치**: `app/.../ui/chat/ChatScreen.kt:284-767`(본체 ~480줄) — 캡처 범위 상태 기계(337-373)·
  스크롤 추적(391-415)·다이얼로그 5종·타이핑 만료·상태 바 색.
- **수정**: 캡처 선택 상태(start/end/idx/tap)를 `rememberSaveable` 기반 홀더 클래스로, 스크롤
  추적을 별도 컴포저블로. `captureRangeAfterTap` 등 순수 함수는 이미 분리돼 있어 이식 용이.

### D4. CapturePreviewScreen만 VM 없이 오케스트레이션

- **위치**: `app/.../ui/chat/CapturePreviewScreen.kt:79-141·228-291` — 저장·공유·재렌더와
  SharedPreferences 쓰기가 전부 클릭 핸들러에. A2(busy 전역화)와 같이 처리하면 자연 해소.

---

## E — 기능·품질 소소 (일괄 처리 가능)

- **E1.** 캐릭터 삭제만 확인 다이얼로그 없음 — `app/.../ui/profile/ProfileEditScreen.kt:471-477`.
  방 삭제·메시지 삭제와 동일한 AlertDialog 추가. (되돌릴 수 없는 데이터 손실.)
- **E2.** 타이핑 만료 타이머가 화면 전체를 초당 2회 리컴포즈 — `ChatScreen.kt:301-311`.
  만료 판정·라벨 계산을 소형 컴포저블로 하향.
- **E3.** 여러 장 캡처가 전 페이지를 2번 렌더(페이지 번호 삽입 재렌더) —
  `app/.../export/CaptureRenderer.kt:142-149`. 완성 비트맵 위 `Canvas.drawText` 낙관으로 1패스화
  (32,000px 기준 최대 8회·~184MB 누적 할당 절감).
- **E4.** 데스크톱 참가/생성 경로가 IO 코루틴에서 Compose 상태 직접 변경 —
  `desktop/.../Main.kt:910-940·946-966`, `Overlays.kt:387-394`. resetRoomLogs(M2)처럼
  `withContext(Dispatchers.Main)`으로 통일. (메타 폴의 `rooms` read-modify-write와 갱신 유실 교차 가능.)
- **E5.** CoC7 하향 판정 대실패가 `total == 100`만 — `shared/.../Rules.kt:62`. 룰상 목표치 50
  미만이면 96~100이 대실패. `threshold < 50 && total >= 96 -> FUMBLE` 분기 + 테스트.
  의도한 단순화라면 KDoc에 명시.
- **E6.** 캡처 결과가 화면과 갈라지는 지점 2곳 — `app/.../export/CaptureRenderer.kt:301·348-354`.
  CaptureSheet가 themeColor를 DEFAULT로 고정(시간 표기 색), judgeState를 Waiting 고정(완료된
  판정 카드가 ⋯로 찍힘). Request에 themeColor를 담고 rolledRefs 계산을 시트에서도 수행.
- **E7.** 캡처 모드 중 `subList().toList()`+`estimateHeightPx`(최대 200건)가 매 리컴포지션 재계산 —
  `ChatScreen.kt:598-608`. `remember(messages, captureIdx)` 캐시.
- **E8.** Functions의 오래된 메시지 필터가 발신 기기 시계 기준 — `functions/index.js:29`.
  시계가 2분 뒤진 기기의 푸시가 조용히 누락("가끔 알림 안 옴"). 서버 시각 `syncAt` 비교로 변경
  (백필 차단 목적 P8은 그대로 성립).
- **E9.** 데스크톱 "전부 잡담" 캡처 실패가 System.err로만 — `desktop/.../Main.kt:582-585`.
  같은 함수의 V3 원칙(621행)대로 `captureError`에.
- **E10.** d66에 비교식(`1d66<=30`)이 파싱 통과 — `shared/.../DiceBot.kt:75-77`. d66은 십의자리
  합성값이라 크기 비교 무의미. d66 + `op != null`이면 거부.
- **E11.** GmSpeech 인용 정규식이 줄바꿈 포함 매칭 — `shared/.../GmSpeech.kt:15`. 여러 문단에
  홀수 따옴표면 문단을 가로지르는 거대 인용. `[^"\n]`으로 한 줄 제한 검토.
- **E12.** 방 목록 relativeTime이 컴포지션 시점 1회 계산 — `app/.../ui/roomlist/RoomListScreen.kt:438`.
  "방금"이 갱신 안 됨. 분 단위 틱(produceState) 또는 수용 결정.
- **E13.** 데스크톱 FileDialog를 IO 스레드에서 생성·표시 — `Main.kt:629·748`, `DesktopImages.kt:115`.
  AWT EDT 규칙 위반(플랫폼별 교착 소지). 표시는 Main(EDT), 파일 쓰기만 IO.
- **E14.** 데스크톱 ImageGc가 config 라이브 리스트를 락 없이 순회 — `desktop/.../data/ImageGc.kt:21-24`.
  `roomsCopy()` 패턴으로 스냅샷 순회.
- **E15.** sendWithRetry가 주석("401/403")과 달리 401만 처리 — `desktop/.../data/Firestore.kt:201-209`.
  403 PERMISSION_DENIED도 토큰 무효화·재시도에 포함(또는 주석 수정).

---

## F — 테스트·위생

- **F1. [높음] LogExport(266줄) 테스트 전무** — shared 최대 파일(HTML 이스케이프·GM 서술/인용
  분기·잡담·날짜 구분선·MIME 스니핑)인데 테스트 0. 고정 메시지 목록으로 `buildHtml` 핵심 조각
  (이스케이프된 `<`, GM 인용 분리, OOC 접두) 검증 + `escape`/`hex`/`bytesToDataUri` 단위 테스트.
  `Palette.darken`/`nameColorForLight`도 무테스트.
- **F2.** 파일 분리(B3) 때 복사된 미사용 import 잔존 — `ChatInput.kt`·`ChatDialogs.kt`·
  `MessageBlock.kt` 상단 수십 개, 데스크톱 6개 파일의 동일 ~105줄 import 블록(`RoomSync.kt:3-104`는
  Compose import를 거의 안 씀). IDE optimize-imports 일괄 적용.
- **F3.** 죽은 코드에 현행과 다른 주석 — `SyncManager.kt:61`(`isAttached`),
  `Daos.kt:136-137`(`existingRemoteIds`). 삭제 또는 주석 현행화.
- **F4.** GM 인용 말풍선 색이 두 값 — `shared/.../Palette.kt:26`(0xFFE7E2D4) vs
  `LogExport.kt:104`("#EFE9D8" 하드코딩). 종이 톤 치환 의도라면 Palette에 export용 상수로 명명.
- **F5.** 데스크톱 CaptureRenderer가 `Protocol.MessageType.TEXT` 대신 리터럴 비교 —
  `desktop/.../export/CaptureRenderer.kt:166`. 상수로 교체.

---

## 기록만 (수정 불요, 설계 결정 확인)

- **inviteCodes 무만료 + members create가 roomId 지식만 요구** (`firestore.rules:45`) —
  접근 통제의 근거가 "roomId 비밀 유지"인데 roomId는 FCM 페이로드·로그에 흘러 다닌다.
  2인 앱 위협 모델에선 수용 가능 — 의도적 결정임을 여기 기록. 탈퇴/방 삭제 시 inviteCodes
  무효화(규칙 `delete` 완화)는 추후 검토.
- **데스크톱은 선택된 방만 폴링** (`Main.kt:279`) — 비선택 방은 트레이 알림 없음. read 절감
  의도로 판단, 수용. (원하면 비선택 방 60초 저빈도 커서 확인 1쿼리 절충 가능.)
- **functions가 메시지당 members 전체 get(2 read)** (`index.js:35-39`) — 현재 규모에선 절대액이
  작아 수용. 트래픽 증가 시 room 문서에 토큰 denormalize.
- **데스크톱에 캡처 총높이 사전 거절(R2 상당)·추정 표시 없음** — PNG 바이트 축적이라 메모리
  특성이 달라 버그는 아님. 비대칭은 C1 공유화 때 자연 정리.
