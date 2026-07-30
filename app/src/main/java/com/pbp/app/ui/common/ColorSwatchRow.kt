package com.pbp.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.app.data.RecentColors
import com.pbp.app.ui.theme.Pbp

/** 스와치 지름 — 프리셋 3 + 커스텀 1 + 최근 5 = 9개가 한 줄에 들어가는 크기 (목업 01장) */
private val SWATCH = 26.dp
private val GAP = 4.dp

/**
 * 색 선택 줄 — **프리셋 → 커스텀(＋) → 구분선 → 최근 5개** 순서 (목업 01장).
 *
 * 오너 컬러·이름 색·말풍선 색·방 테마 컬러 네 화면이 이 부품 하나를 쓴다.
 * 최근 색의 빈 자리는 점선 원으로 남겨 몇 칸이 더 차는지 보이게 한다.
 * 큰 글꼴·좁은 화면에서 넘칠 수 있어 가로 스크롤로 감싼다.
 */
@Composable
fun ColorSwatchRow(
    presets: List<Long>,
    selected: Long?,
    /** 최근 색 목록은 자리마다 따로다 — 이름 색과 말풍선 색이 섞이지 않게 */
    slot: RecentColors.Slot,
    onSelect: (Long) -> Unit,
    onCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recent = RecentColors.list(slot)
    val tokens = Pbp.colors
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEach { color ->
            Swatch(color = color, selected = selected == color) { onSelect(color) }
        }
        // 커스텀 — 무지개 스와치
        Box(
            Modifier
                .size(SWATCH)
                .clip(CircleShape)
                .background(customColorBrush)
                .clickable(onClick = onCustom),
        )
        // 최근 색 그룹 구분선 (텍스트 없이 시각적으로만 분리)
        Box(
            Modifier
                .width(1.dp)
                .height(16.dp)
                .background(tokens.line)
        )
        recent.forEach { color ->
            Swatch(color = color, selected = selected == color, outlined = true) { onSelect(color) }
        }
        // 남은 자리는 점선 원 — 최근 색이 몇 칸 더 차는지 보인다
        repeat(RecentColors.MAX - recent.size) {
            Box(
                Modifier
                    .size(SWATCH)
                    .drawBehind {
                        drawCircle(
                            color = tokens.line,
                            radius = size.minDimension / 2,
                            style = Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                            ),
                        )
                    }
            )
        }
    }
}

/** 최근 색만 별도로 붙이는 줄 — 라벨 그리드를 쓰는 방 설정 테마 컬러용 (목업 01-B) */
@Composable
fun RecentColorRow(
    selected: Long?,
    slot: RecentColors.Slot,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Pbp.colors
    val recent = RecentColors.list(slot)
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        recent.forEach { color ->
            Swatch(color = color, selected = selected == color, outlined = true) { onSelect(color) }
        }
        repeat(RecentColors.MAX - recent.size) {
            Box(
                Modifier
                    .size(SWATCH)
                    .drawBehind {
                        drawCircle(
                            color = tokens.line,
                            radius = size.minDimension / 2,
                            style = Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                            ),
                        )
                    }
            )
        }
    }
}

/** @param outlined 흰색 계열도 보이도록 안쪽 옅은 테두리 (최근 색용) */
@Composable
private fun Swatch(
    color: Long,
    selected: Boolean,
    outlined: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(SWATCH)
            .then(if (selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(Color(color))
            .then(
                if (outlined) Modifier.border(1.dp, Pbp.colors.line, CircleShape) else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text("✓", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color(0xFF10151C))
        }
    }
}
