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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.pbp.app.text.GmSpeech
import com.pbp.app.ui.common.Avatar
import com.pbp.app.ui.common.MarkupText
import com.pbp.app.ui.common.RoomBackdrop
import com.pbp.app.ui.common.dashedBorder
import com.pbp.app.ui.common.formatTime
import com.pbp.app.ui.theme.GowunBatang
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(private val app: PbpApp, private val roomId: Long) : ViewModel() {
    private val repo = app.repository

    val room = repo.observeRoom(roomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messages = repo.observeMessages(roomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val profiles = repo.observeProfilesForRoom(roomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun markRead() = viewModelScope.launch { repo.markRead(roomId) }

    fun send(text: String, isOoc: Boolean) = viewModelScope.launch {
        val sender = profiles.value.find { it.id == room.value?.activeProfileId } ?: return@launch
        repo.sendMessage(roomId, sender, text, isOoc)
        repo.markRead(roomId)
    }

    fun switchTo(profile: CharacterProfile) = viewModelScope.launch {
        if (room.value?.activeProfileId == profile.id) return@launch
        repo.switchProfile(roomId, profile)
    }

    fun edit(messageId: Long, body: String) = viewModelScope.launch {
        repo.editMessage(messageId, body)
    }

    fun delete(message: Message) = viewModelScope.launch { repo.deleteMessage(message) }

    fun exportTo(uri: Uri, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val html = LogExporter.buildHtml(
                    roomName = room.value?.name ?: "PbP",
                    roomIcon = room.value?.icon ?: "",
                    messages = messages.value,
                )
                app.contentResolver.openOutputStream(uri)!!.use { it.write(html.toByteArray()) }
            }.isSuccess
        }
        onResult(ok)
    }
}

@Composable
fun ChatScreen(nav: NavController, roomId: Long) {
    val context = LocalContext.current
    val app = context.applicationContext as PbpApp
    val vm: ChatViewModel = viewModel(key = "chat-$roomId", factory = viewModelFactory {
        initializer { ChatViewModel(app, roomId) }
    })
    val tokens = Pbp.colors
    val room by vm.room.collectAsState()
    val messages by vm.messages.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val active = profiles.find { it.id == room?.activeProfileId }
    val themeColor = Color(room?.themeColor ?: PbpPalette.DEFAULT_THEME_COLOR)
    var input by remember { mutableStateOf("") }
    var oocOn by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Message?>(null) }
    var deleteTarget by remember { mutableStateOf<Message?>(null) }

    // 방에 들어와 있는 동안은 읽음 처리 (미확인 배지·푸시 기준)
    LaunchedEffect(roomId, messages.size) { vm.markRead() }

    // 새 메시지가 오면 최신 위치로 스크롤 (reverseLayout에서 index 0 = 최신)
    val listState = rememberLazyListState()
    LaunchedEffect(messages.firstOrNull()?.id, messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(0)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        if (uri != null) vm.exportTo(uri) { ok ->
            Toast.makeText(
                context,
                if (ok) "HTML 로그를 저장했습니다" else "저장에 실패했습니다",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Scaffold(containerColor = tokens.bg) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            RoomBackdrop(backgroundKey = room?.backgroundKey ?: PbpPalette.DEFAULT_BACKGROUND) {
                // ── 상단 바: 좌측 룸명+테마, 우측 내보내기·설정 (스펙 4장)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = if (tokens.isDark) 0.45f else 0.06f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Text("←", fontSize = 20.sp, color = tokens.ink)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            room?.name ?: "",
                            fontFamily = GowunBatang,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.5.sp,
                            color = tokens.ink,
                            maxLines = 1,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(themeColor)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                if (room?.isMaster == true) "마스터" else "참여자",
                                fontSize = 10.sp,
                                color = tokens.inkDim,
                            )
                        }
                    }
                    IconButton(onClick = { exportLauncher.launch("${room?.name ?: "PbP"}_log.html") }) {
                        Text("↓", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
                    }
                    IconButton(onClick = { nav.navigate("settings/$roomId") }) {
                        Text("⚙", fontSize = 17.sp, color = tokens.ink)
                    }
                }

                // ── 메시지 목록
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
                ) {
                    items(messages.asReversed(), key = { it.id }) { message ->
                        MessageBlock(
                            message = message,
                            onEdit = { editTarget = it },
                            onDelete = { deleteTarget = it },
                        )
                    }
                }

                // ── 입력 영역: 프로필 교체 스트립 + 잡담 토글 + 입력줄
                InputZone(
                    profiles = profiles,
                    activeId = active?.id,
                    themeColor = themeColor,
                    input = input,
                    onInputChange = { input = it },
                    oocOn = oocOn,
                    onOocToggle = { oocOn = !oocOn },
                    onSwitch = { vm.switchTo(it) },
                    onEditProfile = { nav.navigate("profile/${it.id}") },
                    onAddProfile = { nav.navigate("profile/0") },
                    onSend = {
                        vm.send(input, oocOn)
                        input = ""
                    },
                )
            }
        }
    }

    editTarget?.let { target ->
        EditMessageDialog(
            original = target.body,
            onDismiss = { editTarget = null },
            onSave = { newBody ->
                vm.edit(target.id, newBody)
                editTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("메시지 삭제") },
            text = { Text("이 메시지를 삭제할까요? 공유된 방이면 상대 화면에서도 사라집니다.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target)
                    deleteTarget = null
                }) { Text("삭제", color = Pbp.colors.signature) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } },
        )
    }
}

