# 작업 지시서 — Supabase 전환 (Firestore → Postgres + Realtime)

전송 채널을 Firestore에서 **Supabase(Postgres + Realtime 웹소켓)** 로 교체한다.
**다른 세션이 이 문서만 보고 작업할 수 있도록** 단계마다 위치·변경·주의를 명시한다.
기준: main `aae4841`(v0.12.0) — 파일·함수 참조는 이 시점 기준이며, 이후 UI 커밋(v0.13.0)과는
충돌하지 않는 영역이다.

## 전환 한 줄

"Room이 유일한 화면 소스" 원칙과 outbox·멱등 전송 **설계는 그대로 두고**, 그 아래의 전송
채널만 바꾼다. read 과금이 강제했던 장치(증분 커서·적응형 폴링·윈도·레거시 스윕)와
데스크톱 수제 REST 클라이언트(688줄)가 삭제 대상이고, 데스크톱도 웹소켓 실시간이 된다.
**푸시는 FCM 유지** — Supabase에는 푸시 서비스가 없다.

## 왜 (근거 요약)

- Supabase는 **API 요청 무과금**(Free: DB 500MB·실시간 월 200만 메시지·동시 200연결) —
  2인 앱 수년치 여유. "재조회는 과금이라 못 한다"는 전제가 사라져 재접속 시 그냥 다시
  SELECT가 정답이 된다.
