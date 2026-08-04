package com.pbp.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.app.data.ScenarioFetcher
import com.pbp.app.ui.common.PbpDialogButton
import com.pbp.app.ui.common.PbpDialogTitle
import com.pbp.app.ui.theme.GowunBatang
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import kotlin.math.roundToInt

/**
 * 시나리오 뷰어 플로트 창 (V4·V5).
 *
 * 방 안에 떠 있는 카드다. 링크를 받아 문서를 문장 단위로 넘겨 보여 준다 —
 * GM이 대화 화면을 벗어나지 않고 시나리오를 읽기 위한 창이라, 화면 전환 대신
 * 오버레이로 둔다. 읽던 위치는 ViewModel에 있어 창을 닫아도 살아남는다.
 *
 * 내용은 **GM 화면에만 있다** — 상대에게 전송되지 않고 서버에도 올라가지 않는다.
 */
@Composable
internal fun ScenarioFloat(
    state: ChatViewModel.ScenarioState,
    onSubmit: (String) -> Unit,
    onStep: (Int) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    onFailureAck: () -> Unit,
) {
    val tokens = Pbp.colors
    // 입력 중인 링크는 화면에 둔다 — 실패 후 고쳐서 다시 보낼 수 있어야 한다
    var link by rememberSaveable { mutableStateOf("") }
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        // 창이 화면 밖으로 도망가지 않게 — 드래그 폭은 좌우 여백만큼만
        val maxDragX = with(density) { PbpDimens.gap4.toPx() }
        // 위로만 끌어 올린다 — 아래는 입력줄이라 내려갈 자리가 없다
        val maxDragY = with(density) { maxHeight.toPx() }
        Column(
            Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .padding(horizontal = PbpDimens.gap4)
                .fillMaxWidth()
                .clip(RoundedCornerShape(PbpDimens.rCell))
                // 입력 바 톤을 쓰되 **불투명**해야 한다 — chatBarBg는 반투명이라
                // 그대로 두면 뒤 말풍선이 글자에 겹쳐 시나리오를 읽을 수 없다
                .background(tokens.panel)
                .background(tokens.chatBarBg)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offsetX = (offsetX + drag.x).coerceIn(-maxDragX, maxDragX)
                        offsetY = (offsetY + drag.y).coerceIn(-maxDragY, 0f)
                    }
                }
                .padding(PbpDimens.gap4),
        ) {
            // 머리글 — 좌측 이름표, 우측 닫기
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "📖 시나리오",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.inkDim,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(PbpDimens.touchTarget)
                        .clip(RoundedCornerShape(999.dp))
                        .semantics { contentDescription = "시나리오 창 닫기" }
                        .pointerInput(Unit) { detectTapGesturesSimple(onClose) },
                    contentAlignment = Alignment.Center,
                ) { Text("×", fontSize = 18.sp, color = tokens.inkDim) }
            }
            Spacer(Modifier.height(PbpDimens.gap3))

            // 본문만 굴린다 — 아래 이동 행은 늘 손이 닿는 자리에 있어야 한다
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (state) {
                    is ChatViewModel.ScenarioState.Viewing -> Text(
                        // 시나리오 원문 그대로 — 마크업으로 해석하지 않는다.
                        // 서체는 GM 서술과 같은 명조: 읽는 글이지 대화가 아니다
                        state.sentences.getOrNull(state.index).orEmpty(),
                        fontFamily = GowunBatang,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = tokens.ink,
                    )

                    is ChatViewModel.ScenarioState.Loading -> Box(
                        Modifier.fillMaxWidth().heightIn(min = LINK_FORM_MIN_HEIGHT),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = tokens.signature) }

                    // 경고창을 확인하기 전까지는 링크 폼을 그대로 둔다 — 카드가 튀지 않는다
                    else -> LinkForm(link, { link = it }, onSubmit)
                }
            }
            if (state is ChatViewModel.ScenarioState.Viewing) {
                Spacer(Modifier.height(PbpDimens.gap3))
                NavRow(state, onStep, onReset)
            }
        }
    }

    if (state is ChatViewModel.ScenarioState.Failed) {
        ScenarioErrorDialog(state.error, onFailureAck)
    }
}

