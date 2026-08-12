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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.app.ui.common.Avatar
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import com.pbp.shared.Rules

/** 판정 요청 후보 — 내 캐릭터와 상대 캐릭터를 같은 모양으로 다룬다 (J3) */
data class JudgeCandidate(
    /**
     * 그 캐릭터의 고유 id. 구버전 상대의 캐릭터에는 없다(null) — 그때만 이름으로
     * 다루고, 요청에도 id 없이 나간다(받는 쪽이 이름으로 되돌아간다).
     */
    val id: String?,
    val name: String,
    val emoji: String,
    val nameColor: Long?,
    /** 값 **이름**만. 숫자는 굴리는 쪽 기기에만 있다 */
    val stats: List<String>,
)

/**
 * 판정 요청 시트 — 대상 고르기 → 값 고르기, **한 시트 안에서** 단계를 전환한다
 * (시트를 두 개 띄우면 뒤로 가기가 꼬인다).
 *
 * 값은 그 캐릭터가 가진 목록에서 고르거나, 없으면 직접 적을 수 있다. 직접 적은 값은
 * 대상자가 누를 때 그 기기에서 값을 물어보고 프로필에 채워 넣는다 (J6).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun JudgeRequestSheet(
    candidates: List<JudgeCandidate>,
    rule: String,
    onDismiss: () -> Unit,
    onSend: (targetId: String?, targetName: String, statName: String) -> Unit,
) {
    val tokens = Pbp.colors
    // 고른 대상은 **목록에서의 자리**로 들고 있는다. 이름으로 들고 있으면 같은 이름이
    // 둘일 때 어느 쪽을 골랐는지 구분할 수 없다(고유 id 없는 구버전 상대도 있다)
    var targetIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var statName by rememberSaveable { mutableStateOf<String?>(null) }
    var manualStat by rememberSaveable { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val target = targetIndex?.let { candidates.getOrNull(it) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = tokens.panel,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(horizontal = PbpDimens.gap4)
                .padding(bottom = PbpDimens.gap6),
        ) {
            Text(
                "판정 요청",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = tokens.ink,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(PbpDimens.gap1))
            Text(
                if (target == null) "누구에게 요청할까요?" else "어떤 값으로 판정할까요?",
                fontSize = 11.sp,
                color = tokens.inkDim,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(PbpDimens.gap3))

            if (target == null) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    candidates.forEachIndexed { index, candidate ->
                        CandidateRow(candidate) { targetIndex = index }
                    }
                    if (candidates.isEmpty()) {
                        Text(
                            "요청할 캐릭터가 없습니다",
                            fontSize = 13.sp,
                            color = tokens.inkDim,
                            modifier = Modifier.padding(vertical = PbpDimens.gap4),
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(emoji = target.emoji, imagePath = null, size = PbpDimens.avatarStrip)
                    Spacer(Modifier.width(PbpDimens.gap2))
                    Text(
                        target.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = target.nameColor?.let {
                            androidx.compose.ui.graphics.Color(
                                com.pbp.app.ui.theme.PbpPalette.nameColorForLight(it)
                            )
                        } ?: tokens.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "대상 바꾸기",
                        fontSize = 11.sp,
                        color = tokens.signatureInk,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable {
                                targetIndex = null
                                statName = null
                            }
                            .padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2),
                    )
                }
                Spacer(Modifier.height(PbpDimens.gap3))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    // 두 칸씩 — 목업 C컷
                    target.stats.chunked(2).forEach { pair ->
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = PbpDimens.gap2),
                            horizontalArrangement = Arrangement.spacedBy(PbpDimens.gap2),
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
                    // 값이 없거나 목록에 없는 값을 걸고 싶을 때 — 직접 적는다
                    OutlinedTextField(
                        value = manualStat,
                        onValueChange = {
                            manualStat = it
                            statName = it.trim().ifEmpty { null }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("값 이름 직접 입력 (예: 은신)", fontSize = 10.sp) },
                        singleLine = true,
                    )
                }
                Spacer(Modifier.height(PbpDimens.gap3))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            statName?.let { "${target.name}의 $it 값으로 1d100 하향 판정" }
                                ?: "값을 고르거나 직접 적어 주세요",
                            fontSize = 11.sp,
                            color = tokens.inkDim,
                        )
                        Text(Rules.label(rule), fontSize = 10.sp, color = tokens.inkDim)
                    }
                    val enabled = statName != null
                    Text(
                        "보내기",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        color = if (enabled) tokens.onSignature else tokens.ink.copy(alpha = .34f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (enabled) tokens.signature else tokens.ink.copy(alpha = .08f)
                            )
                            .clickable(enabled = enabled) {
                                onSend(target.id, target.name, statName!!)
                            }
                            .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap3),
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: JudgeCandidate, onClick: () -> Unit) {
    val tokens = Pbp.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PbpDimens.rCell))
            .clickable(onClick = onClick)
            .padding(PbpDimens.gap3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(emoji = candidate.emoji, imagePath = null, size = PbpDimens.avatarStrip)
        Spacer(Modifier.width(PbpDimens.gap3))
        Column(Modifier.weight(1f)) {
            Text(
                candidate.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = candidate.nameColor?.let {
                    androidx.compose.ui.graphics.Color(
                        com.pbp.app.ui.theme.PbpPalette.nameColorForLight(it)
                    )
                } ?: tokens.ink,
            )
            Text(
                // 값이 없어도 숨기지 않는다 — 직접 적어 걸 수 있다
                if (candidate.stats.isEmpty()) "값 없음 · 직접 적어 요청"
                else "값 ${candidate.stats.size}개",
                fontSize = 11.sp,
                color = tokens.inkDim,
            )
        }
        Text("›", fontSize = 15.sp, color = tokens.inkDim)
    }
}

@Composable
private fun StatChip(name: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = Pbp.colors
    Box(
        modifier
            .clip(RoundedCornerShape(PbpDimens.rCell))
            .background(if (selected) tokens.signature.copy(alpha = .16f) else tokens.panel2)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) tokens.signature else tokens.line,
                RoundedCornerShape(PbpDimens.rCell),
            )
            .clickable(onClick = onClick)
            .padding(vertical = PbpDimens.gap3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) tokens.signatureInk else tokens.ink,
        )
    }
}
