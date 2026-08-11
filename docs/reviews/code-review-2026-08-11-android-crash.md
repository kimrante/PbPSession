# 코드 리뷰 보고서 — 안드로이드 앱 전반 크래시 점검 + 강제 종료 로그 (2026-08-11, v0.16.0)

**v0.16.0(`eb97076`)** 기준. 캡처 모드 리뷰(2026-08-10)에 이어, **캡처 외의 안드로이드
모바일 전 영역**을 "앱이 꺼질 수 있는 상황" 관점으로 정독한 결과다. 시작·수신·전송·
DB·이미지·내보내기·알림·파서 경로를 전부 훑었고, **강제 종료 시 로그가 남는지도 함께
확인했다**(결론: 남지 않는다 — Z2). 다른 세션에서 이 문서만 보고 수정 작업을 진행할 수
있도록 항목마다 위치·재현·수정 방향을 명시한다. 라인 번호는 `eb97076` 기준.
항목 번호는 이전 라운드(A~F, G/H/I/K, X/Y)와 겹치지 않게 **Z**를 쓴다.

## 총평

- **전반적으로 방어가 잘 되어 있다.** 파일 IO·비트맵·내보내기·수신 파싱은 거의 전부
  `runCatching` 아래에 있고, `!!`는 전수 확인 결과 모두 가드 안이다(문서 끝 "확인 종결"
  참조). 남은 크래시 리스크는 **코루틴 예외 처리의 구멍** 두 곳(Z1·Z3)에 몰려 있다.
- **가장 위험한 것은 Z1** — 앱 시작 정리 코루틴이 무보호라, DB가 한 번 나쁜 상태가 되면
  (디스크 풀·손상·마이그레이션 실패) **시작할 때마다 죽는 크래시 루프**가 된다. 이 지점이
  DB를 처음 여는 곳(마이그레이션 실행 지점)이기도 해서 더 아프다.
- **강제 종료 로그는 현재 아무 데도 남지 않는다 (Z2).** `UncaughtExceptionHandler`·
  Crashlytics류 수집 장치가 코드베이스에 없다(전수 grep 확인). 크래시 직후 PC에 연결해
  `adb logcat --buffer=crash`로 볼 수 있을 뿐, 시간이 지나면 증거가 사라진다.
  사용자 보고("캡처 중 꺼짐")를 확정하지 못한 이유이기도 하다 — **Z2를 먼저 넣으면
  다음 보고부터는 스택트레이스가 남는다.**

권장 수정 순서: **Z1 → Z2 → Z3 → (낮음) Z4·Z5**. Z1·Z2는 각각 몇 줄이라 한 커밋에
묶어도 된다.

---

## Z — 수정 항목

### Z1. [높음] 앱 시작 정리 코루틴이 무보호 — DB 예외 한 번이면 시작 크래시 루프

- **위치**: `app/src/main/java/com/pbp/app/PbpApp.kt:78-92`

  ```kotlin
  CoroutineScope(Dispatchers.IO).launch {
      com.pbp.app.data.ImageGc.sweep(this@PbpApp, database)   // 내부 runCatching — 안전
      database.profileDao().deleteGmProfilesOfJoinedRooms()    // ← 무보호
      database.roomDao().clearDanglingActiveProfiles()         // ← 무보호
      database.roomDao().listJoined().forEach { room -> … }    // ← 무보호 (insert 포함)
  }
  ```

- **재현**: 이 `CoroutineScope`에는 예외 핸들러가 없다. `ImageGc.sweep`은 내부에서
  삼키지만 이어지는 DAO 호출들은 그대로다. 여기가 `database`를 **처음 만지는 지점**이라
  Room 마이그레이션(AppDatabase.kt:123-130)도 이 순간 돈다. 마이그레이션 실패·
  `SQLiteDatabaseCorruptException`·`SQLiteFullException`(저장 공간 소진 — 이미지까지
  저장하는 앱이라 현실적이다) 중 무엇이든 나면 미처리 코루틴 예외 → 기본 핸들러 →
  **프로세스 즉사**. onCreate마다 다시 실행되므로 **앱을 켤 때마다 죽는 루프**가 된다.
