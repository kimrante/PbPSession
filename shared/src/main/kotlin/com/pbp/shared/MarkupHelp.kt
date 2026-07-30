package com.pbp.shared

/**
 * 채팅 입력창의 "?" 도움말에 들어가는 문법 목록.
 *
 * 양 플랫폼이 같은 목록을 그리도록 여기 한 곳에만 둔다 — 문법을 늘리거나 고칠 때
 * [PbpMarkup]·[DiceBot]과 이 목록을 함께 손보면 화면은 자동으로 따라온다.
 */
object MarkupHelp {

    /**
     * @param syntax 입력하는 형태 (고정폭으로 표시)
     * @param summary 무슨 일이 일어나는지
     * @param example 실제 예시. 설명만으로 충분하면 null
     */
    data class Entry(val syntax: String, val summary: String, val example: String? = null)

    val entries = listOf(
        Entry("**굵게**", "굵은 글씨"),
        Entry("*기울임*", "기울인 글씨"),
        Entry("~~취소선~~", "가운데 줄"),
        Entry("(루비)[문자]", "문자 위에 작은 독음", "(등대)[等臺] → 等臺 위에 '등대'"),
        Entry("{능력치}", "내 캐릭터의 값으로 치환", "{은신} → 50"),
        Entry("\"대사\"", "큰따옴표로 감싼 부분은 대사 말풍선으로 분리"),
        Entry("1d100", "주사위 굴림", "/r 1d6 · d66 · 1d6 공격한다!"),
        Entry("1d100<=50", "비교 판정 — 성공·실패를 함께 표시"),
        Entry("Ctrl+Enter", "전송 (물리 키보드)"),
    )
}