/** 메시지 1건 렌더링 — GM 서술/인용 분리, 말풍선, 잡담, 다이스, 시스템 */
@Composable
private fun MessageBlock(
    message: Message,
    onEdit: (Message) -> Unit,
    onDelete: (Message) -> Unit,
) {
    val tokens = Pbp.colors
    when {
        message.type == MessageType.SYSTEM -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(color = Color.Black.copy(alpha = .35f), shape = RoundedCornerShape(999.dp)) {
                    Text(
                        message.body,
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = .6f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                    )
                }
            }
        }
        message.type == MessageType.DICE -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = .5f))
                        .border(1.dp, tokens.signature.copy(alpha = .35f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🎲", fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${message.diceExpr} → ${message.body}",
                        fontSize = 11.sp,
                        color = Color(0xFFFFE9AE),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        // 서술자(GM) 발화: 명조 서술 문단 + " " 인용만 말풍선 분리 (스펙 4장)
        message.senderIsGm && !message.isOoc -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GmSpeech.split(message.body).forEach { part ->
                    when (part) {
                        is GmSpeech.Part.Narration -> NarrationBlock(message, part.text, onEdit, onDelete)
                        is GmSpeech.Part.Quote -> BubbleRow(
                            message = message,
                            overrideBody = part.text,
                            overrideName = "???",
                            overrideBubbleColor = PbpPalette.gmQuoteBubble,
                            anonymous = true, // GM 정체(프로필 이미지) 비노출 — 익명 敍 아바타
                            onEdit = onEdit,
                            onDelete = onDelete,
                        )
                    }
                }
            }
        }
        else -> BubbleRow(message = message, onEdit = onEdit, onDelete = onDelete)
    }
}

