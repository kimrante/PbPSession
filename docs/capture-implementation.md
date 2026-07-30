# 작업 지시서 — 대화 캡처 (범위 선택 → 이미지 내보내기)

시각 규격은 **`docs/mockups/mockup-capture.html`**(+PNG)에 있다. 이 문서는 **그 목업을 코드로 옮기는 순서와 지점**을
정리한 것이며, **다른 세션이 이 문서만 보고 작업할 수 있도록** 항목마다 위치·변경·주의를 명시한다.
기준 커밋: `8b3897c` (v0.5.4). 라인 번호는 이 커밋 기준.

> **선행 작업이 이미 들어와 있다.** v0.5.4의 '메시지 복사' 기능이 캡처가 필요로 하던 배선을 대부분 깔아 놓았다 —
> 롱프레스가 상대 메시지에서도 열리고(`MessageBlock`의 `incoming` 가드 제거), `MessageActionDialog`에
> `canModify: Boolean`이 생겼다. 덕분에 S1이 크게 줄었다. 이 문서는 그 상태를 전제로 쓰였다.

## 만드는 것 / 만들지 않는 것

**만드는 것** — 채팅방에서 메시지 A부터 메시지 B까지를 골라 **한 장의 PNG**로 합쳐 저장·공유하는 기능.
모바일(`app/`)과 데스크톱(`desktop/`) 양쪽.

**만들지 않는 것**

- 기존 **HTML 로그 내보내기(상단 바 `↓`)는 손대지 않는다.** 방 전체를 문서로 남기는 기능이라 성격이 다르다
  (`ChatScreen.kt:256-266`, `export/LogExporter.kt`, `:shared LogExport`). 캡처는 완전히 별개 경로다.
- 스크린샷을 찍지 않는다 — 화면 픽셀을 캡처하는 대신 **선택 범위를 오프스크린에서 다시 그린다**(S4 참조).
  그래야 상단 바·입력줄·딤이 섞이지 않고, 화면 폭에 따라 결과가 달라지지 않는다.
- 서버·스키마 변경 없음. Firestore 문서, Room 엔티티, 동기화 경로를 건드릴 이유가 없다.

## 작업 순서

앞 단계가 끝나야 다음이 의미가 있으므로 **S1 → S7 순서대로**. S1~S3까지만 해도 "범위를 고르는 UI"가
동작하므로 그 지점에서 한 번 확인하고 넘어가면 좋다.

| 단계 | 내용 | 규모 | 산출물 |
|---|---|---|---|
| S1 | 캡처 모드 상태 + 롱프레스 메뉴 진입 | S | `ChatScreen.kt`, `ChatDialogs.kt` |
| S2 | 범위 선택 표시 (흐리게 / 밴드 / 배지) | M | `MessageBlock.kt` |
| S3 | 하단 캡처 바 | S | 신규 `CaptureBar` |
| S4 | 이미지 렌더러 (오프스크린) | **L — 가장 위험** | 신규 `export/CaptureRenderer.kt` |
| S5 | 미리보기 화면 | M | 신규 `ui/chat/CapturePreviewScreen.kt` |
| S6 | 갤러리 저장(MediaStore) · 공유 | M | 신규 `export/CaptureSaver.kt` + `AndroidManifest` + `res/xml/file_paths.xml` |
| S7 | 데스크톱 이식 | M | `ChatPane.kt`, `Overlays.kt`, `Main.kt` |

---

## S1. 캡처 모드 상태와 진입

### S1-1. 진입 배선은 이미 있다 — 데스크톱 한 곳만 빠졌다

- 모바일 `MessageBlock.kt:173`, `:299`, `:417`은 이미 `onLongClick = { onLongPress(message) }`로
  **상대 메시지에서도** 팝업을 연다(주석 `// 복사는 상대 메시지에서도`). 손댈 것이 없다.
- **다만 데스크톱은 한 곳이 빠져 있다**: `desktop/.../ChatPane.kt:325`의 GM 서술
  (`NarrationBlock(onLongPress = { if (mine) onLongPress(message) })`)만 여전히 `if (mine)` 가드가 남아,
  같은 파일의 다른 세 곳(`:305`, `:494`, `:521`)과 어긋난다. **상대의 GM 서술은 복사도 캡처도 안 된다.**
  가드를 제거해 `onLongPress = { onLongPress(message) }`로 맞춘다 — 복사 기능의 누락을 함께 고치는 셈이다.

### S1-2. `MessageActionDialog`에 캡처 줄 추가

- **위치**: `app/src/main/java/com/pbp/app/ui/chat/ChatDialogs.kt:103-180`.
  현재 줄 구성은 **복사**(항상) → `if (canModify) { 편집, 삭제 }`.
