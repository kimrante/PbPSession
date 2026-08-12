# 보안·암호화 리뷰 및 수정 지시서 (2026-08-12, v0.18.0)

**v0.18.0(`3aa22b2`)** 기준으로 **보안·암호화** 관점만 집중 점검한 결과. 서버 신뢰 경계
(firestore.rules·Functions·인증) / Android 저장·암호 / 데스크톱 자격증명 세 영역을 나눠 보고,
여러 영역이 독립적으로 같은 결론에 도달한 항목은 교차 확인했다. **다른 세션이 이 문서만 보고
수정할 수 있도록** 항목마다 위치·공격 시나리오·수정 방향을 명시한다. 라인 번호는 `3aa22b2` 기준.
항목 번호는 기존 리뷰(A~K)와 겹치지 않게 **SV**(security)를 쓴다.

## 위협 모델 전제

2인용 사적 앱이고, GitHub 공개 저장소 + 실제 Firebase 프로젝트(pbp-session-1195c)라는 전제다.
따라서 "상대는 신뢰한다"는 성립하지만 **① 초대 코드/방 ID를 손에 넣은 제3자, ② 익명 로그인만
하면 되는 임의의 인터넷 사용자, ③ 상대가 보낸 데이터(avatarId 등)를 처리하는 내 클라이언트**
세 경로는 위협으로 다룬다.

## 총평

기본기는 갖춰져 있다 — 익명 인증 필수, 방 목록 `list` 차단, `authorUid == auth.uid` create 강제,
catch-all deny, 시나리오 뷰어의 호스트 고정·https 강제, HTML 내보내기 이스케이프, 노출 컴포넌트
최소화(MainActivity만 exported), TLS 전 구간 강제(우회 없음), 신원 생성 대부분 SecureRandom.
**하지만 접근 제어가 "멤버냐 아니냐" 수준에 머물러 필드 단위 검증이 거의 없고, 방 콘텐츠 전체를
지키는 유일한 비밀인 초대 코드가 비암호학적 난수 + 무제한 열거에 노출**돼 있다. 실제 조치가
필요한 것은 아래 SV1~SV7이며, 그중 SV1(데스크톱 임의 파일 쓰기)과 SV2(무증명 방 가입)가 최우선이다.

권장 순서: **SV1 → SV2 → SV5 → SV3 → SV4 → SV6·SV7 → 나머지**.
SV5·SV6은 각각 규칙 한 줄이라 SV2와 한 배포에 묶는다.

> **배포 필요**: SV2·SV3·SV5·SV6·SV8·SV9는 `firestore.rules` 수정이라
> `npx firebase-tools deploy --only firestore:rules --project pbp-session-1195c`를 해야 실제
> 적용된다. SV3의 App Check는 Firebase 콘솔 설정 + 클라이언트 SDK 추가가 별도로 필요하다.
> 절차는 docs/firebase-security.md / docs/deploy.md.

---

## SV1. [HIGH] 데스크톱 avatarId 경로 탈출 → 원격 제어 임의 파일 쓰기·삭제

- **위치**: `desktop/.../DesktopImages.kt:132`(`File(dir, avatarId)`)·`:133`(readBytes)·
  `:142`(renameTo → 쓰기)·`:153`(delete), 출처 `desktop/.../data/Firestore.kt:593`
  (`parseMessage`의 `avatarId = doc.str("avatarId")` — 검증 없음), `:464`(listPeerCharacters).
  `fetchAvatar` URL(`avatars/$avatarId`, Firestore.kt:787·793)에도 인코딩 없이 삽입.
- **공격 시나리오**: 같은 방의 상대(또는 SV2로 무단 가입한 제3자)가 ① `uploadAvatar`로 임의
  바이트를 avatars 문서에 올리고 ② 메시지의 `avatarId`를
  `..\..\..\Users\<이름>\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup\evil.bat`
  같은 값으로 보낸다. 수신 PC가 그 메시지를 렌더할 때 `fetchAvatarCached`가 **공격자가 정한
  경로에 공격자가 정한 내용**을 쓴다(경로·내용 모두 원격 제어). 디코드 실패 시
  `dropBrokenAvatarCache`가 **임의 경로 파일을 삭제**한다.
