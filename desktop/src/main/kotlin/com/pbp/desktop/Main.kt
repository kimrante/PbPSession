package com.pbp.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import com.pbp.desktop.data.RoomCacheStore
import com.pbp.desktop.logic.CharacterCodec
import com.pbp.desktop.logic.DiceBot
import com.pbp.desktop.logic.ProfileStats
import com.pbp.desktop.logic.Rules
import com.pbp.desktop.logic.GmSpeech
import com.pbp.desktop.notify.DesktopNotifier
import com.pbp.desktop.ui.GowunBatang
import com.pbp.desktop.ui.MarkupText
import com.pbp.desktop.ui.Pretendard
import com.pbp.desktop.ui.Tokens
import com.pbp.desktop.ui.appFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 방별 세션 캐시 (P3) — 메시지 목록·증분 커서·삭제 억제 목록. 프로세스 수명 동안 유지 */
private class RoomSession {
    var messages: List<Message> = emptyList()
    var lastCreatedAt: Long = 0L
    val deletedDocIds: MutableSet<String> = mutableSetOf()
    /** 파일 캐시(P3 근본 수정)에서 이미 복원 시도했는지 */
    var diskLoaded: Boolean = false
    /** 마지막 파일 캐시 저장 시각 — 30초 스로틀 */
    var lastSavedAt: Long = 0L
}

// 모바일 앱과 같은 Firebase 프로젝트 (app/src/main/res/values/firebase.xml)
private const val PROJECT_ID = "pbp-session-1195c"
private const val API_KEY = "AIzaSyCTgWzPb62iJ5rASCZ6WEiKi7kwNPVC2m4"

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "PbP — 1:1 TRPG 채팅",
        state = rememberWindowState(width = 1200.dp, height = 760.dp),
    ) {
        // 창 포커스 추적 — 포커스가 없을 때만 OS 알림 (모바일 isForeground와 동일 역할)
        val windowFocused = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
        DisposableEffect(Unit) {
            val listener = object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                    windowFocused.set(true)
                }

                override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                    windowFocused.set(false)
                }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        App(windowFocused)
    }
}