- **수정**: 블록 전체를 `runCatching`으로 감싸고 `Log.w("PbpApp", "시작 정리 실패", it)`.
  일회성 교정·GC는 실패해도 다음 시작에 다시 시도하면 그만이라, 삼켜도 잃는 것이 없다.
  (DB가 정말 죽어 있으면 이후 화면 경로에서 드러나고, Z2의 크래시 로그가 원인을 남긴다.)
- **검증**: 수정 후 단위 재현이 어렵다 — 코드 리뷰로 가드 존재 확인 + 기존
  `ImageGcTest` 통과를 기준으로 한다.

### Z2. [높음] 강제 종료 로그가 아무 데도 남지 않는다 — 크래시 브레드크럼 추가

- **확인 결과**: `UncaughtExceptionHandler`·Crashlytics·자체 파일 로깅 전부 **없음**
  (저장소 전수 grep). 크래시 스택은 시스템 logcat 크래시 버퍼에만 잠시 남는다 —
  재현 직후 `adb logcat --buffer=crash`로만 볼 수 있고, 기기 재부팅·시간 경과로 사라진다.
  사이드로드 배포라 Play Console 자동 수집도 없다. **"캡처 중 꺼짐" 같은 보고를 영영
  확정할 수 없는 구조다.**
- **위치**: `app/src/main/java/com/pbp/app/PbpApp.kt` `onCreate` 맨 앞(다른 초기화보다 먼저)
- **수정**: 기본 핸들러를 **체인**해 파일로 남기고 반드시 위임한다 — 위임을 빼먹으면
  시스템 크래시 처리(재시작·"앱이 중지됨" 안내)가 사라진다.

  ```kotlin
  val previous = Thread.getDefaultUncaughtExceptionHandler()
  Thread.setDefaultUncaughtExceptionHandler { thread, e ->
      runCatching {
          val dir = java.io.File(filesDir, "logs").apply { mkdirs() }
          // 최근 5개만 유지 — 크래시 루프가 저장소를 채우지 않게
          dir.listFiles()?.sortedByDescending { it.name }?.drop(4)?.forEach { it.delete() }
          java.io.File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(
              "time=${java.util.Date()}\nthread=${thread.name}\n" +
                  android.util.Log.getStackTraceString(e)
          )
      }
      previous?.uncaughtException(thread, e)
  }
  ```

  회수 방법(문서로 충분, 화면 작업 불요): 디버그 빌드는
  `adb shell run-as com.pbp.app cat files/logs/crash-*.txt`. 릴리스 빌드에서 사용자가
  직접 보낼 수 있게 하려면 "지난 종료 로그 공유" 버튼이 필요하지만 **이번 범위에서는
  제외** — 파일이 남는 것부터가 목적이다.
- **검증**: 디버그 빌드에 임시 강제 크래시(개발 중에만, 예: 숨은 버튼에서 `error()`)를
  넣어 파일 생성·5개 유지·시스템 크래시 안내 유지를 확인하고 임시 코드는 제거한다.

### Z3. [중간·계통] ViewModel 코루틴의 DB 쓰기가 전부 무보호 — 디스크 풀이면 전송·저장마다 크래시

- **위치** (쓰기 경로 전수):
  - `ChatScreen.kt`(ChatViewModel) — `send` :231, `edit` :247, `delete` :251,
    `markRead` :158, `switchTo` :242, `sendJudgeRequest` :169, `rollJudge` :175,
    `addStatAndRoll` :179, `createFromCode` :253, `notifyTyping` :150(내부 DB get),
    `init`의 `profiles.collect` :161-167
  - `RoomListScreen.kt` — `createRoom` :95, `deleteRoom` :99, `createFromCode` :110
  - `RoomSettingsScreen.kt` — `setThemeColor` :75, `setBackground` :77,
    `importBackground` :79(이미지 처리는 안전, 이어지는 `repo.setBackground`가 무보호)
  - `ProfileEditScreen.kt` — `save` :93, `delete` :109
