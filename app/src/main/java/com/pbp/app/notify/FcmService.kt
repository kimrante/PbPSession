package com.pbp.app.notify

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.pbp.app.PbpApp

/**
 * FCM 백그라운드 푸시 수신 (스펙 7장).
 * Cloud Functions(functions/index.js)가 새 메시지마다 데이터 푸시를 보내면,
 * 앱이 꺼져 있어도 여기서 받아 "~님의 메시지가 도착했습니다." 알림을 띄운다.
 * 채팅 본문은 푸시 데이터 자체에 담지 않는다 — 어떤 경로로도 본문 비노출.
 */
class FcmService : FirebaseMessagingService() {

    // 메시지마다 새로 만들면 알림 채널을 매번 재생성한다 (C18)
    private val notifier by lazy { MessageNotifier(this) }

    override fun onNewToken(token: String) {
        (application as PbpApp).syncManager.onNewFcmToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as PbpApp
        // 앱이 화면에 떠 있으면 Firestore 리스너가 이미 처리하므로 중복 알림을 막는다
        if (app.isForeground) return
        // 프로세스가 살아 있고 이 방의 리스너가 붙어 있으면 리스너 경로가 알림을 담당 (P2-2)
        val remoteRoomId = message.data["roomId"]
        if (remoteRoomId != null && app.syncManager.isAttached(remoteRoomId)) return
        val senderName = message.data["senderName"] ?: "상대"
        notifier.notify(senderName, remoteRoomId?.hashCode() ?: 0, imagePath = null)
    }
}
