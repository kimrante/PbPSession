# 보안 이행 검증 + 2차 리뷰 지시서 (2026-08-14, v0.20.0)

직전 보안 지시서(`docs/reviews/security-review-2026-08-12.md`, SV1~SV12)의 반영 커밋
(`d377aff` 클라이언트 / `e6f19d0` 서버)이 **실제 코드로 올바르게 이행됐는지 전수 검증**하고,
그 과정에서 발견한 신규·잔여 이슈를 함께 정리한다. 서버 규칙·양 클라이언트 배선·신규 코드
(Identifiers.kt·AppPaths 권한·판정 후보 얼굴 기능)를 세 영역으로 나눠 교차 확인했다.
**다른 세션이 이 문서만 보고 조치할 수 있도록** 항목마다 위치·근거·수정 방향을 명시한다.
기준: v0.20.0(`e6f19d0`). 라인 번호는 이 커밋 기준.

## 총평

- **이행 품질 우수.** 반영을 표방한 SV1·SV4·SV5·SV6·SV7·SV8·SV9·SV10·SV11·SV12 **전부
  정확·완전하게 랜딩**됐고, 규칙과 양 클라이언트 동작이 정합한다(1회용 코드 소비, 멤버-먼저-코드
  생성 순서, avatarId 화이트리스트를 모든 싱크에 대칭 적용 등 배선까지 코드로 확인). 도입된 새
  보안 코드(Identifiers·AppPaths 권한)에 실질 결함 없음.
- **깨진 정당한 흐름 없음.** 초대 코드 1회용화가 host를 막지 않도록 재공유·코드표시 자가치유가
  양 클라이언트에 구현돼 있다. 유일한 마찰은 "다기기·재참여마다 host가 새 코드 발급"(설계상 수용).
- **신규 조치 1건** — SV13(이미지 디컴프레션 폭탄 → 반복 OOM, 판정 얼굴 기능이 디코드 지점을
  하나 더 늘림). **상호작용 1건** — SV14(SV6 강화가 침입자 축출 경로까지 없앰).
- **의도적으로 미해결(재확인)** — SV2의 멤버 수 서버 강제와 SV3의 App Check. 문서에 정직히
  "미적용"으로 명시돼 있으며, 아래에서 현재 시점의 실제 잔여 노출을 다시 평가한다.

권장 조치 순서: **SV13 → (SV2·SV14 함께: 멤버 슬롯 서버 강제) → SV3(App Check) → 경미 3건**.

---

## 1부 — 이행 검증 결과 (SV1~SV12)

| 항목 | 상태 | 근거 (현재 코드) |
|---|---|---|
| **SV1** avatarId 경로 탈출 | ✅ 정확·완전 | 공용 `Identifiers.isValidAvatarId`(md5 32 hex)를 **모든 싱크**에 적용 — 데스크톱 `DesktopImages.kt:128` `avatarCacheFile`(검증 + `canonicalPath.startsWith` 이중 방어)가 read·rename·delete를 관문화, `Firestore.kt:828` `fetchAvatar`는 `encodePath`, 모바일 `AvatarStore.kt:76` `resolve`가 파일·문서 경로 전에 검증. 판정 얼굴의 peer avatarId도 같은 관문 통과. 테스트 `IdentifiersTest.kt:40`. |
| **SV4** 초대 코드 CSPRNG+길이 | ✅ 정확·완전 | `Identifiers.kt:13` `SecureRandom`(lazy), `newInviteCode()` = `nextInt(32)`(모듈로 바이어스 없음), `Protocol.kt:187` `INVITE_LENGTH=8`. 양 클라이언트 위임(`SyncManager.kt:993`, `Main.kt:1317`), 인라인 `Random.Default` 제거. |
| **SV5** authorUid 재지정 차단 | ✅ | `firestore.rules:43-45` `request.resource.data.authorUid == resource.data.authorUid` 추가. 정당한 편집은 authorUid 그대로 재전송해 통과. |
| **SV6** 멤버 self-only 삭제 | ✅ (단 SV14 부작용) | `firestore.rules:69` `signedIn() && uid==memberId`. 클라 삭제도 본인 uid만(`SyncManager.kt:987`, `Firestore.kt:816`). ⚠️ 축출 경로 소멸 → SV14. |
| **SV7** config.json 권한 제한 | ✅ 정확·완전 | `AppPaths.restrictToOwner`(POSIX `rw-------`/Win ACL 소유자 단독), `Config.load`가 시작 시 root 잠금 + 매 기동 tmp→restrict→atomic move라 구버전 파일도 첫 기동에 잠김. TOCTOU 창은 root `rwx------`로 차단. |
| **SV8** 방 update 필드 화이트리스트 | ✅ | `firestore.rules:29-31` `hasOnly(['themeColor','logsClearedAt','inviteCode','name','rule'])`. 클라 실제 update 필드 전수 확인 — 전부 화이트리스트 내. `inviteCode` 포함은 `refreshInviteCode`에 필요하며 순수 축소(신규 권한 아님). |
| **SV9** 아바타 크기·필드 캡 | ✅ | `firestore.rules:55-58` `keys().hasOnly(['data']) && data is string && data.size()<700000`. 업로드 모두 `{data}`(`AvatarStore.kt:65`, `Firestore.kt:823`). |
| **SV10** senderName 절단 | ✅ | `functions/index.js:39-40` `slice(0,40) || "상대"`. |
| **SV11** allowBackup | ✅ | `AndroidManifest.xml:17` `android:allowBackup="false"`. |
| **SV12** 쿼리 문자열 보간 | ✅ | `Firestore.kt:353` `legacyFindRoomByCode`가 `isValidInviteCode` 선차단, 정식 경로는 `encodePath`. 테스트 `IdentifiersTest.kt:31`. |
| **SV2** 초대 코드 1회용 소비 | ✅ 배선됨 / 상한은 미적용 | 규칙 `firestore.rules:84` delete + 양 클라 소비 배선(`SyncManager.kt:583`, `Main.kt:1017`, 둘 다 멤버 확보 후 삭제). **단 멤버 수 서버 강제는 미적용** — 아래 잔여 노출. |
| **SV3** inviteCodes create=isMember | ✅ / App Check 미적용 | 규칙 `firestore.rules:79`, 양 클라 멤버-먼저-코드 순서 확인. App Check는 데스크톱 차단 우려로 보류(문서 명시). |

