package com.pbp.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 디자인 토큰 (docs/PbP-design-spec.md 2장).
 * 다크/화이트 모드는 이 토큰 스왑만으로 구성된다.
 */
@Immutable
data class PbpColors(
    val isDark: Boolean,
    val bg: Color,            // 베이스 배경
    val panel: Color,         // 패널
    val panel2: Color,
    val line: Color,          // 구분선
    val ink: Color,           // 본문 잉크
    val inkDim: Color,        // 보조 텍스트
    val signature: Color,     // 시그니처 옐로 (앱 타이틀 강조는 titleAccent)
    val titleAccent: Color,   // "PbP" 강조색 — 화이트 모드에선 잉크 블랙
    val themeDefault: Color,  // 방 테마 컬러 기본값(새벽 하늘)
    val chatterBubble: Color, // 잡담 말풍선
    val chatterInk: Color,
    val bubbleInk: Color,     // 말풍선 안 글자색
    val narrInk: Color,       // GM 서술 문단 글자색
    val narrBg: Color,        // GM 서술 문단 배경
    val veilTop: Color,       // 배경 이미지 가독성 베일
    val veilMid: Color,
)

val PbpDarkColors = PbpColors(
    isDark = true,
    bg = Color(0xFF0D1117),
    panel = Color(0xFF161C24),
    panel2 = Color(0xFF1D2530),
    line = Color(0x14FFFFFF),
    ink = Color(0xFFE8ECF2),
    inkDim = Color(0xFF8B95A5),
    signature = Color(0xFFFFD972),
    titleAccent = Color(0xFFFFD972),
    themeDefault = Color(0xFF8EC5E8),
    chatterBubble = Color(0x24FFFFFF),
    chatterInk = Color(0x9EFFFFFF),
    bubbleInk = Color(0xFF10151C),
    narrInk = Color(0xFFF0EAD8),
    narrBg = Color(0x73060A0E),
    veilTop = Color(0x9E0A0E14),
    veilMid = Color(0x470A0E14),
)

val PbpLightColors = PbpColors(
    isDark = false,
    bg = Color(0xFFF4F2EC),
    panel = Color(0xFFFFFFFF),
    panel2 = Color(0xFFF7F4EC),
    line = Color(0x1414191F),
    ink = Color(0xFF23272E),
    inkDim = Color(0xFF6E7683),
    signature = Color(0xFFFFD05C),
    titleAccent = Color(0xFF23272E),
    themeDefault = Color(0xFF5F9EC7),
    chatterBubble = Color(0x0F141920),
    chatterInk = Color(0x9923272E),
    bubbleInk = Color(0xFF10151C),
    narrInk = Color(0xFF3D3628),
    narrBg = Color(0xB3FFFFFF),
    veilTop = Color(0x8CF4F2EC),
    veilMid = Color(0x40F4F2EC),
)

val LocalPbpColors = staticCompositionLocalOf { PbpDarkColors }

/** 프리셋 팔레트 (목업 03·04 화면) */
object PbpPalette {
    /** 방 테마 컬러 7종 (+ 커스텀은 UI에서 별도 처리) */
    val themePresets = listOf(
        0xFF8EC5E8 to "새벽 하늘",
        0xFFC9A7E8 to "라일락",
        0xFFE8B48E to "호박등",
        0xFF9FE0B8 to "이끼",
        0xFFF2A1A8 to "동백",
        0xFFE8D48E to "사금",
        0xFFA8B4C8 to "잿빛",
    )

    /** 이름 색 프리셋 5종 */
    val namePresets = listOf(0xFFFFC46B, 0xFF8EC5E8, 0xFFC9A7E8, 0xFF9FE0B8, 0xFFF2A1A8)

    /** 말풍선 색 프리셋 5종 */
    val bubblePresets = listOf(0xFFFFD9A8, 0xFFBFE3F6, 0xFFE3D2F2, 0xFFCDEED9, 0xFFF6D3D6)

    /** GM 발화 중 " " 대사 말풍선 색 */
    val gmQuoteBubble = 0xFFE7E2D4

    /**
     * 다크 모드용 밝은 이름색 → 화이트 모드(및 종이 톤 로그)용 진한 색 치환.
     * 스펙 명시 매핑 외의 색은 알고리즘으로 어둡게 낮춘다.
     */
    private val nameColorLightMap = mapOf(
        0xFFFFC46B to 0xFFC07B1F,
        0xFF8EC5E8 to 0xFF33719C,
        0xFFFFD972 to 0xFF8A6D1C,
    )

    fun nameColorForLight(argb: Long): Long =
        nameColorLightMap[argb] ?: darken(argb, 0.55f)

    private fun darken(argb: Long, factor: Float): Long {
        val r = ((argb shr 16 and 0xFF) * factor).toInt()
        val g = ((argb shr 8 and 0xFF) * factor).toInt()
        val b = ((argb and 0xFF) * factor).toInt()
        return 0xFF000000 or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    /** 방 배경 프리셋 (key → 세로 그라데이션 색 쌍). 갤러리 이미지는 파일 경로를 key로 쓴다. */
    val backgroundPresets = linkedMapOf(
        "preset_lighthouse" to (0xFF26374D to 0xFF101A28),
        "preset_lilac" to (0xFF33253F to 0xFF141020),
        "preset_desert" to (0xFF4F4A2C to 0xFF211D12),
        "preset_forest" to (0xFF173226 to 0xFF0A120E),
        "preset_ember" to (0xFF3A1F22 to 0xFF140B0C),
    )

    const val DEFAULT_BACKGROUND = "preset_lighthouse"
    const val DEFAULT_THEME_COLOR = 0xFF8EC5E8
}
