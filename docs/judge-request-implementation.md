# 작업 지시서 — 자동 판정 요청 (GM 전용)

시각 규격은 **`docs/mockups/mockup-judge-request.html`**(+PNG)에 있다. 이 문서는 **그 목업을 코드로 옮기는
순서와 지점**을 정리한 것이며, **다른 세션이 이 문서만 보고 작업할 수 있도록** 항목마다 위치·변경·주의를 명시한다.
기준 커밋: `a0c889b` (v0.9.5). 라인 번호는 이 커밋 기준.

## 기능 한 줄

GM 프로필로 말하는 중에 **대상 캐릭터와 값 이름**을 골라 요청을 보내면 채팅 중앙에 판정 문구가 뜨고,
**그 캐릭터를 가진 사람만** 눌러서 굴린다. 굴림 결과는 지금 쓰는 다이스 카드로 그대로 나온다.

---

## ⚠ 먼저 읽을 것 — 이 기능에는 선행 작업이 하나 있다

**캐릭터 프로필은 기기 로컬 데이터이고 서버로 동기화되지 않는다.**

- `ProfileDao.observeForRoom` (`data/Daos.kt:83`) =
  `SELECT * FROM profiles WHERE roomId IS NULL OR roomId = :roomId` — **이 기기의 DB만** 본다.
- `SyncManager`에 프로필을 올리거나 받는 경로가 **없다**. 상대는 메시지에 박힌 발신자 스냅샷
  (`senderName`·`senderEmoji`·색)만 볼 뿐, 캐릭터 자체나 그 값(`stats`)은 모른다.

즉 **GM 기기에는 상대 플레이어의 캐릭터가 존재하지 않는다.** 요청 화면에서 "방 안의 모든 프로필"을
고르려면 명단이 먼저 건너와야 한다. 이것을 J0으로 먼저 한다.

---

## 작업 순서

| 단계 | 내용 | 규모 | 산출물 |
|---|---|---|---|
| **J0** | **캐릭터 명단 공유 (선행)** | M | `SyncManager`, `Protocol` |
| J1 | 스키마 — `JUDGE` 타입 + 컬럼 2개 | S | `Entities`, `AppDatabase`, `SyncMapping` |
| J2 | GM 전용 판정 요청 버튼 | S | `ChatInput.kt` |
| J3 | 선택 시트 (프로필 → 값) | M | 신규 `JudgeRequestSheet.kt` |
| J4 | 요청 보내기 | S | `PbpRepository` |
| J5 | 판정 문구 렌더 (3상태) | M | `MessageBlock.kt` |
| J6 | 탭 → 굴림 | M | `PbpRepository` |
| J7 | HTML 로그 · 데스크톱 | M | `:shared LogExport`, `desktop/` |

J0 없이 J2~J6만 만들면 **GM 화면에 상대 캐릭터가 안 뜬다.** 순서를 바꾸지 말 것.

---

## J0. 캐릭터 명단 공유 (선행)

### 어디에 싣나

**이미 있는 `rooms/{id}/members/{uid}` 문서에 얹는다.** 새 컬렉션을 만들지 않는다.

- 이 문서에는 이미 `joinedAt`·`platform`·`lastReadAt`·`typingUntil`·`fcmToken`이 들어 있다
  (`SyncManager.kt:183-194`, `:207-223`).
- 그리고 **이 컬렉션을 구독하는 리스너가 이미 하나 돌고 있다** — `observePeerState`
  (`SyncManager.kt:239-260`)가 읽음 확인과 입력 중 표시를 같은 스냅샷에서 뽑는다.
  여기에 필드를 하나 더 얹으면 **추가 읽기가 0**이다. 이 설계를 따르는 이유가 이것이다.

### 무엇을 싣나

```
characters: [
  { name: "카야", emoji: "🌊", nameColor: 4287…, stats: ["민첩","관찰력","설득","도서관","회피"] },
  …
]
```

