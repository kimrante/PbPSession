package com.pbp.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
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
import com.pbp.shared.CharacterCodec
import com.pbp.shared.DiceBot
import com.pbp.shared.ProfileStats
import com.pbp.shared.Rules
import com.pbp.desktop.notify.DesktopNotifier
import com.pbp.desktop.ui.Tokens
import com.pbp.desktop.ui.appFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pbp.shared.Protocol
import com.pbp.desktop.data.AppPaths
import com.pbp.desktop.ui.DesktopTiming



// 모바일 앱과 같은 Firebase 프로젝트 (app/src/main/res/values/firebase.xml)
private const val PROJECT_ID = "pbp-session-1195c"
private const val API_KEY = "AIzaSyCTgWzPb62iJ5rASCZ6WEiKi7kwNPVC2m4"

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "PbP — 1:1 TRPG 채팅",
        state = rememberWindowState(width = 1200.dp, height = 760.dp),
        // Esc = 캡처 모드 종료. 입력창에 포커스가 있어도 먹도록 프리뷰 단계에서 잡는다
        onPreviewKeyEvent = { event ->
            event.key == Key.Escape &&
                event.type == KeyEventType.KeyUp &&
                escapeHandler.get()?.invoke() == true
        },
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

/**
 * Esc 처리기 — 창이 키를 먼저 받지만 캡처 상태는 [App]이 들고 있어서, App이 여기에
 * 처리기를 걸어 둔다. 처리했으면 true를 돌려 키를 소비한다.
 */
private val escapeHandler =
    java.util.concurrent.atomic.AtomicReference<(() -> Boolean)?>(null)

