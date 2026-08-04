package com.pbp.app.export

import com.pbp.app.data.Message
import com.pbp.shared.LogExport
import java.io.File

/**
 * HTML 로그 내보내기 — 렌더링은 :shared의 [LogExport]가 하고, 여기서는
 * Android [Message]를 렌더 모델로 옮기고 아바타 파일을 data URI로 바꾼다 (리뷰 A3).
 */
object LogExporter {

    fun buildHtml(
        roomName: String,
        roomIcon: String,
        messages: List<Message>,
        avatarDataUri: (String) -> String? = ::fileToDataUri,
    ): String = LogExport.buildHtml(
        roomName = roomName,
        roomIcon = roomIcon,
        messages = messages.map { it.toExport() },
        avatarDataUri = avatarDataUri,
    )

    /** 서식 없는 원문 — 규칙은 :shared가 단일 출처 (PC와 같은 파일이 나온다) */
    fun buildText(roomName: String, messages: List<Message>): String =
        LogExport.buildText(roomName, messages.map { it.toExport() })

    private fun Message.toExport() = LogExport.ExportMessage(
        type = type.name,
        body = body,
        createdAt = createdAt,
        mine = !incoming,
        senderName = senderName,
        senderEmoji = senderEmoji,
        senderIsGm = senderIsGm,
        senderNameColor = senderNameColor,
        senderBubbleColor = senderBubbleColor,
        senderTextColor = senderTextColor,
        isOoc = isOoc,
        editedAt = editedAt,
        diceExpr = diceExpr,
        diceOutcome = diceOutcome,
        avatarKey = senderImagePath,
    )

    /** 로컬 이미지 파일을 데이터 URI로 */
    fun fileToDataUri(path: String): String? = runCatching {
        LogExport.bytesToDataUri(File(path).readBytes())
    }.getOrNull()

    // 테스트·호출부가 쓰는 순수 헬퍼 위임
    fun markupHtml(text: String): String = LogExport.markupHtml(text)
    fun escape(text: String): String = LogExport.escape(text)
    fun hex(argb: Long): String = LogExport.hex(argb)
}