@Composable
private fun App(windowFocused: java.util.concurrent.atomic.AtomicBoolean) {
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

    // 시작 시 1회: 참여 중인 방들에 auth UID 멤버 문서 보강 (보안 규칙의 접근 근거).
    // 라이브 리스트를 IO에서 순회하면 persist의 clear/addAll과 겹쳐 CME (C1) — 사본 사용
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            config.roomsCopy().forEach { runCatching { firestore.ensureMember(it.remoteId) } }
        }
    }

    var rooms by remember { mutableStateOf(config.rooms.toList()) }
    var selected by remember { mutableStateOf(rooms.firstOrNull()) }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var profiles by remember { mutableStateOf(config.profiles.toList()) }
    val avatarCache = remember { mutableStateMapOf<String, ImageBitmap?>() }

    // 오너 프로필 — 미설정이면 먼저 설정하게 한다 (첫 실행 포함, 모바일과 동일)
    var ownerName by remember { mutableStateOf(config.ownerName) }
    var ownerColor by remember { mutableStateOf(config.ownerColor) }
    var ownerImagePath by remember { mutableStateOf(config.ownerImagePath) }

    var overlay by remember {
        mutableStateOf<OverlayKind?>(
            if (config.ownerName.isBlank()) OverlayKind.OwnerProfile else null
        )
    }

    // 내 메시지 길게 눌러 편집/삭제 — 앱과 동일 흐름 (팝업 → 편집/삭제)
    var messageAction by remember { mutableStateOf<Message?>(null) }
    var messageEdit by remember { mutableStateOf<Message?>(null) }
    var messageDelete by remember { mutableStateOf<Message?>(null) }

    // 프로필 칩 길게 눌러 편집 — 앱과 동일 흐름
    var editProfileIndex by remember { mutableStateOf<Int?>(null) }

    // 방 카드 길게 눌러 나가기 — 앱의 방 삭제(길게)와 동일 위계
    var leaveTarget by remember { mutableStateOf<JoinedRoom?>(null) }

    // 앱 전체 글꼴 — config.json에 유지, 모바일 AppFonts와 동일 선택지
    var appFont by remember { mutableStateOf(config.appFont) }

    // 내 테마/배경 변경 직후 폴링이 옛 서버 값으로 되돌리는 것 방지 (P3-14)
    var metaFreezeUntil by remember { mutableStateOf(0L) }

    // 메시지 작성자 신원 — 익명 UID가 있으면 그것을(규칙 정합), 없으면 기존 deviceId (C3)
    fun authorUid(): String = firestore.uid ?: config.deviceId

    fun persist() {
        // 목록 교체+직렬화를 한 락 안에서 (C1) — IO 핸들러에서 불러도 CME 없음.
        // 파일 쓰기만 IO에서 (N8·P3-16)
        val json = config.replaceAndSnapshot(rooms, profiles)
        scope.launch(Dispatchers.IO) { config.writeSnapshot(json) }
    }

    // 과거 빌드는 참여 방에서도 GM이 기본 발화 프로필이었다 — 일회성 교정
    // (서술 권한은 마스터 전용, 모바일과 동일 규칙)
    LaunchedEffect(Unit) {
        val firstPlayer = profiles.indexOfFirst { !it.isGm }.coerceAtLeast(0)
        val fixed = rooms.map { r ->
            if (!r.isMaster && profiles.getOrNull(r.activeProfileIndex)?.isGm == true) {
                r.copy(activeProfileIndex = firstPlayer)
            } else r
        }
        if (fixed != rooms) {
            selected = fixed.firstOrNull { it.remoteId == selected?.remoteId }
            rooms = fixed
            persist()
        }
    }

    // 방별 세션 캐시 (P3) — 방 전환 때마다 전체 히스토리를 재다운로드하지 않고,
    // 마지막 커서에서 증분으로 재개한다. 삭제 억제 목록도 방별로 유지.
    val roomSessions = remember { mutableMapOf<String, RoomSession>() }
    fun sessionFor(remoteId: String): RoomSession =
        roomSessions.getOrPut(remoteId) { RoomSession() }

    // 마지막 내 전송 시각 — 활동 기반 폴 주기의 즉시 복귀 신호 (P2)
    val lastLocalSendAt = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    // 선택된 방 폴링: 최초 전체 1회 + 이후 증분(createdAt 기준)만 — read 과금 최소화.
    // 주기는 활동 기반(P2): 최근 2분 내 송수신 2.5초 / 유휴 20초 / 창 미포커스 30초
    // (정지는 금지 — 트레이 알림이 폴링에 의존). 방 메타는 60초 (P6).
    LaunchedEffect(selected?.remoteId) {
        val room = selected ?: return@LaunchedEffect
        val session = sessionFor(room.remoteId)
        // 재시작 후 첫 진입이면 파일 캐시에서 복원 — 전체 재다운로드 방지 (P3 근본 수정)
        if (!session.diskLoaded) {
            session.diskLoaded = true
            if (session.messages.isEmpty() && session.lastCreatedAt == 0L) {
                withContext(Dispatchers.IO) { RoomCacheStore.load(room.remoteId) }?.let { (cached, cursor) ->
                    session.messages = cached
                    session.lastCreatedAt = cursor
                }
            }
        }
        messages = session.messages
        var lastCreatedAt = session.lastCreatedAt
        var lastMetaPollAt = 0L
        var lastActivityAt = System.currentTimeMillis()
        try {
        while (isActive) {
            val now = System.currentTimeMillis()
            val focusedNow = windowFocused.get()
            val active = now - maxOf(lastActivityAt, lastLocalSendAt.get()) < 120_000
            val interval = when {
                !focusedNow -> 30_000L
                active -> 2_500L
                else -> 20_000L
            }
            // 반복 1회 전체를 격리 — 예기치 못한 예외 1건이 폴링을 영구 정지시키지 않게 (C2)
            runCatching {
                val fetched = withContext(Dispatchers.IO) {
                    // 중복 윈도는 주기×2 (P5) — 시계 오차·커밋 재정렬 흡수에 충분
                    firestore.listMessagesSince(room.remoteId, lastCreatedAt, windowMs = interval * 2)
                }
                // null = 오류 — 커서를 전진시키지 않고 다음 폴링에서 재시도 (P1-6)
                if (fetched != null && fetched.isNotEmpty()) {
                    val byId = messages.associateBy { it.docId }
                    val fresh = fetched.filter {
                        it.docId !in byId && it.docId !in session.deletedDocIds
                    }
                    // 창이 포커스를 잃었을 때 새 수신 알림 — 모바일과 동일 규칙
                    // (본문 비노출, SYSTEM 제외). 최초 전체 로드(lastCreatedAt=0)는 제외
                    if (lastCreatedAt > 0 && !focusedNow) {
                        fresh.lastOrNull { it.authorUid != authorUid() && it.type != "SYSTEM" }
                            ?.let { DesktopNotifier.notifyMessage(it.senderName ?: "상대") }
                    }
                    // 재수신 윈도로 다시 받은 문서 중 편집된 것은 갱신 (C10).
                    // 더 새로운 editedAt만 수용 — 내가 방금 편집한 걸 윈도의 구버전이 되돌리지 않게
                    val edited = fetched.filter { incoming ->
                        byId[incoming.docId]?.let { (incoming.editedAt ?: 0) > (it.editedAt ?: 0) } == true
                    }.associateBy { it.docId }
                    if (fresh.isNotEmpty()) lastActivityAt = now // 수신 = 활동 (P2)
                    if (fresh.isNotEmpty() || edited.isNotEmpty()) {
                        messages = (messages.map { edited[it.docId] ?: it } + fresh)
                            .sortedBy { it.createdAt }
                        session.messages = messages
                    }
                    lastCreatedAt = maxOf(lastCreatedAt, fetched.maxOf { it.createdAt })
                    session.lastCreatedAt = lastCreatedAt
                    // 파일 캐시 저장 — 30초 스로틀 (P3 근본 수정)
                    if (now - session.lastSavedAt > 30_000) {
                        session.lastSavedAt = now
                        val snapshotMessages = session.messages
                        withContext(Dispatchers.IO) {
                            RoomCacheStore.save(room.remoteId, snapshotMessages, lastCreatedAt)
                        }
                    }
                }
                if (now - lastMetaPollAt >= 60_000 && now > metaFreezeUntil) {
                    lastMetaPollAt = now
                    val meta = withContext(Dispatchers.IO) { firestore.getRoom(room.remoteId) }
                    // 캡처한 room이 아니라 최신 인스턴스와 비교 — 설정 적용으로 교체됐을 수 있다
                    val cur = rooms.firstOrNull { it.remoteId == room.remoteId }
                    // 커스텀 배경(파일 경로)은 기기 로컬 전용 — 서버 값은 preset_일 때만 반영 (모바일과 동일)
                    val serverBg = meta?.backgroundKey?.takeIf { it.startsWith("preset_") }
                    if (meta != null && cur != null &&
                        (meta.themeColor != cur.themeColor || meta.name != cur.name ||
                            (serverBg != null && serverBg != cur.backgroundKey))
                    ) {
                        val updated = cur.copy(
                            themeColor = meta.themeColor,
                            backgroundKey = serverBg ?: cur.backgroundKey,
                            name = meta.name,
                        )
                        rooms = rooms.map { if (it.remoteId == cur.remoteId) updated else it }
                        if (selected?.remoteId == cur.remoteId) selected = updated
                        persist()
                    }
                }
            }.onFailure { System.err.println("폴링 오류(다음 주기에 재시도): $it") }
            // 긴 주기 대기 중에도 전송·포커스 복귀를 1초 단위로 감지해 즉시 깨어난다 (P2)
            var waited = 0L
            while (waited < interval) {
                val step = minOf(1_000L, interval - waited)
                delay(step)
                waited += step
                if (lastLocalSendAt.get() > now || windowFocused.get() != focusedNow) break
            }
        }
        } finally {
            // 방 전환·창 종료 시 최종 상태를 파일 캐시에 남긴다 (P3 근본 수정)
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                RoomCacheStore.save(room.remoteId, session.messages, session.lastCreatedAt)
            }
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
        // 참여자는 GM 프로필로 발화할 수 없다 — 서술 권한은 마스터 전용
        val sender = profiles.getOrNull(room.activeProfileIndex)
            ?.takeIf { room.isMaster || !it.isGm }
            ?: profiles.firstOrNull { room.isMaster || !it.isGm }
            ?: return onResult(false, true)
        // 캐릭터 값 치환 — 안드로이드와 동일 순서: 저장은 {{값}} 마커, 다이스는 순수 값 (P2-5)
        val (plain, marked) = ProfileStats.substitute(body, sender.stats ?: emptyMap())
        // 잡담은 극 밖의 대화 — 어떤 캐릭터(GM 포함)가 활성이든 오너 프로필로 나간다
        val effectiveSender = if (isOoc && ownerName.isNotBlank()) {
            Profile(
                name = ownerName, emoji = "🙂",
                nameColor = ownerColor, bubbleColor = ownerColor,
                isGm = false, imagePath = ownerImagePath,
            )
        } else sender
        lastLocalSendAt.set(System.currentTimeMillis()) // 폴 주기 즉시 복귀 신호 (P2)
        scope.launch(Dispatchers.IO) {
            // 프로필 이미지가 있으면 축소본을 방 avatars 문서로 업로드 (모바일과 동일 스키마)
            val avatarId = effectiveSender.imagePath?.let { path ->
                runCatching {
                    val (bytes, hash) = encodedAvatarFor(path) ?: return@runCatching null
                    val key = "${room.remoteId}/$hash"
                    if (key in uploadedAvatarKeys ||
                        firestore.uploadAvatar(
                            room.remoteId, hash,
                            java.util.Base64.getEncoder().encodeToString(bytes),
                        )
                    ) {
                        uploadedAvatarKeys += key
                        hash
                    } else null
                }.getOrNull()
            }
            val textOk = firestore.postMessage(
                room.remoteId,
                messageValues(
                    type = "TEXT", body = marked, sender = effectiveSender,
                    isOoc = isOoc, authorUid = authorUid(),
                    avatarId = avatarId,
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
                            diceOutcome = Rules.judgeOutcome(room.rule ?: Rules.COC7, result),
                        ),
                    )
                }
            }
            onResult(textOk, diceOk)
            // 화면 반영은 증분 폴링(≤2.5초)이 담당 — 전체 재조회 금지
        }
    }

    /**
     * 방 로그 리셋 — 서버를 먼저 비우고 성공 시에만 로컬을 비운다 (모바일 N2와 동일 순서).
     * 문서 삭제가 상대 기기의 REMOVED 리스너로 전파되어 상대 로그도 함께 지워진다.
     */
    fun resetRoomLogs(onDone: (Boolean) -> Unit) {
        val room = selected ?: return onDone(false)
        scope.launch(Dispatchers.IO) {
            val ids = firestore.listMessages(room.remoteId)?.map { it.docId }
            val ok = ids != null && ids.all { firestore.deleteMessage(room.remoteId, it) }
            if (ok) {
                val session = sessionFor(room.remoteId)
                session.deletedDocIds.addAll(ids.orEmpty())
                messages = emptyList()
                session.messages = emptyList()
                RoomCacheStore.delete(room.remoteId) // 파일 캐시도 초기화
                // 리셋 흔적을 양쪽에 남긴다 — 모바일과 동일 문구
                firestore.postMessage(
                    room.remoteId,
                    mapOf(
                        "type" to "SYSTEM", "body" to "방 로그가 초기화되었습니다",
                        "createdAt" to System.currentTimeMillis(),
                        "authorUid" to authorUid(),
                        "isOoc" to false, "senderIsGm" to false, "senderIsBot" to false,
                    ),
                )
            }
            onDone(ok)
        }
    }

    /** 방 나가기 — 이 PC의 목록에서 제거 + 서버 멤버 문서 정리. 서버 로그는 남는다 */
    fun leaveRoom(room: JoinedRoom) {
        rooms = rooms.filterNot { it.remoteId == room.remoteId }
        if (selected?.remoteId == room.remoteId) selected = rooms.firstOrNull()
        roomSessions.remove(room.remoteId)
        persist()
        scope.launch(Dispatchers.IO) {
            RoomCacheStore.delete(room.remoteId)
            runCatching { firestore.leaveRoom(room.remoteId, config.deviceId) }
        }
    }

    /** HTML 로그 내보내기 — 모바일과 동일 형식. 아바타는 방 avatars 문서에서 받아 내장 */
    fun exportLogs() {
        val room = selected ?: return
        val snapshot = messages
        scope.launch(Dispatchers.IO) {
            val fd = java.awt.FileDialog(null as java.awt.Frame?, "세션 로그 저장", java.awt.FileDialog.SAVE)
            fd.file = "${room.name}-log.html"
            fd.isVisible = true
            val dir = fd.directory ?: return@launch
            val file = fd.file ?: return@launch
            runCatching {
                val html = com.pbp.desktop.export.LogExporter.buildHtml(
                    roomName = room.name,
                    messages = snapshot,
                    myUid = authorUid(),
                    avatarDataUri = { id ->
                        fetchAvatarCached(firestore, room.remoteId, id)
                            ?.let { com.pbp.desktop.export.LogExporter.bytesToDataUri(it) }
                    },
                )
                java.io.File(dir, file).writeText(html, Charsets.UTF_8)
            }.onFailure { System.err.println("로그 저장 실패: $it") }
        }
    }

    /** 프로필 삭제 — 전역 목록의 인덱스를 참조하는 각 방의 활성 인덱스도 함께 재매핑 */
    fun deleteProfileAt(index: Int) {
        if (profiles.size <= 1) return
        val newProfiles = profiles.filterIndexed { i, _ -> i != index }
        rooms = rooms.map { r ->
            val idx = r.activeProfileIndex
            val fixed = when {
                idx > index -> idx - 1
                idx == index -> newProfiles.indexOfFirst { !it.isGm }.coerceAtLeast(0)
                else -> idx
            }
            if (fixed != idx) r.copy(activeProfileIndex = fixed) else r
        }
        selected = rooms.firstOrNull { it.remoteId == selected?.remoteId }
        profiles = newProfiles
        persist()
    }

    /** 메시지 편집 — 로컬 즉시 반영 후 전파. 실패는 알림만 (모바일과 동일한 한계 수용) */
    fun editMessage(target: Message, newBody: String) {
        val body = newBody.trim()
        if (body.isEmpty()) return
        val room = selected ?: return
        val editedAt = System.currentTimeMillis()
        messages = messages.map {
            if (it.docId == target.docId) it.copy(body = body, editedAt = editedAt) else it
        }
        sessionFor(room.remoteId).messages = messages
        scope.launch(Dispatchers.IO) {
            if (!firestore.updateMessage(room.remoteId, target.docId, body, editedAt)) {
                System.err.println("편집 전파 실패 — 상대 화면에는 반영되지 않을 수 있습니다")
            }
        }
    }

    /** 메시지 삭제 — 로컬 즉시 제거 후 전파 */
    fun deleteMessage(target: Message) {
        val room = selected ?: return
        val session = sessionFor(room.remoteId)
        session.deletedDocIds += target.docId
        messages = messages.filterNot { it.docId == target.docId }
        session.messages = messages
        scope.launch(Dispatchers.IO) {
            if (!firestore.deleteMessage(room.remoteId, target.docId)) {
                System.err.println("삭제 전파 실패 — 상대 화면에는 반영되지 않을 수 있습니다")
            }
        }
    }

    fun switchProfile(index: Int) {
        val room = selected ?: return
        if (room.activeProfileIndex == index) return
        val updated = room.copy(activeProfileIndex = index)
        rooms = rooms.map { if (it.remoteId == room.remoteId) updated else it }
        selected = updated
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

    // 앱 전체 글꼴 적용 — 서술(명조)처럼 명시 지정한 곳은 그대로 유지된다
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.merge(
            TextStyle(fontFamily = appFontFamily(appFont))
        )
    ) {
    Row(Modifier.fillMaxSize().background(Tokens.Bg)) {
        LeftPane(
            rooms = rooms,
            selected = selected,
            onSelect = { selected = it },
            onCreate = { overlay = OverlayKind.CreateRoom },
            onJoin = { overlay = OverlayKind.JoinRoom },
            onFontSetting = { overlay = OverlayKind.FontSetting },
            onLeave = { leaveTarget = it },
            ownerName = ownerName,
            ownerColor = ownerColor,
            ownerImagePath = ownerImagePath,
            // 오너 아이콘 = 프로필 관리 진입 (오너 편집은 관리 목록에서)
            onOwnerProfile = { overlay = OverlayKind.ProfileManager },
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
                // mine 판정은 전송 authorUid와 같은 기준(auth UID 우선)이어야 한다 —
                // 다르면 익명 인증이 켜지는 순간 내 메시지가 상대편으로 렌더링된다
                deviceId = authorUid(),
                avatarCache = avatarCache,
                firestore = firestore,
                onSend = ::sendMessage,
                onSwitchProfile = ::switchProfile,
                onAddProfile = { overlay = OverlayKind.NewProfile },
                onShowCode = { overlay = OverlayKind.ShowCode },
                onOpenSettings = { overlay = OverlayKind.RoomSettings },
                onMessageLongPress = { messageAction = it },
                onEditProfile = { editProfileIndex = it },
                onExport = ::exportLogs,
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
                            backgroundKey = meta.backgroundKey ?: "preset_lighthouse", isMaster = false,
                            rule = meta.rule,
                            // 참여자의 기본 발화는 GM이 아닌 첫 캐릭터 (서술 권한은 마스터 전용)
                            activeProfileIndex = profiles.indexOfFirst { !it.isGm }.coerceAtLeast(0),
                        )
                        if (existing == null) {
                            rooms = rooms + joined
                            // 참여 인사 — 오너 프로필명으로 (처음 참여할 때 한 번, 모바일과 동일)
                            firestore.postMessage(
                                meta.remoteId,
                                mapOf(
                                    "type" to "SYSTEM",
                                    "body" to "'${ownerName.ifBlank { "플레이어" }}' 님이 참여하셨습니다.",
                                    "createdAt" to System.currentTimeMillis(),
                                    "authorUid" to authorUid(),
                                    "isOoc" to false, "senderIsGm" to false, "senderIsBot" to false,
                                ),
                            )
                        }
                        selected = joined
                        persist()
                        overlay = null
                    }
                }
            },
        )
        OverlayKind.CreateRoom -> CreateOverlay(
            onDismiss = { overlay = null },
            onCreate = { name ->
                scope.launch(Dispatchers.IO) {
                    val code = inviteCode()
                    val meta = firestore.createRoom(name.ifBlank { "새 세션" }, code, "coc7")
                    if (meta != null) {
                        firestore.ensureMember(meta.remoteId)
                        // 매핑 생성 실패 = 아무도 참가할 수 없는 초대코드 — 알린다 (C13)
                        if (!firestore.createInviteCode(code, meta.remoteId)) {
                            System.err.println("초대 코드 매핑 생성 실패 — 방 설정에서 다시 공유해야 합니다")
                        }
                        val joined = JoinedRoom(
                            remoteId = meta.remoteId, name = meta.name, icon = meta.icon,
                            inviteCode = code, themeColor = meta.themeColor,
                            backgroundKey = meta.backgroundKey ?: "preset_lighthouse", isMaster = true,
                            rule = meta.rule ?: "coc7",
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
            onResetLogs = ::resetRoomLogs,
            onApply = { theme, background ->
                val room = selected ?: return@SettingsOverlay
                // 같은 인스턴스의 var를 고치면 Compose가 변화를 모른다 —
                // 새 인스턴스로 교체해야 배경·테마가 즉시 화면에 반영된다 (버그 수정)
                val updated = room.copy(themeColor = theme, backgroundKey = background)
                val oldBackground = room.backgroundKey
                rooms = rooms.map { if (it.remoteId == room.remoteId) updated else it }
                selected = updated
                persist()
                // 교체된 커스텀 배경 파일은 다른 방이 안 쓰면 삭제 — 폴더 무한 누적 방지 (L3-1)
                if (oldBackground != background && !oldBackground.startsWith("preset_") &&
                    rooms.none { it.backgroundKey == oldBackground }
                ) {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val f = java.io.File(oldBackground)
                            val bgDir = java.io.File(System.getProperty("user.home"), ".pbp-desktop/backgrounds")
                            if (f.parentFile?.canonicalFile == bgDir.canonicalFile) f.delete()
                        }
                    }
                }
                // PATCH가 서버에 착지하기 전 폴링이 옛 값을 다시 덮지 않도록 유예 (P3-14)
                metaFreezeUntil = System.currentTimeMillis() + 15_000
                scope.launch(Dispatchers.IO) {
                    firestore.updateRoomSettings(room.remoteId, theme, background)
                }
                overlay = null
            },
        )
        OverlayKind.FontSetting -> FontOverlay(
            current = appFont,
            onDismiss = { overlay = null },
            onSelect = { value ->
                appFont = value
                config.appFont = value
                persist()
            },
        )
        OverlayKind.ProfileManager -> ProfileManagerOverlay(
            ownerName = ownerName,
            ownerColor = ownerColor,
            ownerImagePath = ownerImagePath,
            profiles = profiles,
            onDismiss = { overlay = null },
            onOwner = { overlay = OverlayKind.OwnerProfile },
            onProfile = { index ->
                overlay = null
                editProfileIndex = index
            },
            onAdd = { overlay = OverlayKind.AddProfileChoice },
        )
        OverlayKind.AddProfileChoice -> AddProfileChoiceOverlay(
            onDismiss = { overlay = null },
            onEmpty = { overlay = OverlayKind.NewProfile },
            onClipboard = {
                // 모바일과 동일: 클립보드 코드를 즉시 파싱해 캐릭터 생성 (전역)
                val imported = runCatching {
                    val clip = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                        .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                    CharacterCodec.parse(clip ?: "")
                }.getOrNull()
                if (imported != null) {
                    profiles = profiles + Profile(
                        name = imported.name,
                        stats = ProfileStats.sanitize(imported.stats.toMap())
                            .takeIf { it.isNotEmpty() },
                    )
                    persist()
                    overlay = OverlayKind.ProfileManager
                    true
                } else {
                    false
                }
            },
        )
        OverlayKind.OwnerProfile -> OwnerProfileOverlay(
            initialName = ownerName,
            initialColor = ownerColor,
            initialImage = ownerImagePath,
            forced = ownerName.isBlank(), // 미설정이면 저장 전에는 닫을 수 없다
            onDismiss = { overlay = null },
            onSave = { name, color, image ->
                ownerName = name
                ownerColor = color
                ownerImagePath = image
                config.ownerName = name
                config.ownerColor = color
                config.ownerImagePath = image
                persist()
                overlay = null
            },
        )
        null -> {}
    }

    // 메시지 편집/삭제 팝업 — 앱의 길게 누르기 액션 시트와 동일 흐름
    leaveTarget?.let { target ->
        OverlayScaffold("방 나가기", onDismiss = { leaveTarget = null }) {
            Text(
                "'${target.name}' 방을 이 PC의 목록에서 제거합니다.\n" +
                    "서버 로그는 남아 있으며, 초대 코드로 다시 참여할 수 있습니다.",
                fontSize = 13.sp, color = Tokens.InkDim,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YellowButton("나가기", Modifier.weight(1f)) {
                    leaveRoom(target)
                    leaveTarget = null
                }
                GhostButton("취소", Modifier.weight(1f)) { leaveTarget = null }
            }
        }
    }
    messageAction?.let { target ->
        OverlayScaffold("메시지", onDismiss = { messageAction = null }) {
            YellowButton("편집", Modifier.fillMaxWidth()) {
                messageEdit = target
                messageAction = null
            }
            Spacer(Modifier.height(8.dp))
            GhostButton("삭제", Modifier.fillMaxWidth()) {
                messageDelete = target
                messageAction = null
            }
        }
    }
    messageEdit?.let { target ->
        EditMessageOverlay(
            initial = target.body,
            onDismiss = { messageEdit = null },
            onSave = { body ->
                editMessage(target, body)
                messageEdit = null
            },
        )
    }
    editProfileIndex?.let { idx ->
        profiles.getOrNull(idx)?.let { prof ->
            ProfileOverlay(
                onDismiss = { editProfileIndex = null },
                onSave = { updated ->
                    profiles = profiles.mapIndexed { i, p -> if (i == idx) updated else p }
                    persist()
                    editProfileIndex = null
                },
                editing = prof,
                // GM은 서술의 주체라 삭제 불가, 마지막 남은 프로필도 삭제 불가
                onDelete = if (!prof.isGm && profiles.size > 1) {
                    {
                        deleteProfileAt(idx)
                        editProfileIndex = null
                    }
                } else null,
            )
        }
    }
    messageDelete?.let { target ->
        OverlayScaffold("메시지 삭제", onDismiss = { messageDelete = null }) {
            Text(
                "이 메시지를 삭제합니다. 상대 화면에서도 삭제됩니다.",
                fontSize = 13.sp, color = Tokens.InkDim,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YellowButton("삭제", Modifier.weight(1f)) {
                    deleteMessage(target)
                    messageDelete = null
                }
                GhostButton("취소", Modifier.weight(1f)) { messageDelete = null }
            }
        }
    }
    } // CompositionLocalProvider — 앱 전체 글꼴
}