- **`stats`는 값의 "이름"만.** 숫자는 넣지 않는다.
  - GM에게 필요 없다 — 요청은 `{민첩}` 플레이스홀더로 저장되고, **굴림은 대상자 기기에서 그때의 값으로** 한다.
  - 그래서 요청을 보낸 뒤 값이 바뀌어도 항상 최신 값으로 굴러간다.
  - 남의 캐릭터 시트 숫자를 서버에 올리는 결정을 피할 수 있다.
- 숫자가 아닌 값은 애초에 빼고 보낸다 — `ProfileStats.decode(stats).filter { it.second.trim().toIntOrNull() != null }.map { it.first }`.
  (기존 채팅 팔레트가 쓰는 규칙과 같다 — `ProfileStats.paletteSuggestions`.)
- **GM 프로필은 싣지 않는다**(`filter { !it.isGm }`). 대상 목록에 GM이 나오지 않아야 하고,
  애초에 보낼 이유가 없다.

### 언제 쓰나

- 방에 들어갈 때(`ensureMembership` 직후) 한 번, 그리고 **이 방의 프로필 목록이 바뀔 때마다**.
- **매번 쓰지 말 것.** `pushReadReceipt`가 `pushedReadAt` 맵으로 같은 값 재전송을 막는 패턴
  (`SyncManager.kt:196-197`)을 그대로 따라, 직전에 올린 명단과 같으면 쓰지 않는다.
  프로필은 자주 바뀌지 않으므로 실제 쓰기는 거의 발생하지 않는다.
- `SetOptions.merge()`로 쓴다 — 다른 필드(`lastReadAt` 등)를 지우면 안 된다.

### 어떻게 읽나

`observePeerState`의 `PeerState`에 필드를 하나 더 붙인다. **리스너를 새로 만들지 말 것.**

```kotlin
data class PeerState(
    val readAt: Long? = null,
    val typingUntil: Long = 0L,
    val typingName: String? = null,
    /** 상대 기기가 올린 캐릭터 명단 — 판정 요청 대상 목록 (J0) */
    val peerCharacters: List<PeerCharacter> = emptyList(),
)
data class PeerCharacter(val name: String, val emoji: String, val nameColor: Long?, val stats: List<String>)
```

- 파싱은 방어적으로. 스냅샷의 `characters`는 `List<Map<String, Any?>>`로 오는데, 필드가 없거나
  타입이 다르면 그 항목만 버린다(`SyncMapping.fromMap`이 쓰는 `as?` + 기본값 방식과 동일).
- 로컬 전용 방(`remoteId == null`)은 상대가 없으므로 빈 목록이다.

### 데스크톱

같은 문서에 같은 형식으로 쓴다. 데스크톱 프로필은 `AppConfig.profiles`에 있고 값은 `Map<String, String>`이므로
`ProfileStats.sanitize(map)`를 거쳐 숫자만 남긴 키 목록을 보낸다. **두 모듈이 같은 필드명을 쓰도록**
`Protocol.Field`에 `CHARACTERS = "characters"`를 추가하고 양쪽이 그 상수를 쓴다
(`shared/.../Protocol.kt:22-38`에 기존 필드 상수들이 있다).

---

## J1. 스키마

### J1-1. 메시지 타입

- `data/Entities.kt:10` — `enum class MessageType { TEXT, DICE, SYSTEM }`에 **`JUDGE`** 추가.
- **구버전 클라이언트와 호환된다**: `SyncMapping.fromMap`(`sync/SyncMapping.kt:31-32`)이
  `runCatching { MessageType.valueOf(...) }.getOrDefault(TEXT)`라 모르는 타입은 **평범한 텍스트 말풍선**이 된다.
  크래시 없음. 데스크톱도 `message.type == "SYSTEM"` 식 문자열 비교라 `else`(일반 말풍선)로 떨어진다.
  그래서 `body`에 사람이 읽을 문구(`"카야, 민첩 판정"`)를 넣어 두는 것이 중요하다 — 구버전에서 보이는 게 그것이다.

