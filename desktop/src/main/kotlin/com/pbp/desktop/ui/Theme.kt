package com.pbp.desktop.ui

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
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

    // ── 지시서 0-5: 화면 코드에 복제돼 있던 리터럴을 토큰으로 ──

    /** 채팅 입력 바 면 — 모바일 chatBarBg와 **같은 값**이어야 한다 (0xEB로 드리프트했었다) */
    val ChatBarBg = Color(0xEDFFFFFF)

    /** 오버레이 딤 — 세 파일에 복제돼 있던 값 */
    val Scrim = Color(0x611E232D)

    /** 배경 이미지 위 표시용 필·칩의 검정 스크림 (모바일 scrim과 같은 값) */
    val PillScrim = Color(0x59000000)

    /** 스크림 위 글자 */
    val OnScrim = Color(0x99FFFFFF)

    /** 왼쪽 방 목록 패널 면 — 위/아래 두 톤 */
    val SidebarTop = Color(0xFFFAF8F2)
    val SidebarBottom = Color(0xFFF1EDE3)

    /** 방 목록 카드 면 — 가이드대로 Panel 면 + Line 테두리 */
    val CardBg = Panel

    /** 잉크의 옅은 면 — 비활성 버튼 배경 */
    val InkFaint = Color(0x1414191F)

    /** 비활성 버튼 위 글자 */
    val InkDisabled = Color(0x5714191F)

    /** 시그니처 옐로의 짙은 짝 — 로고 타일 그라데이션 전용 */
    val SignatureDeep = Color(0xFFEFB945)

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
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    // 리컴포지션마다 재구성하지 않도록 캐시
    val (annotated, inline) = remember(text, fontSize, color, fontFamily, fontWeight) {
        buildMarkup(text, fontSize, color, fontFamily, fontWeight)
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
                            // 한글 서체는 이탤릭 페이스가 없어 합성 기울임을 강제한다 (모바일과 동일, S4)
                            fontSynthesis = if (node.italic || node.bold) FontSynthesis.All else null,
                        )
                    ) { append(node.text) }
                }
                is PbpMarkup.Node.Value -> {
                    // 캐릭터 value 치환 결과 — StatBlue 토큰 강조 (모바일과 동일 값)
                    withStyle(
                        SpanStyle(color = Tokens.StatBlue, fontWeight = FontWeight.Bold)
                    ) { append(node.text) }
                }
                is PbpMarkup.Node.Ruby -> {
                    val id = "ruby-$index"
                    val width = maxOf(textUnits(node.base), textUnits(node.ruby) * 0.46f) + 0.12f
                    appendInlineContent(id, node.base)
                    inline[id] = InlineTextContent(
                        // 상자 밑변 = 바깥 텍스트의 베이스라인. 높이는 어센트보다 작게 —
                        // 줄 메트릭을 건드리지 않아 루비가 있는 줄만 벌어지지 않는다 (모바일과 동일)
                        Placeholder(width.em, RUBY_BOX_EM.em, PlaceholderVerticalAlign.AboveBaseline)
                    ) {
                        RubyStack(node.base, node.ruby, fontSize, color, fontFamily, fontWeight)
                    }
                }
            }
        }
    }
    return annotated to inline
}

/**
 * 루비 스택 — 본문 베이스라인을 인라인 상자 밑변(= 바깥 텍스트의 베이스라인)에 맞추고,
 * 독음은 본문 글리프 위 줄 간격 여백으로 넘겨 그린다 (모바일 RubyStack과 동일 방식).
 */
@Composable
private fun RubyStack(
    base: String,
    ruby: String,
    fontSize: TextUnit,
    color: Color,
    fontFamily: FontFamily?,
    fontWeight: FontWeight?,
) {
    Layout(content = {
        Text(
            base,
            fontSize = fontSize,
            lineHeight = fontSize,
            color = color,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            ruby,
            fontSize = fontSize * RUBY_SCALE,
            lineHeight = fontSize * RUBY_SCALE,
            // 독음도 본문과 같은 글자색 (모바일과 동일 규칙)
            color = color,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
    }) { measurables, constraints ->
        // 디센더·독음이 상자 밖으로 넘칠 수 있으므로 무제한으로 측정한다
        val basePl = measurables[0].measure(Constraints())
        val rubyPl = measurables[1].measure(Constraints())
        val fb = basePl[FirstBaseline]
        val baseline = if (fb != AlignmentLine.Unspecified) fb else basePl.height
        // 독음을 본문 쪽으로 조금 내린다 (모바일과 같은 값)
        val drop = (fontSize.toPx() * RUBY_DROP_EM).toInt()
        layout(constraints.maxWidth, constraints.maxHeight) {
            val baseY = constraints.maxHeight - baseline // 베이스라인을 상자 밑변에
            basePl.place((constraints.maxWidth - basePl.width) / 2, baseY)
            rubyPl.place((constraints.maxWidth - rubyPl.width) / 2, baseY - rubyPl.height + drop)
        }
    }
}

/** 독음 글자 크기 = 본문의 42% (모바일과 동일) */
private const val RUBY_SCALE = 0.42f

/** 독음을 본문 쪽으로 내리는 양(본문 크기 대비) — 모바일과 같은 값이어야 한다 */
private const val RUBY_DROP_EM = 0.12f

/** 루비 인라인 상자 높이(em) — 본문 어센트보다 작아야 줄 메트릭에 영향이 없다 */
private const val RUBY_BOX_EM = 0.8f

private fun textUnits(text: String): Float =
    text.sumOf { ch -> if (ch.code >= 0x1100) 1.0 else 0.55 }.toFloat()
