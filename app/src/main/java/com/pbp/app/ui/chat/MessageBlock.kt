package com.pbp.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.app.data.Message
import com.pbp.app.data.MessageType
import com.pbp.shared.ChatDates
import com.pbp.shared.GmSpeech
import com.pbp.shared.ProfileStats
import com.pbp.app.ui.common.Avatar
import com.pbp.app.ui.common.MarkupText
import com.pbp.app.ui.common.dashedBorder
import com.pbp.app.ui.common.formatTime
import com.pbp.app.ui.theme.GowunBatang
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import com.pbp.app.ui.theme.PbpPalette

/** 메시지 렌더링 — 시스템·다이스·잡담·GM 서술·말풍선 (ChatScreen에서 분리, 리뷰 B3) */

/** 같은 인물의 연속 말풍선인지 — 아바타·이름 생략과 간격 축소 판정 */
internal fun isContinuation(prev: Message?, current: Message): Boolean {
    if (prev == null) return false
    // 잡담은 말풍선이 아니라 중앙 안내로 렌더링되므로 그룹 대상이 아니다
    fun isBubble(m: Message) = m.type == MessageType.TEXT && !m.senderIsGm && !m.isOoc
    if (!isBubble(prev) || !isBubble(current)) return false
    return prev.senderName == current.senderName && prev.incoming == current.incoming
}

/**
 * 같은 사람이 **같은 분(分)에** 이어서 보냈는가 — 그렇다면 앞 메시지의 시간은 생략하고
 * 마지막 것에만 남긴다. 다른 인물이면 분이 같아도 각자 표기한다.
 */
internal fun sharesTimeLabel(current: Message, next: Message?): Boolean {
    if (next == null) return false
    if (current.senderName != next.senderName || current.incoming != next.incoming) return false
    return formatTime(current.createdAt) == formatTime(next.createdAt)
}

/**
 * "읽음" 배지를 붙일 메시지 id — 상대가 읽은 내 메시지 중 **말풍선이 있는** 가장 최신 1건.
 *
 * 다이스·잡담·시스템과 인용 없는 GM 서술은 중앙 정렬 블록이라 시간·배지를 그리는 자리가
 * 아예 없다. 그런 메시지를 고르면 배지가 어디에도 뜨지 않으므로 직전 말풍선으로 물러난다 (R3).
 */
internal fun readMarkTarget(messages: List<Message>, peerReadAt: Long?): Long? {
    if (peerReadAt == null) return null
    return messages.lastOrNull { it.createdAt <= peerReadAt && rendersBubble(it) }?.id
}

/** 이 메시지가 말풍선(=시간·읽음 배지를 담는 줄)으로 그려지는가 */
private fun rendersBubble(message: Message): Boolean {
    if (message.incoming || message.type != MessageType.TEXT || message.isOoc) return false
    if (!message.senderIsGm) return true
    // GM은 인용(대사)만 말풍선 — 서술 문단뿐이면 배지를 얹을 곳이 없다
    return GmSpeech.split(message.body).any { it is GmSpeech.Part.Quote }
}

/** 메시지 1건 렌더링 — GM 서술/인용 분리, 말풍선, 잡담, 다이스, 시스템 */
/**
 * 캡처 범위 선택 표시 (목업 final-design.html 03장).
 * 양 끝 '시작'·'끝' 배지는 마지막 메시지 본문을 가려 v0.7.3에서 뺐다 —
 * 밴드의 둥근 모서리만으로 시작·끝이 충분히 읽힌다.
 * 말풍선 내부는 건드리지 않고 **감싸는 상자에만** 얹으므로, [CaptureMark.NONE]으로
 * 부르면 화면과 캡처 이미지가 완전히 같아진다.
 */
internal enum class CaptureMark { NONE, OUT, IN, START, END, ONLY }

/**
 * 밴드(선택 구간) 배경·테두리. 열린 쪽(위/아래)의 둥근 모서리는 캔버스 밖으로 밀어내
 * 잘리게 하는 방식이라, 인접한 밴드가 맞닿아도 가로선이 생기지 않는다.
 */
