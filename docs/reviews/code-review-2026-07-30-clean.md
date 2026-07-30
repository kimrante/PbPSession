# 코드 리뷰 보고서 — 유지보수성 · 코드 클린 (2026-07-30)

> **반영 완료 (v0.4.0)** — 권장 6개 PR 전부 적용:
> PR1 `:shared` 모듈(로직 6종+테스트 44개)·Protocol 상수 / PR2 클립보드 dedup·
> 프로필 다이얼로그 분리·ImageSizes·죽은 코드 / PR3 데스크톱 7파일 분할 +
> C2·C3·D1·D2·D3·D4·D6 / PR4 ChatScreen 3분할 + C4·D3 / PR5 LogExporter 통합
> (+Palette) / PR6 AvatarStore·순환 참조 해소·Routes·docs 정리.
> 파일 크기: 데스크톱 Main.kt 2,939 → 865, ChatScreen.kt 1,208 → 471,
> RoomListScreen.kt 881 → 580.
> 미적용(의도): B5의 SyncManager 3분할(문서 권고대로 현 상태 유지),
> 타입 스케일 이탈 정리(디자인 판단 필요).
> `PbpDimens.sp1..sp6` → `gap1..gap6` 개명도 완료(호출부 91곳, v0.4.1).

main 최신 기준으로 **스파게티 구조 여부 · 무리한 하드코딩 · 유지보수 관점 코드 클린**을 점검한 결과.
**다른 세션에서 이 문서만 보고 작업할 수 있도록** 항목마다 위치·이유·수정 방향·규모(S/M/L)를 명시한다.
기준 커밋: `d6f538f`. 라인 번호는 이 커밋 기준.

## 총평

**스파게티 아님.** 주석 규율(결정마다 리뷰 항목 ID 인용), 얇은 ViewModel, 단방향 상태 흐름, 잘 만든 토큰
파일(`Tokens.kt`) 등 솔로 프로젝트치고 이례적으로 잘 관리되고 있다. 부채는 넓게 퍼진 게 아니라 **몇 지점에
집중**되어 있다:

1. **모듈 간 로직 복제 + 데스크톱 테스트 전무** — 6개 로직 객체·LogExporter·팔레트가 손 동기화 쌍둥이인데
   테스트는 전부 Android 쪽에만 있어(9스위트 63개), 데스크톱 사본은 조용히 깨져도 신호가 없다.
   **주변부는 이미 실제로 드리프트했다**(아래 A1). 향후 6개월 유지보수 비용의 최대 항목.
2. **거대 파일 2개** — 데스크톱 `Main.kt` 2,939줄(앱 전체가 한 파일), Android `ChatScreen.kt` 1,208줄.
   둘 다 내부 경계는 이미 깨끗해서 분할은 기계적 작업이다.
3. **프로토콜 문자열 산재** — Firestore 필드·컬렉션명이 4곳(Android/데스크톱/functions/rules), 3개 언어에
   리터럴로 흩어져 있어 `authorUid` 하나 바꾸려면 6개 파일을 고쳐야 한다.

권장 순서: **A1 → A2 → C1 → B1(데스크톱 분할) → B3(ChatScreen 분할) → D → 나머지**.

---

## A — 최상위 레버리지: 모듈 간 복제 해소

### A1. `:shared` 모듈 Phase 1 — 순수 로직 6종 + 테스트 44개 이동 (S~M, 최우선)

