# PbP 프로젝트 구조 — 디자인 스펙 ↔ 코드 매핑

## 모듈

- `:app` — Android 앱 (Jetpack Compose + Room + Firestore SDK)
- `:desktop` — **PC 버전** (Compose Multiplatform Desktop). 같은 디자인 토큰·목업 형태의
  2판 레이아웃(방 목록+채팅). 공식 SDK가 없어 Firestore REST + 폴링(2.5초)으로
  같은 프로젝트에 동기화 — 모바일과 실시간 대화 가능. 실행: `gradlew :desktop:run`,
  배포판: `gradlew :desktop:packageDistributionForCurrentOS`.
  로컬 설정: `~/.pbp-desktop/config.json` (기기 ID·프로필·참여한 방).
  순수 로직(DiceBot/PbpMarkup/GmSpeech)은 복제본 — KMP `:shared` 추출이 장기 과제.

기준 문서: [PbP-design-spec.md](PbP-design-spec.md) · 목업 [다크](trpg-app-mockup.html) / [화이트](trpg-app-mockup-light.html)

## 패키지 구조

```
com.pbp.app
├── PbpApp.kt              앱 배선(DB·동기화·알림), 포그라운드 감지
├── MainActivity.kt        내비게이션(rooms / chat / profile / settings), 알림 권한
├── data/                  Room DB — 엔티티·DAO·마이그레이션·리포지토리
├── dice/                  다이스봇 파서·굴림 (supportedSides = [6,10,100])
├── text/                  텍스트 엔진 (순수 Kotlin, JVM 테스트)
│   ├── PbpMarkup.kt       **굵게**·*기울임*·~~취소선~~·|等臺《등대》 루비 파서
│   └── GmSpeech.kt        GM 발화에서 " " 인용만 말풍선으로 분리
├── sync/                  Firestore 2인 동기화 (수정/삭제 전파 포함)
├── export/                HTML 로그 내보내기 (형태 보존, 종이 톤)
├── notify/                푸시 알림 — 본문 비노출, 원형 아바타
└── ui/
    ├── theme/             디자인 토큰(Tokens.kt)·서체(Type.kt)·테마(Theme.kt)
    ├── common/            Avatar(원형)·RoomBackdrop(배경+베일)·마크업 렌더러·점선 테두리
    ├── roomlist/          01 방 목록
    ├── chat/              02 채팅방 (핵심)
    ├── profile/           03 캐릭터 프로필 편집
    └── roomsettings/      04 방 설정 (마스터 전용)
```

## 스펙 장별 구현 위치

| 스펙 | 구현 |
|---|---|
| 2장 디자인 토큰 | `ui/theme/Tokens.kt` — `PbpDarkColors`/`PbpLightColors`, 시스템 다크모드 따라 스왑. 이름색 다크→라이트 치환은 `PbpPalette.nameColorForLight()` |
| 2장 서체 | 서술 = Gowun Batang 번들(`res/font/`), 대사·UI = 시스템 Noto Sans CJK KR |
| 3장-1 방 목록 | `ui/roomlist/` — 배경 썸네일+테마 점, 미확인 옐로 배지(`observeUnreadCounts`), 옐로 FAB |
| 3장-2 채팅방 | `ui/chat/` — 아래 '채팅방 규칙' 참조 |
| 3장-3 프로필 편집 | `ui/profile/` — 이름 색/말풍선 색 프리셋 5+커스텀, 실시간 미리보기 |
| 3장-4 방 설정 | `ui/roomsettings/` — 테마 8종(커스텀 포함)·배경 프리셋 5+갤러리, 마스터 권한 밴드(`ChatRoom.isMaster`) |
| 3장-5 / 6장 내보내기 | `export/LogExporter.kt` — 원형 인장·이름색(라이트 치환)·말풍선 색/좌우·GM 서술 문단·〔잡담〕·마크다운/루비 보존 |
| 3장-6 / 7장 알림 | `notify/MessageNotifier.kt` — "~님의 메시지가 도착했습니다." 고정, 본문 비노출. 앱 활성 시(포그라운드) 억제 |
| 3장-7 앱 아이콘 | `res/drawable/ic_launcher_foreground.xml` + `mipmap-anydpi-v26/` 어댑티브(사각/원형) d10 |
| 4장 채팅방 규칙 | 아래 참조 |
| 5장 방/권한 | 방 생성 = GM 프로필 자동 + `isMaster=true`; 참여자는 `isMaster=false` → 설정 읽기 전용. 테마 컬러는 전송 버튼·목록 점에 적용, 말풍선 색과 분리 |

