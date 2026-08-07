package com.pbp.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.app.data.ScenarioFetcher
import com.pbp.app.ui.common.PbpDialogButton
import com.pbp.app.ui.common.PbpDialogTitle
import com.pbp.app.ui.theme.GowunBatang
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens

/**
 * 시나리오 뷰어 — **입력 영역 위에 도킹되는 패널** (V4, 시안 ①·②).
 *
 * 떠 있는 창이 아니다. 입력 바와 한 덩어리로 보이고 위쪽 경계선 1dp만 두른다.
 * 오버레이가 아니라 메시지 목록이 패널 높이만큼 줄어드는 방식이라 **마지막 말풍선을
 * 가리지 않는다** — GM이 시나리오를 읽으면서 방금 오간 말을 계속 볼 수 있어야 한다.
 *
 * 내용은 **GM 화면에만 있다** — 상대에게 전송되지 않고 서버에도 올라가지 않는다.
 */
@Composable
internal fun ScenarioPanel(
    state: ChatViewModel.ScenarioState,
    onSubmit: (String) -> Unit,
    onStep: (Int) -> Unit,
    onInsert: () -> Unit,
    onReset: () -> Unit,
    onRestart: () -> Unit,
    onParagraphMode: (Boolean) -> Unit,
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
            // 입력 바와 한 면 — 위쪽 경계선만 두르고 반경·그림자는 없다(도킹)
            .background(tokens.chatBarBg)
            .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap3),
    ) {
        // ── 헤더: 📖 제목 · ⧉ · ⚙ · ✕
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                // 제목을 못 받았으면 기능 이름으로 채운다 — 자리를 비우지 않는다
                "📖 ${viewing?.title ?: "시나리오"}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = tokens.inkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 문서를 열기 전에는 붙여넣을 것도 설정할 것도 없다 — 그때만 보인다
            if (viewing != null) {
                HeaderIcon("⧉", "문장을 입력창에 붙여넣기", onInsert)
                HeaderIcon("⚙", "시나리오 뷰어 설정") { settingsOpen = true }
            }
            HeaderIcon("✕", "시나리오 패널 닫기", onClose)
        }
        // 본문 위아래를 gap2로 맞춘다 (상하 대칭) — gap3 + 본문 자체 여백까지
        // 겹쳐 한 문장짜리 본문에 견줘 위아래가 지나치게 두꺼웠다
        Spacer(Modifier.height(PbpDimens.gap2))

        when (state) {
            is ChatViewModel.ScenarioState.Viewing -> ViewingBody(state)

            is ChatViewModel.ScenarioState.Loading -> Box(
                Modifier.fillMaxWidth().heightIn(min = ASK_LINK_HEIGHT),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = tokens.signature) }

            // 경고창을 확인하기 전까지는 링크 폼을 그대로 둔다 — 패널이 튀지 않는다
            else -> LinkForm(link, { link = it }, onSubmit)
        }

        if (viewing != null) {
            Spacer(Modifier.height(PbpDimens.gap2))
            NavRow(viewing, onStep)
        }
    }

    if (state is ChatViewModel.ScenarioState.Failed) {
        ScenarioErrorDialog(state.error, onFailureAck)
    }
    if (settingsOpen && viewing != null) {
        ScenarioSettingsDialog(
            viewing = viewing,
            onParagraphMode = onParagraphMode,
            onRestart = {
                settingsOpen = false
                onRestart()
            },
            onReset = {
                // 다른 문서로 바꾸면 빈 상자로 돌아간다 — 남은 링크를 지워야 정말 빈 상자다
                link = ""
                settingsOpen = false
                onReset()
            },
            onDismiss = { settingsOpen = false },
        )
    }
}

/** 링크 입력 폼 높이 — Loading이 이 높이를 지켜야 패널이 깜빡이지 않는다 */
private val ASK_LINK_HEIGHT = 96.dp

/** 헤더 아이콘 — 셋이 같은 히트 규격(40dp)·같은 톤을 쓰도록 한 곳에 둔다 */
@Composable
private fun HeaderIcon(glyph: String, description: String, onTap: () -> Unit) {
    val tokens = Pbp.colors
    Box(
        Modifier
            .size(PbpDimens.touchTarget)
            .clip(RoundedCornerShape(999.dp))
            .semantics { contentDescription = description }
            .pointerInput(description) { detectTapGestures { onTap() } },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = 15.sp, color = tokens.inkDim) }
}

@Composable
private fun LinkForm(link: String, onLinkChange: (String) -> Unit, onSubmit: (String) -> Unit) {
    val tokens = Pbp.colors
    Column(Modifier.fillMaxWidth().heightIn(min = ASK_LINK_HEIGHT)) {
        Text(
            "구글 독스 뷰어 링크를 입력해 주세요",
            fontSize = 13.sp,
            color = tokens.inkDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(PbpDimens.gap2))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PbpDimens.gap2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = link,
                onValueChange = onLinkChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = tokens.ink),
                placeholder = {
                    Text("docs.google.com/document/…", fontSize = 12.sp, color = tokens.inkDim)
                },
                // 입력줄과 같은 규격 — panel2 면 + rCell, 밑줄 없음
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = tokens.panel2,
                    unfocusedContainerColor = tokens.panel2,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = tokens.signature,
                ),
                shape = RoundedCornerShape(PbpDimens.rCell),
                modifier = Modifier.weight(1f),
            )
            // 확인 버튼 = 전송 버튼 규격 (테마색 면 + rCell + 높이 40)
            val enabled = link.isNotBlank()
            Box(
                Modifier
                    .height(PbpDimens.touchTarget)
                    .clip(RoundedCornerShape(PbpDimens.rCell))
                    .background(if (enabled) tokens.signature else tokens.inkFaint)
                    .clickable(enabled = enabled) { onSubmit(link) }
                    .padding(horizontal = PbpDimens.gap3),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "확인",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) tokens.bubbleInk else tokens.inkDisabled,
                )
            }
        }
    }
}