- **왜 중요**: 시작 프로그램 폴더 투입·실행/설정 파일 덮어쓰기를 통한 지속성·코드 실행까지 도달
  가능(Windows에서 CRITICAL 근접). 방어가 전무하다는 점이 핵심.
- **수정**: 파일·URL 연산 전에 `avatarId`를 화이트리스트로 강제. 송신 측은 항상 md5 hex
  (`encodedAvatarFor`→`md5Hex`)이므로
  `require(avatarId.matches(Regex("^[a-f0-9]{32}$")))` — 불합격이면 캐시 연산 건너뛰고 이모지
  폴백. 보강으로 `File(dir, avatarId).canonicalPath.startsWith(dir.canonicalPath)` 확인 +
  `fetchAvatar` URL에 `URLEncoder.encode(avatarId, "UTF-8")`. **모바일 AvatarStore도 같은
  검증을 넣어 대칭 유지**(현재 모바일은 md5로 파일명을 만들지만 수신 avatarId 검증은 확인 필요).

## SV2. [HIGH] 멤버 등록에 초대 증명이 없고 멤버 수 상한도 없음 — 무증명·무제한 가입

- **위치**: `firestore.rules:50` — `allow create, update: if signedIn() && request.auth.uid == memberId;`
  연계 `:22`(room get: signedIn), `:58`(inviteCodes get: signedIn).
- **공격 시나리오**: 멤버 문서 생성은 `uid == memberId`만 검사할 뿐 **요청자가 초대 코드를
  안다는 것을 증명하지 않는다.** roomId(또는 코드→roomId)를 얻은 임의의 익명 사용자가
  `rooms/{id}/members/{자기uid}`를 직접 만들면 즉시 정식 멤버가 되어 전체 메시지 읽기·쓰기·
  **전량 삭제**(`:39`), 상대 FCM 토큰·읽음·타이핑·캐릭터 열람이 가능하다. 멤버 수 제한 조건이
  없어 **제3·4자가 조용히 합류**할 수 있고, 두 정당한 사용자는 감지·축출 수단이 없다
  (`leaveRoom`은 자기 문서만 지우고 상대가 `create`로 즉시 재생성 가능).
- **왜 중요**: 1:1 대화 전체의 기밀성·무결성이 "코드 하나"에만 걸려 있는데, 코드 유출
  (스크린샷·전달·SV4 열거) 시 침입이 **영구적·무증상**이다. 앱이 가정한 2인 모델이 서버에서
  전혀 뒷받침되지 않는다.
- **수정(택1~조합)**:
  (a) 초대 코드 **1회용화** — `joinRoom` 성공 시 `inviteCodes/{code}`를 소비(무효화, 규칙에서
      본인 방 코드 delete 허용), 이후 새 가입 차단. 현 구조에 가장 잘 맞는 최소 수정.
  (b) 멤버 수 서버 강제 — 방 문서에 `ownerUid`/`partnerUid` 두 슬롯만 두고 세 번째 uid의 member
      create를 규칙에서 거부(`rooms/{id}` 문서를 참조하는 규칙).
  (c) 근본적으로는 초대 코드 소지를 규칙/Function으로 검증하는 가입 흐름. (a)를 먼저 권장.

## SV3. [MEDIUM] App Check 부재 + 개방형 익명 가입 → 방·초대코드·아바타 무제한 생성(과금 DoS)

- **위치**: `firestore.rules:24`(rooms create: signedIn)·`:60`(inviteCodes create: signedIn)·
  `:43`(avatars write: isMember). App Check 미사용(코드베이스 `appCheck` 흔적 없음).
- **공격 시나리오**: 공개된 config로 누구나 `accounts:signUp` 익명 로그인 → `rooms`·`inviteCodes`
  문서를 초당 수백 건 무한 생성. 특히 아바타는 **base64 이미지를 Firestore 문서에 저장**
  (문서당 최대 ~1MB)하므로 대용량 쓰기를 무제한 반복해 저장·쓰기 과금을 부풀린다.
  `inviteCodes`는 `delete: if false`(`:61`)라 **영구 삭제 불가**해 저장 비용이 단조 증가한다.
  Function엔 `maxInstances:3`이 있으나 Firestore 쓰기 자체엔 상한이 없다.
