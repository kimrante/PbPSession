package com.pbp.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    onSend: (String, Boolean) -> Unit,
    rule: String,
    /** 상대 이름 — "○○님이 입력 중…". 만료 판정은 [TypingLine]이 스스로 한다 (E2) */
    typingName: String? = null,
    typingUntil: Long = 0L,
    /**
     * 지금 말하고 있는 프로필이 GM인가 — GM 도구 칩의 노출 기준.
     * room.isMaster가 아니라 **활성 프로필** 기준이다: GM이 자기 NPC로 말하는 중에는
     * 요청을 걸 수 없고, 그게 자연스럽다 (J2). 시나리오 창도 같은 기준을 쓰므로
     * 화면(ChatScreen)에서 한 번 계산해 내려보낸다 (V4).
     */
    gmActive: Boolean,
    /** GM 프로필로 말하는 중에만 보이는 판정 요청 (J2) */
    onJudgeRequest: () -> Unit = {},
    /** 시나리오 뷰어를 여는 **유일한 진입점** (V3) — 다른 경로로는 창이 뜨지 않는다 */
    onScenarioViewer: () -> Unit = {},
    /**
     * 바깥(시나리오 뷰어)에서 입력창에 넣어 달라고 건네는 글. **보내지는 않는다.**
     *
     * 입력 상태를 화면으로 끌어올리지 않고 이 통로만 뚫은 이유: 입력값이 위로 가면
     * 글자 하나마다 채팅 화면 전체가 리컴포즈된다. 넣고 나면 [onInsertConsumed]로
     * 비워 같은 글이 두 번 들어가지 않게 한다.
     */
    insertText: String? = null,
    onInsertConsumed: () -> Unit = {},
    /** 실제로 글자가 바뀔 때만 */
    onTyping: () -> Unit = {},
    onTypingStopped: () -> Unit = {},
) {
    val tokens = Pbp.colors
    // 입력 상태는 여기(하위)에서만 — 키 입력마다 화면 전체가 리컴포즈되지 않도록.
    // rememberSaveable: 화면 회전에도 입력을 보존 (P2-4)
    var input by rememberSaveable { mutableStateOf("") }
    var oocOn by rememberSaveable { mutableStateOf(false) }
    // 자동완성 채팅 팔레트 — 활성 캐릭터의 값 이름을 부분 입력하면 판정 매크로 추천
    val activeStats = remember(profiles, activeId) {
        profiles.find { it.id == activeId }
            ?.let { com.pbp.shared.ProfileStats.decode(it.stats) } ?: emptyList()
    }
    val suggestions = remember(input, activeStats) {
        com.pbp.shared.ProfileStats.paletteSuggestions(input, activeStats)
    }
    LaunchedEffect(insertText) {
        val incoming = insertText ?: return@LaunchedEffect
        // 쓰던 글이 있으면 지우지 않고 뒤에 잇는다 — 남의 글을 삼키면 안 된다
        input = if (input.isBlank()) incoming else "$input $incoming"
        onTyping()
        onInsertConsumed()
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
        // 프로필 교체는 **상단 바 아바타 → 사이드바** 한 곳으로 모았다.
        // 입력줄의 스트립은 같은 동작의 두 번째 진입점이라 자리만 먹었고,
        // 프로필이 늘수록 입력 영역이 두꺼워져 대화가 밀렸다.
        if (gmActive) {
            // 판정 팔레트 칩과 같은 캡슐·같은 자리 (J2).
            // 입력 중 표시줄보다 위에 둔다 — 사이에 끼면 칩과 입력줄이 불필요하게 벌어진다
            Row(
                Modifier.heightIn(min = PbpDimens.touchTarget),
                horizontalArrangement = Arrangement.spacedBy(PbpDimens.gap2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GmChip("＋ 판정 요청", onJudgeRequest)
                // 시나리오 뷰어 (V3) — 판정 요청과 같은 급의 GM 도구라 같은 캡슐을 쓴다
                GmChip("📖 시나리오", onScenarioViewer)
            }
            // 칩과 입력줄 사이 여백 — 없애 보니 붙어 보여 원래대로 되돌렸다
            Spacer(Modifier.height(PbpDimens.gap2))
        }
        TypingLine(typingName, typingUntil)
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
            // 스위치 노브 없이 **색만으로** 켬/끔을 알린다 — 켜지면 면이 옐로로 차고
            // 글자도 옐로 잉크가 된다. 노브가 빠진 만큼 칩이 좁아진다
            Text(
                "잡담",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (oocOn) tokens.onSignature else tokens.inkDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (oocOn) tokens.signature else tokens.chatterBubble)
                    .border(
                        1.dp,
                        if (oocOn) tokens.signature else tokens.line,
                        RoundedCornerShape(999.dp),
                    )
                    .clickable(onClick = onOocToggle)
                    .padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2),
            )
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
                        lineHeight = 20.sp, // 입력 텍스트와 같은 줄 높이
                        color = tokens.inkDim,
                    )
                },
                // textStyle을 주지 않으면 M3 기본 16sp로 새어, 치기 시작하는 순간
                // 플레이스홀더(13sp)에서 크기가 점프한다 (8장).
                //
                // lineHeight·lineHeightStyle을 함께 지정하는 이유: 기본값은 줄 상자를
                // 글립 크기에 딱 맞춰 잡아, 여러 줄이 되면 **마지막 줄의 내림선(g·y·ㅇ 아래)이
                // 잘려 보인다**. Trim.None으로 첫 줄·마지막 줄의 여유를 남긴다
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                        alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                        trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
                    ),
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
}

/**
 * 입력 중 표시 (E2) — 자리를 늘 차지해 상대가 치기 시작해도 입력 영역이 튀지 않는다.
 *
 * 만료 확인용 0.5초 틱을 **여기서만** 돈다. 예전에는 채팅 화면 본체에 있어서
 * 상대가 타이핑하는 동안 초당 두 번 화면 전체가 리컴포즈됐다.
 */
/**
 * GM 도구 칩 — 판정 요청·시나리오가 같은 캡슐을 쓴다.
 * 자간·패딩·토큰을 한 곳에 두어 동류 컴포넌트가 갈라지지 않게 한다 (CLAUDE.md 0장).
 */
@Composable
private fun GmChip(label: String, onClick: () -> Unit) {
    val tokens = Pbp.colors
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = tokens.signatureInk,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tokens.signature.copy(alpha = .14f))
            .border(1.dp, tokens.signature.copy(alpha = .4f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2),
    )
}

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