/** GM 서술 문단 — 옐로 낙관(敍)이 찍힌 명조체 블록 */
@Composable
private fun NarrationBlock(
    message: Message,
    text: String,
    onEdit: (Message) -> Unit,
    onDelete: (Message) -> Unit,
) {
    val tokens = Pbp.colors
    Box(Modifier.padding(horizontal = 8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 4.dp))
                .background(tokens.narrBg)
                .border(
                    1.dp, tokens.signature.copy(alpha = .0f),
                    RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 4.dp),
                )
                .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
        ) {
            MarkupText(
                text = text,
                fontSize = 13.sp,
                color = tokens.narrInk,
                rubyColor = tokens.signature,
                fontFamily = GowunBatang,
                lineHeight = 24.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 7.dp)) {
                val gmLabel = message.senderName
                    ?.let { if (it.startsWith("GM")) it else "GM $it" } ?: "GM"
                Text(
                    "$gmLabel · 서술",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.signature,
                )
                Spacer(Modifier.width(6.dp))
                Text(formatTime(message.createdAt), fontSize = 9.sp, color = tokens.inkDim)
                if (message.editedAt != null) {
                    Spacer(Modifier.width(4.dp))
                    Text("(수정됨)", fontSize = 9.sp, color = tokens.inkDim)
                }
                Spacer(Modifier.weight(1f))
                MiniActions(message, onEdit, onDelete)
            }
        }
        // 옐로 낙관 + 왼쪽 강조선
        Box(
            Modifier
                .size(20.dp)
                .align(Alignment.TopStart)
                .clip(RoundedCornerShape(4.dp))
                .background(tokens.signature),
            contentAlignment = Alignment.Center,
        ) {
            Text("敍", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
        }
    }
}

/** 카카오톡형 좌/우 말풍선. 내 발신(incoming=false)은 오른쪽 정렬 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BubbleRow(
    message: Message,
    overrideBody: String? = null,
    overrideName: String? = null,
    overrideBubbleColor: Long? = null,
    anonymous: Boolean = false,
    onEdit: (Message) -> Unit,
    onDelete: (Message) -> Unit,
) {
    val tokens = Pbp.colors
    // GM 인용("???")은 극중 화자이므로 항상 상대 측(왼쪽)에 표시
    val mine = !message.incoming && overrideName == null
    val body = overrideBody ?: message.body
    val bubbleColor = when {
        message.isOoc -> tokens.chatterBubble
        else -> Color(overrideBubbleColor ?: message.senderBubbleColor ?: PbpPalette.bubblePresets.first())
    }
    val nameColor = when {
        message.isOoc -> tokens.inkDim
        overrideName != null -> tokens.signature
        message.senderNameColor != null -> Color(message.senderNameColor)
        else -> tokens.ink
    }
    val inkColor = if (message.isOoc) tokens.chatterInk else tokens.bubbleInk

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (!mine) {
                if (anonymous) AnonymousAvatar() else Avatar(
                    emoji = message.senderEmoji,
                    imagePath = message.senderImagePath,
                    size = 38.dp,
                    dimmed = message.isOoc,
                )
            }
            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                Text(
                    overrideName ?: message.senderName ?: "",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = nameColor,
                )
                Spacer(Modifier.height(3.dp))
                val shape = if (mine) {
                    RoundedCornerShape(topStart = 15.dp, topEnd = 4.dp, bottomEnd = 15.dp, bottomStart = 15.dp)
                } else {
                    RoundedCornerShape(topStart = 4.dp, topEnd = 15.dp, bottomEnd = 15.dp, bottomStart = 15.dp)
                }
                val bubbleModifier = Modifier
                    .widthIn(max = 240.dp)
                    .clip(shape)
                    .background(bubbleColor)
                    .then(
                        if (message.isOoc) {
                            Modifier.dashedBorder(Color.White.copy(alpha = .18f), 15.dp)
                        } else Modifier
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                Box(bubbleModifier) {
                    Row {
                        if (message.isOoc) {
                            Text(
                                "잡담",
                                fontSize = 8.5.sp,
                                color = inkColor,
                                modifier = Modifier
                                    .padding(end = 5.dp, top = 2.dp)
                                    .border(1.dp, inkColor.copy(alpha = .4f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 5.dp),
                            )
                        }
                        MarkupText(
                            text = body,
                            fontSize = 12.5.sp,
                            color = inkColor,
                            rubyColor = inkColor.copy(alpha = .65f),
                            lineHeight = 19.sp,
                            fontWeight = if (message.isOoc) FontWeight.Normal else FontWeight.Medium,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    if (mine) MiniActions(message, onEdit, onDelete)
                    Spacer(Modifier.width(5.dp))
                    Text(formatTime(message.createdAt), fontSize = 9.sp, color = tokens.inkDim)
                    if (message.editedAt != null) {
                        Spacer(Modifier.width(4.dp))
                        Text("(수정됨)", fontSize = 9.sp, color = tokens.inkDim)
                    }
                    Spacer(Modifier.width(5.dp))
                    if (!mine) MiniActions(message, onEdit, onDelete)
                }
            }
            if (mine) {
                Avatar(
                    emoji = message.senderEmoji,
                    imagePath = message.senderImagePath,
                    size = 38.dp,
                    dimmed = message.isOoc,
                )
            }
        }
    }
}

/** GM 인용("???") 전용 익명 아바타 — GM의 실제 프로필 이미지를 노출하지 않는다 */
@Composable
private fun AnonymousAvatar() {
    Box(
        Modifier
            .size(38.dp)
            .border(1.5.dp, Color.White.copy(alpha = .22f), CircleShape)
            .clip(CircleShape)
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(Color(0xFF39445A), Color(0xFF1C2330))
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text("敍", fontSize = 15.sp, fontFamily = GowunBatang, color = Color(0xFFFFD972))
    }
}

