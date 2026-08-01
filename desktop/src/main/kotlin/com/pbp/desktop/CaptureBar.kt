package com.pbp.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.desktop.data.Message
import com.pbp.desktop.ui.DesktopDimens
import com.pbp.desktop.ui.Tokens

/** 한 번에 고를 수 있는 최대 개수 — 모바일 ChatViewModel.PAGE_SIZE와 같은 값 */
internal const val CAPTURE_MAX = 200

/** 캡처 바 높이 — 선택 상태가 바뀌어도 튀지 않도록 고정 (모바일과 같은 70dp) */
private val CAPTURE_BAR_HEIGHT = 70.dp

/**
 * 하단 캡처 바 — 입력 영역 자리를 대신한다. 모바일 CaptureBar와 같은 규격·같은 문구.
 * 데스크톱은 Esc로도 나갈 수 있지만, 마우스만 쓰는 사용자를 위해 취소 버튼을 함께 둔다.
 */
@Composable
internal fun CaptureBar(
    count: Int,
    timeRange: String?,
    startLabel: String?,
    overLimit: Boolean,
    rendering: Boolean,
    onMake: () -> Unit,
    onCancel: () -> Unit,
    withBackground: Boolean,
    onToggleBackground: () -> Unit,
    excludeOoc: Boolean,
    onToggleExcludeOoc: () -> Unit,
) {
    val enabled = timeRange != null && !overLimit && !rendering
    val subtitle = when {
        overLimit -> "한 번에 ${CAPTURE_MAX}개까지 고를 수 있어요"
        rendering -> "이미지를 만들고 있어요…"
        timeRange != null -> timeRange
        else -> startLabel ?: "끝 메시지를 클릭하세요"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(CAPTURE_BAR_HEIGHT)
            .background(Tokens.Panel)
            .padding(horizontal = DesktopDimens.gap4, vertical = DesktopDimens.gap3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${count}개 선택됨",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Black,
                color = Tokens.Ink,
            )
            Text(
                subtitle,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = if (overLimit) Tokens.Danger else Tokens.InkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(DesktopDimens.gap3))
        GhostButton(if (withBackground) "배경 포함 ✓" else "배경 포함", Modifier, onToggleBackground)
        Spacer(Modifier.width(DesktopDimens.gap2))
        GhostButton(if (excludeOoc) "잡담 제외 ✓" else "잡담 제외", Modifier, onToggleExcludeOoc)
        Spacer(Modifier.width(DesktopDimens.gap2))
        GhostButton("취소", Modifier, onCancel)
        Spacer(Modifier.width(DesktopDimens.gap2))
        Text(
            "이미지 만들기",
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            color = if (enabled) Tokens.OnSignature else Tokens.Ink.copy(alpha = .34f),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (enabled) Tokens.Signature else Tokens.Ink.copy(alpha = .08f))
                .clickable(enabled = enabled, onClick = onMake)
                .padding(horizontal = DesktopDimens.gap4, vertical = DesktopDimens.gap3),
        )
    }
}

/** 캡처 모드 상단 바 — 평상시와 같은 자리, 배경만 시그니처 톤 (모바일과 동일) */
@Composable
internal fun CaptureModeBar(subtitle: String, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(DesktopDimens.appBar)
            .background(Tokens.Signature),
    ) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "캡처할 범위 선택",
                fontSize = 15.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.OnSignature,
            )
            Spacer(Modifier.height(DesktopDimens.gap1))
            Text(
                subtitle,
                fontSize = 11.sp,
                lineHeight = 11.sp,
                color = Tokens.OnSignature.copy(alpha = .66f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "✕",
            fontSize = 16.sp,
            color = Tokens.OnSignature.copy(alpha = .72f),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = DesktopDimens.gap3)
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onClose)
                .padding(DesktopDimens.gap2),
        )
    }
}

/** "21:03–21:14". 한 건뿐이면 그 시각만 (모바일과 같은 표기) */
internal fun timeRangeLabel(messages: List<Message>): String? {
    val first = messages.firstOrNull() ?: return null
    val last = messages.last()
    val a = formatTime(first.createdAt)
    val b = formatTime(last.createdAt)
    return if (a == b) a else "$a–$b"
}

/**
 * 탭 한 번의 결과 구간 — 모바일 captureRangeAfterTap과 **같은 규칙**이어야 한다
 * (목업 03장). 양 끝을 다시 클릭하면 반대쪽을 고정한 채 그 끝만 옮긴다.
 */
internal fun captureRangeAfterTap(range: IntRange, tapped: Int): IntRange {
    var first = range.first
    var last = range.last
    when {
        tapped == first && first != last -> first = last
        tapped == last && first != last -> last = first
        tapped < first -> first = tapped
        else -> last = tapped
    }
    return minOf(first, last)..maxOf(first, last)
}
