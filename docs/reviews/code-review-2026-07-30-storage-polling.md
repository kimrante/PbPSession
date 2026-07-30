# 코드 리뷰 보고서 — 리소스 저장 · 서버 폴링 점검 (2026-07-30, v0.4.1)

클린 리뷰 PR 1~6(`:shared` 모듈, 데스크톱 7파일 분할, AvatarStore 추출, 순환 참조 해소)이 반영된 main 최신
기준으로 **리소스 저장(파일 캐시·이미지·설정 영속화)과 서버 폴링**에 이상이 없는지 검증한 결과.
**다른 세션에서 이 문서만 보고 수정 작업을 진행할 수 있도록** 항목마다 위치·재현·수정 방향을 명시한다.
기준 커밋: `d445b26`. 라인 번호는 이 커밋 기준.

## 총평

**전반적으로 이상 없음.** 재구조화는 동작 보존이 확인됐다 — 폴 루프는 분할 전 코드와 바이트 단위 동일
(상수만 `DesktopTiming`으로 명명), AvatarStore는 추출 전 private 메서드와 캐시 키·경로·해시·prefs 키까지
동일(재다운로드/재업로드 물결 없음), perf 지시서의 수정 전부(영구 캐시, 동적 폴 주기, 방별 파일 캐시,
원자적 쓰기, 캐시 상한)가 생존해 있다.

남은 문제는 **중간 2건(M1·M2 — 둘 다 분할 이전부터 있던 것을 충실히 물려받은 것)과 낮음 5건**이다.

권장 수정 순서: **M1 → M2 → L1~L5**. M1은 ~2줄, M2는 소규모라 한 세션에 전부 처리 가능한 분량.

---

## M — 중간 (수정 권장)

### M1. 폴링 중복 윈도가 "직전 간격"이 아닌 "다음 간격" 기준 — 주기 전환 순간 메시지 누락 창 (~2줄)

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:263-272` — 반복 시작 시 `interval` 계산 →
  `windowMs = interval * 2`로 폴링 → `interval`만큼 대기.
- **문제**: 윈도가 흡수해야 할 대상은 **직전 반복의 대기 간격**인데 다음 간격으로 계산된다. 미포커스
  30초(윈도 60초) 폴링 중 포커스 복귀/전송 → 웨이크 루프가 1초 내 탈출 → 다음 반복은 활성(2.5초) 판정 →
  **첫 고속 폴의 윈도가 5초뿐**. 직전 최대 ~30초 공백 동안 시계 오차·늦은 커밋으로 커서보다 5초 이상 과거
  `createdAt`으로 도착한 메시지는 커서가 이미 전진해 있어 영구 누락된다(윈도의 존재 이유가 바로 이 흡수 —
  `Firestore.kt:444-446` 주석 — 인데 전환 순간에만 뚫림).
- **수정 방향**: `prevInterval`(또는 `lastPollAt`)을 루프 변수로 추적해
  `windowMs = maxOf(interval, prevInterval) * 2` (또는 `maxOf(interval * 2, now - lastPollAt + interval)`).
  perf 문서의 "윈도 = 주기×2" 표기도 "윈도 = max(직전, 다음 주기)×2"로 정정.

### M2. 데스크톱 로그 초기화가 IO 스레드에서 폴 소유 상태를 직접 변경 — 지운 로그가 부활하고 파일 캐시에 재영속화

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/Main.kt:423-442`(`resetRoomLogs` — `Dispatchers.IO`에서
  `messages = emptyList()`, `session.messages = emptyList()`, `RoomCacheStore.delete`) vs 폴 병합
  `:276-306`(메인 스레드 — `messages` 읽기 → fetch 서스펜드 → `old.map{...} + fresh` 쓰기).
- **재현**: 폴 반복이 `messages`를 읽고 fetch로 수백 ms 서스펜드한 사이에 리셋의 쓰기가 끼면, 폴이 재개되며
  **리셋 전 목록을 복원**한다(`deletedDocIds`는 `fresh`만 필터, 기존 항목은 안 거름). 이후 30초 스로틀 저장
  (`:299-305`)이 지워진 메시지를 `room-<id>.json`에 다시 써서 **재시작 후에도 부활 유지**. 서버 문서는 이미
  삭제돼 REMOVED 신호도 없으므로 치유 불가.
