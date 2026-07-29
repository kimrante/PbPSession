package com.pbp.app.dice

/**
 * 방에 지정하는 TRPG 룰. 채팅 팔레트의 판정 매크로가 어떤 다이스로
 * 굴릴지를 결정한다. 새 룰을 추가하면 all 목록과 judgeCommand에 분기를 더한다.
 */
object Rules {
    const val COC7 = "coc7"

    /** (저장 키, 표시 이름) */
    val all = listOf(COC7 to "크툴루의 부름 7판")

    fun label(rule: String): String = all.firstOrNull { it.first == rule }?.second ?: rule

    /** 룰별 판정 매크로 — 값 이름으로 다이스 명령을 만든다 */
    fun judgeCommand(rule: String, statName: String): String = when (rule) {
        // COC7: 1d100 하향 판정
        else -> "1d100<={$statName}"
    }
}