**SV2·SV3의 배포 상태**: `e6f19d0`이 `firestore.rules`·`functions/index.js`를 수정했고 커밋
메시지에 "에뮬레이터 34건 확인"이 있으나, **실서버 배포 여부는 이 리뷰 범위 밖**이다.
`npx firebase-tools deploy --only firestore:rules,functions` 실행 확인 필요(docs/deploy.md).

---

## 2부 — 신규·잔여 조치 항목

### SV13. [MEDIUM] peer 이미지 디컴프레션 폭탄 → 반복 OOM 크래시 (지속형 DoS)

- **위치(수신 측 디코드 싱크, 픽셀 상한 없음)**:
  - 모바일 저장: `app/.../sync/AvatarStore.kt:87` — 수신 base64를 **다운스케일 없이** 파일로
    그대로 write(다운스케일은 업로드 경로 `downscaleForUpload:101`에만 존재).
  - 모바일 알림 아이콘: `app/.../notify/MessageNotifier.kt:113` — `BitmapFactory.decodeFile`을
    **inSampleSize 없이** 호출, `circularAvatar`에 try/catch도 없음.
  - 데스크톱 판정 얼굴(신규): `desktop/.../Main.kt:676` — 판정 목록을 열 때
    `peers.mapNotNull { it.avatarId }.distinct()` 전부를 `Image.makeFromEncoded(...)`로 **한꺼번에**
    디코드.
  - 데스크톱 메시지 아바타: `desktop/.../ChatPane.kt:889` — 같은 호출.
- **공격 시나리오**: 방 멤버(정상 상대이거나 SV2 잔여로 잠입한 제3자)가 `avatars/{hash}` 문서에
  **작은 용량·초대형 해상도** JPEG(예: 20000×20000, 압축 후 수십 KB)를 올린다. 규칙은
  `data.size() < 700000`으로 **바이트만** 막을 뿐 해상도는 검사 불가라 통과. 이 아바타를 가리키는
  캐릭터/메시지를 보내면 수신 측이 렌더 시 20000×20000×4 ≈ **1.6GB** 비트맵을 할당하다 OOM.
  폭탄은 **로컬 캐시**되므로 데스크톱은 판정 목록을 열 때마다·모바일은 그 상대 알림이 뜰 때마다
  **반복 크래시**(피해자가 캐시를 직접 지우기 전까지 지속). 판정 얼굴은 목록을 열 때 여러 얼굴을
  한꺼번에 디코드해 폭탄 1개로도 확실히 걸린다.
- **왜 중요**: 원격 유발·정상 사용만으로 트리거되는 지속형 가용성 공격. 규칙·Functions로 못 막는
  값이라 **클라이언트 수신 측 정규화가 유일한 실질 방어**.
- **수정(정석 = 수신 시 재인코딩)**:
  - 모바일 `AvatarStore.resolve`: 저장 전에 `downscaleForUpload`와 같은 축소를 태워 캐시가 항상
    ≤256px가 되게. 그러면 하위 모든 디코더(`circularAvatar` 포함)가 자동으로 안전.
  - 데스크톱 `fetchAvatarCached`: 캐시 저장 전 `encodeAvatarBytes`(이미 존재, 긴 변 256px)로 통과.
  - 최소책: 디코드 직전 헤더로 크기 확인(`inJustDecodeBounds`/`image.width*height` 캡, 예 4096²)
    후 초과 시 스킵·폴백. `circularAvatar`는 최소한 try/catch로 감쌀 것.

