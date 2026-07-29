package com.pbp.desktop.logic

import kotlin.random.Random

/*
 * 안드로이드 앱(app 모듈)의 순수 로직 복제본.
 * TODO: 장기적으로는 KMP 공유 모듈(:shared)로 추출해 중복 제거 (architecture.md 참조)
 */

/** 다이스 명령 파서 겸 굴림기 — 앱과 동일: d6/d10/d20/d100, d66 특수, 비교식 판정 */
object DiceBot {
    val supportedSides = listOf(6, 10, 20, 100)
    const val D66 = 66
    const val MAX_COUNT = 20
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
        val total: Int
            get() = if (command.sides == D66) rolls[0] * 10 + rolls[1] else rolls.sum()

        val breakdown: String
            get() = when {
                command.sides == D66 || rolls.size == 1 -> "$total"
                else -> rolls.joinToString(" + ") + " = $total"
            }

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

    fun parse(text: String): Command? {
        val match = pattern.find(text.trim()) ?: return null
        if (match.range.first != 0) return null
        val count = match.groupValues[1].ifEmpty { "1" }.toIntOrNull() ?: return null
        val sides = match.groupValues[2].toIntOrNull() ?: return null
        if (count !in 1..MAX_COUNT) return null
        if (sides == D66) {
            if (count != 1) return null
        } else if (sides !in supportedSides) return null
        val op = match.groupValues[3].ifEmpty { null }
        val threshold = match.groupValues[4].toIntOrNull()
        if (op != null && threshold == null) return null
        return Command(count, sides, op, threshold)
    }

    fun roll(command: Command, random: Random = Random.Default): Result =
        if (command.sides == D66) {
            Result(command, listOf(random.nextInt(1, 7), random.nextInt(1, 7)))
        } else {
            Result(command, List(command.count) { random.nextInt(1, command.sides + 1) })
        }
}

/** 마크다운(**굵게**·*기울임*·~~취소선~~) + 루비(|等臺《등대》) + 값 마커({{50}}) 파서 */
object PbpMarkup {
    sealed interface Node {
        data class Span(
            val text: String,
            val bold: Boolean = false,
            val italic: Boolean = false,
            val strike: Boolean = false,
        ) : Node

        data class Ruby(val base: String, val ruby: String) : Node

        /** 캐릭터 value 치환 결과 — 파란색으로 표시 */
        data class Value(val text: String) : Node
    }

    private val rubyPattern = Regex("""\|([^|《》]+)《([^《》]+)》""")
    private val valuePattern = Regex("""\{\{([^{}]+)\}\}""")

    fun parse(text: String): List<Node> {
        val nodes = mutableListOf<Node>()
        var cursor = 0
        for (match in valuePattern.findAll(text)) {
            if (match.range.first > cursor) {
                nodes += parseRuby(text.substring(cursor, match.range.first))
            }
            nodes += Node.Value(match.groupValues[1])
            cursor = match.range.last + 1
        }
        if (cursor < text.length) nodes += parseRuby(text.substring(cursor))
        return nodes
    }

    private fun parseRuby(text: String): List<Node> {
        val nodes = mutableListOf<Node>()
        var cursor = 0
        for (match in rubyPattern.findAll(text)) {
            if (match.range.first > cursor) {
                nodes += parseStyled(text.substring(cursor, match.range.first))
            }
            nodes += Node.Ruby(match.groupValues[1], match.groupValues[2])
            cursor = match.range.last + 1
        }
        if (cursor < text.length) nodes += parseStyled(text.substring(cursor))
        return nodes
    }

    private fun parseStyled(text: String): List<Node.Span> {
        val spans = mutableListOf<Node.Span>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        var strike = false

        fun flush() {
            if (buf.isNotEmpty()) {
                spans += Node.Span(buf.toString(), bold, italic, strike)
                buf.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) && (bold || hasClosing(text, i + 2, "**")) -> {
                    flush(); bold = !bold; i += 2
                }
                text.startsWith("~~", i) && (strike || hasClosing(text, i + 2, "~~")) -> {
                    flush(); strike = !strike; i += 2
                }
                // 짝 없는 `**` 소실 방지 — `**`는 굵게 분기에서만 처리 (P2-6)
                text[i] == '*' && !text.startsWith("**", i) &&
                    (italic || hasClosing(text, i + 1, "*")) -> {
                    flush(); italic = !italic; i += 1
                }
                else -> {
                    buf.append(text[i]); i += 1
                }
            }
        }
        flush()
        return spans
    }

    private fun hasClosing(text: String, from: Int, marker: String): Boolean =
        text.indexOf(marker, from) >= 0
}

