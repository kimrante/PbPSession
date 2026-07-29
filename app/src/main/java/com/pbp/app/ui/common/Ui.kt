package com.pbp.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.pbp.app.text.PbpMarkup
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpPalette
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 원형 프로필 아바타 — 프로필 이미지는 항상 원형으로 잘려 표시된다. */
@Composable
fun Avatar(
    emoji: String?,
    imagePath: String?,
    size: Dp,
    ringColor: Color? = null,
    dimmed: Boolean = false,
    background: Color = Pbp.colors.panel2,
) {
    val ring = if (ringColor != null) {
        Modifier.border(2.dp, ringColor, CircleShape)
    } else {
        Modifier.border(1.5.dp, Color.White.copy(alpha = .22f), CircleShape)
    }
    Box(
        modifier = Modifier
            .size(size)
            .then(ring)
            .clip(CircleShape)
            .background(background)
            .alpha(if (dimmed) 0.55f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        if (imagePath != null) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(emoji ?: "🙂", fontSize = (size.value * 0.42).sp)
        }
    }
}

/** 잡담 말풍선의 점선 테두리 */
fun Modifier.dashedBorder(color: Color, corner: Dp): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(corner.toPx()),
        style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        ),
    )
}

/**
 * 방 배경: 프리셋 그라데이션 또는 갤러리 이미지 + 가독성 베일 (스펙 4장).
 * 배경 레이어는 IME(키보드) 인셋을 무시해 키보드가 올라와도 그대로 고정되고,
 * 내용 컬럼만 imePadding으로 밀려 올라간다.
 */
@Composable
fun RoomBackdrop(
    backgroundKey: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = Pbp.colors
    Box(modifier.fillMaxSize()) {
        val preset = PbpPalette.backgroundPresets[backgroundKey]
        if (preset != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(preset.first), Color(preset.second))
                        )
                    )
            )
        } else {
            AsyncImage(
                model = File(backgroundKey),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        }
        // 어떤 이미지든 자동으로 덮이는 베일 — 텍스트 가독성 유지
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(tokens.veilTop, tokens.veilMid, tokens.veilTop)
                    )
                )
        )
        Column(Modifier.fillMaxSize().imePadding()) { content() }
    }
}

/**
 * 마크다운 + 루비 문법 렌더링 텍스트.
 * 루비는 InlineTextContent로 본문 위에 작은 독음을 얹는 진짜 위첨자 방식.
 */
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
    // AnnotatedString·인라인 콘텐츠는 리컴포지션마다 재구성하지 않도록 캐시
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
        // 굵게·기울임 등 스타일에 따라 위아래 폰트 여백이 달라져 말풍선 높이가
        // 들쭉날쭉해지지 않도록: 폰트 패딩 제거 + 줄 상자를 lineHeight로 고정
        style = androidx.compose.ui.text.TextStyle(
            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
            ),
        ),
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
                            // 한글 서체는 이탤릭 페이스가 없어 합성 기울임을 강제한다
                            fontSynthesis = if (node.italic || node.bold) FontSynthesis.All else null,
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
                    // 폭은 본문/독음 중 넓은 쪽 (CJK≈1em, 그 외≈0.55em)
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

/** 대략적 글자 폭(em) 추정 — CJK/한글 1em, 그 외 0.55em */
private fun textUnits(text: String): Float =
    text.sumOf { ch -> if (ch.code >= 0x1100) 1.0 else 0.55 }.toFloat()

/** 커스텀 색 입력 다이얼로그 (테마/이름/말풍선 색의 '커스텀' 선택지) */
@Composable
fun HexColorDialog(title: String, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var hex by remember { mutableStateOf("") }
    val parsed = hex.trim().removePrefix("#").let {
        if (it.length == 6) it.toLongOrNull(16)?.or(0xFF000000) else null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    label = { Text("HEX 색상 (예: 8EC5E8)") },
                    singleLine = true,
                )
                if (parsed != null) {
                    Box(
                        Modifier
                            .size(60.dp, 24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(parsed))
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onPick) }, enabled = parsed != null) { Text("적용") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

/** 방 목록의 상대 시각 표기 */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - millis
    return when {
        diff < 60_000 -> "방금"
        diff < 3_600_000 -> "${diff / 60_000}분 전"
        diff < 86_400_000 -> "${diff / 3_600_000}시간 전"
        diff < 172_800_000 -> "어제"
        else -> "${diff / 86_400_000}일 전"
    }
}
