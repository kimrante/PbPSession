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
import com.pbp.app.ui.theme.PbpDimens
import com.pbp.app.ui.theme.PbpPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(private val app: PbpApp, private val roomId: Long) : ViewModel() {
    private val repo = app.repository

    companion object {
        const val PAGE_SIZE = 200
    }

    val room = repo.observeRoom(roomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 최근 PAGE_SIZE개부터 점진 로딩 — '이전 대화 불러오기'로 확장 */
    val limit = kotlinx.coroutines.flow.MutableStateFlow(PAGE_SIZE)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages = limit
        .flatMapLatest { repo.observeLatestMessages(roomId, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadOlder() {
        limit.value += PAGE_SIZE
    }

    /** 총 메시지 수 — '이전 대화 불러오기' 버튼은 실제 남은 게 있을 때만 (P3-7) */
    val totalCount = repo.observeMessageCount(roomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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

    /** 클립보드의 ccfolia식 캐릭터 코드로 새 캐릭터 생성 */
    fun createFromCode(imported: com.pbp.app.data.CharacterCodec.Imported) = viewModelScope.launch {
        repo.saveProfile(
            CharacterProfile(
                name = imported.name,
                stats = com.pbp.app.data.ProfileStats.encode(imported.stats),
            )
        )
    }

    fun exportTo(uri: Uri, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val html = LogExporter.buildHtml(
                    roomName = room.value?.name ?: "PbP",
                    roomIcon = room.value?.icon ?: "",
                    messages = repo.allMessages(roomId), // 내보내기는 화면 페이징과 무관하게 전체
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
    val totalCount by vm.totalCount.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val active = profiles.find { it.id == room?.activeProfileId }
    val themeColor = Color(room?.themeColor ?: PbpPalette.DEFAULT_THEME_COLOR)
    var editTarget by remember { mutableStateOf<Message?>(null) }
    var deleteTarget by remember { mutableStateOf<Message?>(null) }
    var actionTarget by remember { mutableStateOf<Message?>(null) } // 길게 누른 내 메시지
    var showAddProfile by remember { mutableStateOf(false) }

    // 읽음 처리: 입장 시 + 상대 메시지 수신 시에만 (내 발신마다 DB 쓰기 방지)
    val incomingCount = messages.count { it.incoming }
    LaunchedEffect(roomId, incomingCount) { vm.markRead() }

    // 새 메시지가 오면 최신 위치로 스크롤 (reverseLayout에서 index 0 = 최신).
    // 키는 최신 메시지 id만 — size를 키로 쓰면 '이전 대화 불러오기'나 삭제 때마다
    // 바닥으로 끌려간다. 위로 스크롤해 읽는 중에는 자동 스크롤하지 않는다. (P1-7)
    val listState = rememberLazyListState()
    LaunchedEffect(messages.firstOrNull()?.id) {
        if (messages.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
            listState.scrollToItem(0)
        }
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
        // consumeWindowInsets: Scaffold가 이미 적용한 내비게이션 바 패딩을
        // imePadding이 또 더하지 않도록 소비 처리 — 키보드와 입력줄 사이 틈 방지
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            RoomBackdrop(backgroundKey = room?.backgroundKey ?: PbpPalette.DEFAULT_BACKGROUND) {
                // ── 상단 바: 좌측 룸명+테마, 우측 내보내기·설정 (스펙 4장)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(PbpDimens.appBarHeight)
                        .background(Color.Black.copy(alpha = if (tokens.isDark) 0.45f else 0.06f))
                        .padding(horizontal = PbpDimens.sp2),
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
                            fontSize = 15.sp,
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
                val reversed = messages.asReversed()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = PbpDimens.sp4, vertical = PbpDimens.sp3),
                ) {
                    items(reversed.size, key = { reversed[it].id }) { revIdx ->
                        val message = reversed[revIdx]
                        // 같은 인물의 연속 메시지는 아바타·이름을 생략하고 간격을 좁힌다
                        val grouped = isContinuation(reversed.getOrNull(revIdx + 1), message)
                        Box(Modifier.padding(top = if (grouped) PbpDimens.sp1 else PbpDimens.sp3)) {
                            MessageBlock(
                                message = message,
                                grouped = grouped,
                                onLongPress = { actionTarget = it },
                            )
                        }
                    }
                    // 실제로 더 오래된 대화가 있을 때만 (총 개수 기준 — 유령 버튼 방지, P3-7)
                    if (messages.size < totalCount) {
                        item(key = "load-older") {
                            Box(
                                Modifier.fillMaxWidth().padding(top = PbpDimens.sp3),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "이전 대화 불러오기",
                                    fontSize = 11.sp,
                                    color = tokens.inkDim,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(Color.Black.copy(alpha = .35f))
                                        .clickable { vm.loadOlder() }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }

                // ── 입력 영역: 프로필 교체 스트립 + 잡담 토글 + 입력줄
                InputZone(
                    profiles = profiles,
                    activeId = active?.id,
                    themeColor = themeColor,
                    onSwitch = { vm.switchTo(it) },
                    onEditProfile = { nav.navigate("profile/${it.id}") },
                    onAddProfile = { showAddProfile = true },
                    onSend = { text, ooc -> vm.send(text, ooc) },
                    rule = room?.rule ?: com.pbp.app.dice.Rules.COC7,
                )
            }
        }
    }

    if (showAddProfile) {
        AddProfileDialog(
            onDismiss = { showAddProfile = false },
            onEmpty = {
                showAddProfile = false
                nav.navigate("profile/0")
            },
            onClipboard = {
                showAddProfile = false
                val clip = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager)
                    .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                val imported = clip?.let { com.pbp.app.data.CharacterCodec.parse(it) }
                if (imported != null) {
                    vm.createFromCode(imported)
                    Toast.makeText(
                        context,
                        "'${imported.name}' 캐릭터를 만들었습니다 (값 ${imported.stats.size}개)",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "클립보드에서 캐릭터 코드를 찾지 못했습니다",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }

    // 길게 누른 메시지의 편집·삭제 메뉴 (발신자 본인만 진입 가능)
    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text("메시지") },
            text = {
                Column {
                    Text(
                        "편집",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Pbp.colors.signature,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(PbpDimens.rCell))
                            .clickable {
                                editTarget = target
                                actionTarget = null
                            }
                            .padding(PbpDimens.sp3),
                    )
                    Text(
                        "삭제",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF6B6B),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(PbpDimens.rCell))
                            .clickable {
                                deleteTarget = target
                                actionTarget = null
                            }
                            .padding(PbpDimens.sp3),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { actionTarget = null }) { Text("취소") } },
        )
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

/** 같은 인물의 연속 말풍선인지 — 아바타·이름 생략과 간격 축소 판정 */
private fun isContinuation(prev: Message?, current: Message): Boolean {
    if (prev == null) return false
    fun isBubble(m: Message) = m.type == MessageType.TEXT && (!m.senderIsGm || m.isOoc)
    if (!isBubble(prev) || !isBubble(current)) return false
    return prev.senderName == current.senderName &&
        prev.incoming == current.incoming &&
        prev.isOoc == current.isOoc
}

/** 메시지 1건 렌더링 — GM 서술/인용 분리, 말풍선, 잡담, 다이스, 시스템 */
@Composable
private fun MessageBlock(
    message: Message,
    grouped: Boolean = false,
    onLongPress: (Message) -> Unit,
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
                    // 비교식 판정: 성공 = 파랑, 실패 = 빨강
                    when (message.diceOutcome) {
                        "success" -> {
                            Spacer(Modifier.width(8.dp))
                            Text("성공", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E9EFF))
                        }
                        "fail" -> {
                            Spacer(Modifier.width(8.dp))
                            Text("실패", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF6B6B))
                        }
                    }
                }
            }
        }
        // 서술자(GM) 발화: 명조 서술 문단 + " " 인용만 말풍선 분리 (스펙 4장)
        message.senderIsGm && !message.isOoc -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GmSpeech.split(message.body).forEach { part ->
                    when (part) {
                        is GmSpeech.Part.Narration -> NarrationBlock(message, part.text, onLongPress)
                        is GmSpeech.Part.Quote -> BubbleRow(
                            message = message,
                            overrideBody = part.text,
                            overrideName = "???",
                            overrideBubbleColor = PbpPalette.gmQuoteBubble,
                            onLongPress = onLongPress,
                        )
                    }
                }
            }
        }
        else -> BubbleRow(
            message = message,
            showHeader = !grouped,
            onLongPress = onLongPress,
        )
    }
}

