package com.pbp.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pbp.desktop.data.AppConfig
import com.pbp.desktop.data.FirestoreRest
import com.pbp.desktop.data.JoinedRoom
import com.pbp.desktop.data.Message
import com.pbp.desktop.data.Profile
import com.pbp.desktop.logic.DiceBot
import com.pbp.desktop.logic.ProfileStats
import com.pbp.desktop.logic.GmSpeech
import com.pbp.desktop.ui.GowunBatang
import com.pbp.desktop.ui.MarkupText
import com.pbp.desktop.ui.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 모바일 앱과 같은 Firebase 프로젝트 (app/src/main/res/values/firebase.xml)
private const val PROJECT_ID = "pbp-session-1195c"
private const val API_KEY = "AIzaSyCTgWzPb62iJ5rASCZ6WEiKi7kwNPVC2m4"

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "PbP — 1:1 TRPG 채팅",
        state = rememberWindowState(width = 980.dp, height = 620.dp),
    ) {
        App()
    }
}

@Composable
private fun App() {
    // 파일 읽기+쓰기라 UI 스레드에서 하면 첫 프레임이 지연된다 — 별도 스레드에서 로드 (C8)
    val config = remember { runBlockingIo { AppConfig.load() } }
    val firestore = remember {
        FirestoreRest(
            PROJECT_ID, API_KEY,
            initialRefreshToken = config.authRefreshToken,
            onAuthChanged = { refreshToken, _ ->
                if (config.authRefreshToken != refreshToken) {
                    config.authRefreshToken = refreshToken
                    config.save()
                }
            },
        )
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 시작 시 1회: 참여 중인 방들에 auth UID 멤버 문서 보강 (보안 규칙의 접근 근거)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            config.rooms.forEach { runCatching { firestore.ensureMember(it.remoteId) } }
        }
    }

    var rooms by remember { mutableStateOf(config.rooms.toList()) }
    var selected by remember { mutableStateOf(rooms.firstOrNull()) }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var profiles by remember { mutableStateOf(config.profiles.toList()) }
    val avatarCache = remember { mutableStateMapOf<String, ImageBitmap?>() }

    var overlay by remember { mutableStateOf<OverlayKind?>(null) }

    // 내 테마/배경 변경 직후 폴링이 옛 서버 값으로 되돌리는 것 방지 (P3-14)
    var metaFreezeUntil by remember { mutableStateOf(0L) }

    // 메시지 작성자 신원 — 익명 UID가 있으면 그것을(규칙 정합), 없으면 기존 deviceId (C3)
    fun authorUid(): String = firestore.uid ?: config.deviceId

    fun persist() {
        config.rooms.clear(); config.rooms.addAll(rooms)
        config.profiles.clear(); config.profiles.addAll(profiles)
        // 직렬화는 목록을 소유한 여기서, 파일 쓰기만 IO에서 (N8·P3-16)
        val json = config.snapshot()
        scope.launch(Dispatchers.IO) { config.writeSnapshot(json) }
    }

    // 선택된 방 폴링: 최초 전체 1회 + 이후 증분(createdAt 기준)만 — read 과금 최소화.
    // 방 메타(테마/배경)는 10초 주기.
    LaunchedEffect(selected?.remoteId) {
        val room = selected ?: return@LaunchedEffect
        messages = emptyList()
        var lastCreatedAt = 0L
        var tick = 0
        while (isActive) {
            val fetched = withContext(Dispatchers.IO) {
                firestore.listMessagesSince(room.remoteId, lastCreatedAt)
            }
            // null = 오류 — 커서를 전진시키지 않고 다음 폴링에서 재시도 (P1-6)
            if (fetched != null && fetched.isNotEmpty()) {
                val byId = messages.associateBy { it.docId }
                val fresh = fetched.filter { it.docId !in byId }
                // 30초 윈도우로 다시 받은 문서 중 편집된 것은 갱신 (C10)
                val edited = fetched.filter { incoming ->
                    byId[incoming.docId]?.let { it.editedAt != incoming.editedAt } == true
                }.associateBy { it.docId }
                if (fresh.isNotEmpty() || edited.isNotEmpty()) {
                    messages = (messages.map { edited[it.docId] ?: it } + fresh)
                        .sortedBy { it.createdAt }
                }
                lastCreatedAt = maxOf(lastCreatedAt, fetched.maxOf { it.createdAt })
            }
            if (tick % 4 == 0 && System.currentTimeMillis() > metaFreezeUntil) {
                val meta = withContext(Dispatchers.IO) { firestore.getRoom(room.remoteId) }
                if (meta != null &&
                    (meta.themeColor != room.themeColor || meta.backgroundKey != room.backgroundKey || meta.name != room.name)
                ) {
                    room.themeColor = meta.themeColor
                    room.backgroundKey = meta.backgroundKey
                    room.name = meta.name
                    rooms = rooms.toList() // 재구성 트리거
                    persist()
                }
            }
            tick++
            delay(2500)
        }
    }

    /**
     * 전송. onResult는 (본문 전송 성공, 다이스 후속 성공) — 본문이 성공했으면
     * 입력을 복원하면 안 된다(재전송 시 서버에 2건이 생김, N3).
     */
    fun sendMessage(text: String, isOoc: Boolean, onResult: (Boolean, Boolean) -> Unit) {
        val room = selected ?: return onResult(false, true)
        val body = text.trim()
        if (body.isEmpty()) return onResult(true, true)
        val sender = profiles.getOrNull(room.activeProfileIndex) ?: profiles.firstOrNull()
            ?: return onResult(false, true)
        // 캐릭터 값 치환 — 안드로이드와 동일 순서: 저장은 {{값}} 마커, 다이스는 순수 값 (P2-5)
        val (plain, marked) = ProfileStats.substitute(body, sender.stats ?: emptyMap())
        scope.launch(Dispatchers.IO) {
            val textOk = firestore.postMessage(
                room.remoteId,
                messageValues(
                    type = "TEXT", body = marked, sender = sender,
                    isOoc = isOoc, authorUid = authorUid(),
                ),
            )
            var diceOk = true
            if (textOk && !isOoc) {
                DiceBot.parse(plain)?.let { command ->
                    val result = DiceBot.roll(command)
                    diceOk = firestore.postMessage(
                        room.remoteId,
                        messageValues(
                            type = "DICE", body = result.breakdown,
                            sender = Profile(name = "다이스봇", emoji = "🎲"),
                            isOoc = false, authorUid = authorUid(),
                            diceExpr = "${sender.name} · ${command.expr}", isBot = true,
                            diceOutcome = result.success?.let { if (it) "success" else "fail" },
                        ),
                    )
                }
            }
            onResult(textOk, diceOk)
            // 화면 반영은 증분 폴링(≤2.5초)이 담당 — 전체 재조회 금지
        }
    }

    fun switchProfile(index: Int) {
        val room = selected ?: return
        if (room.activeProfileIndex == index) return
        room.activeProfileIndex = index
        rooms = rooms.toList()
        persist()
        val name = profiles.getOrNull(index)?.name ?: return
        scope.launch(Dispatchers.IO) {
            val ok = firestore.postMessage(
                room.remoteId,
                mapOf(
                    "type" to "SYSTEM",
                    "body" to "프로필을 '$name'(으)로 전환했습니다",
                    "createdAt" to System.currentTimeMillis(),
                    "authorUid" to authorUid(),
                    "isOoc" to false, "senderIsGm" to false, "senderIsBot" to false,
                ),
            )
            // 로컬 전환은 이미 끝났으므로 되돌리지 않고 알리기만 (C14)
            if (!ok) System.err.println("프로필 전환 알림 전송 실패 — 상대 화면에는 표시되지 않습니다")
        }
    }

    Row(Modifier.fillMaxSize().background(Tokens.Bg)) {
        LeftPane(
            rooms = rooms,
            selected = selected,
            onSelect = { selected = it },
            onCreate = { overlay = OverlayKind.CreateRoom },
            onJoin = { overlay = OverlayKind.JoinRoom },
        )
        Box(Modifier.width(1.dp).fillMaxHeight().background(Tokens.Line))
        val room = selected
        if (room == null) {
            EmptyPane()
        } else {
            ChatPane(
                room = room,
                messages = messages,
                profiles = profiles,
                deviceId = config.deviceId,
                avatarCache = avatarCache,
                firestore = firestore,
                onSend = ::sendMessage,
                onSwitchProfile = ::switchProfile,
                onAddProfile = { overlay = OverlayKind.NewProfile },
                onShowCode = { overlay = OverlayKind.ShowCode },
                onOpenSettings = { overlay = OverlayKind.RoomSettings },
            )
        }
    }

    when (overlay) {
        OverlayKind.JoinRoom -> JoinOverlay(
            onDismiss = { overlay = null },
            onJoin = { code, onFail ->
                scope.launch(Dispatchers.IO) {
                    val meta = firestore.findRoomByCode(code)
                    // 멤버 등록에 실패하면 규칙상 방을 읽을 수 없다 — 참가 실패로 처리 (C13)
                    if (meta == null || !firestore.ensureMember(meta.remoteId)) onFail()
                    else {
                        val existing = rooms.find { it.remoteId == meta.remoteId }
                        val joined = existing ?: JoinedRoom(
                            remoteId = meta.remoteId, name = meta.name, icon = meta.icon,
                            inviteCode = meta.inviteCode, themeColor = meta.themeColor,
                            backgroundKey = meta.backgroundKey, isMaster = false,
                        )
                        if (existing == null) rooms = rooms + joined
                        selected = joined
                        persist()
                        overlay = null
                    }
                }
            },
        )
        OverlayKind.CreateRoom -> CreateOverlay(
            onDismiss = { overlay = null },
            onCreate = { name, icon ->
                scope.launch(Dispatchers.IO) {
                    val code = inviteCode()
                    val meta = firestore.createRoom(name.ifBlank { "새 세션" }, icon.ifBlank { "🎲" }, code)
                    if (meta != null) {
                        firestore.ensureMember(meta.remoteId)
                        // 매핑 생성 실패 = 아무도 참가할 수 없는 초대코드 — 알린다 (C13)
                        if (!firestore.createInviteCode(code, meta.remoteId)) {
                            System.err.println("초대 코드 매핑 생성 실패 — 방 설정에서 다시 공유해야 합니다")
                        }
                        val joined = JoinedRoom(
                            remoteId = meta.remoteId, name = meta.name, icon = meta.icon,
                            inviteCode = code, themeColor = meta.themeColor,
                            backgroundKey = meta.backgroundKey, isMaster = true,
                        )
                        rooms = rooms + joined
                        selected = joined
                        persist()
                    }
                    overlay = null
                }
            },
        )
        OverlayKind.NewProfile -> ProfileOverlay(
            onDismiss = { overlay = null },
            onSave = { profile ->
                profiles = profiles + profile
                persist()
                overlay = null
            },
        )
        OverlayKind.ShowCode -> CodeOverlay(
            code = selected?.inviteCode ?: "-",
            onDismiss = { overlay = null },
        )
        OverlayKind.RoomSettings -> SettingsOverlay(
            room = selected,
            onDismiss = { overlay = null },
            onApply = { theme, background ->
                val room = selected ?: return@SettingsOverlay
                room.themeColor = theme
                room.backgroundKey = background
                rooms = rooms.toList()
                persist()
                // PATCH가 서버에 착지하기 전 폴링이 옛 값을 다시 덮지 않도록 유예 (P3-14)
                metaFreezeUntil = System.currentTimeMillis() + 15_000
                scope.launch(Dispatchers.IO) {
                    firestore.updateRoomSettings(room.remoteId, theme, background)
                }
                overlay = null
            },
        )
        null -> {}
    }
}

