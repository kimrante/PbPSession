# 작업 지시서 — 시나리오 뷰어 (GM 전용 플로트 창)

**다른 세션이 이 문서만 보고 작업할 수 있도록** 항목마다 위치·변경·주의를 명시한다.
기준: v0.12.0(`aae4841`) 이후 코드. 라인 번호는 참고용(±수 줄 드리프트 가능) — 앵커는
함수·변수명으로 찾을 것.

## 기능 한 줄

GM이 채팅 팔레트의 **시나리오 칩**을 누르면 방 안에 **플로트 창**이 뜨고, 구글 독스
**뷰어 권한 링크**를 입력하면 문서를 **1문장씩** 보여준다. 우하단 `<` `>` 버튼으로
이전·다음 문장 이동. GM 프로필로 말하는 중에만 보이고, 링크가 잘못되었거나 파싱에
실패하면 경고창을 띄운다.

## 요구사항 원문 → 설계 대응

| 요구 | 대응 |
|---|---|
| 세션 방 안 플로트 창, 파싱 문서 1줄씩 | V4 — ChatScreen 오버레이 카드, 문장 인덱스 상태 |
| 구글 독스 뷰어 권한 링크 입력 화면이 먼저 | V4 — 창의 첫 상태 = 링크 입력 폼 |
| 각 방의 GM 프로필에서만 보임 | V3 — 기존 `gmActive` 게이트(판정 요청 J2와 동일 기준) |
| `<` `>` 버튼 우하단, 이전/다음 문장 | V4 — 창 우하단 내비게이션 행 |
| 팔레트 클릭 시에만 창 생성 | V3→V4 — 칩 탭이 유일한 진입점, 그 외 자동 생성 없음 |
| 비정상 경로·파싱 실패 시 경고창 | V5 — AlertDialog, 실패 사유별 문구 |

---

## ⚠ 먼저 읽을 것 — 방식과 한계

**구글 독스 텍스트는 API 키 없이 export 엔드포인트로 가져온다.**

- "링크가 있는 모든 사용자 – 뷰어" 문서는
  `https://docs.google.com/document/d/{docId}/export?format=txt` 가 **인증 없이 평문
  텍스트를 반환**한다 (302 리다이렉트 1~2회 경유 — HttpURLConnection 기본 추종으로 충분).
- 권한이 없는 문서는 로그인 페이지(HTML)로 떨어진다 → **응답이 HTML이면 "권한 없음"으로
  판정**한다 (Content-Type 또는 본문 선두 `<` 검사).
- Google Docs API(OAuth·API 키)는 쓰지 않는다 — 2인 개인 앱에 키 관리 비용이 과하고,
  뷰어 링크 요구사항과 export 방식이 정확히 일치한다.
- **호스트 검증 필수**: `docs.google.com`의 `/document/d/{id}` 형태만 허용. 임의 URL을
  fetch하는 기능이 되면 안 된다 (V0의 파서가 이 검증을 겸한다).

### 착수 전 결정 2가지 (기본값으로 진행 가능, 바꾸려면 여기서)

1. **링크·읽던 위치의 영속화** — 이 지시서의 기본은 **ViewModel 수명**(방 화면을 벗어나면
   소실, 회전은 생존). 방마다 링크·인덱스를 기기에 남기려면 `CaptureSettings`
   (SharedPreferences) 패턴으로 V2에 두 필드만 추가하면 된다 — 요구에 없어 기본 범위 밖.
2. **데스크톱 이식** — 요구에 없음. 이 지시서는 **모바일만** 다룬다. 이식하게 되면
   V0(:shared)은 그대로 쓰고 V3~V5만 `ChatPane`/`Overlays` 문법으로 옮기면 된다(후속 지시서).

---

## 작업 순서

| 단계 | 내용 | 규모 | 산출물 |
|---|---|---|---|
| **V0** | :shared 순수 로직 — 링크 검증·문장 분리 (+테스트) | M | `shared/.../ScenarioDoc.kt` |
| V1 | 문서 가져오기 — HTTP fetch + 실패 사유 판정 | S | `app/.../data/ScenarioFetcher.kt` |
| V2 | 상태 — ChatViewModel에 뷰어 상태 기계 | S | `ChatScreen.kt`(VM부) |
| V3 | GM 팔레트 칩 — 진입점 | S | `ChatInput.kt` |
| V4 | 플로트 창 — 링크 입력 → 문장 표시 → `<` `>` | M | 신규 `ScenarioViewer.kt` |
| V5 | 경고창 — 실패 사유별 AlertDialog | S | `ScenarioViewer.kt` |
| V6 | 검증 | S | 테스트·수동 시나리오 |