/** 링크 입력 폼 높이 — Loading이 이 높이를 지켜야 카드가 깜빡이지 않는다 */
private val LINK_FORM_MIN_HEIGHT = 116.dp

@Composable
private fun LinkForm(link: String, onLinkChange: (String) -> Unit, onSubmit: (String) -> Unit) {
    val tokens = Pbp.colors
    Column(Modifier.fillMaxWidth().heightIn(min = LINK_FORM_MIN_HEIGHT)) {
        Text(
            "구글 독스 뷰어 링크를 입력해 주세요",
            fontSize = 12.sp,
            color = tokens.inkDim,
        )
        Spacer(Modifier.height(PbpDimens.gap2))
        OutlinedTextField(
            value = link,
            onValueChange = onLinkChange,
            singleLine = true,
            placeholder = { Text("https://docs.google.com/document/d/…", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(PbpDimens.gap2))
        PbpDialogButton(
            "확인",
            onClick = { onSubmit(link) },
            modifier = Modifier.fillMaxWidth(),
            enabled = link.isNotBlank(),
        )
    }
}

/** 진행 표시·"다른 문서"·이동 버튼 — 본문이 아무리 길어도 이 줄은 잘리지 않는다 */
@Composable
private fun NavRow(
    state: ChatViewModel.ScenarioState.Viewing,
    onStep: (Int) -> Unit,
    onReset: () -> Unit,
) {
    val tokens = Pbp.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${state.index + 1} / ${state.sentences.size}",
            fontSize = 10.sp,
            color = tokens.inkDim,
        )
        Spacer(Modifier.width(PbpDimens.gap3))
        Text(
            "다른 문서",
            fontSize = 10.sp,
            color = tokens.inkDim,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .pointerInput(Unit) { detectTapGesturesSimple(onReset) }
                .padding(horizontal = PbpDimens.gap2, vertical = PbpDimens.gap1),
        )
        Spacer(Modifier.weight(1f))
        // 요구사항 고정 위치 — 우하단
        NavButton("<", "이전 문장", state.index > 0) { onStep(-1) }
        Spacer(Modifier.width(PbpDimens.gap2))
        NavButton(">", "다음 문장", state.index < state.sentences.lastIndex) { onStep(1) }
    }
}

@Composable
private fun NavButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val tokens = Pbp.colors
    Box(
        Modifier
            .size(PbpDimens.touchTarget)
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) tokens.signature.copy(alpha = .14f) else tokens.inkFaint)
            .semantics { contentDescription = description }
            .then(
                if (enabled) Modifier.pointerInput(Unit) { detectTapGesturesSimple(onClick) }
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) tokens.signatureInk else tokens.inkDisabled,
        )
    }
}

/**
 * 실패 사유별 경고창 (V5). 문구는 작업 지시서에서 확정된 것이라 임의로 바꾸지 않는다.
 * 확인하면 링크 입력으로 돌아간다 — 입력해 둔 링크는 화면에 남아 고쳐 쓸 수 있다.
 */
@Composable
private fun ScenarioErrorDialog(error: ScenarioFetcher.Result.Error, onDismiss: () -> Unit) {
    val message = when (error) {
        ScenarioFetcher.Result.Error.BAD_LINK ->
            "구글 독스 문서 링크가 아닙니다. 공유 → '링크가 있는 모든 사용자' 링크를 붙여넣어 주세요."

        ScenarioFetcher.Result.Error.NO_ACCESS ->
            "문서를 열 수 없습니다. 링크의 공유 설정이 '링크가 있는 모든 사용자(뷰어)'인지 확인해 주세요."

        ScenarioFetcher.Result.Error.NETWORK ->
            "네트워크 오류로 문서를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요."

        ScenarioFetcher.Result.Error.EMPTY ->
            "문서에서 표시할 문장을 찾지 못했습니다."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { PbpDialogTitle("시나리오를 열지 못했습니다") },
        text = { Text(message) },
        confirmButton = { PbpDialogButton("확인", onDismiss) },
    )
}

/**
 * 탭만 받는 최소 제스처. 카드 전체가 드래그를 먹고 있어 `clickable`을 쓰면
 * 버튼 위에서 드래그가 시작될 때 눌림 표시가 남는다.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGesturesSimple(
    onTap: () -> Unit,
) = detectTapGestures { onTap() }
