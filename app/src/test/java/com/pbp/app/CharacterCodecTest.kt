package com.pbp.app

import com.pbp.app.data.CharacterCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCodecTest {

    private val sample = """{ "kind": "character", "data": { "name": "가류 세이시로","initiative": 70, "memo": "가류 세이시로\n臥龍 誠志郎\n\n나이: 25 | 성별: 남성", "status": [{"label": "HP", "value": "14", "max": "13"},{"label": "MP", "value": "10", "max": "10"},{"label": "SAN", "value": "50", "max": "89"}], "params": [{"label": "체구", "value": "1"},{"label": "피해 보너스", "value": "1D4"},{"label": "이동력", "value": "8"}], "commands": "CC(0)<={SAN} 【이성】\nCC(0)<=70【근력】"} }"""

    @Test
    fun `ccfolia 캐릭터 코드에서 이름과 값들을 읽는다`() {
        val imported = CharacterCodec.parse(sample)!!
        assertEquals("가류 세이시로", imported.name)
        val map = imported.stats.toMap()
        assertEquals("70", map["initiative"])
        assertEquals("14", map["HP"])
        assertEquals("13", map["HPmax"])
        assertEquals("50", map["SAN"])
        assertEquals("89", map["SANmax"])
        assertEquals("1", map["체구"])
        assertEquals("1D4", map["피해 보너스"])
        assertEquals("8", map["이동력"])
        assertTrue(map.getValue("memo").startsWith("가류 세이시로\n"))
    }

    @Test
    fun `commands는 값으로 저장하지 않는다`() {
        val imported = CharacterCodec.parse(sample)!!
        assertFalse(imported.stats.any { it.first == "commands" })
    }

    @Test
    fun `따옴표가 겹친 스프레드시트식 복사본도 파싱한다`() {
        val doubled = sample.replace("\"", "\"\"")
        val imported = CharacterCodec.parse(doubled)!!
        assertEquals("가류 세이시로", imported.name)
        assertEquals("14", imported.stats.toMap()["HP"])
    }

    @Test
    fun `캐릭터 코드가 아니면 null`() {
        assertNull(CharacterCodec.parse("그냥 텍스트"))
        assertNull(CharacterCodec.parse("""{"kind":"other","data":{"name":"x"}}"""))
        assertNull(CharacterCodec.parse("""{"kind":"character","data":{}}"""))
        assertNull(CharacterCodec.parse(""))
    }
}
