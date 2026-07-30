package com.pbp.shared

import com.pbp.shared.DiceBot
import com.pbp.shared.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {

    /** 1d100<=목표치 굴림 결과 */
    private fun roll(threshold: Int, total: Int) =
        DiceBot.Result(DiceBot.Command(1, 100, "<=", threshold), listOf(total))

    @Test
    fun `CoC7 하향 판정 등급 - 목표치 50`() {
        // 1 = 대성공, ≤10(1/5) = 대단한 성공, ≤25(1/2) = 어려운 성공, ≤50 = 성공
        assertEquals(Rules.Outcome.CRITICAL, Rules.judgeOutcome(Rules.COC7, roll(50, 1)))
        assertEquals(Rules.Outcome.EXTREME, Rules.judgeOutcome(Rules.COC7, roll(50, 10)))
        assertEquals(Rules.Outcome.HARD, Rules.judgeOutcome(Rules.COC7, roll(50, 11)))
        assertEquals(Rules.Outcome.HARD, Rules.judgeOutcome(Rules.COC7, roll(50, 25)))
        assertEquals(Rules.Outcome.SUCCESS, Rules.judgeOutcome(Rules.COC7, roll(50, 26)))
        assertEquals(Rules.Outcome.SUCCESS, Rules.judgeOutcome(Rules.COC7, roll(50, 50)))
        assertEquals(Rules.Outcome.FAIL, Rules.judgeOutcome(Rules.COC7, roll(50, 51)))
        assertEquals(Rules.Outcome.FUMBLE, Rules.judgeOutcome(Rules.COC7, roll(50, 100)))
    }

    @Test
    fun `등급 경계는 내림 계산 - 목표치 65`() {
        // 1/5 = 13, 1/2 = 32
        assertEquals(Rules.Outcome.EXTREME, Rules.judgeOutcome(Rules.COC7, roll(65, 13)))
        assertEquals(Rules.Outcome.HARD, Rules.judgeOutcome(Rules.COC7, roll(65, 14)))
        assertEquals(Rules.Outcome.HARD, Rules.judgeOutcome(Rules.COC7, roll(65, 32)))
        assertEquals(Rules.Outcome.SUCCESS, Rules.judgeOutcome(Rules.COC7, roll(65, 33)))
    }

    @Test
    fun `굴림 1과 100은 목표치와 무관하게 대성공-대실패`() {
        assertEquals(Rules.Outcome.CRITICAL, Rules.judgeOutcome(Rules.COC7, roll(5, 1)))
        assertEquals(Rules.Outcome.FUMBLE, Rules.judgeOutcome(Rules.COC7, roll(99, 100)))
    }

    @Test
    fun `하향 판정이 아니면 성공 실패만 가린다`() {
        val up = DiceBot.Result(DiceBot.Command(1, 100, ">=", 50), listOf(1))
        assertEquals(Rules.Outcome.FAIL, Rules.judgeOutcome(Rules.COC7, up))
        val d20 = DiceBot.Result(DiceBot.Command(1, 20, "<=", 10), listOf(1))
        assertEquals(Rules.Outcome.SUCCESS, Rules.judgeOutcome(Rules.COC7, d20))
    }

    @Test
    fun `비교식이 없으면 판정 없음`() {
        assertNull(Rules.judgeOutcome(Rules.COC7, DiceBot.Result(DiceBot.Command(1, 100), listOf(7))))
    }

    @Test
    fun `표기와 성공 여부`() {
        assertEquals("대단한 성공", Rules.outcomeLabel(Rules.Outcome.EXTREME))
        assertEquals("대실패", Rules.outcomeLabel(Rules.Outcome.FUMBLE))
        assertNull(Rules.outcomeLabel(null))
        assertTrue(Rules.isSuccess(Rules.Outcome.CRITICAL))
        assertTrue(Rules.isSuccess(Rules.Outcome.HARD))
        assertFalse(Rules.isSuccess(Rules.Outcome.FUMBLE))
        assertFalse(Rules.isSuccess(Rules.Outcome.FAIL))
    }
}
