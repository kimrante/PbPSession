# UI/UX 전수검사 수정 지시서 2026-08-02

> **처리 상태 (v0.11.0)** — 0장 · P0 · P1 · P2 · P3 · P4 · P5 · P6 · 8장 · 9장 전부 반영.
> 검수 기준 3종 모두 통과: 색 리터럴 0건(아래 예외 제외) · 9/12/14/16sp 0건 ·
> 색 미지정 TextButton 0건.
>
> **색 리터럴 기능 예외 5종**을 `docs/ui-guidelines.md` §2에 표로 등재했다 —
> 색 피커(고르는 대상), 앱 아이콘 d10 음영, AWT 트레이 아이콘, 채팅 배경 암전,
> 크롭 레터박스. 각 사용처에 `기능 예외(가이드 §2)` 주석이 있어 grep 결과가 이 목록과
> 정확히 일치한다.
>
> 지시서와 다르게 처리한 것:
> - **토글 치수 통일**은 두 값(22/12/8 · 34/20/16) 중 **34/20/16**을 토큰으로 등재했다.
>   더 큰 쪽이 터치 타깃 규정에 가깝다.
> - **데스크톱 카드 면**(0-5)은 스펙 결정 항목이라 가이드대로 `Panel` + `Line` 테두리로 갔다.
> - **P5의 `letterSpacing` 1/1.5/3 통일**은 `labelTracking`(1sp) 1종으로 수렴했다.
>   초대 코드 4sp는 대형 표기 전용이라 그대로 뒀다.

검사 범위: 모바일 전 화면(캡처 신규 기능 포함) + 데스크톱 전 페인·오버레이.
기준: `docs/ui-guidelines.md` v1.0 (충돌 시 가이드 우선). **이 문서는 지시서다 —
코드는 아직 수정하지 않았다.**

> **v0.10.4 재검증 (2026-08-02)**: 감사는 v0.7.0 병합 시점 코드로 수행했고,
> 이후 main이 v0.10.4까지 전진해 **핵심 항목 전부를 현 코드로 재확인**했다 —
> 모두 유효(색 리터럴 81곳, 9/12/14sp 20곳 잔존). 라인 번호는 재검증한 항목만
> 갱신했으며 나머지는 ±20줄 안에서 스니펫으로 찾으면 된다. v0.8~v0.10에서
> 추가된 **신규 파일 3종의 동일 위반은 9장**에 추가.

총평: 지난 감사(P1~P4) 반영 이후 토큰 체계·카드·상단 바(방 목록)는 안정됐다.
남은 문제는 네 갈래다 — ① **M3 기본값 유출**(버튼·필드에 색/크기 미지정 →
옐로 원색 텍스트·14/16sp 유입), ② **신규 캡처 기능이 가이드 미준수 상태로 합류**,
③ **상단 바 타이틀 센터 정렬이 방 목록에만 적용**되고 3개 화면 + 데스크톱 채팅이
좌측 정렬로 남음, ④ **데스크톱 오프그리드 리터럴·드리프트 잔존**.

---

## 0. 선행 작업 — 토큰·전역 오버라이드 신설 (개별 수정 전에 이것부터)

개별 위반 다수가 "쓸 토큰이 없어서"다. 아래를 먼저 만들면 후속 지시의 절반이
기계적 치환이 된다.

### 0-1. PbpColors 토큰 추가 (`Tokens.kt`)
| 신규 토큰 | 값(다크/라이트) | 흡수하는 리터럴 |
|---|---|---|
| `scrim` | Black 35% 계열 / 동일 | ChatScreen:500, MessageBlock:202·247 (검정 스크림 필·칩) |
| `onScrim` | White 60% / 동일 | MessageBlock:205 (스크림 위 글자) |
| `barScrim` | Black 45% / Black 6% | ChatScreen:388 — **`if (isDark)` 분기 제거** (원칙 1) |
| `signatureDeep` | 0xFFEFB945 | 로고 그라데이션 (RoomListScreen:190, CaptureRenderer:304, RoomListPane:165) |

