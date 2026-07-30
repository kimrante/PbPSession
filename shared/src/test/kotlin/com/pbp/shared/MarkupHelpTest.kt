package com.pbp.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 도움말이 실제 파서와 어긋나지 않는지 — 문법을 바꾸면 여기서 먼저 걸린다 */
class MarkupHelpTest {

    @Test
    fun `루비 항목은 자리표시자 형태로 설명한다`() {
        val ruby = MarkupHelp.entries.first { it.syntax.contains("루비") }
        assertTrue(ruby.syntax == "(루비)[문자]")
    }

    @Test
    fun `제거된 구 문법을 안내하지 않는다`() {
        val all = MarkupHelp.entries.joinToString(" ") { "${it.syntax} ${it.summary} ${it.example}" }
        assertFalse(all.contains("《"))
    }

    @Test
    fun `안내한 예시가 실제로 파싱된다`() {
        val nodes = PbpMarkup.parse("(등대)[等臺]")
        assertTrue(nodes.contains(PbpMarkup.Node.Ruby(base = "等臺", ruby = "등대")))
    }
}
