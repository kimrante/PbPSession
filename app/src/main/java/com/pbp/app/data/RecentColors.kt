package com.pbp.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 최근 사용한 커스텀 색 — **용도별로 따로** 쌓인다.
 *
 * 이름 색에서 고른 색이 말풍선 색 줄에도 나타나면 어느 쪽에 쓴 색인지 알 수 없어
 * 혼란스럽다는 피드백에 따라, [Slot]마다 독립된 목록을 둔다(초기 구현은 네 곳 공용이었다).
 *
 * 각 목록은 최신순이며 [MAX]개를 넘으면 **가장 오래된 색부터** 밀려난다.
 * [OwnerProfile]과 같은 SharedPreferences에 ARGB CSV로 저장하고, 화면이 즉시
 * 갱신되도록 Compose 상태로 들고 있는다.
 */
object RecentColors {
    /** 목록당 저장 상한 — 넘치면 가장 오래된 색이 사라진다 */
    const val MAX = 5

    /** 색을 쓰는 자리. 자리마다 목록이 따로다 */
    enum class Slot(val key: String) {
        NAME("name"),
        BUBBLE("bubble"),
        OWNER("owner"),
        THEME("theme"),
    }

    private var lists by mutableStateOf<Map<Slot, List<Long>>>(emptyMap())

    fun list(slot: Slot): List<Long> = lists[slot].orEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences("pbp-settings", Context.MODE_PRIVATE)

    private fun keyOf(slot: Slot) = "recentColors_${slot.key}"

    fun load(context: Context) {
        val p = prefs(context)
        lists = Slot.entries.associateWith { slot ->
            p.getString(keyOf(slot), "")
                ?.split(',')
                ?.mapNotNull { it.trim().toLongOrNull() }
                ?.take(MAX)
                .orEmpty()
        }
        // v0.5.0의 공용 목록은 어느 자리에 쓴 색인지 알 수 없어 승계하지 않고 정리한다
        if (p.contains("recentColors")) p.edit().remove("recentColors").apply()
    }

    /** 최신순 앞쪽에 넣고 중복은 제거 — 이미 있던 색을 다시 고르면 맨 앞으로 올라온다 */
    fun add(context: Context, slot: Slot, argb: Long) {
        val current = list(slot)
        val next = (listOf(argb) + current.filterNot { it == argb }).take(MAX)
        if (next == current) return
        lists = lists + (slot to next)
        prefs(context).edit().putString(keyOf(slot), next.joinToString(",")).apply()
    }
}