- **변경**:
  - 시그니처에 `onCapture: () -> Unit`만 추가한다. **`canModify`는 이미 있으므로 새 플래그를 만들지 말 것.**
  - 캡처 줄은 `canModify` **밖**에 둔다(복사와 같이 누구 메시지에서든 쓸 수 있어야 한다).
    위치는 `if (canModify) { ... }` 블록 **뒤** — 즉 내 메시지에서는 복사·편집·삭제·캡처 4줄,
    상대 메시지에서는 복사·캡처 2줄이 된다.
  - 기존 `MessageActionRow`를 그대로 재사용한다(새 부품 만들지 말 것):
    `icon = "🖼️"`, `tileColor = tokens.themeDefault`, `title = "캡처"`,
    `titleColor` = 이름색 계열의 진한 청색, `subtitle = "여기부터 범위를 골라 이미지로 만듭니다"`.
- **호출부**: `ChatScreen.kt:422-444`의 `MessageActionDialog(...)`에 `onCapture`를 추가하고
  `{ captureStart = target.id; captureEnd = null; actionTargetId = null }`을 넘긴다.
  `onCopy`가 이미 같은 형태(동작 후 `actionTargetId = null`)라 패턴을 따라가면 된다.
- 목업 A컷이 이 구성(복사·편집·삭제·캡처 4줄)으로 그려져 있으니 그대로 맞추면 된다.

### S1-3. 모드 상태

- **위치**: `app/src/main/java/com/pbp/app/ui/chat/ChatScreen.kt:199-212` (다이얼로그 대상들이 모여 있는 곳).
- **변경**: 같은 자리에 추가한다.

  ```kotlin
  // 캡처 범위 — (시작 메시지 id, 끝 메시지 id). 끝이 null이면 아직 고르는 중.
  // 회전에도 유지되도록 다이얼로그 대상들과 같은 rememberSaveable 규칙 (N10)
  var captureStart by rememberSaveable { mutableStateOf<Long?>(null) }
  var captureEnd by rememberSaveable { mutableStateOf<Long?>(null) }
  ```

  `captureStart != null`이면 캡처 모드다.
- **범위 계산**: 화면 렌더마다 O(N) 재스캔하지 않도록 `remember`로 인덱스 구간을 캐시한다
  (`:209-211`의 `editTarget` 등과 같은 방식).

  ```kotlin
  // messages는 오래된 순. 사용자가 위/아래 어느 쪽을 먼저 눌러도 되도록 정렬한다
  val captureIdx = remember(messages, captureStart, captureEnd) {
      val a = messages.indexOfFirst { it.id == captureStart }
      val b = messages.indexOfFirst { it.id == captureEnd }
      when {
          a < 0 -> null
          b < 0 -> a..a
          else -> minOf(a, b)..maxOf(a, b)
      }
  }
  ```

- **탭 처리**: 목업 03장의 규칙을 그대로 옮긴다. 새 함수 하나로 모은다.

  ```kotlin
  fun onCaptureTap(tappedIndex: Int) {
      val range = captureIdx ?: return
      when (tappedIndex) {
          // ② 양 끝을 다시 탭 → 그 끝을 그 자리로 (한 건만 선택된 상태면 무시)
          range.first -> if (range.first != range.last) captureStart = messages[range.last].id
              .also { captureEnd = messages[range.first].id }
          range.last  -> if (range.first != range.last) captureEnd = messages[range.first].id
          // ①③ 그 밖 → 가까운 쪽 끝을 거기까지 늘린다
          else -> if (tappedIndex < range.first) captureStart = messages[tappedIndex].id
                  else captureEnd = messages[tappedIndex].id
      }
  }
  ```

  위 `when`의 양 끝 분기는 **"반대쪽 끝을 고정한 채 이 끝만 옮긴다"**는 뜻이다. 읽기 어렵다면
  `start`/`end`를 인덱스로 들고 마지막에 id로 환산하는 편이 낫다 — 동작만 목업과 같으면 된다.
- **모드 종료**: `BackHandler(enabled = captureStart != null) { captureStart = null; captureEnd = null }`.
  방을 나가면 안 된다.
- **모드 중 잠그는 것**: 롱프레스(편집·삭제), 프로필 전환, 입력. `MessageBlock`에 넘기는 `onLongPress`를
  캡처 모드에서는 빈 람다로 바꾸고, `InputZone` 자리를 `CaptureBar`가 대신한다(S3).