private fun Modifier.captureBand(mark: CaptureMark, accent: Color, radiusPx: Float): Modifier =
    drawBehind {
        if (mark == CaptureMark.NONE || mark == CaptureMark.OUT) return@drawBehind
        val stroke = 2.dp.toPx()
        val over = radiusPx + stroke // 열린 쪽을 이만큼 밖으로 빼면 모서리가 잘려 직선이 된다
        val top = if (mark == CaptureMark.START || mark == CaptureMark.ONLY) 0f else -over
        val bottom = if (mark == CaptureMark.END || mark == CaptureMark.ONLY) size.height else size.height + over
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

@Composable
internal fun MessageBlock(
    message: Message,
    grouped: Boolean = false,
    /** false면 시간을 감춘다 — 같은 사람이 같은 분에 이어 보낸 중간 메시지 */
    showTime: Boolean = true,
    /** 상대가 여기까지 읽었음 — 내 메시지 중 가장 최신 1건에만 붙는다 */
    showRead: Boolean = false,
    themeColor: Color,
    /**
     * 조각별 선택 상태. 한 메시지가 서술·대사로 갈라지면 조각마다 따로 온다 —
     * 갈라지지 않는 메시지는 0번 하나뿐이다. 전부 NONE이면 평상시와 완전히 같다
     */
    markOf: (Int) -> CaptureMark = { CaptureMark.NONE },
    /** 캡처 모드에서 그 조각을 탭했을 때. 평상시에는 null이라 clickable을 붙이지 않는다 */
    onPartTap: ((Int) -> Unit)? = null,
    /** 판정 요청의 상태 — 화면에서 한 번 계산해 내려보낸다 (J5) */
    judgeState: JudgeState = JudgeState.Waiting,
    onJudgeTap: () -> Unit = {},
    onLongPress: (Message) -> Unit,
) {
    val tokens = Pbp.colors
    val radiusPx = with(LocalDensity.current) { PbpDimens.rCell.toPx() }
    // 캡처 모드에서는 말풍선이 탭을 삼키면 안 된다 — 행 전체를 탭해 범위를 고르는데
    // 자식 clickable이 먼저 먹어 빈 여백에서만 반응했다 (A1)
    val capturing = onPartTap != null
    val bodyTap: Modifier = if (capturing) Modifier else Modifier.combinedClickable(
        onClick = {},
        onLongClick = { onLongPress(message) }, // 복사는 상대 메시지에서도
    )

    /** 조각 하나를 캡처 밴드로 감싼다 — 조각마다 따로 골라야 하므로 부품으로 뽑았다 */
    @Composable
    fun Piece(index: Int, content: @Composable () -> Unit) {
        val mark = markOf(index)
        var wrapper = Modifier
            .fillMaxWidth()
            .captureBand(mark, tokens.signature, radiusPx)
        onPartTap?.let { tap -> wrapper = wrapper.clickable { tap(index) } }
        if (mark != CaptureMark.NONE) wrapper = wrapper.padding(vertical = PbpDimens.gap2)
        if (mark == CaptureMark.OUT) wrapper = wrapper.alpha(.32f)
        Box(wrapper) { content() }
    }

    /** 이웃한 두 조각이 모두 범위 안이면 밴드가 맞닿아야 한다 — 그 사이 간격은 0 */
    fun joins(index: Int): Boolean {
        val a = markOf(index - 1)
        val b = markOf(index)
        fun inRange(m: CaptureMark) = m != CaptureMark.NONE && m != CaptureMark.OUT
        return inRange(a) && inRange(b)
    }

    when {
        message.type == MessageType.SYSTEM -> Piece(0) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(color = tokens.scrim, shape = RoundedCornerShape(999.dp)) {
                    Text(
                        message.body,
                        fontSize = 10.sp,
                        color = tokens.onScrim,
                        modifier = Modifier.padding(
                            horizontal = PbpDimens.gap3,
                            vertical = PbpDimens.gap1,
                        ),
                    )
                }
            }
        }
        // 잡담은 극 밖의 대화 — 시스템 안내처럼 화면 중앙에 작게 '이름 : 내용'.
        // 배경은 그 캐릭터의 말풍선 색을 반투명으로 깔아 누가 말했는지 색으로도 구분한다
        message.isOoc -> Piece(0) {
            val chatterColor = Color(
                message.senderBubbleColor ?: PbpPalette.bubblePresets.first()
            ).copy(alpha = .55f)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    color = chatterColor,
                    shape = RoundedCornerShape(999.dp),
                    modifier = bodyTap,
                ) {
                    Text(
                        "${message.senderName ?: ""} : ${message.body}",
                        fontSize = 10.sp,
                        color = tokens.bubbleInk.copy(alpha = .85f),
                        modifier = Modifier.padding(
                            horizontal = PbpDimens.gap3,
                            vertical = PbpDimens.gap1,
                        ),
                    )
                }
            }
        }
        // 판정 요청 — 대상자만 누를 수 있고, 결과가 있으면 완료 (J5).
        // 캡처 중에는 누를 수 없다 — 범위를 고르려던 탭에 주사위가 굴러갔다 (A1)
        message.type == MessageType.JUDGE -> Piece(0) {
            JudgeCard(message, judgeState, onJudgeTap, tappable = !capturing)
        }
        message.type == MessageType.DICE -> Piece(0) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(PbpDimens.rCell))
                        .background(tokens.scrim)
                        .border(1.dp, tokens.signature.copy(alpha = .35f), RoundedCornerShape(PbpDimens.rCell))
                        .padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🎲", fontSize = 13.sp)
                    Spacer(Modifier.width(PbpDimens.gap2))
                    Text(
                        "${message.diceExpr} → ${message.body}",
                        fontSize = 11.sp,
                        color = tokens.signature,
                        fontWeight = FontWeight.Bold,
                    )
                    // 비교식 판정: 성공 계열 = 파랑, 실패 = 빨강
                    // (CoC7 하향 판정은 대성공·대단한 성공·어려운 성공 단계까지)
                    com.pbp.shared.Rules.outcomeLabel(message.diceOutcome)?.let { label ->
                        val success = com.pbp.shared.Rules.isSuccess(message.diceOutcome)
                        Spacer(Modifier.width(PbpDimens.gap2))
                        Text(
                            label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (success) tokens.statBlue else tokens.danger,
                        )
                    }
                }
            }
        }
        // 서술자(GM) 발화: 명조 서술 문단 + " " 인용만 말풍선 분리 (스펙 4장)
        message.senderIsGm && !message.isOoc -> {
            // 정규식 분해를 리컴포지션마다 반복하지 않는다 (F2)
            val parts = remember(message.body) { GmSpeech.split(message.body) }
            // 배지·시간은 마지막 인용 말풍선에만 — 인용이 여럿이면 중복으로 붙는다 (R4)
            val lastQuote = remember(parts) { parts.indexOfLast { it is GmSpeech.Part.Quote } }
            Column {
                parts.forEachIndexed { index, part ->
                    // 밴드가 맞닿아야 하는 구간에서는 간격을 0으로 (밴드 자체가 여백을 갖는다)
                    if (index > 0) {
                        Spacer(Modifier.height(if (joins(index)) 0.dp else PbpDimens.gap2))
                    }
                    Piece(index) {
                        when (part) {
                            is GmSpeech.Part.Narration ->
                                NarrationBlock(message, part.text, bodyTap, onLongPress)
                            is GmSpeech.Part.Quote -> BubbleRow(
                                message = message,
                                showTime = showTime && index == lastQuote,
                                showRead = showRead && index == lastQuote,
                                overrideBody = part.text,
                                overrideName = "GM",
                                overrideBubbleColor = PbpPalette.gmQuoteBubble,
                                themeColor = themeColor,
                                bodyTap = bodyTap,
                                onLongPress = onLongPress,
                            )
                        }
                    }
                }
            }
        }
        else -> {
            // 캐릭터 발화도 GM과 같은 규칙: 문장 중간의 " " 대사만 인용 말풍선으로 분리한다.
            // 대사가 없거나 본문 전체가 대사면 말풍선 하나로 그대로 둔다. (F2: remember)
            val parts = remember(message.body) { GmSpeech.split(message.body) }
            if (parts.size <= 1) {
                Piece(0) {
                    BubbleRow(
                        message = message, showHeader = !grouped, showTime = showTime,
                        showRead = showRead, themeColor = themeColor, bodyTap = bodyTap,
                        onLongPress = onLongPress,
                    )
                }
            } else {
                Column {
                    parts.forEachIndexed { index, part ->
                        if (index > 0) {
                            Spacer(Modifier.height(if (joins(index)) 0.dp else PbpDimens.gap2))
                        }
                        Piece(index) {
                        BubbleRow(
                            message = message,
                            overrideBody = when (part) {
                                is GmSpeech.Part.Narration -> part.text
                                is GmSpeech.Part.Quote -> part.text
                            },
                            quoteBubble = part is GmSpeech.Part.Quote,
                            showHeader = !grouped && index == 0,
                            showTime = showTime && index == parts.lastIndex,
                            showRead = showRead && index == parts.lastIndex,
                            themeColor = themeColor,
                            bodyTap = bodyTap,
                            onLongPress = onLongPress,
                        )
                        }
                    }
                }
            }
        }
    }
}