- **수정 방향**: ① 리셋의 로컬 상태 변경(`messages`/`session.messages`/캐시 삭제)을 UI 스코프로 홉
  (`withContext(Dispatchers.Main)` 또는 상태 변경만 `scope.launch`). ② 방어선으로 폴 병합 시 기존
  `messages`도 `session.deletedDocIds`로 필터 — 어떤 경로로 부활해도 다음 폴에서 자가 치유.
- **부수 (같이 처리 권장)**: 데스크톱 리셋은 `listMessages` 전체 read(N read) 후 **문서별 개별 DELETE 요청**
  (`:426-427`)이라 Android의 P7 수정(사전 read 제거)과 비대칭. 로컬 세션의 `messages` docId 목록으로 삭제하면
  read가 0이 된다(로컬이 모르는 잔여는 서버에 남지만, 방 재입장 전체 로드 시 다시 보이므로 완전성이 중요하면
  현행 유지 — 빈도가 낮아 비용 영향은 작음. 어느 쪽이든 결정만 명시).

---

## L — 낮음 (기회 될 때)

### L1. 방 나가기의 캐시 삭제가 폴 이펙트의 finally 저장과 레이스 — 떠난 방 캐시 파일 영구 잔류

- **위치**: `Main.kt:445-454`(`leaveRoom` — `launch(IO) { RoomCacheStore.delete }`) vs `:341-346`(폴 이펙트
  `finally`의 NonCancellable 저장).
- **재현**: 선택 중인 방을 나가면 이펙트 취소 → finally 저장과 삭제가 동시 실행 → 저장이 늦게 끝나면
  `room-<id>.json` 재생성·영구 잔류(재참가 시 스테일 캐시 로드 — 내용은 서버 진실과 같아 위생 문제).
- **수정 방향**: finally에서 `roomSessions`에 해당 방이 없으면 저장 생략(leaveRoom이 세션을 먼저 제거하므로).

### L2. 손상됐지만 읽히는 원격 아바타 캐시 파일이 영구 재사용됨

- **위치**: `desktop/src/main/kotlin/com/pbp/desktop/DesktopImages.kt:190-202`(`fetchAvatarCached`) +
  `ChatPane.kt:596-611`.
- **재현**: 캐시 파일 바이트는 읽히는데 Skia 디코드가 실패하면, "실패는 캐시하지 않는다" 재시도가 같은 손상
  파일을 무한 재독 → 해당 아바타 영구 이모지 폴백.
- **수정 방향**: 디코드 실패 시 캐시 파일 삭제 1줄 → 다음 시도에서 재fetch.

### L3. 로컬 이미지 고아 누적 (양 플랫폼, 기존 문제 — 규모상 여유 있을 때)

- **Android**: 프로필 이미지 교체 시 이전 파일 미삭제(`ProfileEditScreen.kt:92`), 크롭 후 편집 취소 고아
  (`ImageCrop.kt:184`), 오너 이미지 재선택 미정리(`ProfileDialogs.kt:166`), `deleteRoom`이 방 배경·원격
  아바타 캐시 파일 미정리(`PbpRepository.kt:81-86`).
- **데스크톱**: `avatars-local/`·`owner/` 교체 시 미정리(`ProfileOverlays.kt:194, 611`) — 배경만 정리 존재
  (`Main.kt:687-693`).
- **규모**: 아바타 50~150KB·배경 200~600KB — 취미 앱 기준 연간 수 MB. 급하지 않음.
- **수정 방향**: 교체 시 이전 파일이 앱 디렉터리 내부이고 다른 참조가 없으면 삭제. `deleteRoom`/`leaveRoom`에
  방 배경·`remote-*` 파일 정리 추가.

### L4. 문서-코드 불일치: 데스크톱 `uploadedAvatarKeys`가 메모리 전용

- **위치**: `DesktopImages.kt:205-206`. perf 문서 헤더(반영 현황)는 "uploadedAvatars 영속화" 완료로 기재.
- **영향**: 실행당 방별 멱등 재업로드 1회(문서 ID=내용 해시라 데이터 이상 없음, 쓰기 1회 낭비).
- **수정 방향**: config 옆에 키 집합 영속화, 또는 perf 문서 헤더에서 "(데스크톱은 메모리 한정)" 단서로 정정 —
  어느 쪽이든 문서와 코드를 일치시킬 것.

### L5. Android 아바타 복원 실패 시 `.tmp` 잔류

