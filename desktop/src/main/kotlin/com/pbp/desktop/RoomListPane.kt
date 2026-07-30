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
import com.pbp.desktop.ui.DesktopDimens
import com.pbp.desktop.ui.DesktopTiming

/** 왼쪽 패널(방 목록)과 배경 렌더 — Main.kt에서 분리 (리뷰 B1) */

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun LeftPane(
    rooms: List<JoinedRoom>,
    selected: JoinedRoom?,
    onSelect: (JoinedRoom) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onFontSetting: () -> Unit,
    onLeave: (JoinedRoom) -> Unit,
    ownerName: String,
    ownerColor: Long,
    ownerImagePath: String?,
    onOwnerProfile: () -> Unit,
) {
    // PC 규격: 사이드바 280dp 고정 (trpg-app-mockup-pc-light.html)
    Column(
        Modifier.width(DesktopDimens.sidebar).fillMaxHeight()
            .background(Brush.verticalGradient(listOf(Color(0xFFFBF9F4), Color(0xFFF0EDE5)))),
    ) {
        // 헤더 — 로고+워드마크 1행, 부제 2행. 묶음 전체가 정중앙 (목업 mockup-home-header).
        // 버튼은 우측 끝에 겹쳐 두므로 타이틀 중심은 버튼 개수와 무관하다.
        Box(Modifier.fillMaxWidth().height(DesktopDimens.appBar)) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                // 오너 프로필 — 탭하여 편집 (모바일 방 목록의 오너 칩과 동일)
                OwnerAvatar(
                    ownerName, ownerColor, ownerImagePath, DesktopDimens.avatarBar,
                    Modifier.clickable(onClick = onOwnerProfile),
                )
                Spacer(Modifier.width(6.dp))
                // 앱 글꼴 설정 — 모바일 방 목록의 'Aa' 버튼과 동일 위계
                Box(
                    Modifier.size(DesktopDimens.avatarBar).clip(CircleShape)
                        .border(1.dp, Tokens.Line, CircleShape)
                        .clickable(onClick = onFontSetting),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Aa", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
                }
            }
            Column(
                // 우측 버튼 묶음(32+6+32+여백)만큼 좌우를 같이 비워 정중앙을 보장
                Modifier.align(Alignment.Center).padding(horizontal = 88.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 새 앱 아이콘(시안 02 '포스트잇')과 동일한 옐로 타일 + 잉크 d10
                    Box(
                        Modifier.size(22.dp).clip(RoundedCornerShape(7.dp))
                            .background(
                                Brush.linearGradient(listOf(Color(0xFFFFD05C), Color(0xFFEFB945)))
                            ),
                        contentAlignment = Alignment.Center,
                    ) { D10Mark(Modifier.size(width = 13.dp, height = 14.dp)) }
                    // 라이트 모드 "PbP" 강조색 = 잉크 블랙 (스펙 2장)
                    Text(
                        "PbP", fontFamily = GowunBatang, fontWeight = FontWeight.Bold,
                        fontSize = 18.sp, lineHeight = 18.sp, color = Tokens.Ink,
                    )
                }
                Spacer(Modifier.height(4.dp))
                // 모바일 방 목록과 같은 문구 — 좁은 사이드바에 맞춰 '· PC' 꼬리표는 뺀다
                Text(
                    "진행 중인 세션 ${rooms.size}",
                    fontSize = 11.sp, lineHeight = 11.sp,
                    fontWeight = FontWeight.Medium, color = Tokens.InkSub,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = DesktopDimens.gap4),
            verticalArrangement = Arrangement.spacedBy(DesktopDimens.gap3),
            contentPadding = PaddingValues(vertical = DesktopDimens.gap2),
        ) {
            items(rooms, key = { it.remoteId }) { room ->
                val active = room.remoteId == selected?.remoteId
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (active) Color(room.themeColor).copy(alpha = .14f)
                            else Color(0x0914191F)
                        )
                        .border(
                            1.dp,
                            if (active) Color(room.themeColor).copy(alpha = .45f) else Tokens.Line,
                            RoundedCornerShape(16.dp),
                        )
                        // 탭 = 선택, 길게 = 나가기 (앱 방 목록의 길게 누르기와 동일 위계)
                        .combinedClickable(
                            onClick = { onSelect(room) },
                            onLongClick = { onLeave(room) },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(48.dp)) {
                        // 방 아이콘 폐지 — 배경(프리셋 그라데이션 또는 커스텀 이미지)으로만 구분
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))) {
                            BackgroundLayer(room.backgroundKey, Modifier.fillMaxSize())
                        }
                        Box(
                            Modifier.size(14.dp)
                                .align(Alignment.BottomEnd)
                                .border(3.dp, Color(0xFFFBF9F4), CircleShape)
                                .clip(CircleShape)
                                .background(Color(room.themeColor))
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            room.name, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (room.isMaster) "마스터 · 코드 ${room.inviteCode ?: "-"}" else "참여자",
                            fontSize = 11.sp, color = Tokens.InkDim,
                        )
                    }
                }
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YellowButton("＋ 새 세션", Modifier.weight(1f), onCreate)
            GhostButton("코드로 참여", Modifier.weight(1f), onJoin)
        }
    }
}