/** 컴포지션 진입 전 1회성 파일 IO를 UI 스레드 밖에서 수행 (C8) */
private fun <T> runBlockingIo(block: () -> T): T =
    kotlinx.coroutines.runBlocking(Dispatchers.IO) { block() }

/** 아바타 fetch in-flight 집합 — 스냅샷 상태가 아니라 리컴포지션을 유발하지 않는다 (R1) */
private val avatarsInFlight: MutableSet<String> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()

private enum class OverlayKind { JoinRoom, CreateRoom, NewProfile, ShowCode, RoomSettings }

private fun inviteCode(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return (1..6).map { alphabet.random() }.joinToString("")
}

private fun messageValues(
    type: String,
    body: String,
    sender: Profile,
    isOoc: Boolean,
    authorUid: String,
    diceExpr: String? = null,
    isBot: Boolean = false,
    diceOutcome: String? = null,
): Map<String, Any?> = mapOf(
    "type" to type,
    "body" to body,
    "diceExpr" to diceExpr,
    "diceOutcome" to diceOutcome,
    "senderName" to sender.name,
    "senderEmoji" to sender.emoji,
    "senderIsGm" to sender.isGm,
    "senderIsBot" to isBot,
    "senderNameColor" to sender.nameColor,
    "senderBubbleColor" to sender.bubbleColor,
    "isOoc" to isOoc,
    "createdAt" to System.currentTimeMillis(),
    "authorUid" to authorUid,
    "avatarId" to null,
)

