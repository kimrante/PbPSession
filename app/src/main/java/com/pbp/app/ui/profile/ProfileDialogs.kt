package com.pbp.app.ui.profile

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.app.data.OwnerProfile
import com.pbp.app.ui.common.PbpButtonKind
import com.pbp.app.ui.common.PbpDialogButton
import com.pbp.app.ui.common.PbpDialogTitle
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens

@Composable
fun ProfileManagerDialog(
    profiles: List<com.pbp.app.data.CharacterProfile>,
    roomNames: Map<Long, String>,
    onDismiss: () -> Unit,
    onOwner: () -> Unit,
    onProfile: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val tokens = Pbp.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { PbpDialogTitle("프로필 관리") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 오너 프로필 — 항상 맨 위
                ManagerRow(
                    label = OwnerProfile.name.ifBlank { "오너 프로필" },
                    sub = "오너 · 잡담과 참여 인사에 사용",
                    onClick = onOwner,
                ) {
                    com.pbp.app.ui.common.OwnerAvatar(
                        OwnerProfile.name, OwnerProfile.color, OwnerProfile.imagePath,
                        PbpDimens.avatarStrip,
                    )
                }
                profiles.forEach { profile ->
                    val sub = when {
                        profile.isGm -> "GM" +
                            (profile.roomId?.let { roomNames[it] }?.let { " · $it" } ?: "")
                        profile.roomId != null ->
                            roomNames[profile.roomId]?.let { "캐릭터 · $it" } ?: "캐릭터"
                        else -> "캐릭터 · 모든 방 공통"
                    }
                    ManagerRow(
                        label = profile.name.ifBlank { "이름 없음" },
                        sub = sub,
                        onClick = { onProfile(profile.id) },
                    ) {
                        com.pbp.app.ui.common.Avatar(
                            emoji = profile.emoji,
                            imagePath = profile.imagePath,
                            size = PbpDimens.avatarStrip,
                        )
                    }
                }
                // 프로필 추가하기 — 목록 맨 아래
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PbpDimens.rCell))
                        .combinedClickable(onClick = onAdd)
                        .padding(PbpDimens.gap3),
                ) {
                    Text(
                        "＋ 프로필 추가하기",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = tokens.signatureInk, // 밝은 다이얼로그 위 옐로 텍스트 금지 (스펙 0장)
                    )
                }
            }
        },
        confirmButton = { PbpDialogButton("닫기", onDismiss) },
    )
}

@Composable
private fun ManagerRow(
    label: String,
    sub: String,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit,
) {
    val tokens = Pbp.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PbpDimens.rCell))
            .combinedClickable(onClick = onClick)
            .padding(PbpDimens.gap3), // 동류 행(추가하기·AddOptionRow)과 같은 패딩
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        Spacer(Modifier.width(PbpDimens.gap3))
        Column {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
            Text(sub, fontSize = 11.sp, color = tokens.inkDim)
        }
    }
}
