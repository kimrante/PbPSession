package com.pbp.app

import com.pbp.app.text.GmSpeech
import com.pbp.app.text.GmSpeech.Part
import org.junit.Assert.assertEquals
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
}