### 0-2. PbpDimens 규격 등재 (`Tokens.kt`) + 가이드 예외 목록 1줄씩
- `bubbleMaxWidth = 240` (MessageBlock:486) · `captureBarHeight = 70` (CaptureBar:34)
- `titleInsetNarrow = 56` — 버튼 1개형 상단 바용 (CaptureBar:67, CapturePreviewScreen:132)
- 토글 1벌: `toggleTrackW/H`, `toggleKnob` — 잡담 토글(22/12/8)과 캡처 배경
  토글(34/20/16)을 **한 치수로 통일** (어느 쪽이든 하나로 결정)
- 인용 말풍선 전용: `quotePadH = 26`, `quotePadV = 14` (+9/5/6 글리프 보정은 주석 유지)

### 0-3. 전역 M3 오버라이드 (`Theme.kt`)
- `shapes.extraSmall = RoundedCornerShape(rCell)` — OutlinedTextField 4dp 모서리 일괄 해결
- AlertDialog `containerColor` 기본이 panel2(베이지)로 새고 있다 —
  `surfaceContainerHigh = tokens.panel`로 조정(다이얼로그 한 가족: panel 면)
  하거나 각 AlertDialog에 `containerColor = tokens.panel` 명시

### 0-4. 공용 부품 2개 신설 (`ui/common`)
- **PbpDialogButton**(label, kind: confirm/cancel/danger): 11sp bold ·
  confirm=`signatureInk` / cancel=`inkDim` / danger=`danger` · `heightIn(min=40)`.
  → 색·크기 미지정 TextButton **15곳**을 이것으로 치환 (아래 1-2 목록)
- **PbpBadge**: 10sp bold · pill · `defaultMinSize(18,18)` + 좌우 6 · 상하는 중앙
  정렬(패딩 없음) → UsingBadge·미확인 배지·EdgeBadge 통합

### 0-5. 데스크톱 Tokens 추가 (`desktop/ui/Theme.kt`)
- `ChatBarBg = 0xEDFFFFFF`(모바일과 동일값 — 현재 0xEB로 드리프트, ChatPane:820)
- `Scrim`(딤 0x611E232D — Overlays:121·168 복제 제거), `SidebarBg` 쌍(RoomListPane:128·222),
  `CardBg`(RoomListPane:199) — 카드 면은 가이드대로 `Panel`+`Line` 테두리로 갈지 스펙 결정
- 골드 알파 4종(0x99/0x80/0x66/0x8C × C89E34) → `GmRing` 하나로 수렴

---

## 1. P0 — 기준 위반: 라이트 모드 가독성 직격 (즉시)

### 1-1. 파괴적 동작이 danger가 아님 — 2곳 (데스크톱)
| 위치 | 현재 | 지시 |
|---|---|---|
| `Overlays.kt:369` 방 로그 "전부 삭제" 확인 | **YellowButton(옐로 면)** | danger 버튼으로 — `Danger` 면 + 흰 잉크 (모바일은 준수) |
| `ProfileOverlays.kt` "이 캐릭터 삭제" | GhostButton(inkDim) | 텍스트 색 `Danger` |

### 1-2. 옐로 원색 텍스트 (§2: 밝은 면 위 옐로 텍스트 금지 → signatureInk)
**직접 지정 7곳:**
| 위치 | 대상 |
|---|---|
| `ChatInput.kt:155` | 활성 프로필 이름 |
| `ChatInput.kt:246` | "잡담" 토글 라벨 |
| `ChatDialogs.kt:278` | 입력 문법 syntax 표기 |
| `MessageBlock.kt:429` | GM 인용 이름표 |
| `RoomSettingsScreen.kt:330` | 초대 코드 32sp 대형 표기 |
| `RoomSettingsScreen.kt:395` | 테마 셀 선택 라벨 |
| `FontSettingDialog.kt:53` | 글꼴 선택 ● 글리프 |