V0 없이 V4부터 만들지 말 것 — 문장 분리 규칙이 테스트로 먼저 고정돼야 UI가 흔들리지 않는다.

---

## V0. :shared 순수 로직 — `shared/src/main/kotlin/com/pbp/shared/ScenarioDoc.kt`

Rules·PbpMarkup과 같은 급의 순수 오브젝트. **플랫폼 의존 0** (HTTP 없음 — 그건 V1).

```kotlin
object ScenarioDoc {
    /** 뷰어 링크에서 docId 추출. docs.google.com의 /document/d/{id} 형태만 인정.
     *  잘못된 링크는 null — 호출부(V5)가 "링크 형식" 경고를 띄운다. */
    fun extractDocId(url: String): String?

    /** export 엔드포인트 URL */
    fun exportUrl(docId: String): String =
        "https://docs.google.com/document/d/$docId/export?format=txt"

    /** 평문 → 문장 목록. 빈 결과는 emptyList — 호출부가 "빈 문서" 경고. */
    fun splitSentences(text: String): List<String>
}
```

### `extractDocId` 인정 형태 (전부 테스트로 고정)

- `https://docs.google.com/document/d/{id}/edit?usp=sharing` (공유 버튼 기본형)
- `https://docs.google.com/document/d/{id}/view`, `/preview`, `/edit#...`, `/{id}` 로 끝나는 형
- 앞뒤 공백·개행 trim 후 판정. `http://`는 `https://`로 간주해 허용.
- **거부**: 다른 호스트(단축 URL 포함), `/spreadsheets/`·`/presentation/` 등 문서 아닌 종류,
  docId에 `[A-Za-z0-9_-]` 외 문자. 거부 = null.

### `splitSentences` 규칙 (한국어 시나리오 텍스트 전제)

1. 줄 단위로 먼저 나눈다. 빈 줄은 버린다.
2. 각 줄을 **종결부호 뒤에서** 나눈다: `.` `!` `?` `…` (연속 부호는 한 덩어리 — `?!`, `...`).
   닫는 따옴표·괄호(`"` `”` `』` `」` `)`)가 바로 뒤따르면 문장에 포함시키고 그 뒤에서 나눈다.
3. 종결부호가 없는 줄(제목·항목)은 그 줄 전체를 한 문장으로.
4. 각 문장 trim, 빈 문자열 제거. **숫자 사이의 `.`(예: `2.5`, `1.
   장`)은 나누지 않는다** — lookahead로 "부호 뒤가 공백 또는 줄 끝"일 때만 분리.
5. 결과가 0개면 emptyList (호출부 경고).

### 테스트 — `shared/src/test/kotlin/com/pbp/shared/ScenarioDocTest.kt`

- extractDocId: 위 인정/거부 형태 각각 + 공백 낀 입력.
- splitSentences: 평서문 연쇄 / `?!`·`…` / 따옴표 끝 문장(`"…간다."`) / 제목 줄 /
  빈 줄 연속 / `2.5` 소수점 / 전부 공백인 입력 → empty.

---

## V1. 문서 가져오기 — `app/src/main/java/com/pbp/app/data/ScenarioFetcher.kt`

앱에는 범용 HTTP 클라이언트가 없다(Firestore SDK는 자체 통신). **의존성 추가 없이
`HttpURLConnection`** 으로 한다 — INTERNET 권한은 이미 있다.

```kotlin
object ScenarioFetcher {
    sealed interface Result {
        data class Ok(val sentences: List<String>) : Result
        enum class Error : Result { BAD_LINK, NO_ACCESS, NETWORK, EMPTY }
    }
    /** Dispatchers.IO에서 호출할 것 (V2가 viewModelScope에서 감싼다) */
    fun fetch(url: String): Result
}
```

- 절차: `extractDocId` → null이면 `BAD_LINK` → `exportUrl` GET
  (connect/read 타임아웃 각 10초, 리다이렉트 기본 추종) →
  - 2xx + 본문 선두가 `<`가 아니면 → `splitSentences` → 비면 `EMPTY`, 아니면 `Ok`
  - 2xx인데 HTML(로그인 페이지) 또는 3xx 최종 도달 실패·4xx → `NO_ACCESS`
  - IOException·타임아웃 → `NETWORK`
