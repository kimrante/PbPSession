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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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

    /**
     * 상대가 어디까지 읽었는지 — 화면이 열려 있는 동안만 구독한다.
     * 상대가 데스크톱이거나 로컬 전용 방이면 null이라 "읽음"을 표시하지 않는다.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val peerReadAt = room
        .flatMapLatest { repo.observePeerReadAt(it?.remoteId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun markRead() = viewModelScope.launch { repo.markRead(roomId) }

    fun send(text: String, isOoc: Boolean) = viewModelScope.launch {
        val sender = profiles.value.find { it.id == room.value?.activeProfileId }
        if (sender == null) {
            // 프로필 삭제 직후의 좁은 레이스 — 입력이 조용히 버려지지 않게 알린다 (L6)
            Toast.makeText(app, "발화 프로필이 없어 전송하지 못했습니다", Toast.LENGTH_SHORT).show()
            return@launch
        }
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

    fun createFromCode(imported: com.pbp.shared.CharacterCodec.Imported) =
        viewModelScope.launch { repo.createFromCode(imported) }

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
    val peerReadAt by vm.peerReadAt.collectAsState()
    val active = profiles.find { it.id == room?.activeProfileId }
    val themeColor = Color(room?.themeColor ?: PbpPalette.DEFAULT_THEME_COLOR)
    // 다이얼로그 대상은 메시지 id로 — 회전해도 유지된다 (N10)
    var editTargetId by rememberSaveable {
        mutableStateOf<Long?>(null)
    }
    var deleteTargetId by rememberSaveable {
        mutableStateOf<Long?>(null)
    }
    var actionTargetId by rememberSaveable {
        mutableStateOf<Long?>(null) // 길게 누른 내 메시지
    }
    // 컴포지션마다 O(N) 재스캔하지 않는다 (F3)
    val editTarget = remember(messages, editTargetId) { messages.find { it.id == editTargetId } }
    val deleteTarget = remember(messages, deleteTargetId) { messages.find { it.id == deleteTargetId } }
    val actionTarget = remember(messages, actionTargetId) { messages.find { it.id == actionTargetId } }
    var showAddProfile by rememberSaveable {
        mutableStateOf(false)
    }

    // 수정 작성 중 상대가 그 메시지를 삭제하면 다이얼로그가 무통보로 사라진다 —
    // 최소한 이유는 알린다 (L6)
    LaunchedEffect(editTargetId, editTarget == null, messages.isNotEmpty()) {
        if (editTargetId != null && editTarget == null && messages.isNotEmpty()) {
            Toast.makeText(context, "편집하려던 메시지가 삭제되었습니다", Toast.LENGTH_SHORT).show()
            editTargetId = null
        }
    }

    // 읽음 처리: 입장 시 + 상대 메시지 수신 시에만 (내 발신마다 DB 쓰기 방지)
    val incomingCount = remember(messages) { messages.count { it.incoming } }
    LaunchedEffect(roomId, incomingCount) { vm.markRead() }

    // 새 메시지가 오면 최신 위치로 스크롤 (reverseLayout에서 index 0 = 최신).
    // messages는 오래된 순이므로 최신은 last — first를 키로 쓰면 새 메시지가 와도
    // 키가 그대로라 이펙트가 아예 실행되지 않는다.
    val listState = rememberLazyListState()
    // 내 발신(메시지·판정)은 어디를 보고 있든 최신으로 내려간다. 전송 직후에는 아직
    // DB 반영 전이라, 플래그를 새 메시지가 실제로 도착할 때까지 유지한다 (N4)
    var pendingScrollToLatest by remember { mutableStateOf(false) }
    val latestMessageId = messages.lastOrNull()?.id
    // 여러 건이 한 배치로 도착하면(상대 판정의 TEXT+DICE 쌍, 백그라운드 복귀 후 몰아
    // 수신) 리스트가 보던 항목에 앵커되어 firstVisibleItemIndex가 도착 수만큼 커진다.
    // 직전 최신 메시지의 현재 위치로 '이번에 몇 건 추가됐는지'를 세어 보정한다 (P1-7 보강).
    var prevLatestId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(latestMessageId, pendingScrollToLatest) {
        if (latestMessageId == null) {
            prevLatestId = null
            return@LaunchedEffect
        }
        val prevIndex = prevLatestId?.let { id -> messages.indexOfLast { it.id == id } } ?: -1
        val appended = if (prevIndex >= 0) messages.size - 1 - prevIndex else messages.size
        prevLatestId = latestMessageId
        // 내 발신이면 무조건, 아니면 바닥 근처를 보고 있을 때만 따라간다 (P1-7)
        if (pendingScrollToLatest || listState.firstVisibleItemIndex <= appended + 1) {
            listState.scrollToItem(0)
            pendingScrollToLatest = false
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
                // ── 상단 바: 타이틀 묶음은 정중앙, 버튼은 좌우 끝 (목업 mockup-chat-header)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(PbpDimens.appBarHeight)
                        .background(Color.Black.copy(alpha = if (tokens.isDark) 0.45f else 0.06f))
                ) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = PbpDimens.gap2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Text("←", fontSize = 20.sp, color = tokens.ink)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            // 문서 프로바이더가 없는 기기에서 ActivityNotFoundException 방지 (C3)
                            runCatching { exportLauncher.launch("${room?.name ?: "PbP"}_log.html") }
                                .onFailure {
                                    Toast.makeText(context, "파일 저장 화면을 열 수 없습니다", Toast.LENGTH_SHORT).show()
                                }
                        }) {
                            Text("↓", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
                        }
                        IconButton(onClick = { nav.navigate(com.pbp.app.Routes.settings(roomId)) }) {
                            Text("⚙", fontSize = 18.sp, color = tokens.ink)
                        }
                    }
                    // 타이틀 묶음은 버튼 위에 겹쳐 화면 정중앙 — 좌우 인셋이 같아 중심이 흔들리지 않는다
                    Column(
                        Modifier
                            .align(Alignment.TopCenter)
                            .height(PbpDimens.appBarHeight)
                            .padding(horizontal = PbpDimens.titleInset),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            room?.name ?: "",
                            fontFamily = GowunBatang,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            lineHeight = 15.sp,
                            color = tokens.ink,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(PbpDimens.gap1))
                        Text(
                            if (room?.isMaster == true) "GM" else "PL",
                            fontSize = 11.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = tokens.inkSub,
                        )
                }
                }

                // ── 메시지 목록
                val reversed = messages.asReversed()
                // "읽음"은 상대가 읽은 내 메시지 중 가장 최신 1건에만 붙인다
                val readMarkId = peerReadAt?.let { readAt ->
                    messages.lastOrNull { !it.incoming && it.createdAt <= readAt }?.id
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap3),
                ) {
                    items(reversed.size, key = { reversed[it].id }) { revIdx ->
                        val message = reversed[revIdx]
                        // 같은 인물의 연속 메시지는 아바타·이름을 생략하고 간격을 좁힌다
                        val grouped = isContinuation(reversed.getOrNull(revIdx + 1), message)
                        // reverseLayout이라 revIdx-1이 더 나중(아래) 메시지 —
                        // 같은 사람이 같은 분에 이어 보냈으면 이 줄의 시간은 감춘다
                        val showTime = !sharesTimeLabel(message, reversed.getOrNull(revIdx - 1))
                        // 항목 '사이' 간격이라 top 한쪽만 준다 — 상하 대칭 원칙의 문서화된
                        // 예외 (연속 말풍선 gap1 / 그룹 사이 gap3, ui-guidelines 1장 원칙 3)
                        Box(Modifier.padding(top = if (grouped) PbpDimens.gap1 else PbpDimens.gap3)) {
                            MessageBlock(
                                message = message,
                                grouped = grouped,
                                showTime = showTime,
                                showRead = message.id == readMarkId,
                                themeColor = themeColor,
                                onLongPress = { actionTargetId = it.id },
                            )
                        }
                    }
                    // 실제로 더 오래된 대화가 있을 때만 (총 개수 기준 — 유령 버튼 방지, P3-7)
                    if (messages.size < totalCount) {
                        item(key = "load-older") {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = PbpDimens.gap3),
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
                                        // 터치용 칩 패딩 (ui-guidelines 5장 '필·칩')
                                        .padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2),
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
                    onEditProfile = { nav.navigate(com.pbp.app.Routes.profile(it.id)) },
                    onAddProfile = { showAddProfile = true },
                    onSend = { text, ooc ->
                        vm.send(text, ooc)
                        pendingScrollToLatest = true // 내 전송·판정은 항상 최신으로 스크롤
                    },
                    rule = room?.rule ?: com.pbp.shared.Rules.COC7,
                )
            }
        }
    }

    if (showAddProfile) {
        AddProfileDialog(
            onDismiss = { showAddProfile = false },
            onEmpty = {
                showAddProfile = false
                nav.navigate(com.pbp.app.Routes.profile(0))
            },
            onClipboard = {
                showAddProfile = false
                importCharacterFromClipboard(context) { vm.createFromCode(it) }
            },
        )
    }

    // 길게 누른 메시지의 편집·삭제 메뉴 (발신자 본인만 진입 가능)
    actionTarget?.let { target ->
        MessageActionDialog(
            message = target,
            // 편집·삭제는 내 메시지만, 복사는 누구 메시지든
            canModify = !target.incoming,
            onCopy = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("PbP 메시지", target.body)
                )
                actionTargetId = null
                Toast.makeText(context, "메시지를 복사했습니다", Toast.LENGTH_SHORT).show()
            },
            onEdit = {
                editTargetId = target.id
                actionTargetId = null
            },
            onDelete = {
                deleteTargetId = target.id
                actionTargetId = null
            },
            onDismiss = { actionTargetId = null },
        )
    }

    editTarget?.let { target ->
        EditMessageDialog(
            messageId = target.id,
            original = target.body,
            onDismiss = { editTargetId = null },
            onSave = { newBody ->
                vm.edit(target.id, newBody)
                editTargetId = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTargetId = null },
            title = { Text("메시지 삭제") },
            text = { Text("이 메시지를 삭제할까요? 공유된 방이면 상대 화면에서도 사라집니다.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target)
                    deleteTargetId = null
                }) { Text("삭제", color = Pbp.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteTargetId = null }) { Text("취소") } },
        )
    }
}
