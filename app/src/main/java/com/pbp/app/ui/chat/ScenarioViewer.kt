package com.pbp.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.app.data.ScenarioFetcher
import com.pbp.app.ui.common.PbpButtonKind
import com.pbp.app.ui.common.PbpDialogButton
import com.pbp.app.ui.common.PbpDialogTitle
import com.pbp.app.ui.theme.GowunBatang
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import com.pbp.shared.ScenarioDoc

/**
 * 시나리오 뷰어 — **입력줄 위에 붙는 판** (V4·V5).
 *
 * 링크를 받아 문서를 문장 단위로 넘겨 보여 준다. GM이 대화 화면을 벗어나지 않고
 * 시나리오를 읽기 위한 것이라 화면 전환 대신 입력 영역 위에 얹는다.
 * 떠다니지 않는다 — 자리를 정해 두면 눈이 그 자리를 다시 찾지 않아도 된다.
 * 읽던 위치는 ViewModel에 있어 판을 닫아도 살아남는다.
 *
 * 내용은 **GM 화면에만 있다** — 상대에게 전송되지 않고 서버에도 올라가지 않는다.
 */
@Composable
internal fun ScenarioFloat(
    state: ChatViewModel.ScenarioState,
    onSubmit: (String) -> Unit,
    onStep: (Int) -> Unit,
    /** 지금 고른 표시 단위 — 문서를 열기 전에도 설정 창이 이 값을 보여 준다 */
    lines: Int,
    onLinesChange: (Int) -> Unit,
    onReset: () -> Unit,
    /** 지금 보고 있는 문장을 입력창에 넣는다 — **보내지는 않는다** */
    onCopyToInput: (String) -> Unit,
    onClose: () -> Unit,
    onFailureAck: () -> Unit,
) {
    val tokens = Pbp.colors
    // 입력 중인 링크는 화면에 둔다 — 실패 후 고쳐서 다시 보낼 수 있어야 한다
    var link by rememberSaveable { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val viewing = state as? ChatViewModel.ScenarioState.Viewing

    Column(
        Modifier
            .fillMaxWidth()
            // 입력줄에 맞닿는 판이라 위쪽만 둥글린다 — 아래는 이어지는 면이다
            .clip(RoundedCornerShape(topStart = PbpDimens.rCell, topEnd = PbpDimens.rCell))
            // 입력 바 톤을 쓰되 **불투명**해야 한다 — chatBarBg는 반투명이라
            // 그대로 두면 뒤 말풍선이 글자에 겹쳐 시나리오를 읽을 수 없다
            .background(tokens.panel)
            .background(tokens.chatBarBg)
            .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap3),
    ) {
        // 머리글 — 좌측 이름표와 문서 제목, 우측 도구
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("📖 시나리오", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tokens.inkDim)
            if (viewing != null) {
                Spacer(Modifier.width(PbpDimens.gap2))
                Text(
                    // 제목을 못 받았으면 자리를 비워 두지 않는다 — 무엇을 읽는지가
                    // 머리글의 존재 이유다
                    viewing.title ?: "제목 없는 문서",
                    fontSize = 11.sp,
                    color = tokens.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            // 복사는 **읽을 문장이 있을 때만** 뜬다 — 링크 입력 중에는 복사할 게 없다
            if (viewing != null) {
                HeaderIcon("⧉", "이 문장을 입력창에 넣기") {
                    onCopyToInput(viewing.pages.getOrNull(viewing.index).orEmpty())
                }
            }
            HeaderIcon("⚙", "시나리오 설정") { settingsOpen = true }
            HeaderIcon("×", "시나리오 창 닫기", size = 18.sp, onTap = onClose)
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
                    state.pages.getOrNull(state.index).orEmpty(),
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
            if (state.truncated) {
                // 조용히 버리지 않는다 — 뒷부분이 없는 줄 모르고 읽으면 진행이 끊긴다
                Spacer(Modifier.height(PbpDimens.gap2))
                Text(
                    "문서가 커서 앞부분만 표시합니다",
                    fontSize = 10.sp,
                    color = tokens.inkDim,
                )
            }
            Spacer(Modifier.height(PbpDimens.gap3))
            NavRow(state, onStep)
        }
    }

    if (state is ChatViewModel.ScenarioState.Failed) {
        ScenarioErrorDialog(state.error, onFailureAck)
    }
    if (settingsOpen) {
        ScenarioSettingsDialog(
            viewing = viewing,
            lines = lines,
            onLinesChange = onLinesChange,
            onReset = {
                // 재설정하면 빈 상자로 돌아간다 — 남은 링크를 지워야 정말 빈 상자다
                link = ""
                settingsOpen = false
                onReset()
            },
            onDismiss = { settingsOpen = false },
        )
    }
}

/** 머리글의 아이콘 버튼 — 셋이 같은 히트 규격·같은 톤을 쓰도록 한 곳에 둔다 */
@Composable
private fun HeaderIcon(
    glyph: String,
    description: String,
    size: androidx.compose.ui.unit.TextUnit = 15.sp,
    onTap: () -> Unit,
) {
    val tokens = Pbp.colors
    Box(
        Modifier
            .size(PbpDimens.touchTarget)
            .clip(RoundedCornerShape(999.dp))
            .semantics { contentDescription = description }
            .pointerInput(Unit) { detectTapGesturesSimple(onTap) },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = size, color = tokens.inkDim) }
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
private fun NavRow(state: ChatViewModel.ScenarioState.Viewing, onStep: (Int) -> Unit) {
    val tokens = Pbp.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${state.index + 1} / ${state.pages.size}",
            fontSize = 10.sp,
            color = tokens.inkDim,
        )
        Spacer(Modifier.weight(1f))
        // 요구사항 고정 위치 — 우하단
        NavButton("<", "이전 문장", state.index > 0) { onStep(-1) }
        Spacer(Modifier.width(PbpDimens.gap2))
        NavButton(">", "다음 문장", state.index < state.pages.lastIndex) { onStep(1) }
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
 * 시나리오 설정 — 표시 단위와 현재 문서.
 *
 * 표시 단위를 바꿔도 문서를 다시 받지 않는다. 이미 나눠 둔 문장을 다시 묶기만 하면
 * 되고, 읽던 자리도 같은 문장 위에 남는다 (ChatViewModel.setScenarioLines).
 */
@Composable
private fun ScenarioSettingsDialog(
    viewing: ChatViewModel.ScenarioState.Viewing?,
    lines: Int,
    onLinesChange: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = Pbp.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { PbpDialogTitle("시나리오 설정") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("한 번에 보여 줄 문장 수", fontSize = 12.sp, color = tokens.ink)
                Spacer(Modifier.height(PbpDimens.gap2))
                Row(horizontalArrangement = Arrangement.spacedBy(PbpDimens.gap2)) {
                    for (n in ScenarioDoc.VIEW_LINES) {
                        val on = lines == n
                        Text(
                            "$n",
                            fontSize = 13.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) tokens.signatureInk else tokens.inkDim,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .size(PbpDimens.touchTarget)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (on) tokens.signature.copy(alpha = .14f) else tokens.inkFaint)
                                .pointerInput(n) { detectTapGesturesSimple { onLinesChange(n) } }
                                .wrapContentHeight(),
                        )
                    }
                }
                Spacer(Modifier.height(PbpDimens.gap4))
                Text("현재 문서", fontSize = 12.sp, color = tokens.ink)
                Spacer(Modifier.height(PbpDimens.gap1))
                Text(
                    viewing?.title ?: "아직 문서를 열지 않았습니다",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (viewing != null) {
                    Spacer(Modifier.height(PbpDimens.gap1))
                    Text(
                        viewing.url,
                        fontSize = 10.sp,
                        color = tokens.inkDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = { PbpDialogButton("닫기", onDismiss) },
        // 재설정은 되돌릴 수 없는 쪽이라 확인 버튼과 반대편에 둔다
        dismissButton = {
            PbpDialogButton("문서 재설정", onReset, kind = PbpButtonKind.Cancel, enabled = viewing != null)
        },
    )
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
