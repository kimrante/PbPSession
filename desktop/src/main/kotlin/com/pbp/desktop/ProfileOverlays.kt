package com.pbp.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pbp.desktop.data.AppConfig
import com.pbp.desktop.data.FirestoreRest
import com.pbp.desktop.data.JoinedRoom
import com.pbp.desktop.data.Message
import com.pbp.desktop.data.Profile
import com.pbp.desktop.data.RoomCacheStore
import com.pbp.shared.CharacterCodec
import com.pbp.shared.DiceBot
import com.pbp.shared.ProfileStats
import com.pbp.shared.Rules
import com.pbp.shared.GmSpeech
import com.pbp.desktop.notify.DesktopNotifier
import com.pbp.desktop.ui.GowunBatang
import com.pbp.desktop.ui.MarkupText
import com.pbp.desktop.ui.Pretendard
import com.pbp.desktop.ui.Tokens
import com.pbp.desktop.ui.appFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pbp.shared.Protocol
import com.pbp.desktop.data.AppPaths
import com.pbp.desktop.ui.DesktopDimens

/** 프로필·오너·컬러 팔레트 오버레이 — Main.kt에서 분리 (리뷰 B1) */

/**
 * 오너 아바타 원 — 이미지가 있으면 이미지, 없으면 이름 첫 글자 (리뷰 C3).
 * 사이드바 칩·관리 목록·오너 설정 3곳이 손으로 조립하던 것을 하나로.
 */
@Composable
internal fun OwnerAvatar(
    name: String,
    color: Long,
    imagePath: String?,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(Color(color)),
        contentAlignment = Alignment.Center,
    ) {
        val image = rememberLocalBitmap(imagePath)
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                name.take(1).ifEmpty { "?" },
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.BubbleInk,
            )
        }
    }
}