// ══════════════ 왼쪽 패널: 방 목록 ══════════════

@Composable
private fun LeftPane(
    rooms: List<JoinedRoom>,
    selected: JoinedRoom?,
    onSelect: (JoinedRoom) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
) {
    Column(Modifier.width(320.dp).fillMaxHeight().background(Tokens.Bg).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF2A3340), Color(0xFF171D26)))),
                contentAlignment = Alignment.Center,
            ) { Text("⬦", color = Color(0xFFEFE8D6), fontSize = 17.sp) }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "PbP", fontFamily = GowunBatang, fontWeight = FontWeight.Bold,
                    fontSize = 18.sp, color = Tokens.Signature,
                )
                Text("진행 중인 세션 ${rooms.size} · PC", fontSize = 11.sp, color = Tokens.InkDim)
            }
        }
        Spacer(Modifier.height(14.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rooms, key = { it.remoteId }) { room ->
                val active = room.remoteId == selected?.remoteId
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (active) Color(0x1F8EC5E8) else Color(0x09FFFFFF))
                        .border(
                            1.dp,
                            if (active) Color(room.themeColor).copy(alpha = .5f) else Tokens.Line,
                            RoundedCornerShape(16.dp),
                        )
                        .clickable { onSelect(room) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp)) {
                        val preset = Tokens.backgroundPresets[room.backgroundKey]
                            ?: Tokens.backgroundPresets.getValue("preset_lighthouse")
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(preset.first), Color(preset.second))
                                    )
                                ),
                            contentAlignment = Alignment.Center,
                        ) { Text(room.icon, fontSize = 16.sp) }
                        Box(
                            Modifier.size(13.dp)
                                .align(Alignment.BottomEnd)
                                .border(2.5.dp, Tokens.Bg, CircleShape)
                                .clip(CircleShape)
                                .background(Color(room.themeColor))
                        )
                    }
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text(
                            room.name, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                            color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (room.isMaster) "마스터 · 코드 ${room.inviteCode ?: "-"}" else "참여자",
                            fontSize = 10.5.sp, color = Tokens.InkDim,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YellowButton("＋ 새 세션", Modifier.weight(1f), onCreate)
            GhostButton("코드로 참여", Modifier.weight(1f), onJoin)
        }
    }
}