- **현황**: `desktop/logic/Logic.kt:5-8` 스스로 "복제본, TODO: KMP :shared"라고 적어둔 상태. 실측 결과:
  - `DiceBot`·`PbpMarkup`·`GmSpeech`·`CharacterCodec` — 아직 동일 (수정 사이클이 성실히 미러링됨)
  - **`Rules` — 이미 드리프트**: 앱은 `Rules.Outcome` 상수(`CRITICAL`…)를 쓰는데 데스크톱(`Logic.kt:221-232`)은
    `"critical"`/`"fumble"` 맨 리터럴. 이 값은 **Firestore `diceOutcome`으로 상대 클라이언트가 읽는 와이어
    값**이라, 한쪽에 등급 하나 추가하면 다른 쪽에선 라벨 없이 렌더링된다. 데스크톱엔 `Rules.all` 레지스트리도
    없어 룰 추가 시 `Main.kt:657,668`의 `"coc7"` 리터럴까지 3곳 수정 필요.
  - **`ProfileStats` — 파이프라인 드리프트**: 앱은 저장 시(`encode`) 정리, 데스크톱은 치환 시(`sanitize`) 정리 —
    현재 출력은 같지만 다음 수정이 한쪽에만 반영될 구조.
  - **데스크톱 테스트 소스셋 자체가 없음** (`desktop/src/main`뿐. `build.gradle.kts:23`의
    `testImplementation(libs.junit)`은 고아 선언).
- **KMP 불필요**: 양쪽 다 Kotlin/JVM 17이므로 **plain `kotlin("jvm")` 모듈**이면 된다. `architecture.md`와
  Logic.kt TODO의 "KMP" 프레이밍은 비용 과대평가.
- **작업**: ① `settings.gradle.kts`에 `include(":shared")` ② ~12줄 `shared/build.gradle.kts`(kotlin-jvm +
  gson + junit — 전부 카탈로그에 있음) ③ `DiceBot`·`PbpMarkup`·`GmSpeech`·`Rules`·`CharacterCodec`·
  `ProfileStats`를 원문 이동 ④ 해당 테스트 6스위트를 `shared/src/test`로 이동 ⑤ 양쪽 build 파일에
  `implementation(project(":shared"))` 1줄 ⑥ import 정리. 유일한 실제 리팩토링: ProfileStats의
  `sanitize`/`paletteSuggestions` 시그니처 분기 통일(소규모).
- **효과**: 데스크톱 다이스·마크업·룰이 즉시 기존 44개 테스트 아래 들어감. 이후 룰/마크업 수정이 1회 작업이 됨.

### A2. 프로토콜 상수 객체 + 스키마 표 (S)

- **현황**: Firestore 스키마가 **4곳·3언어에 문자열 리터럴로 존재** — `authorUid` 변경 시 6개 파일
  (SyncMapping.kt, SyncManager.kt:531, 데스크톱 Firestore.kt:413 + structuredQuery JSON 템플릿 :306·:454,
  Main.kt 4곳, index.js:32/42, firestore.rules:32). 컬렉션명 5종, member 필드, `"TEXT"/"DICE"/"SYSTEM"`
  (데스크톱에 ~10곳 산재 — 앱은 enum), `diceOutcome` 값도 동일 상황. 숫자: 아바타 256px이 양 모듈에 독립
  선언(`SyncManager.kt:834` / `Main.kt:2553`) — 어긋나면 아바타 해시·문서가 갈라진다.
- **작업**: `:shared`에 `Protocol` 객체(컬렉션명·필드명·MessageType·Outcome·아바타 크기). JS와 rules는 소비
  불가하므로 `docs/architecture.md`에 스키마 표 + "Protocol.kt / index.js / firestore.rules 3곳 일치 필수"
  주석. A1과 같은 PR로 처리 권장.

### A3. LogExporter 통합 — Phase 2 (M)

- **현황**: 앱(251줄)/데스크톱(245줄)에 ~200줄(말풍선·서술·다이스·날짜 HTML + CSS 25줄)이 축자 복제.
  **이미 사용자 가시 드리프트 발생**: 앱은 `roomIcon` 파라미터로 `<h1>{icon} {name}</h1>` 렌더링
  (`app LogExporter.kt:24,174`), 데스크톱엔 없음 — 같은 방을 폰/PC에서 내보내면 헤더가 다르다.
  테스트 12개는 앱 쪽만. 팔레트 상수(`nameColorLightMap`+`darken()`)도 내보내기 색상에 관여하며 복제됨.