/**
 * 시작 시 1회성 config 로드. runBlocking이라 호출 스레드는 여전히 블록된다 —
 * 수 ms짜리 로드라 실해가 없어 유지 (F3에서 주석 정정). 진짜 비동기화가 필요해지면
 * produceState로 전환할 것.
 */
private fun <T> runBlockingIo(block: () -> T): T =
    kotlinx.coroutines.runBlocking(Dispatchers.IO) { block() }

/** 아바타 fetch in-flight 집합 — 스냅샷 상태가 아니라 리컴포지션을 유발하지 않는다 (R1) */
private val avatarsInFlight: MutableSet<String> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()

private enum class OverlayKind {
    JoinRoom, CreateRoom, NewProfile, ShowCode, RoomSettings, FontSetting, OwnerProfile,
    ProfileManager, AddProfileChoice,
}

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
    avatarId: String? = null,
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
    "avatarId" to avatarId,
)

// ══════════════ 왼쪽 패널: 방 목록 ══════════════

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LeftPane(
    rooms: List<JoinedRoom>,
    selected: JoinedRoom?,
    onSelect: (JoinedRoom) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onFontSetting: () -> Unit,
    onLeave: (JoinedRoom) -> Unit,
    ownerName: String,
    ownerColor: Long,
    ownerImagePath: String?,
    onOwnerProfile: () -> Unit,
) {
    // PC 규격: 사이드바 280dp 고정 (trpg-app-mockup-pc-light.html)
    Column(
        Modifier.width(280.dp).fillMaxHeight()
            .background(Brush.verticalGradient(listOf(Color(0xFFFBF9F4), Color(0xFFF0EDE5)))),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 새 앱 아이콘(시안 02 '포스트잇')과 동일한 옐로 타일 + 잉크 d10
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFFD05C), Color(0xFFEFB945)))),
                contentAlignment = Alignment.Center,
            ) { D10Mark(Modifier.size(width = 20.dp, height = 21.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // 라이트 모드 "PbP" 강조색 = 잉크 블랙 (스펙 2장)
                Text(
                    "PbP", fontFamily = GowunBatang, fontWeight = FontWeight.Bold,
                    fontSize = 18.sp, color = Tokens.Ink,
                )
                Text("진행 중인 세션 ${rooms.size} · PC", fontSize = 11.sp, color = Tokens.InkDim)
            }
            // 오너 프로필 — 탭하여 편집 (모바일 방 목록의 오너 칩과 동일)
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .background(Color(ownerColor))
                    .clickable(onClick = onOwnerProfile),
                contentAlignment = Alignment.Center,
            ) {
                val ownerImage = rememberLocalBitmap(ownerImagePath)
                if (ownerImage != null) {
                    Image(ownerImage, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(
                        ownerName.take(1).ifEmpty { "?" },
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10151C),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            // 앱 글꼴 설정 — 모바일 방 목록의 'Aa' 버튼과 동일 위계
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .border(1.dp, Tokens.Line, CircleShape)
                    .clickable(onClick = onFontSetting),
                contentAlignment = Alignment.Center,
            ) {
                Text("Aa", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
            }
        }
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp),
        ) {
            items(rooms, key = { it.remoteId }) { room ->
                val active = room.remoteId == selected?.remoteId
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (active) Color(room.themeColor).copy(alpha = .14f)
                            else Color(0x0914191F)
                        )
                        .border(
                            1.dp,
                            if (active) Color(room.themeColor).copy(alpha = .45f) else Tokens.Line,
                            RoundedCornerShape(16.dp),
                        )
                        // 탭 = 선택, 길게 = 나가기 (앱 방 목록의 길게 누르기와 동일 위계)
                        .combinedClickable(
                            onClick = { onSelect(room) },
                            onLongClick = { onLeave(room) },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(48.dp)) {
                        // 방 아이콘 폐지 — 배경(프리셋 그라데이션 또는 커스텀 이미지)으로만 구분
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))) {
                            BackgroundLayer(room.backgroundKey, Modifier.fillMaxSize())
                        }
                        Box(
                            Modifier.size(14.dp)
                                .align(Alignment.BottomEnd)
                                .border(3.dp, Color(0xFFFBF9F4), CircleShape)
                                .clip(CircleShape)
                                .background(Color(room.themeColor))
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            room.name, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (room.isMaster) "마스터 · 코드 ${room.inviteCode ?: "-"}" else "참여자",
                            fontSize = 11.sp, color = Tokens.InkDim,
                        )
                    }
                }
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YellowButton("＋ 새 세션", Modifier.weight(1f), onCreate)
            GhostButton("코드로 참여", Modifier.weight(1f), onJoin)
        }
    }
}