@Composable
private fun EmptyPane() {
    Box(Modifier.fillMaxSize().background(Tokens.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎲", fontSize = 44.sp)
            Spacer(Modifier.height(10.dp))
            Text("왼쪽에서 세션을 만들거나 초대 코드로 참여하세요", color = Tokens.InkDim, fontSize = 13.sp)
        }
    }
}

// ══════════════ 채팅 패널 ══════════════

@Composable
private fun ChatPane(
    room: JoinedRoom,
    messages: List<Message>,
    profiles: List<Profile>,
    deviceId: String,
    avatarCache: MutableMap<String, ImageBitmap?>,
    firestore: FirestoreRest,
    onSend: (String, Boolean, (Boolean, Boolean) -> Unit) -> Unit,
    onSwitchProfile: (Int) -> Unit,
    onAddProfile: () -> Unit,
    onShowCode: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val theme = Color(room.themeColor)
    Box(Modifier.fillMaxSize()) {
        val preset = Tokens.backgroundPresets[room.backgroundKey]
            ?: Tokens.backgroundPresets.getValue("preset_lighthouse")
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(preset.first), Color(preset.second))))
        )
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Tokens.VeilTop, Tokens.VeilMid, Tokens.VeilTop)))
        )
        Column(Modifier.fillMaxSize()) {
            // 상단 바
            Row(
                Modifier.fillMaxWidth().background(Color(0x73080B10)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        room.name, fontFamily = GowunBatang, fontWeight = FontWeight.Bold,
                        fontSize = 16.sp, color = Tokens.Ink, maxLines = 1,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(3.dp)).background(theme))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (room.isMaster) "마스터" else "참여자",
                            fontSize = 10.5.sp, color = Tokens.InkDim,
                        )
                    }
                }
                GhostButton("초대 코드", Modifier, onShowCode)
                if (room.isMaster) {
                    Spacer(Modifier.width(8.dp))
                    GhostButton("방 설정", Modifier, onOpenSettings)
                }
            }

            // 메시지 목록 — 최신 메시지가 바뀔 때만, 바닥 근처를 보고 있을 때만 따라간다
            // (안드로이드 P1-7과 같은 규칙, C9)
            val listState = rememberLazyListState()
            LaunchedEffect(messages.lastOrNull()?.docId) {
                if (messages.isEmpty()) return@LaunchedEffect
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                if (lastVisible >= messages.size - 2) listState.scrollToItem(messages.size - 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages, key = { it.docId }) { message ->
                    MessageBlock(message, deviceId, room, avatarCache, firestore)
                }
            }

            // 입력 영역
            InputZone(
                room = room,
                profiles = profiles,
                theme = theme,
                onSend = onSend,
                onSwitchProfile = onSwitchProfile,
                onAddProfile = onAddProfile,
            )
        }
    }
}