- **작업**: 읽는 필드만 담은 소형 공유 렌더 모델(body/type/시각/색 + `mine: Boolean` + 아바타 키 람다 —
  두 호출부 모두 이미 람다 전달 중)로 `:shared`에 통합. `PbpPalette`의 Long 값들도 함께 이동.
  (Compose `MarkupText` 렌더러는 androidx vs multiplatform 차이로 **공유 비대상** — 리뷰로 동기화 유지.)

---

## B — 파일 구조 (분할은 전부 동작 변경 없는 기계적 이동)

### B1. 데스크톱 `Main.kt` 2,939줄 → 7파일 분할 (M~L, 기계적)

- **현황**: 컴포저블 31개(오버레이 다이얼로그 9개 포함) + 최상위 함수 15개 + `RoomSession` + 모듈 캐시 4개 +
  `App` 안에 상태 15개·비즈니스 함수 10개·110줄 폴 엔진·175줄 오버레이 라우터. 섹션 배너(`══ 왼쪽 패널 ══` 등)로
  경계는 이미 그어져 있음. 상태 전달이 전부 파라미터 기반이라 분할은 순수 이동(공유 헬퍼 `private`→`internal`만).
- **분할표**:

| 새 파일 | 이동 대상 | ~줄 |
|---|---|---|
| `Main.kt` (유지) | `main()`, `App()`(상태·액션·라우터), `OverlayKind`, `runBlockingIo`, `inviteCode` | 750 |
| `sync/RoomSync.kt` | `RoomSession`, 폴 엔진(B2로 추출), `messageValues`(+C2의 `systemMessageValues`) | 200 |
| `ui/RoomListPane.kt` | `LeftPane`, `EmptyPane`, `D10Mark`, `BackgroundLayer`(+`backgroundBitmapCache`) | 250 |
| `ui/ChatPane.kt` | `ChatPane`, `MessageBlock`, `BubbleRow`, `NarrationBlock`, `TimeStamp`, `QuoteMark`, `MessageAvatar`(+`avatarsInFlight`), `InputZone`, `isContinuation`, `quoteContent`, `formatTime` | 550 |
| `ui/Overlays.kt` | `OverlayScaffold`/`OverlayField`/`YellowButton`/`GhostButton` + Join/Create/Code/EditMessage/Font/AddProfileChoice/Settings 오버레이 | 400 |
| `ui/ProfileOverlays.kt` | Profile/ProfileManager/OwnerProfile 오버레이, `SwatchRow`/`CustomSwatch`/`ColorPalettePicker`, HSV 변환 | 450 |
| `Images.kt` | `pickAndStoreImage`, `encodeAvatarBytes`, `encodedAvatarFor`, `md5Hex`, `fetchAvatarCached`, `rememberLocalBitmap`, `uploadedAvatarKeys`, `avatarEncodeCache` | 200 |

- **함께**: `firestore`+`avatarCache`가 아바타 fetch 하나 때문에 `ChatPane→MessageBlock→BubbleRow→MessageAvatar`
  4단계로 관통(`Main.kt:591-608→1290→1444→1623`) — `AvatarLoader(firestore, cache)` 하나로 묶어 1회 전달(S).
  그 외 네트워크 접근은 `App` 액션 함수에 잘 집중되어 있음(확인됨).

### B2. 데스크톱 폴 엔진 추출 (M — B1과 동시)

- **현황**: `Main.kt:246-353` — 캐시 복원·증분 fetch·dedup·편집 반영·알림·디스크 저장 스로틀·메타 폴·멤버 수·
  적응형 인터벌, 9가지 일을 하는 110줄 무명 LaunchedEffect가 `App` 상태 8개를 클로저로 캡처. 모듈에서 가장
  건드리기 위험한 코드인데 이름도 테스트도 없다.
- **작업**: `RoomSyncEngine`(plain class, `FirestoreRest` 주입, `onMessages`/`onMeta`/`onMemberCount` 콜백)로
  추출, LaunchedEffect는 수명 관리만.

### B3. Android `ChatScreen.kt` 1,208줄 → 3분할 (M)

