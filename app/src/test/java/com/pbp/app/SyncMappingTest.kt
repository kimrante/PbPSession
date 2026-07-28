package com.pbp.app

import com.pbp.app.data.Message
import com.pbp.app.data.MessageType
import com.pbp.app.sync.SyncMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncMappingTest {

    private val message = Message(
        id = 42,
        roomId = 7,
        type = MessageType.DICE,
        body = "3 + 5 = 8",
        diceExpr = "리안 · 2d6",
        senderName = "다이스봇",
        senderEmoji = "🎲",
        senderImagePath = "/data/avatars/x.img",
        senderIsGm = false,
        senderIsBot = true,
        senderNameColor = 0xFFFFC46B,
        senderBubbleColor = 0xFFFFD9A8,
        isOoc = true,
        editedAt = 1_700_000_111_000,
        createdAt = 1_700_000_000_000,
    )

    @Test
    fun `왕복 변환하면 스냅샷 필드가 보존된다`() {
        val map = SyncMapping.toMap(message, authorUid = "device-a")
        val restored = SyncMapping.fromMap("remote-1", map, localRoomId = 99)

        assertEquals(99, restored.roomId)               // 수신 측의 로컬 방 ID
        assertEquals("remote-1", restored.remoteId)
        assertEquals(MessageType.DICE, restored.type)
        assertEquals(message.body, restored.body)
        assertEquals(message.diceExpr, restored.diceExpr)
        assertEquals(message.senderName, restored.senderName)
        assertEquals(message.senderEmoji, restored.senderEmoji)
        assertEquals(message.senderIsBot, restored.senderIsBot)
        assertEquals(message.senderNameColor, restored.senderNameColor)
        assertEquals(message.senderBubbleColor, restored.senderBubbleColor)
        assertEquals(message.isOoc, restored.isOoc)
        assertEquals(message.editedAt, restored.editedAt)
        assertEquals(message.createdAt, restored.createdAt)
        // 이미지는 기기 로컬 경로라 동기화하지 않는다
        assertNull(restored.senderImagePath)
        // 수신 측 저장 시 incoming으로 표시되어 미확인 배지·알림 판정에 쓰인다
        assertEquals(true, restored.incoming)
    }

    @Test
    fun `authorUid가 문서에 실린다`() {
        assertEquals("device-a", SyncMapping.toMap(message, "device-a")["authorUid"])
    }

    @Test
    fun `깨진 데이터는 안전한 기본값으로 복원한다`() {
        val restored = SyncMapping.fromMap("r", mapOf("type" to "GARBAGE"), 1)
        assertEquals(MessageType.TEXT, restored.type)
        assertEquals("", restored.body)
        assertEquals(0L, restored.createdAt)
    }

    @Test
    fun `Firestore가 숫자를 다른 타입으로 돌려줘도 시각을 읽는다`() {
        val restored = SyncMapping.fromMap("r", mapOf("createdAt" to 123), 1)
        assertEquals(123L, restored.createdAt)
    }
}