- **자동 스크롤 정지**: `:241-254`의 `LaunchedEffect`가 새 메시지마다 `scrollToItem(0)`(`:251`)을 부를 수 있다.
  고르던 자리가 밀리면 안 되므로 **캡처 모드에서는 스크롤하지 않는다** — 조건에
  `captureStart == null`을 추가한다. (`pendingScrollToLatest`는 내 발신 전용이고 모드 중에는 전송이
  막히므로 자연히 false다.)

---

## S2. 범위 선택 표시

- **위치**: `MessageBlock.kt:137-146` (`MessageBlock` 시그니처), 호출부는 `ChatScreen.kt:351-363`.
- **변경**: 파라미터 2개를 추가한다. `showTime`·`showRead`는 v0.5.x에서 들어온 기존 파라미터이므로 그대로 두고
  그 뒤에 붙인다.

  ```kotlin
  internal enum class CaptureMark { NONE, OUT, IN, START, END, ONLY }

  internal fun MessageBlock(
      message: Message,
      grouped: Boolean = false,
      showTime: Boolean = true,
      showRead: Boolean = false,
      themeColor: Color,
      mark: CaptureMark = CaptureMark.NONE,   // 추가
      onTap: () -> Unit = {},                  // 추가
      onLongPress: (Message) -> Unit,
  )
  ```

  기본값을 준 이유는 **캡처 렌더러가 `mark`/`onTap`을 안 넘기고 그대로 부를 수 있게** 하려는 것이다(S4).

- **그리는 규칙** — **말풍선 내부는 절대 손대지 않는다.** 기존 `when` 블록을 감싸는 `Box` 하나에만
  배경·테두리·투명도를 얹는다. 그래야 캡처 이미지에서 `mark = NONE`으로 부르면 화면과 완전히 같은 결과가 나온다.

  | mark | 표시 |
  |---|---|
  | `NONE` | 아무것도 안 함 (평상시 · 캡처 이미지) |
  | `OUT` | `Modifier.alpha(.32f)` — 범위 밖 |
  | `IN` | 시그니처 `.26f` 배경 + 좌우 2dp 테두리 |
  | `START` | `IN` + 위쪽 2dp 테두리 + 상단 라운드(`rCell`) + 중앙 상단 '시작' 배지 |
  | `END` | `IN` + 아래쪽 2dp 테두리 + 하단 라운드 + 중앙 하단 '끝' 배지 |
  | `ONLY` | 사방 2dp 테두리 + `rCell` 라운드 + '시작' 배지 (한 건만 선택된 상태) |

- **밴드가 끊기지 않게**: 목록의 항목 간격은 `ChatScreen.kt:355`에서
  `Box(Modifier.padding(top = if (grouped) gap1 else gap3))`로 준다. 밴드 배경이 이 간격에서 끊기면
  "범위"로 읽히지 않는다. **간격 자체를 밴드가 먹도록** 캡처 모드에서는 padding을 `Box` 밖이 아니라
  밴드 안쪽으로 옮긴다 — 즉 캡처 모드에서 항목은 `밴드 Box( padding(vertical) { MessageBlock } )` 구조가 되고,
  인접 항목의 밴드가 서로 맞닿는다. 목업에서 이 부분을 실측해 **틈 0px**을 확인했다.
- **탭 영역**: 말풍선만이 아니라 **행 전체**가 탭 대상이어야 한다(GM 서술·다이스·시스템 줄도 골라야 하므로).
  감싸는 `Box`에 `clickable(onClick = onTap)`을 준다. 캡처 모드가 아닐 때는 `onTap`이 빈 람다이므로
  `clickable`을 아예 붙이지 않는다(불필요한 리플·접근성 노드 방지).
- **선택 단위는 메시지 1건**: 대사가 섞인 발화는 `GmSpeech.split`으로 말풍선 여러 개가 되지만
  (`MessageBlock.kt:243-270`), 선택은 `message.id` 기준이다. 조각 중 어느 것을 탭해도 그 메시지 전체가 잡히고,
  이미지에도 조각이 전부 들어간다 — 감싸는 `Box`가 하나이므로 자연히 그렇게 된다.

---

## S3. 하단 캡처 바

- **위치**: `ChatScreen.kt:389-402`의 `InputZone` 자리.
- **변경**: 캡처 모드면 `InputZone` 대신 신규 `CaptureBar`를 그린다. `ChatInput.kt`에 함께 두거나
  `ChatDialogs.kt` 옆에 새 파일로 둔다.

  ```kotlin
  @Composable
  internal fun CaptureBar(
      count: Int,
      timeRange: String?,   // "21:03–21:14" — 끝이 없으면 null
      estimatedPx: Int?,
      overLimit: Boolean,
      onMake: () -> Unit,
  )
  ```

