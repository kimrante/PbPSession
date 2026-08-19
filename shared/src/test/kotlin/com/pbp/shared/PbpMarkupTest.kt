package com.pbp.shared

import com.pbp.shared.PbpMarkup
import com.pbp.shared.PbpMarkup.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PbpMarkupTest {

    @Test
    fun `일반 텍스트는 그대로 하나의 스팬`() {
        assertEquals(listOf(Node.Span("안녕하세요")), PbpMarkup.parse("안녕하세요"))
    }

    @Test
    fun `굵게와 기울임을 파싱한다`() {
        assertEquals(
            listOf(
                Node.Span("여기서 기다리자. "),
                Node.Span("불빛이 꺼진 순간", bold = true),
                Node.Span("을 노리는 거야."),
            ),
            PbpMarkup.parse("여기서 기다리자. **불빛이 꺼진 순간**을 노리는 거야."),
        )
        assertEquals(
            listOf(Node.Span("조용히", italic = true), Node.Span(".")),
            PbpMarkup.parse("*조용히*."),
        )
    }

    @Test
    fun `취소선을 파싱한다`() {
        assertEquals(
            listOf(Node.Span("이건 "), Node.Span("실수", strike = true)),
            PbpMarkup.parse("이건 ~~실수~~"),
        )
    }

    @Test
    fun `루비 문법을 파싱한다`() {
        assertEquals(
            listOf(
                Node.Ruby("等臺", "등대"),
                Node.Span(" 쪽으로 간다"),
            ),
            PbpMarkup.parse("(등대)[等臺] 쪽으로 간다"),
        )
    }

    @Test
    fun `일문 루비도 지원한다 - UTF-8`() {
        assertEquals(
            listOf(Node.Span("이내 "), Node.Ruby("침묵", "しじま"), Node.Span("만이 남았다")),
            PbpMarkup.parse("이내 (しじま)[침묵]만이 남았다"),
        )
    }

    @Test
    fun `짝 없는 구분자는 문자 그대로 남긴다`() {
        assertEquals(listOf(Node.Span("별 * 하나")), PbpMarkup.parse("별 * 하나"))
        // 짝 없는 **도 소실되지 않는다 (P2-6)
        assertEquals(listOf(Node.Span("별 ** 하나")), PbpMarkup.parse("별 ** 하나"))
        assertEquals(listOf(Node.Span("**굵게")), PbpMarkup.parse("**굵게"))
        assertEquals(listOf(Node.Span("~~취소")), PbpMarkup.parse("~~취소"))
    }

    @Test
    fun `값 마커를 파싱한다 - 캐릭터 value 치환 결과`() {
        assertEquals(
            listOf(Node.Span("나 "), Node.Value("50"), Node.Span("할래.")),
            PbpMarkup.parse("나 {{50}}할래."),
        )
        // 한 겹 중괄호(치환 안 된 이름)는 일반 텍스트
        assertEquals(listOf(Node.Span("나 {점프}할래.")), PbpMarkup.parse("나 {점프}할래."))
    }

    @Test
    fun `마크다운과 루비 혼합`() {
        assertEquals(
            listOf(
                Node.Span("그 전에 "),
                Node.Ruby("탐지", "Spot Hidden"),
                Node.Span(" 굴릴게. "),
                Node.Span("조용히.", italic = true),
            ),
            PbpMarkup.parse("그 전에 (Spot Hidden)[탐지] 굴릴게. *조용히.*"),
        )
    }

    @Test
    fun `신 루비 문법 — 괄호가 독음, 대괄호가 본문`() {
        val nodes = PbpMarkup.parse("어두운 (등대)[等臺]로 향했다")
        val ruby = nodes.filterIsInstance<PbpMarkup.Node.Ruby>().single()
        assertEquals("等臺", ruby.base)   // 대괄호 = 본문
        assertEquals("등대", ruby.ruby)   // 괄호 = 위에 붙는 독음
    }

    @Test
    fun `구 루비 문법은 더 이상 인식하지 않는다 — 원문 그대로`() {
        assertTrue(
            PbpMarkup.parse("|等臺《등대》")
                .filterIsInstance<PbpMarkup.Node.Ruby>().isEmpty()
        )
    }

    @Test
    fun `괄호만 있거나 대괄호만 있으면 루비가 아니다`() {
        assertTrue(PbpMarkup.parse("(주석) 그리고 [대괄호]")
            .filterIsInstance<PbpMarkup.Node.Ruby>().isEmpty())
    }

    @Test
    fun `인라인 스타일이 루비를 가로질러도 유지된다`() {
        val nodes = PbpMarkup.parse("**굵게 (독음)[本文] 계속**")
        // 별표가 문자로 남지 않는다
        val text = nodes.filterIsInstance<PbpMarkup.Node.Span>().joinToString("") { it.text }
        assertFalse(text.contains("*"))
        // 루비 앞뒤 텍스트가 모두 굵게
        val spans = nodes.filterIsInstance<PbpMarkup.Node.Span>().filter { it.text.isNotBlank() }
        assertTrue(spans.isNotEmpty())
        assertTrue(spans.all { it.bold })
        // 루비 노드는 그대로 살아 있다
        assertTrue(nodes.any { it is PbpMarkup.Node.Ruby && it.base == "本文" })
    }

    @Test
    fun `스타일이 값 치환을 가로질러도 유지된다`() {
        val nodes = PbpMarkup.parse("~~취소 {{50}} 계속~~")
        val spans = nodes.filterIsInstance<PbpMarkup.Node.Span>().filter { it.text.isNotBlank() }
        assertTrue(spans.all { it.strike })
        assertTrue(nodes.any { it is PbpMarkup.Node.Value && it.text == "50" })
    }

    @Test
    fun `루비 경계에 걸친 별표 짝도 닫힘으로 본다 — 꼬리 탐색 회귀 (Z4)`() {
        // 여는 *의 닫는 짝이 루비 '너머 조각'에 있다 — 꼬리 결합 방식을 바꿔도
        // 이 판정이 달라지면 안 된다
        val nodes = PbpMarkup.parse("*기울임 (독음)[本文]* 끝")
        val spans = nodes.filterIsInstance<PbpMarkup.Node.Span>().filter { it.text.isNotBlank() }
        assertTrue(spans.first().italic)
        assertFalse(spans.last().italic)
        assertFalse(spans.joinToString("") { it.text }.contains("*"))
    }

    @Test
    fun `마커가 수백 개인 초장문도 금방 끝난다 — 예전에는 조각마다 뒤를 다시 이었다`() {
        // 마커 500개 × 본문 2,000자 남짓. O(n²)이던 시절에는 수 초가 걸려
        // 첫 컴포지션이 멎고 시스템이 ANR로 앱을 죽였다 (Z4)
        val body = (1..500).joinToString(separator = " ") { "값 {{$it}} 뒤" }
        val started = System.nanoTime()
        val nodes = PbpMarkup.parse(body)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(500, nodes.count { it is Node.Value })
        assertTrue("파싱에 ${elapsedMs}ms — 너무 느리다", elapsedMs < 500)
    }
}
