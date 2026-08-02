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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.app.data.CharacterProfile
import com.pbp.app.ui.common.Avatar
import com.pbp.app.ui.common.dashedBorder
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens

/** 입력 영역 — 프로필 스트립·판정 팔레트·잡담 토글·입력줄 (리뷰 B3) */

/** 입력 중 표시 줄 높이 — 문구가 있든 없든 같아야 입력 영역이 위아래로 튀지 않는다 */
private val TYPING_ROW_HEIGHT = 14.dp

/** 커서 이동용 방향키 — 입력창 안에서만 쓰이고 포커스 이동으로 새어 나가면 안 된다 */
private val ARROW_KEYS = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InputZone(
    profiles: List<CharacterProfile>,
    activeId: Long?,
    themeColor: Color,
    onSwitch: (CharacterProfile) -> Unit,
    onEditProfile: (CharacterProfile) -> Unit,
    onAddProfile: () -> Unit,
    onSend: (String, Boolean) -> Unit,
    rule: String,
    /** 상대 이름 — "○○님이 입력 중…". 만료 판정은 [TypingLine]이 스스로 한다 (E2) */
    typingName: String? = null,
    typingUntil: Long = 0L,
    /** GM 프로필로 말하는 중에만 보이는 판정 요청 (J2) */
    onJudgeRequest: () -> Unit = {},
    /** 실제로 글자가 바뀔 때만 */
    onTyping: () -> Unit = {},
    onTypingStopped: () -> Unit = {},
) {
    val tokens = Pbp.colors
    // 입력 상태는 여기(하위)에서만 — 키 입력마다 화면 전체가 리컴포즈되지 않도록.
    // rememberSaveable: 화면 회전에도 입력을 보존 (P2-4)
    var input by rememberSaveable { mutableStateOf("") }
    var oocOn by rememberSaveable { mutableStateOf(false) }
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    // 자동완성 채팅 팔레트 — 활성 캐릭터의 값 이름을 부분 입력하면 판정 매크로 추천
    val activeStats = remember(profiles, activeId) {
        profiles.find { it.id == activeId }
            ?.let { com.pbp.shared.ProfileStats.decode(it.stats) } ?: emptyList()
    }
    val suggestions = remember(input, activeStats) {
        com.pbp.shared.ProfileStats.paletteSuggestions(input, activeStats)
    }
    // room.isMaster가 아니라 **지금 말하고 있는 프로필** 기준 — GM이 자기 NPC로
    // 말하는 중에는 요청을 걸 수 없고, 그게 자연스럽다 (J2)
    val gmActive = remember(profiles, activeId) {
        profiles.find { it.id == activeId }?.isGm == true
    }
    val onOocToggle = { oocOn = !oocOn }
    val onInputChange = { text: String ->
        val changed = text != input
        input = text
        // 입력 이벤트가 실제로 있을 때만 알린다. 비우면 즉시 끈다 —
        // 포커스만 있거나 써 둔 글을 그대로 두는 상태는 입력 중이 아니다.
        if (changed) {
            if (text.isBlank()) onTypingStopped() else onTyping()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(tokens.chatBarBg)
            .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap3),
    ) {
        // 프로필 교체 스트립 — 활성 프로필은 옐로 링 (스펙 4장)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(PbpDimens.gap3),
            verticalAlignment = Alignment.Top,
        ) {
            items(profiles, key = { it.id }) { profile ->
                val on = profile.id == activeId
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.combinedClickable(
                        onClick = { onSwitch(profile) },
                        onLongClick = { onEditProfile(profile) },
                    ),
                ) {
                    Avatar(
                        emoji = profile.emoji,
                        imagePath = profile.imagePath,
                        size = PbpDimens.avatarStrip,
                        ringColor = if (on) tokens.signature else null,
                    )
                    Text(
                        profile.name,
                        fontSize = 10.sp,
                        color = if (on) tokens.signatureInk else tokens.inkDim,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(PbpDimens.avatarStrip)
                            .dashedBorder(tokens.line, PbpDimens.avatarStrip / 2)
                            .clip(CircleShape)
                            .clickable(onClick = onAddProfile),
                        contentAlignment = Alignment.Center,
                    ) { Text("＋", color = tokens.inkDim, fontSize = 15.sp) }
                    Text("추가", fontSize = 10.sp, color = tokens.inkDim)
                }
            }
        }
        TypingLine(typingName, typingUntil)
        Spacer(Modifier.height(PbpDimens.gap2))
        if (gmActive) {
            // 판정 팔레트 칩과 같은 캡슐·같은 자리 — 위 프로필 스트립의 점선 '＋ 추가'와
            // 구분되도록 아이콘만 두지 않고 문구를 붙인다 (J2)
            Box(
                Modifier.heightIn(min = PbpDimens.touchTarget),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "＋ 판정 요청",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.signatureInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(tokens.signature.copy(alpha = .14f))
                        .border(1.dp, tokens.signature.copy(alpha = .4f), RoundedCornerShape(999.dp))
                        .clickable(onClick = onJudgeRequest)
                        .padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2),
                )
            }
            Spacer(Modifier.height(PbpDimens.gap2))
        }
        if (suggestions.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(PbpDimens.gap2)) {
                items(suggestions, key = { it }) { name ->
                    // 칩 크기는 그대로 두고 히트박스만 터치 규격으로 (접근성)
                    Box(
                        Modifier.heightIn(min = PbpDimens.touchTarget),
                        contentAlignment = Alignment.Center,
                    ) {
                    Text(
                        "$name 판정",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        // 밝은 입력 바 위 옐로 '텍스트'는 signatureInk (스펙 0장)
                        color = tokens.signatureInk,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(tokens.signature.copy(alpha = .14f))
                            .border(1.dp, tokens.signature.copy(alpha = .4f), RoundedCornerShape(999.dp))
                            .clickable {
                                // 예: "1d100<={LUK} LUK 판정" — 무엇을 판정했는지 함께 남긴다
                                val command = com.pbp.shared.Rules.judgeCommand(rule, name)
                                onSend("$command $name 판정", false)
                                input = ""
                            }
                            .padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2),
                    )
                    }
                }
            }
            Spacer(Modifier.height(PbpDimens.gap2))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PbpDimens.gap2)) {
            // 잡담 토글 — 알약은 그대로 두고 히트박스만 터치 규격으로 (접근성)
            Box(
                Modifier.heightIn(min = PbpDimens.touchTarget),
                contentAlignment = Alignment.Center,
            ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (oocOn) tokens.signature.copy(alpha = .16f) else tokens.chatterBubble)
                    .border(
                        1.dp,
                        if (oocOn) tokens.signature.copy(alpha = .4f) else tokens.line,
                        RoundedCornerShape(999.dp),
                    )
                    .clickable(onClick = onOocToggle)
                    .padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(width = 22.dp, height = 12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (oocOn) tokens.signature else tokens.line),
                    contentAlignment = if (oocOn) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Box(
                        Modifier
                            .padding(2.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (oocOn) tokens.onSignature else tokens.panel)
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    "잡담",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (oocOn) tokens.signatureInk else tokens.inkDim,
                )
            }
            }
            val canSend = input.isNotBlank() && activeId != null
            val doSend = {
                if (canSend) {
                    onSend(input, oocOn)
                    input = ""
                }
            }
            TextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    // 입력창을 벗어나면 치던 것을 멈춘 것으로 본다
                    .onFocusChanged { if (!it.isFocused) onTypingStopped() }
                    // 입력창이 처리하지 않은 방향키를 여기서 삼킨다. 그냥 두면 컴포즈의
                    // 포커스 이동이 받아 커서가 말풍선·버튼으로 튀어 나간다
                    .onKeyEvent { event -> event.key in ARROW_KEYS }
                    // 물리 키보드에서 Ctrl+Enter로 바로 전송
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.Enter &&
                            event.isCtrlPressed &&
                            canSend
                        ) {
                            doSend()
                            true
                        } else false
                    },
                placeholder = {
                    Text(
                        if (oocOn) "잡담으로 보내기…" else "**굵게** · (루비)[문자] · 1d100",
                        fontSize = 13.sp, // 입력줄 플레이스홀더 = 본문과 같은 단(스케일)
                        color = tokens.inkDim,
                    )
                },
                // 입력창 오른쪽 끝 "?" — 지원 문법 도움말
                trailingIcon = {
                    Box(
                        Modifier
                            .size(PbpDimens.touchTarget)
                            .clip(CircleShape)
                            .background(tokens.chatterBubble)
                            .clickable { helpOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("?", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tokens.inkSub)
                    }
                },
                // textStyle을 주지 않으면 M3 기본 16sp로 새어, 치기 시작하는 순간
                // 플레이스홀더(13sp)에서 크기가 점프한다 (8장)
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                    fontSize = 13.sp,
                    color = tokens.ink,
                ),
                colors = TextFieldDefaults.colors(
                    // 라이트 입력바 배경이 거의 흰색이라 흰 반투명 컨테이너는 구분되지 않는다
                    focusedContainerColor = tokens.panel2,
                    unfocusedContainerColor = tokens.panel2,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    // 포커스 라벨이 M3 primary(옐로 원색)로 새는 것을 막는다 (P0 1-2)
                    focusedLabelColor = tokens.signatureInk,
                    unfocusedLabelColor = tokens.inkDim,
                    cursorColor = tokens.signatureInk,
                ),
                shape = RoundedCornerShape(PbpDimens.rCell),
                maxLines = 4,
            )
            // 전송 버튼 — 방 테마 컬러 적용 (스펙 5장)
            Box(
                Modifier
                    .size(PbpDimens.touchTarget)
                    .clip(RoundedCornerShape(PbpDimens.rCell))
                    .background(if (canSend) themeColor else themeColor.copy(alpha = .35f))
                    .clickable(enabled = canSend, onClick = doSend),
                contentAlignment = Alignment.Center,
            ) { Text("➤", fontSize = 15.sp, color = tokens.bubbleInk) }
        }
    }
    if (helpOpen) MarkupHelpDialog(onDismiss = { helpOpen = false })
}

/**
 * 입력 중 표시 (E2) — 자리를 늘 차지해 상대가 치기 시작해도 입력 영역이 튀지 않는다.
 *
 * 만료 확인용 0.5초 틱을 **여기서만** 돈다. 예전에는 채팅 화면 본체에 있어서
 * 상대가 타이핑하는 동안 초당 두 번 화면 전체가 리컴포즈됐다.
 */
@Composable
private fun TypingLine(typingName: String?, typingUntil: Long) {
    val tokens = Pbp.colors
    var now by remember { mutableStateOf(0L) }
    LaunchedEffect(typingUntil) {
        // 상대가 손을 멈추면 아무것도 오지 않는다(그게 설계다) — 스스로 만료를 본다
        while (typingUntil > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(500)
        }
        now = System.currentTimeMillis()
    }
    val label = typingName?.takeIf { typingUntil > now }?.let { "${it}님이 입력 중…" }
    Box(
        Modifier.fillMaxWidth().height(TYPING_ROW_HEIGHT),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (label != null) {
            Text(
                label,
                fontSize = 10.sp,
                color = tokens.inkDim,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