@Composable
private fun MessageBlock(
    message: Message,
    deviceId: String,
    room: JoinedRoom,
    avatarCache: MutableMap<String, ImageBitmap?>,
    firestore: FirestoreRest,
) {
    when {
        message.type == "SYSTEM" -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0x59000000))
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                ) {
                    Text(message.body, fontSize = 10.5.sp, color = Color(0x99FFFFFF))
                }
            }
        }
        message.type == "DICE" -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x80000000))
                        .border(1.dp, Tokens.Signature.copy(alpha = .35f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🎲", fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${message.diceExpr} → ${message.body}",
                        fontSize = 11.5.sp, color = Color(0xFFFFE9AE), fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        message.senderIsGm && !message.isOoc -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GmSpeech.split(message.body).forEach { part ->
                    when (part) {
                        is GmSpeech.Part.Narration -> NarrationBlock(message, part.text)
                        is GmSpeech.Part.Quote -> BubbleRow(
                            message = message, deviceId = deviceId, room = room,
                            avatarCache = avatarCache, firestore = firestore,
                            overrideBody = part.text, overrideName = "???",
                            overrideBubbleColor = Tokens.gmQuoteBubble,
                        )
                    }
                }
            }
        }
        else -> BubbleRow(message, deviceId, room, avatarCache, firestore)
    }
}

@Composable
private fun NarrationBlock(message: Message, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 4.dp))
            .background(Tokens.NarrBg)
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
    ) {
        MarkupText(
            text = text, fontSize = 13.5.sp, color = Tokens.NarrInk,
            rubyColor = Tokens.Signature, fontFamily = GowunBatang, lineHeight = 25.sp,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val gmLabel = message.senderName?.let { if (it.startsWith("GM")) it else "GM $it" } ?: "GM"
            Text("$gmLabel · 서술", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Tokens.Signature)
            Spacer(Modifier.width(6.dp))
            Text(formatTime(message.createdAt), fontSize = 9.5.sp, color = Tokens.InkDim)
        }
    }
}