@Composable
internal fun ProfileOverlay(
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit,
    /** 자리별 최근 사용한 커스텀 색 (최신순) */
    recentColors: Map<String, List<Long>> = emptyMap(),
    /** 커스텀 색을 적용했을 때 — 해당 자리 목록에 기록 */
    onColorUsed: (String, Long) -> Unit = { _, _ -> },
    /** null이면 새 캐릭터, 아니면 이 프로필을 편집 */
    editing: Profile? = null,
    /** 편집 모드에서만 — null이면 삭제 버튼 숨김 */
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var emoji by remember { mutableStateOf(editing?.emoji ?: "") }
    var nameColor by remember { mutableStateOf(editing?.nameColor ?: Tokens.namePresets.first()) }
    var bubbleColor by remember { mutableStateOf(editing?.bubbleColor ?: Tokens.bubblePresets.first()) }
    var textColor by remember { mutableStateOf(editing?.textColor) }
    var nameCustomOpen by remember { mutableStateOf(false) }
    var bubbleCustomOpen by remember { mutableStateOf(false) }
    var textCustomOpen by remember { mutableStateOf(false) }
    // 캐릭터 값 — 앱 프로필 편집기의 value 목록과 동일 개념. {값이름} 치환·팔레트에 쓰인다
    val stats = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            editing?.stats?.forEach { (k, v) -> add(k to v) }
        }
    }
    var imagePath by remember { mutableStateOf(editing?.imagePath) }
    var pickingImage by remember { mutableStateOf(false) }
    val overlayScope = rememberCoroutineScope()
    OverlayScaffold(if (editing == null) "새 캐릭터" else "캐릭터 편집", onDismiss) {
        OverlayField(name, { name = it }, "캐릭터 이름")
        Spacer(Modifier.height(10.dp))
        OverlayField(emoji, { emoji = it }, "이모지 아바타 (비우면 🙂)")
        Spacer(Modifier.height(10.dp))
        // 프로필 이미지 — 로컬 512px 축소 저장, 전송 시 256px 축소본이 방에 업로드 (앱과 동일)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Tokens.Panel2)
                    .border(1.dp, Tokens.Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = rememberLocalBitmap(imagePath)
                if (bmp != null) {
                    Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(emoji.trim().ifEmpty { "🙂" }, fontSize = 15.sp)
                }
            }
            GhostButton(
                if (imagePath == null) "프로필 이미지 선택" else "이미지 변경",
                Modifier.weight(1f),
            ) {
                if (!pickingImage) {
                    pickingImage = true
                    overlayScope.launch(Dispatchers.IO) {
                        try {
                            pickAndStoreImage("프로필 이미지 선택", AppPaths.AVATARS_LOCAL, DesktopDimens.PROFILE_PX)
                                ?.let { imagePath = it }
                        } finally {
                            pickingImage = false
                        }
                    }
                }
            }
            if (imagePath != null) {
                GhostButton("제거") { imagePath = null }
            }
        }
        Spacer(Modifier.height(10.dp))
        // 앱의 '클립보드 코드로 생성'과 동일 — ccfolia 캐릭터 JSON을 붙여넣은 상태로 클릭
        GhostButton("클립보드 캐릭터 코드 불러오기", Modifier.fillMaxWidth()) {
            runCatching {
                val clip = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                CharacterCodec.parse(clip ?: "")
            }.getOrNull()?.let { imported ->
                name = imported.name
                stats.clear()
                imported.stats.forEach { stats.add(it) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("이름 색", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SwatchRow(Tokens.namePresets, nameColor, recentColors["name"].orEmpty()) { nameColor = it; nameCustomOpen = false }
            CustomSwatch(on = nameColor !in Tokens.namePresets) {
                nameCustomOpen = !nameCustomOpen
            }
        }
        if (nameCustomOpen) {
            Spacer(Modifier.height(8.dp))
            ColorPalettePicker(nameColor) { nameColor = it; onColorUsed("name", it) }
        }
        Spacer(Modifier.height(14.dp))
        Text("말풍선 색", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SwatchRow(Tokens.bubblePresets, bubbleColor, recentColors["bubble"].orEmpty()) { bubbleColor = it; bubbleCustomOpen = false }
            CustomSwatch(on = bubbleColor !in Tokens.bubblePresets) {
                bubbleCustomOpen = !bubbleCustomOpen
            }
        }
        if (bubbleCustomOpen) {
            Spacer(Modifier.height(8.dp))
            ColorPalettePicker(bubbleColor) { bubbleColor = it; onColorUsed("bubble", it) }
        }
        Spacer(Modifier.height(14.dp))
        Text("말풍선 글씨색", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SwatchRow(
                com.pbp.shared.Palette.textPresets,
                textColor ?: com.pbp.shared.Palette.textPresets.first(),
                recentColors["text"].orEmpty(),
            ) { textColor = it; textCustomOpen = false }
            CustomSwatch(on = textColor != null && textColor !in com.pbp.shared.Palette.textPresets) {
                textCustomOpen = !textCustomOpen
            }
        }
        if (textCustomOpen) {
            Spacer(Modifier.height(8.dp))
            ColorPalettePicker(textColor ?: com.pbp.shared.Palette.textPresets.first()) {
                textColor = it; onColorUsed("text", it)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("캐릭터 값", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Text(
            "메시지의 {값이름}이 값으로 치환되고, 숫자 값은 판정 팔레트에 뜹니다",
            fontSize = 10.sp, color = Tokens.InkDim,
        )
        Spacer(Modifier.height(7.dp))
        stats.forEachIndexed { index, (key, value) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    OverlayField(key, { stats[index] = it to stats[index].second }, "이름")
                }
                Box(Modifier.weight(1f)) {
                    OverlayField(value, { stats[index] = stats[index].first to it }, "값")
                }
                Text(
                    "✕", fontSize = 13.sp, color = Tokens.InkDim,
                    modifier = Modifier.clip(CircleShape)
                        .clickable { stats.removeAt(index) }
                        .padding(6.dp),
                )
            }
        }
        GhostButton("＋ 값 추가", Modifier.fillMaxWidth()) { stats.add("" to "") }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YellowButton("저장", Modifier.weight(1f)) {
                onSave(
                    Profile(
                        name = name.trim().ifEmpty { "이름 없음" },
                        emoji = emoji.trim().ifEmpty { "🙂" },
                        nameColor = nameColor,
                        bubbleColor = bubbleColor,
                        isGm = editing?.isGm ?: false,
                        stats = ProfileStats.sanitize(stats.toMap()).takeIf { it.isNotEmpty() },
                        imagePath = imagePath,
                        textColor = textColor,
                    )
                )
            }
            GhostButton("취소", Modifier.weight(1f), onDismiss)
        }
        onDelete?.let { delete ->
            Spacer(Modifier.height(8.dp))
            GhostButton("이 캐릭터 삭제", Modifier.fillMaxWidth(), delete)
        }
    }
}

@Composable
internal fun SwatchRow(
    presets: List<Long>,
    selected: Long,
    /** 최근 사용한 커스텀 색 — 프리셋 뒤에 구분선과 함께 붙는다 (목업 01장) */
    recent: List<Long> = emptyList(),
    onSelect: (Long) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEach { color -> DesktopSwatch(color, selected == color) { onSelect(color) } }
        if (recent.isNotEmpty()) {
            Box(Modifier.width(1.dp).height(16.dp).background(Tokens.Line))
            recent.forEach { color ->
                DesktopSwatch(color, selected == color, outlined = true) { onSelect(color) }
            }
        }
    }
}

