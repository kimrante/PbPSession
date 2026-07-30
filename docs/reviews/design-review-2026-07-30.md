# UI 디자인 감사 2026-07-30 — 토큰·스페이싱·라이트 모드 전수 점검

역할: 전문 App UI 디자이너. 앱(Android)·데스크톱 전 UI 코드를
디자인 기준 3종 — ① `Tokens.kt`/목업 v2 0장 토큰(4dp 그리드 6단계 여백·반경 4단계·
타이포 5단계), ② CLAUDE.md §0(센터 정렬 / 상하 대칭 패딩 / 동류 컴포넌트 동일 스페이싱),
③ `PbP-design-spec.md`(라이트 모드 기준, 옐로 텍스트→signatureInk, 파괴 동작→danger) —
에 대조해 전수 감사했다. **적용 현황(2026-07-30): P1~P4는 코드에 반영·푸시됨**
(P5 접근성·품질 제안은 별도 판단 대상으로 보류). 신규 토큰: `rTail`(꼬리 4) ·
`avatarBar`(32) · `avatarProfile`(92) · 데스크톱 `gap1~6`/`rCell·rCard·rSheet·rTail`/
`touchTarget`/`OnSignature`/`StatBlue`, `themePresets`는 `:shared Palette`로 이관.

총평: 색 팔레트 자체는 양 플랫폼 완전 일치하고 토큰 체계도 건실하다. 문제는
값이 아니라 **토큰이 있는데 안 쓰는 하드코딩**, **라이트 모드에서 파탄 나는
다크 전용 색**, **동류 컴포넌트끼리 갈라진 스페이싱**에 몰려 있다.

---

## P1 — 즉시 수정 (스펙 명시 위반 · 라이트 모드 실결함)