@Composable
private fun BubbleRow(
    message: Message,
    deviceId: String,
    room: JoinedRoom,
    avatarCache: MutableMap<String, ImageBitmap?>,
    firestore: FirestoreRest,
    overrideBody: String? = null,
    overrideName: String? = null,
    overrideBubbleColor: Long? = null,
) {
    val mine = message.authorUid == deviceId && overrideName == null
    val body = overrideBody ?: message.body
    val bubbleColor = when {
        message.isOoc -> Tokens.ChatterBubble
        else -> Color(overrideBubbleColor ?: message.senderBubbleColor ?: Tokens.bubblePresets.first())
    }
    val nameColor = when {
        message.isOoc -> Tokens.InkDim
        overrideName != null -> Tokens.Signature
        message.senderNameColor != null -> Color(message.senderNameColor)
        else -> Tokens.Ink
    }
    val inkColor = if (message.isOoc) Tokens.ChatterInk else Tokens.BubbleInk

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (!mine) MessageAvatar(message, room, avatarCache, firestore)
            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                Text(
                    overrideName ?: message.senderName ?: "",
                    fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = nameColor,
                )
                Spacer(Modifier.height(3.dp))
                val shape = if (mine) {
                    RoundedCornerShape(topStart = 15.dp, topEnd = 4.dp, bottomEnd = 15.dp, bottomStart = 15.dp)
                } else {
                    RoundedCornerShape(topStart = 4.dp, topEnd = 15.dp, bottomEnd = 15.dp, bottomStart = 15.dp)
                }
                Box(
                    Modifier.widthIn(max = 420.dp).clip(shape).background(bubbleColor)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row {
                        if (message.isOoc) {
                            Text(
                                "잡담", fontSize = 9.sp, color = inkColor,
                                modifier = Modifier.padding(end = 6.dp, top = 2.dp)
                                    .border(1.dp, inkColor.copy(alpha = .4f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 5.dp),
                            )
                        }
                        MarkupText(
                            text = body, fontSize = 13.sp, color = inkColor,
                            rubyColor = inkColor.copy(alpha = .65f), lineHeight = 20.sp,
                            fontWeight = if (message.isOoc) FontWeight.Normal else FontWeight.Medium,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    Text(formatTime(message.createdAt), fontSize = 9.5.sp, color = Tokens.InkDim)
                    if (message.editedAt != null) {
                        Spacer(Modifier.width(4.dp))
                        Text("(수정됨)", fontSize = 9.5.sp, color = Tokens.InkDim)
                    }
                }
            }
            if (mine) MessageAvatar(message, room, avatarCache, firestore)
        }
    }
}

@Composable
private fun MessageAvatar(
    message: Message,
    room: JoinedRoom,
    avatarCache: MutableMap<String, ImageBitmap?>,
    firestore: FirestoreRest,
) {
    val avatarId = message.avatarId
    // 이펙트는 항상 컴포지션에 있어야 한다 — 조건 안에 두면 캐시 쓰기가 리컴포지션을
    // 유발해 자기 자신을 취소시킨다(R1). 중복 fetch는 스냅샷이 아닌 별도 집합으로 막는다.
    LaunchedEffect(avatarId, room.remoteId) {
        if (avatarId == null) return@LaunchedEffect
        if (avatarCache[avatarId] != null) return@LaunchedEffect
        if (!avatarsInFlight.add(avatarId)) return@LaunchedEffect
        try {
            val bitmap = withContext(Dispatchers.IO) {
                firestore.fetchAvatar(room.remoteId, avatarId)?.let { bytes ->
                    runCatching {
                        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                    }.getOrNull()
                }
            }
            // 실패는 캐시하지 않는다 — 다음 표시 때 재시도 (P3-15)
            if (bitmap != null) avatarCache[avatarId] = bitmap
        } finally {
            avatarsInFlight.remove(avatarId)
        }
    }
    val bitmap = avatarId?.let { avatarCache[it] }
    Box(
        Modifier.size(38.dp)
            .border(1.5.dp, Color.White.copy(alpha = .22f), CircleShape)
            .clip(CircleShape)
            .background(Tokens.Panel2)
            .alpha(if (message.isOoc) 0.55f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap, contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Text(message.senderEmoji ?: "🙂", fontSize = 16.sp)
        }
    }
}

// ══════════════ 입력 영역 ══════════════