/** @param outlined 흰색 계열도 보이도록 옅은 테두리 (최근 색용) */
@Composable
private fun DesktopSwatch(
    color: Long,
    on: Boolean,
    outlined: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(32.dp)
            // 밝은 다이얼로그 위 선택 표시는 잉크색 아웃라인 (라이트 목업 03장)
            .border(2.dp, if (on) Tokens.Ink else Color.Transparent, CircleShape)
            .clip(CircleShape)
            .background(Color(color))
            .then(if (outlined) Modifier.border(1.dp, Tokens.Line, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (on) Text("✓", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Tokens.BubbleInk) }
}

/** 커스텀 컬러 진입용 무지개 스와치 — on이면 프리셋 밖의 색이 선택된 상태 */
@Composable
internal fun CustomSwatch(on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp)
            .border(2.dp, if (on) Tokens.Ink else Color.Transparent, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.sweepGradient(
                    listOf(
                        Color(0xFFFF6666), Color(0xFFFFCC66), Color(0xFF66DD66),
                        Color(0xFF66CCFF), Color(0xFFCC66FF), Color(0xFFFF6666),
                    )
                )
            )
            .clickable(onClick = onClick),
    )
}

/**
 * 드래그 컬러 팔레트 — SV 박스(채도·명도) + 색상 띠 + HEX 입력.
 * 모바일 HexColorDialog의 팔레트와 동일 동작. 변경 즉시 onChange로 전달된다.
 */
