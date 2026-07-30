package com.pbp.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.pbp.shared.PbpMarkup
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
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
    // 지정이 없으면 앱 글꼴 설정을 따른다 — GM 서술만 명조를 명시적으로 넘긴다
    val family = fontFamily ?: com.pbp.app.ui.theme.AppFonts.fontFamily
    // buildMarkup은 컴포저블이 아니라 토큰을 직접 못 읽는다 — 여기서 넘긴다
    val valueColor = Pbp.colors.statBlue
    // AnnotatedString·인라인 콘텐츠는 리컴포지션마다 재구성하지 않도록 캐시
    val (annotated, inline) = remember(text, fontSize, color, rubyColor, family, fontWeight, lineHeight, valueColor) {
        buildMarkup(text, fontSize, color, rubyColor, family, fontWeight, lineHeight, valueColor)
    }
    Text(
        annotated,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontFamily = family,
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
    lineHeight: TextUnit,
    /** 캐릭터 값 치환 강조색 — 컴포저블이 아니라 호출부에서 토큰을 받는다 */
    valueColor: Color,
): Pair<androidx.compose.ui.text.AnnotatedString, Map<String, InlineTextContent>> {
    val nodes = PbpMarkup.parse(text)
    // 루비 상자는 줄 높이를 넘지 않아야 한다 — 넘으면 그 줄만 벌어져 문단이 어긋난다.
    // 줄 높이가 지정돼 있으면 그 값에 맞추고, 없으면 안전한 기본값.
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
                            // 한글 서체는 이탤릭 페이스가 없어 합성 기울임을 강제한다
                            fontSynthesis = if (node.italic || node.bold) FontSynthesis.All else null,
                        )
                    ) { append(node.text) }
                }
                is PbpMarkup.Node.Value -> {
                    // 캐릭터 value 치환 결과 — 파란색 강조
                    withStyle(
                        SpanStyle(color = valueColor, fontWeight = FontWeight.Bold)
                    ) { append(node.text) }
                }
                is PbpMarkup.Node.Ruby -> {
                    val id = "ruby-$index"
                    // 폭은 본문/독음 중 넓은 쪽 (CJK≈1em, 그 외≈0.55em).
                    // 독음이 작아진 만큼(0.42em) 폭 환산 계수도 낮춘다
                    val width = maxOf(textUnits(node.base), textUnits(node.ruby) * 0.46f) + 0.12f
                    appendInlineContent(id, node.base)
                    inline[id] = InlineTextContent(
                        // 줄 높이 안에 들어가는 상자 — 루비가 있는 줄만 벌어지지 않는다
                        Placeholder(width.em, lineEm.em, PlaceholderVerticalAlign.Center)
                    ) {
                        // 본문은 아래쪽 기준, 독음은 그 위 남는 공간에
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

/** 대략적 글자 폭(em) 추정 — CJK/한글 1em, 그 외 0.55em */
/** 독음 글자 크기 = 본문의 42% */
private const val RUBY_SCALE = 0.42f

/** 줄 높이 지정이 없을 때 쓰는 루비 상자 높이(본문 기준 배수) */
private const val MIN_RUBY_BOX_EM = 1.45f

private fun textUnits(text: String): Float =
    text.sumOf { ch -> if (ch.code >= 0x1100) 1.0 else 0.55 }.toFloat()

/** 커스텀 색 입력 다이얼로그 (테마/이름/말풍선 색의 '커스텀' 선택지) */
@Composable
fun HexColorDialog(
    title: String,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    initial: Long? = null,
) {
    val seed = initial ?: PbpPalette.themePresets.first().first
    val seedHsv = remember { argbToHsv(seed) }
    var hue by remember { mutableStateOf(seedHsv.first) }
    var sat by remember { mutableStateOf(seedHsv.second) }
    var bri by remember { mutableStateOf(seedHsv.third) }
    var hex by remember { mutableStateOf("%06X".format(seed and 0xFFFFFF)) }
    val current = hsvToArgb(hue, sat, bri)

    // 드래그 → HEX 표기 동기화. HEX 입력 → 팔레트 동기화 (onValueChange에서)
    fun syncHex() {
        hex = "%06X".format(hsvToArgb(hue, sat, bri) and 0xFFFFFF)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // 채도(가로)·명도(세로) 팔레트 — 드래그/탭으로 선택
                var svSize by remember { mutableStateOf(IntSize.Zero) }
                fun pickSv(x: Float, y: Float) {
                    if (svSize == IntSize.Zero) return
                    sat = (x / svSize.width).coerceIn(0f, 1f)
                    bri = 1f - (y / svSize.height).coerceIn(0f, 1f)
                    syncHex()
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.White, Color(hsvToArgb(hue, 1f, 1f)))
                            )
                        )
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                        )
                        .onSizeChanged { svSize = it }
                        .pointerInput(Unit) {
                            detectTapGestures { p -> pickSv(p.x, p.y) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                pickSv(change.position.x, change.position.y)
                            }
                        }
                ) {
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (sat * svSize.width).toInt() - 8.dp.roundToPx(),
                                    ((1f - bri) * svSize.height).toInt() - 8.dp.roundToPx(),
                                )
                            }
                            .size(16.dp)
                            // 흰 링만 두면 SV 사각형의 밝은 영역에서 사라진다 —
                            // 안쪽에 잉크 링을 겹쳐 어느 색 위에서도 테두리가 보이게
                            .border(3.dp, Pbp.colors.ink.copy(alpha = .45f), CircleShape)
                            .border(2.dp, Color.White, CircleShape)
                            .clip(CircleShape)
                            .background(Color(current))
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 색상(Hue) 띠
                var hueSize by remember { mutableStateOf(IntSize.Zero) }
                fun pickHue(x: Float) {
                    if (hueSize == IntSize.Zero) return
                    hue = (x / hueSize.width).coerceIn(0f, 1f) * 359.9f
                    syncHex()
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
                                    Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF),
                                    Color(0xFFFF0000),
                                )
                            )
                        )
                        .onSizeChanged { hueSize = it }
                        .pointerInput(Unit) {
                            detectTapGestures { p -> pickHue(p.x) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                pickHue(change.position.x)
                            }
                        }
                ) {
                    Box(
                        Modifier
                            .offset {
                                IntOffset((hue / 360f * hueSize.width).toInt() - 8.dp.roundToPx(), 0)
                            }
                            .size(16.dp, 18.dp)
                            .border(3.dp, Pbp.colors.ink.copy(alpha = .45f), CircleShape)
                            .border(2.dp, Color.White, CircleShape)
                            .clip(CircleShape)
                            .background(Color(hsvToArgb(hue, 1f, 1f)))
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp, 28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(current))
                            .border(1.dp, Pbp.colors.line, RoundedCornerShape(6.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { typed ->
                            hex = typed
                            typed.trim().removePrefix("#")
                                .takeIf { it.length == 6 }?.toLongOrNull(16)
                                ?.or(0xFF000000)
                                ?.let { color ->
                                    val (h, s, v) = argbToHsv(color)
                                    hue = h; sat = s; bri = v
                                }
                        },
                        label = { Text("HEX (예: 8EC5E8)") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current) }) { Text("적용") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * 커스텀 컬러 진입 스와치의 6색 sweep 그라데이션 — 3곳에 복제돼 있던 것 (리뷰 C4).
 */
val customColorBrush: Brush = Brush.sweepGradient(
    listOf(
        Color(0xFFFF6666), Color(0xFFFFCC66), Color(0xFF66DD66),
        Color(0xFF66CCFF), Color(0xFFCC66FF), Color(0xFFFF6666),
    )
)

/**
 * 오너 아바타 원 — 이미지가 있으면 이미지, 없으면 이름 첫 글자 (리뷰 C4).
 * 방 목록 헤더·프로필 관리 목록·오너 설정 3곳이 손으로 조립하던 것을 하나로.
 */
@Composable
fun OwnerAvatar(
    name: String,
    color: Long,
    imagePath: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    /** 편집 화면의 큰 사진에만 — 캐릭터 Avatar와 같은 3dp 시그니처 링 */
    ringColor: Color? = null,
) {
    Box(
        modifier
            .size(size)
            .then(if (ringColor != null) Modifier.border(3.dp, ringColor, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(Color(color)),
        contentAlignment = Alignment.Center,
    ) {
        if (imagePath != null) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                name.take(1).ifEmpty { "?" },
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold,
                color = Pbp.colors.bubbleInk,
            )
        }
    }
}

/**
 * 클립보드의 ccfolia식 캐릭터 코드를 읽어 생성하고 결과를 토스트로 알린다 (리뷰 C1).
 * 채팅 화면·프로필 관리 두 진입점이 같은 문구·같은 동작을 쓰도록 한 곳에 둔다.
 */
fun importCharacterFromClipboard(
    context: android.content.Context,
    onImported: (com.pbp.shared.CharacterCodec.Imported) -> Unit,
) {
    val clip = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager)
        .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    val imported = clip?.let { com.pbp.shared.CharacterCodec.parse(it) }
    if (imported != null) {
        onImported(imported)
        android.widget.Toast.makeText(
            context,
            "'${imported.name}' 캐릭터를 만들었습니다 (값 ${imported.stats.size}개)",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    } else {
        android.widget.Toast.makeText(
            context, "클립보드에서 캐릭터 코드를 찾지 못했습니다",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}

/** 캐릭터 추가 방식 선택 — 신규 작성이 위, 클립보드 코드가 아래 (프로필 관리 요구) */
@Composable
fun AddProfileDialog(
    onDismiss: () -> Unit,
    onEmpty: () -> Unit,
    onClipboard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("캐릭터 추가") },
        text = {
            Column {
                AddOptionRow(
                    title = "신규 캐릭터 작성",
                    subtitle = "이름과 색만 정해 새로 만들기",
                    onClick = onEmpty,
                )
                AddOptionRow(
                    title = "클립보드 코드로 생성",
                    subtitle = "복사해 둔 캐릭터 코드(JSON)의 이름·능력치를 값으로 자동 등록",
                    onClick = onClipboard,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun AddOptionRow(title: String, subtitle: String, onClick: () -> Unit) {
    val tokens = Pbp.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PbpDimens.rCell))
            .clickable(onClick = onClick)
            .padding(PbpDimens.gap3),
    ) {
        // 밝은 다이얼로그 위 옐로 '텍스트'는 signatureInk (스펙 0장)
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tokens.signatureInk)
        Text(subtitle, fontSize = 11.sp, color = tokens.inkDim)
    }
}

/** HSV(h 0–360, s/v 0–1) → 0xFFRRGGBB — 팔레트 드래그 선택용 순수 변환 */
fun hsvToArgb(h: Float, s: Float, v: Float): Long {
    val c = v * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun ch(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
    return 0xFF000000 or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/** 0xFFRRGGBB → HSV — HEX 입력·초기색을 팔레트 위치로 되돌릴 때 */
fun argbToHsv(argb: Long): Triple<Float, Float, Float> {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val s = if (max == 0f) 0f else d / max
    return Triple(h, s, max)
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