### SV14. [LOW→MEDIUM] SV6 강화가 침입자 축출 경로를 제거 — SV2 미적용과 결합 시 침입 영구화

- **위치**: `firestore.rules:69`. 이전 규칙(`isMember(roomId) || uid==memberId`)에서는 정당한
  멤버가 **침입자의 멤버 문서를 지워 축출**할 수 있었는데, SV6가 self-only로 좁히며 그리핑
  (A가 B 축출)과 함께 **방어(정당 멤버가 무단 가입자 축출)도 사라졌다**.
- **시나리오**: SV2 멤버 상한 미적용 상태에서 roomId를 아는 제3자(또는 탈퇴 후 `members/{자기uid}`를
  재작성한 전 멤버)가 들어오면 **이제 아무도 쫓아낼 수 없다** — 유일한 대응이 방 폐기·재생성.
- **수정**: 근본은 SV2(b) — 멤버 슬롯 서버 강제(2 uid 초과 create 거부). SV13과 달리 규칙으로
  해결 가능. 그때까지는 "축출 불가"를 문서에 감내 위험으로 명시.

### SV2-잔여. [재평가] roomId를 아는 자의 무증명 가입은 여전히 열려 있음

- 1회용화로 **코드 유출 창은 크게 짧아졌다**(가입 완료 전에만 코드→roomId 해석 가능).
- 그러나 가입 게이트는 코드가 아니라 **roomId를 아느냐**이고(`firestore.rules:66` members create는
  코드 소지를 검증하지 않음), roomId는 추측 불가 auto-ID지만 **한번 멤버였던 사람은 영구 보유**한다.
  즉 **탈퇴자가 `members/{자기uid}`를 재작성해 전체 읽기/쓰기/전량 삭제를 되찾을 수 있다** —
  "탈퇴는 진짜 탈퇴가 아니다." SV14와 결합하면 재작성한 전 멤버를 축출할 수도 없다.
- **근본 해법은 SV2(b) 하나**로 SV14까지 함께 닫힌다 — 방 문서에 `ownerUid`/`partnerUid` 두 슬롯을
  두고 세 번째 uid의 members create를 규칙에서 거부. 재참여는 host 승인(슬롯 재지정)으로.

### SV3-잔여. [재확인] App Check 부재 — 익명 가입 과금 DoS는 그대로

- inviteCodes 무제한 생성은 SV3 규칙(create=isMember)으로 막혔으나, `rooms create: if signedIn()`
  과 익명 `accounts:signUp` 무제한은 그대로라 방·아바타 대량 생성 과금 경로는 남아 있다.
- App Check 도입 시 데스크톱(비공식 REST 클라이언트)이 막히는 문제가 있어 보류 중 — 데스크톱용
  디바이스 증명 대안(또는 데스크톱만 예외 토큰)을 별도 설계 과제로 남긴다. 단기 보강은 GCP 예산
  알림.

---

## 경미 (기록·선택)

- **[INFO] 데스크톱 `createInviteCode` 코드 충돌 재시도 없음** — `Firestore.kt:525`는 PATCH
  (create-or-update)라 천문학적 확률의 코드 충돌 시 기존 문서를 겨냥하면 `update: if false`(규칙)에
  걸려 조용히 실패. 모바일은 `CODE_ATTEMPTS` 재시도 루프가 있는데 데스크톱엔 없음. 32⁸ 공간이라
  실무상 무해 — 대칭성 차원의 기록.
- **[INFO] `fetchAvatar`의 `remoteRoomId` 미인코딩** — `Firestore.kt:828`에서 avatarId만
  `encodePath`하고 roomId는 보간. roomId는 공격자 입력이 아니라 내가 참여한 방의 로컬 저장 id라
  실질 위험 없음. 일관성 차원.
- **[INFO] Windows 기존 cache 하위 파일 소급 미적용** — 신버전 첫 기동 전 생성된 캐시 파일은
  상속 ACE를 소급받지 못하나 `%USERPROFILE%` 상속 ACL이라 world-readable은 아님. 신규 생성분은
  root 잠금 상속으로 커버.

## 검증

- SV13: 초대형 해상도(예 16384²) JPEG를 아바타로 올려 수신 측(모바일 알림·데스크톱 판정 목록)이
  크래시하지 않고 축소본으로 렌더/폴백하는지 확인. `AvatarStore` 캐시가 ≤256px인지 파일로 확인.
- SV2(b)/SV14: 규칙 수정 후 **에뮬레이터 규칙 테스트** — 3번째 uid의 members create 거부, 정당한
  2인 가입·재공유·로그 초기화가 그대로 되는지 회귀 확인. 배포는 docs/deploy.md.
- 클라이언트 수정 후: `gradlew assembleDebug testDebugUnitTest :shared:test :desktop:test`.
- **실서버 규칙·Functions 배포 상태 확인**(SV2·SV3·SV5·SV6·SV8·SV9·SV10은 배포돼야 유효).
