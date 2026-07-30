package com.pbp.app.ui.roomlist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import com.pbp.app.PbpApp
import com.pbp.app.R
import com.pbp.app.data.ChatRoom
import com.pbp.app.data.Images
import com.pbp.app.data.Message
import com.pbp.app.data.MessageType
import com.pbp.app.data.OwnerProfile
import com.pbp.app.dice.Rules
import com.pbp.app.ui.common.HexColorDialog
import com.pbp.app.ui.common.relativeTime
import com.pbp.app.ui.theme.GowunBatang
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import com.pbp.app.ui.theme.PbpPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RoomListViewModel(private val app: PbpApp) : ViewModel() {
    private val repo = app.repository

    val rooms = repo.observeRooms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val previews = repo.observeLastMessages()
        .map { list -> list.associateBy { it.roomId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val unread = repo.observeUnreadCounts()
        .map { list -> list.associate { it.roomId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun createRoom(name: String, rule: String) = viewModelScope.launch {
        repo.createRoom(name.trim().ifEmpty { "새 세션" }, rule = rule)
    }

    fun deleteRoom(room: ChatRoom) = viewModelScope.launch { repo.deleteRoom(room) }

    fun joinRoom(code: String, onResult: (Long?) -> Unit) = viewModelScope.launch {
        onResult(app.syncManager.joinRoom(code.trim().uppercase()))
    }

    /** 프로필 관리 — 오너 외 모든 프로필 (GM·방 귀속 포함) */
    val allProfiles = repo.observeAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 클립보드의 ccfolia식 캐릭터 코드로 새 캐릭터 생성 (전역, 채팅 화면과 동일 규칙) */
    fun createFromCode(imported: com.pbp.app.data.CharacterCodec.Imported) = viewModelScope.launch {
        repo.saveProfile(
            com.pbp.app.data.CharacterProfile(
                name = imported.name,
                stats = com.pbp.app.data.ProfileStats.encode(imported.stats),
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoomListScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as PbpApp
    val vm: RoomListViewModel = viewModel(factory = viewModelFactory {
        initializer { RoomListViewModel(app) }
    })
    val context = LocalContext.current
    val tokens = Pbp.colors
    val rooms by vm.rooms.collectAsState()
    val previews by vm.previews.collectAsState()
    val unread by vm.unread.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var showFont by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ChatRoom?>(null) }
    // 오너 프로필 미설정이면 먼저 설정하게 한다 (첫 실행 포함)
    var showOwner by remember { mutableStateOf(!OwnerProfile.isSet) }
    // 프로필 관리 — 오너 아이콘을 누르면 전체 프로필 목록
    var showManager by remember { mutableStateOf(false) }
    var showAddProfile by remember { mutableStateOf(false) }
    val allProfiles by vm.allProfiles.collectAsState()

    Scaffold(
        containerColor = tokens.bg,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreate = true },
                containerColor = tokens.signature,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.rotate(-4f),
            ) { Text("＋", fontSize = 24.sp, color = Color(0xFF1A1A1A)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 헤더 — 채팅 상단 바와 같은 규격(56dp): 로고+타이틀 묶음이 화면 정중앙,
            // 버튼은 우측 끝 (목업 mockup-home-header)
            Box(Modifier.fillMaxWidth().height(PbpDimens.appBarHeight)) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = PbpDimens.sp4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    CompactBarButton("Aa") { showFont = true }
                    CompactBarButton("참여") { showJoin = true }
                    Spacer(Modifier.width(PbpDimens.sp1))
                    // 프로필 관리 — 오너 프로필 아이콘 모양, 초대코드(참여) 오른편
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(OwnerProfile.color))
                            .combinedClickable(onClick = { showManager = true }),
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
                                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                color = Color(0xFF10151C),
                            )
                        }
                    }
                }
                // 로고+워드마크 1행, 부제 2행 — 묶음 전체가 화면 정중앙 (좌우 인셋 동일)
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .height(PbpDimens.appBarHeight)
                        .padding(horizontal = PbpDimens.titleInsetWide),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PbpDimens.sp2),
                    ) {
                        // 새 앱 아이콘(시안 02 '포스트잇')과 동일한 옐로 타일 + 잉크 d10
                        Box(
                            Modifier
                                .size(PbpDimens.logoTile)
                                .clip(RoundedCornerShape(7.dp))
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFFFFD05C), Color(0xFFEFB945)))
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_logo_d10),
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                        Text(
                            "PbP",
                            fontFamily = GowunBatang,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            lineHeight = 18.sp,
                            color = tokens.titleAccent,
                        )
                    }
                    Spacer(Modifier.height(PbpDimens.sp1))
                    Text(
                        "진행 중인 세션 ${rooms.size}",
                        fontSize = 11.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = tokens.inkSub,
                    )
                }
            }

            if (rooms.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎲", fontSize = 40.sp)
                        Spacer(Modifier.size(8.dp))
                        Text("첫 세션을 만들어보세요", color = tokens.inkDim)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = PbpDimens.sp4, end = PbpDimens.sp4, bottom = 88.dp, // FAB 높이 + 여백×2
                    ),
                    verticalArrangement = Arrangement.spacedBy(PbpDimens.sp3),
                ) {
                    items(rooms, key = { it.id }) { room ->
                        RoomCard(
                            room = room,
                            preview = previews[room.id],
                            unreadCount = unread[room.id] ?: 0,
                            onClick = { nav.navigate("chat/${room.id}") },
                            onLongClick = { deleteTarget = room },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateRoomDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, rule ->
                vm.createRoom(name, rule)
                showCreate = false
            },
        )
    }

    if (showJoin) {
        JoinRoomDialog(
            onDismiss = { showJoin = false },
            onJoin = { code, onDone ->
                vm.joinRoom(code) { roomId ->
                    onDone()
                    if (roomId != null) {
                        showJoin = false
                        nav.navigate("chat/$roomId")
                    } else {
                        android.widget.Toast.makeText(
                            context, "방을 찾지 못했습니다. 코드와 네트워크를 확인해주세요.",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    if (showFont) {
        FontSettingDialog(onDismiss = { showFont = false })
    }

    if (showOwner) {
        OwnerProfileDialog(
            forced = !OwnerProfile.isSet, // 미설정이면 저장 전에는 닫을 수 없다
            onClose = { showOwner = false },
        )
    }

    if (showManager) {
        ProfileManagerDialog(
            profiles = allProfiles,
            roomNames = rooms.associate { it.id to it.name },
            onDismiss = { showManager = false },
            onOwner = {
                showManager = false
                showOwner = true
            },
            onProfile = { id ->
                showManager = false
                nav.navigate("profile/$id")
            },
            onAdd = {
                showManager = false
                showAddProfile = true
            },
        )
    }

    if (showAddProfile) {
        com.pbp.app.ui.common.AddProfileDialog(
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
                    android.widget.Toast.makeText(
                        context,
                        "'${imported.name}' 캐릭터를 만들었습니다 (값 ${imported.stats.size}개)",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        context, "클립보드에서 캐릭터 코드를 찾지 못했습니다",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }

    deleteTarget?.let { room ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("방 삭제") },
            text = { Text("'${room.name}' 방과 모든 메시지, GM 프로필이 삭제됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteRoom(room)
                    deleteTarget = null
                }) { Text("삭제", color = Pbp.colors.signature) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } },
        )
    }
}

/**
 * 상단 바의 좁은 텍스트 버튼 — 기본 TextButton은 최소 폭이 넓어 중앙 타이틀 묶음의
 * 자리를 잠식한다. 터치 높이(40dp)는 유지하면서 폭만 콘텐츠에 맞춘다.
 */
@Composable
private fun CompactBarButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(PbpDimens.touchTarget)
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = PbpDimens.sp2),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Pbp.colors.inkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomCard(
    room: ChatRoom,
    preview: Message?,
    unreadCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val tokens = Pbp.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PbpDimens.rCard))
            .background(if (tokens.isDark) Color(0x09FFFFFF) else tokens.panel)
            .border(1.dp, tokens.line, RoundedCornerShape(PbpDimens.rCard))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = PbpDimens.sp4, vertical = PbpDimens.sp3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 썸네일: 방 배경(프리셋 그라데이션 또는 갤러리 이미지 크롭) + 테마 컬러 점
        Box(Modifier.size(48.dp)) {
            val preset = PbpPalette.backgroundPresets[room.backgroundKey]
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(PbpDimens.rCell))
                    .then(
                        if (preset != null) {
                            Modifier.background(
                                Brush.linearGradient(
                                    listOf(Color(preset.first), Color(preset.second))
                                )
                            )
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // 썸네일은 방 배경만 — 아이콘 글자는 표시하지 않는다
                if (preset == null) {
                    coil3.compose.AsyncImage(
                        model = java.io.File(room.backgroundKey),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
            }
            Box(
                Modifier
                    .size(14.dp)
                    .offset(x = 38.dp, y = 37.dp)
                    .border(3.dp, tokens.bg, CircleShape)
                    .clip(CircleShape)
                    .background(Color(room.themeColor))
            )
        }
        Spacer(Modifier.width(PbpDimens.sp3))
        Column(Modifier.weight(1f)) {
            Text(room.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
            Text(
                previewText(preview),
                fontSize = 11.sp,
                color = tokens.inkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                preview?.let { relativeTime(it.createdAt) } ?: "",
                fontSize = 10.sp,
                color = tokens.inkDim,
            )
            if (unreadCount > 0) {
                Spacer(Modifier.size(4.dp))
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(tokens.signature)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        "$unreadCount",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1A1A1A),
                    )
                }
            }
        }
    }
}

