package com.pbp.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.desktop.data.FirestoreRest
import com.pbp.desktop.data.JoinedRoom
import com.pbp.desktop.data.Message
import com.pbp.desktop.data.Profile
import com.pbp.shared.ChatDates
import com.pbp.shared.ProfileStats
import com.pbp.shared.Rules
import com.pbp.shared.GmSpeech
import com.pbp.desktop.ui.GowunBatang
import com.pbp.desktop.ui.MarkupText
import com.pbp.desktop.ui.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pbp.shared.Protocol
import com.pbp.desktop.ui.DesktopDimens

/** 채팅 패널·메시지 렌더·입력 영역 — Main.kt에서 분리 (리뷰 B1) */

@Composable
internal fun ChatPane(
    room: JoinedRoom,
    messages: List<Message>,
    profiles: List<Profile>,
    myUid: String,
    avatarCache: MutableMap<String, ImageBitmap?>,
    firestore: FirestoreRest?,
    onSend: (String, Boolean, (Boolean, Boolean) -> Unit) -> Unit,
    onSwitchProfile: (Int) -> Unit,
    onAddProfile: () -> Unit,
    onShowCode: () -> Unit,
    onOpenSettings: () -> Unit,
    onMessageLongPress: (Message) -> Unit,
    onEditProfile: (Int) -> Unit,
    /** 입력창 "?" — 지원 문법 도움말 오버레이 열기 */
    onShowMarkupHelp: () -> Unit,
    onTyping: () -> Unit,
    onTypingStopped: () -> Unit,
    /** 캡처 범위 (messages 인덱스). null이면 캡처 모드가 아니다 */
    captureIdx: IntRange?,
    onCaptureTap: (Int) -> Unit,
    onCaptureExit: () -> Unit,
    onCaptureMake: () -> Unit,
    captureRendering: Boolean,
    captureWithBackground: Boolean,
    onToggleCaptureBackground: () -> Unit,
    captureExcludeOoc: Boolean,
    onToggleCaptureExcludeOoc: () -> Unit,
    /** 끝점이 정해졌는가 — 한 건만 고른 상태와 "아직 끝 미선택"을 구분한다 (R7) */
    captureEndPicked: Boolean,
    /** 캡처 실패 사유 — 하단 바에 그대로 보여 준다 (V3) */
    captureError: String?,
    /** 판정 요청을 눌렀을 때 — 그 캐릭터를 가진 쪽에서만 호출된다 (J6) */
    onJudgeRoll: (Message) -> Unit,
    /** GM 프로필로 말하는 중에만 보이는 판정 요청 열기 (J8) */
    onJudgeRequest: () -> Unit,
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
            // 타이틀 묶음은 버튼 개수와 무관하게 **정중앙** — 좌우 인셋을 같게 준다
            // (가이드 §5. 데스크톱 예외는 사이드바 280·본문 720·가장자리 24뿐, P1)
            if (captureIdx != null) CaptureModeBar(
                subtitle = if (!captureEndPicked) "끝 메시지를 클릭하세요"
                else "양 끝을 다시 클릭해 조절할 수 있어요",
                onClose = onCaptureExit,
            ) else
            Box(
                Modifier.fillMaxWidth().height(DesktopDimens.appBar)
                    .background(
                        Brush.verticalGradient(listOf(Tokens.SidebarTop, Tokens.SidebarBottom))
                    )
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = DesktopDimens.edge),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    GhostButton("초대 코드", Modifier, onShowCode)
                    // 테마·배경 변경은 누구나 가능 (모바일과 동일 정책)
                    Spacer(Modifier.width(DesktopDimens.gap2))
                    GhostButton("방 설정", Modifier, onOpenSettings)
                }
                Column(
                    Modifier.align(Alignment.Center).fillMaxWidth()
                        .padding(horizontal = DesktopDimens.titleInset),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        room.name, fontFamily = GowunBatang, fontWeight = FontWeight.Bold,
                        fontSize = 15.sp, lineHeight = 15.sp, color = Tokens.Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(DesktopDimens.gap1))
                    Text(
                        if (room.isMaster) "GM" else "PL",
                        fontSize = 11.sp, lineHeight = 11.sp,
                        fontWeight = FontWeight.Medium, color = Tokens.InkSub,
                    )
                }
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
            // 굴림이 끝난 요청 키 — 메시지마다 전체를 훑으면 O(N²) (J5)
            val rolledRefs = remember(messages) {
                messages.mapNotNullTo(mutableSetOf()) { it.judgeRef }
            }
            val myCharacters = remember(profiles) { profiles.map { it.name }.toSet() }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxHeight().widthIn(max = DesktopDimens.contentMax).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = DesktopDimens.edge, vertical = DesktopDimens.gap3),
                ) {
                    // 같은 인물의 연속 메시지는 아바타·이름 생략 + 간격 축소 (모바일과 동일)
                    // 방을 만든 날 — 로그 맨 위 (데스크톱은 전체 히스토리를 들고 있다)
                    room.createdAt?.let { createdAt ->
                        item(key = "room-created") { DayDivider(createdAt) }
                    }
                    items(messages.size, key = { messages[it].docId }) { index ->
                        val message = messages[index]
                        // 바로 위(더 오래된) 항목과 날짜가 다르면 구분선을 얹는다. 가장 오래된
                        // 항목은 방 생성일과 비교한다 — 같은 날이면 맨 위 구분선이 이미 말했다
                        val olderNeighbor = messages.getOrNull(index - 1)?.createdAt
                            ?: room.createdAt
                        val showDay = olderNeighbor == null ||
                            !ChatDates.isSameDay(olderNeighbor, message.createdAt)
                        val grouped = isContinuation(messages.getOrNull(index - 1), message)
                        // 목록은 오름차순이라 "다음" 메시지는 index + 1 (모바일과 동일 규칙)
                        val showTime = !sharesTimeLabel(message, messages.getOrNull(index + 1))
                        val mark = captureMarkOf(captureIdx, index)
                        // 위 항목도 범위 안이면 간격을 없애 밴드가 맞닿게 한다
                        val joinsAbove = captureIdx?.contains(index) == true &&
                            captureIdx.contains(index - 1)
                        Column {
                            // 구분선이 이미 위아래 여백을 갖고 있어 말풍선 여백을 또 주면 벌어진다
                            if (showDay) DayDivider(message.createdAt)
                            Box(
                                Modifier.padding(
                                    top = when {
                                        index == 0 || joinsAbove || showDay -> 0.dp
                                        grouped -> DesktopDimens.gap1 // 모바일과 같은 연속 간격
                                        else -> DesktopDimens.gap3
                                    }
                                )
                            ) {
                                MessageBlock(
                                    message, myUid, room, avatarCache, firestore, grouped,
                                    showTime = showTime,
                                    mark = mark,
                                    judgeState = when {
                                        (message.docId in rolledRefs) -> JudgeState.Done
                                        message.judgeTarget in myCharacters -> JudgeState.MyTurn
                                        else -> JudgeState.Waiting
                                    },
                                    onJudgeTap = { onJudgeRoll(message) },
                                    onTap = if (captureIdx != null) ({ onCaptureTap(index) }) else null,
                                    // 캡처 모드에서는 편집·삭제 팝업을 잠근다
                                    onLongPress = { if (captureIdx == null) onMessageLongPress(it) },
                                )
                            }
                        }
                    }
                }
            }

            // 캡처 모드면 입력 영역 자리를 캡처 바가 대신한다 (모바일과 같은 규칙)
            if (captureIdx != null) {
                val picked = messages.subList(
                    captureIdx.first.coerceIn(0, messages.size),
                    (captureIdx.last + 1).coerceIn(0, messages.size),
                )
                CaptureBar(
                    count = picked.size,
                    timeRange = if (captureEndPicked) timeRangeLabel(picked) else null,
                    startLabel = picked.firstOrNull()?.let {
                        "시작 " + formatTime(it.createdAt) + " · " + it.senderName
                    },
                    overLimit = picked.size > CAPTURE_MAX,
                    rendering = captureRendering,
                    error = captureError,
                    onMake = onCaptureMake,
                    onCancel = onCaptureExit,
                    withBackground = captureWithBackground,
                    onToggleBackground = onToggleCaptureBackground,
                    excludeOoc = captureExcludeOoc,
                    onToggleExcludeOoc = onToggleCaptureExcludeOoc,
                )
            } else
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
                onShowMarkupHelp = onShowMarkupHelp,
                onTyping = onTyping,
                onTypingStopped = onTypingStopped,
                onJudgeRequest = onJudgeRequest,
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
    firestore: FirestoreRest?,
    grouped: Boolean = false,
    /** false면 시간을 감춘다 — 같은 사람이 같은 분에 이어 보낸 중간 메시지 */
    showTime: Boolean = true,
    /** 캡처 모드의 선택 상태. NONE이면 평상시·캡처 이미지와 완전히 같다 */
    mark: CaptureMark = CaptureMark.NONE,
    /** 캡처 모드에서 행 전체를 클릭했을 때 */
    onTap: (() -> Unit)? = null,
    /** 판정 요청 상태 — 화면에서 한 번 계산해 내려보낸다 (J5) */
    judgeState: JudgeState = JudgeState.Waiting,
    onJudgeTap: () -> Unit = {},
    onLongPress: (Message) -> Unit = {},
) {
    val mine = message.authorUid == myUid
    val radiusPx = with(LocalDensity.current) { DesktopDimens.rCell.toPx() }
    var wrapper = Modifier.fillMaxWidth().captureBand(mark, Tokens.Signature, radiusPx)
    if (onTap != null) wrapper = wrapper.clickable(onClick = onTap)
    if (mark != CaptureMark.NONE) wrapper = wrapper.padding(vertical = DesktopDimens.gap2)
    if (mark == CaptureMark.OUT) wrapper = wrapper.alpha(.32f)
    Box(wrapper) {
    when {
        // 판정 요청 — 대상 캐릭터를 가진 쪽만 누를 수 있다 (J5)
        message.type == Protocol.MessageType.JUDGE -> JudgeCard(message, judgeState, onJudgeTap)
        message.type == Protocol.MessageType.SYSTEM -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(Tokens.Panel.copy(alpha = .75f))
                        .border(1.dp, Tokens.Line, RoundedCornerShape(999.dp))
                        .padding(horizontal = DesktopDimens.gap3, vertical = DesktopDimens.gap1)
                ) {
                    Text(message.body, fontSize = 10.sp, color = Tokens.InkDim)
                }
            }
        }
        message.type == Protocol.MessageType.DICE -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.clip(RoundedCornerShape(DesktopDimens.rCell)).background(Tokens.Panel.copy(alpha = .85f))
                        .border(1.dp, Tokens.GmRing, RoundedCornerShape(DesktopDimens.rCell))
                        .padding(horizontal = DesktopDimens.gap3, vertical = DesktopDimens.gap2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🎲", fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${message.diceExpr} → ${message.body}",
                        fontSize = 11.sp, color = Tokens.DiceInk, fontWeight = FontWeight.Bold,
                    )
                    // 판정 등급 — 성공 계열 파랑, 실패 빨강 (모바일과 동일 표기)
                    Rules.outcomeLabel(message.diceOutcome)?.let { label ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            // 성공 파랑은 모바일 statBlue와 같은 값 (플랫폼 드리프트 해소)
                            color = if (Rules.isSuccess(message.diceOutcome)) Tokens.StatBlue
                            else Tokens.Danger,
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
                            onLongClick = { onLongPress(message) }, // 복사는 상대 메시지에서도
                        )
                        .padding(horizontal = DesktopDimens.gap3, vertical = DesktopDimens.gap1)
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
            // 시각은 마지막 인용에만 — 인용이 여럿이면 중복 표시된다 (모바일 R4와 동일)
            val lastQuote = remember(parts) { parts.indexOfLast { it is GmSpeech.Part.Quote } }
            Column(verticalArrangement = Arrangement.spacedBy(DesktopDimens.gap2)) {
                parts.forEachIndexed { index, part ->
                    when (part) {
                        is GmSpeech.Part.Narration -> NarrationBlock(
                            message, part.text,
                            onLongPress = { onLongPress(message) }, // 복사·캡처는 상대 서술에서도
                        )
                        is GmSpeech.Part.Quote -> BubbleRow(
                            message = message, myUid = myUid, room = room,
                            avatarCache = avatarCache, firestore = firestore,
                            overrideBody = part.text, overrideName = "GM",
                            overrideBubbleColor = Tokens.gmQuoteBubble,
                            showTime = showTime && index == lastQuote,
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
                    showTime = showTime,
                    onLongPress = onLongPress,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(DesktopDimens.gap2)) {
                    parts.forEachIndexed { index, part ->
                        BubbleRow(
                            message = message, myUid = myUid, room = room,
                            avatarCache = avatarCache, firestore = firestore,
                            overrideBody = part.text(),
                            quoteBubble = part is GmSpeech.Part.Quote,
                            showHeader = !grouped && index == 0,
                            showTime = showTime && index == parts.lastIndex,
                            onLongPress = onLongPress,
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * 날짜 구분선 — 방이 만들어진 날과, 날짜가 바뀐 뒤 첫 메시지 위에 붙는다 (모바일과 같은 규격).
 */
@Composable
internal fun DayDivider(millis: Long) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = DesktopDimens.gap3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ChatDates.label(millis),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Tokens.OnScrim,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Tokens.PillScrim)
                .padding(horizontal = DesktopDimens.gap3, vertical = DesktopDimens.gap1),
        )
    }
}

/** 판정 요청 카드의 상태 — 모바일 JudgeState와 같은 규칙 (J5) */
internal enum class JudgeState { MyTurn, Waiting, Done }

/**
 * "가류 세이시로, **LUK** 판정" — 값 이름만 파랑으로 (모바일과 같은 규칙).
 * body를 자르지 않고 구조 필드로 다시 조립한다 — 대상 이름에 쉼표가 있으면
 * 문자열 자르기는 엉뚱한 곳에서 끊긴다. 필드가 없는 구버전 메시지는 body 그대로.
 */
private fun judgeLabel(message: Message): AnnotatedString {
    val target = message.judgeTarget
    val stat = message.diceExpr?.let { ProfileStats.statNameOf(it) }
    if (target == null || stat == null) return AnnotatedString(message.body)
    return buildAnnotatedString {
        append("$target, ")
        withStyle(SpanStyle(color = Tokens.StatBlue)) { append(stat) }
        append(" 판정")
    }
}

/** 판정 요청 카드 — 세 상태의 크기가 같아야 목록이 흔들리지 않는다 (모바일과 같은 규격) */
@Composable
private fun JudgeCard(message: Message, state: JudgeState, onTap: () -> Unit) {
    val border = if (state == JudgeState.MyTurn) 2.dp else 1.dp
    val shape = RoundedCornerShape(DesktopDimens.rCard)
    var box = Modifier
        .clip(shape)
        .background(Tokens.Panel)
        .border(border, if (state == JudgeState.MyTurn) Tokens.Signature else Tokens.Line, shape)
    if (state == JudgeState.MyTurn) box = box.clickable(onClick = onTap)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            box.padding(
                horizontal = DesktopDimens.gap3 - border + 1.dp,
                vertical = DesktopDimens.gap3 - border + 1.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 상태는 오른쪽 버튼 하나로 충분하다 — 문구로 한 번 더 말하지 않는다
            Text(
                judgeLabel(message),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.Ink,
                modifier = Modifier.alpha(if (state == JudgeState.Done) .55f else 1f),
            )
            Spacer(Modifier.width(DesktopDimens.gap3))
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (state == JudgeState.MyTurn) Tokens.Signature
                        else Tokens.Ink.copy(alpha = .08f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (state) {
                        JudgeState.MyTurn -> "▶"
                        JudgeState.Waiting -> "⋯"
                        JudgeState.Done -> "✓"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = if (state == JudgeState.MyTurn) Tokens.OnSignature else Tokens.InkDim,
                )
            }
        }
    }
}

/** 캡처 범위 선택 표시 — 모바일 CaptureMark와 같은 규칙 (목업 mockup-capture 03장) */
internal enum class CaptureMark { NONE, OUT, IN, START, END, ONLY }

/**
 * 밴드(선택 구간) 배경·테두리. 열린 쪽의 둥근 모서리는 캔버스 밖으로 밀어 잘리게 하는
 * 방식이라, 인접한 밴드가 맞닿아도 가로선이 생기지 않는다 (모바일과 같은 구현).
 */
internal fun Modifier.captureBand(mark: CaptureMark, accent: Color, radiusPx: Float): Modifier =
    drawBehind {
        if (mark == CaptureMark.NONE || mark == CaptureMark.OUT) return@drawBehind
        val stroke = 2.dp.toPx()
        val over = radiusPx + stroke
        val top = if (mark == CaptureMark.START || mark == CaptureMark.ONLY) 0f else -over
        val bottom =
            if (mark == CaptureMark.END || mark == CaptureMark.ONLY) size.height else size.height + over
        val inset = stroke / 2
        val corner = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx)
        // drawBehind는 노드 경계로 잘라 주지 않는다 — 늘린 부분이 이웃 항목 위에
        // 그대로 그려지면 메시지마다 알약이 따로 있는 것처럼 보인다
        clipRect {
            drawRoundRect(
                color = accent.copy(alpha = .26f),
                topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                size = androidx.compose.ui.geometry.Size(size.width, bottom - top),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = accent,
                topLeft = androidx.compose.ui.geometry.Offset(inset, top + inset),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, bottom - top - stroke),
                cornerRadius = corner,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
            )
        }
    }

