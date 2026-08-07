package com.pbp.shared

import com.pbp.shared.GmSpeech
import com.pbp.shared.GmSpeech.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GmSpeechTest {

    @Test
    fun `공백만 있는 인용부는 말풍선을 만들지 않는다`() {
        assertEquals(
            listOf(Part.Narration("바람이 분다."), Part.Narration("문이 닫혔다.")),
            GmSpeech.split("바람이 분다. \" \" 문이 닫혔다."),
        )
    }

    @Test
    fun `따옴표가 없으면 전체가 서술`() {
        assertEquals(
            listOf(Part.Narration("밤안개가 방파제를 덮는다.")),
            GmSpeech.split("밤안개가 방파제를 덮는다."),
        )
    }

    @Test
    fun `따옴표 안 문장만 말풍선으로 분리한다`() {
        assertEquals(
            listOf(
                Part.Narration("안개 너머, 젖은 발소리 하나."),
                Part.Quote("…거기 있는 거, 알아."),
            ),
            GmSpeech.split("""안개 너머, 젖은 발소리 하나. "…거기 있는 거, 알아.""""),
        )
    }

    @Test
    fun `여러 인용과 사이 서술을 순서대로 보존한다`() {
        assertEquals(
            listOf(
                Part.Quote("누구냐."),
                Part.Narration("목소리가 갈라진다."),
                Part.Quote("대답해."),
            ),
            GmSpeech.split(""""누구냐." 목소리가 갈라진다. "대답해.""""),
        )
    }

    @Test
    fun `둥근 따옴표도 인식한다`() {
        assertEquals(
            listOf(Part.Narration("그가 속삭인다."), Part.Quote("이리 와.")),
            GmSpeech.split("그가 속삭인다. “이리 와.”"),
        )
    }

    @Test
    fun `인용은 줄을 넘지 않는다 — 홀수 따옴표가 문단을 삼키지 않게`() {
        // 두 문단에 각각 따옴표가 하나씩이면 예전에는 문단을 가로지르는 거대 인용이 됐다 (E11)
        val quote = '"'
        val parts = GmSpeech.split("첫 문단에 $quote 하나.\n둘째 문단에도 $quote 하나.")
        assertEquals(1, parts.size)
        assertTrue(parts.single() is Part.Narration)
    }

    @Test
    fun `공백만 든 따옴표도 사라지지 않는다 — 빈 목록이면 메시지가 통째로 증발한다`() {
        val parts = GmSpeech.split("\" \"")
        assertEquals(1, parts.size)
        assertTrue(parts.first() is GmSpeech.Part.Narration)
    }
}