- **규격** (목업 실측 기준):
  - 배경 `tokens.panel`, 위쪽 1dp `tokens.line`, **패딩은 상하 `gap3` 동일 · 좌우 `gap4`**.
  - 왼쪽에 `N개 선택됨`(13sp 900) + 부제(10sp `inkDim`). **부제는 한 줄 고정**
    (`maxLines = 1`, `TextOverflow.Ellipsis`) — 두 줄로 감기면 바 높이가 튄다.
  - **바 높이는 70dp 고정.** 선택 상태가 바뀔 때 바가 위아래로 튀지 않게 하려는 것이고,
    목업에서 두 상태 모두 70px임을 확인했다.
  - 오른쪽 CTA `이미지 만들기` — 시그니처 캡슐. 끝점이 없거나 `overLimit`이면 비활성
    (배경 `ink .08f`, 글자 `ink .34f`, 클릭 무시).
- **부제 문구**: 상단 바 부제와 **겹치지 않게** 쓴다. 상단 바가 "끝 메시지를 탭하세요"를 말하므로
  하단 바는 시작점 정보(`시작 21:05 · 밤샘꾼`)를, 범위가 정해지면 `21:03–21:14 · 약 1,180px`를 보여준다.
- **예상 높이**: 실제 렌더 전이므로 정확할 수 없다. 메시지당 대략치(말풍선 1줄 ≈ 46dp, 서술 문단 ≈ 90dp,
  시스템·다이스 ≈ 28dp)를 더한 **어림값**이면 충분하고, 문구에 `약`을 붙여 어림임을 밝힌다.

### 상단 바

- **위치**: `ChatScreen.kt:278-336` (상단 바 블록).
- **변경**: 캡처 모드면 같은 56dp 자리에 모드 상단 바를 그린다 — 배경을 시그니처 톤으로 바꿔
  모드 전환을 알리고, 왼쪽은 `✕`(모드 종료), 가운데는 `캡처할 범위 선택` + 상태 부제, 오른쪽은 비운다.
- **주의**: 가운데 묶음은 기존과 같은 방식(좌우 같은 인셋으로 절대 배치)을 유지해야 중심이 흔들리지 않는다.
  버튼이 왼쪽 하나뿐이라 `titleInset`(96dp)은 너무 넓다 — 모드 바는 좌우 `gap6`(32dp)를 쓴다.

---

## S4. 이미지 렌더러 — 가장 위험한 단계

**신규** `app/src/main/java/com/pbp/app/export/CaptureRenderer.kt`.

### 왜 오프스크린 재그리기인가

화면 캡처(`View.draw` on 채팅 화면)는 상단 바·입력줄·딤이 함께 찍히고, 화면에 안 보이는 부분은 아예 없다.
선택 범위 전체를 담으려면 **화면 높이와 무관하게** 그려야 한다. 폭을 **360dp로 고정**하는 이유는
기기 폭에 따라 줄바꿈이 달라져 결과가 갈리지 않게 하려는 것이다.

### 방법

`ComposeView`를 창에 붙여 컴포지션을 돌린 뒤, 높이 무제한으로 measure/layout하고 `Bitmap`에 그린다.

```kotlin
suspend fun render(
    activity: ComponentActivity,
    widthPx: Int,                       // 360dp를 px로
    content: @Composable () -> Unit,
): Bitmap = withContext(Dispatchers.Main) {   // measure/layout/draw는 메인 스레드
    val view = ComposeView(activity).apply { setContent(content) }
    val root = activity.window.decorView as ViewGroup
    // 화면 밖에 두되 붙여 둔다 — 떼어 두면 컴포지션이 돌지 않는다(GONE도 마찬가지)
    view.alpha = 0f
    root.addView(view, ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT))
    try {
        // 컴포지션 → 레이아웃까지 최소 두 프레임 필요 (이미지 로딩은 아래 주의 참조)
        awaitFrame(); awaitFrame()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, widthPx, view.measuredHeight)
        Bitmap.createBitmap(widthPx, view.measuredHeight, Bitmap.Config.ARGB_8888)
            .also { view.draw(Canvas(it)) }
    } finally {
        root.removeView(view)
    }
}
```

- **아바타 이미지**: 본문이 `Avatar`(Coil `AsyncImage`)를 쓰므로 **두 프레임만으로는 아직 안 붙을 수 있다.**
  캡처 이미지에 아바타가 빈 원으로 나오면 이 문제다. 대책: 렌더 전에 해당 범위의 `senderImagePath`들을
  Coil로 미리 로드해 캐시를 채우거나(권장), `Avatar`에 미리 디코드한 `ImageBitmap`을 넘기는 오버로드를 만든다.
