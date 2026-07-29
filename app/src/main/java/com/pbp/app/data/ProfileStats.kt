package com.pbp.app.data

/**
 * 캐릭터별 value(능력치 등) 저장·치환.
 *
 * 저장 형식: 항목은 US(0x1F)로 이름·값을 구분하고, 항목 사이는 RS(0x1E)로 잇는다.
 * 메시지의 `{값이름}`은 발신 시점에 그 캐릭터의 값으로 바뀌어 저장되며,
 * 화면에는 `{{값}}` 마커로 감싸 파란색으로 표시한다 (PbpMarkup.Node.Value).
 */
object ProfileStats {

    private const val FIELD = '\u001F'
    private const val ENTRY = '\u001E'

    private val placeholder = Regex("""\{([^{}]+)\}""")

    fun encode(stats: List<Pair<String, String>>): String =
        stats.filter { it.first.isNotBlank() }
            .joinToString(ENTRY.toString()) { "${it.first}$FIELD${it.second}" }

    fun decode(encoded: String): List<Pair<String, String>> =
        encoded.split(ENTRY)
            .filter { it.isNotEmpty() }
            .map { entry ->
                val idx = entry.indexOf(FIELD)
                if (idx < 0) entry to "" else entry.take(idx) to entry.substring(idx + 1)
            }

    /**
     * 채팅 팔레트 추천: 입력 중인 짧은 텍스트가 값 이름과 부분 일치하면
     * `1d100<={이름}` 판정 매크로 후보를 돌려준다. 숫자 값만 판정 대상.
     * 앞부분 일치를 우선하고 최대 6개.
     */
    fun paletteSuggestions(query: String, stats: List<Pair<String, String>>): List<String> {
        val q = query.trim()
        if (q.isEmpty() || q.length > 12 || q.any { it.isWhitespace() }) return emptyList()
        return stats
            .filter { (name, value) ->
                value.trim().toIntOrNull() != null && name.contains(q, ignoreCase = true)
            }
            .sortedByDescending { it.first.startsWith(q, ignoreCase = true) }
            .map { it.first }
            .take(6)
    }

    /**
     * `{값이름}`을 값으로 바꾼다. 등록되지 않은 이름은 그대로 둔다.
     * @return plain(다이스 파싱용 순수 값) to marked(화면 저장용 `{{값}}` 마커 포함)
     */
    fun substitute(text: String, stats: Map<String, String>): Pair<String, String> {
        if (stats.isEmpty() || '{' !in text) return text to text
        val plain = placeholder.replace(text) { m -> stats[m.groupValues[1]] ?: m.value }
        val marked = placeholder.replace(text) { m ->
            stats[m.groupValues[1]]?.let { "{{$it}}" } ?: m.value
        }
        return plain to marked
    }
}