/** GM 서술 문단 — 명조체 블록 (아바타·낙관 없이 문단만). 본인은 길게 눌러 편집·삭제 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NarrationBlock(
    message: Message,
    text: String,
    /** 본문 탭·길게 누르기. 캡처 모드에서는 빈 Modifier가 온다 (A1) */
    bodyTap: Modifier,
    onLongPress: (Message) -> Unit,
) {
    val tokens = Pbp.colors
    // 좌우 여백은 메시지 목록의 contentPadding(16dp)에 맡긴다 — 별도 들여쓰기 없음
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        topStart = PbpDimens.rTail,
                        topEnd = PbpDimens.rCard,
                        bottomEnd = PbpDimens.rCard,
                        bottomStart = PbpDimens.rTail,
                    )
                )
                .background(tokens.narrBg)
                .then(bodyTap)
                .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap3),
        ) {
            // 서술은 문단 자체가 화면이 되도록 — 서술자·시간 등 메타 표기는 두지 않는다
            MarkupText(
                text = text,
                fontSize = 13.sp,
                color = tokens.narrInk,
                fontFamily = GowunBatang,
                lineHeight = 24.sp,
            )
        }
    }
}

/** 카카오톡형 좌/우 말풍선. 내 발신(incoming=false)은 오른쪽 정렬, 길게 눌러 편집·삭제 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BubbleRow(
    message: Message,
    overrideBody: String? = null,
    overrideName: String? = null,
    overrideBubbleColor: Long? = null,
    /** 이 조각이 대사(인용)임을 호출부가 이미 판정한 경우 */
    quoteBubble: Boolean = false,
    showHeader: Boolean = true, // false = 연속 메시지 (아바타·이름 생략)
    showTime: Boolean = true, // 한 메시지가 여러 말풍선으로 나뉘면 마지막에만
    showRead: Boolean = false, // 상대가 여기까지 읽었음 (모바일↔모바일)
    themeColor: Color,
    /** 본문 탭·길게 누르기. 캡처 모드에서는 빈 Modifier가 온다 (A1) */
    bodyTap: Modifier,
    onLongPress: (Message) -> Unit,
) {
    val tokens = Pbp.colors
    // GM 인용은 극중 화자이므로 항상 상대 측(왼쪽)에 표시
    val mine = !message.incoming && overrideName == null
    val body = overrideBody ?: message.body
    // 대사는 인용 말풍선 — 명조 쌍따옴표를 인용구처럼 크게 (목업 final-design.html).
    // 조각으로 나뉜 경우는 호출부가 알려주고, 통짜 메시지는 본문 전체가 " "인지 본다.
    val quoteInner = when {
        message.isOoc -> null
        quoteBubble -> body
        overrideName == null -> quoteContent(body)
        else -> null
    }
    val bubbleColor = when {
        message.isOoc -> tokens.chatterBubble
        else -> Color(overrideBubbleColor ?: message.senderBubbleColor ?: PbpPalette.bubblePresets.first())
    }
    val nameColor = when {
        message.isOoc -> tokens.inkDim
        overrideName != null -> tokens.signature
        message.senderNameColor != null -> Color(message.senderNameColor)
        else -> tokens.ink
    }
    // 말풍선 글씨색은 발신 시점 스냅샷 — 프로필을 나중에 바꿔도 과거 로그는 그대로
    val inkColor = when {
        message.isOoc -> tokens.chatterInk
        message.senderTextColor != null -> Color(message.senderTextColor)
        else -> tokens.bubbleInk
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(PbpDimens.gap2)) {
            if (mine) {
                // 내 메시지: 시간은 말풍선 왼쪽
                // 시간이 접힌 줄이어도 배지는 남긴다 — 상대가 연속 발화의 중간까지만
                // 읽었을 때 표시가 통째로 사라지지 않도록 (R3)
                if (showTime || showRead) {
                    TimeStamp(
                        message, themeColor, alignEnd = true,
                        showTime = showTime, showRead = showRead,
                        modifier = Modifier.align(Alignment.Bottom),
                    )
                }
            }
            if (!mine) {
                if (showHeader) {
                    Avatar(
                        emoji = message.senderEmoji,
                        imagePath = message.senderImagePath,
                        size = PbpDimens.avatarChat,
                        dimmed = message.isOoc,
                    )
                } else {
                    Box(Modifier.size(PbpDimens.avatarChat)) // 연속 메시지 — 자리만 유지
                }
            }
            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                if (showHeader) {
                    Text(
                        overrideName ?: message.senderName ?: "",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = nameColor,
                    )
                    Spacer(Modifier.height(PbpDimens.gap1))
                }
                val r = PbpDimens.rCard
                val shape = if (mine) {
                    RoundedCornerShape(topStart = r, topEnd = PbpDimens.rTail, bottomEnd = r, bottomStart = r)
                } else {
                    RoundedCornerShape(topStart = PbpDimens.rTail, topEnd = r, bottomEnd = r, bottomStart = r)
                }
                val bubbleBase = Modifier
                    .widthIn(max = PbpDimens.bubbleMaxWidth)
                    .clip(shape)
                    .background(bubbleColor)
                    .then(
                        if (message.isOoc) {
                            Modifier.dashedBorder(tokens.line, PbpDimens.rCard)
                        } else Modifier
                    )
                    .then(bodyTap)
                if (quoteInner != null) {
                    // 여는 “ 좌상단 · 닫는 ” 우하단 — 좌우 여백은 9dp로 같다.
                    // 위아래 값(5dp / +6dp)이 다른 것은 글리프 잉크가 글자 상자 위쪽에
                    // 몰려 있어서다 — 눈으로 보이는 여백을 맞추기 위한 보정.
                    Box(bubbleBase) {
                        QuoteMark(
                            "“", inkColor,
                            Modifier.align(Alignment.TopStart).padding(start = 9.dp, top = 5.dp),
                        )
                        QuoteMark(
                            "”", inkColor,
                            Modifier.align(Alignment.BottomEnd).padding(end = 9.dp).offset(y = 6.dp),
                        )
                        MarkupText(
                            text = quoteInner,
                            fontSize = 13.sp,
                            color = inkColor,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp),
                        )
                    }
                } else {
                    Box(bubbleBase.padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            // 배지 쪽에 한쪽 패딩을 주지 않는다 — 간격은 부모가 (P2)
                            horizontalArrangement = Arrangement.spacedBy(PbpDimens.gap1),
                        ) {
                            if (message.isOoc) {
                                Text(
                                    "잡담",
                                    fontSize = 10.sp,
                                    color = inkColor,
                                    modifier = Modifier
                                        .border(1.dp, inkColor.copy(alpha = .4f), RoundedCornerShape(999.dp))
                                        .padding(horizontal = PbpDimens.gap1),
                                )
                            }
                            MarkupText(
                                text = body,
                                fontSize = 13.sp,
                                color = inkColor,
                                    lineHeight = 20.sp,
                                fontWeight = if (message.isOoc) FontWeight.Normal else FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            if (!mine) {
                // 남(또는 GM 인용) 메시지: 시간은 말풍선 오른쪽
                if (showTime) {
                    TimeStamp(message, themeColor, alignEnd = false, Modifier.align(Alignment.Bottom))
                }
            }
            if (mine) {
                if (showHeader) {
                    Avatar(
                        emoji = message.senderEmoji,
                        imagePath = message.senderImagePath,
                        size = PbpDimens.avatarChat,
                        dimmed = message.isOoc,
                    )
                } else {
                    Box(Modifier.size(PbpDimens.avatarChat)) // 연속 메시지 — 자리만 유지
                }
            }
        }
    }
}

/**
 * 본문 전체가 쌍따옴표(" 또는 “ ”)로 감싸인 대사인지 — 감싸였으면 안쪽 내용을 돌려준다.
 * 인용 말풍선 판정에 사용한다. 따옴표는 장식으로 다시 그려지므로 본문에서 벗긴다.
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

/** 말풍선 곁 시간 + (수정됨) — 내 메시지는 왼쪽, 남의 메시지는 오른쪽. 시간 색 = 방 테마 컬러 */
@Composable
internal fun TimeStamp(
    message: Message,
    themeColor: Color,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
    /** false면 시각·수정됨을 감춘다 (동일 시각 접힘) — 읽음 배지는 별개다 */
    showTime: Boolean = true,
    /** 상대(모바일)가 읽었음 — 시간 위에 작게 */
    showRead: Boolean = false,
) {
    val tokens = Pbp.colors
    // 라이트 모드에선 밝은 테마색이 밝은 베일 위에서 읽히지 않는다 —
    // 이름색과 같은 보정 경로를 태워 진하게 (스펙 2장)
    val timeColor = if (tokens.isDark) themeColor else {
        val argb = themeColor.toArgb().toLong() and 0xFFFFFFFFL
        Color(PbpPalette.nameColorForLight(argb))
    }
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        // 순서: (수정됨) → 읽음 → 시간. 읽음은 **시간 바로 위**여야 한 덩어리로 읽힌다.
        // 시간이 접힌 줄에서는 읽음이 그대로 시간 자리를 차지한다
        if (showTime && message.editedAt != null) {
            Text("(수정됨)", fontSize = 10.sp, color = tokens.inkDim)
        }
        if (showRead) {
            Text("읽음", fontSize = 10.sp, color = tokens.inkDim)
        }
        if (showTime) {
            Text(formatTime(message.createdAt), fontSize = 10.sp, color = timeColor)
        }
    }
}