- 본문은 UTF-8 고정 디코드. **상한 1MB** — 초과분은 자르고 경고 없이 진행(시나리오
  텍스트로 충분, OOM 방지).
- 주의: cleartext 아님(https 고정 — exportUrl이 만든다), 쿠키·세션 관리 없음.

---

## V2. 상태 — `ChatScreen.kt`의 `ChatViewModel`(:91 부근)

플로트 창 상태는 **ViewModel에 둔다** — fetch가 비동기이고 회전에도 문장·위치가
살아남아야 한다 (`judgeSheetOpen` 같은 열림 플래그만 rememberSaveable).

```kotlin
sealed interface ScenarioState {
    data object AskLink : ScenarioState                       // 첫 화면: 링크 입력
    data object Loading : ScenarioState
    data class Viewing(val sentences: List<String>, val index: Int) : ScenarioState
    data class Failed(val error: ScenarioFetcher.Result.Error) : ScenarioState  // V5 경고창
}
val scenario = MutableStateFlow<ScenarioState>(ScenarioState.AskLink)

fun loadScenario(url: String) = viewModelScope.launch {
    scenario.value = ScenarioState.Loading
    val r = withContext(Dispatchers.IO) { ScenarioFetcher.fetch(url) }
    scenario.value = when (r) {
        is Ok -> ScenarioState.Viewing(r.sentences, 0)
        is Error -> ScenarioState.Failed(r)
    }
}
fun scenarioStep(delta: Int)   // index를 coerceIn(0, lastIndex)로 이동
fun scenarioReset()            // 창을 닫아도 유지, "다른 문서" 버튼에서만 AskLink로
```

- `Failed` 처리 후(경고창 확인) `AskLink`로 되돌린다 — 입력한 링크 문자열은
  UI(rememberSaveable)에 남아 있으므로 수정 후 재시도 가능.
- **닫기 ≠ 리셋**: 창을 닫았다 다시 열면 읽던 문장이 그대로여야 한다(Viewing 유지).

## V3. GM 팔레트 칩 — `ChatInput.kt` `InputZone`

- 앵커: `if (gmActive)` 블록(:161 부근)의 `＋ 판정 요청` 칩.
- 그 칩을 감싼 `Box`를 **`Row`(spacedBy(PbpDimens.gap2))로 바꾸고** 두 번째 칩
  `📖 시나리오`를 추가한다. **캡슐 스타일은 판정 요청 칩과 자간·패딩·토큰까지 동일**하게
  (CLAUDE.md 0장 — 동류 컴포넌트는 같은 스페이싱 토큰).
- 콜백 `onScenarioViewer: () -> Unit = {}` 를 InputZone 파라미터에 추가 —
  `onJudgeRequest`(:79)와 같은 자리·같은 기본값 스타일.
- **이 칩이 유일한 진입점**이다. 다른 어떤 경로(방 입장·프로필 전환·수신 이벤트)에서도
  창을 자동 생성하지 않는다.

## V4. 플로트 창 — 신규 `app/src/main/java/com/pbp/app/ui/chat/ScenarioViewer.kt`

### 배선 (`ChatScreen.kt`)

- `var scenarioOpen by rememberSaveable { mutableStateOf(false) }` —
  `judgeSheetOpen`(:300)과 같은 자리.
- InputZone 호출부(:666 부근)에 `onScenarioViewer = { scenarioOpen = true }`.
- 렌더 위치: `RoomBackdrop` 안, 메시지 리스트 **위 레이어** —
  `if (scenarioOpen && gmActive) ScenarioFloat(...)`. `gmActive`는 InputZone과 같은
  계산(활성 프로필 isGm)을 ChatScreen 레벨로 끌어올려 공용화한다.
  **GM 아닌 프로필로 전환하면 창이 사라진다**(상태는 VM에 살아 있어 GM 복귀 시 재개) —
  "GM 프로필에서만 보임" 요구의 해석. 캡처 모드(`capturing`) 중에는 표시하지 않는다
  (캡처 결과에 찍히면 안 되고, 탭 히트테스트와도 충돌 — 리뷰 A1과 같은 계열).

### 창 구성 (`ScenarioFloat` 컴포저블)