/**
 * 캐릭터 값 치환 — 안드로이드 ProfileStats.substitute와 동일 (P2-5).
 * `{값이름}` → 값. 화면 저장용은 `{{값}}` 마커(파란 표시), 다이스 파싱용은 순수 값.
 */
object ProfileStats {
    private val placeholder = Regex("""\{([^{}]+)\}""")

    /** 중괄호가 든 값은 마커 파싱을 깨뜨리므로 제거 — 안드로이드 P3-11과 동일 (C11) */
    fun sanitize(stats: Map<String, String>): Map<String, String> =
        stats.entries.associate { (key, value) ->
            key.filterNot { it == '{' || it == '}' }.trim() to
                value.filterNot { it == '{' || it == '}' }
        }.filterKeys { it.isNotBlank() }

    fun substitute(text: String, stats: Map<String, String>): Pair<String, String> {
        if (stats.isEmpty() || '{' !in text) return text to text
        val clean = sanitize(stats)
        val plain = placeholder.replace(text) { m -> clean[m.groupValues[1]] ?: m.value }
        val marked = placeholder.replace(text) { m ->
            clean[m.groupValues[1]]?.let { "{{$it}}" } ?: m.value
        }
        return plain to marked
    }

    /** 채팅 팔레트 추천 — 모바일 ProfileStats.paletteSuggestions와 동일 규칙 */
    fun paletteSuggestions(query: String, stats: Map<String, String>): List<String> {
        val q = query.trim()
        if (q.isEmpty() || q.length > 12 || q.any { it.isWhitespace() }) return emptyList()
        return sanitize(stats).entries
            .filter { (name, value) ->
                value.trim().toIntOrNull() != null && name.contains(q, ignoreCase = true)
            }
            .sortedByDescending { it.key.startsWith(q, ignoreCase = true) }
            .map { it.key }
            .distinct()
            .take(6)
    }
}

/** 판정 등급 — 안드로이드 Rules와 동일 규칙 (COC7 하향 판정의 성공 단계) */
object Rules {
    const val COC7 = "coc7"

    /** 룰별 판정 매크로 — 모바일 Rules.judgeCommand와 동일 */
    fun judgeCommand(rule: String, statName: String): String = when (rule) {
        else -> "1d100<={$statName}"
    }

    fun judgeOutcome(rule: String, result: DiceBot.Result): String? {
        val success = result.success ?: return null
        val command = result.command
        val threshold = command.threshold
        val coc7Downward = rule == COC7 && command.op == "<=" && threshold != null &&
            command.sides == 100
        if (!coc7Downward) return if (success) "success" else "fail"
        return when {
            result.total == 1 -> "critical"
            result.total == 100 -> "fumble"
            !success -> "fail"
            result.total <= threshold!! / 5 -> "extreme"
            result.total <= threshold / 2 -> "hard"
            else -> "success"
        }
    }

    fun outcomeLabel(outcome: String?): String? = when (outcome) {
        "critical" -> "대성공"
        "extreme" -> "대단한 성공"
        "hard" -> "어려운 성공"
        "success" -> "성공"
        "fail" -> "실패"
        "fumble" -> "대실패"
        else -> null
    }

    fun isSuccess(outcome: String?): Boolean =
        outcome in setOf("critical", "extreme", "hard", "success")
}

/** GM 발화에서 " " 인용만 말풍선으로 분리 */
object GmSpeech {
    sealed interface Part {
        data class Narration(val text: String) : Part
        data class Quote(val text: String) : Part
    }

    private val quotePattern = Regex(""""([^"]+)"|“([^”]+)”""")

    fun split(text: String): List<Part> {
        val parts = mutableListOf<Part>()
        var cursor = 0
        for (match in quotePattern.findAll(text)) {
            val before = text.substring(cursor, match.range.first).trim()
            if (before.isNotEmpty()) parts += Part.Narration(before)
            val quote = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
            // 공백만 있는 인용부는 빈 말풍선을 만들지 않는다 (P3-8)
            if (quote.isNotEmpty()) parts += Part.Quote(quote)
            cursor = match.range.last + 1
        }
        val tail = text.substring(cursor).trim()
        if (tail.isNotEmpty()) parts += Part.Narration(tail)
        return parts
    }
}