/**
 * 방 배경 — backgroundKey가 preset_*이면 그라데이션, 아니면 로컬 이미지 파일(커스텀).
 * 파일이 없거나 읽기 실패면 등대 프리셋으로 폴백 (모바일 RoomBackdrop과 동일 규칙)
 */
@Composable
private fun BackgroundLayer(backgroundKey: String, modifier: Modifier = Modifier) {
    val preset = Tokens.backgroundPresets[backgroundKey]
    if (preset == null) {
        val bitmap by produceState<ImageBitmap?>(null, backgroundKey) {
            // 경로 공용 캐시 (M2) — 채팅 배경과 방 목록 썸네일이 같은 이미지를
            // 각각 디코드해 이중 상주(~10-20MB)하던 것 제거
            value = backgroundBitmapCache[backgroundKey] ?: withContext(Dispatchers.IO) {
                runCatching {
                    org.jetbrains.skia.Image.makeFromEncoded(java.io.File(backgroundKey).readBytes())
                        .toComposeImageBitmap()
                }.getOrNull()?.also {
                    if (backgroundBitmapCache.size >= 8) backgroundBitmapCache.clear() // 상한 (M3)
                    backgroundBitmapCache[backgroundKey] = it
                }
            }
        }
        bitmap?.let {
            Image(
                bitmap = it, contentDescription = null, modifier = modifier,
                contentScale = ContentScale.Crop,
            )
            return
        }
    }
    val colors = preset ?: Tokens.backgroundPresets.getValue("preset_lighthouse")
    Box(modifier.background(Brush.verticalGradient(listOf(Color(colors.first), Color(colors.second)))))
}

/** 새 앱 아이콘과 같은 d10 마크 — 잉크 면 5개 + 옐로 분할선 (모바일 ic_logo_d10과 동일 지오메트리) */
@Composable
private fun D10Mark(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val faces = listOf(
            listOf(50f to 6f, 9f to 54f, 33f to 60f) to Color(0xFF23272E),
            listOf(9f to 54f, 50f to 98f, 33f to 60f) to Color(0xFF181C22),
            listOf(50f to 6f, 91f to 54f, 67f to 60f) to Color(0xFF2A2F38),
            listOf(91f to 54f, 50f to 98f, 67f to 60f) to Color(0xFF1D222A),
            listOf(50f to 6f, 67f to 60f, 50f to 98f, 33f to 60f) to Color(0xFF23272E),
        )
        val sx = size.width / 100f
        val sy = size.height / 104f
        faces.forEach { (points, color) ->
            val path = androidx.compose.ui.graphics.Path().apply {
                points.forEachIndexed { index, (x, y) ->
                    if (index == 0) moveTo(x * sx, y * sy) else lineTo(x * sx, y * sy)
                }
                close()
            }
            drawPath(path, color)
            drawPath(
                path,
                Color(0xFFFFD05C),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2f * sx,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
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
    onMessageLongPress: (Message) -> Unit,
    onEditProfile: (Int) -> Unit,
    onExport: () -> Unit,
) {
    val theme = Color(room.themeColor)
    Box(Modifier.fillMaxSize()) {
        BackgroundLayer(room.backgroundKey, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Tokens.VeilTop, Tokens.VeilMid, Tokens.VeilTop)))
        )
        Column(Modifier.fillMaxSize()) {
            // 상단 바 — 높이 56, 좌우 24(PC 가장자리), 밝은 화이트 그라데이션
            Row(
                Modifier.fillMaxWidth().height(56.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color(0xD9FFFFFF), Color(0x59FFFFFF)))
                    )
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        room.name, fontFamily = GowunBatang, fontWeight = FontWeight.Bold,
                        fontSize = 15.sp, color = Tokens.Ink, maxLines = 1,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(3.dp)).background(theme))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (room.isMaster) "마스터" else "참여자",
                            fontSize = 10.sp, color = Tokens.InkDim,
                        )
                    }
                }
                GhostButton("내보내기", Modifier, onExport)
                Spacer(Modifier.width(8.dp))
                GhostButton("초대 코드", Modifier, onShowCode)
                // 테마·배경 변경은 누구나 가능 (모바일과 동일 정책)
                Spacer(Modifier.width(8.dp))
                GhostButton("방 설정", Modifier, onOpenSettings)
            }

            // 메시지 목록 — 최신 메시지가 바뀔 때만, 바닥 근처를 보고 있을 때만 따라간다
            // (안드로이드 P1-7과 같은 규칙, C9)
            val listState = rememberLazyListState()
            // 방 입장 직후의 첫 로드는 무조건 최하단으로 (S2 — 빈 목록 기준 lastVisible=-1이라
            // 근접 판정이 항상 실패했음). 내 전송은 앱과 동일하게 전송 시점에 플래그를
            // 세워 실제 도착까지 유지한다 (N4와 동일 규칙).
            var initialScrollDone by remember(room.remoteId) { mutableStateOf(false) }
            var pendingScrollToLatest by remember(room.remoteId) { mutableStateOf(false) }
            // 한 폴링 배치로 여러 건이 오면(판정 쌍·오랜만의 수신) 근접 판정이 도착 수만큼
            // 어긋난다 — 직전 최신 메시지 위치로 추가 수를 세어 보정 (모바일과 동일 규칙)
            var prevLatestId by remember(room.remoteId) { mutableStateOf<String?>(null) }
            LaunchedEffect(messages.lastOrNull()?.docId, pendingScrollToLatest) {
                if (messages.isEmpty()) {
                    prevLatestId = null
                    return@LaunchedEffect
                }
                val prevIndex = prevLatestId?.let { id -> messages.indexOfLast { it.docId == id } } ?: -1
                val appended = if (prevIndex >= 0) messages.size - 1 - prevIndex else messages.size
                prevLatestId = messages.last().docId
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                val nearBottom = lastVisible >= messages.size - appended - 2
                val myMessageArrived = messages.last().authorUid == deviceId
                if (!initialScrollDone || pendingScrollToLatest || nearBottom || myMessageArrived) {
                    listState.scrollToItem(messages.size - 1)
                    initialScrollDone = true
                    if (myMessageArrived) pendingScrollToLatest = false
                }
            }
            // 본문 최대 폭 720dp 중앙 정렬 — 초광폭에서 말풍선이 늘어지지 않게 (PC 규격)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxHeight().widthIn(max = 720.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    // 같은 인물의 연속 메시지는 아바타·이름 생략 + 간격 축소 (모바일과 동일)
                    items(messages.size, key = { messages[it].docId }) { index ->
                        val message = messages[index]
                        val grouped = isContinuation(messages.getOrNull(index - 1), message)
                        Box(
                            Modifier.padding(top = if (index == 0) 0.dp else if (grouped) 2.dp else 12.dp)
                        ) {
                            MessageBlock(
                                message, deviceId, room, avatarCache, firestore, grouped,
                                onLongPress = onMessageLongPress,
                            )
                        }
                    }
                }
            }

            // 입력 영역 — 전송 시 스크롤 플래그를 세워 실제 도착까지 유지 (N4)
            InputZone(
                room = room,
                profiles = profiles,
                theme = theme,
                onSend = { text, ooc, onResult ->
                    pendingScrollToLatest = true
                    onSend(text, ooc, onResult)
                },
                onSwitchProfile = onSwitchProfile,
                onAddProfile = onAddProfile,
                onEditProfile = onEditProfile,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBlock(
    message: Message,
    deviceId: String,
    room: JoinedRoom,
    avatarCache: MutableMap<String, ImageBitmap?>,
    firestore: FirestoreRest,
    grouped: Boolean = false,
    onLongPress: (Message) -> Unit = {},
) {
    val mine = message.authorUid == deviceId
    when {
        message.type == "SYSTEM" -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xBFFFFFFF))
                        .border(1.dp, Color(0x1214191F), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                ) {
                    Text(message.body, fontSize = 10.sp, color = Color(0x8C23272E))
                }
            }
        }
        message.type == "DICE" -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xD9FFFFFF))
                        .border(1.dp, Color(0x80C89E34), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🎲", fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${message.diceExpr} → ${message.body}",
                        fontSize = 11.sp, color = Color(0xFF7A5B12), fontWeight = FontWeight.Bold,
                    )
                    // 판정 등급 — 성공 계열 파랑, 실패 빨강 (모바일과 동일 표기)
                    Rules.outcomeLabel(message.diceOutcome)?.let { label ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            label,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (Rules.isSuccess(message.diceOutcome)) Color(0xFF5E9EFF)
                            else Color(0xFFFF6B6B),
                        )
                    }
                }
            }
        }
        // 잡담은 극 밖의 대화 — 시스템 안내처럼 화면 중앙에 '이름 : 내용',
        // 배경은 그 캐릭터의 말풍선 색 반투명 (모바일과 동일)
        message.isOoc -> {
            val chatterColor = Color(message.senderBubbleColor ?: Tokens.bubblePresets.first())
                .copy(alpha = .55f)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(chatterColor)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (mine) onLongPress(message) },
                        )
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                ) {
                    Text(
                        "${message.senderName ?: ""} : ${message.body}",
                        fontSize = 10.sp,
                        color = Tokens.BubbleInk.copy(alpha = .85f),
                    )
                }
            }
        }
        message.senderIsGm -> {
            // 정규식 분해를 리컴포지션마다 반복하지 않는다 (F2)
            val parts = remember(message.body) { GmSpeech.split(message.body) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                parts.forEach { part ->
                    when (part) {
                        is GmSpeech.Part.Narration -> NarrationBlock(
                            message, part.text,
                            onLongPress = { if (mine) onLongPress(message) },
                        )
                        is GmSpeech.Part.Quote -> BubbleRow(
                            message = message, deviceId = deviceId, room = room,
                            avatarCache = avatarCache, firestore = firestore,
                            overrideBody = part.text, overrideName = "GM",
                            overrideBubbleColor = Tokens.gmQuoteBubble,
                            onLongPress = onLongPress,
                        )
                    }
                }
            }
        }
        else -> {
            // 캐릭터 발화도 GM과 같은 규칙 — 문장 중간의 " " 대사만 인용 말풍선으로 분리 (F2: remember)
            val parts = remember(message.body) { GmSpeech.split(message.body) }
            if (parts.size <= 1) {
                BubbleRow(
                    message, deviceId, room, avatarCache, firestore,
                    showHeader = !grouped,
                    onLongPress = onLongPress,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    parts.forEachIndexed { index, part ->
                        BubbleRow(
                            message = message, deviceId = deviceId, room = room,
                            avatarCache = avatarCache, firestore = firestore,
                            overrideBody = part.text(),
                            quoteBubble = part is GmSpeech.Part.Quote,
                            showHeader = !grouped && index == 0,
                            showTime = index == parts.lastIndex,
                            onLongPress = onLongPress,
                        )
                    }
                }
            }
        }
    }
}

