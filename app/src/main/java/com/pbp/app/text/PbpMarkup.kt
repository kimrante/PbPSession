package com.pbp.app.text

/**
 * 채팅 본문 문법 파서 (스펙 4장):
 *   마크다운 — **굵게**, *기울임*, ~~취소선~~
 *   루비 문자 — |等臺《등대》 (본문《독음》)
 *   캐릭터 value — {{50}} (발신 시 {값이름} 치환 결과, 파란색 표시)
 * 렌더링(AnnotatedString 변환)은 UI 계층에서 하고, 여기서는 순수 파싱만 한다.
 */
object PbpMarkup {

    sealed interface Node {
        /** 스타일이 적용된 일반 텍스트 조각 */
        data class Span(
            val text: String,
            val bold: Boolean = false,
            val italic: Boolean = false,
            val strike: Boolean = false,
        ) : Node

        /** 루비 문자: base 위(옆)에 작은 독음 */
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

    /** **·*·~~ 구분자를 토글로 처리한다. 짝이 없는 구분자는 그대로 문자로 남긴다. */
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
                // 여는 구분자는 닫는 짝이 있어야 인정, 닫는 구분자는 항상 토글
                text.startsWith("**", i) && (bold || hasClosing(text, i + 2, "**")) -> {
                    flush(); bold = !bold; i += 2
                }
                text.startsWith("~~", i) && (strike || hasClosing(text, i + 2, "~~")) -> {
                    flush(); strike = !strike; i += 2
                }
                // 짝 없는 `**`가 이탤릭 분기로 흘러 서로를 닫힘으로 오인해
                // 조용히 사라지는 버그 방지 — `**`는 굵게 분기에서만 처리 (P2-6)
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
