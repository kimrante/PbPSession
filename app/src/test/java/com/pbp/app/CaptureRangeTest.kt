package com.pbp.app

import com.pbp.app.ui.chat.CaptureMark
import com.pbp.app.ui.chat.captureMarkOf
import com.pbp.app.ui.chat.captureRangeAfterTap
import org.junit.Assert.assertEquals
import org.junit.Test

/** 캡처 범위 선택 규칙 (목업 mockup-capture 03장) */
class CaptureRangeTest {

    @Test
    fun `시작만 정해진 상태에서 아래를 탭하면 아래로 뻗는다`() {
        assertEquals(2..5, captureRangeAfterTap(2..2, 5))
    }

    @Test
    fun `위를 탭하면 위로 뻗는다 — 순서는 자동 정렬`() {
        assertEquals(1..4, captureRangeAfterTap(4..4, 1))
    }

    @Test
    fun `시작점을 다시 탭하면 시작만 끝 자리로 옮겨진다`() {
        // 2..5에서 2를 탭 → 5 한 건만 남는다 (끝은 고정)
        assertEquals(5..5, captureRangeAfterTap(2..5, 2))
    }

    @Test
    fun `끝점을 다시 탭하면 끝만 시작 자리로 옮겨진다`() {
        assertEquals(2..2, captureRangeAfterTap(2..5, 5))
    }

    @Test
    fun `범위 밖 위쪽을 탭하면 시작이 늘어난다`() {
        assertEquals(0..5, captureRangeAfterTap(2..5, 0))
    }

    @Test
    fun `범위 밖 아래쪽을 탭하면 끝이 늘어난다`() {
        assertEquals(2..9, captureRangeAfterTap(2..5, 9))
    }

    @Test
    fun `범위 안쪽을 탭하면 끝이 당겨진다 — 범위가 초기화되지 않는다`() {
        assertEquals(2..3, captureRangeAfterTap(2..5, 3))
    }

    @Test
    fun `한 건만 선택된 상태에서 그 자리를 다시 탭해도 그대로다`() {
        assertEquals(2..2, captureRangeAfterTap(2..2, 2))
    }

    @Test
    fun `표시 상태 — 양 끝과 안팎`() {
        assertEquals(CaptureMark.NONE, captureMarkOf(null, 3))
        assertEquals(CaptureMark.OUT, captureMarkOf(2..5, 1))
        assertEquals(CaptureMark.START, captureMarkOf(2..5, 2))
        assertEquals(CaptureMark.IN, captureMarkOf(2..5, 3))
        assertEquals(CaptureMark.END, captureMarkOf(2..5, 5))
        assertEquals(CaptureMark.ONLY, captureMarkOf(2..2, 2))
    }
}