- **현황**: god 파일은 아니고 각 조각은 응집적. 깨끗한 경계 3개: 메시지 렌더링(`:497-878` —
  `MessageBlock`/`BubbleRow`/`NarrationBlock`/`QuoteMark`/`TimeStamp`/`isContinuation`/`quoteContent`),
  `InputZone`(`:880-1069`), 다이얼로그(`:1076-1208`).
- **작업**: → `MessageBlock.kt`(~380줄, 앞으로 계속 크는 부분) / `ChatInput.kt` / `ChatDialogs.kt`.
  전부 private 심벌이라 순수 이동. VM은 현 크기에선 같은 파일 유지 무방. 다음 채팅 기능 전에 실행 권장.

### B4. `RoomListScreen.kt`에 얹힌 프로필 관리 ~330줄 분리 (S)

- **현황**: 방 목록 본체는 ~500줄인데, 오너 프로필·프로필 관리 커밋이 진입점(헤더)이 여기라는 이유로
  `OwnerProfileDialog`(`:621-755`)·`ProfileManagerDialog`+`ManagerRow`(`:506-614`)·클립보드 핸들러·
  `FontSettingDialog`(`:759-805`)를 전부 이 파일에 추가.
- **작업**: 프로필 다이얼로그들을 `ui/profile/`로 이동(개념상 `ProfileEditScreen`의 형제). 전부 파라미터
  전달이라 순수 이동.

### B5. `SyncManager.kt`(862줄) — 3분할 비권장, 아바타만 추출 (S)

- **판단**: auth·attach·outbox·share/join/wipe는 `myUid`·in-flight 가드·reconcile 기준선 불변식을 공유하는
  **하나의 서브시스템** — 쪼개면 넓은 내부 인터페이스와 동시성 불변식 산개만 남는다. 섹션 주석으로 항행 가능.
  **현 상태 유지가 정답.** ~1,100줄 넘으면 재검토.
- **작업**: 유일하게 독립적인 아바타 블록(`:760-862` — 업로드 키 영속화·바이트 캐시·인코딩·MD5)만
  `AvatarStore(context, firestore)`로 추출.

### B6. Repository ↔ SyncManager 순환 참조 해소 (M)

- **현황**: `PbpRepository.kt:12`(`var syncManager: SyncManager?`) ↔ `SyncManager.kt:41`
  (`var repository: PbpRepository?`), `PbpApp.kt:19-24`에서 상호 주입. 모든 호출부가 실제로는 null일 수 없는
  `?.`투성이고, 두 클래스를 독립적으로 추론할 수 없다.
- **작업**: SyncManager가 repo를 쓰는 곳은 `joinRoomInternal`의 방 생성 1곳(`:308`)뿐 — 생성자에
  `createLocalRoom: suspend (...) -> Long` 람다로 대체하면 양쪽 다 non-null 생성자 파라미터가 된다.

---

## C — 소규모 중복 제거

### C1. 클립보드 캐릭터 가져오기 3중 복제 (S — 실사용 로직이라 우선)

- `ChatScreen.kt:428-448` ≡ `RoomListScreen.kt:342-361`(클립보드 읽기→`CharacterCodec.parse`→토스트 ~20줄),
  `ChatViewModel.createFromCode(:161-168)` ≡ `RoomListViewModel.createFromCode(:113-120)`, 토스트 문구는
  데스크톱까지 3벌. → `ui/common/`에 헬퍼 1개 + repository에 `createFromCode` 1개.

### C2. 데스크톱 SYSTEM 메시지 맵 3중 복제 (S)

- `Main.kt:443-448, 552-557, 636-641` — 동일한 `mapOf("type" to "SYSTEM", …)` 보일러플레이트. 스키마 변경 시
  데스크톱만 4곳 수정. → `messageValues` 옆에 `systemMessageValues(body, authorUid)` 1개.

### C3. 데스크톱 Main.kt 내부 복사-붙여넣기 조각 (~200줄, B1 분할 중 처리)