/**
 * 날짜 구분선 — 방이 만들어진 날과, 날짜가 바뀐 뒤 첫 메시지 위에 붙는다.
 * 시스템 안내와 같은 캡슐 규격이되 글씨가 더 또렷하다: 안내가 아니라 눈금이다.
 */
@Composable
internal fun DayDivider(millis: Long) {
    val tokens = Pbp.colors
    Box(
        Modifier.fillMaxWidth().padding(vertical = PbpDimens.gap3),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = tokens.scrim, shape = RoundedCornerShape(999.dp)) {
            Text(
                ChatDates.label(millis),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = tokens.onScrim,
                modifier = Modifier.padding(
                    horizontal = PbpDimens.gap3,
                    vertical = PbpDimens.gap1,
                ),
            )
        }
    }
}

/** 판정 요청 카드의 상태 (J5) */
internal enum class JudgeState {
    /** 이 캐릭터를 가진 사람 — 눌러서 굴린다 */
    MyTurn,

    /** 남의 차례 — 값도 모르고 누를 수도 없다 */
    Waiting,

    /** 이미 굴렸다 */
    Done,
}

/**
 * "가류 세이시로, **LUK** 판정" — 값 이름만 파랑으로. 어떤 판정인지 한눈에 들어와야 한다.
 *
 * body를 자르지 않고 구조 필드(judgeTarget·diceExpr)로 다시 조립한다 — 대상 이름에
 * 쉼표가 들어 있으면 문자열 자르기는 엉뚱한 곳에서 끊긴다. 둘 중 하나라도 없는
 * 구버전 메시지는 body를 그대로 쓴다.
 */