### J1-2. 컬럼 2개

`Message`에 추가한다.

| 컬럼 | 타입 | 뜻 |
|---|---|---|
| `judgeTarget` | `String?` | 요청 대상 캐릭터 이름. `JUDGE` 메시지에만 채운다 |
| `judgeRef` | `String?` | **굴림 결과(DICE)가 가리키는 요청의 키.** `JUDGE`에는 null |

- `diceExpr`은 이미 있으므로 재사용한다 — `JUDGE`에는 `Rules.judgeCommand(rule, "민첩")`
  (= `"1d100<={민첩}"`)을 그대로 넣는다. 새 컬럼을 만들지 말 것.
- Room은 `version = 10`에 명시 마이그레이션을 쓴다(`data/AppDatabase.kt:106-121`). `version = 11`로 올리고
  `MIGRATION_10_11`을 추가해 `addMigrations(...)` 목록에 넣는다:

  ```kotlin
  private val MIGRATION_10_11 = object : Migration(10, 11) {
      override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE messages ADD COLUMN judgeTarget TEXT")
          db.execSQL("ALTER TABLE messages ADD COLUMN judgeRef TEXT")
      }
  }
  ```

- `SyncMapping.toMap`/`fromMap`(`sync/SyncMapping.kt:9-25`, `:30-48`)에도 두 필드를 넣는다.
  **가산 변경이라 구버전은 모르는 필드를 그냥 무시한다.**
  `Protocol.Field`에 `JUDGE_TARGET`·`JUDGE_REF` 상수를 추가해 양 모듈이 공유한다.

### J1-3. 왜 "굴렸음" 플래그가 아니라 `judgeRef`인가

요청 메시지에 `judgeRolledAt`을 채우는 방식은 **요청 메시지를 나중에 수정**해야 한다.
그런데 이 앱의 메시지 수정은 오프라인 재시도가 없다(리뷰 L5). 굴림은 로컬에 남았는데 요청 수정이
서버에 못 올라가면, 상대 화면에서는 영원히 "내 차례"로 남는다.

**결과 쪽에 참조를 다는 방식은 그 문제가 없다.** DICE 메시지는 일반 메시지와 같은 아웃박스를 타고,
"이 요청이 끝났는가"는 **결과가 존재하는가**로 판정하면 되므로 수정이 필요 없다. 완료 상태는 파생값이다.

- `judgeRef` 값 = 요청 메시지의 `remoteId ?: "local-${id}"`.
  공유 방에서 대상자가 요청을 보는 시점에는 이미 `remoteId`가 있다(서버를 거쳐 왔으므로).
  로컬 전용 방은 동기화가 없으니 로컬 id로 충분하다.

---

## J2. GM 전용 판정 요청 버튼

- **위치**: `ui/chat/ChatInput.kt` — `InputZone`은 **프로필 스트립**(`:155-213`) →
  **판정 팔레트 칩**(`:214-`, 조건부) → **입력줄**(`:245-`) 순서다.
- **변경**: 판정 팔레트 칩 자리와 같은 층에 한 줄 추가한다. 조건은

  ```kotlin
  val gmActive = remember(profiles, activeId) { profiles.find { it.id == activeId }?.isGm == true }
  ```

  **`room.isMaster`가 아니라 "지금 말하고 있는 프로필"** 기준이다. GM이 자기 NPC로 말하는 중에는
  요청을 걸 수 없고, 그게 자연스럽다.
- **모양**: 맨 `＋`가 아니라 **`＋ 판정 요청` 캡슐**. 바로 위 프로필 스트립에 이미 점선 `＋ 추가`가 있어
  (`ChatInput.kt:193`) 아이콘만 두면 구분되지 않는다. 규격은 판정 팔레트 칩과 **같은 캡슐**
  (`r-pill` · 세로 6dp · 가로 `gap3`)을 써서 같은 자리에 같은 모양이 오게 한다.
- `InputZone` 시그니처에 `onJudgeRequest: () -> Unit = {}`를 추가하고 `ChatScreen`에서 넘긴다.

