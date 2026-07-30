package com.pbp.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.pbp.shared.PbpMarkup

/**
 * 디자인 토큰 — 기본 디자인은 라이트 모드 (docs/PbP-design-spec.md 0장,
 * trpg-app-mockup-pc-light.html). 색 값은 모바일 PbpLightColors와 동일.
 */
object Tokens {
    val Bg = Color(0xFFF4F2EC)
    val Panel = Color(0xFFFFFFFF)
    val Panel2 = Color(0xFFF7F4EC)
    val Line = Color(0x1414191F)
    val Ink = Color(0xFF23272E)
    val InkDim = Color(0xFF6E7683)
    val InkSub = Color(0xB814191F)         // 상단 바 부제 — InkDim보다 진해 또렷
    val Signature = Color(0xFFFFD05C)     // 면(버튼 배경)용 — 옐로 텍스트 금지
    val SignatureRing = Color(0xFFE0B13E) // 링·테두리용 진한 골드
    val SignatureInk = Color(0xFFA3781A)  // 밝은 배경 위 옐로 '텍스트'용
    val Danger = Color(0xFFC94F4F)
    val ChatterBubble = Color(0x0F141920)
    val ChatterInk = Color(0x9923272E)
    val BubbleInk = Color(0xFF10151C)
    val NarrInk = Color(0xFF3D3628)
    val NarrBg = Color(0xB3FFFFFF)
    val VeilTop = Color(0x8CF4F2EC)
    val VeilMid = Color(0x40F4F2EC)

    val themePresets = listOf(
        0xFF8EC5E8 to "새벽 하늘", 0xFFC9A7E8 to "라일락", 0xFFE8B48E to "호박등",
        0xFF9FE0B8 to "이끼", 0xFFF2A1A8 to "동백", 0xFFE8D48E to "사금", 0xFFA8B4C8 to "잿빛",
    )
    val namePresets = listOf(0xFFFFC46B, 0xFF8EC5E8, 0xFFC9A7E8, 0xFF9FE0B8, 0xFFF2A1A8)
    val bubblePresets = listOf(0xFFFFD9A8, 0xFFBFE3F6, 0xFFE3D2F2, 0xFFCDEED9, 0xFFF6D3D6)
    val backgroundPresets = linkedMapOf(
        "preset_lighthouse" to (0xFF26374D to 0xFF101A28),
        "preset_lilac" to (0xFF33253F to 0xFF141020),
        "preset_desert" to (0xFF4F4A2C to 0xFF211D12),
        "preset_forest" to (0xFF173226 to 0xFF0A120E),
        "preset_ember" to (0xFF3A1F22 to 0xFF140B0C),
    )
    const val gmQuoteBubble = 0xFFE7E2D4

    /** 다크용 밝은 이름색 → 라이트 진한 색 치환 — 모바일 PbpPalette.nameColorForLight와 동일 */
    private val nameColorLightMap = mapOf(
        0xFFFFC46B to 0xFFC07B1F,
        0xFF8EC5E8 to 0xFF33719C,
        0xFFFFD972 to 0xFF8A6D1C,
    )

    fun nameColorForLight(argb: Long): Long = nameColorLightMap[argb] ?: darken(argb, 0.55f)

    private fun darken(argb: Long, factor: Float): Long {
        val r = ((argb shr 16 and 0xFF) * factor).toInt()
        val g = ((argb shr 8 and 0xFF) * factor).toInt()
        val b = ((argb and 0xFF) * factor).toInt()
        return 0xFF000000 or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }
}

/** 서술(내레이션)용 명조 — Gowun Batang 번들 */
val GowunBatang = FontFamily(
    Font(resource = "fonts/gowun_batang_regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/gowun_batang_bold.ttf", weight = FontWeight.Bold),
)

/** 본문 대안 서체 — Pretendard (고딕) */
val Pretendard = FontFamily(
    Font(resource = "fonts/pretendard.ttf", weight = FontWeight.Normal),
)

/** 글꼴 설정 값("system"/"gowun"/"pretendard") → FontFamily. null = 시스템 기본 */
fun appFontFamily(choice: String): FontFamily? = when (choice) {
    "gowun" -> GowunBatang
    "pretendard" -> Pretendard
    else -> null
}

/** 마크다운 + 루비(위첨자) 렌더링 — 모바일 MarkupText와 동일 방식 */
@Composable
fun MarkupText(
    text: String,
    fontSize: TextUnit,
    color: Color,
    rubyColor: Color,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    // 리컴포지션마다 재구성하지 않도록 캐시
    val (annotated, inline) = remember(text, fontSize, color, rubyColor, fontFamily, fontWeight) {
        buildMarkup(text, fontSize, color, rubyColor, fontFamily, fontWeight)
    }
    Text(
        annotated,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        inlineContent = inline,
    )
}

private fun buildMarkup(
    text: String,
    fontSize: TextUnit,
    color: Color,
    rubyColor: Color,
    fontFamily: FontFamily?,
    fontWeight: FontWeight?,
): Pair<androidx.compose.ui.text.AnnotatedString, Map<String, InlineTextContent>> {
    val nodes = PbpMarkup.parse(text)
    val inline = mutableMapOf<String, InlineTextContent>()
    val annotated = buildAnnotatedString {
        nodes.forEachIndexed { index, node ->
            when (node) {
                is PbpMarkup.Node.Span -> {
                    withStyle(
                        SpanStyle(
                            fontWeight = if (node.bold) FontWeight.ExtraBold else null,
                            fontStyle = if (node.italic) FontStyle.Italic else null,
                            textDecoration = if (node.strike) TextDecoration.LineThrough else null,
                        )
                    ) { append(node.text) }
                }
                is PbpMarkup.Node.Value -> {
                    // 캐릭터 value 치환 결과 — 파란색 강조
                    withStyle(
                        SpanStyle(color = Color(0xFF3B82F6), fontWeight = FontWeight.Bold)
                    ) { append(node.text) }
                }
                is PbpMarkup.Node.Ruby -> {
                    val id = "ruby-$index"
                    val width = maxOf(textUnits(node.base), textUnits(node.ruby) * 0.58f) + 0.15f
                    appendInlineContent(id, node.base)
                    inline[id] = InlineTextContent(
                        Placeholder(width.em, 1.95.em, PlaceholderVerticalAlign.TextCenter)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                node.ruby,
                                fontSize = fontSize * 0.55f,
                                lineHeight = fontSize * 0.6f,
                                color = rubyColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Text(
                                node.base,
                                fontSize = fontSize,
                                lineHeight = fontSize * 1.1f,
                                color = color,
                                fontFamily = fontFamily,
                                fontWeight = fontWeight,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
    return annotated to inline
}

private fun textUnits(text: String): Float =
    text.sumOf { ch -> if (ch.code >= 0x1100) 1.0 else 0.55 }.toFloat()