**M3 기본값 유출(색 미지정 → primary=옐로) 15곳** — 0-4의 PbpDialogButton으로 치환:
RoomListScreen:336·523·558 / RoomSettingsScreen:303·343 / ProfileDialogs:120 /
Ui:486·488·593 / FontSettingDialog:71 / ImageCrop:150·152 / ChatDialogs:310·312 /
ChatScreen:625. OutlinedButton 3곳(OwnerProfile:173·182, ProfileEdit:251)은
`contentColor = ink` 지정, OutlinedTextField 포커스 라벨(전 필드)은 0-3 + colors 지정.

### 1-3. 흰색/검정 알파·색 리터럴 — 화면 코드 금지 (§2)
| 위치 | 현재 | 지시 |
|---|---|---|
| `CaptureBar.kt:43` | `Color(0xFF1E1908)` | `tokens.onSignature` (+ :59·:84 알파 변형도) |
| `CaptureRenderer.kt:296·334` | `Color.White.copy(.9f)` | `tokens.panel.copy(.9f)` |
| `CaptureRenderer.kt:304` | 로고 그라데이션 리터럴 | `signature`+`signatureDeep` (0-1) |
| `MessageBlock.kt:202·205·247` | Black/White 알파 (시스템 필·다이스) | `scrim`/`onScrim` (0-1) |
| `MessageBlock.kt:257` | `Color(0xFFFFE9AE)` 다이스 텍스트 | `tokens.signature` (검정 스크림 위라 성립) |
| `MessageBlock.kt:491` | 잡담 점선 White .18 | `tokens.line` 계열 |
| `MessageBlock.kt:346` | EdgeBadge 글자 `Color.White` | `tokens.panel` (또는 PbpBadge로 통합) |
| `ChatScreen.kt:388` | Black 알파 + **isDark 분기** | `tokens.barScrim` (0-1) — 원칙 1 위반 해소 |
| `ChatScreen.kt:500` | 이전 대화 필 Black .35 | `tokens.scrim` |
| `ChatInput.kt:166` | 추가 점선 White .3 | `tokens.line` — 라이트에서 비가시 |
| `ChatDialogs.kt:180` | `nameColorForLight(0xFF8EC5E8)` 리터럴 | themeDefault 토큰 경유 |
| `ProfileEditScreen.kt:387–388` | 미리보기 그라데이션 제3의 값 + White 보더 | `backgroundPresets["preset_lighthouse"]` 재사용 + `tokens.line` |
| `RoomSettingsScreen.kt:375` | ThemeCell else `Color(0x08FFFFFF)` | `tokens.panel2` — 라이트에서 비가시 |
| `ImageCrop.kt:94` | `Color.Black` 레터박스 | 토큰 승격 또는 기능 예외 주석 |
| 데스크톱 `ChatPane.kt:752` | 아바타 링 White .85 | `Tokens.Line` (모바일 P1-2 수정의 재발) |
| 데스크톱 `ChatPane.kt:317–329·906–929·974–989` | 시스템 필·추천 칩·토글·힌트 리터럴 군 | 0-5 토큰 + `signature.copy()` 파생으로 (모바일과 동일 식) |
| 데스크톱 `Overlays.kt:121·168·223` | 딤 2곳 복제·Ghost 테두리 | `Scrim`·`Line` 토큰 |
| 데스크톱 `Overlays.kt:150` | syntax를 `SignatureRing`(링용)으로 | `SignatureInk` |

### 1-4. 캡처 시각 규격 (신규 기능 정렬)
| 위치 | 현재 | 지시 |
|---|---|---|
| `CapturePreviewScreen.kt:229·255` | 공유/저장 12sp | **11sp bold** (§5 텍스트 버튼) |
| `CaptureRenderer.kt:315` | 룸명 12sp | 13sp |
| `CaptureRenderer.kt:322·338·343` | 메타·낙관·페이지 9sp | **10sp** (9sp는 폐기) |
| `CaptureRenderer.kt:303` | 반경 7dp | 브랜드 타일 예외 등재 or rTail(4) |
| `CaptureRenderer.kt:228` | 캡처 시각색 = 기본 테마 고정 | `room.themeColor`를 render() 인자로 전달 |
| `CaptureRenderer.kt:266–273` | top-only 간격 주석 없음 | ChatScreen:471과 같은 예외 주석 1줄 |