- **폭 계산**: `360.dp` → `with(density) { 360.dp.roundToPx() }`. 결과 이미지의 픽셀 크기는 기기 밀도에
  비례하므로(고밀도 기기에서 더 큰 이미지) 화면 밀도를 쓰지 말고 **고정 배율**(예: 2.0)로 그리는 편이
  결과가 일정하다 — 어느 쪽이든 선택하고 문서에 남길 것.
- **내용**: `CaptureHeader` → `RoomBackdrop`(또는 종이 톤) 위의 메시지들 → `CaptureFooter`.
  메시지는 **`MessageBlock`을 `mark = NONE`으로 재사용**한다. 간격·좌우 여백도 화면과 같은 값
  (`gap3` / `gap4`)을 쓴다. 이게 "화면과 결과가 갈라지지 않는다"의 실체다.
- **머리글·낙관**: 같은 파일에 둔다.
  - 머리글: 로고 타일 22dp(`ic_logo_d10`) + 방 이름(명조 12sp) + `2026-07-30 21:03 – 21:14 · 4개 메시지`.
    상하 패딩 `gap2` 동일.
  - 낙관: `PbP · 방 이름`과 날짜. 상하 패딩도 `gap2` — 머리글과 같은 값이어야 한다.
  - 날짜 문자열은 `ui/common/Ui.kt:600`의 `formatTime` 옆에 `formatDateRange(first, last)`를 추가해 만든다.

### 높이 상한과 분할

- 안드로이드 `Bitmap`은 아주 긴 세로 이미지에서 메모리로 터지고, 뷰어 앱에서도 열리지 않는다.
- **한 장 최대 8,000px.** 넘으면 여러 장으로 나눠 저장하고 낙관 옆에 `1/3`을 붙인다.
- **분할은 메시지 단위로만.** 말풍선을 가로질러 자르면 안 된다. 구현은 두 가지 중 하나:
  ① 메시지를 앞에서부터 누적하며 예상 높이가 상한에 닿으면 묶음을 끊고, 묶음별로 `render`를 따로 호출한다(권장 — 각 장이
  머리글·낙관을 온전히 갖는다). ② 한 번에 그린 뒤 자를 지점을 찾는다 — 큰 비트맵을 먼저 만들어야 해서
  터지는 문제를 못 피한다. **①로 갈 것.**
- **선택 개수 상한 200개** (채팅 한 페이지 = `ChatViewModel.PAGE_SIZE`와 같은 값). 넘으면 CTA를 잠그고
  하단 바에 안내를 띄운다.

---

## S5. 미리보기 화면

**신규** `app/src/main/java/com/pbp/app/ui/chat/CapturePreviewScreen.kt` + NavHost 라우트.

- **경로**: `이미지 만들기` → 렌더 → 미리보기. 렌더가 수백 ms 걸릴 수 있으므로 **진행 표시**를 둔다
  (CTA를 누른 직후 바를 "만들고 있어요"로 바꾸는 정도로 충분).
- **비트맵 전달**: 네비게이션 인자로 `Bitmap`을 넘길 수 없다. 캡처 상태를 `ChatViewModel`에 두고
  (`var captureResult by mutableStateOf<List<Bitmap>?>(null)`) 미리보기는 같은 VM을 보게 하는 것이 가장 간단하다.
  회전 시 재렌더를 피하려면 VM에 두는 것이 맞다. **화면을 벗어날 때 `recycle()`하지 말 것** — 회전으로
  컴포저블이 재생성되면 이미 그린 비트맵을 다시 써야 한다. VM `onCleared`에서 정리한다.
- **구성**: 상단 바 56dp(`←` + `캡처 미리보기` + `4개 · 1,180 × 720`) / 본문에 결과 이미지 /
  하단 바(상하 패딩 `gap3`).
- **결과 이미지 표시**: 실제 폭(360dp)으로 그려진 이미지를 **화면 폭에 맞춰 축소**해 보여준다.
  `Image(contentScale = ContentScale.FillWidth)` + 세로 스크롤. 위쪽 정렬로 둘 것 — 세로로 늘리면
  낙관 아래에 빈 면이 생긴다(목업에서 실제로 겪은 문제).
- **배경 포함 토글**: 스위치 1개. 켜면 `RoomBackdrop`과 같은 배경 + 가독성 베일, 끄면 `panel2` 종이 톤.
  **끄면 다시 렌더해야 한다** — 토글 변경 시 `render`를 재호출한다.
  마지막 선택은 `SharedPreferences("pbp-settings")`의 `captureWithBackground`에 저장한다
  (`OwnerProfile.kt`와 같은 파일·같은 규칙).
- 여러 장으로 분할되면 세로로 이어 보여주고 상단 바 부제에 `3장`을 표시한다.

