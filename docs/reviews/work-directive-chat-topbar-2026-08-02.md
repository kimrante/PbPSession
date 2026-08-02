# 작업지시서 — 채팅 상단 바 개편: 내보내기 이동 · 현재 프로필 아이콘 · 전환 사이드바

시안: `docs/mockups/mockup-chat-profile-sidebar.html`(.png) — 확정 기준.
디자인 기준: `docs/ui-guidelines.md` v1.0. 대상 버전: v0.11.2 이후 main.
작업은 A→B→C 순서를 권장한다 (A가 상단 바 자리를 비워야 B의 인셋 계산이 성립).

---

## A. 로그 내보내기 ↓ 를 방 설정 안으로 이동

**목적**: 상단 바 아이콘 수를 줄여 현재 프로필 아이콘 자리를 만든다.
내보내기는 매 세션 쓰는 기능이 아니므로 설정 진입이 자연스럽다.

1. **ChatScreen.kt** — 상단 바의 ↓ IconButton(현재 :468–476)과
   `exportLauncher`(:403–415) 관련 코드를 제거. `ActivityResultContracts.CreateDocument`
   임포트 등 이 변경으로 고아가 되는 임포트도 함께 정리.
2. **RoomSettingsScreen.kt** — "공유·기타" 섹션(:266)의 "방 공유 · 초대 코드" 행
   아래에 SettingRow 추가:
   - title: `"로그 내보내기 (HTML)"`
   - subtitle: `"전체 대화를 종이 톤 HTML 파일로 저장합니다"`
   - onClick: `CreateDocument("text/html")` 런처 실행 —
     파일명 `"${room?.name ?: "PbP"}_log.html"`, 성공/실패 토스트는
     기존 ChatScreen 문구 그대로 ("HTML 로그를 저장했습니다" / "저장에 실패했습니다")
3. **RoomSettingsViewModel** — `exportTo(uri)` 이식. 기존 ChatViewModel의
   구현(repo.allMessages + LogExporter 경로)을 그대로 옮기거나, 공용 로직이면
   호출만 추가. ChatViewModel 쪽이 이 변경으로 고아가 되면 제거.
