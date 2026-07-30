package com.pbp.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 최근 사용한 커스텀 색 (목업 mockup-owner-profile 01장).
 *
 * 커스텀 컬러를 적용할 때마다 최신순으로 쌓이고, [MAX]개가 차면 **가장 오래된 것부터**
 * 밀려난다. 오너 컬러·이름 색·말풍선 색·방 테마 컬러 네 곳이 같은 목록을 공유하므로
 * 한 곳에서 고른 색이 나머지 세 곳에도 바로 나타난다.
 *
 * [OwnerProfile]과 같은 SharedPreferences에 ARGB CSV로 저장하고, 화면이 즉시
 * 갱신되도록 Compose 상태로 들고 있는다.
 */
object RecentColors {
    /** 저장 상한 — 넘치면 가장 오래된 색이 사라진다 */
    const val MAX = 5

    private const val KEY = "recentColors"

    var list by mutableStateOf<List<Long>>(emptyList())
        private set

    private fun prefs(context: Context) =
        context.getSharedPreferences("pbp-settings", Context.MODE_PRIVATE)

    fun load(context: Context) {
        list = prefs(context).getString(KEY, "")
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.take(MAX)
            .orEmpty()
    }

    /** 최신순 앞쪽에 넣고 중복은 제거 — 이미 있던 색을 다시 고르면 맨 앞으로 올라온다 */
    fun add(context: Context, argb: Long) {
        val next = (listOf(argb) + list.filterNot { it == argb }).take(MAX)
        if (next == list) return
        list = next
        prefs(context).edit().putString(KEY, next.joinToString(",")).apply()
    }
}
