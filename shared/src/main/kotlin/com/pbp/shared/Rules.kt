package com.pbp.shared

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

    /** 판정 결과 등급 — Message.diceOutcome에 저장되는 값 */
    object Outcome {
        const val CRITICAL = "critical"   // 대성공
        const val EXTREME = "extreme"     // 대단한 성공
        const val HARD = "hard"           // 어려운 성공
        const val SUCCESS = "success"     // 성공
        const val FAIL = "fail"           // 실패
        const val FUMBLE = "fumble"       // 대실패
    }

    /** 화면·로그에 쓰는 한국어 표기. 알 수 없는 값이면 null */
    fun outcomeLabel(outcome: String?): String? = when (outcome) {
        Outcome.CRITICAL -> "대성공"
        Outcome.EXTREME -> "대단한 성공"
        Outcome.HARD -> "어려운 성공"
        Outcome.SUCCESS -> "성공"
        Outcome.FAIL -> "실패"
        Outcome.FUMBLE -> "대실패"
        else -> null
    }

    fun isSuccess(outcome: String?): Boolean = outcome in setOf(
        Outcome.CRITICAL, Outcome.EXTREME, Outcome.HARD, Outcome.SUCCESS,
    )

    /**
     * 굴림 결과의 판정 등급.
     *
     * COC7은 `<=` 하향 판정에 성공 단계가 있다: 굴림 1은 대성공, 목표치의 1/5 이하는
     * 대단한 성공, 1/2 이하는 어려운 성공, 100은 대실패(내림 계산).
     * 그 밖의 비교식·룰은 성공/실패만 가린다.
     */
    fun judgeOutcome(rule: String, result: DiceBot.Result): String? {
        val success = result.success ?: return null
        val command = result.command
        val threshold = command.threshold
        val coc7Downward = rule == COC7 && command.op == "<=" && threshold != null &&
            command.sides == 100
        if (!coc7Downward) return if (success) Outcome.SUCCESS else Outcome.FAIL
        return when {
            result.total == 1 -> Outcome.CRITICAL
            result.total == 100 -> Outcome.FUMBLE
            !success -> Outcome.FAIL
            result.total <= threshold!! / 5 -> Outcome.EXTREME
            result.total <= threshold / 2 -> Outcome.HARD
            else -> Outcome.SUCCESS
        }
    }
}
