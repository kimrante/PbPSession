# Firebase 보안 설정 (P0-1)

코드 반영: 익명 인증(양쪽 클라이언트) + `firestore.rules` + `inviteCodes/{code}` 참가 경로.
아래 **콘솔 작업 3가지는 사람이 직접** 해야 실제로 잠긴다. 하기 전까지는 기존 공개 모드로 동작한다(하위 호환).

## 1. 익명 로그인 활성화 (필수, 규칙 배포 전에 먼저)

Firebase 콘솔 → Authentication → Sign-in method → **익명(Anonymous) 사용 설정**.

- 앱/데스크톱은 시작 시 자동으로 익명 계정을 만들어 쓴다. 활성화 전에는 인증 없이(공개 규칙 하에서) 동작한다.

## 2. 보안 규칙 배포

```
npx firebase-tools deploy --only firestore:rules --project pbp-session-1195c
```

규칙 요지 (`firestore.rules`):
- 모든 접근에 로그인 필수, 방 목록(list) 조회 금지
- 방 데이터는 `rooms/{id}/members/{내 uid}` 문서가 있는 멤버만 읽기/쓰기
- 메시지 수정은 작성자만, 삭제는 멤버 누구나(방 로그 초기화용)
- 초대 참가는 `inviteCodes/{코드}` 단건 조회로만

**주의**: 배포 순간부터 구버전 앱(익명 인증 없는 빌드)은 동기화가 끊긴다. 양쪽 모두 최신 빌드로 올린 뒤 배포할 것.
기존 방은 앱이 시작할 때 `members/{auth uid}` 문서를 자동 보충하므로 그대로 쓸 수 있다.
**규칙 배포 이전에 발급된 초대 코드**는 `inviteCodes` 매핑이 없어 참가가 안 될 수 있다 — 방 설정에서 공유를 다시 열면 기존 코드가 그대로 반환되므로, 이 경우 방 삭제 후 재공유하거나 아래 매핑 문서를 콘솔에서 수동 생성:
`inviteCodes/{코드}` = `{ roomId: "<rooms 문서 ID>" }`

## 3. API 키 제한 (키가 저장소에 노출된 적 있음)

API 키는 Firebase 설계상 비밀이 아니지만, 이미 공개 저장소 이력에 있으므로 제한을 걸 것:

Google Cloud 콘솔 → API 및 서비스 → 사용자 인증 정보 → 해당 API 키:
- **API 제한**: Identity Toolkit API, Token Service API, Cloud Firestore API, FCM 관련만 허용
- 규칙이 배포되면 키만으로는 데이터 접근이 불가능해진다(인증+멤버십 필요)

## 배포 후 정리할 것 (전환기 코드)

규칙이 배포되어 정상 동작을 확인한 뒤에는 아래 하위 호환 코드를 제거한다:

- **레거시 초대코드 폴백** — `SyncManager.joinRoom`과 데스크톱 `legacyFindRoomByCode`의
  `rooms` 컬렉션 쿼리. 규칙 배포 후엔 100% 거부되어 지연만 만든다 (C1)
- **deviceId 신원 폴백** — `SyncManager.myUid`의 `authUid ?: deviceId`,
  수신 필터의 deviceId 비교, 멤버 문서 레거시 삭제 (C2)
- 위 둘을 지우기 전에 양쪽 기기가 최신 빌드로 최소 한 번씩 접속해
  `members/{auth uid}` 문서가 생겼는지 확인할 것

## 동작 방식 메모

- 신원: 익명 auth UID. 인증 실패 시(콘솔 미설정 등) 기존 deviceId로 폴백 — 규칙 배포 전 한정
- 안드로이드: 메시지 `authorUid`·멤버 문서 키가 auth UID로 전환. 수신 필터는 auth UID와 구 deviceId를 모두 "내 발신"으로 취급
- 데스크톱: Bearer ID 토큰(리프레시 토큰은 `~/.pbp-desktop/config.json`에 보존)으로 요청. 메시지 `authorUid`는 기존 deviceId 유지(편집 기능 없음 → 규칙 충돌 없음)
- 재설치 시 auth UID가 바뀌므로 과거 메시지의 좌우 정렬이 어긋날 수 있다(데이터 유실 아님)
- `FirestoreRestLiveTest`(운영 DB에 쓰던 라이브 테스트, 자격증명 포함)는 삭제됨
