package com.pbp.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import com.pbp.app.data.Message
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import com.pbp.app.ui.theme.PbpPalette

/** 채팅 다이얼로그 — 메시지 액션·편집 (리뷰 B3) */

/**
 * 길게 누른 메시지의 액션 팝업 (목업 mockup-message-actions).
 * 대상 말풍선 미리보기(옐로 링)를 시트 위에 띄워 무엇을 다루는지 보여준다.
 */
@Composable
internal fun MessageActionDialog(
    message: Message,
    /** 내 메시지만 편집·삭제할 수 있다. 복사는 상대 메시지에서도 쓸 수 있다 */
    canModify: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCapture: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = Pbp.colors
    val bubbleShape = RoundedCornerShape(
        topStart = PbpDimens.rCard,
        topEnd = PbpDimens.rTail,
        bottomEnd = PbpDimens.rCard,
        bottomStart = PbpDimens.rCard,
    )
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .clip(bubbleShape)
                        .background(Color(message.senderBubbleColor ?: PbpPalette.bubblePresets.first()))
                        .border(2.dp, tokens.signature, bubbleShape)
                        .padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2),
                ) {
                    Text(
                        message.body,
                        fontSize = 13.sp,
                        color = tokens.bubbleInk,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(PbpDimens.gap3))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(PbpDimens.rSheet))
                    .background(tokens.panel)
                    .padding(PbpDimens.gap4),
            ) {
                Text("메시지", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
                Spacer(Modifier.height(PbpDimens.gap2))
                // 순서: 편집 · 복사 · 캡처 · 삭제 — 파괴적인 삭제를 맨 아래로
                if (canModify) {
                MessageActionRow(
                    icon = "✏️",
                    tileColor = tokens.signature,
                    title = "편집",
                    titleColor = tokens.signatureInk,
                    subtitle = "본문을 고치면 (수정됨) 표시가 남습니다",
                    onClick = onEdit,
                )
                }
                MessageActionRow(
                    icon = "📋",
                    tileColor = tokens.inkDim,
                    title = "복사",
                    titleColor = tokens.ink,
                    subtitle = "본문을 클립보드에 담습니다",
                    onClick = onCopy,
                )
                MessageActionRow(
                    icon = "🖼️",
                    tileColor = tokens.themeDefault,
                    title = "캡처",
                    titleColor = Color(PbpPalette.nameColorForLight(0xFF8EC5E8)),
                    subtitle = "여기부터 범위를 골라 이미지로 만듭니다",
                    onClick = onCapture,
                )
                if (canModify) {
                MessageActionRow(
                    icon = "🗑️",
                    tileColor = tokens.danger,
                    title = "삭제",
                    titleColor = tokens.danger,
                    subtitle = "공유된 방이면 상대 화면에서도 사라집니다",
                    onClick = onDelete,
                )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "취소",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.inkDim,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2),
                    )
                }
            }
        }
    }
}

@Composable
internal fun MessageActionRow(
    icon: String,
    tileColor: Color,
    title: String,
    titleColor: Color,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PbpDimens.rCell))
            .clickable(onClick = onClick)
            .padding(PbpDimens.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PbpDimens.gap3),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(PbpDimens.rCell))
                .background(tileColor.copy(alpha = .14f))
                .border(1.dp, tileColor.copy(alpha = .4f), RoundedCornerShape(PbpDimens.rCell)),
            contentAlignment = Alignment.Center,
        ) { Text(icon, fontSize = 15.sp) }
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = titleColor)
            Text(subtitle, fontSize = 11.sp, color = Pbp.colors.inkDim)
        }
    }
}

/**
 * 입력 문법 도움말 — 입력창 오른쪽 끝 "?"로 연다. 오른쪽 위 X로 닫는다.
 * 목록은 [com.pbp.shared.MarkupHelp]가 단일 출처라 PC와 항상 같다.
 *
 * 제목은 센터, 항목은 좌측 정렬 — 문법 예시는 세로로 줄이 맞아야 훑어읽기 좋다.
 */
@Composable
internal fun MarkupHelpDialog(onDismiss: () -> Unit) {
    val tokens = Pbp.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PbpDimens.rSheet))
                .background(tokens.panel)
                .padding(PbpDimens.gap4),
        ) {
            Box(Modifier.fillMaxWidth()) {
                Text(
                    "입력 문법",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.ink,
                    modifier = Modifier.align(Alignment.Center),
                )
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", fontSize = 13.sp, color = tokens.inkSub)
                }
            }
            Spacer(Modifier.height(PbpDimens.gap3))
            com.pbp.shared.MarkupHelp.entries.forEach { entry ->
                Column(Modifier.fillMaxWidth().padding(vertical = PbpDimens.gap2)) {
                    Text(
                        entry.syntax,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = tokens.signature,
                    )
                    Spacer(Modifier.height(PbpDimens.gap1))
                    Text(entry.summary, fontSize = 12.sp, color = tokens.ink)
                    entry.example?.let {
                        Spacer(Modifier.height(PbpDimens.gap1))
                        Text(it, fontSize = 11.sp, color = tokens.inkDim)
                    }
                }
            }
        }
    }
}

@Composable
internal fun EditMessageDialog(
    messageId: Long,
    original: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    // 회전에도 편집 중 텍스트 보존 (P2-4). 키가 메시지 id라 다른 메시지를 열면 초기화된다
    var body by rememberSaveable(messageId) {
        mutableStateOf(original)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("메시지 수정") },
        text = {
            OutlinedTextField(value = body, onValueChange = { body = it }, maxLines = 6)
        },
        confirmButton = {
            TextButton(onClick = { onSave(body) }, enabled = body.isNotBlank()) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * 대상 캐릭터에 그 값이 없을 때 — 값을 받아 프로필에 채우고 바로 굴린다 (J6).
 * 주사위를 굴려야 하므로 **숫자만** 받는다.
 */
@Composable
internal fun JudgeValueDialog(
    targetName: String,
    statName: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var input by rememberSaveable(statName) { mutableStateOf("") }
    val value = input.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$statName 값이 없습니다") },
        text = {
            Column {
                Text(
                    "${targetName}의 $statName 값을 정하면 프로필에 저장하고 바로 굴립니다.",
                    fontSize = 13.sp,
                    color = Pbp.colors.inkDim,
                )
                Spacer(Modifier.height(PbpDimens.gap2))
                OutlinedTextField(
                    value = input,
                    onValueChange = { text -> input = text.filter { it.isDigit() }.take(3) },
                    label = { Text("$statName (숫자)", fontSize = 10.sp) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onConfirm) }, enabled = value != null) {
                Text("저장하고 굴리기")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
