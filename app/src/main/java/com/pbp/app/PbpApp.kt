package com.pbp.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.pbp.app.data.AppDatabase
import com.pbp.app.data.PbpRepository
import com.pbp.app.notify.MessageNotifier
import com.pbp.app.sync.SyncManager

class PbpApp : Application() {
    val database by lazy { AppDatabase.build(this) }
    val syncManager by lazy { SyncManager(this, database) }
    val repository by lazy {
        PbpRepository(database).also {
            it.syncManager = syncManager
            syncManager.repository = it
        }
    }
    private val notifier by lazy { MessageNotifier(this) }

    /** 앱이 화면에 떠 있는지 — 비활성 상태에서만 푸시를 띄운다 (스펙 7장) */
    private var resumedActivities = 0
    val isForeground: Boolean get() = resumedActivities > 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumedActivities++ }
            override fun onActivityPaused(activity: Activity) { resumedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        repository // 배선 후 공유된 방들의 수신 리스너 복구
        syncManager.onIncomingMessage = { message, remoteRoomId ->
            // 알림 ID는 FCM 경로와 같은 기준(원격 방 ID 해시) — 이중 알림이 하나로 합쳐진다 (P2-2)
            if (resumedActivities == 0) {
                notifier.notify(
                    message.senderName ?: "상대",
                    remoteRoomId.hashCode(),
                    message.senderImagePath,
                )
            }
        }
        syncManager.start()
    }
}