- 오너 아바타 원(이미지 또는 이니셜): `:949-964, 2690-2703, 2838-2851` 3벌 → `OwnerAvatar(size)`
- 프로필 칩(GM 골드 스타일): `:1758-1770, 2730-2739` 2벌
- "라벨+SwatchRow+CustomSwatch+조건부 팔레트" 색상 섹션: 4벌(`:2088-2112, 2215-2230, 2875-2887`)
- `picking` 가드 딸린 이미지 선택 버튼: 3벌(`:2054-2069, 2265-2280, 2852-2867`)
- 2버튼 확인 다이얼로그: `leaveTarget`(`:791-805`)/`messageDelete`(`:851-864`) — 문자열만 다름
- 스타일드 `BasicTextField` 크롬: `OverlayField`/`InputZone`(`:1842-1882`)/`EditMessageOverlay`(`:2644-2655`)

### C4. Android 쪽 동일 계열 (S)

- 오너 아바타 손조립 3벌: `RoomListScreen.kt:170-193, 530-549, 651-673` → `OwnerAvatar(size: Dp)`
- 커스텀 컬러 스와치의 6색 sweep 그라데이션 3벌: `RoomListScreen.kt:722-726`, `ProfileEditScreen.kt:503-507`,
  `RoomSettingsScreen.kt:162-166` → `CustomColorSwatch` 컴포저블 1개

---

## D — 하드코딩

### D1. 데스크톱에 치수 토큰 부재 — dp 리터럴 ~290개 (M)

- 데스크톱 `Tokens`(Theme.kt:30-80)는 색상 전용. Android `PbpDimens`의 의미와 정확히 겹치는 것들이 인라인:
  `56.dp` 앱바(×2), `38.dp` 채팅 아바타(×3), `36.dp` 스트립 아바타 + 데스크톱 고유 상수(`280.dp` 사이드바,
  `720.dp` 콘텐츠 최대폭 ×2, `420.dp` 말풍선 최대폭 ×2, `430.dp` 오버레이 폭). → `PbpDimens`를 데스크톱
  Tokens에 미러링, 의미 있는/반복되는 것만 이관(일회성 패딩은 방치).

### D2. 타이밍 상수 전부 인라인 (S)

- 폴 루프(`Main.kt:265-345`): `120_000` 활동 윈도, `2_500/20_000/30_000` 인터벌, `60_000` 메타 폴, `30_000`
  저장 스로틀, `1_000` 웨이크; `15_000` metaFreeze(`:716`); Firestore.kt: `60_000` 인증 백오프·토큰 마진,
  10s/30s 타임아웃. perf 리뷰에서 **의도적으로 튜닝한 값들**인데 이름이 없어 튜닝 이력이 보이지 않는다.
  → `ACTIVE_POLL_MS` 식 명명 상수를 sync 파일 상단에.

### D3. 색상 리터럴 → 토큰 승격 (S)

- **Android**: "시그니처 옐로 위 잉크" `0xFF1A1A1A`/`0xFF10151C` ~9곳(RoomList/RoomSettings/ProfileEdit/
  ChatScreen) → `onSignature` 토큰; 스탯 블루 `0xFF3B82F6` 2곳(`Ui.kt:238`, `ProfileEditScreen.kt:304`);
  다이스 결과색 3종 인라인(`ChatScreen.kt:566,578`).
- **데스크톱**: `Color(0x…)` 46개 중 반복분 — 이니셜 잉크 `0xFF10151C` ×3(이미 `Tokens.BubbleInk` 존재!),
  다크골드 `0xFF7A5B12` ×2, GM 골드 링 ×2, 필드 배경 `0x0D14191F` vs `0x0A14191F`(**거의 같은 두 값 —
  의도치 않은 분기 의심, 통일할 것**), 사이드바 그라데이션 값이 보더 색으로도 하드코딩(`:1044`).

### D4. 데스크톱 앱 데이터 경로 5곳 하드코딩 (S)