---

## J3. 선택 시트

**신규** `ui/chat/JudgeRequestSheet.kt`. 2단계(프로필 → 값)를 **한 `ModalBottomSheet` 안에서 전환**한다
(시트를 두 개 띄우지 말 것 — 뒤로 가기가 꼬인다).

- **상태**는 `ChatScreen`의 다이얼로그 상태들 곁(`ChatScreen.kt:290-309`)에 같은 규칙으로:

  ```kotlin
  var judgeSheetOpen by rememberSaveable { mutableStateOf(false) }
  var judgeTargetName by rememberSaveable { mutableStateOf<String?>(null) }  // 이름으로 들고 있는다
  ```

  대상은 **id가 아니라 이름**으로 들고 있어야 한다 — 상대 캐릭터는 내 DB에 없어 id가 없다.
- **1단계 목록** = 내 로컬 프로필 중 GM이 아닌 것 + J0으로 받은 상대 캐릭터, **이름으로 중복 제거**.

  ```kotlin
  val candidates = remember(profiles, peerCharacters) {
      (profiles.filter { !it.isGm }.map { Candidate(it.name, it.emoji, it.nameColor, statNames(it)) } +
       peerCharacters.map { Candidate(it.name, it.emoji, it.nameColor, it.stats) })
          .distinctBy { it.name }
  }
  ```

  오너 프로필은 `CharacterProfile`이 아니라 이 목록에 애초에 들어오지 않는다 — **별도 필터가 필요 없다.**
- 값이 0개인 캐릭터는 **지우지 말고 비활성**으로 남긴다(목업 B컷 '등대지기'). "왜 안 보이지"를 막는다.
- **2단계 목록** = 그 후보의 `stats`(값 **이름**만). 숫자는 표시하지 않는다 — GM 기기에 없다.
  내 로컬 캐릭터를 고른 경우에는 숫자를 알 수 있지만, **두 경우의 화면이 갈라지지 않도록 항상 이름만** 보여준다.
- 하단 문구는 `"${대상}의 ${값} 값으로 1d100 하향 판정"` + `Rules.label(rule)`.
  값을 고르기 전에는 보내기를 잠근다.

---

## J4. 요청 보내기

**신규** `PbpRepository.sendJudgeRequest(roomId, targetName, statName)`.

- `JUDGE` 메시지 **1건만** 넣고 `pushIfSynced`. **여기서 굴리지 않는다** — 굴림은 대상자 기기에서 한다.
- 채우는 값:

  ```kotlin
  Message(
      roomId = roomId,
      type = MessageType.JUDGE,
      body = "$targetName, $statName 판정",              // 구버전에서 보일 문구이기도 하다
      diceExpr = Rules.judgeCommand(rule, statName),     // "1d100<={민첩}"
      judgeTarget = targetName,
      senderName = gm.name, senderEmoji = gm.emoji, senderIsGm = true, /* 발신자 스냅샷은 기존과 동일 */
      createdAt = System.currentTimeMillis(),
  )
  ```

- **`ProfileStats.substitute`를 여기서 부르지 말 것.** `{민첩}`은 플레이스홀더인 채로 저장돼야 하고,
  치환은 굴리는 쪽에서 한다(J6). 기존 `sendMessage`(`PbpRepository.kt:156-160`)가 발신 시점에 치환하는
  것과 **반대**라는 점을 주의.
- 요청 취소는 새로 만들지 않는다 — GM이 보낸 메시지이므로 **기존 롱프레스 삭제**가 그대로 동작한다.

---

## J5. 판정 문구 렌더

- **위치**: `ui/chat/MessageBlock.kt`의 `when` — 현재 분기는 SYSTEM(`:207`) → OOC(`:224`) →
  DICE(`:249`) → GM 서술(`:283`) → else(`:306`). **DICE 앞**에 `JUDGE` 분기를 넣는다.