- **카드**: 화면 하단 입력줄 위쪽에 뜨는 둥근 카드. 폭 = 화면 - 좌우 `gap4`,
  배경 `tokens.chatBarBg`(불투명), 모서리·그림자는 기존 카드류와 동일 토큰.
  **드래그 이동 가능** — `offset` 상태 + `pointerInput(detectDragGestures)`,
  초기 위치는 하단 중앙(센터 정렬 — CLAUDE.md 0장). 화면 밖으로 못 나가게 clamp.
- **헤더 행**: 좌측 `📖 시나리오` 라벨(11sp Bold, `inkDim`) · 우측 `×` 닫기
  (`scenarioOpen = false` — VM 상태는 유지).
- **본문**: 상태별 3형
  1. `AskLink`: 안내 문구 "구글 독스 뷰어 링크를 입력해 주세요" + `OutlinedTextField`
     (singleLine, rememberSaveable) + `확인` 버튼(테마색). 빈 입력이면 버튼 비활성.
  2. `Loading`: 중앙 `CircularProgressIndicator` — 카드 높이는 1의 높이 유지(튀지 않게).
  3. `Viewing`: 현재 문장 `MarkupText`가 아닌 **일반 Text** (시나리오 원문 그대로,
     명조 serif — GM 서술과 같은 서체 토큰), 상하 패딩 대칭. 문장이 길면 카드 내부
     `verticalScroll`(최대 높이 화면의 1/3).
- **하단 행**: 좌측 진행 표시 `3 / 128`(10sp, `inkDim`) · **우하단 `<` `>` 버튼**
  (요구사항 위치 고정). 터치 타깃 `PbpDimens.touchTarget`, 첫/마지막 문장에서 해당
  방향 버튼 비활성(회색). 좌측에 작은 `다른 문서` 텍스트 버튼 → `scenarioReset()`.
- 접근성: `<`/`>`에 contentDescription("이전 문장"/"다음 문장").

## V5. 경고창

- `ScenarioState.Failed`일 때 `AlertDialog`(기존 삭제 확인 다이얼로그 :789 부근과 같은
  구성 — 확인 단일 버튼) 표시. 확인 시 `AskLink`로 복귀(입력값 보존).
- 사유별 문구 (모두 이 문서에서 확정 — 구현 세션이 임의로 바꾸지 말 것):
  - `BAD_LINK` — "구글 독스 문서 링크가 아닙니다. 공유 → '링크가 있는 모든 사용자'
    링크를 붙여넣어 주세요."
  - `NO_ACCESS` — "문서를 열 수 없습니다. 링크의 공유 설정이 '링크가 있는 모든
    사용자(뷰어)'인지 확인해 주세요."
  - `NETWORK` — "네트워크 오류로 문서를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요."
  - `EMPTY` — "문서에서 표시할 문장을 찾지 못했습니다."

## V6. 검증

- 단위: `ScenarioDocTest`(V0 케이스 전부) — `gradlew :shared:test`.
- 수동 체크리스트:
  - [ ] GM 프로필 활성 시에만 칩 노출, NPC·플레이어 프로필로 전환하면 칩·창 모두 사라짐
  - [ ] 칩 탭 → 링크 입력 화면. **그 외 어떤 경로로도 창이 저절로 뜨지 않음**
  - [ ] 실제 뷰어 링크 → 문장 표시, `<` `>` 이동, 경계에서 버튼 비활성
  - [ ] 창 닫고 다시 열면 읽던 문장 유지, "다른 문서"로만 초기화
  - [ ] 회전 → 창 열림 상태·문장 위치·입력 중 링크 보존
  - [ ] 권한 없는 링크·스프레드시트 링크·아무 URL·비행기 모드 → 각 사유별 경고창
  - [ ] 캡처 모드 진입 시 창 숨김, 캡처 이미지에 찍히지 않음
  - [ ] `gradlew assembleDebug testDebugUnitTest` 통과
- **검증 후 에뮬레이터 즉시 종료.**

---

## 건드리지 않는 것

- 서버·동기화 — 이 기능은 **완전 로컬**이다. Firestore/Protocol/rules/functions 무변경,
  read·write 증가 0. 시나리오 내용은 상대에게 전송되지 않는다(GM 화면 전용).
- Room DB — 스키마 변경 없음 (영속화 결정 1을 채택해도 SharedPreferences로 충분).
- 데스크톱 — 결정 2 참조 (범위 밖).