- [supabase-kt](https://github.com/supabase-community/supabase-kt)는 KMP(Android+JVM 공용)라
  동기화 계층을 :shared로 통합할 수 있다 — 리뷰 지시서(code-review-2026-08-02-overall.md)의
  C(축자 복제)·D(구조) 문제가 이 전환에 흡수된다.
- 삭제·편집이 Realtime DELETE/UPDATE 이벤트로 전파돼 A6·S7류 한계가 구조적으로 소멸.
- Postgres 제약이 앱 코드의 불변식을 대체: 초대 코드 충돌(A4)은 unique 인덱스가,
  authorUid 사칭(B1)은 RLS 한 줄이 막는다.

---

## ⚠ 먼저 읽을 것 — 착수 전 결정 3가지 (사용자 확인 필요)

1. **Free 티어는 7일 미사용 시 프로젝트 자동 일시정지**된다(데이터 보존, 대시보드에서 수동
   재개). 주 1회 이상 플레이하면 발동하지 않는다. 세션 공백이 잦으면 성가시다는 점을
   수용할지, Pro($25/월)로 갈지 **먼저 결정**할 것. 이 지시서는 Free 전제로 쓴다.
2. **프로젝트 생성(사용자 작업)** — 리전은 **서울(ap-northeast-2)**, Dashboard → Authentication →
   Sign In / Up에서 **Anonymous sign-ins 활성화**. `Project URL`과 `anon key`를 확보한다.
   anon key는 RLS가 방어하는 공개 가능 값이지만, 관례상 google-services.json처럼 리포에
   넣지 않는다 — 아래 S1의 로컬 설정 방식 참조.
3. **기존 데이터 이전 여부** — S6(선택)의 스크립트로 이전하거나, 로그 내보내기(HTML/TXT/PDF)로
   보존하고 새 출발한다. 2인 합의로 새 출발이 가장 싸다.

## ⚠ 전환 기간 상호운용 불가 — 배포는 동시에

S2(데스크톱)와 S3(모바일)이 **둘 다 끝나기 전에는 두 클라이언트가 서로 다른 서버를 본다.**
개발·검증 순서는 데스크톱 먼저가 맞지만(아래 이유), **릴리스는 모바일 APK + 데스크톱
배포판을 한 번에** 해야 한다. 중간 버전을 상대에게 먼저 주지 말 것.

---

## 유지 / 폐기 / 대체 맵

| 구분 | 대상 |
|---|---|
| **유지** | Room DB 전체(엔티티·DAO·마이그레이션), outbox(`uploaded`/`remoteId`), reconcile 설계, FCM 수신 경로(`FcmService`·`MessageNotifier`·google-services.json), UI 전부, :shared 순수 로직(DiceBot·Rules·PbpMarkup·GmSpeech·LogExport·Palette…) |
| **폐기** | `desktop/.../data/Firestore.kt`(688줄 REST 클라이언트), 적응형 폴링·재수신 윈도·레거시 스윕(`RoomSync.kt`·`Main.kt` 폴 루프·`Dimens.kt` 타이밍 상수), `SyncManager`의 Firestore 리스너·syncAt 커서, `firestore.rules`, `functions/index.js`(Node), 의존성 `firebase-firestore`·`firebase-auth`·`coroutines-play-services` |
| **대체** | supabase-kt(:shared 공용 클라이언트) / Postgres 스키마+RLS / Edge Function(푸시) / `rooms.invite_code` unique 제약(inviteCodes 컬렉션 폐지) |

## 작업 순서

| 단계 | 내용 | 규모 | 산출물 |
|---|---|---|---|
| **S0** | 스키마 + RLS + RPC (SQL 전문 아래) | M | `supabase/schema.sql` |
| **S1** | :shared 공용 동기화 계층 (supabase-kt) | L | `shared/.../supa/` |
| **S2** | 데스크톱 전환 — 폴링 기계 삭제, Realtime 구독 | L | `desktop/` |
| **S3** | 모바일 전환 — SyncManager 재작성 (D1 분할 겸행) | L | `app/sync/` |
| **S4** | 푸시 — Edge Function → FCM HTTP v1 | M | `supabase/functions/push-message/` |
| **S5** | 아바타 — bytea 이식 (Storage는 추후 선택) | S | `AvatarStore`·`DesktopImages` |
| **S6** | (선택) 기존 데이터 이전 스크립트 | S | 일회성 스크립트 |
| **S7** | 정리 — Firebase 의존 제거, 문서 개정, 동시 릴리스 | M | 전반 |

데스크톱을 먼저 하는 이유: 지금 더 열악한 쪽(폴링)이라 이득이 즉시 검증되고, 실패해도
모바일 세션은 무사하다. 단 **S0·S1이 선행 필수** — 순서를 바꾸지 말 것.

---

## S0. 스키마 + RLS + RPC

`supabase/schema.sql`로 저장하고 Dashboard SQL Editor(또는 supabase CLI migration)로 적용.
필드는 `shared/.../Protocol.kt`의 와이어 스키마를 snake_case로 옮긴 것 — Protocol.kt의
"3곳 동시 수정" 문제(Kt 상수/JS/rules)는 이 파일 하나로 수렴된다.

```sql
-- 방. inviteCodes 컬렉션은 폐지 — unique 제약이 A4(초대 코드 충돌)를 DB 차원에서 해결.
create table rooms (
  id              uuid primary key default gen_random_uuid(),
  name            text not null,
  invite_code     text not null unique,
  theme_color     bigint,
  rule            text,
  logs_cleared_at timestamptz,          -- 로그 초기화 전파용 (리뷰 A6과 같은 발상)
  created_at      timestamptz not null default now()
);

create table members (
  room_id      uuid not null references rooms(id) on delete cascade,
  uid          uuid not null,           -- auth.uid()
  joined_at    timestamptz not null default now(),
  platform     text,
  fcm_token    text,
  last_read_at timestamptz,
  typing_until timestamptz,
  typing_name  text,
  characters   jsonb,                   -- 판정 요청 대상 명단 (J0)
  updated_at   timestamptz not null default now(),
  primary key (room_id, uid)
);

create table messages (
  -- id는 클라이언트가 생성한 UUID = 현행 remoteId 원자 선점의 직접 대응.
  -- outbox 재전송은 insert ... on conflict do nothing 으로 멱등.
  id                 uuid primary key,
  room_id            uuid not null references rooms(id) on delete cascade,
  author_uid         uuid not null default auth.uid(),
  type               text not null,     -- TEXT / DICE / SYSTEM / JUDGE
  body               text,
  dice_expr          text,
  dice_outcome       text,
  sender_name        text,
  sender_emoji       text,
  sender_name_color  bigint,
  sender_bubble_color bigint,
  sender_is_gm       boolean not null default false,
  is_ooc             boolean not null default false,
  avatar_id          text,
  judge_target       text,
  judge_ref          uuid,
  created_at         timestamptz not null,            -- 발신 기기 시각 (표시·정렬)
  synced_at          timestamptz not null default now(), -- 서버 시각 (푸시 필터 E8용)
  edited_at          timestamptz
);
create index messages_room_created on messages(room_id, created_at);

-- 아바타: 현행 base64-in-doc 구조의 1:1 이식 (S5). Storage 전환은 추후 선택.
create table avatars (
  room_id uuid not null references rooms(id) on delete cascade,
  hash    text not null,                -- 현행 md5 그대로
  data    bytea not null,               -- 긴 변 256px JPEG
  primary key (room_id, hash)
);
```

### RLS — firestore.rules의 의미 보존 + B1 반영

```sql
alter table rooms    enable row level security;
alter table members  enable row level security;
alter table messages enable row level security;
alter table avatars  enable row level security;

create function is_member(rid uuid) returns boolean
language sql security definer stable as $$
  select exists(select 1 from members where room_id = rid and uid = auth.uid());
$$;

-- rooms: 멤버만 읽기/수정. 목록 열람 차단은 RLS select 조건 자체가 수행.
create policy rooms_select on rooms for select using (is_member(id));
create policy rooms_update on rooms for update using (is_member(id));
-- create/참가는 아래 RPC로만 (직접 insert 정책 없음 = 거부)

-- members: 같은 방 멤버만 읽기(FCM 토큰 포함), 본인 행만 갱신·삭제(탈퇴)
create policy members_select on members for select using (is_member(room_id));
create policy members_update on members for update using (uid = auth.uid());
create policy members_delete on members for delete using (uid = auth.uid());

-- messages: 멤버만 읽기/쓰기. B1 반영 — author_uid 사칭 불가.
create policy messages_select on messages for select using (is_member(room_id));
create policy messages_insert on messages for insert
  with check (is_member(room_id) and author_uid = auth.uid());
create policy messages_update on messages for update
  using (is_member(room_id) and author_uid = auth.uid());
-- 삭제는 멤버 누구나 — '방 로그 초기화'가 상대 메시지도 지워야 하므로 (현행 규칙과 동일)
create policy messages_delete on messages for delete using (is_member(room_id));

create policy avatars_all on avatars for all
  using (is_member(room_id)) with check (is_member(room_id));
```

### RPC — 방 생성·참가·로그 초기화 (security definer)

초대 참가는 "비멤버가 invite_code로 방 한 건을 찾는" 동작이라 RLS select와 충돌한다.
현행 `inviteCodes/{code}` 단건 get의 대응물로 **RPC**를 쓴다 — 방 열거 차단도 그대로 유지된다.

```sql
create function create_room(p_name text, p_invite_code text, p_theme_color bigint, p_rule text)
returns rooms language plpgsql security definer as $$
declare r rooms;
begin
  insert into rooms(name, invite_code, theme_color, rule)
    values (p_name, p_invite_code, p_theme_color, p_rule) returning * into r;
  insert into members(room_id, uid) values (r.id, auth.uid());
  return r;
end $$;
-- unique 위반(23505)이 그대로 클라이언트 에러로 옴 → 새 코드로 재시도 (A4의 올바른 형태)

create function join_room(p_code text) returns rooms
language plpgsql security definer as $$
declare r rooms;
begin
  select * into r from rooms where invite_code = p_code;
  if not found then raise exception 'invalid_code'; end if;
  insert into members(room_id, uid) values (r.id, auth.uid())
    on conflict do nothing;
  return r;
end $$;

create function clear_room_logs(p_room uuid) returns void
language plpgsql security definer as $$
begin
  if not is_member(p_room) then raise exception 'not_member'; end if;
  delete from messages where room_id = p_room;
  update rooms set logs_cleared_at = now() where id = p_room;
end $$;
```

### Realtime 발행 등록

```sql
alter publication supabase_realtime add table messages, members, rooms;
```

- postgres_changes 이벤트에도 RLS가 적용된다 — 멤버가 아닌 방의 이벤트는 오지 않는다.
- **주의**: DELETE 이벤트의 payload에는 기본적으로 PK만 담긴다(`old` 레코드 전체를 받으려면
  `alter table messages replica identity full;` — 메시지는 PK(id)만으로 로컬 삭제가 가능하므로
  기본값 유지).

**검증**: SQL Editor에서 익명 세션 2개로 join_room → 서로의 messages insert가 보이는지,
비멤버 uid로 select 0건인지, author_uid 사칭 insert가 거부되는지 확인.

---

## S1. :shared 공용 동기화 계층

### 의존성 (gradle/libs.versions.toml + shared/build.gradle.kts)

- 플러그인: `kotlin-serialization` (supabase-kt 필수).
- `platform("io.github.jan-tennert.supabase:bom:<latest>")` + `postgrest-kt`, `realtime-kt`,
  `auth-kt`. **supabase-kt 3.x는 Ktor 3 필요.**
- Ktor 엔진은 **모듈별로**: app → `ktor-client-okhttp`, desktop → `ktor-client-cio`
  (Realtime 웹소켓 지원 엔진이어야 함 — CIO 권장). :shared는 엔진 없이 API만.
- 기존 gson(ccfolia 파싱)은 그대로 두어도 충돌 없음.

### 만들 것 (`shared/src/main/kotlin/com/pbp/shared/supa/`)

1. **`Wire.kt`** — `@Serializable` DTO: `RoomRow`/`MemberRow`/`MessageRow`/`AvatarRow`.
   컬럼명은 `@SerialName("snake_case")`. **Protocol.kt의 필드 상수는 이 DTO로 대체**되며
   Protocol.kt에는 타입 값(TEXT/DICE/SYSTEM/JUDGE)과 타이밍 상수만 남긴다.
2. **`SupaClient.kt`** — `createSupabaseClient(url, key) { install(Auth); install(Postgrest);
   install(Realtime) }` 팩토리. url·key는 플랫폼이 주입:
   - app: `local.properties` → BuildConfig 필드 (google-services.json과 같은 취급)
   - desktop: `~/.pbp-desktop/config.json`에 `supabaseUrl`/`supabaseAnonKey` 추가 (`Config.kt`)
   - 익명 로그인 + 세션 영속화: supabase-kt의 세션 저장소 콜백에 플랫폼 저장소를 연결
     (app: SharedPreferences, desktop: config.json). **UID가 바뀌면 남의 메시지가 되므로
     세션 유실 = 재참가 필요** — 현행 익명 인증과 같은 제약, 저장 경로만 다름.
3. **`RoomChannel.kt`** — 방 단위 구독을 Flow로:
   ```
   fun changes(roomId): Flow<Change>   // Insert(row) / Update(row) / Delete(id) / RoomUpdate(row) / MemberUpdate(row)
   suspend fun backfill(roomId, sinceCreatedAt): List<MessageRow>  // 재접속 보정 — 무과금이라 그냥 재조회
   ```
   재연결 시 규칙: **구독 성공 → `backfill(마지막 로컬 created_at - 여유 5분)` → 이후 이벤트 적용.**
   syncAt 커서·윈도 계산은 만들지 않는다 — 그게 이 전환의 목적이다.
4. **`Sender.kt`** — `insertMessage(row)`(on conflict do nothing — outbox 멱등),
   `updateMessage(id, body, editedAt)`, `deleteMessage(id)`, RPC 래퍼(`createRoom`/`joinRoom`/
   `clearRoomLogs`), `upsertMember(...)`(읽음·타이핑·characters·fcm_token).

**검증**: :shared 단위 테스트 — DTO 직렬화 왕복, Change 매핑. 네트워크 통합 확인은 S2에서.

---

## S2. 데스크톱 전환

### 삭제

- `data/Firestore.kt` 전체(688줄 REST + 토큰 갱신 + sendWithRetry).
- `RoomSync.kt`의 Firestore 매핑(→ Wire DTO 사용으로 대체), `Main.kt`의 폴 루프(약 279-422행:
  적응형 주기·재수신 윈도·레거시 스윕·60초 메타 폴), `ui/Dimens.kt`의 폴 주기·윈도·스윕 상수.
- 참가 인사·로그 초기화의 Firestore 쓰기 경로.

### 교체

- `Main.kt`의 방 선택 시: `RoomChannel.changes(roomId)` 구독 → 이벤트를 기존 `messages`
  상태에 적용(Insert=추가, Update=치환, **Delete=제거 — A6이 여기서 자연 해소**),
  `RoomUpdate.logs_cleared_at` 변화 감지 시 로컬·캐시 비우기.
- **RoomCache는 유지** — 시작 직후 즉시 표시용 가치가 여전히 있다. 다만 "증분 재개 커서"
  역할은 폐지하고 backfill이 대신한다.
- 비선택 방: 현행은 아예 폴링하지 않았지만(트레이 알림 없음), 이제 무과금이므로 **참여한
  방 전부 구독**으로 바꿔 트레이 알림 공백을 해소한다(동시 200연결 한도에 2인 앱은 무관).
- 상태 반영은 반드시 `withContext(Dispatchers.Main)` — 리뷰 E4에서 고친 규율 유지.

**검증**: 데스크톱 2 인스턴스(다른 config 디렉터리, `-DconfigDir=` 등으로 분리)로 —
송수신 실시간, 편집·삭제 즉시 반영(**30초 윈도 제약이 사라졌는지**), 로그 초기화 상대 반영,
네트워크 끊고 발신 → 재연결 후 수렴, 재시작 후 캐시 표시 + backfill 보정.

---

## S3. 모바일 전환

`SyncManager.kt`(약 960줄)를 재작성하되, **리뷰 D1의 분할을 이 참에 반영**한다:

| 새 클래스 | 책임 | 현행 대응 |
|---|---|---|
| `MessageSync` | RoomChannel 구독 → Room 반영, outbox 재전송, reconcile | 리스너·아웃박스 부분 |
| `PresenceSync` | 읽음·타이핑·characters — `upsertMember` | observePeerState·pushReadReceipt·pushTyping·pushCharacters |
| `RoomSharing` | create/join/leave/clear — RPC 래퍼 | shareRoom·joinRoom·leaveRoom·wipeMessages |
| `FcmRegistrar` | 토큰 → members.fcm_token | FCM 토큰 등록부 |

- **Room DB는 거의 무변경**: `remoteId`에 UUID 문자열이 들어갈 뿐(스키마 변경 불필요 —
  기존 Firestore 문서 ID도 문자열이었다). `incoming`/`uploaded` 의미 동일.
- **outbox**: 시작 시 `uploaded=0` 조회 → 로컬에서 UUID 확정(`remoteId` 없으면 생성) →
  `insertMessage`(멱등) → `setUploaded`. 현행 claimRemoteId 흐름과 동형.
- **reconcile**: DELETE 이벤트가 실시간으로 오므로 "기준선 대조"는 **재접속 backfill 시
  로컬-서버 대조 1회**로 축소 — 서버 전체 id 목록 조회가 무과금이라 단순 비교로 충분.
- **편집·삭제 전파 실패 재시도 보류(알려진 한계 L5·S7)도 재검토**: postgrest 호출 실패 시
  기존 outbox에 pendingOp를 얹는 비용이 낮아졌지만, 이번 전환 범위에는 넣지 않는다(현행 유지).
- 익명 인증: `ensureAnonAuth`류 → supabase auth로. recoverAuth(N5)·B7의 재구독 훅은
  supabase-kt의 세션 갱신·채널 재구독으로 대응.
- `PbpRepository`의 `var syncManager? = null` 지연 배선은 이 참에 생성자 주입으로(D1 부속).

**검증**: 기존 SyncMappingTest·ReconcileTest를 새 계층에 맞게 이식(멱등 재전송·중복 방지·
수렴 시나리오는 그대로 유효). 에뮬레이터 2대(또는 실기기+에뮬레이터)로 S2와 동일 시나리오 +
모바일↔데스크톱 크로스. **검증 후 에뮬레이터 즉시 종료.**

---

## S4. 푸시 — Edge Function → FCM HTTP v1

클라이언트 수신부(`FcmService`·`MessageNotifier`·google-services.json)는 **무변경**.

1. `supabase/functions/push-message/index.ts` (Deno/TS):
   - 트리거: Database Webhook — `messages` INSERT.
   - 로직(현행 functions/index.js와 동일 정책): `type` 확인 → **`synced_at`이 2분 이내**(E8
     반영 유지 — 서버 시각 기준) → 같은 방 members에서 `uid != author_uid`인 행의
     `fcm_token` 조회 → FCM HTTP v1 발송("○○님의 메시지가 도착했습니다." — 본문 비노출 정책 유지).
   - FCM 인증: Firebase 콘솔 서비스 계정 JSON → `supabase secrets set FCM_SERVICE_ACCOUNT=...`,
     함수 안에서 JWT 서명으로 액세스 토큰 발급(구글 라이브러리 없이 Deno 표준 crypto로 가능).
2. Webhook 등록: Dashboard → Database → Webhooks — `messages` INSERT → 함수 URL.
3. 배포: `supabase functions deploy push-message` (supabase CLI, 사용자 로그인 필요 —
   Cloud Functions 때와 같은 "배포만 사용자 작업" 구도).
4. `functions/`(Node) 디렉터리와 `firebase.json`의 functions 항목은 S7에서 제거.

**검증**: 모바일 앱을 백그라운드로 → 데스크톱에서 발신 → 헤드업 알림 도착. 포그라운드
중복 억제(기존 로직) 동작 확인.

---

## S5. 아바타 — bytea 1:1 이식

- 현행: `rooms/{id}/avatars/{md5}` 문서에 base64. → `avatars` 테이블에 **bytea로 그대로**
  (base64 인코딩·디코딩 단계가 사라져 오히려 단순해진다).
- `app/.../sync/AvatarStore.kt`: 업로드 = `avatars` upsert, resolve = 단건 select.
  해시 키·파일 캐시·바이트 캐시 구조는 무변경.
- `desktop/.../DesktopImages.kt`의 아바타 GET도 동일 교체.
- Supabase Storage 전환(서명 URL·CDN)은 **이번 범위 밖** — 필요해지면 별도 지시서.

---

## S6. (선택) 기존 데이터 이전

새 출발이면 건너뛴다. 이전한다면:

- 일회성 Node 스크립트(기존 `functions/` 의존성 재활용): firebase-admin으로 rooms/messages/
  members/avatars 전량 export → Postgres insert (supabase-js 또는 psql COPY).
- 매핑 주의: Firestore 문서 ID(20자 영숫자)는 UUID가 아니다 — messages.id로 넣을 수 없으므로
  **새 UUID를 발급하고, 양 기기 Room DB의 remoteId도 재매핑**해야 한다. 이 비용 때문에
  새 출발을 권장한다. (이전한다면 차라리 "서버만 이전 + 양 기기 로컬 DB 초기화 후 전량
  다운로드"가 안전하다.)

---

## S7. 정리 + 동시 릴리스

1. 의존성 제거: app — `firebase-firestore`·`firebase-auth`·`coroutines-play-services`
   (**`firebase-messaging`은 유지**). desktop — 자체 REST 관련 잔재.
2. 파일 삭제: `firestore.rules`, `functions/`(Node), `docs/firebase-security.md`는
   `docs/supabase-security.md`(RLS·RPC 설명)로 대체.
3. 문서 개정: `architecture.md`(폴링·syncAt·3곳 동시 수정 단락 전면 개정 — 스키마 출처는
   `supabase/schema.sql` 단일), `deploy.md`(functions → supabase CLI), 리뷰 지시서의
   "알려진 한계" 중 S7(편집 윈도)·데스크톱 삭제 미전파 항목 삭제.
4. **릴리스**: APK(자산명 규칙 `PbP-vXXX.apk`) + 데스크톱 배포판을 **동시에** 상대에게 전달.
   릴리스 노트에 "이 버전부터 서버가 바뀌므로 양쪽 모두 업데이트해야 대화 가능"을 명시.

### 롤백 계획

- S2~S3 동안 Firestore 코드는 **삭제하지 말고 병존**시킨다(전환 커밋과 삭제 커밋 분리 —
  S7에서만 삭제). 문제 시 이전 릴리스 APK·배포판으로 복귀하면 Firestore 데이터는 그대로다.
- 단, **전환 후 Supabase에만 쓰인 메시지는 롤백 시 Firestore에 없다** — 롤백 결정은
  전환 직후(메시지가 쌓이기 전)에만 저비용이라는 점을 인지할 것.

---

## 전체 검증 체크리스트 (S7 완료 기준)

- [ ] 모바일↔데스크톱 실시간 송수신 (지연 체감 — 폴링 2.5초 → 즉시)
- [ ] 편집·삭제가 시간 제약 없이 상대(데스크톱 포함)에 반영 — 구 S7·A6 한계 소멸 확인
- [ ] 로그 초기화가 상대 데스크톱 캐시까지 비움
- [ ] 오프라인 발신 → 재접속 시 outbox 재전송, 중복 0건 (멱등 확인)
- [ ] 판정 요청(JUDGE) 흐름 — 명단 공유(characters)·대상 탭·결과 카드
- [ ] 아바타 업로드·수신·캐시
- [ ] 백그라운드 푸시(헤드업) + 포그라운드 억제
- [ ] RLS: 비멤버 uid로 rooms/messages/members select 0건, author_uid 사칭 insert 거부
- [ ] Supabase 대시보드에서 사용량이 Free 한도 대비 미미함을 확인
- [ ] `gradlew assembleDebug testDebugUnitTest :shared:test :desktop:test` 전체 통과
