package com.pbp.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTML 로그 내보내기 (F1) — shared 최대 파일인데 테스트가 없었다.
 * 모바일·PC가 이 한 벌을 함께 쓰므로 여기서 깨지면 양쪽이 같이 깨진다.
 */
class LogExportTest {

    private fun msg(
        body: String = "안녕",
        type: String = Protocol.MessageType.TEXT,
        mine: Boolean = false,
        name: String? = "이단",
        isGm: Boolean = false,
        isOoc: Boolean = false,
        diceExpr: String? = null,
        diceOutcome: String? = null,
        editedAt: Long? = null,
        createdAt: Long = 1_700_000_000_000,
    ) = LogExport.ExportMessage(
        type = type,
        body = body,
        createdAt = createdAt,
        mine = mine,
        senderName = name,
        senderEmoji = "단",
        senderIsGm = isGm,
        isOoc = isOoc,
        editedAt = editedAt,
        diceExpr = diceExpr,
        diceOutcome = diceOutcome,
    )

    private fun html(vararg messages: LogExport.ExportMessage) =
        LogExport.buildHtml("테스트 방", "🎲", messages.toList()) { null }

    // ── 이스케이프 ───────────────────────────────────────

    @Test
    fun `본문의 꺾쇠는 이스케이프된다 — 로그가 HTML을 실행하면 안 된다`() {
        val out = html(msg(body = "<script>alert(1)</script>"))
        assertFalse("원문 태그가 그대로 나가면 안 된다", out.contains("<script>alert"))
        assertTrue(out.contains("&lt;script&gt;"))
    }

    @Test
    fun `이름도 이스케이프된다`() {
        val out = html(msg(name = "<b>이단</b>"))
        assertTrue(out.contains("&lt;b&gt;이단&lt;/b&gt;"))
    }