- `.pbp-desktop`이 `Config.kt:49`, `RoomCache.kt:15`, `Main.kt:710, 2520, 2587`에 독립 파생. 특히 `Main.kt:710`은
  `pickAndStoreImage`가 만드는 배경 디렉터리를 별도로 재조립 — 폴더명 하나 바꾸면 고아 정리가 조용히 무력화.
  → `appDataDir(sub: String)` 헬퍼 1개.

### D5. 기타 상수 산재 (S, 일괄)

- Firestore 배치 450: `SyncManager.kt:249, 355` 2곳 → 상수 1개
- 이미지 크기: 크롭 512(`ImageCrop.kt:165`)·오너 512(`RoomListScreen.kt:634`)·동기화 256(`SyncManager.kt:834`)·
  배경 1600(`RoomSettingsScreen.kt:78`) 4파일 산재 + 데스크톱에 전부 독립 재선언 → `Images.kt`에 `ImageSizes`
  (A2의 Protocol과 연계 — 256은 와이어 정합 필수)
- SharedPreferences: `getSharedPreferences("pbp", …)` 9회 + 문자열 키 → `prefs` 필드 + 키 상수
- 데스크톱 기본값: `"preset_lighthouse"` 4곳(`Main.kt:626, 667, 1099`, `Firestore.kt:374`), 기본 테마색
  `0xFF8EC5E8` 2곳(`Firestore.kt:364, 374`) — Android엔 `DEFAULT_BACKGROUND`/`DEFAULT_THEME_COLOR` 상수가
  이미 있음, 데스크톱에도 동일하게. 폰트 키 `"system"/"gowun"/"pretendard"` 3곳.
- 초대코드 알파벳: `Main.kt:887` ≡ `SyncManager.kt:681` (A2 Protocol로)

### D6. `JoinedRoom` var → val (S)

- `Config.kt:21-32`가 전부 `var`인데 코드베이스는 불변으로 취급(`copy()` 사용). `Main.kt:696-697` 주석이
  "같은 인스턴스 var 수정 시 Compose가 모른다"고 **이미 이 함정을 문서화**해 둠 — 컴파일러가 강제하게 val로.
  Gson 역직렬화는 영향 없음.

### D7. 문자열 라우팅 (S, 선택)

- `MainActivity.kt:49-68` 라우트 4종을 7개 호출부에서 손 조립. 규모상 참을 만하나 10줄짜리
  `object Routes { fun chat(id: Long) = "chat/$id" }`면 오타=런타임 크래시 위험이 사라짐.

---

## E — 사소 (여유 있을 때 일괄)

- **죽은 코드**: `PbpRepository.observeMessages`(`:18`)·`observeGlobalProfiles`(`:24`) 무호출(각각
  `MessageDao.observeForRoom`·`ProfileDao.observeGlobal`도 고아화) — 삭제. 데스크톱 `RoomMeta.icon` 완전
  사장("방 아이콘 폐지", `Firestore.kt:362`는 `""` 고정인데 필드는 복사·영속됨) — `JoinedRoom.icon`은
  config JSON 호환용이면 주석 표기. Android `ChatRoom.icon`은 마이그레이션 비용상 유지하되 `// 폐지됨` 주석.
- **오해 소지 이름**: `Modifier.dashedCell`(`RoomSettingsScreen.kt:427`)이 실선을 그림; `downscaleToJpeg`가
  PNG도 반환; `PbpDimens.sp1..sp6`(spacing)이 텍스트 `.sp` 단위와 접두 충돌 — 호출부 ~100곳이라 다른 작업으로
  건드릴 때만 `gap1..`로; 데스크톱 `ChatPane`의 파라미터 `deviceId`가 실제론 `authorUid()` 값(주석으로 해명
  중) — `myUid`로 개명.
- **낡은 주석**: `Main.kt:147` "별도 스레드에서 로드 (C8)" — 실제론 호출 스레드 블록(F3에서 본체 주석은
  정정됐는데 호출부는 남음) — 삭제. `ChatScreen.kt:1184` "…Ui.kt로 이동" 묘비 주석 — 삭제.
