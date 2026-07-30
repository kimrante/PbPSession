package com.pbp.shared

/**
 * 플랫폼 독립 팔레트 값 — 색상 Long과 변환 규칙 (리뷰 A3).
 * Compose Color로 감싸는 것은 각 모듈의 토큰 파일이 한다.
 */
object Palette {

    /** 이름 색 프리셋 5종 */
    val namePresets = listOf(0xFFFFC46B, 0xFF8EC5E8, 0xFFC9A7E8, 0xFF9FE0B8, 0xFFF2A1A8)

    /** 말풍선 색 프리셋 5종 */
    val bubblePresets = listOf(0xFFFFD9A8, 0xFFBFE3F6, 0xFFE3D2F2, 0xFFCDEED9, 0xFFF6D3D6)

    /** GM 발화 중 " " 대사 말풍선 색 */
    const val gmQuoteBubble = 0xFFE7E2D4

    /**
     * 다크 모드용 밝은 이름색 → 화이트 모드(및 종이 톤 로그)용 진한 색 치환.
     * 스펙 명시 매핑 외의 색은 알고리즘으로 어둡게 낮춘다.
     */
    private val nameColorLightMap = mapOf(
        0xFFFFC46B to 0xFFC07B1F,
        0xFF8EC5E8 to 0xFF33719C,
        0xFFFFD972 to 0xFF8A6D1C,
    )

    fun nameColorForLight(argb: Long): Long = nameColorLightMap[argb] ?: darken(argb, 0.55f)

    fun darken(argb: Long, factor: Float): Long {
        val r = ((argb shr 16 and 0xFF) * factor).toInt()
        val g = ((argb shr 8 and 0xFF) * factor).toInt()
        val b = ((argb and 0xFF) * factor).toInt()
        return 0xFF000000 or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    /** 방 배경 프리셋 (key → 세로 그라데이션 색 쌍). 커스텀 배경은 파일 경로를 key로 쓴다. */
    val backgroundPresets = linkedMapOf(
        "preset_lighthouse" to (0xFF26374D to 0xFF101A28),
        "preset_lilac" to (0xFF33253F to 0xFF141020),
        "preset_desert" to (0xFF4F4A2C to 0xFF211D12),
        "preset_forest" to (0xFF173226 to 0xFF0A120E),
        "preset_ember" to (0xFF3A1F22 to 0xFF140B0C),
    )
}
