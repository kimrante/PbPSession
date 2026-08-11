# 코드 리뷰 보고서 — "캡처 모드 중 새 메시지 수신 시 앱 종료" 검증 (2026-08-10, v0.16.0)

**v0.16.0(`eb97076`)** 기준. 사용자 보고 "캡처 모드에서 새 메시지를 받으면 앱이 꺼진다"가
실제인지, 크래시가 날 수 있는 상황 위주로 모바일(app)·데스크톱(desktop)의 캡처 경로 전체를
정독한 결과다. **다른 세션에서 이 문서만 보고 수정 작업을 진행할 수 있도록** 항목마다
위치·재현·수정 방향을 명시한다. 라인 번호는 `eb97076` 기준. 항목 번호는 이전 라운드
(A~F, G/H/I/K)와 겹치지 않게 **X(즉시)/Y(보강)** 를 쓴다.

## 총평 — 보고된 재현 경로는 확인되지 않음, 인접 크래시 1건 실존

- **모바일: "캡처 모드 + 새 메시지 수신 → 크래시" 경로를 코드에서 찾지 못했다.**
  선택 상태가 인덱스가 아니라 **조각 키(`"<메시지 id>:<조각 번호>"`)** 로 저장되고
  (ChatScreen.kt:470-471), 매 컴포지션마다 키→인덱스로 재계산하므로(:480-488) 새 메시지로
  목록이 밀려도 범위가 어긋나지 않는다. 아래 "재현 시나리오 추적"에 수신 케이스별 전수
  추적을 남긴다. 실기기에서 재현된다면 코드 정독 밖의 원인(OOM 등, Y3)일 가능성이 크므로
  **logcat 스택트레이스 확보가 선결**이다 — 문서 끝 "추가 확인" 참조.
- **데스크톱: 캡처 모드에서 앱을 즉사시킬 수 있는 비가드 인덱스 접근 1건 확인 (X1).**
  `Main.kt onCaptureTap`이 `messages[next.first]`를 가드 없이 읽는다. 다만 트리거는
  "새 메시지 수신"(목록이 **늘어나는** 회차 — 인덱스가 범위 안에 남는다)이 아니라
  **목록이 줄어드는 회차**(상대의 로그 리셋이 메타 폴로 적용되는 순간)와 클릭이 겹칠
  때다. 보고 증상("메시지 이벤트 직후 꺼짐")과 정황이 유사하므로, 보고가 PC에서 나온
  것이라면 이게 원인일 수 있다.
- 크래시는 아니지만 캡처 모드의 상태 정합이 깨지는 결함 2건(Y1·Y2)을 함께 잡는다.
  둘 다 몇 줄짜리 수정이다.

권장 수정 순서: **X1 → Y1 → Y2**. (Y3은 로그 확보 전까지 보류.)

---

## 재현 시나리오 추적 — 모바일이 안전한 근거

`messages`는 최신 `PAGE_SIZE(200)`개 창이다(ChatViewModel:109-114, Daos.kt:118-123).
캡처 모드 중 새 메시지가 도착하는 경우를 나눠 추적했다.

| # | 시나리오 | 결과 | 근거 |
|---|---|---|---|
| 1 | 200개 미만에서 수신 (뒤에 추가) | 안전 | 앞쪽 조각 인덱스 불변. `pieces`·`pieceKeys`·`captureIdx`·`pieceBase`가 전부 `remember(messages…)`로 같은 컴포지션에서 일괄 재계산 (ChatScreen.kt:475-493) |
| 2 | 200개 창에서 수신 — 창이 밀려 가장 오래된 1건 탈락 | 안전 | 선택이 키 기반이라 인덱스 밀림과 무관. `indexOf`로 재계산된 새 범위가 그대로 이어진다 |
| 3 | 창 밀림으로 **선택 시작점 메시지가 창 밖으로** | 안전(취소) | `captureIdx == null` → 자동 취소 이펙트가 모드를 닫고 안내한다 (ChatScreen.kt:495-501). 취소되기 전 한 프레임도 `pickedPieces`가 빈 목록으로 계산될 뿐 인덱스 접근이 없다 (:793-811, `dateRangeLabel`은 `firstOrNull` 가드, CaptureBar.kt:170-173) |
| 4 | 시계 오차로 새 메시지가 목록 **중간에 삽입** | 안전 | 키 기반이라 동일. 정렬은 DAO가 (createdAt, id)로 안정 정렬 |
| 5 | 수신과 같은 프레임에 사용자가 조각을 탭 | 안전(크래시 없음) | `onCaptureTap`이 `pieceKeys.getOrNull`을 쓴다 (ChatScreen.kt:508-515). 스테일 키가 저장되면 다음 프레임에 시나리오 3의 자동 취소로 수렴 |
| 6 | 렌더 중("이미지 만들기") 수신 | 안전 | `picked`는 스냅샷이고 렌더 전체가 `runCatching` (ChatViewModel.renderCapture:205-218) |
| 7 | 수신 메시지가 판정/다이스/빈 본문 등 특수형 | 안전 | `renderedPartCount`·`capturePiecesOf`가 최소 1조각을 보장 (MessageBlock.kt:769-778), 수신 파싱은 문서 단위 `runCatching` 격리 (SyncManager.processSnapshot:810-843) |