---

## S6. 저장·공유

> **결정됨: 저장하면 갤러리에 있어야 한다.** SAF(`CreateDocument`)는 쓰지 않는다 —
> 사용자가 파일 위치를 고르는 방식은 "저장했는데 어디 갔지"가 되기 때문이다.
> `MediaStore.Images`에 넣어 **갤러리 앱에 바로 뜨게** 한다. `minSdk = 26`이라 API 28 이하 권한 분기가
> 따라오는데, 이 단계 작업량의 절반이 그 분기다.

### S6-1. 갤러리 저장 (`MediaStore.Images`)

`export/CaptureRenderer.kt` 옆에 두거나 `export/CaptureSaver.kt`로 분리한다.

```kotlin
/** 갤러리(Pictures/PbP)에 PNG로 저장. 실패하면 null */
suspend fun saveToGallery(
    context: Context,
    bitmap: Bitmap,
    fileName: String,          // "PbP_등대에서 만나요_20260730_2144.png"
): Uri? = withContext(Dispatchers.IO) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Q 이상: 앱 전용 폴더 지정 가능 + 쓰는 동안 갤러리에서 감춘다
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PbP")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return@withContext null
    runCatching {
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
        }
        uri
    }.getOrElse {
        // 절반만 쓰인 항목이 갤러리에 남지 않게 되돌린다
        resolver.delete(uri, null, null)
        null
    }
}
```

- **`IS_PENDING`을 반드시 쓸 것** (Q+). 없으면 큰 이미지를 쓰는 동안 갤러리에 깨진 썸네일이 잠깐 뜬다.
- **API 26–28**: `RELATIVE_PATH`가 없으므로 `Pictures/` 루트에 저장된다. 하위 폴더까지 원하면
  `MediaStore.Images.Media.DATA`에 절대 경로
  (`Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES)/PbP/파일명`)를 넣고
  그 디렉터리를 `mkdirs()`해 준다. **`DATA`는 Q 이상에서 쓰면 안 된다** — 분기를 섞지 말 것.
- **실패 시 `delete`로 되돌리는 것**을 빼먹지 말 것. 안 그러면 0바이트 항목이 갤러리에 남는다.
- 파일명은 `PbP_{방이름}_{yyyyMMdd_HHmm}.png`. 방 이름에 `/ \ : * ? " < > |`가 들어갈 수 있으므로
  **치환**한다. 여러 장이면 뒤에 `_1of3`을 붙인다.
- `MediaScannerConnection`은 필요 없다 — `resolver.insert`로 넣으면 이미 MediaStore 항목이다.

### S6-2. API 28 이하 권한

- **`AndroidManifest.xml`**: 현재 권한은 `INTERNET`, `POST_NOTIFICATIONS` 둘뿐이다. 추가한다.

  ```xml
  <!-- 갤러리 저장 — Q부터는 MediaStore가 권한 없이 쓰게 해 준다 -->
  <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
      android:maxSdkVersion="28" />
  ```

  `maxSdkVersion="28"`을 **꼭 붙일 것.** 없으면 Q 이상 기기에서도 권한이 목록에 뜨고 스토어 심사에서
  민감 권한으로 잡힌다.
- **런타임 요청**: 미리보기 화면에서 `저장`을 누를 때만, `Build.VERSION.SDK_INT <= 28`이고
  아직 허용되지 않은 경우에만 요청한다. 앱 시작 시점에 미리 묻지 말 것.

  ```kotlin
  val permLauncher = rememberLauncherForActivityResult(
      ActivityResultContracts.RequestPermission()
  ) { granted -> if (granted) doSave() else /* 아래 거부 처리 */ }
  ```

- **거부 처리**: 대체 저장 경로를 새로 만들지 않는다. 토스트로
  `"저장 권한이 없어 갤러리에 넣을 수 없습니다. 공유로 보내 보세요."`를 띄우고 끝낸다 —
  **공유는 권한이 필요 없으므로** 사용자에게 실제로 통하는 길이 남는다.
  `shouldShowRequestPermissionRationale`이 false인 영구 거부 상태까지 분기하지 말 것(과하다).

### S6-3. 공유

- `cacheDir/capture/*.png`에 쓰고 `FileProvider`로 `ACTION_SEND`(`image/png`).
  여러 장이면 `ACTION_SEND_MULTIPLE`. **권한 불필요.**
- `AndroidManifest.xml`에 `provider` 추가 + `res/xml/file_paths.xml` 신규 (현재 둘 다 없다).
  `authorities`는 `${applicationId}.fileprovider`.