/** GM 서술 문단 — 명조체 블록 (아바타·낙관 없이 문단만). 본인은 길게 눌러 편집·삭제 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NarrationBlock(
    message: Message,
    text: String,
    onLongPress: (Message) -> Unit,
) {
    val tokens = Pbp.colors
    // 좌우 여백은 메시지 목록의 contentPadding(16dp)에 맡긴다 — 별도 들여쓰기 없음
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        topStart = 4.dp,
                        topEnd = PbpDimens.rCard,
                        bottomEnd = PbpDimens.rCard,
                        bottomStart = 4.dp,
                    )
                )
                .background(tokens.narrBg)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { if (!message.incoming) onLongPress(message) },
                )
                .padding(horizontal = PbpDimens.sp4, vertical = PbpDimens.sp3),
        ) {
            MarkupText(
                text = text,
                fontSize = 13.sp,
                color = tokens.narrInk,
                rubyColor = tokens.signature,
                fontFamily = GowunBatang,
                lineHeight = 24.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = PbpDimens.sp2)) {
                val gmLabel = message.senderName
                    ?.let { if (it.startsWith("GM")) it else "GM $it" } ?: "GM"
                Text(
                    "$gmLabel · 서술",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.signature,
                )
                Spacer(Modifier.width(6.dp))
                Text(formatTime(message.createdAt), fontSize = 10.sp, color = tokens.inkDim)
                if (message.editedAt != null) {
                    Spacer(Modifier.width(4.dp))
                    Text("(수정됨)", fontSize = 10.sp, color = tokens.inkDim)
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 카카오톡형 좌/우 말풍선. 내 발신(incoming=false)은 오른쪽 정렬, 길게 눌러 편집·삭제 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BubbleRow(
    message: Message,
    overrideBody: String? = null,
    overrideName: String? = null,
    overrideBubbleColor: Long? = null,
    showHeader: Boolean = true, // false = 연속 메시지 (아바타·이름 생략)
    onLongPress: (Message) -> Unit,
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
        Row(horizontalArrangement = Arrangement.spacedBy(PbpDimens.sp2)) {
            if (mine) {
                // 내 메시지: 시간은 말풍선 왼쪽
                TimeStamp(message, alignEnd = true, Modifier.align(Alignment.Bottom))
            }
            if (!mine) {
                if (showHeader) {
                    Avatar(
                        emoji = message.senderEmoji,
                        imagePath = message.senderImagePath,
                        size = PbpDimens.avatarChat,
                        dimmed = message.isOoc,
                    )
                } else {
                    Box(Modifier.size(PbpDimens.avatarChat)) // 연속 메시지 — 자리만 유지
                }
            }
            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                if (showHeader) {
                    Text(
                        overrideName ?: message.senderName ?: "",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = nameColor,
                    )
                    Spacer(Modifier.height(PbpDimens.sp1))
                }
                val r = PbpDimens.rCard
                val shape = if (mine) {
                    RoundedCornerShape(topStart = r, topEnd = 4.dp, bottomEnd = r, bottomStart = r)
                } else {
                    RoundedCornerShape(topStart = 4.dp, topEnd = r, bottomEnd = r, bottomStart = r)
                }
                val bubbleModifier = Modifier
                    .widthIn(max = 240.dp)
                    .clip(shape)
                    .background(bubbleColor)
                    .then(
                        if (message.isOoc) {
                            Modifier.dashedBorder(Color.White.copy(alpha = .18f), PbpDimens.rCard)
                        } else Modifier
                    )
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { if (!message.incoming) onLongPress(message) },
                    )
                    .padding(horizontal = PbpDimens.sp3, vertical = PbpDimens.sp2)
                Box(bubbleModifier) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (message.isOoc) {
                            Text(
                                "잡담",
                                fontSize = 9.sp,
                                color = inkColor,
                                modifier = Modifier
                                    .padding(end = 5.dp)
                                    .border(1.dp, inkColor.copy(alpha = .4f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 5.dp),
                            )
                        }
                        MarkupText(
                            text = body,
                            fontSize = 13.sp,
                            color = inkColor,
                            rubyColor = inkColor.copy(alpha = .65f),
                            lineHeight = 20.sp,
                            fontWeight = if (message.isOoc) FontWeight.Normal else FontWeight.Medium,
                        )
                    }
                }
            }
            if (!mine) {
                // 남(또는 GM 인용) 메시지: 시간은 말풍선 오른쪽
                TimeStamp(message, alignEnd = false, Modifier.align(Alignment.Bottom))
            }
            if (mine) {
                if (showHeader) {
                    Avatar(
                        emoji = message.senderEmoji,
                        imagePath = message.senderImagePath,
                        size = PbpDimens.avatarChat,
                        dimmed = message.isOoc,
                    )
                } else {
                    Box(Modifier.size(PbpDimens.avatarChat)) // 연속 메시지 — 자리만 유지
                }
            }
        }
    }
}

/** 말풍선 곁 시간 + (수정됨) — 내 메시지는 왼쪽, 남의 메시지는 오른쪽에 붙는다 */
@Composable
private fun TimeStamp(message: Message, alignEnd: Boolean, modifier: Modifier = Modifier) {
    val tokens = Pbp.colors
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        if (message.editedAt != null) {
            Text("(수정됨)", fontSize = 9.sp, color = tokens.inkDim)
        }
        Text(formatTime(message.createdAt), fontSize = 10.sp, color = tokens.inkDim)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InputZone(
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
    var input by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var oocOn by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // 자동완성 채팅 팔레트 — 활성 캐릭터의 값 이름을 부분 입력하면 판정 매크로 추천
    val activeStats = remember(profiles, activeId) {
        profiles.find { it.id == activeId }
            ?.let { com.pbp.app.data.ProfileStats.decode(it.stats) } ?: emptyList()
    }
    val suggestions = remember(input, activeStats) {
        com.pbp.app.data.ProfileStats.paletteSuggestions(input, activeStats)
    }
    val onOocToggle = { oocOn = !oocOn }
    val onInputChange = { text: String -> input = text }
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (tokens.isDark) Color(0xE0090C11) else tokens.panel.copy(alpha = .93f))
            .padding(start = PbpDimens.sp4, end = PbpDimens.sp4, top = PbpDimens.sp2, bottom = PbpDimens.sp3),
    ) {
        // 프로필 교체 스트립 — 활성 프로필은 옐로 링 (스펙 4장)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(PbpDimens.sp3),
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
        Spacer(Modifier.height(PbpDimens.sp2))
        if (suggestions.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(PbpDimens.sp2)) {
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
                                val command = com.pbp.app.dice.Rules.judgeCommand(rule, name)
                                onSend("$command $name 판정", false)
                                input = ""
                            }
                            .padding(horizontal = PbpDimens.sp3, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.height(PbpDimens.sp2))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PbpDimens.sp2)) {
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

/** 캐릭터 추가 방식 선택 — 클립보드의 ccfolia식 캐릭터 코드 또는 빈 캐릭터 */
@Composable
private fun AddProfileDialog(
    onDismiss: () -> Unit,
    onEmpty: () -> Unit,
    onClipboard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("캐릭터 추가") },
        text = {
            Column {
                AddOptionRow(
                    title = "클립보드 코드로 생성",
                    subtitle = "복사해 둔 캐릭터 코드(JSON)의 이름·능력치를 값으로 자동 등록",
                    onClick = onClipboard,
                )
                AddOptionRow(
                    title = "빈 캐릭터 생성",
                    subtitle = "이름과 색만 정해 새로 만들기",
                    onClick = onEmpty,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun AddOptionRow(title: String, subtitle: String, onClick: () -> Unit) {
    val tokens = Pbp.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PbpDimens.rCell))
            .clickable(onClick = onClick)
            .padding(PbpDimens.sp3),
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tokens.signature)
        Text(subtitle, fontSize = 11.sp, color = tokens.inkDim)
    }
}

@Composable
private fun EditMessageDialog(original: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    // 회전에도 편집 중 텍스트 보존 (P2-4)
    var body by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(original) }
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
