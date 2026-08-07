package com.pbp.app

import com.pbp.app.ui.chat.CaptureMark
import com.pbp.app.ui.chat.captureMarkOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 캡처 범위의 **표시** 상태 (목업 final-design.html 03장).
 * 범위 계산 규칙 자체는 :shared CaptureLayoutTest로 옮겼다 — PC 복제본까지 함께 지킨다 (C2).
 */
class CaptureRangeTest {

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