@Composable
private fun InputZone(
    room: JoinedRoom,
    profiles: List<Profile>,
    theme: Color,
    onSend: (String, Boolean, (Boolean, Boolean) -> Unit) -> Unit,
    onSwitchProfile: (Int) -> Unit,
    onAddProfile: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var oocOn by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().background(Color(0xE0090C11)).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(profiles.size) { index ->
                val profile = profiles[index]
                val on = index == room.activeProfileIndex
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onSwitchProfile(index) },
                ) {
                    Box(
                        Modifier.size(34.dp)
                            .border(
                                2.dp,
                                if (on) Tokens.Signature else Color.White.copy(alpha = .2f),
                                CircleShape,
                            )
                            .clip(CircleShape)
                            .background(Tokens.Panel2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            profile.emoji, fontSize = 14.sp,
                            fontFamily = if (profile.isGm) GowunBatang else null,
                            color = if (profile.isGm) Tokens.Signature else Tokens.Ink,
                        )
                    }
                    Text(
                        profile.name, fontSize = 9.sp,
                        color = if (on) Tokens.Signature else Tokens.InkDim,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(34.dp)
                            .border(1.dp, Color.White.copy(alpha = .3f), CircleShape)
                            .clip(CircleShape)
                            .clickable(onClick = onAddProfile),
                        contentAlignment = Alignment.Center,
                    ) { Text("＋", color = Tokens.InkDim, fontSize = 14.sp) }
                    Text("추가", fontSize = 9.sp, color = Tokens.InkDim)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(999.dp))
                    .background(if (oocOn) Tokens.Signature.copy(alpha = .16f) else Color.White.copy(alpha = .07f))
                    .border(
                        1.dp,
                        if (oocOn) Tokens.Signature.copy(alpha = .4f) else Tokens.Line,
                        RoundedCornerShape(999.dp),
                    )
                    .clickable { oocOn = !oocOn }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "잡담", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = if (oocOn) Tokens.Signature else Tokens.InkDim,
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = .08f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = Tokens.Ink, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Tokens.Signature),
                maxLines = 4,
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) {
                            Text(
                                if (oocOn) "잡담으로 보내기…" else "**굵게** · |等臺《등대》 · 1d100",
                                fontSize = 12.sp, color = Tokens.InkDim,
                            )
                        }
                        inner()
                    }
                },
            )
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(13.dp))
                    .background(if (input.isNotBlank()) theme else theme.copy(alpha = .35f))
                    .clickable(enabled = input.isNotBlank()) {
                        val text = input
                        val ooc = oocOn
                        input = ""
                        errorMessage = null
                        // 전송 실패 시 입력을 복원해 무통보 소실을 막는다 (P1-5).
                        // 단 본문이 이미 올라갔으면 복원하지 않는다 — 재전송 시 2건이 된다 (N3)
                        onSend(text, ooc) { textOk, diceOk ->
                            when {
                                !textOk -> {
                                    // 늦게 도착한 실패 콜백이 새로 친 글을 덮지 않도록 (N3)
                                    if (input.isEmpty()) {
                                        input = text
                                        oocOn = ooc
                                    }
                                    errorMessage = "전송에 실패했습니다 — 네트워크를 확인해주세요"
                                }
                                !diceOk -> errorMessage = "메시지는 전송됐지만 다이스 결과 전송에 실패했습니다"
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text("➤", fontSize = 15.sp, color = Color(0xFF0D1420)) }
        }
        errorMessage?.let { message ->
            Text(
                message,
                fontSize = 11.sp,
                color = Color(0xFFF2A1A8),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ══════════════ 오버레이(다이얼로그) ══════════════

@Composable
private fun OverlayScaffold(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(430.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Tokens.Panel)
                .clickable(enabled = false) {}
                .padding(22.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Tokens.Ink)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun OverlayField(value: String, onChange: (String) -> Unit, placeholder: String) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = .06f))
            .border(1.dp, Tokens.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        textStyle = androidx.compose.ui.text.TextStyle(color = Tokens.Ink, fontSize = 14.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Tokens.Signature),
        singleLine = true,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, fontSize = 13.sp, color = Tokens.InkDim)
                inner()
            }
        },
    )
}

@Composable
private fun YellowButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(999.dp)).background(Tokens.Signature)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
    }
}

@Composable
private fun GhostButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(999.dp))
            .border(1.dp, Tokens.Line, RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = .06f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, color = Tokens.InkDim)
    }
}

@Composable
private fun JoinOverlay(onDismiss: () -> Unit, onJoin: (String, onFail: () -> Unit) -> Unit) {
    var code by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    OverlayScaffold("초대 코드로 참여", onDismiss) {
        OverlayField(code, { code = it; failed = false }, "초대 코드 (6자리)")
        if (failed) {
            Spacer(Modifier.height(8.dp))
            Text("방을 찾지 못했습니다. 코드를 확인해주세요.", fontSize = 12.sp, color = Color(0xFFF2A1A8))
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YellowButton("참여", Modifier.weight(1f)) { if (code.isNotBlank()) onJoin(code) { failed = true } }
            GhostButton("취소", Modifier.weight(1f), onDismiss)
        }
    }
}

