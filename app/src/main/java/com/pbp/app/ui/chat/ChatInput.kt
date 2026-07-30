package com.pbp.app.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import com.pbp.app.PbpApp
import com.pbp.app.data.CharacterProfile
import com.pbp.app.data.Message
import com.pbp.app.data.MessageType
import com.pbp.app.export.LogExporter
import com.pbp.shared.GmSpeech
import com.pbp.app.ui.common.AddProfileDialog
import com.pbp.app.ui.common.Avatar
import com.pbp.app.ui.common.importCharacterFromClipboard
import com.pbp.app.ui.common.MarkupText
import com.pbp.app.ui.common.RoomBackdrop
import com.pbp.app.ui.common.dashedBorder
import com.pbp.app.ui.common.formatTime
import com.pbp.app.ui.theme.GowunBatang
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import com.pbp.app.ui.theme.PbpPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 입력 영역 — 프로필 스트립·판정 팔레트·잡담 토글·입력줄 (리뷰 B3) */

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InputZone(
    profiles: List<CharacterProfile>,
    activeId: Long?,
    themeColor: Color,
    onSwitch: (CharacterProfile) -> Unit,
    onEditProfile: (CharacterProfile) -> Unit,
    onAddProfile: () -> Unit,
    onSend: (String, Boolean) -> Unit,
    rule: String,
) {
    val tokens = Pbp.colors
    // 입력 상태는 여기(하위)에서만 — 키 입력마다 화면 전체가 리컴포즈되지 않도록.
    // rememberSaveable: 화면 회전에도 입력을 보존 (P2-4)
    var input by rememberSaveable { mutableStateOf("") }
    var oocOn by rememberSaveable { mutableStateOf(false) }
    // 자동완성 채팅 팔레트 — 활성 캐릭터의 값 이름을 부분 입력하면 판정 매크로 추천
    val activeStats = remember(profiles, activeId) {
        profiles.find { it.id == activeId }
            ?.let { com.pbp.shared.ProfileStats.decode(it.stats) } ?: emptyList()
    }
    val suggestions = remember(input, activeStats) {
        com.pbp.shared.ProfileStats.paletteSuggestions(input, activeStats)
    }
    val onOocToggle = { oocOn = !oocOn }
    val onInputChange = { text: String -> input = text }
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (tokens.isDark) Color(0xE0090C11) else tokens.panel.copy(alpha = .93f))
            .padding(start = PbpDimens.gap4, end = PbpDimens.gap4, top = PbpDimens.gap2, bottom = PbpDimens.gap3),
    ) {
        // 프로필 교체 스트립 — 활성 프로필은 옐로 링 (스펙 4장)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(PbpDimens.gap3),
            verticalAlignment = Alignment.Top,
        ) {
            items(profiles, key = { it.id }) { profile ->
                val on = profile.id == activeId
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.combinedClickable(
                        onClick = { onSwitch(profile) },
                        onLongClick = { onEditProfile(profile) },
                    ),
                ) {
                    Avatar(
                        emoji = profile.emoji,
                        imagePath = profile.imagePath,
                        size = PbpDimens.avatarStrip,
                        ringColor = if (on) tokens.signature else null,
                    )
                    Text(
                        profile.name,
                        fontSize = 10.sp,
                        color = if (on) tokens.signature else tokens.inkDim,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(PbpDimens.avatarStrip)
                            .dashedBorder(Color.White.copy(alpha = .3f), PbpDimens.avatarStrip / 2)
                            .clip(CircleShape)
                            .clickable(onClick = onAddProfile),
                        contentAlignment = Alignment.Center,
                    ) { Text("＋", color = tokens.inkDim, fontSize = 15.sp) }
                    Text("추가", fontSize = 10.sp, color = tokens.inkDim)
                }
            }
        }
        Spacer(Modifier.height(PbpDimens.gap2))
        if (suggestions.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(PbpDimens.gap2)) {
                items(suggestions, key = { it }) { name ->
                    Text(
                        "$name 판정",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.signature,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(tokens.signature.copy(alpha = .14f))
                            .border(1.dp, tokens.signature.copy(alpha = .4f), RoundedCornerShape(999.dp))
                            .clickable {
                                // 예: "1d100<={LUK} LUK 판정" — 무엇을 판정했는지 함께 남긴다
                                val command = com.pbp.shared.Rules.judgeCommand(rule, name)
                                onSend("$command $name 판정", false)
                                input = ""
                            }
                            .padding(horizontal = PbpDimens.gap3, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.height(PbpDimens.gap2))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PbpDimens.gap2)) {
            // 잡담 토글
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (oocOn) tokens.signature.copy(alpha = .16f) else Color.White.copy(alpha = .07f))
                    .border(
                        1.dp,
                        if (oocOn) tokens.signature.copy(alpha = .4f) else tokens.line,
                        RoundedCornerShape(999.dp),
                    )
                    .clickable(onClick = onOocToggle)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(width = 22.dp, height = 12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (oocOn) tokens.signature else Color.White.copy(alpha = .2f)),
                    contentAlignment = if (oocOn) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Box(
                        Modifier
                            .padding(2.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (oocOn) Color(0xFF1A1A1A) else Color.White)
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    "잡담",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (oocOn) tokens.signature else tokens.inkDim,
                )
            }
            val canSend = input.isNotBlank() && activeId != null
            val doSend = {
                if (canSend) {
                    onSend(input, oocOn)
                    input = ""
                }
            }
            TextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    // 물리 키보드에서 Ctrl+Enter로 바로 전송
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.Enter &&
                            event.isCtrlPressed &&
                            canSend
                        ) {
                            doSend()
                            true
                        } else false
                    },
                placeholder = {
                    Text(
                        if (oocOn) "잡담으로 보내기…" else "**굵게** · |等臺《등대》 · 1d100",
                        fontSize = 12.sp,
                        color = tokens.inkDim,
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = .08f),
                    unfocusedContainerColor = Color.White.copy(alpha = .08f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(PbpDimens.rCell),
                maxLines = 4,
            )
            // 전송 버튼 — 방 테마 컬러 적용 (스펙 5장)
            Box(
                Modifier
                    .size(PbpDimens.touchTarget)
                    .clip(RoundedCornerShape(PbpDimens.rCell))
                    .background(if (canSend) themeColor else themeColor.copy(alpha = .35f))
                    .clickable(enabled = canSend, onClick = doSend),
                contentAlignment = Alignment.Center,
            ) { Text("➤", fontSize = 15.sp, color = Color(0xFF0D1420)) }
        }
    }
}