- **재현**: 전부 `viewModelScope.launch { repo.… }` 꼴이고 repo는 Room에 바로 쓴다.
  `SQLiteFullException`(저장 공간 소진)·`SQLiteDatabaseCorruptException`이 나면 잡는
  곳이 없어 앱이 죽는다. 이미지·캡처 PNG까지 쌓는 앱이라 저장 공간 소진은 언젠가
  실제로 온다. **그때 증상이 정확히 "메시지를 보내면/설정을 바꾸면 앱이 꺼진다"다.**
  (수신 경로는 이미 격리돼 있다 — SyncManager.attach:771-776, processSnapshot:812-843.
  발신·설정 쪽만 구멍이다.)
- **수정**: 헬퍼 하나로 일괄 치환. 새 파일 `ui/common/SafeLaunch.kt`:

  ```kotlin
  /** DB·동기화 쓰기용 launch — 실패를 크래시 대신 로그+토스트로 (Z3) */
  internal fun androidx.lifecycle.ViewModel.safeLaunch(
      app: android.app.Application,
      block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit,
  ) = androidx.lifecycle.viewModelScope.launch(
      kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
          android.util.Log.w("Pbp", "작업 실패", e)
          android.os.Handler(android.os.Looper.getMainLooper()).post {
              android.widget.Toast.makeText(
                  app, "작업을 완료하지 못했습니다 — 저장 공간을 확인해 주세요",
                  android.widget.Toast.LENGTH_SHORT,
              ).show()
          }
      },
      block = block,
  )
  ```

  위 목록의 `viewModelScope.launch`를 `safeLaunch(app)`로 바꾼다(각 VM은 이미 `app`을
  들고 있다). **읽기 Flow(`stateIn`·`collectAsState`)는 대상이 아니다** — Room이 스스로
  관리하고, 실패 시 크래시 루프가 아니라 재구독 문제라 성격이 다르다. `init`의
  `profiles.collect`는 collect 자체를 safeLaunch로 감싼다.
- **검증**: "전송 실패 시 크래시 대신 토스트"를 목표로, 계측 없이 확인하려면
  repo에 임시로 예외를 던지는 디버그 분기를 넣어 send → 토스트 → 앱 생존을 확인 후
  제거. 기존 단위 테스트 전체 통과.

### Z4. [낮음] PbpMarkup 파서의 O(n²) 꼬리 결합 — 초장문 수신 메시지에서 ANR(시스템 강제 종료)

- **위치**: `shared/src/main/kotlin/com/pbp/shared/PbpMarkup.kt:72`

  ```kotlin
  val tail = pieces.drop(index + 1).filterIsInstance<String>().joinToString("")
  ```

- **재현**: 조각(값 `{{ }}`·루비)마다 **뒤쪽 전체 문자열을 새로 결합**한다. 본문에
  마커가 k개면 O(k×본문길이)의 문자열 복사다. 메시지 길이 제한이 없으므로(Firestore
  문서 상한 1MB) 상대가 마커 수백 개짜리 초장문을 보내면 첫 컴포지션(메인 스레드)이
  수 초 이상 멎고, 시스템이 ANR로 **앱을 강제 종료**한다 — 사용자 눈에는 "메시지를
  받았더니 앱이 꺼졌다"로 보이는 경로다. 2인용 앱이라 상대가 악의적일 가능성은 낮아
  낮음으로 두지만, 실수(긴 문서 붙여넣기 + 값 치환)로도 체감 렉은 생긴다.
- **수정**: 꼬리를 매번 만들지 않는다 — 뒤에서부터 접미사를 한 번 누적해 두고 조각별로
  꺼내 쓴다(O(n)). `parse` 시작에서 `pieces` 확정 후:

  ```kotlin
  val suffixes = arrayOfNulls<String>(pieces.size)
  var acc = ""
  for (i in pieces.indices.reversed()) {
      suffixes[i] = acc
      if (pieces[i] is String) acc = pieces[i] as String + acc
  }
  // 루프 안: val tail = suffixes[index]!!
  ```

  동작은 동일하다(기존 `PbpMarkupTest`가 그대로 통과해야 한다).
