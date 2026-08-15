package com.pbp.shared

import kotlin.random.Random

/**
 * 다이스 명령 파서 겸 굴림기.
 *
 * 인식 규칙:
 *  - `/r 1d6`, `1d6`, `d6` — 메시지 전체가 명령일 때
 *  - `1d6 공격한다!` — 메시지가 `XdY` + 공백으로 시작하면 나머지는 대사, 굴림은 함께 실행
 *  - `1d100<=50`, `2d6 > 7` — 비교식(<, >, <=, >=)이 붙으면 합계를 판정해 성공/실패 표시
 *  - `3d6*5`, `2d6+3`, `1d100-10` — 굴림 뒤에 사칙연산을 붙이면 **계산까지 마친 값**이 나온다
 *    (`3d6`이 12면 `3d6*5`는 60). 여러 개를 이어 쓰면 **왼쪽부터 차례로** 적용한다
 *  - `d66` — 특수 주사위: 1d6×10 + 1d6 (십의 자리·일의 자리)
 */
object DiceBot {
    /** 지원하는 주사위 면 수. 새 주사위는 이 목록에만 추가하면 된다. */
    val supportedSides = listOf(6, 10, 20, 100)

    /** 특수 주사위: d66 = 1d6*10 + 1d6 */
    const val D66 = 66

    const val MAX_COUNT = 20

    /** 굴림 뒤 연산에 쓸 수 있는 수의 상한 — 터무니없는 값으로 자릿수를 불리지 않는다 */
    const val MAX_OPERAND = 1000

    /** 이어 붙일 수 있는 연산의 개수 */
    const val MAX_MODIFIERS = 4

    // (개수)d(면수) [사칙연산...] [비교연산 임계값] — 메시지 시작에서만. 명령 뒤는 공백 또는 끝.
    private val pattern = Regex(
        """^(?:/r\s+)?(\d*)d(\d+)((?:\s*[-+*/x×÷]\s*\d+)*)\s*(?:(<=|>=|<|>)\s*(\d+))?(?=\s|$)""",
        RegexOption.IGNORE_CASE,
    )

    /** 사칙연산 한 조각 (연산자는 +, -, *, / 로 정규화해 들고 있는다) */
    data class Modifier(val op: Char, val value: Int) {
        /** 화면에는 곱셈·나눗셈을 ×, ÷로 — 별표·슬래시는 눈에 잘 안 들어온다 */
        val symbol: String
            get() = when (op) {
                '*' -> "×"
                '/' -> "÷"
                else -> op.toString()
            }
    }

    data class Command(
        val count: Int,
        val sides: Int,
        val op: String? = null,
        val threshold: Int? = null,
        /** 굴림 뒤에 왼쪽부터 차례로 적용할 연산 */
        val modifiers: List<Modifier> = emptyList(),
    ) {
        val expr: String
            get() = "${count}d${sides}" +
                modifiers.joinToString("") { "${it.symbol}${it.value}" } +
                (op?.let { "$it${threshold}" } ?: "")
    }

    data class Result(val command: Command, val rolls: List<Int>) {
        /** 주사위만의 합 — 뒤에 붙은 연산을 적용하기 전 값 */
        val rollTotal: Int
            get() = if (command.sides == D66) rolls[0] * 10 + rolls[1] else rolls.sum()

        /** 연산까지 마친 최종 값. 비교식·판정 등급도 이 값으로 가린다 */
        val total: Int
            get() = command.modifiers.fold(rollTotal) { acc, modifier ->
                when (modifier.op) {
                    '+' -> acc + modifier.value
                    '-' -> acc - modifier.value
                    '*' -> acc * modifier.value
                    // 0으로 나누는 명령은 파서가 막는다
                    '/' -> acc / modifier.value
                    else -> acc
                }
            }

        /**
         * "7", "3 + 5 = 8", "3 + 5 = 8 × 5 = 40" 형태의 표시용 문자열.
         * d66은 계산식 없이 최종 값만.
         */
        val breakdown: String
            get() {
                val rolled = when {
                    command.sides == D66 || rolls.size == 1 -> "$rollTotal"
                    else -> rolls.joinToString(" + ") + " = $rollTotal"
                }
                if (command.modifiers.isEmpty()) return rolled
                // 굴림과 계산을 화살표로 가른다 — "3 + 6 = 9 + 3 = 12"처럼 이어 붙이면
                // 뒤의 +3이 주사위 눈으로 읽힌다
                return rolled + " → " +
                    command.modifiers.joinToString(" ") { "${it.symbol}${it.value}" } +
                    " = $total"
            }

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
        if (sides == D66) {
            if (count != 1) return null // d66은 1회만
        } else if (sides !in supportedSides) return null
        val modifiers = parseModifiers(match.groupValues[3]) ?: return null
        val op = match.groupValues[4].ifEmpty { null }
        val threshold = match.groupValues[5].toIntOrNull()
        if (op != null && threshold == null) return null
        // d66은 십의 자리를 합성한 값이라 크기 비교가 의미 없다 — 받아 주면
        // "성공/실패"가 아무 뜻 없이 찍힌다 (E10)
        if (sides == D66 && op != null) return null
        return Command(count, sides, op, threshold, modifiers)
    }

    /**
     * `*5`, `+3 -1` 같은 꼬리를 연산 목록으로. 형식이 어긋나면 null —
     * 명령 전체를 취소해 **계산이 조용히 빠진 채 굴러가는 일이 없게** 한다.
     */
    private fun parseModifiers(raw: String): List<Modifier>? {
        if (raw.isBlank()) return emptyList()
        val parts = Regex("""([-+*/x×÷])\s*(\d+)""", RegexOption.IGNORE_CASE).findAll(raw).toList()
        if (parts.isEmpty() || parts.size > MAX_MODIFIERS) return null
        val out = parts.map { part ->
            val op = when (part.groupValues[1].lowercase()) {
                "x", "×", "*" -> '*'
                "÷", "/" -> '/'
                "+" -> '+'
                else -> '-'
            }
            val value = part.groupValues[2].toIntOrNull() ?: return null
            if (value > MAX_OPERAND) return null
            if (op == '/' && value == 0) return null // 0으로 나눌 수는 없다
            Modifier(op, value)
        }
        return out
    }

    fun roll(command: Command, random: Random = Random.Default): Result =
        if (command.sides == D66) {
            Result(command, listOf(random.nextInt(1, 7), random.nextInt(1, 7)))
        } else {
            Result(command, List(command.count) { random.nextInt(1, command.sides + 1) })
        }
}
