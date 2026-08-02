package com.pbp.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.desktop.ui.DesktopDimens
import com.pbp.desktop.ui.Tokens
import com.pbp.shared.Rules

/**
 * 판정 요청 후보 (J8) — 내 캐릭터와 상대가 올린 캐릭터를 같은 모양으로 다룬다.
 * 값은 **이름만** 들고 있다. 숫자는 그 캐릭터를 가진 기기에만 있다.
 */
internal data class JudgeCandidate(
    val name: String,
    val emoji: String,
    val nameColor: Long?,
    val stats: List<String>,
)

/**
 * 판정 요청 창 (J8) — 대상 고르기 → 값 고르기를 **한 창 안에서** 전환한다.
 * 모바일 JudgeRequestSheet와 같은 흐름·같은 문구를 쓴다.
 *
 * @param candidates null이면 아직 명단을 받아오는 중
 */
@Composable
internal fun JudgeRequestOverlay(
    candidates: List<JudgeCandidate>?,
    rule: String,
    onDismiss: () -> Unit,
    onSend: (targetName: String, statName: String) -> Unit,
) {
    // 대상은 인덱스가 아니라 **이름**으로 들고 있는다 — 상대 캐릭터는 내 목록에 없다
    var targetName by remember { mutableStateOf<String?>(null) }
    var statName by remember { mutableStateOf<String?>(null) }
    var manualStat by remember { mutableStateOf("") }
    val target = candidates?.find { it.name == targetName }

    Box(
        Modifier.fillMaxSize().background(Tokens.Scrim).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(DesktopDimens.overlay)
                .clip(RoundedCornerShape(DesktopDimens.rSheet))
                .background(Tokens.Panel)
                .clickable(enabled = false) {}
                .padding(DesktopDimens.gap5),
        ) {
            Text(
                "판정 요청", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Tokens.Ink,
            )
            Spacer(Modifier.height(DesktopDimens.gap1))
            Text(
                when {
                    candidates == null -> "캐릭터 명단을 불러오는 중…"
                    target == null -> "누구에게 요청할까요?"
                    else -> "어떤 값으로 판정할까요?"
                },
                fontSize = 11.sp, color = Tokens.InkDim,
            )
            Spacer(Modifier.height(DesktopDimens.gap3))

            if (candidates == null) return@Column
            if (target == null) {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    candidates.forEach { candidate ->
                        CandidateRow(candidate) { targetName = candidate.name }
                    }
                    if (candidates.isEmpty()) {
                        Text(
                            "요청할 캐릭터가 없습니다",
                            fontSize = 13.sp, color = Tokens.InkDim,
                            modifier = Modifier.padding(vertical = DesktopDimens.gap4),
                        )
                    }
                }
                Spacer(Modifier.height(DesktopDimens.gap3))
                GhostButton("취소", Modifier.fillMaxWidth(), onDismiss)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CandidateAvatar(target.emoji)
                    Spacer(Modifier.width(DesktopDimens.gap2))
                    Text(
                        target.name, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = target.nameColor?.let { Color(Tokens.nameColorForLight(it)) }
                            ?: Tokens.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "대상 바꾸기", fontSize = 11.sp, color = Tokens.SignatureInk,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable {
                                targetName = null
                                statName = null
                                manualStat = ""
                            }
                            .padding(horizontal = DesktopDimens.gap3, vertical = DesktopDimens.gap2),
                    )
                }
                Spacer(Modifier.height(DesktopDimens.gap3))
                Column(
                    Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    // 행마다 아래 패딩을 주면 마지막 행만 여백이 남는다 (P2)
                    verticalArrangement = Arrangement.spacedBy(DesktopDimens.gap2),
                ) {
                    // 두 칸씩 — 모바일 시트와 같은 격자
                    target.stats.chunked(2).forEach { pair ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(DesktopDimens.gap2),
                        ) {
                            pair.forEach { name ->
                                StatChip(name, name == statName, Modifier.weight(1f)) {
                                    statName = name
                                    manualStat = ""
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(DesktopDimens.gap2))
                // 값이 없거나 목록에 없는 값을 걸고 싶을 때 — 직접 적는다
                OverlayField(
                    value = manualStat,
                    onChange = {
                        manualStat = it
                        statName = it.trim().ifEmpty { null }
                    },
                    placeholder = "값 이름 직접 입력 (예: 은신)",
                )
                Spacer(Modifier.height(DesktopDimens.gap3))
                Text(
                    statName?.let { "${target.name}의 $it 값으로 1d100 하향 판정" }
                        ?: "값을 고르거나 직접 적어 주세요",
                    fontSize = 11.sp, color = Tokens.InkDim,
                )
                Text(Rules.label(rule), fontSize = 10.sp, color = Tokens.InkDim)
                Spacer(Modifier.height(DesktopDimens.gap3))
                Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.gap2)) {
                    val picked = statName
                    if (picked != null) {
                        YellowButton("보내기", Modifier.weight(1f)) { onSend(target.name, picked) }
                    } else {
                        // 누를 수 없는 버튼에도 자리는 남겨 둔다 — 버튼이 사라지면 창이 흔들린다
                        Box(
                            Modifier.weight(1f)
                                .heightIn(min = DesktopDimens.touchTarget)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Tokens.InkFaint)
                                .padding(horizontal = DesktopDimens.gap4, vertical = DesktopDimens.gap2),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "보내기", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = Tokens.InkDisabled,
                            )
                        }
                    }
                    GhostButton("취소", Modifier.weight(1f), onDismiss)
                }
            }
        }
    }
}

