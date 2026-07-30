package com.pbp.shared

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

    /** 구분자(제어문자)와 중괄호는 인코딩·치환을 깨뜨리므로 저장 시 제거 (P3-11) */
    private fun sanitize(s: String): String =
        s.filterNot { it == FIELD || it == ENTRY || it == '{' || it == '}' }

    /**
     * 맵 형태 값 목록 정리 — 저장 경로가 encode가 아닌 쪽(데스크톱 config.json)에서 쓴다.
     * 이름은 trim, 빈 이름은 버린다. 정리 규칙은 encode와 동일해야 한다.
     */
    fun sanitize(stats: Map<String, String>): Map<String, String> =
        stats.entries.associate { (key, value) -> sanitize(key).trim() to sanitize(value) }
            .filterKeys { it.isNotBlank() }

    fun encode(stats: List<Pair<String, String>>): String =
        stats.map { sanitize(it.first).trim() to sanitize(it.second) }
            .filter { it.first.isNotBlank() }
            // 같은 이름은 마지막 항목이 이긴다 — 편집 중 덮어쓰기·중복 저장 방지 (P1-8)
            .associateBy { it.first }.values
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
            .distinct() // 같은 이름 값이 중복 저장돼 있어도 LazyRow 키 충돌 방지 (P1-8)
            .take(6)
    }

    /** 맵 오버로드 — 데스크톱처럼 값을 Map으로 들고 있는 호출부용 (규칙 동일) */
    fun paletteSuggestions(query: String, stats: Map<String, String>): List<String> =
        paletteSuggestions(query, sanitize(stats).toList())

    /**
     * `{값이름}`을 값으로 바꾼다. 등록되지 않은 이름은 그대로 둔다.
     * @return plain(다이스 파싱용 순수 값) to marked(화면 저장용 `{{값}}` 마커 포함)
     */
    fun substitute(text: String, stats: Map<String, String>): Pair<String, String> {
        if (stats.isEmpty() || '{' !in text) return text to text
        // 저장 경로가 다른 클라이언트(데스크톱 config.json)를 위해 치환 시점에도 정리 —
        // encode를 거친 값은 이미 깨끗하므로 결과가 달라지지 않는다
        val clean = sanitize(stats)
        val plain = placeholder.replace(text) { m -> clean[m.groupValues[1]] ?: m.value }
        val marked = placeholder.replace(text) { m ->
            clean[m.groupValues[1]]?.let { "{{$it}}" } ?: m.value
        }
        return plain to marked
    }
}
