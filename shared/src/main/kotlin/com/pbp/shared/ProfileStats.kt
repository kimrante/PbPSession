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

    /**
     * 값 이름 가나다순 정렬 — 저장할 때 한 번 돌린다.
     * 한글·영문이 섞여도 사람이 기대하는 순서가 되도록 로케일 대조기를 쓴다.
     */
    fun sortByName(stats: List<Pair<String, String>>): List<Pair<String, String>> {
        val collator = java.text.Collator.getInstance(java.util.Locale.KOREAN)
        return stats.sortedWith(compareBy(collator) { it.first })
    }

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
     * 비교용 정규화 — 괄호·공백·구두점을 지우고 전각을 반각으로 접는다.
     * "근접전(도검)"을 "도검"으로도, "근접전 도검"·"근접전（도검）"으로도 찾게 하려는 것이다.
     */
    private fun fold(s: String): String = buildString {
        s.forEach { ch ->
            // 전각 영숫자·괄호(U+FF01~U+FF5E)는 반각으로
            val c = if (ch.code in 0xFF01..0xFF5E) (ch.code - 0xFEE0).toChar() else ch
            if (!c.isWhitespace() && (c.isLetterOrDigit())) append(c.lowercaseChar())
        }
    }

    /**
     * 채팅 팔레트 추천: 입력 중인 짧은 텍스트가 값 이름과 부분 일치하면
     * `1d100<={이름}` 판정 매크로 후보를 돌려준다. 숫자 값만 판정 대상.
     *
     * 이름 중간·뒤쪽도 잡힌다 — "근접전(도검)"은 "도검"으로도 찾을 수 있어야 한다.
     * 괄호·공백 차이로 놓치지 않도록 양쪽을 [fold]로 정규화해 비교한다.
     * 앞부분 일치를 우선하고 최대 6개.
     */
    fun paletteSuggestions(query: String, stats: List<Pair<String, String>>): List<String> {
        val q = fold(query)
        if (q.isEmpty() || q.length > 12) return emptyList()
        return stats
            .filter { (name, value) ->
                value.trim().toIntOrNull() != null && fold(name).contains(q)
            }
            .sortedByDescending { fold(it.first).startsWith(q) }
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

    /**
     * `"1d100<={민첩}"` → `"민첩"`. 치환이 안 된 판정식에서 값 이름을 캐낸다 (J6) —
     * 모바일·데스크톱이 같은 규칙으로 "값이 없습니다" 다이얼로그를 띄우기 위한 단일 출처.
     */
    fun statNameOf(diceExpr: String): String? =
        placeholder.find(diceExpr)?.groupValues?.get(1)
}