@Composable
internal fun ColorPalettePicker(initial: Long, onChange: (Long) -> Unit) {
    val seedHsv = remember { argbToHsv(initial) }
    var hue by remember { mutableStateOf(seedHsv.first) }
    var sat by remember { mutableStateOf(seedHsv.second) }
    var bri by remember { mutableStateOf(seedHsv.third) }
    var hex by remember { mutableStateOf("%06X".format(initial and 0xFFFFFF)) }
    val current = hsvToArgb(hue, sat, bri)

    fun push() {
        val c = hsvToArgb(hue, sat, bri)
        hex = "%06X".format(c and 0xFFFFFF)
        onChange(c)
    }

    Column {
        var svSize by remember { mutableStateOf(IntSize.Zero) }
        fun pickSv(x: Float, y: Float) {
            if (svSize == IntSize.Zero) return
            sat = (x / svSize.width).coerceIn(0f, 1f)
            bri = 1f - (y / svSize.height).coerceIn(0f, 1f)
            push()
        }
        Box(
            Modifier.fillMaxWidth().height(140.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.horizontalGradient(listOf(Color.White, Color(hsvToArgb(hue, 1f, 1f))))
                )
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                .onSizeChanged { svSize = it }
                .pointerInput(Unit) { detectTapGestures { p -> pickSv(p.x, p.y) } }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        pickSv(change.position.x, change.position.y)
                    }
                }
        ) {
            Box(
                Modifier.offset {
                    IntOffset(
                        (sat * svSize.width).toInt() - 8.dp.roundToPx(),
                        ((1f - bri) * svSize.height).toInt() - 8.dp.roundToPx(),
                    )
                }
                    .size(16.dp)
                    // 흰 링만 두면 밝은 영역에서 사라진다 — 안쪽에 잉크 링을 겹친다
                    .border(3.dp, Tokens.Ink.copy(alpha = .45f), CircleShape)
                    .border(2.dp, Color.White, CircleShape)
                    .clip(CircleShape)
                    .background(Color(current))
            )
        }
        Spacer(Modifier.height(8.dp))
        var hueSize by remember { mutableStateOf(IntSize.Zero) }
        fun pickHue(x: Float) {
            if (hueSize == IntSize.Zero) return
            hue = (x / hueSize.width).coerceIn(0f, 1f) * 359.9f
            push()
        }
        Box(
            Modifier.fillMaxWidth().height(16.dp)
                .clip(RoundedCornerShape(8.dp))
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
                .pointerInput(Unit) { detectTapGestures { p -> pickHue(p.x) } }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        pickHue(change.position.x)
                    }
                }
        ) {
            Box(
                Modifier.offset {
                    IntOffset((hue / 360f * hueSize.width).toInt() - 8.dp.roundToPx(), 0)
                }
                    .size(16.dp)
                    .border(3.dp, Tokens.Ink.copy(alpha = .45f), CircleShape)
                    .border(2.dp, Color.White, CircleShape)
                    .clip(CircleShape)
                    .background(Color(hsvToArgb(hue, 1f, 1f)))
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(width = 40.dp, height = 26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(current))
                    .border(1.dp, Tokens.Line, RoundedCornerShape(6.dp))
            )
            Box(Modifier.weight(1f)) {
                OverlayField(hex, { typed ->
                    hex = typed
                    typed.trim().removePrefix("#")
                        .takeIf { it.length == 6 }?.toLongOrNull(16)?.or(0xFF000000)
                        ?.let { color ->
                            val (h, s, v) = argbToHsv(color)
                            hue = h; sat = s; bri = v
                            onChange(color)
                        }
                }, "HEX (예: 8EC5E8)")
            }
        }
    }
}