### 1. 파괴적 동작 색 오용 — 4곳, 3가지 다른 방식으로 틀림
스펙 §0: 파괴 동작은 `danger` 토큰(#C94F4F 라이트). 현재:

| 위치 | 현재 | 문제 |
|---|---|---|
| `ChatScreen.kt:460` 메시지 삭제 | `Pbp.colors.signature` | 옐로 원색 — 밝은 다이얼로그 위 옐로 텍스트 금지 규칙까지 이중 위반 |
| `RoomListScreen.kt:330` 방 삭제 | `Pbp.colors.signature` | 동일 |
| `RoomSettingsScreen.kt:301` 로그 전부 삭제 | `Color(0xFFFF6B6B)` | 다크 danger 값 하드코딩 — 라이트에서 색 안 맞음 |
| `ProfileEditScreen.kt:444` 캐릭터 삭제 | `tokens.signatureInk` | 파괴 동작이 골드색 |

참고로 `ChatDialogs.kt:166`의 메시지 액션 삭제는 올바르게 `danger`를 쓰고 있어
동류 컴포넌트 불일치이기도 하다. **수정: 4곳 모두 `danger` 토큰.**
데스크톱 `ChatPane.kt:283` 판정 실패색 `Color(0xFFFF6B6B)`도 같은 건.

### 2. 라이트 모드에서 보이지 않는 흰색 계열 — 선택 링·입력 필드
- `ColorSwatchRow.kt:147` 선택 링이 `Color.White` 고정 — 흰 패널 위 흰 링,
  흰색 계열 스와치 위 흰 링 → **라이트 모드에서 선택 상태 식별 불가**.
  `Ui.kt:373·417`, 데스크톱 `ProfileOverlays.kt:435·473`도 동일 패턴.
  수정: `tokens.ink` 기반 링(또는 다크/라이트 분기 토큰).
- `ChatInput.kt:203·217·267–268` 잡담 토글 off 배경·트랙·텍스트필드 컨테이너가
  `Color.White.copy(alpha = .07~.2f)` — 라이트 입력바 배경이 흰색 93%라
  **필드·토글이 배경과 구분되지 않는다**. 수정: `chatterBubble`/`panel2`/`line` 계열.

### 3. 밝은 면 위 옐로 원색 텍스트 (스펙 0장 금지)
- `ProfileDialogs.kt:114` "＋ 프로필 추가하기", `Ui.kt:560` AddOptionRow 타이틀 —
  `tokens.signature` → 흰 다이얼로그 위 대비 약 1.5:1. 수정: `signatureInk`.
  (`ProfileEditScreen.kt:371` "추가" 버튼은 올바른 선례.)

### 4. 토큰이 이미 존재하는 값의 하드코딩 (기계적 교체 가능)
| 위치 | 하드코딩 | 존재하는 토큰 |
|---|---|---|
| `MessageBlock.kt:193` 성공/실패 | `0xFF5E9EFF` / `0xFFFF6B6B` | `statBlue` / `danger` |
| `Ui.kt:249` 값 치환 스팬 | `0xFF3B82F6` | `statBlue` (non-composable이라 호출부에서 파라미터로 전달) |
| `ChatInput.kt:225` 토글 노브 | `0xFF1A1A1A` | `onSignature` |
| `ChatInput.kt:283` 전송 아이콘 | `0xFF0D1420` | `bubbleInk`(#10151C)와 같은 역할 |
| `ProfileEditScreen.kt:419`, `ColorSwatchRow.kt:157` | `0xFF10151C` | `bubbleInk` |
| `Theme.kt:26·43` onPrimary | `0xFF1A1A1A` | `onSignature` |
| `ProfileDialogs.kt:80·99` 아바타 36dp | 리터럴 | `avatarStrip` |
| `ProfileEditScreen.kt:424·433` 미리보기 아바타 | `34.dp` | `avatarChat`(38) — Owner 쪽은 토큰 사용 중 |
| `ColorSwatchRow.kt:32` `GAP = 4.dp` | 리터럴 | `gap1` |
| `Ui.kt:314` 커스텀 색 seed | `0xFF8EC5E8` | `themePresets` 첫 값 |
| 데스크톱 `ChatPane.kt:273·808` | `0xFF7A5B12` | `Tokens.DiceInk` |
| 데스크톱 `ChatPane.kt:718` GM 금테 | `0x99C89E34` | `Tokens.GmRing` |
| 데스크톱 `ProfileOverlays.kt:362`, `ChatPane.kt:713·752`, `:563·582` | `0xFF10151C`, 36dp | `BubbleInk`, `avatarStrip` |

---

## P2 — 상하 비대칭 패딩 (CLAUDE.md §0-(b) 위반, 8건)

| 위치 | 현재 | 수정 |
|---|---|---|
| `ChatInput.kt:128` 입력 영역 | top=gap2, bottom=gap3 | `vertical = gap3` (또는 gap2) |
| `RoomSettingsScreen.kt:409` SectionTitle | top=gap5, bottom=gap2 | `vertical = gap2` + 섹션 간격은 루트 `Arrangement.spacedBy(gap5)`로 이동 |
| `RoomListScreen.kt:233` 리스트 contentPadding | top=0, bottom=88 | `top = gap3` 추가 (첫 카드 앱바 밀착 해소), 88은 파생식 주석 |
| `OwnerProfileScreen.kt:236` | bottom=gap6만 | ProfileEdit처럼 말미 `Spacer(gap6)` 방식으로 통일 |
| `ProfileEditScreen.kt:378` | top=gap2만 | `Spacer(gap2)` 분리 |
| `ChatScreen.kt:347·362` 리스트 항목 | top만 | :362는 `vertical` 대칭화, :347(연속 말풍선 간격)은 예외로 문서화 |
| 데스크톱 `ChatPane.kt:696` 입력 영역 | top=8, bottom=12 | `vertical` 통일 |
| 데스크톱 `RoomListPane.kt:190` | top=8, bottom=0 | `vertical = 8.dp` |

---

## P3 — 동류 컴포넌트 스페이싱 발산 (CLAUDE.md §0-(c) 위반)

1. **알약형 소형 컨트롤 패딩 5종 난립**: 시스템·잡담 필(12,3) / 다이스 칩·이전대화(14,6) /
   추천 칩(gap3,5) / 잡담 토글(10,6) / 취소 버튼(gap3,gap2). 3·5·6·10·14는 전부
   토큰 밖. → **표시용 필 (gap3, gap1) / 터치용 칩 (gap3, gap2)** 2단으로 수렴.
2. **다이얼로그 이원화**: 커스텀 Dialog(`rSheet`·`tokens.panel`) vs 스톡 M3
   AlertDialog(반경 28·headlineSmall 24sp — 타이포 스케일 밖). →
   `Theme.kt`에 `shapes = Shapes(extraLarge = RoundedCornerShape(PbpDimens.rSheet))`
   한 줄 + 타이틀 18sp 재정의로 일괄 해결.
3. **다이얼로그 행 패딩**: `ProfileDialogs.kt:136` ManagerRow만 gap2, 나머지
   (프로필 추가:109 / AddOptionRow / FontSettingDialog:48)는 gap3. → gap3 통일.
4. **프로필 사진 규격**: Owner 72dp(링-이미지 3dp 갭) vs ProfileEdit 92dp(갭 없음) —
   파일 주석(:66)은 "같은 규격 공유"라 명시하는데 실제로 다름. 미리보기 본문도
   11sp vs 13sp. → 한 규격으로 통일하고 `avatarProfile` 토큰 신설.
5. **GM/캐릭터 메시지 조각 간격**: `MessageBlock.kt:203` GM 10dp(토큰 밖) vs
   :231 캐릭터 gap1. → 양쪽 gap2 통일 권장.
6. **말풍선 꼬리 반경 4dp가 5곳 반복** (MessageBlock:269·272·378·380, ChatDialogs:114,
   ProfileEdit:411, 데스크톱 동일) → `PbpDimens.rTail = 4.dp` 토큰 승격.

---

## P4 — 플랫폼 드리프트 (Android ↔ Desktop, 같은 의미 다른 값)

| 항목 | Android | Desktop | 통일안 |
|---|---|---|---|
| 빈 상태 🎲 | 40sp (예외 목록 명기) | 44sp | 40 |
| 초대 코드 | 32sp + 센터 정렬 | 34sp + 좌측 | 32 + 센터 (§0-(a)) |
| 헤더 오너 아바타 | 30dp | 32dp | 32 |
| 연속 말풍선 간격 | gap1=4 (토큰 주석 명기) | 2dp | 4 |
| 성공/치환 파랑 | statBlue #3B82F6 | #3B82F6·#5E9EFF 혼재 | statBlue 단일 |
| 빈 상태 안내문 | 미지정(기본 14sp — 스케일 밖) | 13sp | 13 |
| 입력 필드 h패딩 | — | Overlays 13 vs ChatPane 14 | 단일 값 토큰화 |

근본 원인: `DesktopDimens`에 gap·반경·touchTarget 토큰이 없어 7/10/13/14/18/22dp
오프그리드 리터럴이 증식 중. **gap1~6 + rCell/rCard/rSheet만 데스크톱에 옮겨도
드리프트 대부분이 구조적으로 재발 방지된다.** `themePresets` 수동 사본
(desktop Theme.kt:66–68)도 `shared Palette`로 이관 권장 (namePresets 선례).

---

## P5 — 접근성·품질 제안 (선별)

1. **터치 타깃 40dp 미달**: 추천 칩(~25dp)·잡담 토글(~27dp)·"이전 대화 불러오기"(~27dp)·
   다이얼로그 "취소"(~33dp)·스와치 26dp·값 삭제 ✕ 24dp·데스크톱 Aa 버튼 32dp.
   → 시각 크기 유지 + `heightIn(min = touchTarget)` 또는 히트박스 확장.
2. **전송 버튼 대비**: 테마색(#8EC5E8 등) 배경 위 흰 ➤ 대비 ~1.9:1 —
   밝은 테마색일 때 잉크색 화살표 스왑 (`nameColorForLight` 계열 로직 재사용).
3. **타임스탬프 = 방 테마색**: 라이트 프리셋은 배경 사진 위 대비 취약 —
   라이트 보정 경로 적용 검토.
4. **다크 분기 잔재**: `RoomListScreen.kt:369` 등 `if (tokens.isDark)` 화면 코드 분기는
   "다크/화이트는 토큰 스왑만" 원칙과 상충 — 카드 배경을 PbpColors로 승격.
5. **잉크-알파 정리(데스크톱)**: 0x12/0x26/0x40/0x47/0x59/0x8C × 14191F 산재 —
   `Ink.copy(alpha=)` 3단계로 수렴.
6. `MessageBlock.kt:399` 인용 말풍선 주석("7·9dp 대칭")과 코드(9/5/9/+6) 불일치 —
   주석부터 현실과 맞출 것.
7. 9sp가 배지 아닌 곳에 사용: "읽음"/"(수정됨)"(MessageBlock:503·506),
   미리보기 캡션(ProfileEdit:391) → 10sp 승격 또는 예외 목록 등재.

## 부수 관찰 (수정 대상 아님, 보고만)

- 채팅 4개 파일이 동일한 대형 import 블록을 복사해 갖고 있고 다수 미사용 — 기존 데드 코드.
- `ChatDialogs.kt:155–172` `if (canModify)` 블록 들여쓰기 깨짐 (기능 무관).
- 무지개 그라데이션 등 기능성 고정 스펙트럼 색(Ui.kt HexColorDialog)은 토큰화
  부적합으로 판정 — 위반 아님, 주석으로 예외 명시만 권장.

---

## 수정 순서 제안

1. P1-1 danger 4곳 + 데스크톱 1곳 (5분, 위험 없음)
2. P1-4 기계적 토큰 교체 (교체표 그대로)
3. P1-2·3 라이트 모드 결함 (선택 링·입력 필드·옐로 텍스트)
4. P3-2 Theme.kt shapes/타이포 오버라이드 (한 곳 수정으로 다이얼로그 일괄)
5. P2 비대칭 패딩 8건
6. P4 데스크톱 gap 토큰 신설 + 드리프트 통일
7. P3 나머지·P5는 각 항목 별도 판단

---

## 조치 결과 (2026-07-30, v0.6.0)

| 항목 | 조치 |
|---|---|
| P1-1 | 파괴 동작 5곳 모두 `danger` 토큰 (메시지 삭제·방 삭제·로그 전부 삭제·캐릭터 삭제·데스크톱 판정 실패) |
| P1-2 | 색 선택 핸들은 **잉크 링 + 흰 링 이중**으로(어느 색 위에서도 보이게), 스와치 선택 링은 `ink`. 잡담 토글·입력 필드 컨테이너는 `chatterBubble`/`panel2`/`line` |
| P1-3 | "＋ 프로필 추가하기"·AddOptionRow·판정 추천 칩을 `signatureInk`로 (추천 칩은 감사 목록 밖이지만 같은 위반이라 함께) |
| P1-4 | 교체표 전 항목 + 데스크톱 잔여(`OnSignature`·`GmRing`·`avatarStrip`). 데스크톱 `StatBlue` 토큰 신설 |
| P2 | 8건 전부 대칭화. 연속 말풍선 위쪽 간격만 의도적 예외로 주석 명시, 섹션 제목은 앞 `Spacer(gap5)` + 대칭 패딩으로 분리 |
| P3-1 | 알약 패딩 2단 수렴 — 표시용 `(gap3, gap1)` / 터치용 `(gap3, gap2)`, 양 플랫폼 |
| P3-2 | `Theme.kt`에 `Shapes(extraLarge = rSheet)` + `headlineSmall` 18sp — 스톡 AlertDialog 일괄 정렬 |
| P3-3 | ManagerRow·색 카드 `gap3` 통일 |
| P3-4 | `avatarProfile = 92dp` 토큰 신설, 오너도 링을 이미지에 직접(3dp 갭 제거) — 파일 주석대로 같은 규격 |
| P3-5 | GM·캐릭터 조각 간격 모두 `gap2` |
| P3-6 | `PbpDimens.rTail = 4dp` 승격, 5곳 교체 |
| P4 | 데스크톱에 `gap1~6`·`rCell/rCard/rSheet`·`touchTarget`·`avatarBar` 신설. 빈 상태 🎲 40 / 안내문 13sp / 초대 코드 32sp+센터 / 헤더 아바타 32 / 연속 말풍선 4dp / statBlue 단일 / 입력 필드 h패딩 `gap3`. `themePresets`를 `:shared Palette`로 이관 |
| P5-1 | 잡담 토글·판정 추천 칩에 히트박스 40dp (시각 크기 유지). 스와치 26dp는 "9개 한 줄" 레이아웃과 충돌해 제외 |
| P5-2 | 전송 화살표를 잉크로 — 밝은 테마색 위 흰 화살표 대비 문제 해소 (양 플랫폼) |
| P5-3 | 타임스탬프에 이름색과 같은 라이트 보정 경로 적용 |
| P5-4 | `cardBg`·`chatBarBg` 토큰 승격 — 화면 코드의 `if (isDark)` 분기 제거 |
| P5-5 | **미조치** — 데스크톱 잉크 알파 6종 수렴은 시각 변화 대비 범위가 넓어 보류 |
| P5-6 | 인용 따옴표 오프셋 주석을 코드 현실에 맞게 정정 |
| P5-7 | 9sp는 배지 전용 — "읽음"·"(수정됨)"·미리보기 캡션을 10sp로 |
| 부수 관찰 | 보고 항목이라 미조치 (미사용 import·들여쓰기·무지개 그라데이션) |