/** 같은 인물의 연속 말풍선인지 — 아바타·이름 생략과 간격 축소 판정 (모바일과 동일 규칙) */
private fun isContinuation(prev: Message?, current: Message): Boolean {
    if (prev == null) return false
    fun isBubble(m: Message) = m.type == "TEXT" && !m.senderIsGm && !m.isOoc
    if (!isBubble(prev) || !isBubble(current)) return false
    return prev.senderName == current.senderName && prev.authorUid == current.authorUid
}

private fun GmSpeech.Part.text(): String = when (this) {
    is GmSpeech.Part.Narration -> text
    is GmSpeech.Part.Quote -> text
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NarrationBlock(message: Message, text: String, onLongPress: () -> Unit = {}) {
    val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
    Column(
        Modifier.fillMaxWidth()
            .shadow(3.dp, shape) // 목업 box-shadow 0 3px 12px
            .clip(shape)
            .background(Tokens.NarrBg)
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // 서술은 문단 자체가 화면 — 서술자·시간 등 메타 표기는 두지 않는다 (모바일과 동일)
        MarkupText(
            text = text, fontSize = 13.sp, color = Tokens.NarrInk,
            rubyColor = Tokens.SignatureInk, fontFamily = GowunBatang, lineHeight = 24.sp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    /** 이 조각이 대사(인용)임을 호출부가 이미 판정한 경우 */
    quoteBubble: Boolean = false,
    showHeader: Boolean = true, // false = 연속 메시지 (아바타·이름 생략)
    showTime: Boolean = true, // 한 메시지가 여러 말풍선으로 나뉘면 마지막에만
    onLongPress: (Message) -> Unit = {},
) {
    val mine = message.authorUid == deviceId && overrideName == null
    // 편집/삭제 대상 여부는 표시 방향(mine)과 무관하게 실제 작성자 기준 (앱과 동일)
    val editable = message.authorUid == deviceId
    val body = overrideBody ?: message.body
    // 대사는 인용 말풍선 — 모바일과 동일 규칙 (목업 mockup-quote-bubble)
    val quoteInner = when {
        message.isOoc -> null
        quoteBubble -> body
        overrideName == null -> quoteContent(body)
        else -> null
    }
    val bubbleColor = when {
        message.isOoc -> Tokens.ChatterBubble
        else -> Color(overrideBubbleColor ?: message.senderBubbleColor ?: Tokens.bubblePresets.first())
    }
    val nameColor = when {
        message.isOoc -> Tokens.InkDim
        overrideName != null -> Tokens.SignatureInk
        // 밝은 배경 위에서는 저장된 밝은 이름색을 진한 색으로 치환 (스펙 2장)
        message.senderNameColor != null -> Color(Tokens.nameColorForLight(message.senderNameColor))
        else -> Tokens.Ink
    }
    val inkColor = if (message.isOoc) Tokens.ChatterInk else Tokens.BubbleInk

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mine && showTime) {
                // 내 메시지: 시간은 말풍선 왼쪽 (모바일과 동일)
                TimeStamp(message, Color(room.themeColor), Modifier.align(Alignment.Bottom))
            }
            if (!mine) {
                if (showHeader) {
                    MessageAvatar(message, room, avatarCache, firestore)
                } else {
                    Box(Modifier.size(38.dp)) // 연속 메시지 — 자리만 유지
                }
            }
            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                if (showHeader) {
                    Text(
                        overrideName ?: message.senderName ?: "",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = nameColor,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                val shape = if (mine) {
                    RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
                } else {
                    RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
                }
                if (quoteInner != null) {
                    // 여는 “ 좌상단 · 닫는 ” 우하단 — 오프셋은 상하좌우 대칭(7·9dp).
                    // 닫는 따옴표는 글리프 잉크가 글자 상자 위쪽에 몰려 있어 offset으로 보정한다.
                    Box(
                        Modifier.widthIn(max = 420.dp)
                            .shadow(2.dp, shape) // 목업 box-shadow 0 2px 8px
                            .clip(shape).background(bubbleColor)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { if (editable) onLongPress(message) },
                            )
                    ) {
                        QuoteMark(
                            "“",
                            inkColor,
                            Modifier.align(Alignment.TopStart).padding(start = 9.dp, top = 5.dp),
                        )
                        QuoteMark(
                            "”",
                            inkColor,
                            Modifier.align(Alignment.BottomEnd).padding(end = 9.dp).offset(y = 6.dp),
                        )
                        MarkupText(
                            text = quoteInner, fontSize = 13.sp, color = inkColor,
                            rubyColor = inkColor.copy(alpha = .65f), lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp),
                        )
                    }
                } else {
                    Box(
                        Modifier.widthIn(max = 420.dp)
                            .shadow(2.dp, shape) // 목업 box-shadow 0 2px 8px
                            .clip(shape).background(bubbleColor)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { if (editable) onLongPress(message) },
                            )
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
                }
            }
            if (!mine && showTime) {
                // 남의 메시지: 시간은 말풍선 오른쪽 (모바일과 동일)
                TimeStamp(message, Color(room.themeColor), Modifier.align(Alignment.Bottom))
            }
            if (mine) {
                if (showHeader) {
                    MessageAvatar(message, room, avatarCache, firestore)
                } else {
                    Box(Modifier.size(38.dp)) // 연속 메시지 — 자리만 유지
                }
            }
        }
    }
}

/** 말풍선 곁 시간 + (수정됨) — 모바일 TimeStamp와 동일. 시간 색 = 방 테마 컬러 */
@Composable
private fun TimeStamp(message: Message, themeColor: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (message.editedAt != null) {
            Text("(수정됨)", fontSize = 9.sp, color = Tokens.InkDim)
        }
        Text(formatTime(message.createdAt), fontSize = 10.sp, color = themeColor)
    }
}

/**
 * 본문 전체가 쌍따옴표(" 또는 “ ”)로 감싸인 대사인지 — 감싸였으면 안쪽 내용을 돌려준다.
 * 모바일 ChatScreen의 quoteContent와 동일 규칙.
 */
private fun quoteContent(body: String): String? {
    val trimmed = body.trim()
    if (trimmed.length < 2) return null
    if (trimmed.first() !in "\"“" || trimmed.last() !in "\"”") return null
    return trimmed.substring(1, trimmed.length - 1).trim().ifEmpty { null }
}

/** 인용 말풍선의 장식 따옴표 — 명조 볼드, 말풍선 잉크의 옅은 톤 */
@Composable
private fun QuoteMark(mark: String, inkColor: Color, modifier: Modifier) {
    Text(
        mark,
        fontFamily = GowunBatang,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 24.sp,
        color = inkColor.copy(alpha = .32f),
        modifier = modifier,
    )
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
                fetchAvatarCached(firestore, room.remoteId, avatarId)?.let { bytes ->
                    runCatching {
                        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                    }.getOrNull()
                }
            }
            // 실패는 캐시하지 않는다 — 다음 표시 때 재시도 (P3-15)
            if (bitmap != null) {
                // 단순 상한 (M3) — 초과 시 비움. 디스크 캐시(P9)가 재적재를 싸게 만든다
                if (avatarCache.size >= 64) avatarCache.clear()
                avatarCache[avatarId] = bitmap
            }
        } finally {
            avatarsInFlight.remove(avatarId)
        }
    }
    val bitmap = avatarId?.let { avatarCache[it] }
    Box(
        Modifier.size(38.dp)
            .border(1.5.dp, Color.White.copy(alpha = .85f), CircleShape)
            .clip(CircleShape)
            .background(Tokens.Panel2)
            .alpha(if (message.isOoc) 0.55f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap, contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(message.senderEmoji ?: "🙂", fontSize = 16.sp)
        }
    }
}