## 채팅방 핵심 규칙 (스펙 4장) 구현

- **좌상단 룸명+테마 / 우상단 내보내기·설정**: `ChatScreen` 상단 바
- **배경+베일**: `ui/common/RoomBackdrop` — 프리셋 그라데이션 또는 갤러리 이미지 위에 다크/라이트 베일 자동
- **말풍선 = 프로필 컬러**: 메시지에 `senderNameColor`/`senderBubbleColor` 스냅샷 저장 → 과거 로그 색 보존
- **GM 서술**: `senderIsGm && !isOoc` 메시지를 `GmSpeech.split()`으로 서술 문단(명조+敍 낙관) / "???" 인용 말풍선으로 렌더링. 저장은 원문 그대로 1건 — 분리는 렌더 시점
- **잡담 토글**: 입력줄 스위치 → `Message.isOoc`. 회색 점선 말풍선(`dashedBorder`), 로그에선 〔잡담〕
- **수정/삭제**: 말풍선 곁 ✎/🗑 → 로컬 갱신 후 Firestore update/delete 전파, 수신 측 리스너가 MODIFIED/REMOVED 처리
- **프로필 교체 스트립**: 입력창 위 가로 아바타 열. 탭=교체(옐로 링), 길게=편집, ＋=새 캐릭터
- **마크다운·루비**: `text/PbpMarkup` → `ui/common/markupToAnnotated()` (루비는 본문 뒤 작은 글자 근사 — 진짜 위첨자 루비는 커스텀 레이아웃 필요, 추후 과제)

## 데이터 모델 (Room DB v3)

- `ChatRoom` + `themeColor`, `backgroundKey`(preset_* 또는 파일 경로), `isMaster`, `lastReadAt`
- `CharacterProfile` + `nameColor`, `bubbleColor`
- `Message` + `senderNameColor/senderBubbleColor`(스냅샷), `isOoc`, `editedAt`, `incoming`(수신 여부 → 미확인·알림·좌우 정렬 판정)
- 마이그레이션: `AppDatabase.MIGRATION_2_3` (ALTER TABLE, 데이터 보존)

## 이후 반영된 것 (2026-07-29)

- **루비 위첨자 렌더링** — `ui/common/Ui.kt`의 `MarkupText`가 InlineTextContent로 본문 위에 독음을 얹는다
- **프로필 이미지 동기화** — Storage 없이 `rooms/{id}/avatars/{hash}` 문서에 축소 JPEG(≤256px)를 base64로 내장.
  메시지의 `avatarId`가 해시를 가리키고 수신 측이 파일로 복원·캐시 (`SyncManager` 하단)
- **GM 익명 인장** — GM 인용("???") 말풍선은 GM의 실제 프로필 이미지 대신 어두운 敍 아바타로 표시 (서술 문단은 원래 아바타 없음)

## 추가 반영 (2026-07-29 2차)

- **GM 렌더링 조정** — 서술 문단은 낙관·아바타 없이 문단만, 인용("???")·일반 말풍선은 GM의 실제 프로필 이미지 표시
- **테마·배경 실시간 동기화** — 마스터가 변경하면 방 문서 update → 상대 기기의 방 문서 리스너가 즉시 로컬 반영 (`SyncManager.pushRoomSettings`/`attachRoomDoc`)
- **FCM 백그라운드 푸시** — 클라이언트 완비: firebase-messaging + `notify/FcmService`(본문 비노출 알림, 포그라운드 중복 억제) + 기기 토큰을 `rooms/{id}/members/{deviceId}`에 자동 등록.
  서버 발송은 `functions/index.js`(Firestore onCreate → 상대 토큰으로 데이터 푸시).
  **배포만 남음(사용자 작업)**: Firebase 콘솔에서 Blaze 요금제 활성화 후
  `npx firebase-tools login` → `npx firebase-tools deploy --only functions --project pbp-session-1195c`

## 남은 과제

1. **Cloud Functions 배포** — 위 참조 (Blaze 요금제 + 로그인 필요, 코드는 준비됨)
2. **화이트 모드 미세 대비 검수** — 토큰은 스펙대로, 실기기에서 화이트 목업과 대조 확인 권장
3. **커스텀 컬러 피커** — HEX 입력만 제공. HSV 휠은 추후
4. **Firestore 보안 규칙** — 테스트 모드 30일 만료 전 규칙 갱신 (design.md 스니펫)

## 검증

- 단위 테스트: DiceBot 8 · GmSpeech 4 · PbpMarkup 7 · LogExporter 9 · SyncMapping 4 = **32개**
- `gradlew assembleDebug testDebugUnitTest`