private fun judgeLabel(message: Message, statColor: Color): AnnotatedString {
    val target = message.judgeTarget
    val stat = message.diceExpr?.let { ProfileStats.statNameOf(it) }
    if (target == null || stat == null) return AnnotatedString(message.body)
    return buildAnnotatedString {
        append("$target, ")
        withStyle(SpanStyle(color = statColor)) { append(stat) }
        append(" 판정")
    }
}

/**
 * 판정 요청 카드 — 세 상태의 **크기가 같아야** 목록이 흔들리지 않는다.
 * 테두리가 2dp인 MyTurn은 패딩에서 1dp를 빼 높이를 맞춘다 (목업 02장).
 */
@Composable
private fun JudgeCard(
    message: Message,
    state: JudgeState,
    onTap: () -> Unit,
    /** 캡처 모드에서는 false — 범위를 고르는 탭이 굴림으로 새면 안 된다 (A1) */
    tappable: Boolean,
) {
    val tokens = Pbp.colors
    val border = if (state == JudgeState.MyTurn) 2.dp else 1.dp
    val shape = RoundedCornerShape(PbpDimens.rCard)
    var box = Modifier
        .clip(shape)
        .background(tokens.panel)
        .border(
            border,
            if (state == JudgeState.MyTurn) tokens.signature else tokens.line,
            shape,
        )
    // 누를 수 없는 상태에 리플이 생기면 눌러도 되는 것처럼 보인다
    if (state == JudgeState.MyTurn && tappable) box = box.clickable(onClick = onTap)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            box.padding(
                horizontal = PbpDimens.gap3 - border + 1.dp,
                vertical = PbpDimens.gap3 - border + 1.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 상태는 오른쪽 버튼 하나로 충분하다 — 문구로 한 번 더 말하지 않는다
            Text(
                judgeLabel(message, tokens.statBlue),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = tokens.ink,
                modifier = Modifier.alpha(if (state == JudgeState.Done) .55f else 1f),
            )
            Spacer(Modifier.width(PbpDimens.gap3))
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (state == JudgeState.MyTurn) tokens.signature
                        else tokens.ink.copy(alpha = .08f)
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
                    color = if (state == JudgeState.MyTurn) tokens.onSignature else tokens.inkDim,
                )
            }
        }
    }
}

