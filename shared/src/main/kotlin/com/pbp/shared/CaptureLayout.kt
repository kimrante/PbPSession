package com.pbp.shared

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 캡처 이미지의 **크기 계산과 분할 규칙** (C1).
 *
 * 모바일·PC가 같은 이미지를 내놓아야 하는데, 예전에는 양쪽에 축자 복제해 두고
 * "같아야 한다" 주석으로만 묶여 있었다. 모바일 쪽 [estimate]가 한 번 튜닝되면서
 * 실제로 갈라진 적이 있어(R1) 여기로 옮긴다. 플랫폼 의존이 없는 순수 계산만 둔다.
 */
object CaptureLayout {

    /** 결과 이미지 폭(dp) — 기기 폭과 무관하게 고정 */
    const val SHEET_WIDTH_DP = 360

    /** 고정 렌더 배율. 기기 밀도를 쓰면 고밀도 기기에서만 큰 이미지가 나온다 */
    const val RENDER_DENSITY = 2f

    /** 한 장 최대 높이(px). 넘으면 메시지 경계에서 나눠 여러 장으로 만든다 */
    const val MAX_HEIGHT_PX = 8_000

    /**
     * 한 번에 만들 수 있는 총 높이(px) 상한 — 720×이 높이×4바이트가 한꺼번에 힙에 올라간다.
     * 32,000px ≈ 4장 ≈ 92MB. 이보다 크면 기기에 따라 OOM으로 죽는다 (R2).
     */
    const val MAX_TOTAL_HEIGHT_PX = 32_000

    /** 머리글 + 낙관 + 시트 여백의 어림 합(dp) */
    const val CHROME_DP = 120f

    /**
     * 한 번에 고를 수 있는 메시지 수 (C3). 모바일의 한 페이지 로드량과 같은 값이어야
     * 한다 — 화면에 없는 메시지를 범위에 넣을 수 없기 때문이다.
     */
    const val MAX_MESSAGES = 200

    val widthPx = (SHEET_WIDTH_DP * RENDER_DENSITY).toInt()

    /** 높이 계산에 필요한 최소한의 메시지 정보 — 플랫폼 모델에서 이것만 뽑아 넘긴다 */
    data class Item(
        val body: String,
        /** [Protocol.MessageType] 값 */
        val type: String,
        val isOoc: Boolean,
        val senderIsGm: Boolean,
    )

    /**
     * 메시지 1건의 대략 높이(dp). **길이에 비례해야 한다** — GM 서술을 고정값으로 두면
     * 긴 서술 하나가 상한을 통째로 넘겨 페이지 하단이 잘려 나갔다 (R1).
     *
     * 실측 기준: 폭 360dp에서 말풍선 본문은 240dp 남짓, 13sp 한글이 줄당 17~18자,
     * 줄 높이 20dp. GM 서술은 폭을 다 쓰므로 줄당 글자가 더 들어간다.
     */
    fun estimate(item: Item): Float = when {
        item.type != Protocol.MessageType.TEXT || item.isOoc -> 28f
        item.senderIsGm -> 34f + lines(item.body, perLine = 26) * 20f
        else -> 30f + lines(item.body, perLine = 17) * 20f
    }

    /**
     * 결과 이미지의 대략 높이(px). **분할 판정과 같은 함수를 써야** 하단 바가 말한
     * 높이와 실제 장수가 어긋나지 않는다.
     */
    fun estimateHeightPx(items: List<Item>): Int =
        ((CHROME_DP + items.sumOf { estimate(it).toDouble() }.toFloat()) * RENDER_DENSITY).toInt()

    /**
     * 예상 높이를 누적해 상한에 닿기 전에 끊는다. 한 번에 크게 그린 뒤 자르면
     * 그 큰 비트맵에서 먼저 터지므로 **묶음을 나눠 따로 그린다**.
     *
     * @return 원본 인덱스 구간 목록. 플랫폼 메시지 타입을 몰라도 되도록 인덱스로 돌려준다.
     */
    fun splitByHeight(items: List<Item>): List<IntRange> {
        val chunks = mutableListOf<IntRange>()
        var start = 0
        var height = CHROME_DP
        items.forEachIndexed { index, item ->
            val h = estimate(item)
            if (index > start && height + h > MAX_HEIGHT_PX / RENDER_DENSITY) {
                chunks += start until index
                start = index
                height = CHROME_DP
            }
            height += h
        }
        if (start < items.size) chunks += start until items.size
        return chunks
    }

    /** 줄바꿈과 자동 줄바꿈을 함께 센다 — 최소 1줄 */
    private fun lines(body: String, perLine: Int): Int =
        body.split('\n').sumOf { line ->
            maxOf(1, (line.length + perLine - 1) / perLine)
        }.coerceAtLeast(1)

    /** "2026-07-30 21:03 – 21:14" — 날짜가 다르면 양쪽 모두 날짜를 붙인다 */
    fun formatDateRange(first: Long, last: Long): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        val sameDay = day.format(Date(first)) == day.format(Date(last))
        return if (sameDay) {
            "${day.format(Date(first))} ${time.format(Date(first))} – ${time.format(Date(last))}"
        } else {
            "${day.format(Date(first))} ${time.format(Date(first))} – " +
                "${day.format(Date(last))} ${time.format(Date(last))}"
        }
    }

    /** 낙관에 찍는 날짜 */
    fun dateOnly(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))

    /**
     * 탭 한 번의 결과 구간 (목업 03장, C2).
     * ① 시작만 정해진 상태에서 다른 곳을 탭하면 그게 끝점 — 위를 탭하면 위로 뻗는다(자동 정렬).
     * ② 양 끝을 다시 탭하면 **반대쪽 끝을 고정한 채** 그 끝만 그 자리로 옮긴다.
     * ③ 범위 밖을 탭하면 가까운 쪽 끝이 거기까지 늘어난다.
     * 어느 경우에도 범위가 초기화되지 않는다.
     */
    fun rangeAfterTap(range: IntRange, tapped: Int): IntRange {
        var first = range.first
        var last = range.last
        when {
            tapped == first && first != last -> first = last
            tapped == last && first != last -> last = first
            tapped < first -> first = tapped
            else -> last = tapped
        }
        return minOf(first, last)..maxOf(first, last)
    }
}
