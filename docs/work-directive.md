# 작업 지시서 — 시나리오 뷰어 컴팩트 개정 (진행·이동을 칩 행으로)

> **이 파일은 '작업 지시서' 슬롯이다** (UI/UX 3종 체계 — 가이드 · 최종 시안 · 지시서).
> 진행 중 작업 하나만 담고, 완료되면 다음 작업 내용으로 교체한다.
> 직전 지시서(시나리오 뷰어 신설 V0~V6)는 v0.16.0으로 구현 완료되어 내려갔다.

**다른 세션이 이 문서만 보고 작업할 수 있도록** 위치·변경·주의를 명시한다.
기준: v0.16.0(`eb97076`) 이후 코드. 확정 시안: `docs/mockups/final-design.html` ④ 프레임
(이미 컴팩트 개정 반영됨 — 이 브랜치). 라인 번호는 참고용, 앵커는 함수명으로 찾을 것.

## 목적 한 줄

시나리오 패널의 **하단 행(진행 표시 + `‹` `›`)을 패널에서 빼서 시나리오·판정 칩 행의
우측**에 넣는다 — 패널은 헤더+본문 두 줄이 되고, 칩 행 높이(40dp)는 그대로라
뷰어 전체 높이가 하단 행 한 줄(≈48dp)만큼 줄어든다.

## C1. 패널에서 NavRow 제거 — `ScenarioViewer.kt`

- `ScenarioPanel`(:63)의 `NavRow(viewing, onStep)` 호출(:124) 제거.
  Viewing 본문은 문장 Text만 남는다 (헤더·AskLink·Loading·다이얼로그는 무변경).
- `NavRow`(:266)와 `NavButton`(:285)은 **삭제하지 말고 C2로 이사** — 시각 규격만
  개정한다(아래). `onStep` 파라미터가 `ScenarioPanel`에서 더 이상 안 쓰이면
  파라미터에서 제거하고 호출부(ChatScreen)의 전달도 정리.

## C2. 칩 행 우측에 진행 + `‹` `›` — `ChatInput.kt` `InputZone`

- 앵커: `if (gmActive)` 블록의 칩 Row(:146–154, `GmChip("📖 시나리오"…)`).
- 파라미터 추가 (기존 `scenarioOpen` 옆, 같은 기본값 스타일):
  ```kotlin
  /** 패널이 열려 있고 문서를 읽는 중일 때만 non-null — 칩 행 우측 진행·이동 */
  scenarioNav: ScenarioNav? = null,   // data class ScenarioNav(position, total, unit: String)
  onScenarioStep: (Int) -> Unit = {},
  ```
  `ScenarioNav`는 ChatViewModel(또는 ScenarioViewer.kt)에 선언 — Viewing 상태에서
  `position/total/paragraphMode`("문장"/"문단")로 ChatScreen이 만들어 내려보낸다.
  **AskLink·Loading·Failed·패널 닫힘이면 null** → 우측 요소 미표시.
- 칩 Row를 다음 구성으로 확장 (한 Row, `verticalAlignment = CenterVertically`):
  ```
  GmChip(📖 시나리오, active=scenarioOpen) · GmChip(🎲 판정)
  · Spacer(weight 1f)
  · Text("${position}/${total}", 10sp, inkDim, maxLines=1)   ← "17/128" (공백 없음)
  · Spacer(gap1) · NavButton(‹) · Spacer(gap1) · NavButton(›)
  ```
- **NavButton 시각 규격 개정** (시안 ④): 시각 원 **32dp** + pill + `panel` 면 +
  `line` 1dp 테두리, 글리프 13sp. 비활성 = `inkFaint` 면 + `inkDisabled` 글리프 +
  테두리 없음(기존과 동일). **히트 영역은 40dp 유지** — 32dp 원을 40dp Box 중앙에
  (스와치·값 삭제 ✕과 같은 §6 패턴). contentDescription "이전/다음 {unit}" 유지.
- 칩 행의 `heightIn(min = touchTarget)`(:147)은 그대로 — 우측 요소가 들어가도
  행 높이 40 불변이 이 개정의 핵심이다.
- 320dp 초소형 폭에서도 한 줄 유지 확인: 칩 2개(≈178) + 진행(≈40) + 버튼
  40×2 + 간격 ≈ 306 ≤ 320−32. 진행 텍스트는 `maxLines=1`.

## C3. 배선 — `ChatScreen.kt`

- `ScenarioPanel` 호출부에서 `onStep` 정리(C1)하고, InputZone 호출부에
  `scenarioNav`·`onScenarioStep = vm::scenarioStep` 전달.
- `scenarioNav` 산출: `scenarioOpen && gmActive && state is Viewing` 일 때만
  non-null. 파생 위치는 ChatScreen(이미 scenarioOpen·gmActive를 앎).

## C4. 검증

- [ ] 패널 = 헤더 + 문장 두 줄. 하단 행 없음 — 열림 시 이전보다 낮아짐
- [ ] 칩 행: 📖 시나리오 · 🎲 판정 ─ 우측 17/128 ‹ › (패널 열림+Viewing일 때만)
- [ ] 행 높이 40 불변 (요소 추가 전후 스크린샷 비교)
- [ ] ‹ › 경계 비활성, 문단 모드에서 단위·진행 수 문단 기준, contentDescription 유지
- [ ] AskLink/Loading/Failed·패널 닫힘 → 우측 요소 미표시
- [ ] 시각 32·히트 40 (터치 영역 검사), 신규 dp/sp/색 리터럴 0건
- [ ] 회전·재진입 후 진행 표시 정상 (기존 위치 기억 v0.16 동작 회귀 없음)
- [ ] `gradlew assembleDebug testDebugUnitTest` 통과 후 에뮬레이터 즉시 종료

## 건드리지 않는 것

- 상태 기계·fetch·설정(ScenarioSettings)·위치 기억 — UI 배치만 바꾼다.
- 데스크톱 — 이 개정은 모바일만. 데스크톱 이식 시 같은 배치로.
