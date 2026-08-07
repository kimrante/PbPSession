package com.pbp.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시나리오 뷰어의 규칙 고정 (V0).
 * 문장 분리가 흔들리면 화면의 `<` `>` 이동이 통째로 달라지므로 여기서 먼저 못을 박는다.
 */
class ScenarioDocTest {

    private val id = "1AbC_de-FGH123"

    // ── 링크 검증 ────────────────────────────────────────

    @Test
    fun `공유 버튼 기본형에서 docId를 뽑는다`() {
        assertEquals(id, ScenarioDoc.extractDocId("https://docs.google.com/document/d/$id/edit?usp=sharing"))
    }

    @Test
    fun `view preview 조각 링크 그리고 id로 끝나는 형도 인정한다`() {
        assertEquals(id, ScenarioDoc.extractDocId("https://docs.google.com/document/d/$id/view"))
        assertEquals(id, ScenarioDoc.extractDocId("https://docs.google.com/document/d/$id/preview"))
        assertEquals(id, ScenarioDoc.extractDocId("https://docs.google.com/document/d/$id/edit#heading=h.1"))
        assertEquals(id, ScenarioDoc.extractDocId("https://docs.google.com/document/d/$id"))
    }

    @Test
    fun `앞뒤 공백과 개행은 털어낸다 — 붙여넣기가 늘 깔끔하지 않다`() {
        assertEquals(id, ScenarioDoc.extractDocId("  \nhttps://docs.google.com/document/d/$id/edit\n "))
    }

    @Test
    fun `http도 허용한다 — 어차피 우리가 https로 다시 만든다`() {
        assertEquals(id, ScenarioDoc.extractDocId("http://docs.google.com/document/d/$id/edit"))
    }

    @Test
    fun `다른 호스트는 거부 — 임의 URL을 받아 오는 기능이 되면 안 된다`() {
        assertNull(ScenarioDoc.extractDocId("https://evil.example.com/document/d/$id/edit"))
        assertNull(ScenarioDoc.extractDocId("https://docs.google.com.evil.com/document/d/$id"))
        assertNull(ScenarioDoc.extractDocId("https://bit.ly/abcd"))
    }

    @Test
    fun `문서가 아닌 종류는 거부한다`() {
        assertNull(ScenarioDoc.extractDocId("https://docs.google.com/spreadsheets/d/$id/edit"))
        assertNull(ScenarioDoc.extractDocId("https://docs.google.com/presentation/d/$id/edit"))
    }

    @Test
    fun `docId에 허용되지 않는 글자가 있으면 거부한다`() {
        assertNull(ScenarioDoc.extractDocId("https://docs.google.com/document/d/abc%20def/edit"))
        assertNull(ScenarioDoc.extractDocId("https://docs.google.com/document/d/"))
    }

    @Test
    fun `스킴이 없으면 거부한다`() {
        assertNull(ScenarioDoc.extractDocId("docs.google.com/document/d/$id"))
        assertNull(ScenarioDoc.extractDocId(""))
    }

    @Test
    fun `export URL은 docId를 그대로 끼운다`() {
        assertEquals(
            "https://docs.google.com/document/d/$id/export?format=txt",
            ScenarioDoc.exportUrl(id),
        )
    }

    // ── 문장 분리 ────────────────────────────────────────

    @Test
    fun `평서문 연쇄는 종결부호마다 나뉜다`() {
        assertEquals(
            listOf("문이 열린다.", "안개가 짙다.", "아무도 없다."),
            ScenarioDoc.splitSentences("문이 열린다. 안개가 짙다. 아무도 없다."),
        )
    }

    @Test
    fun `연속 부호는 한 덩어리로 본다`() {
        assertEquals(
            listOf("정말인가?!", "그럴 리가…", "글쎄..."),
            ScenarioDoc.splitSentences("정말인가?! 그럴 리가… 글쎄..."),
        )
    }

    @Test
    fun `닫는 따옴표는 앞 문장에 딸려 간다 — 따옴표만 다음 문장으로 넘어가면 안 된다`() {
        assertEquals(
            listOf("그가 말했다.", "\"이제 간다.\"", "문이 닫혔다."),
            ScenarioDoc.splitSentences("그가 말했다. \"이제 간다.\" 문이 닫혔다."),
        )
    }

    @Test
    fun `종결부호가 없는 줄은 통째로 한 문장 — 제목이 뭉개지면 안 된다`() {
        assertEquals(
            listOf("1장 등대", "밤이 깊었다."),
            ScenarioDoc.splitSentences("1장 등대\n밤이 깊었다."),
        )
    }

    @Test
    fun `빈 줄이 연달아도 무시한다`() {
        assertEquals(
            listOf("첫 줄.", "둘째 줄."),
            ScenarioDoc.splitSentences("첫 줄.\n\n\n   \n둘째 줄."),
        )
    }

    @Test
    fun `숫자 사이의 마침표는 자르지 않는다`() {
        assertEquals(
            listOf("이동 속도는 2.5미터다."),
            ScenarioDoc.splitSentences("이동 속도는 2.5미터다."),
        )
    }

    @Test
    fun `번호 매김의 마침표 뒤 공백은 자른다 — 항목 번호는 그 자체로 한 조각이다`() {
        assertEquals(
            listOf("1.", "장 등대"),
            ScenarioDoc.splitSentences("1. 장 등대"),
        )
    }

    @Test
    fun `전부 공백이면 빈 목록 — 호출부가 빈 문서로 경고한다`() {
        assertEquals(emptyList<String>(), ScenarioDoc.splitSentences("   \n\n  \t \n"))
        assertEquals(emptyList<String>(), ScenarioDoc.splitSentences(""))
    }