- **FQN 혼용**: 양쪽 큰 화면 모두 import 블록과 인라인 FQN(`com.pbp.app.data.CharacterCodec`, `java.io.File`,
  `org.jetbrains.skia.*` 등) 혼재 — 분할 작업 때 정리.
- **타입 스케일 이탈**: 문서화된 18/15/13/11/10 스케일 밖의 `12.sp` ×4, `14.sp` ×3, `16.sp`, `17.sp` — 스케일에
  맞추거나 Tokens.kt 문서 주석을 현실에 맞게 수정(현재 문서가 살짝 거짓말).
- **빌드 위생**: `functions/`에 **package-lock.json 없음** + 캐럿 범위(`^12.6.0`) — 배포 재현 불가, 락파일
  커밋(S). 데스크톱 `packageVersion "1.0.0"` vs 앱 `versionName "0.1.0"` — 버전 스킴 통일. 데스크톱의 고아
  `testImplementation(libs.junit)`은 A1로 해소.
- **docs 정리**: 평면 29파일 → `docs/reviews/`(날짜별 리뷰 5개), `docs/mockups/`(mockup·아이콘 시안 등 ~16개),
  최상위엔 architecture/design-spec/design/firebase-security만. 이동 시 `architecture.md`·`Tokens.kt`의 상대
  경로 참조 갱신.
- **architecture.md 사실 갱신 5건**: ① supportedSides `[6,10,100]` → 실제 `[6,10,20,100]`+d66 ② "Room DB v3"
  → 실제 v9 ③ "테스트 32개" → 실제 9스위트 63개 ④ "데스크톱 30초 윈도" → 실제 동적 `interval×2`
  ⑤ "순수 로직 복제본" 목록 과소(실제 6객체+LogExporter+팔레트) + "남은 과제"의 배포 항목 최신화, KMP → plain
  JVM `:shared`로 표현 수정.

---

## 좋은 상태 (바꾸지 말 것)

- **주석 규율**: 비자명한 결정마다 리뷰 ID와 이유 인용 — 이 코드베이스의 최대 자산. 유지할 것.
- Android: `MainActivity`/nav 최소, `PbpRepository` 얇은 파사드, `Tokens.kt` 문서화된 스케일·시맨틱 색·양
  테마, `ProfileEditScreen`/`RoomSettingsScreen` 적정 크기, VM에 비즈니스 로직 없음.
- 데스크톱: Main.kt 밖 파일들 전부 단일 목적·적정 크기. `FirestoreRest`는 인증 분리·균일한 에러 계약(null=
  오류, 커서 안전성 주석)으로 잘 조직된 수제 클라이언트. `Config.kt` 동시성 설계(스냅샷+원자적 파일 교체) 신중.
- 빌드: 버전 카탈로그를 양 모듈이 일관 사용, 하드코딩 의존성 버전 없음, JVM 17 통일.
- 식별자는 일관되게 영어, 한국어는 주석·문자열에만 — 좋음. 인라인 한국어 UI 문자열은 한국어 전용 앱의 합리적
  선택으로 **수용**(추출 요구 없음 — 중복 문구만 C1로 해소).

## 권장 실행 묶음

| PR | 내용 | 규모 |
|---|---|---|
| 1 | A1 `:shared` Phase 1 + A2 Protocol 상수 + 고아 junit 해소 | S~M |
| 2 | C1 클립보드 dedup + B4 프로필 다이얼로그 이동 + D5 상수 일괄 + E 사소(죽은 코드·주석·락파일) | S |
| 3 | B1+B2 데스크톱 분할(+C2·C3·D1·D3·D4·D6 동시 처리) | M~L |
| 4 | B3 ChatScreen 분할 + C4 + D3(Android) | M |
| 5 | A3 LogExporter 통합 (Phase 2) | M |
| 6 | B5 AvatarStore + B6 순환 해소 + D7 + architecture.md·docs 정리 | S~M |