/**
 * 방 배경 — backgroundKey가 preset_*이면 그라데이션, 아니면 로컬 이미지 파일(커스텀).
 * 파일이 없거나 읽기 실패면 등대 프리셋으로 폴백 (모바일 RoomBackdrop과 동일 규칙)
 */
@Composable
internal fun BackgroundLayer(backgroundKey: String, modifier: Modifier = Modifier) {
    val preset = Tokens.backgroundPresets[backgroundKey]
    if (preset == null) {
        val bitmap by produceState<ImageBitmap?>(null, backgroundKey) {
            // 경로 공용 캐시 (M2) — 채팅 배경과 방 목록 썸네일이 같은 이미지를
            // 각각 디코드해 이중 상주(~10-20MB)하던 것 제거
            value = backgroundBitmapCache[backgroundKey] ?: withContext(Dispatchers.IO) {
                runCatching {
                    org.jetbrains.skia.Image.makeFromEncoded(java.io.File(backgroundKey).readBytes())
                        .toComposeImageBitmap()
                }.getOrNull()?.also {
                    if (backgroundBitmapCache.size >= 8) backgroundBitmapCache.clear() // 상한 (M3)
                    backgroundBitmapCache[backgroundKey] = it
                }
            }
        }
        bitmap?.let {
            Image(
                bitmap = it, contentDescription = null, modifier = modifier,
                contentScale = ContentScale.Crop,
            )
            return
        }
    }
    val colors = preset ?: Tokens.backgroundPresets.getValue(Protocol.DEFAULT_BACKGROUND)
    Box(modifier.background(Brush.verticalGradient(listOf(Color(colors.first), Color(colors.second)))))
}

/** 새 앱 아이콘과 같은 d10 마크 — 잉크 면 5개 + 옐로 분할선 (모바일 ic_logo_d10과 동일 지오메트리) */
@Composable
internal fun D10Mark(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val faces = listOf(
            listOf(50f to 6f, 9f to 54f, 33f to 60f) to Color(0xFF23272E),
            listOf(9f to 54f, 50f to 98f, 33f to 60f) to Color(0xFF181C22),
            listOf(50f to 6f, 91f to 54f, 67f to 60f) to Color(0xFF2A2F38),
            listOf(91f to 54f, 50f to 98f, 67f to 60f) to Color(0xFF1D222A),
            listOf(50f to 6f, 67f to 60f, 50f to 98f, 33f to 60f) to Color(0xFF23272E),
        )
        val sx = size.width / 100f
        val sy = size.height / 104f
        faces.forEach { (points, color) ->
            val path = androidx.compose.ui.graphics.Path().apply {
                points.forEachIndexed { index, (x, y) ->
                    if (index == 0) moveTo(x * sx, y * sy) else lineTo(x * sx, y * sy)
                }
                close()
            }
            drawPath(path, color)
            drawPath(
                path,
                Color(0xFFFFD05C),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2f * sx,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
        }
    }
}

@Composable
internal fun EmptyPane() {
    Box(Modifier.fillMaxSize().background(Tokens.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎲", fontSize = 40.sp) // 모바일과 같은 예외 크기
            Spacer(Modifier.height(DesktopDimens.gap2))
            Text("왼쪽에서 세션을 만들거나 초대 코드로 참여하세요", color = Tokens.InkDim, fontSize = 13.sp)
        }
    }
}

// ══════════════ 채팅 패널 ══════════════

/** 커스텀 배경 디코드 공용 캐시 (M2) — 경로가 타임스탬프 파일명이라 무효화 불필요 */
internal val backgroundBitmapCache =
    java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()