// ══════════════ 입력 영역 ══════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InputZone(
    room: JoinedRoom,
    profiles: List<Profile>,
    theme: Color,
    onSend: (String, Boolean, (Boolean, Boolean) -> Unit) -> Unit,
    onSwitchProfile: (Int) -> Unit,
    onAddProfile: () -> Unit,
    onEditProfile: (Int) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var oocOn by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 전송 — 버튼과 Ctrl+Enter가 공유
    val doSend = send@{
        if (input.isBlank()) return@send
        val text = input
        val ooc = oocOn
        input = ""
        errorMessage = null
        // 전송 실패 시 입력을 복원해 무통보 소실을 막는다 (P1-5).
        // 단 본문이 이미 올라갔으면 복원하지 않는다 — 재전송 시 2건이 된다 (N3)
        onSend(text, ooc) { textOk, diceOk ->
            when {
                !textOk -> {
                    if (input.isEmpty()) {
                        // 늦게 도착한 실패 콜백이 새로 친 글을 덮지 않도록 (N3)
                        input = text
                        oocOn = ooc
                        errorMessage = "전송에 실패했습니다 — 네트워크를 확인해주세요"
                    } else {
                        // 새 입력을 이미 치고 있으면 원문이 갈 곳이 없다 —
                        // 에러 라인에 원문을 남겨 복사할 수 있게 (C3)
                        errorMessage = "전송 실패 — 잃은 내용: $text"
                    }
                }
                !diceOk -> errorMessage = "메시지는 전송됐지만 다이스 결과 전송에 실패했습니다"
            }
        }
    }

    Column(Modifier.fillMaxWidth().background(Color(0xEBFFFFFF))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Line))
        // 본문과 같은 720dp 중앙 정렬 (PC 규격)
        Column(
            Modifier.align(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp).fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
        ) {
            // 참여자에게는 GM 프로필을 숨긴다 — 서술 권한은 마스터 전용 (모바일과 동일)
            val visible = profiles.withIndex().filter { room.isMaster || !it.value.isGm }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visible.size) { vi ->
                    val (index, profile) = visible[vi]
                    val on = index == room.activeProfileIndex
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        // 탭 = 전환, 길게 = 편집 (앱 프로필 스트립과 동일)
                        modifier = Modifier.combinedClickable(
                            onClick = { onSwitchProfile(index) },
                            onLongClick = { onEditProfile(index) },
                        ),
                    ) {
                        Box(
                            Modifier.size(36.dp)
                                .border(
                                    2.dp,
                                    when {
                                        on -> Tokens.SignatureRing
                                        profile.isGm -> Color(0x99C89E34) // GM 금테
                                        else -> Color(0x2614191F)
                                    },
                                    CircleShape,
                                )
                                .clip(CircleShape)
                                .background(Tokens.Panel2),
                            contentAlignment = Alignment.Center,
                        ) {
                            val chipImage = rememberLocalBitmap(profile.imagePath)
                            if (chipImage != null) {
                                Image(
                                    chipImage, null, Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Text(
                                    profile.emoji, fontSize = 15.sp,
                                    fontFamily = if (profile.isGm) GowunBatang else null,
                                    color = if (profile.isGm) Tokens.SignatureInk else Tokens.Ink,
                                )
                            }
                        }
                        Text(
                            profile.name, fontSize = 10.sp,
                            color = if (on) Tokens.SignatureInk else Tokens.InkDim,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(36.dp)
                                .border(1.dp, Color(0x4714191F), CircleShape)
                                .clip(CircleShape)
                                .clickable(onClick = onAddProfile),
                            contentAlignment = Alignment.Center,
                        ) { Text("＋", color = Tokens.InkDim, fontSize = 15.sp) }
                        Text("추가", fontSize = 10.sp, color = Tokens.InkDim)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 자동완성 채팅 팔레트 — 활성 캐릭터의 값 이름 부분 입력 시 판정 매크로 (모바일과 동일)
            val activeStats = profiles.getOrNull(room.activeProfileIndex)?.stats ?: emptyMap()
            val suggestions = ProfileStats.paletteSuggestions(input, activeStats)
            if (suggestions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { name ->
                        Text(
                            "$name 판정",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Tokens.SignatureInk,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color(0x2EFFD05C))
                                .border(1.dp, Color(0x66C89E34), RoundedCornerShape(999.dp))
                                .clickable {
                                    val command =
                                        Rules.judgeCommand(room.rule ?: Rules.COC7, name)
                                    input = ""
                                    errorMessage = null
                                    onSend("$command $name 판정", false) { textOk, _ ->
                                        if (!textOk) errorMessage = "판정 전송에 실패했습니다"
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (oocOn) Color(0x47FFD05C) else Color(0x0D14191F))
                        .border(
                            1.dp,
                            if (oocOn) Color(0x8CC89E34) else Tokens.Line,
                            RoundedCornerShape(999.dp),
                        )
                        .clickable { oocOn = !oocOn }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "잡담", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = if (oocOn) Color(0xFF7A5B12) else Tokens.InkDim,
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f)
                        .onPreviewKeyEvent { event ->
                            // PC는 Ctrl+Enter로 바로 전송 (모바일과 동일 규칙).
                            // 한글 IME 조합 중에는 KeyDown이 IME에 먹혀 도달하지 않으므로
                            // KeyUp 시점에 발사하고, Down/Up 모두 소비해 개행 삽입을 막는다
                            if (event.key == Key.Enter && event.isCtrlPressed) {
                                if (event.type == KeyEventType.KeyUp && input.isNotBlank()) doSend()
                                true
                            } else false
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x0D14191F))
                        .border(1.dp, Tokens.Line, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    textStyle = TextStyle(color = Tokens.Ink, fontSize = 13.sp),
                    cursorBrush = SolidColor(Tokens.SignatureRing),
                    maxLines = 4,
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                if (input.isEmpty()) {
                                    Text(
                                        if (oocOn) "잡담으로 보내기…" else "**굵게** · |等臺《등대》 · 1d100",
                                        fontSize = 12.sp, color = Tokens.InkDim,
                                    )
                                }
                                inner()
                            }
                            // PC는 Ctrl+Enter 힌트를 상시 노출 (trpg-app-mockup-pc-light.html)
                            Text(
                                "Ctrl+Enter 전송", fontSize = 10.sp, color = Color(0x5914191F),
                                modifier = Modifier.padding(start = 8.dp)
                                    .border(1.dp, Color(0x2614191F), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    },
                )
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (input.isNotBlank()) theme else theme.copy(alpha = .35f))
                        .clickable(enabled = input.isNotBlank()) { doSend() },
                    contentAlignment = Alignment.Center,
                ) { Text("➤", fontSize = 15.sp, color = Color.White) }
            }
            errorMessage?.let { message ->
                Text(
                    message,
                    fontSize = 11.sp,
                    color = Tokens.Danger,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// ══════════════ 오버레이(다이얼로그) ══════════════

@Composable
private fun OverlayScaffold(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        // 라이트 모드 딤 — rgba(30,35,45,.38) (목업 mockup-message-actions)
        Modifier.fillMaxSize().background(Color(0x611E232D)).clickable(onClick = onDismiss),
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
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x0A14191F))
            .border(1.dp, Tokens.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        textStyle = TextStyle(color = Tokens.Ink, fontSize = 14.sp),
        cursorBrush = SolidColor(Tokens.SignatureRing),
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
            .border(1.dp, Color(0x4014191F), RoundedCornerShape(999.dp))
            .background(Tokens.Panel)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
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
            Text("방을 찾지 못했습니다. 코드를 확인해주세요.", fontSize = 12.sp, color = Tokens.Danger)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YellowButton("참여", Modifier.weight(1f)) { if (code.isNotBlank()) onJoin(code) { failed = true } }
            GhostButton("취소", Modifier.weight(1f), onDismiss)
        }
    }
}

@Composable
private fun CreateOverlay(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    OverlayScaffold("새 세션", onDismiss) {
        OverlayField(name, { name = it }, "방 이름")
        Spacer(Modifier.height(8.dp))
        // 방 아이콘 폐지 — 배경으로만 구분. TRPG 룰은 크툴루의 부름 7판 고정 (모바일과 동일)
        Text("TRPG 룰: 크툴루의 부름 7판", fontSize = 12.sp, color = Tokens.Ink)
        Spacer(Modifier.height(4.dp))
        Text("방을 만들면 마스터 권한과 초대 코드가 부여됩니다.", fontSize = 12.sp, color = Tokens.InkDim)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YellowButton("만들기", Modifier.weight(1f)) { onCreate(name) }
            GhostButton("취소", Modifier.weight(1f), onDismiss)
        }
    }
}

@Composable
private fun ProfileOverlay(
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit,
    /** null이면 새 캐릭터, 아니면 이 프로필을 편집 */
    editing: Profile? = null,
    /** 편집 모드에서만 — null이면 삭제 버튼 숨김 */
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var emoji by remember { mutableStateOf(editing?.emoji ?: "") }
    var nameColor by remember { mutableStateOf(editing?.nameColor ?: Tokens.namePresets.first()) }
    var bubbleColor by remember { mutableStateOf(editing?.bubbleColor ?: Tokens.bubblePresets.first()) }
    var nameCustomOpen by remember { mutableStateOf(false) }
    var bubbleCustomOpen by remember { mutableStateOf(false) }
    // 캐릭터 값 — 앱 프로필 편집기의 value 목록과 동일 개념. {값이름} 치환·팔레트에 쓰인다
    val stats = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            editing?.stats?.forEach { (k, v) -> add(k to v) }
        }
    }
    var imagePath by remember { mutableStateOf(editing?.imagePath) }
    var pickingImage by remember { mutableStateOf(false) }
    val overlayScope = rememberCoroutineScope()
    OverlayScaffold(if (editing == null) "새 캐릭터" else "캐릭터 편집", onDismiss) {
        OverlayField(name, { name = it }, "캐릭터 이름")
        Spacer(Modifier.height(10.dp))
        OverlayField(emoji, { emoji = it }, "이모지 아바타 (비우면 🙂)")
        Spacer(Modifier.height(10.dp))
        // 프로필 이미지 — 로컬 512px 축소 저장, 전송 시 256px 축소본이 방에 업로드 (앱과 동일)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Tokens.Panel2)
                    .border(1.dp, Tokens.Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = rememberLocalBitmap(imagePath)
                if (bmp != null) {
                    Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(emoji.trim().ifEmpty { "🙂" }, fontSize = 17.sp)
                }
            }
            GhostButton(
                if (imagePath == null) "프로필 이미지 선택" else "이미지 변경",
                Modifier.weight(1f),
            ) {
                if (!pickingImage) {
                    pickingImage = true
                    overlayScope.launch(Dispatchers.IO) {
                        try {
                            pickAndStoreImage("프로필 이미지 선택", "avatars-local", 512)
                                ?.let { imagePath = it }
                        } finally {
                            pickingImage = false
                        }
                    }
                }
            }
            if (imagePath != null) {
                GhostButton("제거") { imagePath = null }
            }
        }
        Spacer(Modifier.height(10.dp))
        // 앱의 '클립보드 코드로 생성'과 동일 — ccfolia 캐릭터 JSON을 붙여넣은 상태로 클릭
        GhostButton("클립보드 캐릭터 코드 불러오기", Modifier.fillMaxWidth()) {
            runCatching {
                val clip = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                CharacterCodec.parse(clip ?: "")
            }.getOrNull()?.let { imported ->
                name = imported.name
                stats.clear()
                imported.stats.forEach { stats.add(it) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("이름 색", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SwatchRow(Tokens.namePresets, nameColor) { nameColor = it; nameCustomOpen = false }
            CustomSwatch(on = nameColor !in Tokens.namePresets) {
                nameCustomOpen = !nameCustomOpen
            }
        }
        if (nameCustomOpen) {
            Spacer(Modifier.height(8.dp))
            ColorPalettePicker(nameColor) { nameColor = it }
        }
        Spacer(Modifier.height(14.dp))
        Text("말풍선 색", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SwatchRow(Tokens.bubblePresets, bubbleColor) { bubbleColor = it; bubbleCustomOpen = false }
            CustomSwatch(on = bubbleColor !in Tokens.bubblePresets) {
                bubbleCustomOpen = !bubbleCustomOpen
            }
        }
        if (bubbleCustomOpen) {
            Spacer(Modifier.height(8.dp))
            ColorPalettePicker(bubbleColor) { bubbleColor = it }
        }
        Spacer(Modifier.height(14.dp))
        Text("캐릭터 값", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Text(
            "메시지의 {값이름}이 값으로 치환되고, 숫자 값은 판정 팔레트에 뜹니다",
            fontSize = 10.sp, color = Tokens.InkDim,
        )
        Spacer(Modifier.height(7.dp))
        stats.forEachIndexed { index, (key, value) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    OverlayField(key, { stats[index] = it to stats[index].second }, "이름")
                }
                Box(Modifier.weight(1f)) {
                    OverlayField(value, { stats[index] = stats[index].first to it }, "값")
                }
                Text(
                    "✕", fontSize = 13.sp, color = Tokens.InkDim,
                    modifier = Modifier.clip(CircleShape)
                        .clickable { stats.removeAt(index) }
                        .padding(6.dp),
                )
            }
        }
        GhostButton("＋ 값 추가", Modifier.fillMaxWidth()) { stats.add("" to "") }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YellowButton("저장", Modifier.weight(1f)) {
                onSave(
                    Profile(
                        name = name.trim().ifEmpty { "이름 없음" },
                        emoji = emoji.trim().ifEmpty { "🙂" },
                        nameColor = nameColor,
                        bubbleColor = bubbleColor,
                        isGm = editing?.isGm ?: false,
                        stats = ProfileStats.sanitize(stats.toMap()).takeIf { it.isNotEmpty() },
                        imagePath = imagePath,
                    )
                )
            }
            GhostButton("취소", Modifier.weight(1f), onDismiss)
        }
        onDelete?.let { delete ->
            Spacer(Modifier.height(8.dp))
            GhostButton("이 캐릭터 삭제", Modifier.fillMaxWidth(), delete)
        }
    }
}

