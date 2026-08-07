package com.pbp.shared

/**
 * 서술자(GM) 출력 규칙 (스펙 4장):
 * GM 발화는 명조체 서술 문단으로 렌더링하되,
 * 발화 중 " " 안의 문장만 분리하여 일반 말풍선으로 출력한다.
 */
object GmSpeech {

    sealed interface Part {
        data class Narration(val text: String) : Part
        data class Quote(val text: String) : Part
    }

    // 줄바꿈을 넘지 않는다 — 홀수 개 따옴표가 있으면 문단을 통째로 삼키는
    // 거대 인용이 만들어졌다 (E11)
    private val quotePattern = Regex("\"([^\"\\n]+)\"|\u201C([^\u201D\\n]+)\u201D")

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
        // 본문이 `" "`처럼 **공백만 든 따옴표**뿐이면 위 규칙이 전부 걸러 내 빈 목록이
        // 된다. 그러면 그 메시지는 화면에도 안 그려지고 내보내기에서도 통째로 빠진다 —
        // 보낸 사람에겐 사라진 것처럼 보인다. 아무것도 못 건졌으면 원문을 그대로 둔다 (K5)
        if (parts.isEmpty() && text.isNotBlank()) parts += Part.Narration(text.trim())
        return parts
    }

    /**
     * 본문 전체가 한 덩어리 대사인가 — 맞으면 따옴표를 벗긴 알맹이 (C5).
     * 모바일·PC가 같은 규칙으로 인용 말풍선을 만든다.
     */
    fun quoteContent(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.length < 2) return null
        if (trimmed.first() !in "\"“" || trimmed.last() !in "\"”") return null
        return trimmed.substring(1, trimmed.length - 1).trim().ifEmpty { null }
    }
}