- **왜 중요**: Blaze 요금제면 실제 청구가 발생하는 순수 비용 공격. 익명 인증 앱의 공통 약점이라
  근본 차단이 필요.
- **수정**: (1) **Firebase App Check**(Play Integrity/DeviceCheck) 도입 — 정식 앱 외 접근 차단이
  근본 해법. (2) GCP 예산 알림(budget alert) 설정. (3) SV5·SV9의 필드/크기 화이트리스트 병행.

## SV4. [MEDIUM] 초대 코드가 비암호학적 난수 + 좁은 공간 + 무제한 열거

- **위치**: `app/.../sync/SyncManager.kt:986-989`(`randomCode()` → `alphabet.random()` =
  Kotlin `Random.Default`, 비-CSPRNG), `desktop/.../Main.kt:1297-1300`(동일),
  `shared/.../Protocol.kt:180-181`(`INVITE_ALPHABET` 32자 × `INVITE_LENGTH` 6 → 32⁶ ≈ 10.7억),
  게이트 `firestore.rules:58`(get: signedIn). 대비: `desktop/.../data/Firestore.kt:701`의 문서
  ID는 SecureRandom을 쓰는데 초대 코드만 비-CSPRNG라 일관성도 없다.
- **공격 시나리오**: 초대 코드가 사실상 방 콘텐츠 전체의 bearer 토큰(SV2)인데, `get`이 모든 익명
  사용자에게 열려 있고 App Check·레이트리밋이 없다. 특정 방 1건은 10억분의 1로 비현실적이지만,
  **활성 방이 존재하는 아무 코드나 찾는 열거**는 방 수에 비례해 확률이 오르고, `inviteCodes`가
  영구 누적(SV3)돼 시간이 갈수록 유효 코드 밀도가 높아진다.
- **수정**: (1) `randomCode()`를 **SecureRandom** 기반으로 교체(`SecureRandom().nextInt(alphabet.length)`)
  — :shared에 공용 함수로 두어 모바일·데스크톱 통일. (2) 코드 길이 **8자 이상**으로 상향
  (32²배 ≈ 1000배 열거 비용). (3) App Check(SV3)로 자동 스캔 차단. (4) SV2(a)의 1회용화가 되면
  열거 창 자체가 짧아진다.

## SV5. [LOW→즉시] 메시지 update 규칙이 authorUid 재지정을 막지 않음

- **위치**: `firestore.rules:37` —
  `allow update: if isMember(roomId) && resource.data.authorUid == request.auth.uid;`
- **문제**: 기존 문서(`resource`)의 작성자만 수정하도록 막았으나, **새 값
  (`request.resource.data.authorUid`)이 기존과 같아야 한다는 조건이 없다.** 자기 메시지를
  수정하며 `authorUid`를 상대 uid로 바꿔치기하면, 그 메시지가 상대 작성으로 표시되고 수신 측
  "내 발신" 필터(`SyncManager.kt:816`, `author == myUid`)에 걸려 상대 화면에서 오히려 숨겨질 수
  있다. B1(create의 authorUid 강제)으로 create는 막았는데 update에 같은 구멍이 남았다.
- **수정(한 줄)**: update 조건에
  `&& request.resource.data.authorUid == resource.data.authorUid` 추가.

## SV6. [LOW] 멤버가 상대의 멤버 문서를 삭제 가능 — 푸시 무력화 그리핑

- **위치**: `firestore.rules:52` —
  `allow delete: if isMember(roomId) || (signedIn() && request.auth.uid == memberId);`
- **문제**: 멤버 A는 `isMember`가 참이라 상대 B의 **현재 멤버 문서**를 지울 수 있다(memberId가
  본인인지 확인 안 함). B의 `fcmToken`이 날아가 푸시가 끊기고, B는 `isMember` 자격을 잃어
  재등록 전까지 메시지를 못 읽는다. 규칙 주석의 의도는 "레거시 deviceId 문서 정리"인데 상대
  현재 문서까지 지울 수 있는 건 과도.
- **수정**: 실제 삭제 주 경로가 본인 uid 문서이므로(`SyncManager.kt:981`)
  `allow delete: if signedIn() && request.auth.uid == memberId;`로 좁힌다. 레거시(deviceId 키)
  정리는 클라이언트가 본인 문서만 지우도록 흐름 조정.

