package com.pbp.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.em
import com.pbp.shared.PbpMarkup

/**
 * 디자인 토큰 — 기본 디자인은 라이트 모드 (docs/PbP-design-spec.md 0장,
 * trpg-app-mockup-pc-light.html). 색 값은 모바일 PbpLightColors와 동일.
 */
object Tokens {
    /*
     * 글자 크기는 모바일과 같은 18/15/13/11/10sp 5단계를 리터럴로 쓴다 (리뷰 E).
     * 본문 스케일 밖의 글리프는 아이콘·장식뿐: 인용 따옴표 24 · 초대 코드 32 ·
     * 빈 상태 '🎲' 40. 모바일 PbpDimens의 예외 목록과 같은 값이어야 한다.
     */

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
    val OnSignature = Color(0xFF1A1A1A)   // 시그니처 옐로 면 위의 잉크
    val Danger = Color(0xFFC94F4F)
    /** 캐릭터 값 치환·성공 판정 파랑 (모바일 statBlue와 같은 값) */
    val StatBlue = Color(0xFF3B82F6)
    /** 입력 필드·토글 배경 — 과거 0x0A/0x0D 두 값으로 갈라져 있던 것 통일 (리뷰 D3) */
    val FieldBg = Color(0x0D14191F)
    /** GM 표식 금색 링 */
    val GmRing = Color(0x99C89E34)
    /** 다이스 카드 텍스트의 진한 골드 */
    val DiceInk = Color(0xFF7A5B12)
    val ChatterBubble = Color(0x0F141920)
    val ChatterInk = Color(0x9923272E)
    val BubbleInk = Color(0xFF10151C)
    val NarrInk = Color(0xFF3D3628)
    val NarrBg = Color(0xB3FFFFFF)
    val VeilTop = Color(0x8CF4F2EC)
    val VeilMid = Color(0x40F4F2EC)

    // 팔레트 값·변환은 :shared Palette가 단일 출처 (리뷰 A3)
    val themePresets = com.pbp.shared.Palette.themePresets
    val namePresets = com.pbp.shared.Palette.namePresets
    val bubblePresets = com.pbp.shared.Palette.bubblePresets
    val backgroundPresets = com.pbp.shared.Palette.backgroundPresets
    const val gmQuoteBubble = com.pbp.shared.Palette.gmQuoteBubble

    fun nameColorForLight(argb: Long): Long = com.pbp.shared.Palette.nameColorForLight(argb)
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
    val (annotated, inline) = remember(text, fontSize, color, rubyColor, fontFamily, fontWeight, lineHeight) {
        buildMarkup(text, fontSize, color, rubyColor, fontFamily, fontWeight, lineHeight)
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
    lineHeight: TextUnit,
): Pair<androidx.compose.ui.text.AnnotatedString, Map<String, InlineTextContent>> {
    val nodes = PbpMarkup.parse(text)
    // 루비 상자가 줄 높이를 넘으면 그 줄만 벌어진다 — 줄 높이에 맞춰 가둔다 (모바일과 동일)
    val lineEm = if (lineHeight.isSpecified && fontSize.value > 0f) {
        (lineHeight.value / fontSize.value).coerceAtLeast(MIN_RUBY_BOX_EM)
    } else {
        MIN_RUBY_BOX_EM
    }
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
                    val width = maxOf(textUnits(node.base), textUnits(node.ruby) * 0.46f) + 0.12f
                    appendInlineContent(id, node.base)
                    inline[id] = InlineTextContent(
                        Placeholder(width.em, lineEm.em, PlaceholderVerticalAlign.Center)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                            modifier = Modifier.fillMaxHeight(),
                        ) {
                            Text(
                                node.ruby,
                                fontSize = fontSize * RUBY_SCALE,
                                lineHeight = fontSize * (RUBY_SCALE + 0.04f),
                                color = rubyColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Text(
                                node.base,
                                fontSize = fontSize,
                                lineHeight = fontSize * 1.0f,
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

/** 독음 글자 크기 = 본문의 42% (모바일과 동일) */
private const val RUBY_SCALE = 0.42f
private const val MIN_RUBY_BOX_EM = 1.45f

private fun textUnits(text: String): Float =
    text.sumOf { ch -> if (ch.code >= 0x1100) 1.0 else 0.55 }.toFloat()