/**
 * 캡처 선택 단위 (사용자 요청) — 한 메시지가 서술·대사로 갈라져 여러 말풍선으로
 * 그려지면 **그 조각 하나하나**가 선택 단위다.
 *
 * 예전에는 메시지 1건이 단위라, GM 서술 + 대사처럼 갈라진 메시지는 통째로만 담겼다.
 * 대사 한 줄만 캡처하고 싶어도 서술이 따라왔다.
 */
internal data class CapturePiece(val messageIndex: Int, val partIndex: Int)

/** 이 메시지가 화면에서 몇 조각으로 그려지는가 — 캡처 단위 수와 같아야 한다 */
internal fun renderedPartCount(message: Message): Int {
    if (message.type != MessageType.TEXT || message.isOoc) return 1
    return GmSpeech.split(message.body).size.coerceAtLeast(1)
}

/** 메시지 목록을 화면에 그려지는 순서 그대로 조각 목록으로 편다 */
internal fun capturePiecesOf(messages: List<Message>): List<CapturePiece> =
    messages.flatMapIndexed { messageIndex, message ->
        List(renderedPartCount(message)) { CapturePiece(messageIndex, it) }
    }

/** 조각을 다시 본문으로 — 대사는 따옴표를 되살려야 같은 모양으로 다시 갈라진다 */
private fun GmSpeech.Part.toBody(): String = when (this) {
    is GmSpeech.Part.Narration -> text
    is GmSpeech.Part.Quote -> "\"$text\""
}

/**
 * 고른 조각 구간을 **그릴 메시지 목록**으로 바꾼다.
 *
 * 일부 조각만 고른 메시지는 그 조각들만 남긴 본문으로 복제한다 — 이미지 렌더러는
 * 화면과 같은 [MessageBlock]을 쓰므로, 본문만 줄이면 그 조각들만 그려진다.
 */
internal fun messagesForPieces(messages: List<Message>, pieces: List<CapturePiece>): List<Message> =
    pieces.groupBy { it.messageIndex }.toSortedMap().map { (messageIndex, group) ->
        val message = messages[messageIndex]
        val total = renderedPartCount(message)
        if (group.size == total) return@map message
        val parts = GmSpeech.split(message.body)
        val picked = group.map { it.partIndex }.sorted().mapNotNull { parts.getOrNull(it) }
        message.copy(body = picked.joinToString(" ") { it.toBody() })
    }