@Composable
private fun ViewingBody(state: ChatViewModel.ScenarioState.Viewing) {
    val tokens = Pbp.colors
    BoxWithConstraints {
        // 긴 문단은 패널 안에서 굴린다 — 입력줄이 밀려나지 않게 화면 1/3로 묶는다
        val maxBody = (maxHeight / 3).coerceAtLeast(72.dp)
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBody)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 시나리오 원문 그대로 — 마크업으로 해석하지 않는다.
                // 서체·행간은 GM 서술과 같다: 읽는 글이지 대화가 아니다
                Text(
                    state.displayText,
                    fontFamily = GowunBatang,
                    fontSize = 13.sp,
                    lineHeight = 24.sp,
                    color = tokens.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.truncated) {
                // 조용히 버리지 않는다 — 뒷부분이 없는 줄 모르고 읽으면 진행이 끊긴다
                Text(
                    "문서가 커서 앞부분만 표시합니다",
                    fontSize = 10.sp,
                    color = tokens.inkDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 진행 표시와 이동 버튼 — 본문이 아무리 길어도 이 줄은 잘리지 않는다 */
@Composable
private fun NavRow(state: ChatViewModel.ScenarioState.Viewing, onStep: (Int) -> Unit) {
    val tokens = Pbp.colors
    val unit = if (state.paragraphMode) "문단" else "문장"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${state.position} / ${state.total}",
            fontSize = 10.sp,
            color = tokens.inkDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        // 요구사항 고정 위치 — 우하단
        NavButton("‹", "이전 $unit", state.position > 1) { onStep(-1) }
        Spacer(Modifier.width(PbpDimens.gap2))
        NavButton("›", "다음 $unit", state.position < state.total) { onStep(1) }
    }
}

@Composable
private fun NavButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val tokens = Pbp.colors
    Box(
        Modifier
            .size(PbpDimens.touchTarget)
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) tokens.panel else tokens.inkFaint)
            .then(
                if (enabled) Modifier.border(1.dp, tokens.line, RoundedCornerShape(999.dp))
                else Modifier
            )
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) tokens.ink else tokens.inkDisabled,
        )
    }
}

/**
 * 뷰어 설정 창 (V4.5, 시안 ④) — 문서와 보기 방식.
 *
 * 보기 방식을 바꿔도 문서를 다시 받지 않는다. 원문을 들고 있으니 나누는 규칙만
 * 갈아 끼우면 되고, 읽던 자리도 :shared의 환산으로 그대로 따라온다.
 */
@Composable
private fun ScenarioSettingsDialog(
    viewing: ChatViewModel.ScenarioState.Viewing,
    onParagraphMode: (Boolean) -> Unit,
    onRestart: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = Pbp.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { PbpDialogTitle("시나리오 뷰어 설정") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel("문서")
                Spacer(Modifier.height(PbpDimens.gap2))
                // 현재 문서 — 보여 주기만 한다(탭 동작 없음)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PbpDimens.rCell))
                        .background(tokens.panel2)
                        .padding(PbpDimens.gap3),
                ) {
                    Text(
                        "📖 ${viewing.title ?: "시나리오"}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        viewing.url,
                        fontSize = 10.sp,
                        color = tokens.inkDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TapRow("다른 문서로 바꾸기", onReset)
                TapRow("처음부터 읽기", onRestart)
                Spacer(Modifier.height(PbpDimens.gap2))
                Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.line))
                Spacer(Modifier.height(PbpDimens.gap2))
                SectionLabel("보기")
                ScenarioToggle(
                    "문단 단위로 보기",
                    checked = viewing.paragraphMode,
                ) { onParagraphMode(!viewing.paragraphMode) }
            }
        },
        confirmButton = { PbpDialogButton("닫기", onDismiss) },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Pbp.colors.inkDim)
}

@Composable
private fun TapRow(label: String, onTap: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = Pbp.colors.signatureInk,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PbpDimens.rCell))
            .clickable(onClick = onTap)
            .padding(PbpDimens.gap3),
    )
}

/** 캡처 설정 토글과 같은 규격(34×20) — 설정 창끼리 부품이 갈라지지 않게 */
@Composable
private fun ScenarioToggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    val tokens = Pbp.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = PbpDimens.gap2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 34.dp, height = 20.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (checked) tokens.signature else tokens.ink.copy(alpha = .16f)),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(2.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(tokens.panel)
            )
        }
        Spacer(Modifier.width(PbpDimens.gap2))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
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
        title = { PbpDialogTitle("시나리오 뷰어") },
        text = {
            Text(message, fontSize = 13.sp, textAlign = TextAlign.Center, color = Pbp.colors.ink)
        },
        confirmButton = { PbpDialogButton("확인", onDismiss) },
    )
}
