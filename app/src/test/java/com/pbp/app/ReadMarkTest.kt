package com.pbp.app

import com.pbp.app.data.Message
import com.pbp.app.data.MessageType
import com.pbp.app.ui.chat.readMarkTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "읽음" 배지를 어느 메시지에 붙일지 — 배지를 그릴 자리가 없는 종류는 건너뛴다 (리뷰 R3) */
class ReadMarkTest {

    private fun message(
        id: Long,
        createdAt: Long,
        type: MessageType = MessageType.TEXT,
        body: String = "안녕",
        incoming: Boolean = false,
        isOoc: Boolean = false,
        isGm: Boolean = false,
    ) = Message(
        id = id,
        roomId = 1,
        type = type,
        body = body,
        senderName = if (isGm) "GM" else "이단",
        senderIsGm = isGm,
        isOoc = isOoc,
        incoming = incoming,
        createdAt = createdAt,
    )

    @Test
    fun `상대가 읽지 않았으면 배지 없음`() {
        val messages = listOf(message(1, 100))
        assertNull(readMarkTarget(messages, null))
        assertNull(readMarkTarget(messages, 99))
    }

    @Test
    fun `읽은 내 메시지 중 최신에 붙는다`() {
        val messages = listOf(message(1, 100), message(2, 200), message(3, 300))
        assertEquals(2L, readMarkTarget(messages, 250))
        assertEquals(3L, readMarkTarget(messages, 300))
    }

    @Test
    fun `상대 메시지는 후보가 아니다`() {
        val messages = listOf(message(1, 100), message(2, 200, incoming = true))
        assertEquals(1L, readMarkTarget(messages, 300))
    }

    @Test
    fun `마지막이 다이스면 직전 말풍선으로 물러난다`() {
        val messages = listOf(
            message(1, 100),
            message(2, 200, type = MessageType.DICE, body = "1d100 → 42"),
        )
        assertEquals(1L, readMarkTarget(messages, 300))
    }

    @Test
    fun `잡담과 시스템 메시지도 건너뛴다`() {
        val messages = listOf(
            message(1, 100),
            message(2, 200, isOoc = true),
            message(3, 300, type = MessageType.SYSTEM),
        )
        assertEquals(1L, readMarkTarget(messages, 400))
    }

    @Test
    fun `인용 없는 GM 서술은 말풍선이 없어 건너뛴다`() {
        val messages = listOf(
            message(1, 100),
            message(2, 200, isGm = true, body = "등대의 불빛이 흔들렸다."),
        )
        assertEquals(1L, readMarkTarget(messages, 300))
    }

    @Test
    fun `인용이 있는 GM 발화는 후보다`() {
        val messages = listOf(
            message(1, 100),
            message(2, 200, isGm = true, body = "노인이 말했다. \"돌아가게.\""),
        )
        assertEquals(2L, readMarkTarget(messages, 300))
    }
}