- **위치**: `app/src/main/java/com/pbp/app/sync/AvatarStore.kt:82` — `tmp.writeBytes` 예외(디스크 풀) 시
  부분 파일이 남음(`renameTo` 실패 분기만 삭제 처리).
- **수정 방향**: 쓰기+rename을 감싸 실패 경로에서 `tmp` 삭제 1줄.

### 관찰 (조치 불요, 기록만)

- `RoomCache`는 크기 상한 없이 활성 채팅 30초마다 전체 JSON 재작성(`RoomCache.kt:33-52`) — 현 규모(수천 건)
  에서는 문제 없음. 방이 1만+ 건에 도달하면 증분 저장 또는 상한 재검토.
- `avatars-remote/` 디스크 캐시는 무한 증식(파일당 10~30KB, 내용 해시 키) — L3와 함께 정리하면 됨.

---

## 검증 정상 (재확인 불필요 — 실제 추적 완료)

### 폴링 (데스크톱)
- 주기 사양 일치: 활성 2.5s/유휴 20s/미포커스 30s/활동 윈도 120s/메타 폴 60s/웨이크 1s/메타 프리즈 15s —
  전부 `ui/Dimens.kt:38-53`의 명명 상수로 배선 확인. 전송·포커스 복귀 시 1초 내 고속 복귀 확인.
- 커서 규율: HTTP/파싱 오류 → null → 커서 미전진·다음 폴 재시도. docId dedup + `deletedDocIds` 필터,
  편집 병합은 더 새로운 `editedAt`만 수용.
- 방 전환 시 취소·재시작 청결, 방별 `RoomSession` 격리, 취소된 fetch의 스테일 쓰기 불가(메인 디스패치 확인).
- 방 미선택 시 폴링 없음, 창 종료 시 컴포지션과 함께 취소.
- 반복 1회 전체 runCatching 격리 — 예외 1건이 폴링을 영구 정지시키지 않음.

### 트래픽 (Android — perf 수정 7건 생존 확인)
- PersistentCache(메모리 캐시 오버라이드 전무 — grep 확인), P4 UPDATE 스킵+`withTransaction` 일괄,
  M1 allIds 게이트(`reconcilePending`), P7 wipe 사전 read 제거(60s 타임아웃 유지), L4 기준선-선행 순서
  (기준선 조회 후 리스너 등록), attach `putIfAbsent` 단일 등록, FCM 방별 쓰기(`force=true` 호출자 전무),
  재인증 백오프 상한(120s)·시도 카운터 리셋. start()는 attach-후-outbox 순서인데 이는 의도된 설계
  (기준선이 uploaded=1 집합이라 이후 push는 오판 불가 — 주석 근거 확인).
- 재구조화 diff는 순수 기계적: AvatarStore는 추출 전과 바이트 동일(MD5·키 형식 `room/hash`·경로·prefs 키·
  256px/q82 파라미터), createLocalRoom 람다 주입은 호출 순서 무변경. **추가 read/write 0.**

### 저장 (양쪽)
- `RoomCache`: tmp+`ATOMIC_MOVE`(+플레인 move 폴백), 손상 JSON → 크래시 없이 캐시 없음 취급 → 전체 재조회,
  커서 동반 저장으로 재시작 시 증분 재개, Gson null 필드 방어.
- `Config`: 원자적 쓰기 + `.bak` 백업 유지, `replaceAndSnapshot`/`snapshot`이 유일한 변경/직렬화 경로
  (분할 후 우회 호출자 없음 — grep 확인).
- Android `AvatarStore`: tmp+rename으로 부분 쓰기 캐시 불가, prefs 영속화 생존, `MessageNotifier` 아바타
  캐시(lastModified 무효화) 유지.
- 비트맵 캐시 상한 구현 확인: 배경 8(`RoomListPane.kt:264, 328`), 아바타 64+in-flight dedup
  (`ChatPane.kt:596-610`). 배경 고아 정리의 경로 재파생 위험은 `AppPaths` 통합으로 해소.
- Room DB에 블롭 저장 없음(경로 문자열만), 메시지 미정리는 설계상 의도.

## 테스트 권고

- M1: 윈도 계산을 순수 함수로 추출하면 "30s→2.5s 전환 첫 폴의 윈도 ≥ 직전 간격" 단위 테스트 가능.
- M2: 수정 후 "리셋 직후 폴 1회 강제 실행 → messages 빈 상태 유지 + 캐시 파일 부재" 수동 시나리오 확인.
