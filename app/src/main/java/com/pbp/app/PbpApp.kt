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
            syncManager.createLocalRoom = { name, themeColor, backgroundKey, rule ->
                repo.createRoom(
                    name = name,
                    isMaster = false, // 참여자 표시용 (설정 변경은 누구나 가능)
                    themeColor = themeColor,
                    backgroundKey = backgroundKey,
                    rule = rule,
                )
            }
        }
    }
    private val notifier by lazy { MessageNotifier(this) }

    /** 앱이 화면에 떠 있는지 — 비활성 상태에서만 푸시를 띄운다 (스펙 7장) */
    private var resumedActivities = 0
    val isForeground: Boolean get() = resumedActivities > 0

    override fun onCreate() {
        super.onCreate()
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
