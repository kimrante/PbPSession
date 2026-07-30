package com.pbp.shared

import com.pbp.shared.PbpMarkup
import com.pbp.shared.PbpMarkup.Node
import org.junit.Assert.assertEquals
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
}
