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

/** 채팅 패널·메시지 렌더·입력 영역 — Main.kt에서 분리 (리뷰 B1) */

@Composable
internal fun ChatPane(
    room: JoinedRoom,
    messages: List<Message>,
    memberCount: Int?,
    profiles: List<Profile>,
    myUid: String,
    avatarCache: MutableMap<String, ImageBitmap?>,
    firestore: FirestoreRest,
    onSend: (String, Boolean, (Boolean, Boolean) -> Unit) -> Unit,
    onSwitchProfile: (Int) -> Unit,
    onAddProfile: () -> Unit,
    onShowCode: () -> Unit,
    onOpenSettings: () -> Unit,
    onMessageLongPress: (Message) -> Unit,
    onEditProfile: (Int) -> Unit,
    onExport: () -> Unit,
) {
    val theme = Color(room.themeColor)
    Box(Modifier.fillMaxSize()) {
        BackgroundLayer(room.backgroundKey, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Tokens.VeilTop, Tokens.VeilMid, Tokens.VeilTop)))
        )
        Column(Modifier.fillMaxSize()) {
            // 상단 바 — 높이 56, 좌우 24(PC 가장자리), 밝은 화이트 그라데이션.
            // PC는 좌측 정렬 유지 (trpg-app-mockup-pc-light.html) — 넓은 창에서 제목이
            // 사이드바 쪽 시선 흐름과 이어지고, 부제 규격만 모바일과 공유한다.
            Row(
                Modifier.fillMaxWidth().height(DesktopDimens.appBar)
                    .background(
                        Brush.verticalGradient(listOf(Color(0xD9FFFFFF), Color(0x59FFFFFF)))
                    )
                    .padding(horizontal = DesktopDimens.edge),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        room.name, fontFamily = GowunBatang, fontWeight = FontWeight.Bold,
                        fontSize = 15.sp, lineHeight = 15.sp, color = Tokens.Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // GM/PL · 참여 인원 — 방 테마 컬러 점을 둘 사이 구분점으로 쓴다
                        Text(
                            if (room.isMaster) "GM" else "PL",
                            fontSize = 11.sp, lineHeight = 11.sp,
                            fontWeight = FontWeight.Medium, color = Tokens.InkSub,
                        )
                        if (memberCount != null) {
                            Spacer(Modifier.width(4.dp))
                            Box(Modifier.size(6.dp).clip(CircleShape).background(theme))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${memberCount}명 참여 중",
                                fontSize = 11.sp, lineHeight = 11.sp,
                                fontWeight = FontWeight.Medium, color = Tokens.InkSub,
                            )
                        }
                    }
                }
                GhostButton("내보내기", Modifier, onExport)
                Spacer(Modifier.width(8.dp))
                GhostButton("초대 코드", Modifier, onShowCode)
                // 테마·배경 변경은 누구나 가능 (모바일과 동일 정책)
                Spacer(Modifier.width(8.dp))
                GhostButton("방 설정", Modifier, onOpenSettings)
            }

            // 메시지 목록 — 최신 메시지가 바뀔 때만, 바닥 근처를 보고 있을 때만 따라간다
            // (안드로이드 P1-7과 같은 규칙, C9)
            val listState = rememberLazyListState()
            // 방 입장 직후의 첫 로드는 무조건 최하단으로 (S2 — 빈 목록 기준 lastVisible=-1이라
            // 근접 판정이 항상 실패했음). 내 전송은 앱과 동일하게 전송 시점에 플래그를
            // 세워 실제 도착까지 유지한다 (N4와 동일 규칙).
            var initialScrollDone by remember(room.remoteId) { mutableStateOf(false) }
            var pendingScrollToLatest by remember(room.remoteId) { mutableStateOf(false) }
            // 한 폴링 배치로 여러 건이 오면(판정 쌍·오랜만의 수신) 근접 판정이 도착 수만큼
            // 어긋난다 — 직전 최신 메시지 위치로 추가 수를 세어 보정 (모바일과 동일 규칙)
            var prevLatestId by remember(room.remoteId) { mutableStateOf<String?>(null) }
            LaunchedEffect(messages.lastOrNull()?.docId, pendingScrollToLatest) {
                if (messages.isEmpty()) {
                    prevLatestId = null
                    return@LaunchedEffect
                }
                val prevIndex = prevLatestId?.let { id -> messages.indexOfLast { it.docId == id } } ?: -1
                val appended = if (prevIndex >= 0) messages.size - 1 - prevIndex else messages.size
                prevLatestId = messages.last().docId
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                val nearBottom = lastVisible >= messages.size - appended - 2
                val myMessageArrived = messages.last().authorUid == myUid
                if (!initialScrollDone || pendingScrollToLatest || nearBottom || myMessageArrived) {
                    listState.scrollToItem(messages.size - 1)
                    initialScrollDone = true
                    if (myMessageArrived) pendingScrollToLatest = false
                }
            }
            // 본문 최대 폭 720dp 중앙 정렬 — 초광폭에서 말풍선이 늘어지지 않게 (PC 규격)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxHeight().widthIn(max = DesktopDimens.contentMax).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = DesktopDimens.edge, vertical = 16.dp),
                ) {
                    // 같은 인물의 연속 메시지는 아바타·이름 생략 + 간격 축소 (모바일과 동일)
                    items(messages.size, key = { messages[it].docId }) { index ->
                        val message = messages[index]
                        val grouped = isContinuation(messages.getOrNull(index - 1), message)
                        Box(
                            Modifier.padding(top = if (index == 0) 0.dp else if (grouped) 2.dp else 12.dp)
                        ) {
                            MessageBlock(
                                message, myUid, room, avatarCache, firestore, grouped,
                                onLongPress = onMessageLongPress,
                            )
                        }
                    }
                }
            }

            // 입력 영역 — 전송 시 스크롤 플래그를 세워 실제 도착까지 유지 (N4)
            InputZone(
                room = room,
                profiles = profiles,
                theme = theme,
                onSend = { text, ooc, onResult ->
                    pendingScrollToLatest = true
                    onSend(text, ooc, onResult)
                },
                onSwitchProfile = onSwitchProfile,
                onAddProfile = onAddProfile,
                onEditProfile = onEditProfile,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBlock(
    message: Message,
    myUid: String,
    room: JoinedRoom,
    avatarCache: MutableMap<String, ImageBitmap?>,
    firestore: FirestoreRest,
    grouped: Boolean = false,
    onLongPress: (Message) -> Unit = {},
) {
    val mine = message.authorUid == myUid
    when {
        message.type == "SYSTEM" -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xBFFFFFFF))
                        .border(1.dp, Color(0x1214191F), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                ) {
                    Text(message.body, fontSize = 10.sp, color = Color(0x8C23272E))
                }
            }
        }
        message.type == "DICE" -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xD9FFFFFF))
                        .border(1.dp, Color(0x80C89E34), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🎲", fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${message.diceExpr} → ${message.body}",
                        fontSize = 11.sp, color = Color(0xFF7A5B12), fontWeight = FontWeight.Bold,
                    )
                    // 판정 등급 — 성공 계열 파랑, 실패 빨강 (모바일과 동일 표기)
                    Rules.outcomeLabel(message.diceOutcome)?.let { label ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (Rules.isSuccess(message.diceOutcome)) Color(0xFF5E9EFF)
                            else Color(0xFFFF6B6B),
                        )
                    }
                }
            }
        }
        // 잡담은 극 밖의 대화 — 시스템 안내처럼 화면 중앙에 '이름 : 내용',
        // 배경은 그 캐릭터의 말풍선 색 반투명 (모바일과 동일)
        message.isOoc -> {
            val chatterColor = Color(message.senderBubbleColor ?: Tokens.bubblePresets.first())
                .copy(alpha = .55f)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(chatterColor)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (mine) onLongPress(message) },
                        )
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                ) {
                    Text(
                        "${message.senderName ?: ""} : ${message.body}",
                        fontSize = 10.sp,
                        color = Tokens.BubbleInk.copy(alpha = .85f),
                    )
                }
            }
        }
        message.senderIsGm -> {
            // 정규식 분해를 리컴포지션마다 반복하지 않는다 (F2)
            val parts = remember(message.body) { GmSpeech.split(message.body) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                parts.forEach { part ->
                    when (part) {
                        is GmSpeech.Part.Narration -> NarrationBlock(
                            message, part.text,
                            onLongPress = { if (mine) onLongPress(message) },
                        )
                        is GmSpeech.Part.Quote -> BubbleRow(
                            message = message, myUid = myUid, room = room,
                            avatarCache = avatarCache, firestore = firestore,
                            overrideBody = part.text, overrideName = "GM",
                            overrideBubbleColor = Tokens.gmQuoteBubble,
                            onLongPress = onLongPress,
                        )
                    }
                }
            }
        }
        else -> {
            // 캐릭터 발화도 GM과 같은 규칙 — 문장 중간의 " " 대사만 인용 말풍선으로 분리 (F2: remember)
            val parts = remember(message.body) { GmSpeech.split(message.body) }
            if (parts.size <= 1) {
                BubbleRow(
                    message, myUid, room, avatarCache, firestore,
                    showHeader = !grouped,
                    onLongPress = onLongPress,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    parts.forEachIndexed { index, part ->
                        BubbleRow(
                            message = message, myUid = myUid, room = room,
                            avatarCache = avatarCache, firestore = firestore,
                            overrideBody = part.text(),
                            quoteBubble = part is GmSpeech.Part.Quote,
                            showHeader = !grouped && index == 0,
                            showTime = index == parts.lastIndex,
                            onLongPress = onLongPress,
                        )
                    }
                }
            }
        }
    }
}