### 1-5. 스케일 밖 sp 잔존 — v0.10.4 재검증 완료 목록 (grep 결과 전량)
**9sp → 10sp** (§3 "9sp 폐기, 배지도 10sp"):
`MessageBlock.kt:484` / `RoomSettingsScreen.kt:327` /
`CaptureRenderer.kt:367·384·388` (모바일) / 데스크톱 `ChatPane.kt:760·795` /
데스크톱 `export/CaptureRenderer.kt:249·265·269`. `Tokens.kt` 주석의 "(+배지 한정 9sp)"도 삭제.

**12sp → 11sp(라벨) 또는 13sp(본문)**:
`CapturePreviewScreen.kt:249·277` (공유/저장 — 11sp bold) /
`ChatDialogs.kt:227` (문법 요약 — 13sp) / `MessageBlock.kt:690` /
`ChatInput.kt:305` ("?" — 11sp) / `CaptureRenderer.kt:361` (룸명 — 13sp) /
데스크톱 `ChatPane.kt:540` / `Overlays.kt:96` / `export/CaptureRenderer.kt:243`.

**14sp**: 데스크톱 `Overlays.kt:85` (✕ — 13sp로). 데스크톱 `CaptureBar.kt:139` **16sp**도 스케일 밖.

---

## 2. P1 — 센터 정렬 누락 (원칙 2)

| 위치 | 현재 | 지시 |
|---|---|---|
| `RoomSettingsScreen.kt:125` | "방 설정 · 룸명" 좌측(뒤로 버튼 옆) | **titleInset 패턴 이식**: Box 오버레이 + `align(Center)` + `padding(horizontal = titleInset)` — RoomListScreen:172 구조 재사용 |
| `OwnerProfileScreen.kt:125` | "오너 프로필" 좌측 | 동일 |
| `ProfileEditScreen.kt:204` | "프로필 편집" 좌측 | 동일 |
| 데스크톱 `ChatPane.kt:147–175` | 채팅 상단 바 타이틀 좌측 | 센터 구조로 (가이드 §5 "버튼 개수와 무관 정중앙" — 데스크톱 예외는 280/720/24뿐) |
| 데스크톱 `Overlays.kt:179` | 오버레이 타이틀 좌측 (도움말만 센터) | OverlayScaffold 타이틀을 센터로 — 전 오버레이 일괄 |
| `ChatScreen.kt:615–627` 삭제 다이얼로그 | M3 기본 좌측 타이틀 | 타이틀 센터 + panel 면 (0-3) |
| `ChatDialogs.kt:147` | "메시지" 타이틀 좌측 (문법 도움말만 센터) | 센터 + 18sp |
| `CapturePreviewScreen.kt:157–175` | 빈 상태 안내 없음(공백) | 중앙 13sp `inkDim` 안내문 |
| 데스크톱 `Overlays.kt:371` | "파일\n선택" 2줄 좌측 | `TextAlign.Center` (모바일 :249 준수) |
| 데스크톱 `Overlays.kt:271` | 초대 코드 자간 없음 | `letterSpacing = 4.sp` (모바일과 동일) |
| `CaptureBar.kt:67`, `CapturePreviewScreen.kt:132` | 타이틀 인셋 gap6(32) < 버튼 56 | `titleInsetNarrow`(56, 0-2) — 부제가 버튼 밑으로 파고드는 것 방지 |

다이얼로그 타이틀 크기: `ChatDialogs.kt:91` 외 15sp 타이틀 → **18sp** (§5).
다이얼로그 내부 여백: `ChatDialogs.kt:89·195` gap4 → **gap5** (4장과 동일 건).

---

## 3. P2 — 불균형(비대칭) 여백 (원칙 3)

