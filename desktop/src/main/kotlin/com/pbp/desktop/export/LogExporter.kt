package com.pbp.desktop.export

import com.pbp.desktop.data.Message
import com.pbp.shared.LogExport

/**
 * HTML 로그 내보내기 — 렌더링은 :shared의 [LogExport]가 한다 (리뷰 A3).
 * 여기서는 데스크톱 [Message]를 렌더 모델로 옮기기만 한다.
 * (과거에는 ~200줄이 축자 복제돼 방 아이콘 헤더 같은 차이가 실제로 생겼다.)
 */
object LogExporter {

    fun buildHtml(
        roomName: String,
        messages: List<Message>,
        myUid: String,
        avatarDataUri: (String) -> String?,
    ): String = LogExport.buildHtml(
        roomName = roomName,
        roomIcon = "", // 데스크톱은 방 아이콘을 쓰지 않는다 (아이콘 폐지)
        messages = messages.map { it.toExport(myUid) },
        avatarDataUri = avatarDataUri,
    )

    private fun Message.toExport(myUid: String) = LogExport.ExportMessage(
        type = type,
        body = body,
        createdAt = createdAt,
        mine = authorUid == myUid,
        senderName = senderName,
        senderEmoji = senderEmoji,
        senderIsGm = senderIsGm,
        senderNameColor = senderNameColor,
        senderBubbleColor = senderBubbleColor,
        isOoc = isOoc,
        editedAt = editedAt,
        diceExpr = diceExpr,
        diceOutcome = diceOutcome,
        avatarKey = avatarId,
    )

    fun bytesToDataUri(bytes: ByteArray): String? = LogExport.bytesToDataUri(bytes)
}
