package com.pbp.shared

/**
 * 채팅 본문 문법 파서 (스펙 4장):
 *   마크다운 — **굵게**, *기울임*, ~~취소선~~
 *   루비 문자 — (루비)[문자] (괄호=위에 붙는 독음, 대괄호=본문)
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

    /**
     * 루비(위첨자 독음) — `(루비)[문자]`. 괄호가 위에 작게 붙는 독음, 대괄호가 본문이다.
     * 예: `(등대)[等臺]`를 쓰면 等臺 위에 "등대"가 작게 붙는다.
     */
    private val rubyPattern = Regex("""\(([^()\[\]]+)\)\[([^\[\]]+)]""")
    private val valuePattern = Regex("""\{\{([^{}]+)\}\}""")

    fun parse(text: String): List<Node> {
        // 값·루비 자리를 먼저 잡고, 그 사이 텍스트만 스타일 파서에 넘긴다.
        // 예전에는 값→루비→스타일 순으로 잘라 넘겨서, `**굵게 (독음)[本文] 계속**`처럼
        // 스타일이 루비를 가로지르면 여는 `**`와 닫는 `**`가 서로 다른 조각에 떨어져
        // 짝을 못 찾고 별표가 그대로 찍혔다 (S3).
        val specials = mutableListOf<Pair<IntRange, Node>>()
        valuePattern.findAll(text).forEach {
            specials += it.range to Node.Value(it.groupValues[1])
        }
        rubyPattern.findAll(text).forEach { match ->
            // 값 안쪽의 루비는 값이 이긴다 (예전 파싱 순서와 같은 우선순위)
            val overlaps = specials.any { (range, _) ->
                match.range.first <= range.last && range.first <= match.range.last
            }
            if (!overlaps) {
                specials += match.range to
                    Node.Ruby(base = match.groupValues[2], ruby = match.groupValues[1])
            }
        }
        specials.sortBy { it.first.first }

        // 텍스트 조각과 비텍스트 노드를 순서대로 늘어놓는다
        val pieces = mutableListOf<Any>()
        var cursor = 0
        specials.forEach { (range, node) ->
            if (range.first > cursor) pieces += text.substring(cursor, range.first)
            pieces += node
            cursor = range.last + 1
        }
        if (cursor < text.length) pieces += text.substring(cursor)

        // 스타일 토글 상태를 조각 사이로 이어 간다. 닫는 짝 탐색도 뒤 조각까지 본다.
        val nodes = mutableListOf<Node>()
        var style = Style()
        pieces.forEachIndexed { index, piece ->
            when (piece) {
                is String -> {
                    val tail = pieces.drop(index + 1).filterIsInstance<String>().joinToString("")
                    val (spans, next) = parseStyled(piece, tail, style)
                    nodes += spans
                    style = next
                }
                is Node -> nodes += piece
            }
        }
        return nodes
    }

    /** 인라인 스타일 토글 상태 — 조각을 넘어가며 이어진다 */
    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
    )

    /**
     * **·*·~~ 구분자를 토글로 처리한다. 짝이 없는 구분자는 그대로 문자로 남긴다.
     *
     * @param tail 이 조각 뒤에 이어지는 텍스트 — 닫는 짝이 루비/값 너머에 있을 수 있어
     *   여는 구분자 판정에 함께 본다.
     * @param incoming 앞 조각에서 넘어온 토글 상태
     */
    private fun parseStyled(
        text: String,
        tail: String,
        incoming: Style,
    ): Pair<List<Node.Span>, Style> {
        val spans = mutableListOf<Node.Span>()
        val buf = StringBuilder()
        var bold = incoming.bold
        var italic = incoming.italic
        var strike = incoming.strike
        // 닫는 짝은 이 조각 밖에도 있을 수 있다
        val lookahead = text + tail

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
                text.startsWith("**", i) && (bold || hasClosing(lookahead, i + 2, "**")) -> {
                    flush(); bold = !bold; i += 2
                }
                text.startsWith("~~", i) && (strike || hasClosing(lookahead, i + 2, "~~")) -> {
                    flush(); strike = !strike; i += 2
                }
                // 짝 없는 `**`가 이탤릭 분기로 흘러 서로를 닫힘으로 오인해
                // 조용히 사라지는 버그 방지 — `**`는 굵게 분기에서만 처리 (P2-6)
                text[i] == '*' && !text.startsWith("**", i) &&
                    (italic || hasClosing(lookahead, i + 1, "*")) -> {
                    flush(); italic = !italic; i += 1
                }
                else -> {
                    buf.append(text[i]); i += 1
                }
            }
        }
        flush()
        return spans to Style(bold, italic, strike)
    }

    private fun hasClosing(text: String, from: Int, marker: String): Boolean =
        text.indexOf(marker, from) >= 0
}