| 위치 | 현재 | 지시 |
|---|---|---|
| `RoomSettingsScreen.kt:411` | SectionTitle `start`만 gap4 | `horizontal = gap4` |
| `ProfileEditScreen.kt:511` | FieldLabel `bottom = gap2` | 패딩 제거 → 호출부 `Spacer(gap2)` |
| `MessageBlock.kt:530` | 잡담 배지 `end = 5.dp` | 부모 Row `spacedBy(gap1)` |
| 데스크톱 `ChatPane.kt:652` | 잡담 태그 `end=6, top=2` (top만) | `end`만 남기고 top 제거, 값은 모바일과 통일 |
| 데스크톱 `ChatPane.kt:1006` | 오류문 `top = 4.dp` | `Spacer(gap1)` |
| 데스크톱 `Overlays.kt:374` | 캡션 `top = 6.dp` | `Spacer(gap2)` |
| 데스크톱 `ProfileOverlays.kt:281` | stats `bottom = 6.dp` | `Arrangement.spacedBy(gap2)` |
| 데스크톱 `ChatPane.kt:229–236` | 말풍선 top-only에 예외 주석 없음 | 모바일(ChatScreen:471)과 같은 주석 추가 |
| `MessageBlock.kt:505·509` | 인용 따옴표 9/5/+6 | 주석은 있음 — 0-2 토큰 등재로 절차 완결 (데스크톱 `ChatPane.kt:609` 주석 "7·9dp 대칭"은 코드와 불일치 — 모바일 주석으로 교체) |

## 4. P3 — 과도한 여백 (기준 2)

| 위치 | 산술 | 지시 |
|---|---|---|
| `RoomSettingsScreen.kt:402–416` | Spacer 24 + top 8 = 제목 위 **32dp** (규정 24), 최근색 하단 8 합산 시 **40dp** | SectionTitle 내부 Spacer를 gap4(16)로 → 순 24dp |
| `ChatDialogs.kt:272` | 도움말 항목 vertical 8×2 = 간격 **16dp** (규정 8) | 항목 패딩 제거 → 부모 `spacedBy(gap2)` |
| `ChatDialogs.kt:145·249` | 다이얼로그 내부 gap4(16) | **gap5(24)** (§5) |
| 데스크톱 `Overlays.kt:129·176` | 오버레이 내부 22dp (오프그리드) | **gap5(24)** |
| `OwnerProfileScreen.kt` 사진 12 / 밴드 16 / 필드 24 혼재, `ProfileEditScreen.kt` 12/24 혼재 | 같은 위계 3값 | 섹션 간 **gap5(24)** 단일화 (사진 섹션 내부만 gap3 유지) |
| `OwnerProfileScreen.kt:186`, `ProfileEditScreen.kt:254` | 버튼 행↔캡션 간격 0 | `Spacer(gap2)` |

## 5. P4 — 터치 타깃 40dp 미달 (§6) — 시각 크기 유지 + 히트만 확장

| 위치 | 실효 | 지시 |
|---|---|---|
| `ColorSwatchRow.kt` 스와치 전부 | 26dp | `minimumInteractiveComponentSize()` — 4개 화면 공유 부품, **파급 최대** |
| `OwnerProfileScreen.kt:120`·`ProfileEditScreen.kt:194` 저장 캡슐 | ≈31dp | `heightIn(min = touchTarget)` |
| `ProfileEditScreen.kt:325` 값 삭제 ✕ | 24dp | 40dp 히트박스 |
| `ChatInput.kt:283` 도움말 ? | 24dp | 40dp 히트박스 + 12sp→11sp |
| `ChatDialogs.kt:259` ✕ · `:184` 취소 | 28/34dp | 40 히트 + 취소 11sp bold |
| `CapturePreviewScreen.kt:185–269` 토글·공유·저장 | 28~33dp | `heightIn(min=40)` 3곳 |
| `ChatScreen.kt:496` 이전 대화 필 | ≈31dp | `heightIn(min=40)` |
| 데스크톱 `Overlays.kt:212·225` Yellow/GhostButton | ≈33dp | 공용 버튼에 `heightIn(min=40)` + 패딩 h=gap4 (14/9 오프그리드 해소) |
| 데스크톱 `ChatPane.kt:904·924·981` 추천 칩·토글·? | 25~31/20dp | 모바일과 같은 규격(gap3/gap2 + min 40) |
| 데스크톱 `RoomListPane.kt:145` Aa 32dp · `Overlays.kt:138` ✕ 28dp | — | 40 히트박스 |