- **검증**: `PbpMarkupTest` 전체 통과 + (선택) 마커 500개×2,000자 입력의 파싱 시간이
  수십 ms 안쪽인지 JVM 테스트로 확인.

### Z5. [낮음] ScenarioFetcher가 IOException만 잡는다 — 비-IO 예외는 뷰어를 여는 순간 크래시

- **위치**: `app/src/main/java/com/pbp/app/data/ScenarioFetcher.kt:59-92` — `try`가
  `IOException`만 잡고, 호출부(`ChatViewModel.loadScenario`, ChatScreen.kt:332-334)도
  감싸지 않는다.
- **재현**: `HttpURLConnection` 계열은 드물게 `RuntimeException`(제조사 네트워크 스택
  버그, 프록시 설정 이상 등)을 던진다. 그러면 viewModelScope 미처리 예외로 앱이 죽는다.
  발생 확률은 낮지만, 고치는 비용이 더 낮다.
- **수정**: `loadScenario`의 호출을 감싼다 —
  `runCatching { ScenarioFetcher.fetch(url) }.getOrDefault(Result.Error.NETWORK)`.
  fetch 내부를 고치는 것보다 호출부 한 줄이 싸고, G1(레거시 API 크래시)과 같은 계열의
  마지막 구멍을 막는다.
- **검증**: 기존 시나리오 뷰어 흐름(정상·권한 없음·네트워크 오류) 수동 확인.

---

## 확인하고 문제없음으로 종결한 것 (전수 점검 기록)

- **`!!` 전수 확인** — `CaptureSaver.kt:70`·`RoomSettingsScreen.kt:112·124·140`은
  `runCatching` 안(NPE도 실패 처리로 흡수), `MainActivity.kt:112·118·127`은 NavType이
  보장하는 인자, `JudgeRequestSheet.kt:195`는 `enabled = statName != null` 가드 뒤,
  `ProfileEditScreen.kt:261·476`·`OwnerProfileScreen.kt:174`는 null 검사 분기 안.
- **이미지 경로** — `Images.importDownscaled`·`ImageCrop.loadBitmap/cropToFile`·
  `CaptureSaver` 전부 `runCatching`. EXIF·샘플링·알파 처리 포함 견고하다.
- **수신 경로** — `SyncMapping.fromMap`이 타입·널 방어(`MessageType.valueOf`도
  runCatching→TEXT 폴백), 문서 1건 예외는 `processSnapshot`이 건별 격리, 스냅샷 전체
  실패도 attach 루프가 삼킨다. 알림(`MessageNotifier`) 예외도 같은 격리 안이다.
- **다이스·판정** — `DiceBot`은 개수 상한 20·지원 면수 화이트리스트라 상대가 보낸
  판정식(`diceExpr`)으로도 폭주시킬 수 없다. `ProfileStats`·`Rules`도 안전.
- **내보내기** — `PdfExporter`는 전 구간 `runCatching`(WebView 미탑재 기기의
  `AndroidRuntimeException`, `android.print` 패키지 트릭이 특정 기기에서 실패하는
  경우까지 Throwable로 잡힌다). `LogExporter`는 순수 문자열 조립.
- **DB** — 마이그레이션 1→11 전 단계 등록 확인(빠진 구간 없음). `Converters.toMessageType`의
  `valueOf`는 다운그레이드 설치에서만 문제인데 Room 버전 검사가 먼저 막는다.
- **클립보드 캐릭터 코드**(`CharacterCodec`)·설정 로드(`OwnerProfile`·`RecentColors`·
  `CaptureSettings`·`ScenarioSettings`·`AppFonts`) — 파싱 전부 방어적(`toLongOrNull` 등).
- **G1(API 33 `readNBytes` 즉사)** — 수동 8KB 루프로 교체돼 있음을 재확인했다.