- **상태 판정** — 세 가지다.

  ```kotlin
  val rolled = /* 이 요청을 가리키는 DICE 결과가 이미 있는가 */
  val mineToRoll = profiles.any { it.name == message.judgeTarget }
  val state = when {
      rolled -> Done
      mineToRoll -> MyTurn
      else -> Waiting
  }
  ```

- `rolled` 계산은 **화면에서 한 번만** 한다. 메시지마다 전체 목록을 훑으면 O(N²)다.
  `ChatScreen`에서 `remember(messages)`로 완료된 요청 키 집합을 한 번 만들어 내려보낸다 —
  `readMarkTarget`(`ChatScreen.kt`)이 이미 같은 방식이다.

  ```kotlin
  val rolledRefs = remember(messages) { messages.mapNotNullTo(mutableSetOf()) { it.judgeRef } }
  ```

- **그리는 규칙** (목업 02장):

  | 상태 | 표시 |
  |---|---|
  | `MyTurn` | 시그니처 옐로 **2dp** 테두리 + 채워진 ▶. 부제에 실제 식(`탭하면 1d100 ≤ 55 굴림`) — **이 기기에는 값이 있으므로** 보여줄 수 있다 |
  | `Waiting` | 1dp 테두리 + `⋯`. 부제 `"${대상}의 응답을 기다리는 중"`. **값을 보여주지 않는다**(모른다) |
  | `Done` | 1dp 테두리 + `✓`, 전체 62% |

  세 상태의 **폭·높이·패딩이 같아야** 목록이 흔들리지 않는다. 테두리가 2dp인 `MyTurn`은
  패딩에서 1dp를 빼 높이를 맞춘다(목업에서 실측해 266×61로 통일).
- **`MyTurn`일 때만 `clickable`을 붙인다.** `Waiting`에 리플이 생기면 눌러도 되는 것처럼 보인다.

---

## J6. 탭 → 굴림

**신규** `PbpRepository.rollJudge(request: Message)`.

```
① 중복 가드 — 이 요청의 judgeRef를 가진 DICE가 이미 있으면 즉시 반환
② 대상 프로필 찾기 — profiles.find { it.name == request.judgeTarget } ?: 반환
③ 치환 — ProfileStats.substitute(request.diceExpr!!, ProfileStats.decode(profile.stats).toMap())
④ DiceBot.parse → roll → Rules.judgeOutcome(rule, result)
⑤ DICE 메시지 1건 삽입 + pushIfSynced
```

- **④의 DICE 메시지는 새로 만들지 않는다** — 기존 `sendMessage`의 다이스 분기
  (`PbpRepository.kt:161-176`)와 **똑같은 형태**로 만든다:
  `senderName = "다이스봇"`, `senderEmoji = "🎲"`, `senderIsBot = true`,
  `diceExpr = "${profile.name} · ${command.expr}"`, `body = result.breakdown`.
  달라지는 것은 `judgeRef`를 채운다는 것뿐이다.
- **②의 프로필은 활성 프로필이 아니다.** "카야, 민첩 판정"인데 밤샘꾼으로 굴러가면 안 된다.
  활성 프로필을 바꾸지도 않는다 — 굴림 한 번만 그 캐릭터 이름으로 나간다.
- **중복 굴림 방지**는 두 겹이다.
  - 렌더: `Done`이면 `clickable`이 아예 없다.
  - 저장: ①의 가드. 연타로 두 번 들어와도 두 번째는 버린다.
  - 남는 창: 같은 이름의 캐릭터가 양쪽 기기에 있으면 둘 다 굴릴 수 있어 결과가 2건 나온다.
    1:1 방에서 같은 이름을 쓰는 경우가 드물고, 막으면 정상 사용(양쪽이 같은 캐릭터를 가진 경우)이
    깨지므로 **막지 않는다.** 결과가 2건 남을 뿐 데이터가 깨지지는 않는다.