/** 선택 구간의 표시 상태 — 모바일 captureMarkOf와 같은 판정 */
internal fun captureMarkOf(range: IntRange?, index: Int): CaptureMark = when {
    range == null -> CaptureMark.NONE
    index !in range -> CaptureMark.OUT
    range.first == range.last -> CaptureMark.ONLY
    index == range.first -> CaptureMark.START
    index == range.last -> CaptureMark.END
    else -> CaptureMark.IN
}

/**
 * 같은 사람이 같은 분(分)에 이어 보냈는가 — 앞 메시지의 시간을 생략하고 마지막에만 남긴다
 * (모바일과 동일 규칙). 다른 인물이면 분이 같아도 각자 표기한다.
 */
internal fun sharesTimeLabel(current: Message, next: Message?): Boolean {
    if (next == null) return false
    if (current.senderName != next.senderName || current.authorUid != next.authorUid) return false
    return formatTime(current.createdAt) == formatTime(next.createdAt)
}

/** 같은 인물의 연속 말풍선인지 — 아바타·이름 생략과 간격 축소 판정 (모바일과 동일 규칙) */
internal fun isContinuation(prev: Message?, current: Message): Boolean {
    if (prev == null) return false
    fun isBubble(m: Message) = m.type == Protocol.MessageType.TEXT && !m.senderIsGm && !m.isOoc
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
            fontFamily = GowunBatang, lineHeight = 24.sp,
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
    firestore: FirestoreRest?,
    overrideBody: String? = null,
    overrideName: String? = null,
    overrideBubbleColor: Long? = null,
    /** 이 조각이 대사(인용)임을 호출부가 이미 판정한 경우 */
    quoteBubble: Boolean = false,
    showHeader: Boolean = true, // false = 연속 메시지 (아바타·이름 생략)
    /**
     * 시각 표시 여부. **기본값을 두지 않는다** — 전달을 잊어 시간 접기가 사문화된 일이
     * 세 번 반복돼(읽음 리뷰 R2 → GM 분기만 → 일반 말풍선 누락), 컴파일러가 잡게 했다.
     */
    showTime: Boolean,
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
    // 말풍선 글씨색은 발신 시점 스냅샷 (모바일과 동일)
    val inkColor = when {
        message.isOoc -> Tokens.ChatterInk
        message.senderTextColor != null -> Color(message.senderTextColor)
        else -> Tokens.BubbleInk
    }

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
                                onLongClick = { onLongPress(message) }, // 복사는 상대 메시지에서도
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
                            Modifier.align(Alignment.BottomEnd).padding(end = DesktopDimens.gap2).offset(y = DesktopDimens.gap2),
                        )
                        MarkupText(
                            text = quoteInner, fontSize = 13.sp, color = inkColor,
                            lineHeight = 20.sp,
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
                                onLongClick = { onLongPress(message) }, // 복사는 상대 메시지에서도
                            )
                            .padding(horizontal = DesktopDimens.gap3, vertical = DesktopDimens.gap2)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            // 간격은 부모가 — 배지에 top만 주면 위아래가 어긋난다 (P2).
                            // 값도 모바일과 같은 gap1으로 통일
                            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.gap1),
                        ) {
                            if (message.isOoc) {
                                Text(
                                    "잡담", fontSize = 10.sp, color = inkColor,
                                    modifier = Modifier
                                        .border(1.dp, inkColor.copy(alpha = .4f), RoundedCornerShape(999.dp))
                                        .padding(horizontal = DesktopDimens.gap1),
                                )
                            }
                            MarkupText(
                                text = body, fontSize = 13.sp, color = inkColor,
                                lineHeight = 20.sp,
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
            Text("(수정됨)", fontSize = 10.sp, color = Tokens.InkDim)
        }
        // 밝은 배경에서 원색 테마는 읽히지 않는다 — 모바일과 같은 보정 (P6)
        Text(
            formatTime(message.createdAt), fontSize = 10.sp,
            color = Color(Tokens.nameColorForLight(themeColor.toArgb().toLong() and 0xFFFFFFFFL)),
        )
    }
}