## 6. P5 — 스케일 밖 수치 정리 (등재 또는 스냅)

- **모바일**: ChatInput 토글 22/12/8/2·Spacer 5(→gap1) / ChatDialogs 아이콘 타일 34 /
  UsingBadge r5·마진 6·v1 / 미확인 배지 v1·Spacer 4(→gap1) / 로고 r7·글리프 13 /
  HexColorDialog r10·r9·r6·Spacer 10·150/18/16/40×28 / ImageCrop 260 /
  ProfileDialogs 420 / RoomSettings 76·72+2·r8 / letterSpacing 1/1.5/3 →
  **라벨 자간 1종으로 통일**, 반경은 rCell/rTail로 스냅, 나머지는 Tokens.kt 등재
  + 가이드 예외 목록 갱신. 테두리 1.5 vs 1도 한 값으로.
- **데스크톱**: Spacer 6/7/10/14/18 산재(Overlays·ProfileOverlays — §4 그리드로 스냅:
  7→8, 14→16, 6→8, 10→12, 18→16), OverlayField v11→gap3, 배경 셀 r10→rCell,
  행 패딩 8/10→gap3(모바일 ManagerRow와 동일), 저장 필드 15sp/13sp 혼재→13sp,
  도움말 ✕ 14sp→13sp·요약 12sp→13sp.

## 7. P6 — 플랫폼 드리프트 (모바일 ↔ 데스크톱)

| 항목 | 모바일 | 데스크톱 | 통일안 |
|---|---|---|---|
| GM 조각 간격 / 다중 말풍선 | gap2 / gap2 | 10 / 4 (`ChatPane:379·408`) | gap2 |
| 메시지 목록 상하 패딩 | gap3 | 16 (`ChatPane:217`) | gap3 |
| 방 목록 상하 패딩 | gap3 | gap2 (`RoomListPane:190`) | gap3 |
| 입력 바 면 | 0xED… | 0xEB… (`ChatPane:820`) | `ChatBarBg` 토큰 0xED |
| (수정됨) | 10sp | 9sp (`ChatPane:686`) | 10sp |
| 타임스탬프 라이트 보정 | nameColorForLight | 원색 (`ChatPane:688`) | 보정 적용 |
| 스와치/간격 | 26/4 | 32/8 (`ProfileOverlays:354`) | 한 값 결정(26/4 권장) |
| 색 피커 규격 | 150/18/10 | 140/16/8 (`ProfileOverlays:412–487`) | 8-그리드 쪽(140/16/8)으로 모바일 정렬 권장 |
| 프로필 편집 미리보기 아바타 | — | 44 vs 48 혼재 (`ProfileOverlays:181·660`) | 48 |
| 관리 행 패딩 | gap3 | 8/10 | gap3 |
| 플레이스홀더 | 13sp | 11sp (`ChatPane:966`) | 13sp |
| 스트립 활성 링/이름 | signature/signature(→Ink로 수정) | SignatureRing/SignatureInk | 링 signature · 텍스트 signatureInk 양쪽 통일 |
| 테마 점 돌출 | offset(3,3) | 없음 (`RoomListPane:219`) | offset 추가 |
| 타이틀 인셋 | 토큰 | 88 리터럴(산술도 86≠88, `RoomListPane:156`) | 토큰 등재 + 파생식 주석 |
| d10 글리프 | 13×13 | 13×14 | 13×13 |
| 상단 바 좌우 패딩 | RoomList만 gap4, 나머지 gap2 | — | **gap2로 통일** (RoomListScreen:159 수정) |

## 8. 기타 관찰

- `Ui.kt:536` OwnerAvatar 이니셜이 항상 진한 잉크 — 어두운 오너 컬러에서 비가시,
  배경 밝기 기반 스왑 필요 (§6).