/** 말풍선 곁 연필·휴지통 마이크로 버튼 */
@Composable
private fun MiniActions(message: Message, onEdit: (Message) -> Unit, onDelete: (Message) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier
                .size(17.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.Black.copy(alpha = .38f))
                .clickable { onEdit(message) },
            contentAlignment = Alignment.Center,
        ) { Text("✎", fontSize = 9.sp, color = Color.White.copy(alpha = .75f)) }
        Box(
            Modifier
                .size(17.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.Black.copy(alpha = .38f))
                .clickable { onDelete(message) },
            contentAlignment = Alignment.Center,
        ) { Text("🗑", fontSize = 8.sp) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InputZone(
    profiles: List<CharacterProfile>,
    activeId: Long?,
    themeColor: Color,
    input: String,
    onInputChange: (String) -> Unit,
    oocOn: Boolean,
    onOocToggle: () -> Unit,
    onSwitch: (CharacterProfile) -> Unit,
    onEditProfile: (CharacterProfile) -> Unit,
    onAddProfile: () -> Unit,
    onSend: () -> Unit,
) {
    val tokens = Pbp.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (tokens.isDark) Color(0xE0090C11) else tokens.panel.copy(alpha = .93f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // 프로필 교체 스트립 — 활성 프로필은 옐로 링 (스펙 4장)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                        size = 34.dp,
                        ringColor = if (on) tokens.signature else null,
                    )
                    Text(
                        profile.name,
                        fontSize = 8.5.sp,
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
                            .size(34.dp)
                            .dashedBorder(Color.White.copy(alpha = .3f), 17.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onAddProfile),
                        contentAlignment = Alignment.Center,
                    ) { Text("＋", color = tokens.inkDim, fontSize = 15.sp) }
                    Text("추가", fontSize = 8.5.sp, color = tokens.inkDim)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
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
                    .padding(horizontal = 9.dp, vertical = 5.dp),
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
            TextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
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
                shape = RoundedCornerShape(14.dp),
                maxLines = 4,
            )
            // 전송 버튼 — 방 테마 컬러 적용 (스펙 5장)
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (input.isNotBlank()) themeColor else themeColor.copy(alpha = .35f))
                    .clickable(enabled = input.isNotBlank(), onClick = onSend),
                contentAlignment = Alignment.Center,
            ) { Text("➤", fontSize = 15.sp, color = Color(0xFF0D1420)) }
        }
    }
}

@Composable
private fun EditMessageDialog(original: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var body by remember { mutableStateOf(original) }
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
