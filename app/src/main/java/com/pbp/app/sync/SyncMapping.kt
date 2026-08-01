package com.pbp.app.sync

import com.pbp.app.data.Message
import com.pbp.app.data.MessageType

/** 로컬 Message ↔ Firestore 문서 필드 변환. 순수 함수라 JVM 테스트 가능. */
object SyncMapping {

    fun toMap(message: Message, authorUid: String, avatarId: String? = null): Map<String, Any?> = mapOf(
        "avatarId" to avatarId,
        "type" to message.type.name,
        "body" to message.body,
        "diceExpr" to message.diceExpr,
        "diceOutcome" to message.diceOutcome,
        "senderName" to message.senderName,
        "senderEmoji" to message.senderEmoji,
        "senderIsGm" to message.senderIsGm,
        "senderIsBot" to message.senderIsBot,
        "senderNameColor" to message.senderNameColor,
        "senderBubbleColor" to message.senderBubbleColor,
        "senderTextColor" to message.senderTextColor,
        "isOoc" to message.isOoc,
        "editedAt" to message.editedAt,
        "judgeTarget" to message.judgeTarget,
        "judgeRef" to message.judgeRef,
        "createdAt" to message.createdAt,
        // 커밋 시점의 서버 시계 — 오프라인 큐에 오래 머물러도 이 값은 실제 도착 순서다.
        // 데스크톱 폴 커서가 이 필드를 본다 (V1)
        "syncAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
        "authorUid" to authorUid,
    )

    /** 수신 메시지 복원. incoming=true로 저장되어 미확인 배지·알림 판정에 쓰인다. */
    fun fromMap(remoteId: String, data: Map<String, Any?>, localRoomId: Long): Message = Message(
        roomId = localRoomId,
        type = runCatching { MessageType.valueOf(data["type"] as? String ?: "") }
            .getOrDefault(MessageType.TEXT),
        body = data["body"] as? String ?: "",
        diceExpr = data["diceExpr"] as? String,
        diceOutcome = data["diceOutcome"] as? String,
        senderName = data["senderName"] as? String,
        senderEmoji = data["senderEmoji"] as? String,
        senderImagePath = null,
        senderIsGm = data["senderIsGm"] as? Boolean ?: false,
        senderIsBot = data["senderIsBot"] as? Boolean ?: false,
        senderNameColor = (data["senderNameColor"] as? Number)?.toLong(),
        senderBubbleColor = (data["senderBubbleColor"] as? Number)?.toLong(),
        senderTextColor = (data["senderTextColor"] as? Number)?.toLong(),
        isOoc = data["isOoc"] as? Boolean ?: false,
        judgeTarget = data["judgeTarget"] as? String,
        judgeRef = data["judgeRef"] as? String,
        editedAt = (data["editedAt"] as? Number)?.toLong(),
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L,
        remoteId = remoteId,
        incoming = true,
        uploaded = true, // 서버에서 온 메시지 — 아웃박스 재전송 대상 아님
    )
}