- `ChatInput.kt:257` TextField `textStyle` 미지정 → 입력 중 16sp(플레이스홀더 13sp와
  불일치, 타이핑 순간 크기 점프). `textStyle = 13.sp` 지정. 데스크톱 저장 필드도 동일 계열.
- `ChatDialogs.kt:303` EditMessageDialog: 필드 r4(→0-3로 해결)·버튼 색/크기
  미지정(→PbpDialogButton)·containerColor panel2(→panel).
- 다이스 필(`MessageBlock.kt:246`)이 표시용 필 규격(pill+v gap1) 밖 — 사각 카드형이
  의도면 가이드 §5에 예외 명기, 아니면 규격으로.
- 데스크톱 그림자(shadow 2/3dp)는 모바일에 없음 — 플랫폼 차이 유지 여부 스펙 결정.
- ColorSwatchRow 점선 플레이스홀더 코드 2벌 복제 — 값 분기 위험(보고만).

---

## 9. v0.8~v0.10 신규 파일 — 같은 유형 위반 (v0.10.4 스캔)

감사 시점 이후 추가된 파일 3종. 정밀 감사는 안 거쳤으나 grep 스캔으로 확인된
확정 위반은 다음과 같다 — 0장 토큰·부품이 생기면 같은 방식으로 치환할 것.

### 데스크톱 `JudgeOverlays.kt` (판정 요청 오버레이)
| 위치 | 현재 | 지시 |
|---|---|---|
| :70 | 딤 `Color(0x611E232D)` 리터럴 (Overlays와 3중 복제) | `Tokens.Scrim` (0-5) |
| :78 | 오버레이 내부 `padding(22.dp)` | `gap5(24)` — Overlays와 동일 건 |
| :176 | `Color(0x1414191F)` — **Tokens.Line과 동일값** | `Tokens.Line` |
| :177 | 버튼 `padding(h=14, v=9)` — 실효 높이 ≈33dp | 공용 버튼 규격(min 40 + h=gap4) |
| :182 | `Color(0x5714191F)` 리터럴 | Ink-알파 토큰 |
| :137 | `padding(bottom = gap2)` 한쪽 패딩 | `Spacer(gap2)` |
| :166 | 10sp 룰 라벨 | 필드 라벨 관례(10sp) 확인 후 유지 or 11sp — E-4와 함께 결정 |

### 데스크톱 `CaptureBar.kt` / `export/CaptureRenderer.kt`
- `CaptureBar.kt:139` **16sp** — 스케일 밖. 15sp(title)로.
- `export/CaptureRenderer.kt:243`(12sp)·`:249·265·269`(9sp) — 모바일 CaptureRenderer와
  동일 위반 세트(1-4·1-5 목록에 포함). 모바일 수정 시 짝으로 반영.
- 모바일 `CaptureRenderer.kt`의 리터럴·반경 7·테마색 고정(1-3·1-4)은 데스크톱판에도
  동일 존재 여부 확인 후 짝 수정.

---

## 시공 순서 (권장)

1. **0장 선행 작업** — 토큰·전역 오버라이드·공용 부품 (이후 작업의 전제)
2. **P0 1-1~1-2** — danger 2곳 + 옐로 텍스트 22곳 (라이트 가독성 직격, 기계적)
3. **P0 1-3~1-5** — 리터럴 소탕·캡처 정렬·9sp 폐기
4. **P1 센터 정렬** — 상단 바 3화면+데스크톱, 다이얼로그 타이틀
5. **P4 터치 타깃** — ColorSwatchRow부터 (공유 부품)
6. **P2·P3 여백** — 비대칭·과다 정리
7. **P5·P6** — 수치 등재·드리프트 통일 (양 플랫폼 짝지어 한 커밋씩)

검수 기준: 완료 후 `Color(0x`·`Color.White`·`Color.Black` grep이 토큰 파일 밖에서
0건, 9sp·12sp·14sp(명시) grep 0건, 상단 바 타이틀은 전 화면 센터.
