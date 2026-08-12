package com.pbp.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil3.request.allowHardware
import com.pbp.app.data.AppDatabase
import com.pbp.app.data.CharacterProfile
import com.pbp.app.data.MessageType
import com.pbp.app.data.PbpRepository
import com.pbp.app.notify.MessageNotifier
import com.pbp.app.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 하드웨어 비트맵을 끄는 이유(캡처): Coil은 기본으로 이미지를 하드웨어 비트맵으로
 * 디코드하는데, 그런 비트맵은 **소프트웨어 캔버스에 그릴 수 없다**. 캡처는 화면을
 * 찍는 대신 뷰를 소프트웨어 캔버스로 그리므로, 아바타가 하나라도 있으면
 * "Software rendering doesn't support hardware bitmaps"로 렌더가 통째로 실패한다.
 * 아바타는 256px이라 소프트웨어 비트맵으로 두어도 메모리 차이가 사실상 없다.
 */
class PbpApp : Application(), coil3.SingletonImageLoader.Factory {

    override fun newImageLoader(context: coil3.PlatformContext) =
        coil3.ImageLoader.Builder(context)
            .allowHardware(false)
            .build()

    val database by lazy { AppDatabase.build(this) }
    val syncManager by lazy { SyncManager(this, database) }
    val repository by lazy {
        PbpRepository(database).also { repo ->
            repo.syncManager = syncManager
            // 참여 시 로컬 방 생성 능력만 넘긴다 — 상호 참조 대신 단방향 (리뷰 B6)
            syncManager.createLocalRoom = { name, themeColor, backgroundKey, rule, remoteId, code ->
                repo.createRoom(
                    name = name,
                    isMaster = false, // 참여자 표시용 (설정 변경은 누구나 가능)
                    themeColor = themeColor,
                    backgroundKey = backgroundKey,
                    rule = rule,
                    // 원격 연결까지 한 트랜잭션에서 (H6)
                    remoteId = remoteId,
                    inviteCode = code,
                )
            }
        }
    }
    private val notifier by lazy { MessageNotifier(this) }

    /**
     * 앱이 화면에 떠 있는지 — 비활성 상태에서만 푸시를 띄운다 (스펙 7장).
     * 세는 쪽은 메인 스레드지만 읽는 쪽은 **FCM 바인더 스레드**라 @Volatile이 필요하다 (I6)
     */
    @Volatile
    private var resumedActivities = 0
    val isForeground: Boolean get() = resumedActivities > 0

    /**
     * 강제 종료가 나면 스택트레이스를 파일로 남긴다 (Z2).
     *
     * 사이드로드 배포라 Play Console 수집이 없고, 시스템 크래시 버퍼는 재부팅·시간
     * 경과로 사라진다 — 그래서 "캡처 중 꺼졌다" 같은 보고를 확정할 수가 없었다.
     * 파일로 남겨 두면 나중에라도 원인을 짚을 수 있다.
     *
     * **기존 핸들러에 반드시 위임한다.** 빼먹으면 시스템의 크래시 처리("앱이 중지됨"
     * 안내·재시작)가 통째로 사라져, 죽는데 아무 일도 없는 것처럼 보인다.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val dir = java.io.File(filesDir, "logs").apply { mkdirs() }
                // 최근 5개만 — 크래시 루프가 저장 공간을 채우면 병을 키운다
                dir.listFiles()?.sortedByDescending { it.name }?.drop(4)?.forEach { it.delete() }
                java.io.File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(
                    "time=${java.util.Date()}\nthread=${thread.name}\n" +
                        android.util.Log.getStackTraceString(error)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        // 알림 채널을 첫 메시지 때가 아니라 시작할 때 만든다 — 설치 직후에도
        // 시스템 설정에서 알림 방식을 확인·조정할 수 있고, 구 채널 정리도 함께 끝난다
        notifier
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumedActivities++ }
            override fun onActivityPaused(activity: Activity) { resumedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // 과거 버전이 초대 참여 방에도 GM 프로필을 만들었다 — 일회성 교정
        // (서술 권한은 마스터 전용, 정리 후 그 프로필을 가리키던 활성 지정도 해제,
        //  발화 프로필이 하나도 안 남은 참여 방에는 기본 캐릭터를 만들어 준다)
        CoroutineScope(Dispatchers.IO).launch {
            // 통째로 감싼다 (Z1). 여기가 database를 **처음 만지는 지점**이라 Room
            // 마이그레이션도 이 순간 돈다 — 마이그레이션 실패·DB 손상·저장 공간 소진
            // 중 무엇이든 나면 미처리 코루틴 예외로 프로세스가 죽고, onCreate마다
            // 다시 실행되므로 **켤 때마다 죽는 루프**가 됐다.
            // 일회성 교정과 GC는 실패해도 다음 시작에 다시 하면 그만이라 삼켜도 잃는 게 없다
            runCatching {
                // 아무도 가리키지 않는 로컬 이미지 정리 — 교체·취소·방 삭제로 쌓인 고아 (L3).
                // 시작 시점이라 편집 중인 파일이 있을 수 없다
                com.pbp.app.data.ImageGc.sweep(this@PbpApp, database)
                database.profileDao().deleteGmProfilesOfJoinedRooms()
                database.roomDao().clearDanglingActiveProfiles()
                database.roomDao().listJoined().forEach { room ->
                    if (database.profileDao().countForRoom(room.id) == 0) {
                        val playerId = database.profileDao().insert(
                            CharacterProfile(name = "플레이어", roomId = room.id)
                        )
                        database.roomDao().setActiveProfile(room.id, playerId)
                    }
                }
            }.onFailure { android.util.Log.w("PbpApp", "시작 정리 실패", it) }
        }
        repository // 배선 후 공유된 방들의 수신 리스너 복구
        syncManager.onIncomingMessage = { message, remoteRoomId ->
            // 알림 ID는 FCM 경로와 같은 기준(원격 방 ID 해시) — 이중 알림이 하나로 합쳐진다 (P2-2)
            // 프로필 전환 등 SYSTEM 안내는 대화가 아니다 — 알림 제외 (L2-1)
            if (!isForeground && message.type != MessageType.SYSTEM) {
                notifier.notify(
                    message.senderName ?: "상대",
                    remoteRoomId.hashCode(),
                    message.senderImagePath,
                    remoteRoomId = remoteRoomId,
                )
            }
        }
        syncManager.start()
    }
}
