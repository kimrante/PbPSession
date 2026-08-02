# 서버 배포 안내 (Firestore 규칙 · Cloud Functions)

앱 코드를 고쳐도 **서버 쪽 두 가지는 따로 배포해야 실제로 바뀐다.**

| 배포 대상 | 파일 | 안 하면 생기는 일 |
|---|---|---|
| Firestore 보안 규칙 | `firestore.rules` | 규칙이 옛 것이라 새 제약(예: 작성자 사칭 차단)이 걸리지 않는다 |
| Cloud Functions (푸시) | `functions/index.js` | 푸시 트리거가 옛 로직으로 계속 돈다 |

프로젝트 ID는 **`pbp-session-1195c`** 하나뿐이다. 아래 명령의 `--project` 값은 늘 이것.

---

## 0. 준비 (처음 한 번만)

1. **Node** — 이미 설치돼 있다 (`node --version`으로 확인. 22 이상이면 된다).
   Functions 런타임은 `functions/package.json`의 `engines.node = 22`를 쓴다.

2. **로그인** — 브라우저가 열리고 구글 계정을 고르면 된다.

   ```bash
   npx firebase-tools login
   ```

   확인:

   ```bash
   npx firebase-tools projects:list
   ```

   `pbp-session-1195c`가 목록에 보여야 한다.

3. **Blaze 요금제** — Cloud Functions는 종량제 계정에서만 배포된다 (무료 한도가 커서
   실제 청구는 거의 0). Firebase 콘솔 → 톱니바퀴 → 사용량 및 결제 → 요금제 수정.
   **규칙 배포만 할 거라면 이 단계는 필요 없다.**

---

## 1. Firestore 보안 규칙 배포

```bash
npx firebase-tools deploy --only firestore:rules --project pbp-session-1195c
```

- 저장소 루트에서 실행한다 (`firebase.json`이 `firestore.rules`를 가리킨다).
- 몇 초면 끝난다. 콘솔 → Firestore Database → 규칙 탭에서 반영 시각을 확인할 수 있다.

### 배포 전에 반드시 확인할 것

규칙은 **즉시 전원에게 적용**된다. 조건을 만족하지 못하는 기기는 그 순간부터 동기화가 끊긴다.

- **양쪽 기기(폰·PC)가 모두 최신 빌드**여야 한다. 구버전이 남아 있으면 그 기기만 조용히
  전송에 실패한다.
- 익명 로그인이 켜져 있어야 한다 (`docs/firebase-security.md` 1장). 이미 켜 두었다면 그대로.

### v0.10.4에서 바뀐 것

`messages` 생성 규칙에 **작성자 본인 확인**이 붙었다.

```
allow create: if isMember(roomId) &&
  request.resource.data.authorUid == request.auth.uid;
```

멤버가 상대 UID를 사칭한 메시지를 만들 수 없게 된다. 정상 클라이언트는 원래 자기 UID를
싣고 있으므로 동작 변화가 없다.

---

## 2. Cloud Functions (푸시 알림) 배포

```bash
npx firebase-tools deploy --only functions --project pbp-session-1195c
```

- 1~3분 걸린다. 처음 배포하는 계정이면 필요한 API(Cloud Build 등) 사용 설정을 물어보는데
  전부 승인하면 된다.
- 리전은 `functions/index.js`가 `asia-northeast3`으로 고정한다 — Firestore DB와 같은
  리전이라 교차 리전 지연이 없다. 바꾸지 말 것.
- `functions/node_modules`는 이미 있다. 의존성을 바꿨다면 `cd functions && npm install` 후 배포.

### v0.10.4에서 바뀐 것

오래된 메시지를 거르는 기준이 **보낸 기기의 시계(`createdAt`) → 서버 시각(`syncAt`)** 으로
바뀌었다. 시계가 2분쯤 늦은 기기에서 보낸 새 메시지가 "오래된 것"으로 걸러져 알림이
조용히 누락되던 문제가 사라진다.

---

## 3. 둘 다 한 번에

```bash
npx firebase-tools deploy --only firestore:rules,functions --project pbp-session-1195c
```

---

## 4. 배포 후 확인

1. **규칙** — 폰과 PC에서 각각 메시지를 하나씩 보내 양쪽에 도착하는지 본다.
   한쪽만 안 되면 그 기기가 구버전이거나 익명 로그인이 꺼져 있는 것이다.
2. **푸시** — 폰 앱을 백그라운드로 보낸 뒤 PC에서 메시지를 보내 알림이 뜨는지 본다.
3. **함수 로그** — 실패하면 여기 이유가 남는다.

   ```bash
   npx firebase-tools functions:log --project pbp-session-1195c
   ```

---

## 5. 되돌리기

배포에는 "이전 버전으로" 버튼이 없다. **직전 커밋의 파일을 다시 배포**하는 방식으로 되돌린다.

```bash
# 규칙만 직전 상태로
git show HEAD~1:firestore.rules > firestore.rules
npx firebase-tools deploy --only firestore:rules --project pbp-session-1195c
git checkout firestore.rules   # 작업 트리 원상 복구
```

Functions도 같은 방식(`git show HEAD~1:functions/index.js > functions/index.js` → 배포 →
`git checkout`)이다.

규칙은 콘솔에서도 되돌릴 수 있다: Firestore Database → 규칙 → **기록** 탭에서 과거 버전을
골라 게시.

---

## 6. 자주 걸리는 것

| 증상 | 원인·해결 |
|---|---|
| `Failed to authenticate` | `npx firebase-tools login` 다시 |
| `HTTP Error: 403 ... billing` | Blaze 요금제가 아니다 (Functions 한정) |
| 배포는 됐는데 규칙이 안 걸린 것 같다 | 클라이언트가 캐시된 규칙을 쓰지 않는다 — 실제로는 즉시 적용된다. 앱이 구버전인지 먼저 의심할 것 |
| 배포 후 한쪽만 동기화 안 됨 | 그 기기가 구버전이거나 `members/{auth uid}` 문서가 없다. 최신 빌드로 앱을 한 번 열면 자동 보충된다 |
| 푸시가 여전히 안 온다 | 알림 권한, 그리고 `members/*.fcmToken`이 있는지 확인. 방을 한 번 열면 등록된다 |

---

## 함께 볼 것

- `docs/firebase-security.md` — 익명 로그인 켜기, API 키 제한, 규칙 요지, 전환기 코드 정리
- `docs/architecture.md` — 스키마를 바꿀 때 **세 곳 동시 수정**(Protocol.kt · functions/index.js · firestore.rules)