4. **문서 갱신** — `docs/PbP-design-spec.md` §4("우상단: 로그 내보내기 아이콘,
   방 설정 아이콘")와 §6("진입: 채팅방 우상단 ↓ 아이콘, 방 설정 메뉴")을
   "진입: 방 설정 메뉴"로 수정.
5. **데스크톱 패리티(§7)** — PC 채팅 상단의 내보내기 진입점도 동일하게 방 설정
   오버레이로 이동(같은 커밋 또는 후속 커밋, PR 본문에 명시).

검증: 방 설정에서 내보내기 실행 → 파일 저장·토스트 확인. 채팅 상단 바에 ↓ 없음.

## B. 상단 바 현재 프로필 아이콘 (⚙ 오른쪽)

**규격 (시안 ①)**
- 위치: ⚙ IconButton 오른쪽, 앞에 `Spacer(gap1)`.
- `Avatar(emoji/imagePath of 활성 프로필, size = PbpDimens.avatarBar /*32*/,
  ringColor = tokens.signature)` — 링 2dp는 Avatar의 기존 ringColor 경로 사용.
  방 목록 헤더 오너 아바타와 동일 규격 (원칙 4).
- 히트 영역: 40dp — `Modifier.size(PbpDimens.touchTarget)` Box 중앙에 32dp 아바타.
- 데이터: `profiles.find { it.id == activeId }` — InputZone과 같은 소스.
  활성 프로필 전환 시 즉시 반영(상태 승격 필요 시 ChatScreen 레벨 state 사용).
- 인셋: 좌 ←·?(88dp) / 우 ⚙·아바타(84dp) 모두 96 이내 —
  **기존 `titleInset` 그대로, 신규 토큰 없음.**
- 탭 동작: C의 사이드바 열기.

## C. 프로필 전환 사이드바 (시안 ②)

**토큰 등재 (선행)** — `Tokens.kt`에 `drawerWidth = 264.dp`
(화면 320 − 스크림 스트립 56, M3 모달 드로어 관례) + 가이드 4장 고정 규격에 1줄.

**구조** — M3 ModalNavigationDrawer는 좌측 고정이므로 커스텀 오버레이로 구현:
```
if (showProfileDrawer) {
  Box(fillMaxSize) {
    스크림: fillMaxSize().background(rgba(30,35,45,.38) → 토큰: 기존 딤과 통일)
            .clickable { close } // 바깥 탭 닫기
    드로어: align(CenterEnd), width = drawerWidth, fillMaxHeight,
            background(panel), RoundedCornerShape(topStart=rSheet, bottomStart=rSheet),
            padding(vertical=gap5, horizontal=gap4)
  }
}
+ BackHandler(showProfileDrawer) { close }
+ 등장/퇴장: slideInHorizontally { it } / slideOutHorizontally { it } (AnimatedVisibility)
```
스크림 색은 모바일 다이얼로그 딤과 같은 값을 쓰고, 하드코딩하지 말 것(§2) —
기존 딤 색이 리터럴이면 이번에 토큰으로 승격.

**콘텐츠 (위→아래)**
1. 타이틀 "프로필 전환" 18sp bold 센터 + 부제 "지금 말하는 캐릭터를 고르세요" 11sp inkDim 센터
2. `Spacer(gap4)`
3. 프로필 행 목록 (스크롤 가능, `verticalScroll`):
   - 행 규격 = 프로필 관리 다이얼로그 ManagerRow와 동일: `padding(gap3)` + `rCell`
     클립, 아바타 `avatarStrip(36)` + 이름 15sp bold + 태그 11sp inkDim.
     **ManagerRow를 공용 컴포저블로 승격해 재사용**할 것 (복제 금지, 원칙 4).
   - 태그: 캐릭터 = "캐릭터"(활성이면 "캐릭터 · 사용 중"), GM = "GM · 명조 서술".
   - 활성 행 강조: `signature.copy(alpha=.14f)` 면 + `signature.copy(alpha=.4f)`
     1dp 테두리 + 이름 `signatureInk` + 우측 ✓ 13sp `signatureInk`
     (판정 요청 칩과 같은 강조 문법).
   - 탭 = `onSwitch(profile)` 재사용 후 닫기. 길게 = `onEditProfile` 후 닫기.
4. 구분선 `line` 1dp, 상하 `gap2`
5. "＋ 프로필 추가" 행: 점선 원(avatarStrip, dashedBorder line) + 13sp bold
   `signatureInk` — `onAddProfile` 재사용 후 닫기.
6. 하단 고정 힌트: "행을 길게 누르면 프로필 편집 · 바깥을 탭하면 닫기" 10sp inkDim 센터.

**상태·재사용** — `showProfileDrawer`는 ChatScreen state. 전환·편집·추가 콜백은
InputZone에 이미 내려가는 것과 같은 람다를 공유(동작 분기 금지). 입력줄 프로필
스트립은 그대로 유지 — 사이드바는 같은 동작의 두 번째 진입점이다.

## D. 검수 체크리스트

- [ ] 채팅 상단 바: ← ? | 타이틀(정중앙, titleInset 96) | ⚙ 아바타 — ↓ 없음
- [ ] 아바타가 활성 프로필과 즉시 동기화 (스트립에서 바꿔도, 사이드바에서 바꿔도)
- [ ] 방 설정 "공유·기타"에 내보내기 행, 동작·토스트 기존과 동일
- [ ] 사이드바: 바깥 탭·뒤로 버튼 닫기, 행 탭 전환+닫기, 길게 편집, ＋ 추가
- [ ] 신규 리터럴 0건 (`Color(0x` grep — 스크림은 토큰), 신규 수치는 drawerWidth만
- [ ] 모든 sp가 18/15/13/11/10 안, 탭 요소 히트 ≥40
- [ ] 스펙 문서 §4·§6 갱신, 데스크톱 내보내기 진입점 이동 여부 명시
- [ ] 다크 모드 확인 (토큰 스왑만으로 성립해야 함 — isDark 분기 금지)
