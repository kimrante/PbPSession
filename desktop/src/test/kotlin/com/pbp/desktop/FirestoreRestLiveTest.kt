package com.pbp.desktop

import com.pbp.desktop.data.FirestoreRest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실서버 왕복 검증 — 데스크톱 앱의 발신 경로(postMessage)와 수신 경로(listMessages)를
 * UI 없이 그대로 실행한다. (에뮬레이터/실기기와 같은 Live Test 방 사용)
 */
class FirestoreRestLiveTest {

    private val firestore = FirestoreRest(
        projectId = "pbp-session-1195c",
        apiKey = "AIzaSyCTgWzPb62iJ5rASCZ6WEiKi7kwNPVC2m4",
    )
    private val roomId = "5KDJCtliN73xevT2OnnQ"

    @Test
    fun `PC 발신 - 실서버에 메시지를 쓰고 다시 읽는다`() {
        val marker = "PC 데스크톱에서 보냅니다! (${System.currentTimeMillis() % 100000})"
        val posted = firestore.postMessage(
            roomId,
            mapOf(
                "type" to "TEXT",
                "body" to marker,
                "diceExpr" to null,
                "senderName" to "PC유저",
                "senderEmoji" to "🖥️",
                "senderIsGm" to false,
                "senderIsBot" to false,
                "senderNameColor" to 0xFF9FE0B8,
                "senderBubbleColor" to 0xFFCDEED9,
                "isOoc" to false,
                "createdAt" to System.currentTimeMillis(),
                "authorUid" to "desktop-test",
                "avatarId" to null,
            ),
        )
        assertTrue("postMessage 실패", posted)

        val fetched = firestore.listMessages(roomId)
        assertTrue("보낸 메시지가 목록에 없음", fetched.any { it.body == marker })
        // 스키마 필드 왕복 확인
        val mine = fetched.last { it.body == marker }
        assertTrue(mine.senderName == "PC유저")
        assertTrue(mine.senderBubbleColor == 0xFFCDEED9)
    }
}
