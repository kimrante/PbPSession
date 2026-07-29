package com.pbp.app

import com.pbp.app.dice.DiceBot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DiceBotTest {

    @Test
    fun `지원하는 모든 면 수를 파싱한다`() {
        for (sides in DiceBot.supportedSides) {
            assertEquals(DiceBot.Command(1, sides), DiceBot.parse("1d$sides"))
        }
    }

    @Test
    fun `slash r 접두어와 대소문자를 허용한다`() {
        assertEquals(DiceBot.Command(1, 6), DiceBot.parse("/r 1d6"))
        assertEquals(DiceBot.Command(2, 10), DiceBot.parse("/R 2D10"))
        assertEquals(DiceBot.Command(1, 100), DiceBot.parse("  1d100  "))
    }

    @Test
    fun `개수를 생략하면 1개로 취급한다`() {
        assertEquals(DiceBot.Command(1, 6), DiceBot.parse("d6"))
        assertEquals(DiceBot.Command(1, 100), DiceBot.parse("/r d100"))
    }

    @Test
    fun `잘못된 입력은 null을 돌려준다`() {
        assertNull(DiceBot.parse("안녕하세요"))
        assertNull(DiceBot.parse("1d7"))          // 미지원 면 수
        assertNull(DiceBot.parse("0d6"))          // 개수 0
        assertNull(DiceBot.parse("21d6"))         // 최대 개수 초과
        assertNull(DiceBot.parse("공격! 1d6"))    // 문장 중간의 다이스는 명령 아님
        assertNull(DiceBot.parse("d6면체"))       // 명령 뒤에 공백 없이 글자
        assertNull(DiceBot.parse("/r"))
        assertNull(DiceBot.parse(""))
    }

    @Test
    fun `명령 뒤에 공백과 대사가 붙어도 다이스로 인식한다`() {
        assertEquals(DiceBot.Command(1, 6), DiceBot.parse("1d6 굴려줘"))
        assertEquals(DiceBot.Command(2, 10), DiceBot.parse("2d10 기습 공격!"))
        assertEquals(
            DiceBot.Command(1, 100, "<=", 50),
            DiceBot.parse("1d100<=50 은신 판정"),
        )
    }

    @Test
    fun `비교식을 파싱한다 - 공백 유무 모두`() {
        assertEquals(DiceBot.Command(1, 100, "<", 30), DiceBot.parse("1d100<30"))
        assertEquals(DiceBot.Command(2, 6, ">", 7), DiceBot.parse("2d6 > 7"))
        assertEquals(DiceBot.Command(1, 10, ">=", 5), DiceBot.parse("/r 1d10 >= 5"))
        assertEquals("1d100<=50", DiceBot.Command(1, 100, "<=", 50).expr)
    }

    @Test
    fun `비교식 판정 - 성공과 실패`() {
        // total=7
        val r7 = DiceBot.Result(DiceBot.Command(1, 10, "<", 10), listOf(7))
        assertEquals(true, r7.success)
        assertEquals(false, DiceBot.Result(DiceBot.Command(1, 10, ">", 10), listOf(7)).success)
        assertEquals(true, DiceBot.Result(DiceBot.Command(1, 10, "<=", 7), listOf(7)).success)
        assertEquals(true, DiceBot.Result(DiceBot.Command(1, 10, ">=", 7), listOf(7)).success)
        assertEquals(false, DiceBot.Result(DiceBot.Command(1, 10, "<=", 6), listOf(7)).success)
        // 비교식이 없으면 판정 없음
        assertNull(DiceBot.Result(DiceBot.Command(1, 10), listOf(7)).success)
    }

    @Test
    fun `굴림 결과는 항상 1과 면 수 사이의 정수다`() {
        for (sides in DiceBot.supportedSides) {
            val command = DiceBot.Command(1, sides)
            repeat(1000) {
                val roll = DiceBot.roll(command).rolls.single()
                assertTrue("${sides}면체 결과 $roll", roll in 1..sides)
            }
        }
    }

    @Test
    fun `여러 개 굴리면 개수만큼 굴려 합산한다`() {
        val result = DiceBot.roll(DiceBot.Command(5, 6), Random(42))
        assertEquals(5, result.rolls.size)
        assertEquals(result.rolls.sum(), result.total)
    }

    @Test
    fun `표시 문자열 형식 - 한 개는 값만, 여러 개는 내역과 합계`() {
        assertEquals("7", DiceBot.Result(DiceBot.Command(1, 10), listOf(7)).breakdown)
        assertEquals("3 + 5 = 8", DiceBot.Result(DiceBot.Command(2, 6), listOf(3, 5)).breakdown)
    }

    @Test
    fun `expr은 XdY 형식이다`() {
        assertEquals("2d6", DiceBot.Command(2, 6).expr)
    }
}