캡처 모드에서 새 메시지 수신 시 자동 스크롤은 의도적으로 건너뛰며(ChatScreen.kt:552-553)
크래시와 무관하다. LazyColumn 키는 로컬 PK라 중복 키 예외도 없다.

---

## X — 즉시 수정 (크래시 실존)

### X1. [크래시·데스크톱] onCaptureTap의 비가드 인덱스 접근 — 목록 축소와 클릭이 겹치면 IndexOutOfBoundsException 즉사

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:564-570`

  ```kotlin
  fun onCaptureTap(tapped: Int) {
      captureError = null
      val range = captureIdx ?: return
      val next = captureRangeAfterTap(range, tapped)
      captureStart = messages[next.first].docId   // ← 가드 없음
      captureEnd = messages[next.last].docId      // ← 가드 없음
  }
  ```

- **재현**: 클릭 핸들러는 리컴포지션 밖(프레임 사이)에서 돈다. `range`·`tapped`는
  **직전 컴포지션**의 값이고 `messages`는 **현재 상태**를 읽는다(위임 프로퍼티,
  Main.kt:140). 캡처 모드 중에 목록이 줄어드는 회차 —
  ① 상대가 로그를 리셋해 메타 폴이 `messages = kept`로 축소 적용 (Main.kt:379-391),
  ② 로그 리셋으로 `messages = emptyList()` (Main.kt:508-511) —
  가 클릭과 같은 프레임에 겹치면 `next`가 새 목록 범위를 벗어나
  `IndexOutOfBoundsException` → **프로세스 즉사**. 같은 함수 아래의 `makeCapture`는
  이미 `coerceIn`으로 방어하고 있어(Main.kt:576-579) 이 함수만 빠진 것이다.
  (새 메시지 **수신**만으로는 목록이 늘어나므로 이 크래시는 나지 않는다 — 인덱스가
  범위 안에 남는다. 다만 시계 오차로 중간 삽입되면 클릭 위치와 다른 메시지가
  선택될 수 있는데, 다음 클릭으로 자가 교정되는 수준이라 별도 항목으로 두지 않는다.)
- **수정**: 모바일 `onCaptureTap`(ChatScreen.kt:508-515)과 같은 `getOrNull` 패턴으로.

  ```kotlin
  fun onCaptureTap(tapped: Int) {
      captureError = null
      val range = captureIdx ?: return
      val next = captureRangeAfterTap(range, tapped)
      val start = messages.getOrNull(next.first)?.docId ?: return
      val end = messages.getOrNull(next.last)?.docId ?: return
      captureStart = start
      captureEnd = end
  }
  ```

  둘 다 확보된 뒤에 대입한다 — 한쪽만 갱신되면 시작·끝이 서로 다른 세대로 어긋난다.
- **검증**: 데스크톱 실행 → 방 A에서 캡처 모드 진입·시작점 선택 → 다른 기기에서 그 방
  로그 초기화 → 메타 폴(60초 주기) 적용 직후 메시지를 연타 클릭. 수정 전에는 낮은
  확률로 즉사, 수정 후에는 무반응(범위 밖 return)이어야 한다. 레이스라 결정적 재현은
  어려우므로 코드 리뷰로 가드 존재를 확인하는 것을 1차 기준으로 한다.

---

## Y — 보강 (크래시 인접, 상태 정합)

### Y1. [모바일] 자동 취소 이펙트에 빈 목록 가드가 없다 — 프로세스 재생성 직후 캡처가 오탐 취소

- **위치**: `app/src/main/java/com/pbp/app/ui/chat/ChatScreen.kt:495-501`
- **재현**: `captureStart`/`captureEnd`는 `rememberSaveable`이라 프로세스 재생성 후
  복원되는데, 첫 컴포지션의 `messages`는 `stateIn` 초기값 `emptyList()`다(:112-114).
  빈 목록 → `captureIdx == null` → 이펙트가 "선택한 메시지가 **삭제되어** 캡처를
  취소했습니다"라는 틀린 안내와 함께 모드를 닫는다. 메시지는 삭제된 적이 없다.
  같은 파일의 편집 다이얼로그 이펙트(:520-525)는 `messages.isNotEmpty()` 가드를
  이미 두고 있다 — 그 가드가 여기만 빠졌다.
- **수정**: 동일 가드 추가.

  ```kotlin
  LaunchedEffect(captureIdx == null, capturing, messages.isNotEmpty()) {
      if (capturing && captureIdx == null && messages.isNotEmpty()) { … }
  }
  ```

  로드가 끝나 메시지가 실제로 남아 있으면 `captureIdx`가 재계산되어 선택이 그대로
  복원되고, 정말 사라졌으면 그때 취소 안내가 나간다.
- **검증**: 개발자 옵션 "활동 유지 안 함" + 캡처 모드에서 홈 → 복귀. 수정 전에는
  오탐 토스트와 함께 모드 해제, 수정 후에는 선택이 복원되어야 한다.

### Y2. [모바일] 끝점 메시지만 사라지면 captureEnd 키가 스테일로 남아 "범위 확정" 표시가 유지된다

- **위치**: `app/src/main/java/com/pbp/app/ui/chat/ChatScreen.kt:480-488` (b<0 → `a..a`),
  표시 분기 :586-587(상단 바 부제)·:805-810(`dateRange`/`estimatedPx`의
  `captureEnd == null` 판정)
- **재현**: 범위를 확정한 뒤 **끝점 메시지만** 창 밖으로 밀리거나 상대에 의해 삭제되면
  `captureIdx`는 `a..a`(시작점 1건)로 줄어드는데 `captureEnd`는 사라진 키를 그대로
  들고 있다. 화면은 "양 끝을 다시 탭해 조절할 수 있어요" + 날짜 범위·px 추정이 뜬
  **확정 상태**로 보이지만 실제 선택은 1조각이다. 사용자가 그대로 "이미지 만들기"를
  누르면 의도(원래 범위)와 다른 1조각짜리 이미지가 나온다. 크래시는 아니다.
- **수정**: "끝점이 정해졌는가"를 스테일 키가 아니라 실존 키로 판정한다. 예:

  ```kotlin
  val endPicked = captureEnd != null && pieceKeys.contains(captureEnd)
  ```

  를 `remember(pieceKeys, captureEnd)`로 두고 :586·:805·:810의 `captureEnd == null`
  판정을 `!endPicked`로 교체. (데스크톱은 `captureEndPicked = captureEnd != null`,
  Main.kt:920 — 같은 결함이므로 같은 방식으로 `messages.any { it.docId == captureEnd }`
  판정으로 교체.)
- **검증**: 기존 `CaptureRangeTest`(표시 상태)·`CaptureLayoutTest`(범위 규칙)는 그대로
  통과해야 한다. 수동으로는 2인 방에서 범위 확정 후 상대가 끝점 메시지를 삭제 →
  하단 바가 "끝 메시지를 탭하세요"(시작점 표기)로 되돌아가는지 확인.

### Y3. [관찰·보류] 모바일 "앱 꺼짐"이 실기기에서 계속된다면 — OOM 가능성

- **위치**: `shared/.../CaptureLayout.kt:22-29` (`MAX_HEIGHT_PX 8,000` /
  `MAX_TOTAL_HEIGHT_PX 32,000`), `app/.../CaptureRenderer.kt:243` (ARGB_8888 생성)
- **관찰**: 상한까지 고르면 720×32,000×4B ≈ **92MB**의 비트맵이 동시에 힙에 올라간다
  (주석 R2가 이미 인지). 저사양 기기에서는 상한 안쪽에서도 OOM으로 죽을 수 있고,
  OOM은 사용자에게 "앱이 그냥 꺼졌다"로 보인다. 다만 이는 "이미지 만들기" 시점의
  일이지 **새 메시지 수신과는 무관**하므로, 보고 증상과는 트리거가 다르다.
- **지시**: 지금 고치지 않는다. 실기기 재현 시 logcat에서 `OutOfMemoryError` 여부를
  먼저 확인하고, 맞다면 그때 `Runtime.maxMemory()` 기반으로 총 상한을 낮추는 별도
  작업을 연다. 추측으로 상한을 건드리면 멀쩡한 기기의 캡처 길이만 줄인다.

---

## 추가 확인 — 보고자에게 요청할 것

수정과 별개로, 보고된 크래시를 확정하려면 다음이 필요하다.

1. **어느 앱인가** — Android 앱인지 PC(데스크톱)인지. PC라면 X1이 유력하다.
2. **logcat / 콘솔 스택트레이스** — Android는
   `adb logcat --buffer=crash`, 데스크톱은 실행 콘솔의 예외 출력. 예외 클래스 한 줄이면
   위 항목 중 어디인지(또는 이 문서 밖 원인인지) 즉시 판별된다.
3. 재현 당시 **방 메시지 수가 200개(창 상한) 부근이었는지**, 상대가 메시지
   삭제·로그 초기화를 했는지.

## 이번 라운드에서 확인하고 문제없음으로 종결한 것

- 모바일 선택 상태 파이프라인 전체(:470-515) — 키 기반이라 인덱스 밀림에 안전.
- `dateRangeLabel`·`estimateHeightPx`·`messagesForPieces`·`GmSpeech.split` — 빈 목록·
  빈 본문 등 경계 입력에서 예외 없음 (모바일 CaptureBar.kt:170-173, CaptureLayout.kt:68-69,
  MessageBlock.kt:792-799, GmSpeech.kt:19-37).
- 수신 경로(SyncManager) — 문서 단위 `runCatching` 격리(:810-843), 스냅샷 처리 실패가
  크래시로 번지지 않음(:770-776). 리스너 오류는 재접속 경로로 회수(G3 반영 확인).
- 캡처 렌더·재렌더·저장·공유 — 전부 `runCatching` 아래이며 실패는 토스트/부제로 표면화.
- LazyColumn 키 중복 없음(모바일: 로컬 PK, 데스크톱: 병합 시 docId dedup, Main.kt:307-310).
