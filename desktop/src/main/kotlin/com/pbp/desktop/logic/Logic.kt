package com.pbp.desktop.logic

import kotlin.random.Random

/*
 * 안드로이드 앱(app 모듈)의 순수 로직 복제본.
 * TODO: 장기적으로는 KMP 공유 모듈(:shared)로 추출해 중복 제거 (architecture.md 참조)
 */

/** 다이스 명령 파서 겸 굴림기 — `/r 1d6` 또는 `1d6`, 개수 생략 시 1개 */
object DiceBot {
    val supportedSides = listOf(6, 10, 100)
    const val MAX_COUNT = 20
    private val pattern = Regex("""^(?:/r\s+)?(\d*)d(\d+)$""", RegexOption.IGNORE_CASE)

    data class Command(val count: Int, val sides: Int) {
        val expr: String get() = "${count}d${sides}"
    }

    data class Result(val command: Command, val rolls: List<Int>) {
        val total: Int get() = rolls.sum()
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

/** 마크다운(**굵게**·*기울임*·~~취소선~~) + 루비(|等臺《등대》) 파서 */
object PbpMarkup {
    sealed interface Node {
        data class Span(
            val text: String,
            val bold: Boolean = false,
            val italic: Boolean = false,
            val strike: Boolean = false,
        ) : Node

        data class Ruby(val base: String, val ruby: String) : Node
    }

    private val rubyPattern = Regex("""\|([^|《》]+)《([^《》]+)》""")

    fun parse(text: String): List<Node> {
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
                text[i] == '*' && (italic || hasClosing(text, i + 1, "*")) -> {
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
            val quote = match.groupValues[1].ifEmpty { match.groupValues[2] }
            parts += Part.Quote(quote.trim())
            cursor = match.range.last + 1
        }
        val tail = text.substring(cursor).trim()
        if (tail.isNotEmpty()) parts += Part.Narration(tail)
        return parts
    }
}