- `Intent.FLAG_GRANT_READ_URI_PERMISSION` 필수. 없으면 받는 앱에서 `SecurityException`이 난다.
- 갤러리 저장분(`MediaStore` URI)을 공유에 재사용하지 말 것 — 저장을 안 한 채 공유만 하는 경우가 있고,
  캐시 파일은 정리가 쉽다. `cacheDir/capture`는 화면을 벗어날 때 비운다.

---

## S7. 데스크톱 이식

같은 규칙, 같은 결과 이미지. 데스크톱은 `Message`가 `type: String`·`authorUid` 기반이라는 점만 다르다.

- **진입**: `ChatPane.kt:216`이 이미 `onLongPress = onMessageLongPress`로 **길게 클릭**을 쓴다.
  우클릭 메뉴를 새로 만들 필요 없다 — 기존 액션 팝업(`Overlays.kt`)에 캡처 항목만 추가한다.
  가드는 `:325`(GM 서술) 한 곳만 남아 있다 — S1-1 참조.
- **모드 종료**: `Esc`. 창 단위 키 처리는 `Main.kt`의 기존 `onPreviewKeyEvent` 패턴을 따른다.
- **선택 표시**: `ChatPane.kt:243`의 `MessageBlock`에 모바일과 같은 `mark`/`onTap`을 추가한다.
- **렌더**: `ImageComposeScene`(Compose Desktop)으로 **같은 360dp 폭**에 그린다.
  `ImageComposeScene(width, height, density) { content }.render()` → `Image`(Skia) → PNG 바이트.
  높이를 먼저 알아야 하므로 넉넉한 높이로 한 번 재고 실제 높이로 다시 그리거나, 모바일과 같은
  누적 방식으로 묶음을 나눈다.
- **저장**: 기존 로그 내보내기와 같은 `java.awt.FileDialog`(`Main.kt:466`) — PNG.
- **양쪽 결과가 같아야 한다**: 폭·간격·머리글·낙관 수치가 모바일과 같은지 확인할 것.
  로그 내보내기에서 `roomIcon` 헤더가 한쪽에만 있어 결과가 갈렸던 전례가 있다
  (`docs/reviews/code-review-2026-07-30-clean.md` A1).

---

## 검증 체크리스트

컴파일·실행 확인이 되는 환경에서:

- [ ] 내 메시지 롱프레스 → 편집·삭제·캡처 3줄. **상대 메시지 롱프레스 → 캡처 1줄**
- [ ] 캡처 진입 시 그 메시지가 시작점(`ONLY` 표시, '시작' 배지), CTA 비활성
- [ ] 아래를 탭 → 범위가 아래로, **위를 탭 → 범위가 위로** (순서 자동 정렬)
- [ ] 양 끝 탭 → 그 끝만 이동. 범위 밖 탭 → 가까운 끝이 확장. **범위가 초기화되지 않는다**
- [ ] 선택 구간의 밴드가 **끊기지 않고 이어진다**. 범위 밖은 흐리다
- [ ] 하단 바 높이가 상태가 바뀌어도 **튀지 않는다**(70dp 고정), 부제가 두 줄로 감기지 않는다
- [ ] 모드 중 back → **모드만 종료**(방을 나가지 않음). 입력·프로필 전환·편집 잠김
- [ ] 모드 중 상대 메시지 수신 → **화면이 최신으로 튀지 않는다**, 새 메시지는 범위 밖
- [ ] 범위 안 메시지가 상대에 의해 삭제 → 그 건만 빠지고 개수가 줄어든다 (앱이 죽지 않는다)
- [ ] 결과 이미지: 상단 바·입력줄·딤이 **들어가지 않는다**. 아바타가 빈 원이 아니다
- [ ] 결과 이미지의 말풍선·이름색·시간·인용 따옴표·다이스·GM 서술이 **화면과 같다**
- [ ] 기기 폭이 다른 두 기기에서 **결과 이미지가 같다**(360dp 고정이 실제로 먹는지)
- [ ] 200개 초과 선택 → CTA 잠금 + 안내. 8,000px 초과 → 여러 장, 낙관에 `n/N`, **말풍선이 잘리지 않는다**
- [ ] 배경 토글 끄면 종이 톤으로 다시 렌더된다. 앱 재시작 후 마지막 선택이 유지된다
- [ ] 회전해도 미리보기의 이미지가 유지된다(재렌더로 깜빡이지 않는다)
- [ ] **저장 → 갤러리 앱에 바로 보인다** (Q 이상: `Pictures/PbP` 폴더 안)
- [ ] 저장 중 갤러리에 **깨진 썸네일이 뜨지 않는다**(`IS_PENDING`)
- [ ] 저장 실패·중단 시 **0바이트 항목이 갤러리에 남지 않는다**
- [ ] 방 이름에 `/`나 `:`가 들어간 방에서도 저장된다 (파일명 치환)
- [ ] **API 28 이하 기기**: 저장 탭 → 권한 요청 → 허용 시 저장. 거부 시 안내 토스트만 뜨고 앱이 멈추지 않는다
- [ ] **API 29 이상 기기**: 권한 요청이 **뜨지 않는다**. 앱 정보의 권한 목록에 저장 권한이 없다
- [ ] 공유 → 카카오톡/디스코드에 이미지가 실제로 붙는다 (권한 없이)
- [ ] 데스크톱 결과 이미지가 모바일과 같다
- [ ] 기존 `↓` HTML 로그 내보내기가 그대로 동작한다 (회귀 없음)

