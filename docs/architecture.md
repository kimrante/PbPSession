# PbP 프로젝트 구조 — 디자인 스펙 ↔ 코드 매핑

## 모듈

- `:app` — Android 앱 (Jetpack Compose + Room + Firestore SDK)
- `:desktop` — **PC 버전** (Compose Multiplatform Desktop). 같은 디자인 토큰·목업 형태의
  2판 레이아웃(방 목록+채팅). 공식 SDK가 없어 Firestore REST + 폴링(2.5초)으로
  같은 프로젝트에 동기화 — 모바일과 실시간 대화 가능. 실행: `gradlew :desktop:run`,
  배포판: `gradlew :desktop:packageDistributionForCurrentOS`.
  로컬 설정: `~/.pbp-desktop/config.json` (기기 ID·프로필·참여한 방).
  순수 로직은 **`:shared` 모듈(plain kotlin-jvm)로 통합 완료** — DiceBot/Rules/PbpMarkup/
  GmSpeech/CharacterCodec/ProfileStats + Protocol(와이어 상수)/Palette(색)/LogExport(HTML 렌더).
  양 모듈이 같은 코드를 쓰고 테스트도 여기 모여 있다.

기준 문서: [PbP-design-spec.md](PbP-design-spec.md) · 목업 [다크](mockups/trpg-app-mockup.html) / [화이트](mockups/trpg-app-mockup-light.html)

## 패키지 구조

```
com.pbp.app
├── PbpApp.kt              앱 배선(DB·동기화·알림), 포그라운드 감지
├── MainActivity.kt        내비게이션(rooms / chat / profile / settings), 알림 권한
├── data/                  Room DB — 엔티티·DAO·마이그레이션·리포지토리
├── dice/                  다이스봇 파서·굴림 (supportedSides = [6,10,20,100] + d66)
├── text/                  텍스트 엔진 (순수 Kotlin, JVM 테스트)
│   ├── PbpMarkup.kt       **굵게**·*기울임*·~~취소선~~·(루비)[문자] 루비 파서
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

## 데이터 모델 (Room DB v9)

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

## 서비스 최적화 반영 (2026-07-29 3차)

- **릴리스 R8+리소스 축소** — APK 19.6MB(디버그) → **4.3MB**(릴리스). 볼드 명조는 번들 제외(합성 볼드), 레귤러는 힌팅 제거판
- **이미지 축소 임포트** — 프로필 512px·배경 1600px JPEG로 저장(`data/Images.kt`) → 풀사이즈 디코딩 제거
- **메시지 점진 로딩** — 최근 200개 + '이전 대화 불러오기'. 내보내기는 별도로 전체 조회
- **전송 아웃박스** — 시작 시 `remoteId IS NULL` 미전송분 자동 재전송 (오프라인 유실 방지)
- **백필 WriteBatch**(450건 단위), **수신 dedup 일괄 조회**, **아바타 바이트 캐시**
- **Firestore 메모리 캐시** — Room이 소스이므로 디스크 이중 저장 제거
- **FCM 토큰 변경 시에만 업로드**
- **입력 상태 하향** — 타이핑이 화면 전체를 리컴포즈하지 않음. `MarkupText`도 remember 캐시
- **markRead** — 입장 시 + 상대 메시지 수신 시에만 (내 발신마다 쓰기 제거)
- **데스크톱 적응형 폴링** — 활성 2.5초 / 유휴 20초 / 창 미포커스 30초, 재수신 윈도는 주기×2 동적. 방별 파일 캐시로 재시작에도 증분 재개

## 남은 과제

1. **Cloud Functions 배포** — 위 참조 (Blaze 요금제 + 로그인 필요, 코드는 준비됨)
2. **화이트 모드 미세 대비 검수** — 토큰은 스펙대로, 실기기에서 화이트 목업과 대조 확인 권장
3. **커스텀 컬러 피커** — HEX 입력만 제공. HSV 휠은 추후
4. **Firestore 보안 규칙** — firestore.rules 준비됨, 배포 절차는 docs/firebase-security.md

## 알려진 한계 (설계 결정 — 3차 리뷰 L5·S7)

- **오프라인 편집/삭제는 상대에게 반영되지 않을 수 있다**: 신규 메시지는 아웃박스
  (`uploaded=0`)로 재전송되지만, 편집·삭제의 원격 전파는 실패 시 재시도하지 않는다
  (로그만 남김). 로컬에는 반영되므로 기기 간 표시가 다를 수 있다. pending-op 재시도
  큐는 복잡도 대비 이득이 작아 보류 — 필요해지면 `pendingOp` 컬럼 + start() 재시도로.
- **데스크톱은 30초 윈도보다 오래된 메시지의 편집·삭제를 실시간 반영하지 못한다**:
  편집은 폴링 윈도(30초) 안에서만 업서트되고, 삭제는 방 재입장 시 반영된다.
  주기적 전체 재조회는 read 과금이 메시지 수에 비례해 보류.

## 검증

- 단위 테스트: **63개** — :shared 6스위트(DiceBot·Rules·PbpMarkup·GmSpeech·CharacterCodec·ProfileStats) + :app 3스위트(LogExporter·SyncMapping·Reconcile)
- `gradlew assembleDebug testDebugUnitTest`

## Firestore 스키마 — 3곳 동시 수정 필요 (리뷰 A2)

와이어 스키마는 `shared/.../Protocol.kt`가 단일 출처지만, **JS와 보안 규칙은 그 상수를
소비할 수 없다**. 필드·컬렉션명을 바꿀 때는 반드시 세 곳을 함께 고칠 것:

| 위치 | 역할 |
|---|---|
| `shared/src/main/kotlin/com/pbp/shared/Protocol.kt` | 양 클라이언트가 참조하는 상수 |
| `functions/index.js` | 푸시 트리거가 읽는 필드(`type`, `createdAt`, `authorUid`, `senderName`, `members/*.fcmToken`) |
| `firestore.rules` | 접근 제어가 읽는 필드(`authorUid`, `members/{uid}`) |

주요 문서 구조:

```
rooms/{roomId}                    name, icon(폐지·빈값), inviteCode, themeColor, backgroundKey, rule
rooms/{roomId}/messages/{msgId}   type, body, diceExpr, diceOutcome, sender*, isOoc,
                                  createdAt, editedAt, authorUid, avatarId
rooms/{roomId}/members/{uid}      joinedAt, fcmToken, updatedAt, platform, lastReadAt
rooms/{roomId}/avatars/{md5}      data (base64, 긴 변 256px)
inviteCodes/{code}                roomId
```

## 파일 구조 (2026-07-30 클린 리뷰 반영)

- `:shared` — 순수 로직·프로토콜·팔레트·HTML 내보내기 (테스트 44개 포함)
- `:app` — `ui/chat`은 ChatScreen / MessageBlock / ChatInput / ChatDialogs로 분할,
  프로필 다이얼로그는 `ui/profile`, 아바타 동기화는 `sync/AvatarStore`
- `:desktop` — Main(앱 상태·라우터) / ChatPane / ProfileOverlays / Overlays /
  RoomListPane / DesktopImages / RoomSync, 치수·타이밍 토큰은 `ui/Dimens.kt`
- `docs/reviews/` 리뷰 보고서 · `docs/mockups/` 목업·아이콘 시안