/**
 * 대상 캐릭터에 그 값이 없을 때 (J8) — 숫자를 받아 프로필에 저장하고 바로 굴린다.
 * 모바일 JudgeValueDialog와 같은 규칙: 숫자만, 세 자리까지.
 */
@Composable
internal fun JudgeValueOverlay(
    targetName: String,
    statName: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    OverlayScaffold("$statName 값이 없습니다", onDismiss) {
        Text(
            "${targetName}의 $statName 값을 정하면 프로필에 저장하고 바로 굴립니다.",
            fontSize = 13.sp, color = Tokens.InkDim,
        )
        Spacer(Modifier.height(DesktopDimens.gap3))
        OverlayField(
            value = text,
            // 주사위가 굴러가야 하므로 숫자만 받는다
            onChange = { text = it.filter { ch -> ch.isDigit() }.take(3) },
            placeholder = "$statName (숫자)",
        )
        Spacer(Modifier.height(DesktopDimens.gap4))
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.gap2)) {
            YellowButton("저장하고 굴리기", Modifier.weight(1f)) {
                text.toIntOrNull()?.let(onConfirm)
            }
            GhostButton("취소", Modifier.weight(1f), onDismiss)
        }
    }
}

@Composable
private fun CandidateRow(candidate: JudgeCandidate, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(DesktopDimens.rCell))
            .clickable(onClick = onClick)
            .padding(DesktopDimens.gap3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CandidateAvatar(candidate.emoji)
        Spacer(Modifier.width(DesktopDimens.gap3))
        Column(Modifier.weight(1f)) {
            Text(
                candidate.name, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = candidate.nameColor?.let { Color(Tokens.nameColorForLight(it)) }
                    ?: Tokens.Ink,
            )
            Text(
                // 값이 없어도 숨기지 않는다 — 직접 적어 걸 수 있다
                if (candidate.stats.isEmpty()) "값 없음 · 직접 적어 요청"
                else "값 ${candidate.stats.size}개",
                fontSize = 11.sp, color = Tokens.InkDim,
            )
        }
        Text("›", fontSize = 15.sp, color = Tokens.InkDim)
    }
}

@Composable
private fun CandidateAvatar(emoji: String) {
    Box(
        Modifier.size(DesktopDimens.avatarStrip)
            .clip(CircleShape)
            .background(Tokens.Panel2)
            .border(1.dp, Tokens.Line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji.ifBlank { "🙂" }, fontSize = 15.sp)
    }
}

@Composable
private fun StatChip(name: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(DesktopDimens.rCell)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) Tokens.Signature.copy(alpha = .16f) else Tokens.Panel2)
            .border(if (selected) 2.dp else 1.dp, if (selected) Tokens.Signature else Tokens.Line, shape)
            .clickable(onClick = onClick)
            .padding(vertical = DesktopDimens.gap3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            color = if (selected) Tokens.SignatureInk else Tokens.Ink,
        )
    }
}