## 규모 감각

S1~S3(범위 고르는 UI)은 기존 부품 재사용이라 한 세션 분량이다. **S4가 이 기능의 실체이자 위험**이고,
특히 아바타 로딩 타이밍과 분할 처리에서 시간이 든다. S5는 배선이고, **S6은 갤러리 저장으로 정해져 권한 분기가 붙어 배선보다는 조금 더 든다**
(API 28 이하 요청·거부 처리, 파일명 치환, 실패 롤백). S7은 S4가 끝나면 기계적이다.

---

## 구현 결과 (2026-07-30)

S1~S7 전부 반영. 지시서와 다르게 간 지점만 남긴다.

| 단계 | 산출물 |
|---|---|
| S1 | `ChatDialogs.MessageActionDialog(onCapture)` · `ChatScreen`의 `captureStart/captureEnd` · `BackHandler` · 캡처 중 스크롤/롱프레스/입력 잠금. 데스크톱 GM 서술의 `if (mine)` 가드 제거 |
| S2 | `MessageBlock`에 `mark`/`onTap` · `Modifier.captureBand` · `EdgeBadge` (양 플랫폼) |
| S3 | 신규 `ui/chat/CaptureBar.kt` (하단 바 70dp 고정 + 모드 상단 바), 데스크톱 `CaptureBar.kt` |
| S4 | 신규 `export/CaptureRenderer.kt` — ComposeView 오프스크린, 360dp 폭 + **고정 밀도 2.0** |
| S5 | 신규 `ui/chat/CapturePreviewScreen.kt` + `Routes.capturePreview` |
| S6 | 신규 `export/CaptureSaver.kt` + `AndroidManifest`(권한·FileProvider) + `res/xml/file_paths.xml` |
| S7 | 데스크톱 `ImageComposeScene` 렌더러 · Esc 종료 · FileDialog 저장 |

**결정·이탈**

- **렌더 밀도는 고정 2.0**을 골랐다(지시서가 "어느 쪽이든 골라 문서에 남길 것"이라고 한 부분).
  기기 밀도를 쓰면 같은 대화가 폰마다 다른 픽셀 크기로 나온다. 360dp × 2.0 = **720px 고정**.
- **밴드 그리기**는 CSS의 `box-shadow: inset`을 그대로 옮길 수 없어, 열린 쪽(위/아래)의 둥근 모서리를
  캔버스 밖으로 밀어내 잘리게 하는 방식으로 구현했다(`captureBand`). 인접 밴드가 맞닿아도 가로선이 없다.
- **예상 높이는 한 함수**(`CaptureRenderer.estimateHeightPx`)만 쓴다. 하단 바 문구와 분할 판정이
  다른 계산을 쓰면 "약 N px"과 실제 장수가 어긋난다.
- **탭 규칙은 순수 함수**(`captureRangeAfterTap`)로 빼서 단위 테스트 9건으로 고정했다.
  데스크톱도 같은 이름·같은 규칙의 함수를 쓴다(모듈이 달라 코드는 각자 갖는다).
- **데스크톱에는 미리보기 화면이 없다**(지시서 S7이 FileDialog 저장만 요구). 대신 배경 포함 토글이
  묻히지 않도록 캡처 바에 `배경 포함 ✓` 버튼을 두었다.
- **캡처 이미지는 항상 라이트 토큰**으로 그린다. 기기 다크 설정에 따라 결과가 갈리면 안 된다.

**미검증** — 컴파일·단위 테스트까지만 확인했다. 아래는 실기기·실행 확인이 필요하다.

- 아바타 프리로드가 실제로 충분한지(빈 원으로 찍히지 않는지)
- 8,000px 초과 분할이 실제로 여러 장으로 나오는지
- 갤러리 저장·공유·API 28 이하 권한 흐름
- 데스크톱 `ImageComposeScene` 2패스 측정이 실제 높이를 맞추는지
