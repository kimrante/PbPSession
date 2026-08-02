package com.pbp.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens

/**
 * 프로필 한 줄 — 아바타 + 이름 + 태그 (목업 프로필 관리).
 *
 * 프로필 관리 다이얼로그와 채팅 프로필 전환 사이드바가 **같은 부품**을 쓴다.
 * 규격을 복제하면 한쪽만 손봤을 때 조용히 갈라진다 (가이드 원칙 4).
 *
 * @param selected 지금 말하고 있는 프로필 — 면·테두리·이름색으로 강조하고 ✓를 붙인다
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ManagerRow(
    label: String,
    sub: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    avatar: @Composable () -> Unit,
) {
    val tokens = Pbp.colors
    val shape = RoundedCornerShape(PbpDimens.rCell)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            // 활성 강조는 판정 요청 칩과 같은 문법 (면 .14 + 테두리 .4)
            .then(
                if (selected) {
                    Modifier
                        .background(tokens.signature.copy(alpha = .14f))
                        .border(1.dp, tokens.signature.copy(alpha = .4f), shape)
                } else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(PbpDimens.gap3), // 동류 행(추가하기·AddOptionRow)과 같은 패딩
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        Spacer(Modifier.width(PbpDimens.gap3))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) tokens.signatureInk else tokens.ink,
            )
            Text(sub, fontSize = 11.sp, color = tokens.inkDim)
        }
        if (selected) {
            Text("✓", fontSize = 13.sp, fontWeight = FontWeight.Black, color = tokens.signatureInk)
        }
    }
}