@Composable
private fun SwatchRow(presets: List<Long>, selected: Long, onSelect: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { color ->
            val on = selected == color
            Box(
                Modifier.size(32.dp)
                    // 밝은 다이얼로그 위 선택 표시는 잉크색 아웃라인 (라이트 목업 03장)
                    .border(2.dp, if (on) Tokens.Ink else Color.Transparent, CircleShape)
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
            code, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Tokens.SignatureInk,
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
private fun SettingsOverlay(
    room: JoinedRoom?,
    onDismiss: () -> Unit,
    onApply: (Long, String) -> Unit,
    onResetLogs: ((Boolean) -> Unit) -> Unit,
) {
    if (room == null) return
    val scope = rememberCoroutineScope()
    var theme by remember { mutableStateOf(room.themeColor) }
    var background by remember { mutableStateOf(room.backgroundKey) }
    var hexOpen by remember {
        mutableStateOf(Tokens.themePresets.none { it.first == room.themeColor })
    }
    OverlayScaffold("방 설정 · ${room.name}", onDismiss) {
        Text("테마 컬러", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SwatchRow(Tokens.themePresets.map { it.first }, theme) {
                theme = it
                hexOpen = false
            }
            // 커스텀 — 무지개 스와치, 프리셋 밖의 색이 선택되어 있으면 선택 표시 (모바일과 동일)
            CustomSwatch(on = Tokens.themePresets.none { it.first == theme }) {
                hexOpen = !hexOpen
            }
        }
        if (hexOpen) {
            Spacer(Modifier.height(8.dp))
            ColorPalettePicker(theme) { theme = it }
        }
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
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 현재 커스텀 배경 미리보기 (모바일 '커스텀 · 사용 중' 셀과 동일 역할)
            if (Tokens.backgroundPresets[background] == null) {
                Box(
                    Modifier.size(width = 64.dp, height = 44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.5.dp, Tokens.Signature, RoundedCornerShape(10.dp))
                ) {
                    BackgroundLayer(background, Modifier.fillMaxSize())
                }
            }
            // 파일에서 선택 — 이미지를 ~/.pbp-desktop/backgrounds/에 저장해 경로를 보관
            var picking by remember { mutableStateOf(false) }
            Box(
                Modifier.size(width = 64.dp, height = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Tokens.Line, RoundedCornerShape(10.dp))
                    .clickable(enabled = !picking) {
                        picking = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                pickAndStoreImage("배경 이미지 선택", "backgrounds", 1600)
                                    ?.let { background = it }
                            } finally {
                                picking = false
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("파일\n선택", fontSize = 10.sp, color = Tokens.InkDim, lineHeight = 13.sp)
            }
        }
        Text(
            "커스텀 배경은 이 PC에서만 보입니다 (모바일과 동일)",
            fontSize = 10.sp, color = Tokens.InkDim, modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YellowButton("적용", Modifier.weight(1f)) { onApply(theme, background) }
            GhostButton("취소", Modifier.weight(1f), onDismiss)
        }
        // 방 로그 초기화 — 앱 방 설정과 동일 (로컬·서버·상대 로그 전부 삭제)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Line))
        Spacer(Modifier.height(12.dp))
        var resetConfirm by remember { mutableStateOf(false) }
        var resetting by remember { mutableStateOf(false) }
        var resetResult by remember { mutableStateOf<String?>(null) }
        if (!resetConfirm) {
            GhostButton("방 로그 초기화", Modifier.fillMaxWidth()) { resetConfirm = true }
        } else {
            Text(
                "이 방의 모든 메시지가 삭제됩니다. 상대방의 로그도 함께 삭제되며, 되돌릴 수 없습니다.",
                fontSize = 12.sp, color = Tokens.Danger,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YellowButton(if (resetting) "삭제 중…" else "전부 삭제", Modifier.weight(1f)) {
                    if (!resetting) {
                        resetting = true
                        onResetLogs { ok ->
                            resetting = false
                            resetConfirm = false
                            resetResult = if (ok) "방 로그를 초기화했습니다"
                            else "서버 삭제가 완료되지 않아 중단했습니다 — 네트워크 확인 후 다시 시도해주세요"
                        }
                    }
                }
                GhostButton("취소", Modifier.weight(1f)) { resetConfirm = false }
            }
        }
        resetResult?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 11.sp, color = Tokens.InkDim)
        }
    }
}

/** 커스텀 컬러 진입용 무지개 스와치 — on이면 프리셋 밖의 색이 선택된 상태 */
@Composable
private fun CustomSwatch(on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp)
            .border(2.dp, if (on) Tokens.Ink else Color.Transparent, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.sweepGradient(
                    listOf(
                        Color(0xFFFF6666), Color(0xFFFFCC66), Color(0xFF66DD66),
                        Color(0xFF66CCFF), Color(0xFFCC66FF), Color(0xFFFF6666),
                    )
                )
            )
            .clickable(onClick = onClick),
    )
}

/**
 * 드래그 컬러 팔레트 — SV 박스(채도·명도) + 색상 띠 + HEX 입력.
 * 모바일 HexColorDialog의 팔레트와 동일 동작. 변경 즉시 onChange로 전달된다.
 */
@Composable
private fun ColorPalettePicker(initial: Long, onChange: (Long) -> Unit) {
    val seedHsv = remember { argbToHsv(initial) }
    var hue by remember { mutableStateOf(seedHsv.first) }
    var sat by remember { mutableStateOf(seedHsv.second) }
    var bri by remember { mutableStateOf(seedHsv.third) }
    var hex by remember { mutableStateOf("%06X".format(initial and 0xFFFFFF)) }
    val current = hsvToArgb(hue, sat, bri)

    fun push() {
        val c = hsvToArgb(hue, sat, bri)
        hex = "%06X".format(c and 0xFFFFFF)
        onChange(c)
    }

    Column {
        var svSize by remember { mutableStateOf(IntSize.Zero) }
        fun pickSv(x: Float, y: Float) {
            if (svSize == IntSize.Zero) return
            sat = (x / svSize.width).coerceIn(0f, 1f)
            bri = 1f - (y / svSize.height).coerceIn(0f, 1f)
            push()
        }
        Box(
            Modifier.fillMaxWidth().height(140.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.horizontalGradient(listOf(Color.White, Color(hsvToArgb(hue, 1f, 1f))))
                )
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                .onSizeChanged { svSize = it }
                .pointerInput(Unit) { detectTapGestures { p -> pickSv(p.x, p.y) } }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        pickSv(change.position.x, change.position.y)
                    }
                }
        ) {
            Box(
                Modifier.offset {
                    IntOffset(
                        (sat * svSize.width).toInt() - 8.dp.roundToPx(),
                        ((1f - bri) * svSize.height).toInt() - 8.dp.roundToPx(),
                    )
                }
                    .size(16.dp)
                    .border(2.dp, Color.White, CircleShape)
                    .clip(CircleShape)
                    .background(Color(current))
            )
        }
        Spacer(Modifier.height(8.dp))
        var hueSize by remember { mutableStateOf(IntSize.Zero) }
        fun pickHue(x: Float) {
            if (hueSize == IntSize.Zero) return
            hue = (x / hueSize.width).coerceIn(0f, 1f) * 359.9f
            push()
        }
        Box(
            Modifier.fillMaxWidth().height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
                            Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF),
                            Color(0xFFFF0000),
                        )
                    )
                )
                .onSizeChanged { hueSize = it }
                .pointerInput(Unit) { detectTapGestures { p -> pickHue(p.x) } }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        pickHue(change.position.x)
                    }
                }
        ) {
            Box(
                Modifier.offset {
                    IntOffset((hue / 360f * hueSize.width).toInt() - 8.dp.roundToPx(), 0)
                }
                    .size(16.dp)
                    .border(2.dp, Color.White, CircleShape)
                    .clip(CircleShape)
                    .background(Color(hsvToArgb(hue, 1f, 1f)))
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(width = 40.dp, height = 26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(current))
                    .border(1.dp, Tokens.Line, RoundedCornerShape(6.dp))
            )
            Box(Modifier.weight(1f)) {
                OverlayField(hex, { typed ->
                    hex = typed
                    typed.trim().removePrefix("#")
                        .takeIf { it.length == 6 }?.toLongOrNull(16)?.or(0xFF000000)
                        ?.let { color ->
                            val (h, s, v) = argbToHsv(color)
                            hue = h; sat = s; bri = v
                            onChange(color)
                        }
                }, "HEX (예: 8EC5E8)")
            }
        }
    }
}

/** HSV(h 0–360, s/v 0–1) → 0xFFRRGGBB — 모바일 Ui.kt와 동일 변환 */
private fun hsvToArgb(h: Float, s: Float, v: Float): Long {
    val c = v * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun ch(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
    return 0xFF000000 or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

private fun argbToHsv(argb: Long): Triple<Float, Float, Float> {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val s = if (max == 0f) 0f else d / max
    return Triple(h, s, max)
}

/**
 * OS 파일 선택창으로 이미지를 골라 설정 폴더(~/.pbp-desktop/<subDir>)에 저장,
 * 저장본 경로를 돌려준다. 원본이 크면 maxSize(긴 변)로 줄여 JPEG로 저장 —
 * 모바일 Images.kt와 동일 정책 (풀사이즈 디코딩으로 인한 메모리·지연 방지).
 */
private fun pickAndStoreImage(title: String, subDir: String, maxSize: Int): String? {
    val fd = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    fd.setFilenameFilter { _, name ->
        name.lowercase().substringAfterLast('.', "") in setOf("png", "jpg", "jpeg", "webp", "bmp")
    }
    fd.isVisible = true // 선택할 때까지 블록
    val dir = fd.directory ?: return null
    val file = fd.file ?: return null
    val src = java.io.File(dir, file)
    val destDir = java.io.File(System.getProperty("user.home"), ".pbp-desktop/$subDir")
    return runCatching {
        destDir.mkdirs()
        val image = org.jetbrains.skia.Image.makeFromEncoded(src.readBytes())
        val maxDim = maxOf(image.width, image.height)
        if (maxDim <= maxSize) {
            val dest = java.io.File(destDir, "img-${System.currentTimeMillis()}.${src.extension.ifEmpty { "img" }}")
            src.copyTo(dest, overwrite = true)
            dest.absolutePath
        } else {
            val scale = maxSize.toFloat() / maxDim
            val w = (image.width * scale).toInt().coerceAtLeast(1)
            val h = (image.height * scale).toInt().coerceAtLeast(1)
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
            surface.canvas.drawImageRect(
                image,
                org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()),
            )
            val jpeg = surface.makeImageSnapshot()
                .encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 85)
                ?: error("이미지 인코딩 실패")
            val dest = java.io.File(destDir, "img-${System.currentTimeMillis()}.jpg")
            dest.writeBytes(jpeg.bytes)
            dest.absolutePath
        }
    }.onFailure { System.err.println("이미지 저장 실패: $it") }.getOrNull()
}

/**
 * 아바타 업로드용 축소 인코딩 — 모바일 downscaleToJpeg와 동일 정책:
 * 긴 변 256px, 투명이 있으면 PNG, 아니면 JPEG(82).
 */