    @Test
    fun `escape는 다섯 문자를 모두 바꾼다 — 앰퍼샌드가 먼저여야 이중 변환이 없다`() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", LogExport.escape("&<>\"'"))
    }

    // ── 종류별 분기 ──────────────────────────────────────

    @Test
    fun `GM 서술은 인용만 말풍선으로 갈라진다`() {
        val out = html(msg(body = "문이 열린다. \"거기 누구냐.\"", isGm = true, name = "GM"))
        assertTrue("서술 문단이 있어야 한다", out.contains("문이 열린다."))
        assertTrue("인용도 있어야 한다", out.contains("거기 누구냐."))
    }

    @Test
    fun `잡담은 이름 접두를 달고 나간다`() {
        val out = html(msg(body = "오늘 무서웠어", isOoc = true, name = "이단"))
        assertTrue(out.contains("이단"))
        assertTrue(out.contains("오늘 무서웠어"))
    }

    @Test
    fun `다이스는 식과 결과와 판정 등급을 함께 찍는다`() {
        val out = html(
            msg(
                body = "76",
                type = Protocol.MessageType.DICE,
                diceExpr = "1d100<=50",
                diceOutcome = Rules.Outcome.FAIL,
            )
        )
        assertTrue(out.contains("1d100&lt;=50"))
        assertTrue(out.contains("실패"))
    }

    @Test
    fun `SYSTEM 안내는 가운데 한 줄로`() {
        val out = html(msg(body = "프로필을 바꿨습니다", type = Protocol.MessageType.SYSTEM))
        assertTrue(out.contains("class=\"sys\""))
    }

    @Test
    fun `수정됨 표시`() {
        val out = html(msg(editedAt = 1_700_000_100_000))
        assertTrue(out.contains("수정됨"))
    }

    // ── 머리글·걸러내기 ─────────────────────────────────

    @Test
    fun `날짜 구분선은 넣지 않는다 — 범위는 머리글이 말한다`() {
        val day1 = 1_700_000_000_000
        val day2 = day1 + 3 * 24 * 60 * 60 * 1000L
        val out = html(msg(createdAt = day1), msg(createdAt = day2))
        assertFalse("본문에 날짜 구분선이 남으면 안 된다", out.contains("class=\"day\""))
    }

    @Test
    fun `제목 아래에 첫 메시지부터 마지막 메시지까지의 날짜가 붙는다`() {
        val day1 = 1_700_000_000_000
        val day2 = day1 + 3 * 24 * 60 * 60 * 1000L
        val out = html(msg(createdAt = day1), msg(createdAt = day2))
        assertTrue(out, out.contains(CaptureLayout.formatDateRange(day1, day2)))
    }

    @Test
    fun `말풍선 곁 시각은 찍지 않는다`() {
        val out = html(msg(createdAt = 1_700_000_000_000))
        assertFalse("시각 자리가 남으면 안 된다", out.contains("<time>"))
    }

    @Test
    fun `로그 초기화 안내는 내보내기에서 빠진다 — 다른 시스템 안내는 남는다`() {
        val out = html(
            msg(body = Protocol.Notice.LOGS_RESET, type = Protocol.MessageType.SYSTEM),
            msg(body = "프로필을 바꿨습니다", type = Protocol.MessageType.SYSTEM),
        )
        assertFalse(out, out.contains(Protocol.Notice.LOGS_RESET))
        assertTrue(out, out.contains("프로필을 바꿨습니다"))
    }

    // ── 이미지 MIME 스니핑 ───────────────────────────────

    @Test
    fun `PNG는 시그니처 전체로 판별한다`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)
        assertTrue(LogExport.bytesToDataUri(png)!!.startsWith("data:image/png;base64,"))
    }

    @Test
    fun `JPEG와 GIF와 WEBP도 판별한다`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
        val gif = "GIF89a".toByteArray() + byteArrayOf(0)
        val webp = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray()
        assertTrue(LogExport.bytesToDataUri(jpeg)!!.startsWith("data:image/jpeg"))
        assertTrue(LogExport.bytesToDataUri(gif)!!.startsWith("data:image/gif"))
        assertTrue(LogExport.bytesToDataUri(webp)!!.startsWith("data:image/webp"))
    }

    @Test
    fun `알 수 없는 형식은 null — 깨진 img 태그를 만들지 않는다`() {
        assertNull(LogExport.bytesToDataUri(byteArrayOf(1, 2, 3, 4)))
        assertNull(LogExport.bytesToDataUri(ByteArray(0)))
    }

    @Test
    fun `RIFF지만 WEBP가 아니면 거부한다 — 접두만 보면 오탐한다`() {
        val wav = "RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray()
        assertNull(LogExport.bytesToDataUri(wav))
    }

    // ── 색 변환 ──────────────────────────────────────────

    @Test
    fun `hex는 알파를 떼고 여섯 자리로`() {
        assertEquals("#FFC46B", LogExport.hex(0xFFFFC46B))
        assertEquals("#000000", LogExport.hex(0xFF000000))
    }

    @Test
    fun `darken은 각 채널에 비율을 곱한다 — 알파는 늘 불투명`() {
        // 0xFF→127, 0x80→64, 0x40→32 (내림)
        assertEquals(0xFF7F4020L, Palette.darken(0xFFFF8040, 0.5f))
        assertEquals(0xFF000000L, Palette.darken(0xFFFFFFFF, 0f))
    }

    @Test
    fun `nameColorForLight — 프리셋은 표에서, 나머지는 어둡게`() {
        // 프리셋은 눈으로 고른 값이라 계산식과 다를 수 있다. 어떤 값이든
        // 밝은 배경에서 읽히도록 원색보다 어두워야 한다
        Palette.namePresets.forEach { preset ->
            val converted = Palette.nameColorForLight(preset)
            assertTrue(
                "밝은 배경용 색이 원색보다 밝으면 안 된다",
                (converted and 0xFFFFFF) <= (preset and 0xFFFFFF),
            )
        }
    }

    // ── 텍스트 내보내기 ──────────────────────────────────

    /** 2026-08-04 12:34 KST 근처 — 날짜 구분선이 한 번만 나오면 되니 값 자체는 무관 */
    private fun msg(
        body: String,
        type: String = Protocol.MessageType.TEXT,
        at: Long = 1_754_270_000_000L,
        name: String? = "루나",
        ooc: Boolean = false,
        editedAt: Long? = null,
        diceExpr: String? = null,
        diceOutcome: String? = null,
    ) = LogExport.ExportMessage(
        type = type, body = body, createdAt = at, mine = false, senderName = name,
        isOoc = ooc, editedAt = editedAt, diceExpr = diceExpr, diceOutcome = diceOutcome,
    )

    @Test
    fun `텍스트는 마크업을 벗기고 루비는 괄호로 편다`() {
        val out = LogExport.buildText("방", listOf(msg("**굵게** 그리고 (한글)[한자]")))
        assertTrue(out, out.contains("루나: 굵게 그리고 한자(한글)"))
        assertTrue("서식 문자가 남았다", !out.contains("**"))
    }

    @Test
    fun `텍스트도 둘째 줄이 기록 범위 — 날짜 구분선은 없다`() {
        val day = 24 * 60 * 60 * 1000L
        val at = 1_754_270_000_000L
        val out = LogExport.buildText("방", listOf(msg("가", at = at), msg("나", at = at + day)))
        assertEquals(
            "${CaptureLayout.formatDateRange(at, at + day)} · 2개 메시지 · PbP 대화 기록",
            out.lines()[1],
        )
        assertFalse(out, out.lines().any { it.startsWith("── ") })
    }

    @Test
    fun `텍스트에도 시각은 찍지 않는다`() {
        val out = LogExport.buildText("방", listOf(msg("가"), msg("7", type = Protocol.MessageType.DICE)))
        assertFalse(out, Regex("""\d\d:\d\d""").containsMatchIn(out))
    }

    @Test
    fun `텍스트에서도 로그 초기화 안내는 빠진다`() {
        val out = LogExport.buildText(
            "방",
            listOf(msg(Protocol.Notice.LOGS_RESET, type = Protocol.MessageType.SYSTEM), msg("가")),
        )
        assertFalse(out, out.contains(Protocol.Notice.LOGS_RESET))
    }

    @Test
    fun `시스템·판정은 대괄호 한 줄, 다이스는 식과 성패까지`() {
        val out = LogExport.buildText(
            "방",
            listOf(
                msg("방이 열렸습니다", type = Protocol.MessageType.SYSTEM),
                msg("7", type = Protocol.MessageType.DICE, diceExpr = "1d20", diceOutcome = "success"),
            ),
        )
        assertTrue(out, out.contains("[방이 열렸습니다]"))
        assertTrue(out, out.contains("1d20 → 7 (성공)"))
    }

    @Test
    fun `잡담과 수정됨은 본문 옆에 표시된다`() {
        val out = LogExport.buildText("방", listOf(msg("딴소리", ooc = true), msg("고쳤다", editedAt = 1L)))
        assertTrue(out, out.contains("(루나 · 잡담) 딴소리"))
        assertTrue(out, out.contains("고쳤다 (수정됨)"))
    }
}