@Composable
internal fun App(windowFocused: java.util.concurrent.atomic.AtomicBoolean) {
    val config = remember { runBlockingIo { AppConfig.load() } }
    // 아무도 가리키지 않는 로컬 이미지 정리 — 교체·방 삭제로 쌓인 고아 (L3).
    // 시작 시점이라 편집 중인 파일이 있을 수 없다
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { com.pbp.desktop.data.ImageGc.sweep(config) }
    }
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

    // 최근 사용한 커스텀 색 — 자리(name/bubble/owner/theme)별로 따로 (버그 수정)
    var recentColors by remember { mutableStateOf(config.recentColorsBySlot.mapValues { it.value.toList() }) }

    // 오너 프로필 — 미설정이면 먼저 설정하게 한다 (첫 실행 포함, 모바일과 동일)
    var ownerName by remember { mutableStateOf(config.ownerName) }
    var ownerColor by remember { mutableStateOf(config.ownerColor) }
    var ownerImagePath by remember { mutableStateOf(config.ownerImagePath) }
    var ownerTextColor by remember { mutableStateOf(config.ownerTextColor) }

    var overlay by remember {
        mutableStateOf<OverlayKind?>(
            if (config.ownerName.isBlank()) OverlayKind.OwnerProfile else null
        )
    }

    // 내 메시지 길게 눌러 편집/삭제 — 앱과 동일 흐름 (팝업 → 편집/삭제)
    var messageAction by remember { mutableStateOf<Message?>(null) }
    // 캡처 범위 — (시작 docId, 끝 docId). 끝이 null이면 아직 고르는 중 (모바일과 같은 규칙)
    var captureStart by remember { mutableStateOf<String?>(null) }
    var captureEnd by remember { mutableStateOf<String?>(null) }
    var captureRendering by remember { mutableStateOf(false) }
    var captureWithBackground by remember { mutableStateOf(config.captureWithBackground) }
    var captureExcludeOoc by remember { mutableStateOf(config.captureExcludeOoc) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var messageEdit by remember { mutableStateOf<Message?>(null) }
    var messageDelete by remember { mutableStateOf<Message?>(null) }

    // 프로필 칩 길게 눌러 편집 — 앱과 동일 흐름
    var editProfileIndex by remember { mutableStateOf<Int?>(null) }

    // 판정 요청 (J8) — 창을 열었는지, 후보 명단(null이면 불러오는 중),
    // 그리고 대상 캐릭터에 값이 없어 물어봐야 하는 요청
    var judgeOpen by remember { mutableStateOf(false) }
    var judgeCandidates by remember { mutableStateOf<List<JudgeCandidate>?>(null) }
    var judgeNeedValue by remember { mutableStateOf<Pair<Message, String>?>(null) }

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

    /** 커스텀 색 적용 기록 — 자리별 최대 5개, 넘치면 가장 오래된 것부터 밀려난다 */
    fun rememberColor(slot: String, argb: Long) {
        config.addRecentColor(slot, argb)
        recentColors = config.recentColorsBySlot.mapValues { it.value.toList() }
        persist()
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
        // 이 PC의 캐릭터 명단을 올린다 — 모바일 GM의 요청 대상 목록에 뜬다 (J0).
        // 명단이 그대로면 쓰지 않으므로 실제 쓰기는 거의 없다
        withContext(Dispatchers.IO) {
            firestore.pushCharacters(
                room.remoteId,
                profiles.filterNot { it.isGm }.map { profile ->
                    profile.name to ProfileStats.sanitize(profile.stats.orEmpty())
                        .filterValues { it.trim().toIntOrNull() != null }
                        .keys.toList()
                },
            )
        }
        var lastCreatedAt = session.lastCreatedAt
        var lastMetaPollAt = 0L
        var lastActivityAt = System.currentTimeMillis()
        // 직전 폴 시각 — 중복 윈도는 "다음 주기"가 아니라 **직전에 실제로 비어 있던 시간**을
        // 흡수해야 한다. 미포커스(30초) → 활성(2.5초)으로 바뀌는 첫 폴에서 윈도가 5초뿐이면,
        // 직전 30초 공백에 시계 오차·늦은 커밋으로 도착한 메시지가 커서 뒤로 밀려 영구 누락된다 (M1)
        var lastPollAt = System.currentTimeMillis()
        // 구버전 상대가 쓴 메시지는 syncAt이 없어 새 질의에 안 걸린다 — 가끔 옛 방식으로
        // 훑어 메꾼다. 10분에 한 번이라 비용은 무시할 수준 (V1 전환 안전망)
        var lastLegacySweepAt = System.currentTimeMillis()
        try {
        while (isActive) {
            val now = System.currentTimeMillis()
            val focusedNow = windowFocused.get()
            val active = now - maxOf(lastActivityAt, lastLocalSendAt.get()) < DesktopTiming.ACTIVE_WINDOW_MS
            val interval = when {
                !focusedNow -> DesktopTiming.UNFOCUSED_POLL_MS
                active -> DesktopTiming.ACTIVE_POLL_MS
                else -> DesktopTiming.IDLE_POLL_MS
            }
            // 반복 1회 전체를 격리 — 예기치 못한 예외 1건이 폴링을 영구 정지시키지 않게 (C2)
            runCatching {
                // 중복 윈도 = max(다음 주기, 직전 공백)×2 (P5·M1) — 주기가 짧아지는
                // 순간에도 직전 긴 공백을 덮는다
                // 절전에서 깨면 공백이 몇 시간일 수 있다 — 그만큼 되읽으면 과금만 늘고
                // 그 구간은 어차피 커서가 뒤에 있어 잡힌다. 상한을 둔다 (V6)
                val pollWindowMs = (maxOf(interval, now - lastPollAt) * 2)
                    .coerceAtMost(DesktopTiming.WINDOW_CAP_MS)
                lastPollAt = now
                val legacySweep = now - lastLegacySweepAt >= DesktopTiming.LEGACY_SWEEP_MS
                if (legacySweep) lastLegacySweepAt = now
                // 스윕은 안전망이라 일반 폴과 같은 윈도(보통 5~60초)를 쓰면 구멍이 남는다 —
                // syncAt 없는 구버전 메시지가 커서보다 그만큼 과거로 밀리면 영영 못 본다.
                // 스윕 회차만 주기(10분)만큼 넓게 본다 (B6)
                val windowMs =
                    if (legacySweep) DesktopTiming.LEGACY_SWEEP_MS else pollWindowMs
                val fetched = withContext(Dispatchers.IO) {
                    firestore.listMessagesSince(
                        room.remoteId, lastCreatedAt, windowMs = windowMs,
                        byCreatedAt = legacySweep,
                    )
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
                        fresh.lastOrNull { it.authorUid != authorUid() && it.type != Protocol.MessageType.SYSTEM }
                            ?.let { DesktopNotifier.notifyMessage(it.senderName ?: "상대") }
                    }
                    // 재수신 윈도로 다시 받은 문서 중 편집된 것은 갱신 (C10).
                    // 더 새로운 editedAt만 수용 — 내가 방금 편집한 걸 윈도의 구버전이 되돌리지 않게
                    val edited = fetched.filter { incoming ->
                        byId[incoming.docId]?.let { (incoming.editedAt ?: 0) > (it.editedAt ?: 0) } == true
                    }.associateBy { it.docId }
                    if (fresh.isNotEmpty()) lastActivityAt = now // 수신 = 활동 (P2)
                    if (fresh.isNotEmpty() || edited.isNotEmpty()) {
                        // 기존 목록도 삭제 목록으로 거른다 — 리셋과 폴이 겹쳐 지운 메시지가
                        // 되살아나도 다음 폴에서 스스로 낫는다 (M2 방어선)
                        messages = (messages.map { edited[it.docId] ?: it } + fresh)
                            .filterNot { it.docId in session.deletedDocIds }
                            .sortedBy { it.createdAt }
                        session.messages = messages
                    }
                    // 커서는 서버 기록 시각(syncAt) 기준 — createdAt으로 재면 오프라인에서
                    // 쓴 메시지가 커서 뒤로 떨어져 영영 안 보인다 (V1).
                    // 옛 방식으로 훑은 회차는 커서를 밀지 않는다 — 기준이 다른 값이다
                    if (!legacySweep) {
                        lastCreatedAt = maxOf(lastCreatedAt, fetched.maxOf { it.syncAt })
                    }
                    session.lastCreatedAt = lastCreatedAt
                    // 파일 캐시 저장 — 30초 스로틀 (P3 근본 수정)
                    if (now - session.lastSavedAt > DesktopTiming.CACHE_SAVE_THROTTLE_MS) {
                        session.lastSavedAt = now
                        val snapshotMessages = session.messages
                        withContext(Dispatchers.IO) {
                            RoomCacheStore.save(room.remoteId, snapshotMessages, lastCreatedAt)
                        }
                    }
                }
                if (now - lastMetaPollAt >= DesktopTiming.META_POLL_MS && now > metaFreezeUntil) {
                    lastMetaPollAt = now
                    val meta = withContext(Dispatchers.IO) { firestore.getRoom(room.remoteId) }
                    // 캡처한 room이 아니라 최신 인스턴스와 비교 — 설정 적용으로 교체됐을 수 있다
                    val cur = rooms.firstOrNull { it.remoteId == room.remoteId }
                    // 배경은 읽지 않는다 — 상대가 바꿔도 내 배경은 그대로 (개인 설정)
                    // createdAt은 바뀌지 않는다 — 구 config에 없던 방을 한 번 메꿀 뿐이다.
                    // 이미 받아 온 meta를 쓰므로 읽기가 늘지 않는다
                    val needsCreatedAt = cur?.createdAt == null && meta?.createdAt != null
                    if (meta != null && cur != null &&
                        (meta.themeColor != cur.themeColor || meta.name != cur.name || needsCreatedAt)
                    ) {
                        val updated = cur.copy(
                            themeColor = meta.themeColor,
                            name = meta.name,
                            createdAt = cur.createdAt ?: meta.createdAt,
                        )
                        rooms = rooms.map { if (it.remoteId == cur.remoteId) updated else it }
                        if (selected?.remoteId == cur.remoteId) selected = updated
                        persist()
                    }
                    // 누군가 로그를 초기화했다 — 폴링은 '문서가 사라졌다'를 볼 수 없어
                    // 파일 캐시가 유령을 계속 되살렸다 (A6). 그 시각 이전만 비운다
                    meta?.logsClearedAt?.let { clearedAt ->
                        val session = sessionFor(room.remoteId)
                        val kept = session.messages.filter { it.createdAt > clearedAt }
                        if (kept.size != session.messages.size) {
                            session.messages = kept
                            if (selected?.remoteId == room.remoteId) messages = kept
                            RoomCacheStore.save(room.remoteId, kept, session.lastCreatedAt)
                        }
                    }
                }
            }.onFailure { System.err.println("폴링 오류(다음 주기에 재시도): $it") }
            // 긴 주기 대기 중에도 전송·포커스 복귀를 1초 단위로 감지해 즉시 깨어난다 (P2)
            var waited = 0L
            while (waited < interval) {
                val step = minOf(DesktopTiming.WAKE_STEP_MS, interval - waited)
                delay(step)
                waited += step
                if (lastLocalSendAt.get() > now || windowFocused.get() != focusedNow) break
            }
        }
        } finally {
            // 방 전환·창 종료 시 최종 상태를 파일 캐시에 남긴다 (P3 근본 수정).
            // 나간 방은 저장하지 않는다 — leaveRoom이 세션을 먼저 지우므로 여기서
            // 다시 쓰면 삭제한 캐시 파일이 되살아나 영구 잔류한다 (L1)
            if (roomSessions.containsKey(room.remoteId)) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    RoomCacheStore.save(room.remoteId, session.messages, session.lastCreatedAt)
                }
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
                isGm = false, imagePath = ownerImagePath, textColor = ownerTextColor,
            )
        } else sender
        lastLocalSendAt.set(System.currentTimeMillis()) // 폴 주기 즉시 복귀 신호 (P2)
        // 보냈으면 더 치고 있는 게 아니다 — 즉시 끈다
        scope.launch(Dispatchers.IO) { firestore.clearTyping(room.remoteId) }
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
                    type = Protocol.MessageType.TEXT, body = marked, sender = effectiveSender,
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
                            type = Protocol.MessageType.DICE, body = result.breakdown,
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
     *
     * 모바일은 문서 삭제를 REMOVED 리스너로 받지만 **데스크톱 폴링은 문서가 사라진 것을
     * 볼 수 없다.** 그래서 방 문서에 `logsClearedAt`을 남기고, 상대 데스크톱의 60초 메타
     * 폴이 그것을 보고 자기 로컬·캐시를 비운다 (A6, 추가 읽기 0).
     */
    fun resetRoomLogs(onDone: (Boolean) -> Unit) {
        val room = selected ?: return onDone(false)
        scope.launch(Dispatchers.IO) {
            val ids = firestore.listMessages(room.remoteId)?.map { it.docId }
            val ok = ids != null && ids.all { firestore.deleteMessage(room.remoteId, it) }
            if (ok) {
                // 로컬 상태는 폴 루프가 소유한다 — IO 스레드에서 직접 비우면 fetch로
                // 서스펜드해 있던 폴이 재개하며 리셋 전 목록을 되살리고 파일 캐시에까지
                // 다시 쓴다. 상태 변경은 UI 스코프에서 한 번에 (M2)
                withContext(Dispatchers.Main) {
                    val session = sessionFor(room.remoteId)
                    session.deletedDocIds.addAll(ids.orEmpty())
                    session.messages = emptyList()
                    session.lastSavedAt = System.currentTimeMillis() // 진행 중 폴의 스로틀 저장 차단
                    messages = emptyList()
                }
                RoomCacheStore.delete(room.remoteId) // 파일 캐시도 초기화
                // 상대 데스크톱이 초기화를 알아챌 유일한 단서 (A6) — 안내 메시지보다 먼저 찍어
                // 안내 자체가 걸러지지 않게 한다
                firestore.setLogsClearedAt(room.remoteId, System.currentTimeMillis())
                // 리셋 흔적을 양쪽에 남긴다 — 모바일과 동일 문구
                firestore.postMessage(
                    room.remoteId,
                    systemMessageValues("방 로그가 초기화되었습니다", authorUid()),
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
    /** 선택 구간(messages 인덱스). 시작점이 없으면 null = 캡처 모드 아님 */
    val captureIdx: IntRange? = remember(messages, captureStart, captureEnd) {
        val a = messages.indexOfFirst { it.docId == captureStart }
        val b = messages.indexOfFirst { it.docId == captureEnd }
        when {
            a < 0 -> null
            b < 0 -> a..a
            else -> minOf(a, b)..maxOf(a, b)
        }
    }
    fun exitCapture() {
        captureStart = null
        captureEnd = null
    }
    DisposableEffect(captureStart == null) {
        escapeHandler.set {
            if (captureStart != null) {
                captureStart = null
                captureEnd = null
                true
            } else false
        }
        onDispose { escapeHandler.set(null) }
    }
    // 목업 03장의 탭 규칙 — 모바일 onCaptureTap과 같은 동작
    fun onCaptureTap(tapped: Int) {
        captureError = null
        val range = captureIdx ?: return
        val next = captureRangeAfterTap(range, tapped)
        captureStart = messages[next.first].docId
        captureEnd = messages[next.last].docId
    }

    /** 캡처 이미지를 만들어 PNG로 저장 — 기존 로그 내보내기와 같은 FileDialog */
    fun makeCapture() {
        val room = selected ?: return
        val range = captureIdx ?: return
        val picked = messages.subList(
            range.first.coerceIn(0, messages.size),
            (range.last + 1).coerceIn(0, messages.size),
        ).toList()
        if (picked.isEmpty() || picked.size > CAPTURE_MAX) return
        if (captureExcludeOoc && picked.all { it.isOoc }) {
            // stderr는 사용자가 볼 수 없다 — 같은 함수 아래쪽의 V3 원칙과 맞춘다 (E9)
            captureError = "고른 범위가 전부 잡담이라 뺄 수 없습니다"
            return
        }
        captureRendering = true
        val uid = authorUid()
        scope.launch {
            // 렌더는 동기라 이미지 로딩을 기다려 주지 않는다 — 필요한 아바타를 먼저 받아
            // 캐시에 채운다. 이러면 렌더 중 서버 요청이 0이 되고 빈 원도 남지 않는다 (P3·R6)
            withContext(Dispatchers.IO) {
                picked.mapNotNull { it.avatarId }.distinct()
                    .filter { it !in avatarCache }
                    .forEach { id ->
                        fetchAvatarCached(firestore, room.remoteId, id)?.let { bytes ->
                            runCatching {
                                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                            }.onFailure { dropBrokenAvatarCache(id) }
                                .getOrNull()?.let { avatarCache[id] = it }
                        }
                    }
            }
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    com.pbp.desktop.export.CaptureRenderer.render(
                        room = room,
                        messages = picked,
                        myUid = uid,
                        avatarCache = avatarCache,
                        // 렌더러에는 넘기지 않는다 — 아바타는 위에서 미리 받아 캐시에
                        // 채웠고, 그리는 중 서버 요청이 나갈 길을 아예 없앤다 (V7)
                        firestore = null,
                        withBackground = config.captureWithBackground,
                        excludeOoc = config.captureExcludeOoc,
                    )
                }
            }
            captureRendering = false
            val pages = result.getOrDefault(emptyList())
            if (pages.isEmpty()) {
                // 창 안에 보여 준다 — stderr는 사용자가 볼 수 없다 (V3)
                captureError = result.exceptionOrNull()
                    ?.let { "캡처 실패 — ${it.message}" }
                    ?: "캡처 이미지를 만들지 못했습니다"
                return@launch
            }
            captureError = null
            // 대화상자는 EDT에서, 파일 쓰기만 IO에서 (E13)
            val target = showFileDialog(
                "캡처 이미지 저장", java.awt.FileDialog.SAVE, "PbP_${room.name}.png",
            )
            if (target != null) {
                val (dir, picked) = target
                val name = picked.removeSuffix(".png")
                withContext(Dispatchers.IO) {
                    pages.forEachIndexed { index, bytes ->
                        val suffix = if (pages.size > 1) "_${index + 1}of${pages.size}" else ""
                        runCatching { java.io.File(dir, "$name$suffix.png").writeBytes(bytes) }
                            .onFailure { System.err.println("캡처 저장 실패: $it") }
                    }
                }
            }
            exitCapture()
        }
    }

    /**
     * GM이 판정 요청 창을 연다 (J8). 후보 명단은 **이때 한 번만** 받아온다 —
     * 폴링에 얹으면 members를 상시로 읽게 되므로 절대 옮기지 말 것.
     */
    fun openJudgeRequest() {
        val room = selected ?: return
        judgeOpen = true
        judgeCandidates = null
        scope.launch {
            val peers = withContext(Dispatchers.IO) { firestore.listPeerCharacters(room.remoteId) }
            // 오너 프로필은 캐릭터가 아니라 애초에 이 목록에 들어오지 않는다
            val mine = profiles.filterNot { it.isGm }.map { profile ->
                JudgeCandidate(
                    name = profile.name,
                    emoji = profile.emoji,
                    nameColor = profile.nameColor,
                    // 주사위가 굴러가야 하므로 숫자 값만 고를 수 있다
                    stats = ProfileStats.sanitize(profile.stats.orEmpty())
                        .filterValues { it.trim().toIntOrNull() != null }
                        .keys.toList(),
                )
            }
            val peerCandidates = peers.map { JudgeCandidate(it.name, it.emoji, null, it.stats) }
            judgeCandidates = (mine + peerCandidates).distinctBy { it.name }
        }
    }

    /** 판정 요청을 보낸다 (J8) — 값 **이름**만 싣는다. 숫자는 소유자 기기에만 있다 */
    fun sendJudgeRequest(targetName: String, statName: String) {
        val room = selected ?: return
        val gm = profiles.getOrNull(room.activeProfileIndex) ?: return
        lastLocalSendAt.set(System.currentTimeMillis())
        scope.launch(Dispatchers.IO) {
            firestore.postMessage(
                room.remoteId,
                messageValues(
                    type = Protocol.MessageType.JUDGE,
                    // 구버전 클라이언트에서는 이 문구가 그대로 말풍선으로 보인다
                    body = "$targetName, $statName 판정",
                    sender = gm,
                    isOoc = false,
                    authorUid = authorUid(),
                    diceExpr = Rules.judgeCommand(room.rule ?: Rules.COC7, statName),
                    judgeTarget = targetName,
                ),
            )
        }
    }

    /**
     * 판정 요청을 눌러 굴린다 (J6) — 요청이 지목한 캐릭터로 나간다. 지금 활성 프로필이
     * 무엇이든 상관없고, 활성 프로필을 바꾸지도 않는다.
     */
    fun rollJudge(request: Message) {
        val room = selected ?: return
        val target = request.judgeTarget ?: return
        val expr = request.diceExpr ?: return
        // 연타 방지 — 이미 결과가 있으면 아무것도 하지 않는다 (렌더의 Done과 두 겹)
        if (messages.any { it.judgeRef == request.docId }) return
        val profile = profiles.find { it.name == target } ?: return
        val (plain, _) = ProfileStats.substitute(expr, ProfileStats.sanitize(profile.stats.orEmpty()))
        val command = DiceBot.parse(plain) ?: run {
            // 치환이 안 됐다 = 그 캐릭터에 그 값이 없다. 값을 받아 채운 뒤 굴린다 (J8)
            ProfileStats.statNameOf(expr)?.let { judgeNeedValue = request to it }
            return
        }
        val result = DiceBot.roll(command)
        lastLocalSendAt.set(System.currentTimeMillis())
        scope.launch(Dispatchers.IO) {
            firestore.postMessage(
                room.remoteId,
                messageValues(
                    type = Protocol.MessageType.DICE,
                    body = result.breakdown,
                    sender = Profile(name = "다이스봇", emoji = "🎲"),
                    isOoc = false,
                    authorUid = authorUid(),
                    diceExpr = "${profile.name} · ${command.expr}",
                    isBot = true,
                    diceOutcome = Rules.judgeOutcome(room.rule ?: Rules.COC7, result),
                    judgeRef = request.docId,
                ),
            )
        }
    }

    /** 요청에 값을 채워 넣고 바로 굴린다 — 대상 캐릭터에 그 값이 없었을 때 (J8) */
    fun addStatAndRoll(request: Message, statName: String, value: Int) {
        val target = request.judgeTarget ?: return
        val index = profiles.indexOfFirst { it.name == target }
        if (index < 0) return
        val merged = profiles[index].stats.orEmpty() + (statName to value.toString())
        profiles = profiles.mapIndexed { i, profile ->
            if (i == index) profile.copy(stats = ProfileStats.sortByName(merged.toList()).toMap())
            else profile
        }
        persist()
        rollJudge(request)
    }

    fun exportLogs() {
        val room = selected ?: return
        val snapshot = messages
        scope.launch {
            // 대화상자는 EDT에서, 조립·쓰기만 IO에서 (E13)
            val (dir, file) = showFileDialog(
                "세션 로그 저장", java.awt.FileDialog.SAVE, "${room.name}-log.html",
            ) ?: return@launch
            withContext(Dispatchers.IO) {
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

    /** 발화 프로필 교체 — 화면 안내 메시지는 남기지 않는다 (모바일과 동일) */
    fun switchProfile(index: Int) {
        val room = selected ?: return
        if (room.activeProfileIndex == index) return
        val updated = room.copy(activeProfileIndex = index)
        rooms = rooms.map { if (it.remoteId == room.remoteId) updated else it }
        selected = updated
        persist()
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
                myUid = authorUid(),
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
                onShowMarkupHelp = { overlay = OverlayKind.MarkupHelp },
                // 데스크톱은 입력 중을 올리기만 한다 — 표시하지 않으므로 읽기가 늘지 않는다
                onTyping = {
                    val name = profiles.getOrNull(room.activeProfileIndex)?.name
                    if (name != null) {
                        scope.launch(Dispatchers.IO) { firestore.pushTyping(room.remoteId, name) }
                    }
                },
                onTypingStopped = {
                    scope.launch(Dispatchers.IO) { firestore.clearTyping(room.remoteId) }
                },
                captureIdx = captureIdx,
                onCaptureTap = ::onCaptureTap,
                onCaptureExit = ::exitCapture,
                onCaptureMake = ::makeCapture,
                captureRendering = captureRendering,
                captureWithBackground = captureWithBackground,
                onToggleCaptureBackground = {
                    captureWithBackground = !captureWithBackground
                    config.captureWithBackground = captureWithBackground
                    persist()
                },
                captureExcludeOoc = captureExcludeOoc,
                captureEndPicked = captureEnd != null,
                captureError = captureError,
                onJudgeRoll = ::rollJudge,
                onJudgeRequest = ::openJudgeRequest,
                onToggleCaptureExcludeOoc = {
                    captureExcludeOoc = !captureExcludeOoc
                    config.captureExcludeOoc = captureExcludeOoc
                    persist()
                },
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
                            // 배경은 공유 대상이 아니다 — 기본으로 시작해 각자 바꾼다
                            backgroundKey = Protocol.DEFAULT_BACKGROUND, isMaster = false,
                            rule = meta.rule,
                            createdAt = meta.createdAt,
                            // 참여자의 기본 발화는 GM이 아닌 첫 캐릭터 (서술 권한은 마스터 전용)
                            activeProfileIndex = profiles.indexOfFirst { !it.isGm }.coerceAtLeast(0),
                        )
                        if (existing == null) {
                            // 참여 인사 — 오너 프로필명으로 (처음 참여할 때 한 번, 모바일과 동일)
                            firestore.postMessage(
                                meta.remoteId,
                                systemMessageValues(
                                    "'${ownerName.ifBlank { "플레이어" }}' 님이 참여하셨습니다.",
                                    authorUid(),
                                ),
                            )
                        }
                        // 상태 변경은 UI 스코프에서 (E4) — 메타 폴의 rooms 읽고-쓰기와
                        // 겹치면 한쪽 갱신이 통째로 사라진다 (resetRoomLogs의 M2와 같은 이유)
                        withContext(Dispatchers.Main) {
                            if (existing == null) rooms = rooms + joined
                            selected = joined
                            persist()
                            overlay = null
                        }
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
                            backgroundKey = Protocol.DEFAULT_BACKGROUND, isMaster = true,
                            rule = meta.rule ?: "coc7",
                            createdAt = meta.createdAt,
                        )
                        withContext(Dispatchers.Main) {
                            rooms = rooms + joined
                            selected = joined
                            persist()
                        }
                    }
                    withContext(Dispatchers.Main) { overlay = null }
                }
            },
        )
        OverlayKind.NewProfile -> ProfileOverlay(
            recentColors = recentColors,
            onColorUsed = ::rememberColor,
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
            recentColors = recentColors,
            onColorUsed = ::rememberColor,
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
                            val bgDir = AppPaths.dir(AppPaths.BACKGROUNDS)
                            if (f.parentFile?.canonicalFile == bgDir.canonicalFile) f.delete()
                        }
                    }
                }
                // PATCH가 서버에 착지하기 전 폴링이 옛 값을 다시 덮지 않도록 유예 (P3-14)
                metaFreezeUntil = System.currentTimeMillis() + DesktopTiming.META_FREEZE_MS
                scope.launch(Dispatchers.IO) {
                    firestore.updateRoomSettings(room.remoteId, theme)
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
        OverlayKind.MarkupHelp -> MarkupHelpOverlay(onDismiss = { overlay = null })
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
            recentColors = recentColors,
            onColorUsed = ::rememberColor,
            initialName = ownerName,
            initialColor = ownerColor,
            initialImage = ownerImagePath,
            initialTextColor = ownerTextColor,
            forced = ownerName.isBlank(), // 미설정이면 저장 전에는 닫을 수 없다
            onDismiss = { overlay = null },
            onSave = { name, color, image, textColor ->
                ownerName = name
                ownerColor = color
                ownerImagePath = image
                ownerTextColor = textColor
                config.ownerName = name
                config.ownerColor = color
                config.ownerImagePath = image
                config.ownerTextColor = textColor
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
            // 복사는 상대 메시지에서도 — 편집·삭제만 내 메시지로 제한
            GhostButton("복사", Modifier.fillMaxWidth()) {
                runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        java.awt.datatransfer.StringSelection(target.body), null,
                    )
                }
                messageAction = null
            }
            Spacer(Modifier.height(8.dp))
            // 캡처는 복사와 같이 누구 메시지에서든
            GhostButton("캡처", Modifier.fillMaxWidth()) {
                captureStart = target.docId
                captureEnd = null
                messageAction = null
            }
            if (target.authorUid == authorUid()) {
                Spacer(Modifier.height(8.dp))
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
                recentColors = recentColors,
                onColorUsed = ::rememberColor,
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
    if (judgeOpen) {
        JudgeRequestOverlay(
            candidates = judgeCandidates,
            rule = selected?.rule ?: Rules.COC7,
            onDismiss = { judgeOpen = false },
            onSend = { targetName, statName ->
                sendJudgeRequest(targetName, statName)
                judgeOpen = false
            },
        )
    }
    judgeNeedValue?.let { (request, statName) ->
        JudgeValueOverlay(
            targetName = request.judgeTarget.orEmpty(),
            statName = statName,
            onDismiss = { judgeNeedValue = null },
            onConfirm = { value ->
                addStatAndRoll(request, statName, value)
                judgeNeedValue = null
            },
        )
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
internal fun <T> runBlockingIo(block: () -> T): T =
    kotlinx.coroutines.runBlocking(Dispatchers.IO) { block() }

private enum class OverlayKind {
    JoinRoom, CreateRoom, NewProfile, ShowCode, RoomSettings, FontSetting, OwnerProfile,
    ProfileManager, AddProfileChoice, MarkupHelp,
}

internal fun inviteCode(): String {
    val alphabet = Protocol.INVITE_ALPHABET
    return (1..6).map { alphabet.random() }.joinToString("")
}