@Composable
private fun CreateOverlay(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("") }
    OverlayScaffold("새 세션", onDismiss) {
        OverlayField(name, { name = it }, "방 이름")
        Spacer(Modifier.height(10.dp))
        OverlayField(icon, { icon = it }, "아이콘 (이모지, 비우면 🎲)")
        Spacer(Modifier.height(8.dp))
        Text("방을 만들면 마스터 권한과 초대 코드가 부여됩니다.", fontSize = 12.sp, color = Tokens.InkDim)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YellowButton("만들기", Modifier.weight(1f)) { onCreate(name, icon) }
            GhostButton("취소", Modifier.weight(1f), onDismiss)
        }
    }
}

@Composable
private fun ProfileOverlay(onDismiss: () -> Unit, onSave: (Profile) -> Unit) {
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("") }
    var nameColor by remember { mutableStateOf(Tokens.namePresets.first()) }
    var bubbleColor by remember { mutableStateOf(Tokens.bubblePresets.first()) }
    OverlayScaffold("새 캐릭터", onDismiss) {
        OverlayField(name, { name = it }, "캐릭터 이름")
        Spacer(Modifier.height(10.dp))
        OverlayField(emoji, { emoji = it }, "이모지 아바타 (비우면 🙂)")
        Spacer(Modifier.height(14.dp))
        Text("이름 색", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Spacer(Modifier.height(7.dp))
        SwatchRow(Tokens.namePresets, nameColor) { nameColor = it }
        Spacer(Modifier.height(14.dp))
        Text("말풍선 색", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Spacer(Modifier.height(7.dp))
        SwatchRow(Tokens.bubblePresets, bubbleColor) { bubbleColor = it }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YellowButton("저장", Modifier.weight(1f)) {
                onSave(
                    Profile(
                        name = name.trim().ifEmpty { "이름 없음" },
                        emoji = emoji.trim().ifEmpty { "🙂" },
                        nameColor = nameColor,
                        bubbleColor = bubbleColor,
                    )
                )
            }
            GhostButton("취소", Modifier.weight(1f), onDismiss)
        }
    }
}

@Composable
private fun SwatchRow(presets: List<Long>, selected: Long, onSelect: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { color ->
            val on = selected == color
            Box(
                Modifier.size(30.dp)
                    .border(2.dp, if (on) Color.White else Color.Transparent, CircleShape)
                    .clip(CircleShape)
                    .background(Color(color))
                    .clickable { onSelect(color) },
                contentAlignment = Alignment.Center,
            ) { if (on) Text("✓", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color(0xFF10151C)) }
        }
    }
}

@Composable
private fun CodeOverlay(code: String, onDismiss: () -> Unit) {
    OverlayScaffold("초대 코드", onDismiss) {
        Text(
            code, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Tokens.Signature,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "상대가 모바일/PC의 '참여'에서 이 코드를 입력하면 같은 방에 연결됩니다.",
            fontSize = 12.5.sp, color = Tokens.InkDim,
        )
        Spacer(Modifier.height(16.dp))
        YellowButton("닫기", Modifier.fillMaxWidth(), onDismiss)
    }
}

@Composable
private fun SettingsOverlay(room: JoinedRoom?, onDismiss: () -> Unit, onApply: (Long, String) -> Unit) {
    if (room == null) return
    var theme by remember { mutableStateOf(room.themeColor) }
    var background by remember { mutableStateOf(room.backgroundKey) }
    OverlayScaffold("방 설정 · ${room.name}", onDismiss) {
        Text("테마 컬러", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Spacer(Modifier.height(7.dp))
        SwatchRow(Tokens.themePresets.map { it.first }, theme) { theme = it }
        Spacer(Modifier.height(14.dp))
        Text("배경", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tokens.backgroundPresets.forEach { (key, colors) ->
                val on = background == key
                Box(
                    Modifier.size(width = 64.dp, height = 44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.verticalGradient(listOf(Color(colors.first), Color(colors.second)))
                        )
                        .border(
                            1.5.dp,
                            if (on) Tokens.Signature else Tokens.Line,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { background = key },
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YellowButton("적용", Modifier.weight(1f)) { onApply(theme, background) }
            GhostButton("취소", Modifier.weight(1f), onDismiss)
        }
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
