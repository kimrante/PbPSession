package com.pbp.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 캡처 범위 선택·분할 규칙 (목업 final-design.html 03장, C2).
 * 예전에는 앱 쪽에만 있어 PC 복제본이 검증 없이 굴러갔다.
 */
class CaptureLayoutTest {

    private fun tap(range: IntRange, at: Int) = CaptureLayout.rangeAfterTap(range, at)

    @Test
    fun `시작만 정해진 상태에서 아래를 탭하면 아래로 뻗는다`() {
        assertEquals(2..5, tap(2..2, 5))
    }

    @Test
    fun `위를 탭하면 위로 뻗는다 — 순서는 자동 정렬`() {
        assertEquals(1..4, tap(4..4, 1))
    }

    @Test
    fun `시작점을 다시 탭하면 시작만 끝 자리로 옮겨진다`() {
        assertEquals(5..5, tap(2..5, 2))
    }

    @Test
    fun `끝점을 다시 탭하면 끝만 시작 자리로 옮겨진다`() {
        assertEquals(2..2, tap(2..5, 5))
    }

    @Test
    fun `범위 밖 위쪽을 탭하면 시작이 늘어난다`() {
        assertEquals(0..5, tap(2..5, 0))
    }

    @Test
    fun `범위 밖 아래쪽을 탭하면 끝이 늘어난다`() {
        assertEquals(2..9, tap(2..5, 9))
    }

    @Test
    fun `범위 안쪽을 탭하면 끝이 당겨진다 — 범위가 초기화되지 않는다`() {
        assertEquals(2..3, tap(2..5, 3))
    }

    @Test
    fun `한 건만 선택된 상태에서 그 자리를 다시 탭해도 그대로다`() {
        assertEquals(2..2, tap(2..2, 2))
    }

    // ── 분할 ────────────────────────────────────────────

    private fun text(body: String, gm: Boolean = false) =
        CaptureLayout.Item(body, Protocol.MessageType.TEXT, isOoc = false, senderIsGm = gm)

    @Test
    fun `짧은 대화는 한 장`() {
        val items = List(5) { text("안녕") }
        assertEquals(listOf(0 until 5), CaptureLayout.splitByHeight(items))
    }

    @Test
    fun `상한을 넘으면 메시지 경계에서 나뉘고 원본 순서를 빠짐없이 덮는다`() {
        // 한 건이 20줄 남짓 되도록 긴 본문
        val items = List(120) { text("가".repeat(340)) }
        val chunks = CaptureLayout.splitByHeight(items)
        assertTrue("여러 장으로 나뉘어야 한다", chunks.size > 1)
        assertEquals(items.indices.toList(), chunks.flatMap { it.toList() })
    }

    @Test
    fun `추정 높이는 길이에 비례한다 — 긴 서술이 고정값으로 눌리면 안 된다`() {
        val short = CaptureLayout.estimate(text("짧다", gm = true))
        val long = CaptureLayout.estimate(text("가".repeat(1000), gm = true))
        assertTrue("긴 서술이 더 높아야 한다", long > short * 5)
    }

    @Test
    fun `본문 없는 종류는 한 줄 크기로 센다`() {
        val dice = CaptureLayout.Item("1d100 → 42", Protocol.MessageType.DICE, false, false)
        assertEquals(28f, CaptureLayout.estimate(dice), 0.01f)
    }

    // ── 기록 범위 ────────────────────────────────────────

    @Test
    fun `범위는 날짜만 — 시각은 넣지 않는다`() {
        val at = 1_754_270_000_000L
        val range = CaptureLayout.formatDateRange(at, at + 3 * 60 * 60 * 1000L)
        assertFalse(range, Regex("""\d\d:\d\d""").containsMatchIn(range))
    }

    @Test
    fun `같은 날이면 한 번만, 날짜가 다르면 양쪽 모두`() {
        val at = 1_754_270_000_000L
        val day = 24 * 60 * 60 * 1000L
        assertEquals(CaptureLayout.dateOnly(at), CaptureLayout.formatDateRange(at, at + 1000))
        assertEquals(
            "${CaptureLayout.dateOnly(at)} – ${CaptureLayout.dateOnly(at + day)}",
            CaptureLayout.formatDateRange(at, at + day),
        )
    }
}
