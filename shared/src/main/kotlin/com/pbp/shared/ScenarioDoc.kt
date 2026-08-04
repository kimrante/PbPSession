package com.pbp.shared

/**
 * 시나리오 뷰어의 순수 규칙 — 링크 검증과 문장 분리 (V0).
 *
 * 플랫폼 의존이 없다. HTTP는 호출부([앱의 ScenarioFetcher])가 맡는다 — 여기서
 * 네트워크를 다루면 규칙을 테스트로 고정할 수 없다.
 *
 * [extractDocId]는 docId를 뽑는 김에 **호스트 검증도 겸한다**. 이 검증이 없으면
 * 기능이 "아무 URL이나 받아 오는 창"이 되어 버린다 — 뷰어 링크만 받는다.
 */
object ScenarioDoc {

    /** docId에 허용하는 글자 — 구글이 쓰는 URL-safe base64 범위 */
    private val docIdPattern = Regex("^[A-Za-z0-9_-]+$")

    /**
     * 뷰어 링크에서 docId를 뽑는다. `docs.google.com`의 `/document/d/{id}` 형태만
     * 인정하고, 그 외에는 null — 호출부가 "링크 형식" 경고를 띄운다.
     *
     * 스프레드시트·프레젠테이션은 export 형식이 달라 같은 경로로 다룰 수 없으므로
     * 거부한다. 단축 URL도 거부다 — 어디로 가는지 여기서 알 수 없다.
     */
    fun extractDocId(url: String): String? {
        val trimmed = url.trim()
        val withoutScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
            // http로 붙여 넣는 경우가 있다 — 어차피 우리가 https로 다시 만든다
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
            else -> return null
        }
        val path = withoutScheme.substringBefore('?').substringBefore('#')
        val host = path.substringBefore('/')
        if (!host.equals("docs.google.com", ignoreCase = true)) return null
        // "/document/d/{id}" — 그 뒤에 /edit·/view·/preview가 붙든 말든 상관없다
        val segments = path.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
        if (segments.size < 3) return null
        if (segments[0] != "document" || segments[1] != "d") return null
        return segments[2].takeIf { docIdPattern.matches(it) }
    }

    /** export 엔드포인트 — 뷰어 권한 문서면 인증 없이 평문을 돌려준다 */
    fun exportUrl(docId: String): String =
        "https://docs.google.com/document/d/$docId/export?format=txt"

    /** 문장 끝으로 볼 부호. 연속으로 찍힌 것(`?!`, `...`)은 한 덩어리로 본다 */
    private const val ENDERS = ".!?…"

    /** 종결부호 바로 뒤에 붙어 있으면 문장에 딸려 가는 닫는 짝 */
    private const val CLOSERS = "\"”』」)›»’'"

    /**
     * 평문을 문장 목록으로. 한국어 시나리오 텍스트를 전제한다.
     *
     * 줄을 먼저 나누는 것은 시나리오 문서가 제목·항목 줄을 많이 쓰기 때문이다 —
     * 종결부호가 없는 줄을 통째로 한 문장으로 두면 목차가 뭉개지지 않는다.
     *
     * `2.5`나 `1. 장`처럼 부호 뒤에 곧바로 글자가 오는 경우는 자르지 않는다.
     * 자를 자리는 **부호(와 닫는 짝) 뒤가 공백이거나 줄 끝일 때**뿐이다.
     */
    fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            var start = 0
            var i = 0
            while (i < line.length) {
                if (line[i] !in ENDERS) {
                    i++
                    continue
                }
                // 연속 부호를 한 덩어리로 삼킨다 (`?!`, `...`)
                var end = i
                while (end + 1 < line.length && line[end + 1] in ENDERS) end++
                // 닫는 따옴표·괄호가 뒤따르면 문장에 포함시킨다
                while (end + 1 < line.length && line[end + 1] in CLOSERS) end++
                val nextIsBreak = end + 1 >= line.length || line[end + 1].isWhitespace()
                if (nextIsBreak) {
                    line.substring(start, end + 1).trim().takeIf { it.isNotEmpty() }
                        ?.let { out += it }
                    start = end + 1
                }
                i = end + 1
            }
            // 종결부호가 없는 줄(제목·항목)과 마지막 조각
            line.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }
}