## SV7. [MEDIUM] 데스크톱 리프레시 토큰이 config.json에 평문·기본 권한 저장

- **위치**: `desktop/.../data/Config.kt:55·117·161`(`authRefreshToken`),
  `desktop/.../data/AppPaths.kt:12-14`(`~/.pbp-desktop/config.json`). Gson 직렬화로 평문 저장,
  파일 생성 시 권한 제한 없음.
- **공격 시나리오**: 리프레시 토큰은 폐기 전까지 무기한 유효한 장기 자격증명 —
  `securetoken.googleapis.com`에서 무제한 ID 토큰을 재발급받아 그 익명 UID의 방 멤버십·메시지
  읽기/쓰기를 전부 얻는다. 다중 사용자 PC·백업·동기화 폴더(OneDrive 등) 유출 시 방 접근 탈취.
- **왜 중요**: UID가 곧 방 접근 근거이므로 토큰 유출 = 방 계정 탈취. 단일 사용자 데스크톱에선
  위험이 낮아 CRITICAL은 아니나, 평문+무권한은 회피 가능한 노출.
- **수정**: 저장 파일에 소유자 전용 권한(POSIX `rw-------`, Windows는 ACL로 현재 사용자만).
  가능하면 OS 자격증명 저장소(Windows DPAPI/Credential Manager, macOS Keychain). 최소한 리프레시
  토큰만 별도 권한 제한 파일로 분리. RoomCache 평문 로그도 같은 권한 개선으로 함께 커버.

## SV8. [MEDIUM] 방 문서 update에 필드 화이트리스트 없음

- **위치**: `firestore.rules:26` — `allow update: if isMember(roomId);`
- **문제**: 멤버가 방 문서의 **모든 필드**를 바꾸거나 임의의 큰 필드를 추가(1MB 한도까지)해
  문서를 부풀릴 수 있다. 주석 의도는 "테마·배경"뿐. `inviteCode` 필드를 바꿔 상대 재참여 흐름을
  교란할 여지도 있다(로컬 캐시가 remoteId 기준이라 실질 영향은 경미하나 무결성 훼손).
- **수정**: `request.resource.data.diff(resource.data).affectedKeys()
  .hasOnly(['themeColor','backgroundKey','name','rule','logsClearedAt'])` 로 변경 키 제한.

## SV9. [LOW] 아바타 write에 크기·타입·필드 검증 없음

- **위치**: `firestore.rules:43` — `allow read, write: if isMember(roomId);`
- **문제**: 멤버가 avatars 하위에 임의 개수·크기(문서당 ~1MB) 문서를 무제한 생성 — 저장 비용
  소진 벡터(SV3와 결합). 
- **수정**: 허용 필드 화이트리스트 + 크기 상한(규칙에서 `request.resource.data.data.size()` 제한),
  중장기적으로 이미지는 Firestore 대신 Cloud Storage(contentType/size 규칙)로.

## SV10. [LOW] Function이 클라이언트 senderName을 무검증 신뢰 — 푸시 표시명 위조

- **위치**: `functions/index.js:36·59` — `data.senderName`을 그대로 푸시 페이로드에 실음.
- **문제**: 멤버가 메시지 생성 시 `senderName`을 임의/초장문으로 지정하면 상대 알림에 위조된
  발신자명이 뜬다. 2인 방에선 낮음(발신자는 둘 중 하나). 함수가 클라이언트 필드를 무비판
  신뢰하는 패턴만 기록.
- **수정**: `authorUid`로 members 문서를 조회해 서버가 이름을 결정하거나, 최소한 길이 상한
  (앞 40자)만 잘라 전송.

## SV11. [LOW→MEDIUM] Android allowBackup 미지정 → 로컬 채팅 DB가 백업으로 유출

- **위치**: `app/src/main/AndroidManifest.xml:10-16`(`<application>`에 `android:allowBackup`·
  `dataExtractionRules`·`fullBackupContent` 없음 → 기본 `allowBackup=true`).
- **공격 시나리오**: Auto Backup 기본 활성이라 `pbp.db`(모든 RP 메시지·프로필·초대 코드 평문)와
  SharedPreferences가 Google 계정 백업·기기 이전(D2D)에 포함된다. USB 디버깅 기기는 `adb backup`
  으로도 추출 가능. 사적 RP 로그가 앱 샌드박스를 벗어난다.
