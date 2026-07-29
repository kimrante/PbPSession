package com.pbp.app.ui.roomlist

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.pbp.app.data.ChatRoom
import com.pbp.app.data.Message
import com.pbp.app.data.MessageType
import com.pbp.app.dice.Rules
import com.pbp.app.ui.common.relativeTime
import com.pbp.app.ui.theme.GowunBatang
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import com.pbp.app.ui.theme.PbpPalette
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
            // 헤더: 미니 앱 아이콘 + PbP 타이틀 — 상단 바 규격(56dp), 좌우는 화면 가장자리와 동일
            Row(
                Modifier.fillMaxWidth().height(PbpDimens.appBarHeight).padding(horizontal = PbpDimens.sp4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF2A3340), Color(0xFF171D26)))),
                    contentAlignment = Alignment.Center,
                ) { Text("⬦", color = Color(0xFFEFE8D6), fontSize = 18.sp) }
                Spacer(Modifier.width(PbpDimens.sp3))
                Column {
                    Text(
                        "PbP",
                        fontFamily = GowunBatang,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = tokens.titleAccent,
                    )
                    Text("진행 중인 세션 ${rooms.size}", fontSize = 11.sp, color = tokens.inkDim)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showFont = true }) {
                    Text("Aa", color = tokens.inkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { showJoin = true }) {
                    Text("참여", color = tokens.inkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
            onJoin = { code ->
                vm.joinRoom(code) { roomId ->
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
                            fontFamily = if (value == com.pbp.app.ui.theme.AppFonts.GOWUN) GowunBatang else null,
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
private fun JoinRoomDialog(onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
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
            TextButton(onClick = { onJoin(code) }, enabled = code.isNotBlank()) { Text("참여") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
