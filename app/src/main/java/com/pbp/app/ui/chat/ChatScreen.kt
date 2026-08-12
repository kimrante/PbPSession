package com.pbp.app.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import com.pbp.app.ui.common.safeLaunch
import com.pbp.app.PbpApp
import com.pbp.app.data.CharacterProfile
import com.pbp.app.data.CaptureSettings
import com.pbp.app.data.Message
import com.pbp.app.data.judgeKey
import com.pbp.app.data.numericStatNames
import com.pbp.app.export.LogExporter
import com.pbp.app.export.CaptureHolder
import com.pbp.app.export.CaptureRenderer
import com.pbp.shared.ChatDates
import com.pbp.shared.ScenarioDoc
import com.pbp.app.ui.common.Avatar
import com.pbp.app.ui.common.AddProfileDialog
import com.pbp.app.ui.common.importCharacterFromClipboard
import com.pbp.app.data.ScenarioFetcher
import com.pbp.app.data.ScenarioSettings
import com.pbp.app.ui.common.RoomBackdrop
import com.pbp.app.ui.common.formatTime
import com.pbp.app.ui.theme.GowunBatang
import com.pbp.app.ui.common.PbpButtonKind
import com.pbp.app.ui.common.PbpDialogButton
import com.pbp.app.ui.common.PbpDialogTitle
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import com.pbp.app.ui.theme.PbpPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(private val app: PbpApp, private val roomId: Long) : ViewModel() {
    private val repo = app.repository

    companion object {
        /**
         * 한 페이지 로드량 — 캡처 최대 선택 수와 **같아야** 한다. 화면에 없는 메시지를
         * 범위에 넣을 수 없기 때문이다. :shared가 단일 출처 (C3)
         */
        const val PAGE_SIZE = com.pbp.shared.CaptureLayout.MAX_MESSAGES
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
     * 상대가 어디까지 읽었는지 — 화면이 보이는 동안만 구독한다.
     * 상대가 데스크톱이거나 로컬 전용 방이면 null이라 "읽음"을 표시하지 않는다.
     *
     * remoteId만 뽑아 distinctUntilChanged로 거르는 이유(R1): room 엔티티에는 로컬
     * lastReadAt이 들어 있어 markRead마다 새 값이 방출된다. 엔티티째로 flatMapLatest에
     * 넣으면 메시지 1건마다 Firestore 리스너가 해제·재등록된다.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val peerState = room
        .map { it?.remoteId }
        .distinctUntilChanged()
        .flatMapLatest { repo.observePeerState(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            com.pbp.app.sync.SyncManager.PeerState(),
        )

    /**
     * 입력 중 알림 — 입력창에서 **실제로 글자가 바뀔 때만** 부른다.
     * 포커스만 있는 상태, 써 둔 글을 그대로 두는 상태는 입력 중이 아니다.
     */
    fun notifyTyping() = safeLaunch(app) {
        val name = profiles.value.find { it.id == room.value?.activeProfileId }?.name ?: return@safeLaunch
        repo.pushTyping(roomId, name)
    }

    /** 전송·비움·포커스 해제 때 즉시 끈다 */
    fun notifyTypingStopped() = safeLaunch(app) { repo.clearTyping(roomId) }

    fun markRead() = safeLaunch(app) { repo.markRead(roomId) }

    // ── 판정 요청 ────────────────────────────────────────
    init {
        // 이 기기의 캐릭터 명단을 상대에게 알린다 — 명단이 그대로면 쓰지 않는다 (J0).
        // 상대 GM의 요청 목록에 내 캐릭터가 뜨려면 이게 먼저 건너가야 한다.
        // collect 자체를 감싼다 (Z3) — 방을 열어 두는 내내 도는 쓰기라, 여기서 터지면
        // "방에 들어가면 앱이 꺼진다"가 된다
        safeLaunch(app) {
            profiles.collect { list -> repo.pushCharacters(roomId, list) }
        }
    }

    fun sendJudgeRequest(targetId: String?, targetName: String, statName: String) = safeLaunch(app) {
        val gm = profiles.value.find { it.id == room.value?.activeProfileId } ?: return@safeLaunch
        repo.sendJudgeRequest(roomId, gm, targetId, targetName, statName)
    }

    /** @param onNeedValue 대상 캐릭터에 그 값이 없을 때 — 값 이름을 돌려준다 (J6) */
    fun rollJudge(request: Message, onNeedValue: (String) -> Unit) = safeLaunch(app) {
        repo.rollJudge(request)?.let(onNeedValue)
    }

    fun addStatAndRoll(request: Message, statName: String, value: Int) = safeLaunch(app) {
        repo.addStatAndRoll(request, statName, value)
    }

    // ── 캡처 ──────────────────────────────────────────────
    // 결과 비트맵은 CaptureHolder에 둔다 — viewModel()은 화면마다 저장소가 달라
    // 여기 두면 미리보기 화면이 볼 수 없다.

    /** @param onDone null이면 성공, 아니면 실패 사유 (화면에 그대로 보여 준다) */
    fun renderCapture(
        context: android.content.Context,
        picked: List<Message>,
        onDone: (String?) -> Unit,
    ) = viewModelScope.launch {
        val request = CaptureHolder.Request(
            roomName = room.value?.name ?: "PbP",
            backgroundKey = room.value?.backgroundKey ?: PbpPalette.DEFAULT_BACKGROUND,
            messages = picked,
            themeColor = room.value?.themeColor ?: PbpPalette.DEFAULT_THEME_COLOR,
            // 고른 범위가 아니라 방 전체 — 굴림이 범위 밖에 있어도 완료로 찍힌다 (E6)
            rolledRefs = messages.value.mapNotNullTo(mutableSetOf()) { it.judgeRef },
        )
        if (CaptureSettings.excludeOoc && picked.all { it.isOoc }) {
            onDone("고른 범위가 전부 잡담입니다")
            return@launch
        }
        val result = runCatching {
            CaptureRenderer.render(
                context = context,
                roomName = request.roomName,
                backgroundKey = request.backgroundKey,
                messages = request.messages,
                withBackground = CaptureSettings.withBackground,
                excludeOoc = CaptureSettings.excludeOoc,
                themeColor = request.themeColor,
                rolledRefs = request.rolledRefs,
            )
        }.onFailure {
            android.util.Log.w("PbpCapture", "캡처 렌더 실패", it)
        }
        val bitmaps = result.getOrDefault(emptyList())
        if (bitmaps.isEmpty()) {
            val cause = result.exceptionOrNull()
            onDone(
                cause?.let { "${it::class.simpleName}: ${it.message}" } ?: "만들어진 이미지가 없습니다"
            )
            return@launch
        }
        CaptureHolder.set(request, bitmaps)
        onDone(null)
    }

    fun send(text: String, isOoc: Boolean) = safeLaunch(app) {
        val sender = profiles.value.find { it.id == room.value?.activeProfileId }
        if (sender == null) {
            // 프로필 삭제 직후의 좁은 레이스 — 입력이 조용히 버려지지 않게 알린다 (L6)
            Toast.makeText(app, "발화 프로필이 없어 전송하지 못했습니다", Toast.LENGTH_SHORT).show()
            return@safeLaunch
        }
        repo.sendMessage(roomId, sender, text, isOoc)
        repo.markRead(roomId)
    }

    fun switchTo(profile: CharacterProfile) = safeLaunch(app) {
        if (room.value?.activeProfileId == profile.id) return@safeLaunch
        repo.switchProfile(roomId, profile)
    }

    fun edit(messageId: Long, body: String) = safeLaunch(app) {
        repo.editMessage(messageId, body)
    }

    fun delete(message: Message) = safeLaunch(app) { repo.deleteMessage(message) }

    fun createFromCode(imported: com.pbp.shared.CharacterCodec.Imported) =
        safeLaunch(app) { repo.createFromCode(imported) }

    /** 방을 떠나면 캡처 결과도 정리한다 — 화면 밖에 있어도 메모리는 이 방의 것이다 */
    override fun onCleared() {
        CaptureHolder.clear()
        super.onCleared()
    }

    // ── 시나리오 뷰어 (V2) ───────────────────────────────

    /**
     * 패널의 내용 상태. **열림 여부는 여기 없다** — 그건 화면의 rememberSaveable이다.
     * 패널을 닫았다 열어도 읽던 문장이 그대로여야 하므로 둘을 갈라 둔다.
     */
    sealed interface ScenarioState {
        /** 첫 화면 — 링크 입력 */
        data object AskLink : ScenarioState
        data object Loading : ScenarioState

        /**
         * @param text 원문 — 문단 보기로 바꿀 때 여기서 다시 나눈다(문서 재요청 없음)
         * @param index **문장 번호**. 문단 보기에서도 문장 기준을 유지한다 —
         *   보기 방식을 바꿔도 읽던 자리가 그대로여야 하고, "읽은 문장까지 진하게"가
         *   문단 안에서 어디까지 읽었는지 알아야 하기 때문이다.
         * @param paragraphStarts 각 문단이 시작하는 문장 번호. 불러올 때 한 번만 센다 —
         *   이동할 때마다 다시 세면 큰 문서에서 매번 전체를 훑는다 (K4)
         * @param truncated 문서가 상한을 넘어 뒷부분이 잘렸는가 (K3)
         */
        data class Viewing(
            val title: String?,
            val url: String,
            val text: String,
            val sentences: List<String>,
            val paragraphs: List<String>,
            val paragraphStarts: List<Int>,
            val index: Int,
            val paragraphMode: Boolean,
            val truncated: Boolean = false,
        ) : ScenarioState {

            /** 지금 문장이 든 문단 번호 */
            val paragraphIndex: Int
                get() = paragraphStarts.indexOfLast { it <= index }.coerceAtLeast(0)

            /** 화면에 띄우는 덩어리 — 문장 모드면 문장, 문단 모드면 그 문장이 든 문단 */
            val displayText: String
                get() = if (paragraphMode) paragraphs.getOrNull(paragraphIndex).orEmpty()
                else sentences.getOrNull(index).orEmpty()

            /** ⧉가 넣는 것은 문단 모드에서도 **문장** 하나다 (지시서 V4) */
            val currentSentence: String get() = sentences.getOrNull(index).orEmpty()

            /** 진행 표시 — 보기 단위를 따른다 */
            val position: Int get() = if (paragraphMode) paragraphIndex + 1 else index + 1
            val total: Int get() = if (paragraphMode) paragraphs.size else sentences.size
        }

        data class Failed(val error: ScenarioFetcher.Result.Error) : ScenarioState
    }

    val scenario = kotlinx.coroutines.flow.MutableStateFlow<ScenarioState>(ScenarioState.AskLink)

    /**
     * 칩을 눌러 패널을 열 때 (V3).
     *
     * 지난번에 읽던 문서가 기억돼 있으면 **그 문서를 그 자리에서** 다시 연다.
     * 문서 본문은 저장하지 않는다 — 1MB짜리를 설정 파일에 넣을 이유가 없고,
     * 원본이 바뀌었을 수도 있어 어차피 새로 받는 편이 맞다.
     *
     * 이미 읽고 있던 중이면(화면을 껐다 켠 정도) 아무것도 하지 않는다.
     */
    fun openScenario() {
        if (scenario.value !is ScenarioState.AskLink) return
        val saved = ScenarioSettings.savedLink(app, roomId) ?: return
        loadScenario(saved, startIndex = ScenarioSettings.savedIndex(app, roomId))
    }

    /** @param startIndex 되살릴 문장 번호. 문서가 짧아졌으면 범위 안으로 당겨진다 */
    fun loadScenario(url: String, startIndex: Int = 0) = viewModelScope.launch {
        scenario.value = ScenarioState.Loading
        // HttpURLConnection 계열은 드물게 IOException이 아닌 예외를 던진다(제조사
        // 네트워크 스택·프록시 설정 등). fetch 안을 고치는 것보다 여기 한 줄이 싸다 (Z5)
        val result = withContext(Dispatchers.IO) {
            runCatching { ScenarioFetcher.fetch(url) }
                .getOrDefault(ScenarioFetcher.Result.Error.NETWORK)
        }
        scenario.value = when (result) {
            is ScenarioFetcher.Result.Ok -> {
                val paragraphs = ScenarioDoc.splitParagraphs(result.text)
                var counted = 0
                val starts = paragraphs.map { paragraph ->
                    counted.also { counted += ScenarioDoc.splitSentences(paragraph).size }
                }
                // 원본이 줄었을 수 있다 — 기억한 자리를 그대로 믿지 않는다
                val index = startIndex.coerceIn(0, result.sentences.lastIndex.coerceAtLeast(0))
                ScenarioSettings.rememberPlace(app, roomId, url.trim(), index)
                ScenarioState.Viewing(
                    title = result.title,
                    url = url.trim(),
                    text = result.text,
                    sentences = result.sentences,
                    paragraphs = paragraphs,
                    paragraphStarts = starts,
                    index = index,
                    paragraphMode = ScenarioSettings.paragraphMode,
                    truncated = result.truncated,
                )
            }

            is ScenarioFetcher.Result.Error -> ScenarioState.Failed(result)
        }
    }

    /** 이동 — 지금 보기 단위(문장/문단)로 한 걸음. 양 끝에서는 제자리 */
    fun scenarioStep(delta: Int) {
        val current = scenario.value as? ScenarioState.Viewing ?: return
        val next = if (current.paragraphMode) {
            val paragraph = (current.paragraphIndex + delta)
                .coerceIn(0, current.paragraphs.lastIndex.coerceAtLeast(0))
            current.paragraphStarts.getOrElse(paragraph) { current.index }
        } else {
            current.index + delta
        }
        val moved = next.coerceIn(0, current.sentences.lastIndex.coerceAtLeast(0))
        ScenarioSettings.rememberPlace(app, roomId, current.url, moved)
        scenario.value = current.copy(index = moved)
    }

    /**
     * 상대 캐릭터의 아바타 id → 로컬 파일 경로. 판정 요청 목록에 얼굴을 띄우려고
     * 필요한 만큼만 받아 둔다 — 이미 받은 아바타는 파일 캐시에서 바로 나온다.
     */
    val peerAvatarPaths = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())

    fun resolvePeerAvatars(avatarIds: List<String>) = safeLaunch(app) {
        val remote = room.value?.remoteId ?: return@safeLaunch
        val missing = avatarIds.distinct().filterNot { it in peerAvatarPaths.value }
        if (missing.isEmpty()) return@safeLaunch
        val found = missing.mapNotNull { id ->
            app.syncManager.avatarPath(remote, id)?.let { id to it }
        }
        if (found.isNotEmpty()) peerAvatarPaths.value = peerAvatarPaths.value + found
    }

    /**
     * "다른 문서로 바꾸기" — 패널을 닫는 것으로는 초기화되지 않는다.
     * 기억해 둔 문서도 함께 지운다: 안 지우면 다음에 열 때 옛 문서가 되살아난다.
     */
    fun scenarioReset() {
        ScenarioSettings.forgetPlace(app, roomId)
        scenario.value = ScenarioState.AskLink
    }

    /** "처음부터 읽기" — 문서는 그대로 두고 자리만 맨 앞으로 */
    fun scenarioRestart() {
        val current = scenario.value as? ScenarioState.Viewing ?: return
        ScenarioSettings.rememberPlace(app, roomId, current.url, 0)
        scenario.value = current.copy(index = 0)
    }

    /**
     * ⧉ — 지금 보고 있는 **문장**을 입력창에 넣어 달라는 신호.
     *
     * 입력값을 화면으로 끌어올리지 않고 흐름만 흘려보낸다: 입력 상태가 위로 가면
     * 글자 하나마다 채팅 화면 전체가 리컴포즈된다. 버퍼 1칸이면 충분하다 —
     * 밀린 삽입을 쌓아 둘 이유가 없다.
     */
    val scenarioInsert = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    fun scenarioInsertCurrent() {
        val current = scenario.value as? ScenarioState.Viewing ?: return
        scenarioInsert.tryEmit(current.currentSentence)
    }

    /**
     * 보기 단위 전환 — **읽던 자리는 저절로 지켜진다**. 번호를 문장 기준 하나로
     * 통일해 두었기 때문에 환산할 것이 없다: 문단 보기는 "그 문장이 든 문단"을
     * 띄우는 것뿐이다.
     */
    fun setScenarioParagraphMode(context: android.content.Context, on: Boolean) {
        ScenarioSettings.setParagraphMode(context, on)
        val current = scenario.value as? ScenarioState.Viewing ?: return
        scenario.value = current.copy(paragraphMode = on)
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
    val scenarioState by vm.scenario.collectAsState()
    // 백그라운드에서는 구독을 끊는다 — 리스너가 살아 있으면 상대 영수증마다 read가 붙는다 (R5)
    val peerState by vm.peerState.collectAsStateWithLifecycle()
    val peerReadAt = peerState.readAt
    val active = profiles.find { it.id == room?.activeProfileId }
    // GM 도구(판정 요청 칩·시나리오 창)의 공통 게이트 — 활성 프로필 기준 (J2·V4)
    val gmActive = active?.isGm == true
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
    // 입력 문법 도움말 — 상단 바 "?"로 연다
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    // 프로필 전환 사이드바 — 상단 바 아바타로 연다 (시안 ②)
    var showProfileDrawer by rememberSaveable { mutableStateOf(false) }
    // 판정 요청 (J3) — 시트와 "값이 없어요" 다이얼로그
    var judgeSheetOpen by rememberSaveable { mutableStateOf(false) }
    // 시나리오 뷰어 (V4) — 열림 여부만 화면에, 읽던 문장은 ViewModel에.
    // 닫았다 열면 그대로여야 하므로 둘을 갈라 둔다
    var scenarioOpen by rememberSaveable { mutableStateOf(false) }
    var needValueFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var needValueName by rememberSaveable { mutableStateOf("") }
    // 캡처 범위 — (시작 조각 키, 끝 조각 키). 끝이 null이면 아직 고르는 중.
    // 키는 "<메시지 id>:<조각 번호>" — 한 메시지가 서술·대사로 갈라지면 조각이
    // 각각 선택 단위다(사용자 요청). 회전에도 유지되도록 rememberSaveable (N10)
    var captureStart by rememberSaveable { mutableStateOf<String?>(null) }
    var captureEnd by rememberSaveable { mutableStateOf<String?>(null) }
    var captureRendering by remember { mutableStateOf(false) }
    val capturing = captureStart != null
    // 화면에 그려지는 순서 그대로 편 조각 목록 — 캡처 인덱스 공간이 된다
    val pieces = remember(messages) { capturePiecesOf(messages) }
    val pieceKeys = remember(messages, pieces) {
        pieces.map { "${messages[it.messageIndex].id}:${it.partIndex}" }
    }
    // 화면 렌더마다 O(N) 재스캔하지 않도록 인덱스 구간을 캐시한다 (F3과 같은 방식)
    val captureIdx = remember(pieceKeys, captureStart, captureEnd) {
        val a = pieceKeys.indexOf(captureStart)
        val b = pieceKeys.indexOf(captureEnd)
        when {
            a < 0 -> null
            b < 0 -> a..a
            else -> minOf(a, b)..maxOf(a, b)
        }
    }
    // 메시지 시작 조각의 전역 인덱스 — 조각 번호를 더하면 그 조각의 인덱스가 된다
    val pieceBase = remember(messages) {
        var running = 0
        messages.map { message -> running.also { running += renderedPartCount(message) } }
    }
    // 범위 안 메시지가 상대에 의해 삭제되면 시작점이 사라질 수 있다 — 모드를 닫는다.
    // **아직 안 불러온 상태와 구분해야 한다** (Y1): captureStart는 rememberSaveable이라
    // 프로세스 재생성 뒤 복원되는데, 첫 컴포지션의 messages는 stateIn 초기값(빈 목록)이다.
    // 가드가 없으면 "삭제되어 취소했습니다"라는 틀린 안내와 함께 모드가 풀렸다
    LaunchedEffect(captureIdx == null, capturing, messages.isNotEmpty()) {
        if (capturing && captureIdx == null && messages.isNotEmpty()) {
            captureStart = null
            captureEnd = null
            Toast.makeText(context, "선택한 메시지가 삭제되어 캡처를 취소했습니다", Toast.LENGTH_SHORT).show()
        }
    }
    val exitCapture = {
        captureStart = null
        captureEnd = null
    }
    // 끝점이 정해졌는가 — **살아 있는 조각인지**로 판정한다 (Y2). 끝점 메시지만
    // 사라지면 captureIdx는 시작점 1건으로 줄어드는데 captureEnd는 사라진 키를 그대로
    // 들고 있어, 화면만 "범위 확정"으로 남고 실제 선택은 1조각이었다
    val endPicked = remember(pieceKeys, captureEnd) {
        captureEnd != null && pieceKeys.contains(captureEnd)
    }
    // 목업 03장의 탭 규칙: ① 시작만 있으면 그 자리가 끝 ② 양 끝을 다시 탭하면 그 끝만 이동
    // ③ 범위 밖은 가까운 끝이 늘어난다. 어느 경우에도 범위가 초기화되지 않는다.
    val onCaptureTap = { tapped: Int ->
        captureIdx?.let { range ->
            val next = captureRangeAfterTap(range, tapped)
            captureStart = pieceKeys.getOrNull(next.first)
            captureEnd = pieceKeys.getOrNull(next.last)
        }
        Unit
    }
    BackHandler(enabled = capturing) { exitCapture() }

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
        // 캡처 모드에서는 고르던 자리가 밀리면 안 되므로 따라가지 않는다
        if (capturing) return@LaunchedEffect
        if (pendingScrollToLatest || listState.firstVisibleItemIndex <= appended + 1) {
            listState.scrollToItem(0)
            pendingScrollToLatest = false
        }
    }

    // 시간·배터리가 보이는 시스템 영역을 검정으로. 안드로이드 15부터는 시스템이 상태 바를
    // 칠하지 않아 앱 배경이 그대로 비치는데, 채팅 배경은 어두워서 밝은 띠가 도드라졌다.
    // 이 색은 인셋 영역에서만 보인다 — 본문은 RoomBackdrop이 덮는다.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val wasLight = bars?.isAppearanceLightStatusBars
        bars?.isAppearanceLightStatusBars = false // 검정 위에는 흰 아이콘
        onDispose { if (wasLight != null) bars.isAppearanceLightStatusBars = wasLight }
    }
    // 기능 예외(가이드 §2): 채팅은 상태 바까지 검정으로 덮어 배경 이미지가
    // 화면 끝까지 이어지게 한다. 테마 면색이 아니라 '무대의 암전'이라 토큰 밖이다
    Scaffold(containerColor = Color.Black) { padding ->
        // consumeWindowInsets: Scaffold가 이미 적용한 내비게이션 바 패딩을
        // imePadding이 또 더하지 않도록 소비 처리 — 키보드와 입력줄 사이 틈 방지
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            RoomBackdrop(backgroundKey = room?.backgroundKey ?: PbpPalette.DEFAULT_BACKGROUND) {
                // ── 상단 바: 타이틀 묶음은 정중앙, 버튼은 좌우 끝 (목업 final-design.html)
                if (capturing) {
                    CaptureModeBar(
                        subtitle = if (!endPicked) "끝 메시지를 탭하세요"
                        else "양 끝을 다시 탭해 조절할 수 있어요",
                        onClose = exitCapture,
                    )
                } else
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(PbpDimens.appBarHeight)
                        .background(tokens.barScrim)
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
                        // 입력 문법 도움말 — 입력줄 끝에 있던 것을 제목 옆으로 옮겼다.
                        // 입력창은 글을 쓰는 자리고, 도움말은 방 전체에 걸린 안내다.
                        // 좌우 버튼을 2:2로 맞춰야 titleInset 기준 중심이 흔들리지 않는다
                        IconButton(onClick = { helpOpen = true }) {
                            Text("?", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { nav.navigate(com.pbp.app.Routes.settings(roomId)) }) {
                            Text("⚙", fontSize = 18.sp, color = tokens.ink)
                        }
                        Spacer(Modifier.width(PbpDimens.gap1))
                        // 지금 말하고 있는 프로필 — 탭하면 전환 사이드바 (시안 ①).
                        // 시각 크기는 방 목록 헤더 오너 아바타와 같은 32, 히트는 40
                        Box(
                            Modifier
                                .size(PbpDimens.touchTarget)
                                .clip(CircleShape)
                                .clickable { showProfileDrawer = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Avatar(
                                emoji = active?.emoji ?: "🙂",
                                imagePath = active?.imagePath,
                                size = PbpDimens.avatarBar,
                                ringColor = tokens.signature,
                            )
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
                val readMarkId = remember(messages, peerReadAt) { readMarkTarget(messages, peerReadAt) }
                // 굴림이 끝난 요청 키 — 메시지마다 전체를 훑으면 O(N²)라 한 번만 만든다 (J5)
                val rolledRefs = remember(messages) {
                    messages.mapNotNullTo(mutableSetOf()) { it.judgeRef }
                }
                // 내가 가진 캐릭터 이름 — 이 이름이 대상이면 내가 굴릴 차례다
                // 내 차례인지는 **고유 id**로 가린다 — 이름으로 보면 같은 이름의 프로필이
                // 둘 있을 때 엉뚱한 쪽이 굴리거나 아무도 못 굴렸다. 구버전이 보낸 요청은
                // id가 없으니 그때만 이름으로 되돌아간다
                val myCharacterIds = remember(profiles) { profiles.map { it.characterId }.toSet() }
                val myCharacters = remember(profiles) { profiles.map { it.name }.toSet() }
                // 방을 만든 날은 로그 맨 위에만 찍는다 — 아직 못 불러온 옛 대화가 있으면
                // '이전 대화 불러오기' 위에 생성일이 뜨는 꼴이라 거짓말이 된다
                val fullyLoaded = messages.size >= totalCount
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
                        // messages(오래된 순) 기준 인덱스 — 캡처 범위 판정에 쓴다
                        val idx = messages.size - 1 - revIdx
                        val base = pieceBase[idx]
                        // 위 항목도 범위 안이면 간격을 없애 밴드가 맞닿게 한다 (목업 실측 틈 0px).
                        // reverseLayout이라 화면에서 '위'는 더 오래된 메시지 = idx - 1.
                        // 조각 단위이므로 '이 메시지의 첫 조각'과 '앞 메시지의 마지막 조각'을 본다
                        val joinsAbove = captureIdx?.contains(base) == true &&
                            captureIdx.contains(base - 1)
                        val topPad = when {
                            joinsAbove -> 0.dp
                            grouped -> PbpDimens.gap1
                            else -> PbpDimens.gap3
                        }
                        // 이 메시지 바로 위(더 오래된 쪽)와 날짜가 다르면 구분선을 얹는다.
                        // 가장 오래된 항목은 방 생성일과 비교한다 — 같은 날이면 맨 위
                        // 생성일 구분선이 이미 그 날을 말하고 있어 두 번 찍지 않는다.
                        val olderNeighbor = reversed.getOrNull(revIdx + 1)?.createdAt
                            ?: room?.createdAt?.takeIf { fullyLoaded }
                        val showDay = olderNeighbor == null ||
                            !ChatDates.isSameDay(olderNeighbor, message.createdAt)
                        // 말풍선 사이 간격은 위쪽에만 준다 — 아래에도 주면 이중으로 벌어진다.
                        // 상하 대칭 규칙(CLAUDE.md §0)의 의도적 예외
                        Column {
                            // 구분선이 이미 위아래 여백을 갖고 있어 말풍선 여백을 또 주면 벌어진다
                            // 구분선이 고른 구간 한가운데면 밴드도 그 위를 덮는다 —
                            // 안 그러면 노란 상자가 날짜에서 두 동강 난 것처럼 보인다
                            if (showDay) CaptureBandedDay(message.createdAt, banded = joinsAbove)
                            Box(Modifier.padding(top = if (showDay) 0.dp else topPad)) {
                                MessageBlock(
                                    message = message,
                                    grouped = grouped,
                                    showTime = showTime,
                                    showRead = message.id == readMarkId,
                                    themeColor = themeColor,
                                    markOf = { part -> captureMarkOf(captureIdx, base + part) },
                                    judgeState = judgeStateOf(
                                        message, rolledRefs, myCharacterIds, myCharacters,
                                    ),
                                    onJudgeTap = {
                                        // 캡처 중에는 굴리지 않는다 — MessageBlock에서도
                                        // 막지만, 굴림은 되돌릴 수 없어 두 겹으로 (A1)
                                        if (!capturing) vm.rollJudge(message) { statName ->
                                            needValueFor = message.id
                                            needValueName = statName
                                        }
                                    },
                                    onPartTap = if (capturing) ({ part -> onCaptureTap(base + part) }) else null,
                                    // 캡처 모드에서는 편집·삭제 팝업을 잠근다
                                    onLongPress = { if (!capturing) actionTargetId = it.id },
                                )
                            }
                        }
                    }
                    // 방을 만든 날 — reverseLayout이라 나중에 선언할수록 위로 간다.
                    // 옛 대화를 다 불러왔을 때만 (그래야 정말 로그의 맨 위다)
                    if (fullyLoaded) {
                        room?.createdAt?.let { createdAt ->
                            item(key = "room-created") { DayDivider(createdAt) }
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
                                        .background(tokens.scrim)
                                        .clickable { vm.loadOlder() }
                                        // 시각 크기는 그대로, 히트만 40dp (§6, P4)
                                        .heightIn(min = PbpDimens.touchTarget)
                                        .padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2),
                                )
                            }
                        }
                    }
                }
                // ── 시나리오 뷰어 — 입력줄 위에 맞붙는 판 (V4).
                // 진입점은 팔레트 칩 하나뿐이고, 여기서 저절로 뜨는 경로는 없다.
                // GM이 아닌 프로필로 바꾸면 사라진다(상태는 VM에 남아 GM으로 돌아오면
                // 읽던 자리에서 재개). 캡처 중에는 숨긴다 — 캡처 이미지에 찍히면 안 되고
                // 탭 히트테스트와도 부딪힌다 (A1과 같은 계열)
                if (scenarioOpen && gmActive && !capturing) {
                    ScenarioPanel(
                        state = scenarioState,
                        onSubmit = vm::loadScenario,
                        onStep = vm::scenarioStep,
                        onInsert = vm::scenarioInsertCurrent,
                        onReset = vm::scenarioReset,
                        onRestart = vm::scenarioRestart,
                        onParagraphMode = { vm.setScenarioParagraphMode(context, it) },
                        onClose = { scenarioOpen = false },
                        onFailureAck = vm::scenarioReset,
                    )
                }

                // ── 하단: 캡처 모드면 입력줄 자리를 캡처 바가 대신한다
                if (capturing) {
                    // 최대 200건의 복사와 높이 추정을 매 리컴포지션마다 다시 하지 않는다 (E7)
                    val pickedPieces = remember(pieces, captureIdx) {
                        captureIdx?.let { pieces.subList(it.first, it.last + 1) }.orEmpty()
                    }
                    // 일부 조각만 고른 메시지는 그 조각들만 남긴 본문으로 복제된다
                    val picked = remember(messages, pickedPieces) {
                        messagesForPieces(messages, pickedPieces)
                    }
                    val estimatedPx = remember(picked) {
                        CaptureRenderer.estimateHeightPx(picked)
                    }
                    CaptureBar(
                        count = pickedPieces.size,
                        dateRange = if (!endPicked) null else dateRangeLabel(picked),
                        startLabel = picked.firstOrNull()?.let {
                            "시작 " + com.pbp.shared.CaptureLayout.dateOnly(it.createdAt) +
                                " · " + (it.senderName ?: "이름 없음")
                        },
                        estimatedPx = if (!endPicked) null else estimatedPx,
                        overLimit = pickedPieces.size > ChatViewModel.PAGE_SIZE,
                        rendering = captureRendering,
                        onMake = {
                            captureRendering = true
                            vm.renderCapture(context, picked) { error ->
                                captureRendering = false
                                if (error == null) {
                                    exitCapture()
                                    nav.navigate(com.pbp.app.Routes.CAPTURE)
                                } else {
                                    // 사유를 그대로 보여 준다 — 로그를 볼 수 없는 환경에서
                                    // "만들지 못했습니다"만으로는 고칠 수가 없다
                                    Toast.makeText(
                                        context,
                                        "이미지를 만들지 못했습니다 — $error",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        },
                    )
                } else
                // ── 입력 영역: GM 도구 칩 + 잡담 토글 + 입력줄
                // (프로필 교체는 상단 바 아바타 → 사이드바로 옮겼다)
                InputZone(
                    profiles = profiles,
                    activeId = active?.id,
                    themeColor = themeColor,
                    onSend = { text, ooc ->
                        vm.send(text, ooc)
                        vm.notifyTypingStopped()
                        pendingScrollToLatest = true // 내 전송·판정은 항상 최신으로 스크롤
                    },
                    typingName = peerState.typingName,
                    typingUntil = peerState.typingUntil,
                    gmActive = gmActive,
                    onJudgeRequest = { judgeSheetOpen = true },
                    onScenarioViewer = {
                        scenarioOpen = true
                        vm.openScenario() // 지난번 문서가 있으면 그 자리에서 다시 연다
                    },
                    scenarioOpen = scenarioOpen && gmActive,
                    insertFlow = vm.scenarioInsert,
                    onTyping = vm::notifyTyping,
                    onTypingStopped = vm::notifyTypingStopped,
                    rule = room?.rule ?: com.pbp.shared.Rules.COC7,
                )
            }
        }
    }

    if (helpOpen) MarkupHelpDialog(onDismiss = { helpOpen = false })

    // 프로필 전환 사이드바 — 입력줄 스트립과 **같은 콜백**을 쓴다 (동작 분기 금지)
    BackHandler(enabled = showProfileDrawer) { showProfileDrawer = false }
    ProfileDrawer(
        visible = showProfileDrawer,
        profiles = profiles,
        activeId = room?.activeProfileId,
        onSwitch = { vm.switchTo(it) },
        onEditProfile = { nav.navigate(com.pbp.app.Routes.profile(it.id)) },
        onAddProfile = { showAddProfile = true },
        onDismiss = { showProfileDrawer = false },
    )

    if (judgeSheetOpen) {
        // 내 캐릭터(GM 제외) + 상대가 올린 명단.
        // 오너 프로필은 CharacterProfile이 아니라 애초에 이 목록에 들어오지 않는다.
        //
        // 중복 제거는 **고유 id 기준**이다. 이름으로 걸렀더니 같은 방에 같은 이름의
        // 프로필이 둘 있으면 뒤엣것이 통째로 사라져 판정 대상으로 고를 수조차 없었다.
        // id가 없는 구버전 상대의 캐릭터만 예전처럼 이름으로 거른다
        // 상대 캐릭터의 얼굴은 아바타 id를 파일로 풀어야 나온다 — 목록이 뜨는 김에 받는다
        val peerAvatars by vm.peerAvatarPaths.collectAsState()
        LaunchedEffect(peerState.peerCharacters) {
            vm.resolvePeerAvatars(peerState.peerCharacters.mapNotNull { it.avatarId })
        }
        val candidates = remember(profiles, peerState.peerCharacters, peerAvatars) {
            (
                profiles.filterNot { it.isGm }.map {
                    JudgeCandidate(
                        it.characterId, it.name, it.imagePath, it.emoji, it.nameColor,
                        numericStatNames(it),
                    )
                } + peerState.peerCharacters.map {
                    JudgeCandidate(
                        it.id, it.name, it.avatarId?.let(peerAvatars::get), it.emoji,
                        it.nameColor, it.stats,
                    )
                }
                ).distinctBy { it.id ?: "name:${it.name}" }
        }
        JudgeRequestSheet(
            candidates = candidates,
            rule = room?.rule ?: com.pbp.shared.Rules.COC7,
            onDismiss = { judgeSheetOpen = false },
            onSend = { targetId, targetName, statName ->
                vm.sendJudgeRequest(targetId, targetName, statName)
                judgeSheetOpen = false
                pendingScrollToLatest = true
            },
        )
    }

    needValueFor?.let { requestId ->
        val request = remember(messages, requestId) { messages.find { it.id == requestId } }
        if (request == null) {
            needValueFor = null
        } else {
            JudgeValueDialog(
                targetName = request.judgeTarget ?: "",
                statName = needValueName,
                onDismiss = { needValueFor = null },
                onConfirm = { value ->
                    vm.addStatAndRoll(request, needValueName, value)
                    needValueFor = null
                },
            )
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
            onCapture = {
                captureStart = "${target.id}:0"
                captureEnd = null
                actionTargetId = null
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
            title = { PbpDialogTitle("메시지 삭제") },
            text = { Text("이 메시지를 삭제할까요? 공유된 방이면 상대 화면에서도 사라집니다.") },
            confirmButton = {
                PbpDialogButton("삭제", kind = PbpButtonKind.Danger, onClick = {
                    vm.delete(target)
                    deleteTargetId = null
                })
            },
            dismissButton = {
                PbpDialogButton("취소", { deleteTargetId = null }, kind = PbpButtonKind.Cancel)
            },
        )
    }
}

/**
 * 요청 카드의 상태 (J5) — 굴림 결과가 있으면 완료, 그 캐릭터가 내게 있으면 내 차례.
 */
internal fun judgeStateOf(
    message: Message,
    rolledRefs: Set<String>,
    myCharacterIds: Set<String>,
    myCharacters: Set<String>,
): JudgeState = when {
    judgeKey(message) in rolledRefs -> JudgeState.Done
    // id가 실린 요청은 id로만 가린다 — 이름이 같아도 남의 캐릭터를 내 차례로 치지 않는다
    message.judgeTargetId != null -> {
        if (message.judgeTargetId in myCharacterIds) JudgeState.MyTurn else JudgeState.Waiting
    }
    message.judgeTarget in myCharacters -> JudgeState.MyTurn
    else -> JudgeState.Waiting
}
