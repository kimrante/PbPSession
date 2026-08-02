package com.pbp.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens

/**
 * 다이얼로그 버튼의 성격 (지시서 0-4).
 *
 * 색과 크기를 지정하지 않은 TextButton은 M3 기본값(primary=옐로 원색·14sp)으로 새어
 * 밝은 면 위에서 읽히지 않았다. 세 성격만 두고 화면 코드에서는 고르기만 한다.
 */
enum class PbpButtonKind { Confirm, Cancel, Danger }

/**
 * 다이얼로그 버튼 — 11sp bold · 성격별 색 · 터치 타깃 40dp (가이드 §5·§6).
 *
 * 색·크기를 화면마다 다시 적지 않게 하는 것이 목적이다. 여기 없는 변형이 필요하면
 * 그 화면에 리터럴을 쓰지 말고 이 부품에 성격을 추가할 것.
 */
@Composable
fun PbpDialogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: PbpButtonKind = PbpButtonKind.Confirm,
    enabled: Boolean = true,
) {
    val tokens = Pbp.colors
    val color = when (kind) {
        PbpButtonKind.Confirm -> tokens.signatureInk
        PbpButtonKind.Cancel -> tokens.inkDim
        PbpButtonKind.Danger -> tokens.danger
    }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = PbpDimens.touchTarget),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) color else tokens.inkDisabled,
        )
    }
}

/**
 * 작은 알림 배지 (지시서 0-4) — 10sp bold · pill · 최소 18×18.
 *
 * 상하 패딩을 주지 않고 중앙 정렬로 높이를 맞춘다. 패딩으로 높이를 만들면
 * 글자 수에 따라 배지가 세로로 흔들린다.
 */
@Composable
fun PbpBadge(
    label: String,
    modifier: Modifier = Modifier,
    background: Color? = null,
    contentColor: Color? = null,
) {
    val tokens = Pbp.colors
    Box(
        modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(background ?: tokens.signature)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor ?: tokens.onSignature,
        )
    }
}

/**
 * 다이얼로그 제목 (지시서 P1) — 18sp bold · **정중앙**.
 *
 * M3 AlertDialog의 기본 타이틀은 좌측 정렬에 headline 크기라, 화면 상단 바는 센터인데
 * 다이얼로그만 좌측으로 어긋났다. 제목 스타일을 화면 코드에 다시 적지 않게 부품으로 둔다.
 */
@Composable
fun PbpDialogTitle(text: String) {
    Text(
        text,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = Pbp.colors.ink,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