/**
 * 본문 전체가 쌍따옴표(" 또는 “ ”)로 감싸인 대사인지 — 감싸였으면 안쪽 내용을 돌려준다.
 * 모바일 ChatScreen의 quoteContent와 동일 규칙.
 */
internal fun quoteContent(body: String): String? = GmSpeech.quoteContent(body)

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
    firestore: FirestoreRest?,
) {
    val avatarId = message.avatarId
    // 이펙트는 항상 컴포지션에 있어야 한다 — 조건 안에 두면 캐시 쓰기가 리컴포지션을
    // 유발해 자기 자신을 취소시킨다(R1). 중복 fetch는 스냅샷이 아닌 별도 집합으로 막는다.
    LaunchedEffect(avatarId, room.remoteId) {
        if (avatarId == null) return@LaunchedEffect
        // 캡처 렌더는 firestore를 넘기지 않는다 — 그리는 중에 서버로 나가는 길 자체를
        // 없앤다(프리페치가 이미 캐시를 채워 둔다). 미스는 빈 원 (V7)
        val remote = firestore ?: return@LaunchedEffect
        if (avatarCache[avatarId] != null) return@LaunchedEffect
        if (!avatarsInFlight.add(avatarId)) return@LaunchedEffect
        try {
            val bitmap = withContext(Dispatchers.IO) {
                fetchAvatarCached(remote, room.remoteId, avatarId)?.let { bytes ->
                    runCatching {
                        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                    }.onFailure {
                        // 못 여는 캐시 파일은 지운다 — 안 그러면 같은 파일을 계속 다시 읽는다 (L2)
                        dropBrokenAvatarCache(avatarId)
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
            .border(1.dp, Tokens.Line, CircleShape)
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
/** 커서 이동용 방향키 — 입력창 밖으로 새어 나가면 포커스가 옮겨 간다 */
private val ARROW_KEYS = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
)

@Composable
internal fun InputZone(
    room: JoinedRoom,
    profiles: List<Profile>,
    theme: Color,
    onSend: (String, Boolean, (Boolean, Boolean) -> Unit) -> Unit,
    onSwitchProfile: (Int) -> Unit,
    onAddProfile: () -> Unit,
    onEditProfile: (Int) -> Unit,
    onShowMarkupHelp: () -> Unit,
    /** 실제로 글자가 바뀔 때만 — 가만히 있는 상태는 입력 중이 아니다 */
    onTyping: () -> Unit = {},
    onTypingStopped: () -> Unit = {},
    /** GM 프로필로 말하는 중에만 보이는 판정 요청 열기 (J8) */
    onJudgeRequest: () -> Unit = {},
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

    Column(Modifier.fillMaxWidth().background(Tokens.ChatBarBg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Line))
        // 본문과 같은 720dp 중앙 정렬 (PC 규격)
        Column(
            Modifier.align(Alignment.CenterHorizontally)
                .widthIn(max = DesktopDimens.contentMax).fillMaxWidth()
                .padding(horizontal = DesktopDimens.edge, vertical = DesktopDimens.gap3),
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
                            Modifier.size(DesktopDimens.avatarStrip)
                                .border(
                                    2.dp,
                                    when {
                                        on -> Tokens.Signature
                                        profile.isGm -> Tokens.GmRing // GM 금테
                                        else -> Tokens.Line
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
                            Modifier.size(DesktopDimens.avatarStrip)
                                .border(1.dp, Tokens.Line, CircleShape)
                                .clip(CircleShape)
                                .clickable(onClick = onAddProfile),
                            contentAlignment = Alignment.Center,
                        ) { Text("＋", color = Tokens.InkDim, fontSize = 15.sp) }
                        Text("추가", fontSize = 10.sp, color = Tokens.InkDim)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // room.isMaster가 아니라 **지금 말하고 있는 프로필** 기준 — GM이 자기 NPC로
            // 말하는 중에는 요청을 걸 수 없고, 그게 자연스럽다 (모바일과 동일)
            if (profiles.getOrNull(room.activeProfileIndex)?.isGm == true) {
                Text(
                    "＋ 판정 요청",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Tokens.SignatureInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Tokens.Signature.copy(alpha = .18f))
                        .border(1.dp, Tokens.GmRing, RoundedCornerShape(999.dp))
                        .clickable(onClick = onJudgeRequest)
                        .padding(horizontal = DesktopDimens.gap3, vertical = DesktopDimens.gap2),
                )
                Spacer(Modifier.height(8.dp))
            }
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
                                .background(Tokens.Signature.copy(alpha = .18f))
                                .border(1.dp, Tokens.GmRing, RoundedCornerShape(999.dp))
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
                        .background(if (oocOn) Tokens.Signature.copy(alpha = .28f) else Tokens.FieldBg)
                        .border(
                            1.dp,
                            if (oocOn) Tokens.GmRing else Tokens.Line,
                            RoundedCornerShape(999.dp),
                        )
                        .clickable { oocOn = !oocOn }
                        .padding(horizontal = DesktopDimens.gap3, vertical = DesktopDimens.gap2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "잡담", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = if (oocOn) Tokens.DiceInk else Tokens.InkDim,
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { text ->
                        val changed = text != input
                        input = text
                        if (changed) {
                            if (text.isBlank()) onTypingStopped() else onTyping()
                        }
                    },
                    modifier = Modifier.weight(1f)
                        // 창을 옮기면 치던 것을 멈춘 것으로 본다 (모바일과 동일, P4)
                        .onFocusChanged { if (!it.isFocused) onTypingStopped() }
                        // 입력창이 처리하지 않은 방향키를 삼킨다 — 그냥 두면 포커스가
                        // 말풍선·버튼으로 옮겨 가 커서가 입력창을 벗어난다 (모바일과 동일)
                        .onKeyEvent { event -> event.key in ARROW_KEYS }
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
                        .padding(horizontal = DesktopDimens.gap3, vertical = 11.dp),
                    // 줄 높이를 주지 않으면 여러 줄일 때 마지막 줄 내림선이 잘린다 (모바일과 같은 수정)
                    textStyle = TextStyle(
                        color = Tokens.Ink,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
                        ),
                    ),
                    cursorBrush = SolidColor(Tokens.SignatureRing),
                    maxLines = 4,
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                if (input.isEmpty()) {
                                    Text(
                                        if (oocOn) "잡담으로 보내기…" else "**굵게** · (루비)[문자] · 1d100",
                                        fontSize = 11.sp, color = Tokens.InkDim,
                                    )
                                }
                                inner()
                            }
                            // PC는 Ctrl+Enter 힌트를 상시 노출 (trpg-app-mockup-pc-light.html)
                            Text(
                                "Ctrl+Enter 전송", fontSize = 10.sp, color = Tokens.InkDisabled,
                                modifier = Modifier.padding(start = 8.dp)
                                    .border(1.dp, Tokens.Line, RoundedCornerShape(DesktopDimens.rTail))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                            // 입력창 오른쪽 끝 "?" — 지원 문법 도움말 (모바일과 동일)
                            Box(
                                Modifier.padding(start = 8.dp).size(20.dp)
                                    .clip(CircleShape).background(Tokens.InkFaint)
                                    .clickable(onClick = onShowMarkupHelp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "?", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    color = Tokens.InkSub,
                                )
                            }
                        }
                    },
                )
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (input.isNotBlank()) theme else theme.copy(alpha = .35f))
                        .clickable(enabled = input.isNotBlank()) { doSend() },
                    contentAlignment = Alignment.Center,
                ) { Text("➤", fontSize = 15.sp, color = Tokens.BubbleInk) }
            }
            errorMessage?.let { message ->
                Spacer(Modifier.height(DesktopDimens.gap1))
                Text(message, fontSize = 11.sp, color = Tokens.Danger)
            }
        }
    }
}

// ══════════════ 오버레이(다이얼로그) ══════════════

internal fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
