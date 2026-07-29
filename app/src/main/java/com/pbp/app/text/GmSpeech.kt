package com.pbp.app.text

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

    private val quotePattern = Regex(""""([^"]+)"|“([^”]+)”""")

    fun split(text: String): List<Part> {
        val parts = mutableListOf<Part>()
        var cursor = 0
        for (match in quotePattern.findAll(text)) {
            val before = text.substring(cursor, match.range.first).trim()
            if (before.isNotEmpty()) parts += Part.Narration(before)
            val quote = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
            // 공백만 있는 인용부는 빈 "???" 말풍선을 만들지 않는다 (P3-8)
            if (quote.isNotEmpty()) parts += Part.Quote(quote)
            cursor = match.range.last + 1
        }
        val tail = text.substring(cursor).trim()
        if (tail.isNotEmpty()) parts += Part.Narration(tail)
        return parts
    }
}
