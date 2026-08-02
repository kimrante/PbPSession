package com.pbp.shared

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 대화 로그의 날짜 구분 — 화면(모바일·PC)과 HTML 내보내기가 **같은 문구·같은 경계**를
 * 쓰도록 한 곳에 모았다. 갈라지면 같은 로그인데 날짜가 다른 자리에서 끊긴다.
 *
 * 경계는 **기기 시간대의 달력 날짜** 기준이다. UTC로 자르면 자정 전후 대화가
 * 엉뚱한 날로 넘어간다.
 */
object ChatDates {
    /** "2026년 8월 2일" — 사람이 읽는 표기 */
    fun label(millis: Long): String =
        SimpleDateFormat("yyyy년 M월 d일", Locale.KOREAN).format(Date(millis))

    /** "2026-08-02" — 날짜가 바뀌었는지 비교하는 키 */
    fun dayKey(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

    /** 같은 날인가 — 날짜 구분선을 넣을지 판단한다 */
    fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}