private fun previewText(message: Message?): String = when {
    message == null -> "아직 메시지가 없습니다"
    message.isOoc -> "잡담 · ${message.body}"
    message.type == MessageType.SYSTEM -> message.body
    message.type == MessageType.DICE -> "🎲 ${message.diceExpr} → ${message.body}"
    else -> "${message.senderName} · ${message.body}"
}

/**
 * 프로필 관리 — 오너·GM·캐릭터 전부를 이미지+이름 목록으로.
 * 항목 클릭 = 해당 설정 화면, 하단 = 프로필 추가하기.
 */
@Composable
private fun ProfileManagerDialog(
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
private fun OwnerProfileDialog(forced: Boolean, onClose: () -> Unit) {
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
                Images.importDownscaled(context, uri, "owner", maxSize = 512)
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

/** 앱 전체 글꼴 선택 — 시스템 기본 / 고운 바탕(명조), 즉시 반영·유지 */
@Composable
private fun FontSettingDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val tokens = Pbp.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("앱 글꼴") },
        text = {
            Column {
                listOf(
                    com.pbp.app.ui.theme.AppFonts.SYSTEM to "시스템 기본",
                    com.pbp.app.ui.theme.AppFonts.GOWUN to "고운 바탕 (명조)",
                    com.pbp.app.ui.theme.AppFonts.PRETENDARD to "프리텐다드 (고딕)",
                ).forEach { (value, label) ->
                    val selected = com.pbp.app.ui.theme.AppFonts.choice == value
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(PbpDimens.rCell))
                            .combinedClickable(onClick = {
                                com.pbp.app.ui.theme.AppFonts.set(context, value)
                            })
                            .padding(PbpDimens.sp3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (selected) "●" else "○",
                            color = if (selected) tokens.signature else tokens.inkDim,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.width(PbpDimens.sp2))
                        Text(
                            label,
                            fontSize = 13.sp,
                            fontFamily = when (value) {
                                com.pbp.app.ui.theme.AppFonts.GOWUN -> GowunBatang
                                com.pbp.app.ui.theme.AppFonts.PRETENDARD ->
                                    com.pbp.app.ui.theme.Pretendard
                                else -> null
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun CreateRoomDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    val tokens = Pbp.colors
    var name by remember { mutableStateOf("") }
    var rule by remember { mutableStateOf(Rules.COC7) }
    var ruleMenuOpen by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 세션") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PbpDimens.sp2)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("방 이름") },
                    singleLine = true,
                )
                // TRPG 룰 — 판정 매크로의 다이스 기준
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(PbpDimens.rCell))
                            .border(1.dp, tokens.line, RoundedCornerShape(PbpDimens.rCell))
                            .combinedClickable(onClick = { ruleMenuOpen = true })
                            .padding(PbpDimens.sp3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("TRPG 룰", fontSize = 10.sp, color = tokens.inkDim)
                            Text(Rules.label(rule), fontSize = 13.sp, color = tokens.ink)
                        }
                        Text("▾", fontSize = 13.sp, color = tokens.inkDim)
                    }
                    DropdownMenu(
                        expanded = ruleMenuOpen,
                        onDismissRequest = { ruleMenuOpen = false },
                    ) {
                        Rules.all.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label, fontSize = 13.sp) },
                                onClick = {
                                    rule = key
                                    ruleMenuOpen = false
                                },
                            )
                        }
                    }
                }
                Text(
                    "방을 만들면 서술자(GM) 프로필과 마스터 권한이 자동 부여됩니다.",
                    fontSize = 11.sp,
                    color = Pbp.colors.inkDim,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, rule) }) { Text("만들기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun JoinRoomDialog(onDismiss: () -> Unit, onJoin: (String, () -> Unit) -> Unit) {
    var code by remember { mutableStateOf("") }
    // 더블탭 가드 (L2) — 진행 중에는 버튼 비활성화
    var joining by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("초대 코드로 참여") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PbpDimens.sp2)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("초대 코드 (6자리)") },
                    singleLine = true,
                )
                Text(
                    "상대가 방 설정의 '방 공유'에서 받은 코드를 입력하세요.",
                    fontSize = 11.sp,
                    color = Pbp.colors.inkDim,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    joining = true
                    onJoin(code) { joining = false }
                },
                enabled = code.isNotBlank() && !joining,
            ) { Text(if (joining) "참여 중…" else "참여") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