private fun encodeAvatarBytes(path: String, maxSize: Int = 256): ByteArray? = runCatching {
    val image = org.jetbrains.skia.Image.makeFromEncoded(java.io.File(path).readBytes())
    val maxDim = maxOf(image.width, image.height)
    val scale = if (maxDim > maxSize) maxSize.toFloat() / maxDim else 1f
    val w = (image.width * scale).toInt().coerceAtLeast(1)
    val h = (image.height * scale).toInt().coerceAtLeast(1)
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(
        image,
        org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()),
    )
    val opaque = image.imageInfo.isOpaque
    surface.makeImageSnapshot()
        .encodeToData(
            if (opaque) org.jetbrains.skia.EncodedImageFormat.JPEG
            else org.jetbrains.skia.EncodedImageFormat.PNG,
            if (opaque) 82 else 100,
        )?.bytes
}.getOrNull()

private fun md5Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("MD5").digest(bytes)
        .joinToString("") { "%02x".format(it) }

/**
 * 아바타 다운로드 디스크 캐시 (P9) — Android의 filesDir/avatars와 동일하게
 * 해시 키 파일로 저장해 실행마다 재다운로드(문서 크기만큼 read 대역폭 과금)를 없앤다.
 */
private fun fetchAvatarCached(
    firestore: FirestoreRest,
    remoteRoomId: String,
    avatarId: String,
): ByteArray? {
    val dir = java.io.File(System.getProperty("user.home"), ".pbp-desktop/avatars-remote")
    val cached = java.io.File(dir, avatarId)
    runCatching { if (cached.exists()) return cached.readBytes() }
    val bytes = firestore.fetchAvatar(remoteRoomId, avatarId) ?: return null
    runCatching {
        dir.mkdirs()
        // 임시 파일 + 교체 — 쓰다 중단된 깨진 캐시 방지 (모바일 R7-2와 동일)
        val tmp = java.io.File(dir, "$avatarId.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(cached)) tmp.delete()
    }
    return bytes
}

/** 방별 업로드 완료 표시 — 같은 이미지의 중복 업로드 방지 (모바일 uploadedAvatars와 동일) */
private val uploadedAvatarKeys: MutableSet<String> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()

/** 커스텀 배경 디코드 공용 캐시 (M2) — 경로가 타임스탬프 파일명이라 무효화 불필요 */
private val backgroundBitmapCache =
    java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

/** 전송마다 아바타 재인코딩·재해시하지 않는다 (F3) — lastModified 기준 캐시 */
private val avatarEncodeCache =
    java.util.concurrent.ConcurrentHashMap<String, Triple<Long, ByteArray, String>>()

private fun encodedAvatarFor(path: String): Pair<ByteArray, String>? {
    val file = java.io.File(path)
    if (!file.exists()) return null
    avatarEncodeCache[path]?.let { (modified, bytes, hash) ->
        if (modified == file.lastModified()) return bytes to hash
    }
    val bytes = encodeAvatarBytes(path) ?: return null
    val hash = md5Hex(bytes)
    avatarEncodeCache[path] = Triple(file.lastModified(), bytes, hash)
    return bytes to hash
}

/** 로컬 이미지 파일 로더 — 실패는 캐시하지 않고 null (호출부는 이모지 폴백) */
@Composable
private fun rememberLocalBitmap(path: String?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching {
                org.jetbrains.skia.Image.makeFromEncoded(java.io.File(path).readBytes())
                    .toComposeImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

/** 메시지 편집 — 앱의 편집 다이얼로그와 동일 흐름 (여러 줄 입력 + 저장/취소) */
@Composable
private fun EditMessageOverlay(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var body by remember { mutableStateOf(initial) }
    OverlayScaffold("메시지 편집", onDismiss) {
        BasicTextField(
            value = body,
            onValueChange = { body = it },
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x0A14191F))
                .border(1.dp, Tokens.Line, RoundedCornerShape(12.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            textStyle = TextStyle(color = Tokens.Ink, fontSize = 14.sp),
            cursorBrush = SolidColor(Tokens.SignatureRing),
            maxLines = 8,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YellowButton("저장", Modifier.weight(1f)) {
                if (body.isNotBlank()) onSave(body)
            }
            GhostButton("취소", Modifier.weight(1f), onDismiss)
        }
    }
}

/**
 * 프로필 관리 — 오너·GM·캐릭터 전부를 이미지+이름 목록으로 (모바일과 동일).
 * 항목 클릭 = 해당 설정, 하단 = 프로필 추가하기.
 */
@Composable
private fun ProfileManagerOverlay(
    ownerName: String,
    ownerColor: Long,
    ownerImagePath: String?,
    profiles: List<Profile>,
    onDismiss: () -> Unit,
    onOwner: () -> Unit,
    onProfile: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    OverlayScaffold("프로필 관리", onDismiss) {
        // 오너 프로필 — 항상 맨 위
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOwner)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Color(ownerColor)),
                contentAlignment = Alignment.Center,
            ) {
                val ownerImage = rememberLocalBitmap(ownerImagePath)
                if (ownerImage != null) {
                    Image(ownerImage, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(
                        ownerName.take(1).ifEmpty { "?" },
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10151C),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    ownerName.ifBlank { "오너 프로필" },
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Tokens.Ink,
                )
                Text("오너 · 잡담과 참여 인사에 사용", fontSize = 11.sp, color = Tokens.InkDim)
            }
        }
        profiles.forEachIndexed { index, profile ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onProfile(index) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Tokens.Panel2)
                        .border(
                            1.dp,
                            if (profile.isGm) Color(0x99C89E34) else Tokens.Line,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val chipImage = rememberLocalBitmap(profile.imagePath)
                    if (chipImage != null) {
                        Image(chipImage, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text(
                            profile.emoji, fontSize = 15.sp,
                            fontFamily = if (profile.isGm) GowunBatang else null,
                            color = if (profile.isGm) Tokens.SignatureInk else Tokens.Ink,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        profile.name.ifBlank { "이름 없음" },
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Tokens.Ink,
                    )
                    Text(
                        if (profile.isGm) "GM · 모든 방 공통" else "캐릭터 · 모든 방 공통",
                        fontSize = 11.sp, color = Tokens.InkDim,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // 프로필 추가하기 — 목록 맨 아래
        GhostButton("＋ 프로필 추가하기", Modifier.fillMaxWidth(), onAdd)
        Spacer(Modifier.height(10.dp))
        GhostButton("닫기", Modifier.fillMaxWidth(), onDismiss)
    }
}

/** 캐릭터 추가 방식 선택 — 신규 작성이 위, 클립보드 코드가 아래 (모바일과 동일 순서) */
@Composable
private fun AddProfileChoiceOverlay(
    onDismiss: () -> Unit,
    onEmpty: () -> Unit,
    /** true = 생성 성공, false = 클립보드에서 코드를 찾지 못함 */
    onClipboard: () -> Boolean,
) {
    var clipboardError by remember { mutableStateOf(false) }
    OverlayScaffold("캐릭터 추가", onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onEmpty)
                .padding(10.dp),
        ) {
            Text(
                "신규 캐릭터 작성",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Tokens.SignatureInk,
            )
            Text("이름과 색만 정해 새로 만들기", fontSize = 11.sp, color = Tokens.InkDim)
        }
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { clipboardError = !onClipboard() }
                .padding(10.dp),
        ) {
            Text(
                "클립보드 코드로 생성",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Tokens.SignatureInk,
            )
            Text(
                "복사해 둔 캐릭터 코드(JSON)의 이름·능력치를 값으로 자동 등록",
                fontSize = 11.sp, color = Tokens.InkDim,
            )
        }
        if (clipboardError) {
            Spacer(Modifier.height(6.dp))
            Text("클립보드에서 캐릭터 코드를 찾지 못했습니다", fontSize = 11.sp, color = Tokens.Danger)
        }
        Spacer(Modifier.height(12.dp))
        GhostButton("취소", Modifier.fillMaxWidth(), onDismiss)
    }
}

/**
 * 오너 프로필 설정 — 이미지·이름·컬러만 (캐릭터 프로필 편집의 축소판, 모바일과 동일).
 * 잡담과 참여 인사에 쓰이는 '플레이어 본인' 프로필. forced면 저장 전 닫기 불가.
 */
@Composable
private fun OwnerProfileOverlay(
    initialName: String,
    initialColor: Long,
    initialImage: String?,
    forced: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Long, String?) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    var imagePath by remember { mutableStateOf(initialImage) }
    var customOpen by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    OverlayScaffold("오너 프로필", onDismiss = { if (!forced) onDismiss() }) {
        Text(
            "잡담과 참여 인사에 쓰이는 플레이어 본인 프로필입니다. " +
                "세션 캐릭터 목록에는 나타나지 않습니다.",
            fontSize = 12.sp, color = Tokens.InkDim,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color(color)),
                contentAlignment = Alignment.Center,
            ) {
                val preview = rememberLocalBitmap(imagePath)
                if (preview != null) {
                    Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(
                        name.take(1).ifEmpty { "?" },
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10151C),
                    )
                }
            }
            GhostButton(
                if (imagePath == null) "이미지 선택" else "이미지 변경",
                Modifier.weight(1f),
            ) {
                if (!picking) {
                    picking = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            pickAndStoreImage("오너 프로필 이미지 선택", "owner", 512)
                                ?.let { imagePath = it }
                        } finally {
                            picking = false
                        }
                    }
                }
            }
            if (imagePath != null) {
                GhostButton("제거") { imagePath = null }
            }
        }
        Spacer(Modifier.height(10.dp))
        OverlayField(name, { name = it }, "이름")
        Spacer(Modifier.height(14.dp))
        Text("컬러", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SwatchRow(Tokens.bubblePresets, color) {
                color = it
                customOpen = false
            }
            CustomSwatch(on = color !in Tokens.bubblePresets) { customOpen = !customOpen }
        }
        if (customOpen) {
            Spacer(Modifier.height(8.dp))
            ColorPalettePicker(color) { color = it }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YellowButton("저장", Modifier.weight(1f)) {
                if (name.isNotBlank()) onSave(name.trim(), color, imagePath)
            }
            if (!forced) {
                GhostButton("취소", Modifier.weight(1f), onDismiss)
            }
        }
    }
}

/** 앱 전체 글꼴 선택 — 모바일 FontSettingDialog와 동일 선택지, 즉시 반영·config.json 유지 */
@Composable
private fun FontOverlay(current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    OverlayScaffold("앱 글꼴", onDismiss) {
        listOf(
            "system" to "시스템 기본",
            "gowun" to "고운 바탕 (명조)",
            "pretendard" to "프리텐다드 (고딕)",
        ).forEach { (value, label) ->
            val selected = current == value
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(value) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selected) "●" else "○",
                    color = if (selected) Tokens.SignatureRing else Tokens.InkDim,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    label, fontSize = 13.sp, color = Tokens.Ink,
                    fontFamily = when (value) {
                        "gowun" -> GowunBatang
                        "pretendard" -> Pretendard
                        else -> FontFamily.Default
                    },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        GhostButton("닫기", Modifier.fillMaxWidth(), onDismiss)
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
