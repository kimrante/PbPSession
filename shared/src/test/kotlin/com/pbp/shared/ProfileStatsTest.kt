package com.pbp.shared

import com.pbp.shared.ProfileStats
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileStatsTest {

    @Test
    fun `인코딩과 디코딩은 왕복이 된다`() {
        val stats = listOf("은신" to "50", "근력" to "12")
        assertEquals(stats, ProfileStats.decode(ProfileStats.encode(stats)))
        assertEquals(emptyList<Pair<String, String>>(), ProfileStats.decode(""))
    }

    @Test
    fun `이름이 빈 항목은 저장하지 않는다`() {
        assertEquals("", ProfileStats.encode(listOf("" to "50", "  " to "3")))
    }

    @Test
    fun `같은 이름은 마지막 값이 이기고 중복 저장되지 않는다`() {
        val decoded = ProfileStats.decode(ProfileStats.encode(listOf("은신" to "50", "은신" to "70")))
        assertEquals(listOf("은신" to "70"), decoded)
    }

    @Test
    fun `구분자 제어문자와 중괄호는 저장 시 제거된다`() {
        val decoded = ProfileStats.decode(
            ProfileStats.encode(listOf("은신" to "50", "{점프}" to "{3}"))
        )
        assertEquals(listOf("은신" to "50", "점프" to "3"), decoded)
    }

    @Test
    fun `등록된 값이름은 치환되고 파란색 마커로 감싼다`() {
        val stats = mapOf("은신" to "50")
        val (plain, marked) = ProfileStats.substitute("나 {은신}할래.", stats)
        assertEquals("나 50할래.", plain)
        assertEquals("나 {{50}}할래.", marked)
    }

    @Test
    fun `등록되지 않은 이름과 값 없는 캐릭터는 원문 그대로다`() {
        val (plain, marked) = ProfileStats.substitute("나 {점프}할래.", mapOf("은신" to "50"))
        assertEquals("나 {점프}할래.", plain)
        assertEquals("나 {점프}할래.", marked)
        assertEquals("{은신}" to "{은신}", ProfileStats.substitute("{은신}", emptyMap()))
    }

    @Test
    fun `팔레트 추천 - 부분 일치하는 숫자 값만, 앞부분 일치 우선`() {
        val stats = listOf(
            "LUK" to "50", "LUKmax" to "99", "SAN" to "50",
            "피해 보너스" to "1D4", "memo" to "긴 소개글",
        )
        assertEquals(listOf("LUK", "LUKmax"), ProfileStats.paletteSuggestions("L", stats))
        assertEquals(listOf("LUK", "LUKmax"), ProfileStats.paletteSuggestions("luk", stats))
        assertEquals(listOf("SAN"), ProfileStats.paletteSuggestions("SAN", stats))
        // 숫자가 아닌 값(1D4, memo)은 판정 후보가 아니다
        assertEquals(emptyList<String>(), ProfileStats.paletteSuggestions("피해", stats))
        assertEquals(emptyList<String>(), ProfileStats.paletteSuggestions("memo", stats))
        // 빈 입력·공백 포함·긴 문장은 추천하지 않는다
        assertEquals(emptyList<String>(), ProfileStats.paletteSuggestions("", stats))
        assertEquals(emptyList<String>(), ProfileStats.paletteSuggestions("LUK 판정", stats))
        assertEquals(emptyList<String>(), ProfileStats.paletteSuggestions("아주아주아주아주긴입력이다", stats))
    }

    @Test
    fun `다이스 비교식에 값을 쓸 수 있다 - plain은 순수 숫자`() {
        val (plain, _) = ProfileStats.substitute("1d100<={은신} 판정", mapOf("은신" to "50"))
        assertEquals("1d100<=50 판정", plain)
    }

    @Test
    fun `이름 중간·뒤쪽으로도 찾는다 — 괄호·공백 차이는 무시`() {
        val stats = listOf("근접전(도검)" to "65", "회피" to "40", "근접전 격투" to "50")
        // 뒤쪽 조각만 입력해도 추천된다
        assertEquals(listOf("근접전(도검)"), ProfileStats.paletteSuggestions("도검", stats))
        // 앞부분 일치가 먼저 온다
        assertEquals(
            listOf("근접전(도검)", "근접전 격투"),
            ProfileStats.paletteSuggestions("근접전", stats),
        )
        // 전각 괄호로 입력해도 같은 결과
        assertEquals(listOf("근접전(도검)"), ProfileStats.paletteSuggestions("（도검）", stats))
        // 이름에 없는 조각은 추천하지 않는다
        assertEquals(emptyList<String>(), ProfileStats.paletteSuggestions("사격", stats))
    }

    @Test
    fun `값 목록을 가나다순으로 정렬한다`() {
        val stats = listOf("회피" to "40", "근접전" to "65", "APP" to "50")
        assertEquals(
            listOf("APP", "근접전", "회피"),
            ProfileStats.sortByName(stats).map { it.first },
        )
    }
}