- **수정**: `android:allowBackup="false"` 명시, 또는 백업을 유지하되 `dataExtractionRules`로
  `pbp.db`·prefs 제외.

## SV12. [LOW] 데스크톱 legacyFindRoomByCode의 쿼리 문자열 보간

- **위치**: `desktop/.../data/Firestore.kt:344-348` — `structuredQuery` JSON을
  `"stringValue":"${code.replace("\"","")}"`로 보간(따옴표만 제거, 백슬래시·개행 잔존).
  같은 파일 `findRoomByCode`(:338)는 `URLEncoder.encode`를 쓰는데 이 폴백만 수동 이스케이프라
  일관성도 없다.
- **문제**: `code`는 본인 입력이라 실질 공격면은 작지만, 안티패턴.
- **수정**: `[A-Z0-9]` 화이트리스트 강제(또는 파라미터화). SV4의 코드 생성 규격과 자연히 일치.

---

## INFO — 수용된 위험 / 확인된 양호 (재보고 아님, 명시 목적)

- **API 키가 tracked 파일에 포함** (`app/.../res/values/firebase.xml:8-11`, `desktop/Main.kt:67-68`) —
  Firebase 클라이언트 키는 **비밀이 아니라 프로젝트 식별자**로 클라이언트 임베드가 정상. 실제
  방어는 Firestore 규칙. **단 전제 확인 필요**: GCP 콘솔에서 이 키에 API 제한(Firestore/Identity
  Toolkit만)을 걸 것, 그리고 규칙이 실제로 배포돼 있을 것(키 안전성이 규칙 배포에 전적으로 의존).
- **릴리스가 디버그 키스토어로 서명** (`app/build.gradle.kts:30`) — 디버그 키 비번은 공개값
  (`android`)이라 업데이트 서명 무결성이 약함. 개인 사이드로드 맥락 수용, **스토어 배포 전 전용
  키스토어 필수**.
- **Room DB·RoomCache 미암호화** — 비루팅/비침해 기기에선 앱 샌드박스/홈 권한으로 보호. 2인 앱에
  SQLCipher는 과함. SV11(백업)·SV7(파일 권한)로 실질 노출 경로만 닫으면 충분.
- **멤버가 상대 FCM 토큰 열람 가능**(`:48`) — 토큰만으로 서버 자격증명 없이 푸시 불가라 직접
  악용 불가. 최소권한상 별도 서브컬렉션 분리가 이상적이나 수용.
- **멤버 누구나 메시지 전량 삭제**(`:39`) — "로그 초기화가 상대 메시지도 지운다"는 의도된 정책.
- **서버 측 GM/마스터 역할 없음** — `senderIsGm`·`isMaster`·`senderName`은 클라이언트 데이터.
  2인 상호신뢰 전제 수용(서버 강제 권한 상승 개념이 없음을 명시).
- **양호 확인**: TLS 전 구간 강제·우회 없음, 시나리오 뷰어 SSRF 방어(호스트 고정·https 재조립·
  크로스프로토콜 리다이렉트 미추종·1MB 상한·로그인 HTML 거부), HTML 내보내기 이스케이프, 노출
  컴포넌트 최소화(MainActivity만 exported, FileProvider `exported=false` capture/ 스코프),
  Cloud Function 본문 미탑재·발신자 정확 제외·syncAt 2분 필터, 신원 생성 SecureRandom(초대 코드
  제외), avatar md5는 콘텐츠 주소 해시라 보안 무관.

## 검증

- 규칙 수정(SV2·SV5·SV6·SV8·SV9)은 **Firestore 에뮬레이터 규칙 테스트**로 확인 권장 —
  비멤버 member create 거부, authorUid 재지정 update 거부, 상대 member delete 거부, 방 문서
  화이트리스트 밖 키 update 거부. 배포는 위 "배포 필요" 절차.
- SV1은 데스크톱에서 `avatarId="../evil"` 메시지를 주입해 캐시 디렉터리 밖 쓰기가 차단되는지 확인.
- SV4는 `randomCode()`가 SecureRandom을 쓰고 길이 8+인지 + :shared 공용화 확인.
- 클라이언트 수정 후: `gradlew assembleDebug testDebugUnitTest :shared:test :desktop:test`.