/** HSV(h 0–360, s/v 0–1) → 0xFFRRGGBB — 모바일 Ui.kt와 동일 변환 */
internal fun hsvToArgb(h: Float, s: Float, v: Float): Long {
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

internal fun argbToHsv(argb: Long): Triple<Float, Float, Float> {
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

/**
 * 프로필 관리 — 오너·GM·캐릭터 전부를 이미지+이름 목록으로 (모바일과 동일).
 * 항목 클릭 = 해당 설정, 하단 = 프로필 추가하기.
 */
@Composable
internal fun ProfileManagerOverlay(
    ownerName: String,
    ownerColor: Long,
    ownerImagePath: String?,
    profiles: List<Profile>,
    onDismiss: () -> Unit,
    onOwner: () -> Unit,
    onProfile: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    OverlayScaffold("프로필 관리", onDismiss) {
        // 오너 프로필 — 항상 맨 위
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOwner)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OwnerAvatar(ownerName, ownerColor, ownerImagePath, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    ownerName.ifBlank { "오너 프로필" },
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Tokens.Ink,
                )
                Text("오너 · 잡담과 참여 인사에 사용", fontSize = 11.sp, color = Tokens.InkDim)
            }
        }
        profiles.forEachIndexed { index, profile ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onProfile(index) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(DesktopDimens.avatarStrip).clip(CircleShape).background(Tokens.Panel2)
                        .border(
                            1.dp,
                            if (profile.isGm) Tokens.GmRing else Tokens.Line,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val chipImage = rememberLocalBitmap(profile.imagePath)
                    if (chipImage != null) {
                        Image(chipImage, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text(
                            profile.emoji, fontSize = 15.sp,
                            fontFamily = if (profile.isGm) GowunBatang else null,
                            color = if (profile.isGm) Tokens.SignatureInk else Tokens.Ink,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        profile.name.ifBlank { "이름 없음" },
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Tokens.Ink,
                    )
                    Text(
                        if (profile.isGm) "GM · 모든 방 공통" else "캐릭터 · 모든 방 공통",
                        fontSize = 11.sp, color = Tokens.InkDim,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // 프로필 추가하기 — 목록 맨 아래
        GhostButton("＋ 프로필 추가하기", Modifier.fillMaxWidth(), onAdd)
        Spacer(Modifier.height(10.dp))
        GhostButton("닫기", Modifier.fillMaxWidth(), onDismiss)
    }
}

/**
 * 오너 프로필 설정 — 이미지·이름·컬러만 (캐릭터 프로필 편집의 축소판, 모바일과 동일).
 * 잡담과 참여 인사에 쓰이는 '플레이어 본인' 프로필. forced면 저장 전 닫기 불가.
 */
@Composable
internal fun OwnerProfileOverlay(
    recentColors: Map<String, List<Long>> = emptyMap(),
    onColorUsed: (String, Long) -> Unit = { _, _ -> },
    initialName: String,
    initialColor: Long,
    initialImage: String?,
    initialTextColor: Long? = null,
    forced: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Long, String?, Long?) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    var imagePath by remember { mutableStateOf(initialImage) }
    var textColor by remember { mutableStateOf(initialTextColor) }
    var customOpen by remember { mutableStateOf(false) }
    var textCustomOpen by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    OverlayScaffold("오너 프로필", onDismiss = { if (!forced) onDismiss() }) {
        Text(
            "잡담과 참여 인사에 쓰이는 플레이어 본인 프로필입니다. " +
                "세션 캐릭터 목록에는 나타나지 않습니다.",
            fontSize = 11.sp, color = Tokens.InkDim,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OwnerAvatar(name, color, imagePath, 48.dp)
            GhostButton(
                if (imagePath == null) "이미지 선택" else "이미지 변경",
                Modifier.weight(1f),
            ) {
                if (!picking) {
                    picking = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            pickAndStoreImage("오너 프로필 이미지 선택", AppPaths.OWNER, DesktopDimens.PROFILE_PX)
                                ?.let { imagePath = it }
                        } finally {
                            picking = false
                        }
                    }
                }
            }
            if (imagePath != null) {
                GhostButton("제거") { imagePath = null }
            }
        }
        Spacer(Modifier.height(10.dp))
        OverlayField(name, { name = it }, "이름")
        Spacer(Modifier.height(14.dp))
        Text("컬러", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SwatchRow(Tokens.bubblePresets, color, recentColors["owner"].orEmpty()) {
                color = it
                customOpen = false
            }
            CustomSwatch(on = color !in Tokens.bubblePresets) { customOpen = !customOpen }
        }
        if (customOpen) {
            Spacer(Modifier.height(8.dp))
            ColorPalettePicker(color) { color = it; onColorUsed("owner", it) }
        }
        Spacer(Modifier.height(14.dp))
        Text("말풍선 글씨색", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SwatchRow(
                com.pbp.shared.Palette.textPresets,
                textColor ?: com.pbp.shared.Palette.textPresets.first(),
                recentColors["text"].orEmpty(),
            ) { textColor = it; textCustomOpen = false }
            CustomSwatch(on = textColor != null && textColor !in com.pbp.shared.Palette.textPresets) {
                textCustomOpen = !textCustomOpen
            }
        }
        if (textCustomOpen) {
            Spacer(Modifier.height(8.dp))
            ColorPalettePicker(textColor ?: com.pbp.shared.Palette.textPresets.first()) {
                textColor = it; onColorUsed("text", it)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YellowButton("저장", Modifier.weight(1f)) {
                if (name.isNotBlank()) onSave(name.trim(), color, imagePath, textColor)
            }
            if (!forced) {
                GhostButton("취소", Modifier.weight(1f), onDismiss)
            }
        }
    }
}