/** 같은 인물의 연속 말풍선인지 — 아바타·이름 생략과 간격 축소 판정 (모바일과 동일 규칙) */
internal fun isContinuation(prev: Message?, current: Message): Boolean {
    if (prev == null) return false
    fun isBubble(m: Message) = m.type == "TEXT" && !m.senderIsGm && !m.isOoc
    if (!isBubble(prev) || !isBubble(current)) return false
    return prev.senderName == current.senderName && prev.authorUid == current.authorUid
}

internal fun GmSpeech.Part.text(): String = when (this) {
    is GmSpeech.Part.Narration -> text
    is GmSpeech.Part.Quote -> text
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NarrationBlock(message: Message, text: String, onLongPress: () -> Unit = {}) {
    val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
    Column(
        Modifier.fillMaxWidth()
            .shadow(3.dp, shape) // 목업 box-shadow 0 3px 12px
            .clip(shape)
            .background(Tokens.NarrBg)
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // 서술은 문단 자체가 화면 — 서술자·시간 등 메타 표기는 두지 않는다 (모바일과 동일)
        MarkupText(
            text = text, fontSize = 13.sp, color = Tokens.NarrInk,
            rubyColor = Tokens.SignatureInk, fontFamily = GowunBatang, lineHeight = 24.sp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BubbleRow(
    message: Message,
    myUid: String,
    room: JoinedRoom,
    avatarCache: MutableMap<String, ImageBitmap?>,
    firestore: FirestoreRest,
    overrideBody: String? = null,
    overrideName: String? = null,
    overrideBubbleColor: Long? = null,
    /** 이 조각이 대사(인용)임을 호출부가 이미 판정한 경우 */
    quoteBubble: Boolean = false,
    showHeader: Boolean = true, // false = 연속 메시지 (아바타·이름 생략)
    showTime: Boolean = true, // 한 메시지가 여러 말풍선으로 나뉘면 마지막에만
    onLongPress: (Message) -> Unit = {},
) {
    val mine = message.authorUid == myUid && overrideName == null
    // 편집/삭제 대상 여부는 표시 방향(mine)과 무관하게 실제 작성자 기준 (앱과 동일)
    val editable = message.authorUid == myUid
    val body = overrideBody ?: message.body
    // 대사는 인용 말풍선 — 모바일과 동일 규칙 (목업 mockup-quote-bubble)
    val quoteInner = when {
        message.isOoc -> null
        quoteBubble -> body
        overrideName == null -> quoteContent(body)
        else -> null
    }
    val bubbleColor = when {
        message.isOoc -> Tokens.ChatterBubble
        else -> Color(overrideBubbleColor ?: message.senderBubbleColor ?: Tokens.bubblePresets.first())
    }
    val nameColor = when {
        message.isOoc -> Tokens.InkDim
        overrideName != null -> Tokens.SignatureInk
        // 밝은 배경 위에서는 저장된 밝은 이름색을 진한 색으로 치환 (스펙 2장)
        message.senderNameColor != null -> Color(Tokens.nameColorForLight(message.senderNameColor))
        else -> Tokens.Ink
    }
    val inkColor = if (message.isOoc) Tokens.ChatterInk else Tokens.BubbleInk

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mine && showTime) {
                // 내 메시지: 시간은 말풍선 왼쪽 (모바일과 동일)
                TimeStamp(message, Color(room.themeColor), Modifier.align(Alignment.Bottom))
            }
            if (!mine) {
                if (showHeader) {
                    MessageAvatar(message, room, avatarCache, firestore)
                } else {
                    Box(Modifier.size(DesktopDimens.avatarChat)) // 연속 메시지 — 자리만 유지
                }
            }
            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                if (showHeader) {
                    Text(
                        overrideName ?: message.senderName ?: "",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = nameColor,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                val shape = if (mine) {
                    RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
                } else {
                    RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
                }
                if (quoteInner != null) {
                    // 여는 “ 좌상단 · 닫는 ” 우하단 — 오프셋은 상하좌우 대칭(7·9dp).
                    // 닫는 따옴표는 글리프 잉크가 글자 상자 위쪽에 몰려 있어 offset으로 보정한다.
                    Box(
                        Modifier.widthIn(max = DesktopDimens.bubbleMax)
                            .shadow(2.dp, shape) // 목업 box-shadow 0 2px 8px
                            .clip(shape).background(bubbleColor)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { if (editable) onLongPress(message) },
                            )
                    ) {
                        QuoteMark(
                            "“",
                            inkColor,
                            Modifier.align(Alignment.TopStart).padding(start = 9.dp, top = 5.dp),
                        )
                        QuoteMark(
                            "”",
                            inkColor,
                            Modifier.align(Alignment.BottomEnd).padding(end = 9.dp).offset(y = 6.dp),
                        )
                        MarkupText(
                            text = quoteInner, fontSize = 13.sp, color = inkColor,
                            rubyColor = inkColor.copy(alpha = .65f), lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp),
                        )
                    }
                } else {
                    Box(
                        Modifier.widthIn(max = DesktopDimens.bubbleMax)
                            .shadow(2.dp, shape) // 목업 box-shadow 0 2px 8px
                            .clip(shape).background(bubbleColor)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { if (editable) onLongPress(message) },
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row {
                            if (message.isOoc) {
                                Text(
                                    "잡담", fontSize = 9.sp, color = inkColor,
                                    modifier = Modifier.padding(end = 6.dp, top = 2.dp)
                                        .border(1.dp, inkColor.copy(alpha = .4f), RoundedCornerShape(999.dp))
                                        .padding(horizontal = 5.dp),
                                )
                            }
                            MarkupText(
                                text = body, fontSize = 13.sp, color = inkColor,
                                rubyColor = inkColor.copy(alpha = .65f), lineHeight = 20.sp,
                                fontWeight = if (message.isOoc) FontWeight.Normal else FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            if (!mine && showTime) {
                // 남의 메시지: 시간은 말풍선 오른쪽 (모바일과 동일)
                TimeStamp(message, Color(room.themeColor), Modifier.align(Alignment.Bottom))
            }
            if (mine) {
                if (showHeader) {
                    MessageAvatar(message, room, avatarCache, firestore)
                } else {
                    Box(Modifier.size(DesktopDimens.avatarChat)) // 연속 메시지 — 자리만 유지
                }
            }
        }
    }
}

/** 말풍선 곁 시간 + (수정됨) — 모바일 TimeStamp와 동일. 시간 색 = 방 테마 컬러 */
@Composable
internal fun TimeStamp(message: Message, themeColor: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (message.editedAt != null) {
            Text("(수정됨)", fontSize = 9.sp, color = Tokens.InkDim)
        }
        Text(formatTime(message.createdAt), fontSize = 10.sp, color = themeColor)
    }
}

/**
 * 본문 전체가 쌍따옴표(" 또는 “ ”)로 감싸인 대사인지 — 감싸였으면 안쪽 내용을 돌려준다.
 * 모바일 ChatScreen의 quoteContent와 동일 규칙.
 */
internal fun quoteContent(body: String): String? {
    val trimmed = body.trim()
    if (trimmed.length < 2) return null
    if (trimmed.first() !in "\"“" || trimmed.last() !in "\"”") return null
    return trimmed.substring(1, trimmed.length - 1).trim().ifEmpty { null }
}

/** 인용 말풍선의 장식 따옴표 — 명조 볼드, 말풍선 잉크의 옅은 톤 */
@Composable
internal fun QuoteMark(mark: String, inkColor: Color, modifier: Modifier) {
    Text(
        mark,
        fontFamily = GowunBatang,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 24.sp,
        color = inkColor.copy(alpha = .32f),
        modifier = modifier,
    )
}

@Composable
internal fun MessageAvatar(
    message: Message,
    room: JoinedRoom,
    avatarCache: MutableMap<String, ImageBitmap?>,
    firestore: FirestoreRest,
) {
    val avatarId = message.avatarId
    // 이펙트는 항상 컴포지션에 있어야 한다 — 조건 안에 두면 캐시 쓰기가 리컴포지션을
    // 유발해 자기 자신을 취소시킨다(R1). 중복 fetch는 스냅샷이 아닌 별도 집합으로 막는다.
    LaunchedEffect(avatarId, room.remoteId) {
        if (avatarId == null) return@LaunchedEffect
        if (avatarCache[avatarId] != null) return@LaunchedEffect
        if (!avatarsInFlight.add(avatarId)) return@LaunchedEffect
        try {
            val bitmap = withContext(Dispatchers.IO) {
                fetchAvatarCached(firestore, room.remoteId, avatarId)?.let { bytes ->
                    runCatching {
                        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                    }.getOrNull()
                }
            }
            // 실패는 캐시하지 않는다 — 다음 표시 때 재시도 (P3-15)
            if (bitmap != null) {
                // 단순 상한 (M3) — 초과 시 비움. 디스크 캐시(P9)가 재적재를 싸게 만든다
                if (avatarCache.size >= 64) avatarCache.clear()
                avatarCache[avatarId] = bitmap
            }
        } finally {
            avatarsInFlight.remove(avatarId)
        }
    }
    val bitmap = avatarId?.let { avatarCache[it] }
    Box(
        Modifier.size(DesktopDimens.avatarChat)
            .border(1.5.dp, Color.White.copy(alpha = .85f), CircleShape)
            .clip(CircleShape)
            .background(Tokens.Panel2)
            .alpha(if (message.isOoc) 0.55f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap, contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(message.senderEmoji ?: "🙂", fontSize = 15.sp)
        }
    }
}

// ══════════════ 입력 영역 ══════════════

/** 아바타 fetch in-flight 집합 — 스냅샷 상태가 아니라 리컴포지션을 유발하지 않는다 (R1) */
internal val avatarsInFlight: MutableSet<String> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InputZone(
    room: JoinedRoom,
    profiles: List<Profile>,
    theme: Color,
    onSend: (String, Boolean, (Boolean, Boolean) -> Unit) -> Unit,
    onSwitchProfile: (Int) -> Unit,
    onAddProfile: () -> Unit,
    onEditProfile: (Int) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var oocOn by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 전송 — 버튼과 Ctrl+Enter가 공유
    val doSend = send@{
        if (input.isBlank()) return@send
        val text = input
        val ooc = oocOn
        input = ""
        errorMessage = null
        // 전송 실패 시 입력을 복원해 무통보 소실을 막는다 (P1-5).
        // 단 본문이 이미 올라갔으면 복원하지 않는다 — 재전송 시 2건이 된다 (N3)
        onSend(text, ooc) { textOk, diceOk ->
            when {
                !textOk -> {
                    if (input.isEmpty()) {
                        // 늦게 도착한 실패 콜백이 새로 친 글을 덮지 않도록 (N3)
                        input = text
                        oocOn = ooc
                        errorMessage = "전송에 실패했습니다 — 네트워크를 확인해주세요"
                    } else {
                        // 새 입력을 이미 치고 있으면 원문이 갈 곳이 없다 —
                        // 에러 라인에 원문을 남겨 복사할 수 있게 (C3)
                        errorMessage = "전송 실패 — 잃은 내용: $text"
                    }
                }
                !diceOk -> errorMessage = "메시지는 전송됐지만 다이스 결과 전송에 실패했습니다"
            }
        }
    }

    Column(Modifier.fillMaxWidth().background(Color(0xEBFFFFFF))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Line))
        // 본문과 같은 720dp 중앙 정렬 (PC 규격)
        Column(
            Modifier.align(Alignment.CenterHorizontally)
                .widthIn(max = DesktopDimens.contentMax).fillMaxWidth()
                .padding(start = DesktopDimens.edge, end = DesktopDimens.edge, top = 8.dp, bottom = 12.dp),
        ) {
            // 참여자에게는 GM 프로필을 숨긴다 — 서술 권한은 마스터 전용 (모바일과 동일)
            val visible = profiles.withIndex().filter { room.isMaster || !it.value.isGm }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visible.size) { vi ->
                    val (index, profile) = visible[vi]
                    val on = index == room.activeProfileIndex
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        // 탭 = 전환, 길게 = 편집 (앱 프로필 스트립과 동일)
                        modifier = Modifier.combinedClickable(
                            onClick = { onSwitchProfile(index) },
                            onLongClick = { onEditProfile(index) },
                        ),
                    ) {
                        Box(
                            Modifier.size(36.dp)
                                .border(
                                    2.dp,
                                    when {
                                        on -> Tokens.SignatureRing
                                        profile.isGm -> Color(0x99C89E34) // GM 금테
                                        else -> Color(0x2614191F)
                                    },
                                    CircleShape,
                                )
                                .clip(CircleShape)
                                .background(Tokens.Panel2),
                            contentAlignment = Alignment.Center,
                        ) {
                            val chipImage = rememberLocalBitmap(profile.imagePath)
                            if (chipImage != null) {
                                Image(
                                    chipImage, null, Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Text(
                                    profile.emoji, fontSize = 15.sp,
                                    fontFamily = if (profile.isGm) GowunBatang else null,
                                    color = if (profile.isGm) Tokens.SignatureInk else Tokens.Ink,
                                )
                            }
                        }
                        Text(
                            profile.name, fontSize = 10.sp,
                            color = if (on) Tokens.SignatureInk else Tokens.InkDim,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(36.dp)
                                .border(1.dp, Color(0x4714191F), CircleShape)
                                .clip(CircleShape)
                                .clickable(onClick = onAddProfile),
                            contentAlignment = Alignment.Center,
                        ) { Text("＋", color = Tokens.InkDim, fontSize = 15.sp) }
                        Text("추가", fontSize = 10.sp, color = Tokens.InkDim)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 자동완성 채팅 팔레트 — 활성 캐릭터의 값 이름 부분 입력 시 판정 매크로 (모바일과 동일)
            val activeStats = profiles.getOrNull(room.activeProfileIndex)?.stats ?: emptyMap()
            val suggestions = ProfileStats.paletteSuggestions(input, activeStats)
            if (suggestions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { name ->
                        Text(
                            "$name 판정",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Tokens.SignatureInk,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color(0x2EFFD05C))
                                .border(1.dp, Color(0x66C89E34), RoundedCornerShape(999.dp))
                                .clickable {
                                    val command =
                                        Rules.judgeCommand(room.rule ?: Rules.COC7, name)
                                    input = ""
                                    errorMessage = null
                                    onSend("$command $name 판정", false) { textOk, _ ->
                                        if (!textOk) errorMessage = "판정 전송에 실패했습니다"
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (oocOn) Color(0x47FFD05C) else Tokens.FieldBg)
                        .border(
                            1.dp,
                            if (oocOn) Color(0x8CC89E34) else Tokens.Line,
                            RoundedCornerShape(999.dp),
                        )
                        .clickable { oocOn = !oocOn }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "잡담", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = if (oocOn) Color(0xFF7A5B12) else Tokens.InkDim,
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f)
                        .onPreviewKeyEvent { event ->
                            // PC는 Ctrl+Enter로 바로 전송 (모바일과 동일 규칙).
                            // 한글 IME 조합 중에는 KeyDown이 IME에 먹혀 도달하지 않으므로
                            // KeyUp 시점에 발사하고, Down/Up 모두 소비해 개행 삽입을 막는다
                            if (event.key == Key.Enter && event.isCtrlPressed) {
                                if (event.type == KeyEventType.KeyUp && input.isNotBlank()) doSend()
                                true
                            } else false
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .background(Tokens.FieldBg)
                        .border(1.dp, Tokens.Line, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    textStyle = TextStyle(color = Tokens.Ink, fontSize = 13.sp),
                    cursorBrush = SolidColor(Tokens.SignatureRing),
                    maxLines = 4,
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                if (input.isEmpty()) {
                                    Text(
                                        if (oocOn) "잡담으로 보내기…" else "**굵게** · |等臺《등대》 · 1d100",
                                        fontSize = 11.sp, color = Tokens.InkDim,
                                    )
                                }
                                inner()
                            }
                            // PC는 Ctrl+Enter 힌트를 상시 노출 (trpg-app-mockup-pc-light.html)
                            Text(
                                "Ctrl+Enter 전송", fontSize = 10.sp, color = Color(0x5914191F),
                                modifier = Modifier.padding(start = 8.dp)
                                    .border(1.dp, Color(0x2614191F), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    },
                )
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (input.isNotBlank()) theme else theme.copy(alpha = .35f))
                        .clickable(enabled = input.isNotBlank()) { doSend() },
                    contentAlignment = Alignment.Center,
                ) { Text("➤", fontSize = 15.sp, color = Color.White) }
            }
            errorMessage?.let { message ->
                Text(
                    message,
                    fontSize = 11.sp,
                    color = Tokens.Danger,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// ══════════════ 오버레이(다이얼로그) ══════════════

internal fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
