package com.pbp.app.dice

import kotlin.random.Random

/**
 * 다이스 명령 파서 겸 굴림기.
 * `/r 1d6` 또는 그냥 `1d6` 형태를 인식한다. 개수를 생략하면 1개(`d6` == `1d6`).
 */
object DiceBot {
    /** 지원하는 주사위 면 수. 새 주사위는 이 목록에만 추가하면 된다. */
    val supportedSides = listOf(6, 10, 100)

    const val MAX_COUNT = 20

    private val pattern = Regex("""^(?:/r\s+)?(\d*)d(\d+)$""", RegexOption.IGNORE_CASE)

    data class Command(val count: Int, val sides: Int) {
        val expr: String get() = "${count}d${sides}"
    }

    data class Result(val command: Command, val rolls: List<Int>) {
        val total: Int get() = rolls.sum()

        /** "7" 또는 "3 + 5 = 8" 형태의 표시용 문자열 */
        val breakdown: String
            get() = if (rolls.size == 1) "$total" else rolls.joinToString(" + ") + " = $total"
    }

    fun parse(text: String): Command? {
        val match = pattern.matchEntire(text.trim()) ?: return null
        val count = match.groupValues[1].ifEmpty { "1" }.toIntOrNull() ?: return null
        val sides = match.groupValues[2].toIntOrNull() ?: return null
        if (count !in 1..MAX_COUNT) return null
        if (sides !in supportedSides) return null
        return Command(count, sides)
    }

    fun roll(command: Command, random: Random = Random.Default): Result =
        Result(command, List(command.count) { random.nextInt(1, command.sides + 1) })
}
