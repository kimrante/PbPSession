package com.pbp.app

import com.pbp.app.data.Message
import com.pbp.app.data.MessageType
import com.pbp.app.export.LogExporter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogExporterTest {

    private fun message(
        type: MessageType = MessageType.TEXT,
        body: String = "안녕",
        name: String? = "이단",
        isGm: Boolean = false,
        isOoc: Boolean = false,
        incoming: Boolean = false,
        nameColor: Long? = 0xFFFFC46B,
        bubbleColor: Long? = 0xFFFFD9A8,
        diceExpr: String? = null,
        imagePath: String? = null,
        editedAt: Long? = null,
        createdAt: Long = 1_700_000_000_000,
    ) = Message(
        roomId = 1,
        type = type,
        body = body,
        diceExpr = diceExpr,
        senderName = name,
        senderEmoji = "단",
        senderImagePath = imagePath,
        senderIsGm = isGm,
        senderNameColor = nameColor,
        senderBubbleColor = bubbleColor,
        isOoc = isOoc,
        incoming = incoming,
        editedAt = editedAt,
        createdAt = createdAt,
    )

    @Test
    fun `다중행 본문의 줄바꿈이 보존된다 - pre-wrap`() {
        val html = LogExporter.buildHtml("방", "🎲", listOf(message(body = "첫 줄\n둘째 줄")))
        // 줄바꿈 문자가 본문에 그대로 있고, CSS가 pre-wrap으로 렌더링한다 (P2-7)
        assertTrue(html.contains("첫 줄\n둘째 줄"))
        assertTrue(html.contains("white-space:pre-wrap"))
    }

    @Test
    fun `방 이름과 본문이 들어가고 특수문자는 이스케이프된다`() {
        val html = LogExporter.buildHtml("잿빛 등대의 밤", "🕯️", listOf(message(body = "<script>x</script>")))
        assertTrue(html.contains("잿빛 등대의 밤"))
        assertFalse(html.contains("<script>x"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `내 메시지는 오른쪽, 상대 메시지는 왼쪽 정렬`() {
        val html = LogExporter.buildHtml(
            "방", "",
            listOf(message(incoming = false), message(incoming = true, name = "은서린")),
        )
        assertTrue(html.contains("""class="lrow me""""))
        assertTrue(html.contains("""class="lrow">"""))
    }

    @Test
    fun `이름 색은 라이트 톤으로 치환되고 말풍선 색은 보존된다`() {
        val html = LogExporter.buildHtml("방", "", listOf(message()))
        // #FFC46B → #C07B1F (스펙 명시 매핑)
        assertTrue(html.contains("color:#C07B1F"))
        assertTrue(html.contains("background:#FFD9A8"))
    }

    @Test
    fun `GM 발화는 서술 문단과 인용 말풍선으로 분리된다`() {
        val html = LogExporter.buildHtml(
            "방", "",
            listOf(message(isGm = true, name = "서리", body = """안개가 짙다. "…거기 있는 거, 알아.""""))
        )
        assertTrue(html.contains("""class="lnarr""""))
        assertTrue(html.contains("안개가 짙다."))
        assertTrue(html.contains("GM 서리 · 서술"))
        assertTrue(html.contains("…거기 있는 거, 알아."))
        assertTrue(html.contains("background:#EFE9D8")) // 인용 말풍선
        // 인용 말풍선의 화자 이름 — GM 표기
        assertTrue(html.contains("""color:#8A6D1C">GM<time>"""))
    }

    @Test
    fun `잡담은 회색 점선 말풍선에 잡담 표기가 붙는다`() {
        val html = LogExporter.buildHtml("방", "", listOf(message(isOoc = true, body = "오늘 분위기 미쳤다")))
        assertTrue(html.contains("lbubble lchat"))
        assertTrue(html.contains("〔잡담〕"))
    }

    @Test
    fun `마크다운과 루비가 HTML 태그로 변환된다`() {
        val html = LogExporter.buildHtml(
            "방", "",
            listOf(message(body = "**불빛이 꺼진 순간**을 |等臺《등대》에서"))
        )
        assertTrue(html.contains("<b>불빛이 꺼진 순간</b>"))
        assertTrue(html.contains("<ruby>等臺<rt>등대</rt></ruby>"))
    }

    @Test
    fun `수정된 메시지에 수정됨 표기`() {
        val html = LogExporter.buildHtml("방", "", listOf(message(editedAt = 1L)))
        assertTrue(html.contains("(수정됨)"))
    }

    @Test
    fun `다이스 결과는 중앙 라인`() {
        val html = LogExporter.buildHtml(
            "방", "",
            listOf(message(type = MessageType.DICE, body = "17", diceExpr = "은서린 · 1d100"))
        )
        assertTrue(html.contains("""class="dice""""))
        assertTrue(html.contains("은서린 · 1d100"))
    }

    @Test
    fun `아바타 이미지를 base64로 내장한다`() {
        val png = File.createTempFile("avatar", ".png").apply {
            deleteOnExit()
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        }
        val html = LogExporter.buildHtml("방", "", listOf(message(imagePath = png.absolutePath)))
        assertTrue(html.contains("data:image/png;base64,"))
    }
}