- **오프라인**: 굴림은 로컬에서 즉시 일어나고 결과는 기존 아웃박스를 탄다. 요청 메시지를 고치지 않으므로
  (J1-3) 오프라인에서도 상태가 어긋나지 않는다 — 결과가 올라가는 순간 양쪽 모두 `Done`이 된다.

---

## J7. HTML 로그 · 데스크톱

- **HTML 로그** (`shared/.../LogExport.kt:72-` 의 타입 분기): `JUDGE` 분기를 추가해
  **`"카야, 민첩 판정"` 한 줄**로만 그린다. 종이 문서에서 버튼은 의미가 없고, 굴림 결과는 어차피
  뒤의 다이스 카드로 남는다. 분기를 빼먹으면 `else`로 떨어져 말풍선으로 나오므로 반드시 넣을 것.
- **데스크톱**: 같은 규칙을 이식한다.
  - 시트는 기존 오버레이 방식(`OverlayScaffold`)을 쓴다.
  - `Message.type`이 `String`이므로 `"JUDGE"` 비교.
  - 대상 판정은 `AppConfig.profiles`의 **이름 일치**.
  - J0의 명단 쓰기도 데스크톱에서 해야 한다 — 안 하면 모바일 GM에게 데스크톱 캐릭터가 안 보인다.

---

## 검증 체크리스트

- [ ] **J0**: 상대가 방에 들어온 뒤 GM의 요청 시트에 **상대 캐릭터가 보인다**
- [ ] J0: 프로필을 고쳐도 명단이 갱신된다. **가만히 두면 쓰기가 발생하지 않는다**(로그로 확인)
- [ ] J0: `lastReadAt`·`typingUntil`이 명단 쓰기로 **지워지지 않는다**(`merge` 확인)
- [ ] GM 프로필일 때만 판정 요청 줄이 보이고, 프로필을 바꾸면 **즉시 사라진다**
- [ ] 목록에 **GM 프로필과 오너 프로필이 없다**. 값 없는 캐릭터는 비활성으로 보인다
- [ ] 글자 값(직업 '탐정')은 값 목록에 **나오지 않는다**
- [ ] 요청을 보내면 GM 화면은 **대기(⋯)**, 대상자 화면은 **내 차례(▶)** 로 보인다
- [ ] 대상자가 탭하면 **그 캐릭터 이름**으로 굴러간다(활성 프로필이 달라도)
- [ ] 굴린 뒤 양쪽 모두 **완료(✓)** 로 바뀌고, 결과 카드가 아래에 붙는다
- [ ] 요청을 **연타**해도 결과가 1건이다
- [ ] 굴린 뒤 값을 고치고 **다시 보면** 이미 굴린 결과는 그대로다(과거 기록이 변하지 않는다)
- [ ] 새 요청은 **바뀐 값**으로 굴러간다(요청 시점 값이 아니라)
- [ ] 비행기 모드에서 굴려도 결과가 로컬에 남고, 복구되면 상대에게 간다
- [ ] 세 상태의 카드 **높이가 같아** 목록이 흔들리지 않는다
- [ ] 회전해도 시트 단계·선택이 유지된다
- [ ] **구버전 클라이언트**(있다면)에서 요청이 `"카야, 민첩 판정"` 텍스트 말풍선으로 보이고 **크래시하지 않는다**
- [ ] HTML 로그에 요청이 한 줄로 남는다
- [ ] 기존 채팅 팔레트 판정(`민첩 판정` 칩)이 그대로 동작한다 (회귀 없음)

## 규모 감각

**J0이 이 기능에서 가장 큰 덩어리**다 — 기존에 없던 동기화를 하나 추가하는 일이라서다. 다만 문서·리스너가
이미 있어 새로 만드는 것은 필드 하나와 파싱뿐이고, 추가 읽기 비용은 0이다.
J1~J4는 기존 부품 재사용이라 가볍고, J5는 상태 3가지의 시각 규격을 맞추는 데 시간이 든다.
J6은 기존 다이스 경로를 그대로 쓰므로 짧다. J7은 J0~J6이 끝나면 기계적이다.
