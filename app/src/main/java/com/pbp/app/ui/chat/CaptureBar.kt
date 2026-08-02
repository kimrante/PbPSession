package com.pbp.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.app.data.Message
import com.pbp.app.export.CaptureRenderer
import com.pbp.app.ui.common.formatTime
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens

/** 캡처 바 높이 — 선택 상태가 바뀌어도 바가 튀지 않도록 고정 (목업 실측 70px) */
private val CAPTURE_BAR_HEIGHT = PbpDimens.captureBarHeight

/**
 * 캡처 모드 상단 바 — 평상시 바와 같은 56dp 자리에, 배경만 시그니처 톤으로 바꿔
 * 모드 전환을 알린다. 버튼이 왼쪽 하나뿐이라 titleInset(96dp) 대신 titleInsetNarrow(56dp).
 */
@Composable
internal fun CaptureModeBar(subtitle: String, onClose: () -> Unit) {
    val tokens = Pbp.colors
    val onBar = tokens.onSignature
    Box(
        Modifier
            .fillMaxWidth()
            .height(PbpDimens.appBarHeight)
            .background(
                Brush.verticalGradient(
                    listOf(tokens.signature.copy(alpha = .95f), tokens.signature.copy(alpha = .72f))
                )
            )
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = PbpDimens.gap2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Text("✕", fontSize = 18.sp, color = onBar.copy(alpha = .72f))
            }
        }
        // 좌우 같은 인셋으로 절대 배치 — 버튼 개수와 무관하게 정중앙
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .height(PbpDimens.appBarHeight)
                .padding(horizontal = PbpDimens.titleInsetNarrow),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "캡처할 범위 선택",
                fontSize = 15.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Bold,
                color = onBar,
            )
            Spacer(Modifier.height(PbpDimens.gap1))
            Text(
                subtitle,
                fontSize = 11.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium,
                color = onBar.copy(alpha = .66f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 하단 캡처 바 — 입력줄 자리를 대신한다.
 * 부제는 한 줄 고정: 두 줄로 감기면 바 안쪽 상하 여백이 어긋난다.
 */
@Composable
internal fun CaptureBar(
    count: Int,
    /** "21:03–21:14" — 끝점이 없으면 null */
    timeRange: String?,
    /** 끝점이 없을 때 보여줄 시작점 정보 */
    startLabel: String?,
    estimatedPx: Int?,
    overLimit: Boolean,
    rendering: Boolean,
    onMake: () -> Unit,
) {
    val tokens = Pbp.colors
    val tooTall = (estimatedPx ?: 0) > CaptureRenderer.MAX_TOTAL_HEIGHT_PX
    val enabled = timeRange != null && !overLimit && !tooTall && !rendering
    val subtitle = when {
        overLimit -> "한 번에 ${ChatViewModel.PAGE_SIZE}개까지 고를 수 있어요"
        tooTall -> "너무 길어요 — 범위를 나눠서 만들어 주세요"
        rendering -> "이미지를 만들고 있어요…"
        timeRange != null -> "$timeRange · 약 ${"%,d".format(estimatedPx ?: 0)}px"
        else -> startLabel ?: "끝 메시지를 탭하세요"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(CAPTURE_BAR_HEIGHT)
            .background(tokens.panel)
            .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${count}개 선택됨",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Black,
                color = tokens.ink,
            )
            Text(
                subtitle,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = if (overLimit || tooTall) tokens.danger else tokens.inkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(PbpDimens.gap3))
        Text(
            "이미지 만들기",
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            color = if (enabled) tokens.onSignature else tokens.ink.copy(alpha = .34f),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (enabled) tokens.signature else tokens.ink.copy(alpha = .08f))
                .clickable(enabled = enabled, onClick = onMake)
                .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap3),
        )
    }
}

/** 선택 구간의 표시 상태 — 밴드 모서리와 흐리기 판정 */
internal fun captureMarkOf(range: IntRange?, index: Int): CaptureMark = when {
    range == null -> CaptureMark.NONE
    index !in range -> CaptureMark.OUT
    range.first == range.last -> CaptureMark.ONLY
    index == range.first -> CaptureMark.START
    index == range.last -> CaptureMark.END
    else -> CaptureMark.IN
}

/** "21:03–21:14". 한 건뿐이면 그 시각만 */
internal fun timeRangeLabel(messages: List<Message>): String? {
    val first = messages.firstOrNull() ?: return null
    val last = messages.last()
    val a = formatTime(first.createdAt)
    val b = formatTime(last.createdAt)
    return if (a == b) a else "$a–$b"
}

/** 규칙은 :shared CaptureLayout이 단일 출처 — PC와 갈라지지 않게 (C2) */
internal fun captureRangeAfterTap(range: IntRange, tapped: Int): IntRange =
    com.pbp.shared.CaptureLayout.rangeAfterTap(range, tapped)
