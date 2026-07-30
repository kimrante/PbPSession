package com.pbp.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.app.data.Images
import com.pbp.app.data.OwnerProfile
import com.pbp.app.ui.common.HexColorDialog
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import com.pbp.app.ui.theme.PbpPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        title = { Text("프로필 관리") },
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
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color(OwnerProfile.color)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val ownerImage = OwnerProfile.imagePath
                        if (ownerImage != null) {
                            coil3.compose.AsyncImage(
                                model = java.io.File(ownerImage),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        } else {
                            Text(
                                OwnerProfile.name.take(1).ifEmpty { "?" },
                                fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                color = Color(0xFF10151C),
                            )
                        }
                    }
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
                            size = 36.dp,
                        )
                    }
                }
                // 프로필 추가하기 — 목록 맨 아래
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PbpDimens.rCell))
                        .combinedClickable(onClick = onAdd)
                        .padding(PbpDimens.sp3),
                ) {
                    Text(
                        "＋ 프로필 추가하기",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = tokens.signature,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
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
            .padding(PbpDimens.sp2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        Spacer(Modifier.width(PbpDimens.sp3))
        Column {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
            Text(sub, fontSize = 11.sp, color = tokens.inkDim)
        }
    }
}

/**
 * 오너 프로필 설정 — 이미지·이름·컬러만 (캐릭터 프로필 편집의 축소판).
 * 잡담과 참여 인사에 쓰이는 '플레이어 본인' 프로필. forced면 저장 전 닫기 불가.
 */
@Composable
fun OwnerProfileDialog(forced: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokens = Pbp.colors
    var name by remember { mutableStateOf(OwnerProfile.name) }
    var color by remember { mutableStateOf(OwnerProfile.color) }
    var imagePath by remember { mutableStateOf(OwnerProfile.imagePath) }
    var showCustomColor by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                Images.importDownscaled(context, uri, "owner", maxSize = com.pbp.app.data.ImageSizes.PROFILE)
                    ?.let { imagePath = it }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!forced) onClose() },
        title = { Text("오너 프로필") },
        text = {
            Column {
                Text(
                    "잡담과 참여 인사에 쓰이는 플레이어 본인 프로필입니다. " +
                        "세션 캐릭터 목록에는 나타나지 않습니다.",
                    fontSize = 12.sp, color = tokens.inkDim,
                )
                Spacer(Modifier.height(PbpDimens.sp3))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(color)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val preview = imagePath
                        if (preview != null) {
                            coil3.compose.AsyncImage(
                                model = java.io.File(preview),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        } else {
                            Text(
                                name.take(1).ifEmpty { "?" },
                                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                color = Color(0xFF10151C),
                            )
                        }
                    }
                    Spacer(Modifier.width(PbpDimens.sp3))
                    TextButton(onClick = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) { Text(if (imagePath == null) "이미지 선택" else "이미지 변경") }
                    if (imagePath != null) {
                        TextButton(onClick = { imagePath = null }) { Text("제거") }
                    }
                }
                Spacer(Modifier.height(PbpDimens.sp2))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("이름") },
                    singleLine = true,
                )
                Spacer(Modifier.height(PbpDimens.sp3))
                Text("컬러", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tokens.inkDim)
                Spacer(Modifier.height(PbpDimens.sp2))
                Row(horizontalArrangement = Arrangement.spacedBy(PbpDimens.sp2)) {
                    PbpPalette.bubblePresets.forEach { preset ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .border(
                                    2.dp,
                                    if (color == preset) tokens.ink else Color.Transparent,
                                    CircleShape,
                                )
                                .clip(CircleShape)
                                .background(Color(preset))
                                .combinedClickable(onClick = { color = preset }),
                        )
                    }
                    // 커스텀 — 드래그 팔레트로
                    Box(
                        Modifier
                            .size(32.dp)
                            .border(
                                2.dp,
                                if (PbpPalette.bubblePresets.none { it == color }) tokens.ink
                                else Color.Transparent,
                                CircleShape,
                            )
                            .clip(CircleShape)
                            .background(
                                Brush.sweepGradient(
                                    listOf(
                                        Color(0xFFFF6666), Color(0xFFFFCC66), Color(0xFF66DD66),
                                        Color(0xFF66CCFF), Color(0xFFCC66FF), Color(0xFFFF6666),
                                    )
                                )
                            )
                            .combinedClickable(onClick = { showCustomColor = true }),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    OwnerProfile.set(context, name, color, imagePath)
                    onClose()
                },
            ) { Text("저장") }
        },
        dismissButton = { if (!forced) TextButton(onClick = onClose) { Text("취소") } },
    )
    if (showCustomColor) {
        HexColorDialog(
            title = "오너 컬러 (커스텀)",
            onDismiss = { showCustomColor = false },
            onPick = { picked ->
                color = picked
                showCustomColor = false
            },
            initial = color,
        )
    }
}
