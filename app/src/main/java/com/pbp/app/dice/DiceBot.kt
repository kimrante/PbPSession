package com.pbp.app.dice

import kotlin.random.Random

/**
 * 다이스 명령 파서 겸 굴림기.
 *
 * 인식 규칙:
 *  - `/r 1d6`, `1d6`, `d6` — 메시지 전체가 명령일 때
 *  - `1d6 공격한다!` — 메시지가 `XdY` + 공백으로 시작하면 나머지는 대사, 굴림은 함께 실행
 *  - `1d100<=50`, `2d6 > 7` — 비교식(<, >, <=, >=)이 붙으면 합계를 판정해 성공/실패 표시
 */
object DiceBot {
    /** 지원하는 주사위 면 수. 새 주사위는 이 목록에만 추가하면 된다. */
    val supportedSides = listOf(6, 10, 100)

    const val MAX_COUNT = 20

    // (개수)d(면수) [비교연산 임계값] — 메시지 시작에서만. 명령 뒤는 공백 또는 끝.
    private val pattern = Regex(
        """^(?:/r\s+)?(\d*)d(\d+)\s*(?:(<=|>=|<|>)\s*(\d+))?(?=\s|$)""",
        RegexOption.IGNORE_CASE,
    )

    data class Command(
        val count: Int,
        val sides: Int,
        val op: String? = null,
        val threshold: Int? = null,
    ) {
        val expr: String
            get() = "${count}d${sides}" + (op?.let { "$it${threshold}" } ?: "")
    }

    data class Result(val command: Command, val rolls: List<Int>) {
        val total: Int get() = rolls.sum()

        /** "7" 또는 "3 + 5 = 8" 형태의 표시용 문자열 */
        val breakdown: String
            get() = if (rolls.size == 1) "$total" else rolls.joinToString(" + ") + " = $total"

        /** 비교식 판정 결과. 비교식이 없으면 null */
        val success: Boolean?
            get() {
                val op = command.op ?: return null
                val x = command.threshold ?: return null
                return when (op) {
                    "<" -> total < x
                    ">" -> total > x
                    "<=" -> total <= x
                    ">=" -> total >= x
                    else -> null
                }
            }
    }

    /**
     * 메시지에서 다이스 명령을 찾는다.
     * 메시지가 명령으로 시작하면(뒤에 공백/끝) 명령을 돌려주고, 아니면 null.
     */
    fun parse(text: String): Command? {
        val match = pattern.find(text.trim()) ?: return null
        if (match.range.first != 0) return null
        val count = match.groupValues[1].ifEmpty { "1" }.toIntOrNull() ?: return null
        val sides = match.groupValues[2].toIntOrNull() ?: return null
        if (count !in 1..MAX_COUNT) return null
        if (sides !in supportedSides) return null
        val op = match.groupValues[3].ifEmpty { null }
        val threshold = match.groupValues[4].toIntOrNull()
        if (op != null && threshold == null) return null
        return Command(count, sides, op, threshold)
    }

    fun roll(command: Command, random: Random = Random.Default): Result =
        Result(command, List(command.count) { random.nextInt(1, command.sides + 1) })
}