    @Test
    fun `줄 끝의 종결부호도 문장을 닫는다`() {
        assertEquals(
            listOf("끝났다.", "다음 장."),
            ScenarioDoc.splitSentences("끝났다.\n다음 장."),
        )
    }

    // ── 제목 ────────────────────────────────────────────

    @Test
    fun `한글 제목은 filename 별표 쪽에서 뽑는다 — 곁들여 오는 ASCII 이름은 뭉개져 있다`() {
        val header = "attachment; filename=\"___.txt\"; " +
            "filename*=UTF-8''%EC%8B%AC%ED%95%B4%20%EB%93%B1%EB%8C%80.txt"
        assertEquals("심해 등대", ScenarioDoc.titleFromDisposition(header))
    }

    @Test
    fun `별표 항목이 없으면 따옴표 이름을 쓴다`() {
        assertEquals(
            "Haunted Manors",
            ScenarioDoc.titleFromDisposition("attachment; filename=\"Haunted Manors.txt\""),
        )
    }

    @Test
    fun `더하기는 공백이 아니다 — 제목에 든 +가 사라지면 안 된다`() {
        assertEquals(
            "A+B",
            ScenarioDoc.titleFromDisposition("attachment; filename*=UTF-8''A+B.txt"),
        )
    }

    @Test
    fun `헤더가 없거나 이름이 비면 null`() {
        assertNull(ScenarioDoc.titleFromDisposition(null))
        assertNull(ScenarioDoc.titleFromDisposition("attachment"))
        assertNull(ScenarioDoc.titleFromDisposition("attachment; filename=\".txt\""))
    }

    // ── 문단 나누기 ─────────────────────────────────────

    private val doc = "첫 문단 첫 문장. 첫 문단 둘째 문장." + "\n" +
        "접힌 줄." + "\n\n" +
        "둘째 문단." + "\n\n\n" +
        "셋째 문단 하나. 셋째 문단 둘."

    @Test
    fun `빈 줄이 문단 경계 — 접힌 줄은 한 문단으로 잇는다`() {
        assertEquals(
            listOf("첫 문단 첫 문장. 첫 문단 둘째 문장." + "\n" + "접힌 줄.", "둘째 문단.", "셋째 문단 하나. 셋째 문단 둘."),
            ScenarioDoc.splitParagraphs(doc),
        )
    }

    @Test
    fun `빈 줄이 몇 개든 경계는 하나 — 빈 문단을 만들지 않는다`() {
        assertEquals(listOf("가.", "나."), ScenarioDoc.splitParagraphs("가." + "\n\n\n\n" + "나."))
        assertEquals(emptyList<String>(), ScenarioDoc.splitParagraphs("   \n  \n"))
    }

    @Test
    fun `문장 번호로 그 문장이 든 문단을 찾는다 — 보기 전환에서 자리를 지킨다`() {
        // 문장: 0,1,2 = 첫 문단 / 3 = 둘째 / 4,5 = 셋째
        assertEquals(0, ScenarioDoc.paragraphIndexOf(doc, 0))
        assertEquals(0, ScenarioDoc.paragraphIndexOf(doc, 2))
        assertEquals(1, ScenarioDoc.paragraphIndexOf(doc, 3))
        assertEquals(2, ScenarioDoc.paragraphIndexOf(doc, 5))
    }

    @Test
    fun `범위를 넘는 문장 번호는 마지막 문단으로`() {
        assertEquals(2, ScenarioDoc.paragraphIndexOf(doc, 99))
        assertEquals(0, ScenarioDoc.paragraphIndexOf(doc, -1))
    }

    @Test
    fun `문단 번호는 그 문단의 첫 문장으로 되돌아간다 — 왕복해도 같은 문단`() {
        for (sentence in 0..5) {
            val paragraph = ScenarioDoc.paragraphIndexOf(doc, sentence)
            val back = ScenarioDoc.sentenceIndexOfParagraph(doc, paragraph)
            assertEquals(paragraph, ScenarioDoc.paragraphIndexOf(doc, back))
        }
    }

    @Test
    fun `여덟 문장이 넘는 문단은 여덟 문장씩 나눈다 — 긴 문단이 화면 밖으로 넘치지 않게`() {
        val long = (1..20).joinToString(separator = " ") { "문장 $it." }
        val units = ScenarioDoc.splitParagraphs(long)
        assertEquals(3, units.size) // 8 + 8 + 4
        units.forEach {
            assertTrue(it, ScenarioDoc.splitSentences(it).size <= ScenarioDoc.MAX_SENTENCES_PER_UNIT)
        }
        // 나눠도 문장은 하나도 잃지 않는다
        assertEquals(
            ScenarioDoc.splitSentences(long),
            units.flatMap { ScenarioDoc.splitSentences(it) },
        )
    }

    @Test
    fun `여덟 문장 이하는 그대로 한 덩어리`() {
        val short = (1..8).joinToString(separator = " ") { "문장 $it." }
        assertEquals(1, ScenarioDoc.splitParagraphs(short).size)
    }

    @Test
    fun `자를 경계가 없는 한 덩어리는 그대로 둔다`() {
        val single = "종결부호가 하나도 없는 아주 긴 줄 " + "가".repeat(500)
        assertEquals(listOf(single.trim()), ScenarioDoc.splitParagraphs(single))
    }

    @Test
    fun `BOM은 떼어 낸다 — 첫 문장에 안 보이는 글자가 붙으면 안 된다`() {
        assertEquals("문이 열린다.", ScenarioDoc.stripBom("﻿문이 열린다."))
        assertEquals("문이 열린다.", ScenarioDoc.stripBom("문이 열린다."))
        assertEquals(
            listOf("문이 열린다."),
            ScenarioDoc.splitSentences(ScenarioDoc.stripBom("﻿문이 열린다.")),
        )
    }
}
