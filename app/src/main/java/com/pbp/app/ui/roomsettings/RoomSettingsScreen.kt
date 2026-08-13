package com.pbp.app.ui.roomsettings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.pbp.app.data.Images
import com.pbp.app.ui.common.HexColorDialog
import com.pbp.app.ui.common.PbpButtonKind
import com.pbp.app.ui.common.PbpDialogButton
import com.pbp.app.ui.common.PbpDialogTitle
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import com.pbp.app.ui.theme.PbpPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RoomSettingsViewModel(private val app: PbpApp, private val roomId: Long) : ViewModel() {
    private val repo = app.repository

    val room = repo.observeRoom(roomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setThemeColor(color: Long) = safeLaunch(app) { repo.setThemeColor(roomId, color) }

    fun setBackground(key: String) = safeLaunch(app) { repo.setBackground(roomId, key) }

    fun importBackground(uri: Uri) = safeLaunch(app) {
        withContext(Dispatchers.IO) {
            // 배경은 최대 1600px로 축소 저장
            Images.importDownscaled(
                app, uri, "backgrounds", maxSize = com.pbp.app.data.ImageSizes.BACKGROUND,
            )?.let { repo.setBackground(roomId, it) }
        }
    }

    fun share(onResult: (String?) -> Unit) = viewModelScope.launch {
        onResult(app.syncManager.shareRoom(roomId))
    }

    /** 화면을 열 때 한 번 — 죽은 코드를 그대로 보여 주지 않는다 */
    fun refreshInviteCode() = safeLaunch(app) {
        app.syncManager.liveInviteCode(roomId)
    }

    /**
     * 로그 내보내기 (A) — 채팅 상단 바에 있던 것을 여기로 옮겼다.
     * 매 세션 쓰는 기능이 아니라 상단 바 자리를 차지할 이유가 없다.
     *
     * 화면 스코프가 아니라 앱 스코프에서 돌린다 (K1). 파일을 고른 뒤 설정 화면을
     * 벗어나면 코루틴이 끊기는데, 특히 PDF는 중단 지점이 많아 **반쯤 기록된 파일**이
     * 남고 완료 안내도 뜨지 않았다. 아래 [resetLogs]가 같은 이유로 쓰는 경로다 (N1).
     */
    fun exportTo(uri: Uri, format: ExportFormat, onResult: (Boolean) -> Unit) {
        app.syncManager.runInAppScope(
            work = { exportBlocking(uri, format) },
            onDone = onResult,
        )
    }

    private suspend fun exportBlocking(uri: Uri, format: ExportFormat): Boolean {
        val name = room.value?.name ?: "PbP"
        // 내보내기는 화면 페이징과 무관하게 전체
        val messages = withContext(Dispatchers.IO) { repo.allMessages(roomId) }
        return when (format) {
            ExportFormat.Text -> withContext(Dispatchers.IO) {
                val text = com.pbp.app.export.LogExporter.buildText(name, messages)
                runCatching {
                    app.contentResolver.openOutputStream(uri)!!
                        .use { it.write(text.toByteArray()) }
                }.isSuccess
            }

            ExportFormat.Html -> withContext(Dispatchers.IO) {
                val html = com.pbp.app.export.LogExporter.buildHtml(
                    roomName = name,
                    roomIcon = room.value?.icon ?: "",
                    messages = messages,
                )
                runCatching {
                    app.contentResolver.openOutputStream(uri)!!
                        .use { it.write(html.toByteArray()) }
                }.isSuccess
            }

            // PDF는 같은 HTML을 WebView 인쇄 경로로 뽑는다 — 서식이 그대로 남는다.
            // WebView는 메인 스레드 전용이라 조립(IO)과 쓰기(Main)를 나눈다
            ExportFormat.Pdf -> {
                val html = withContext(Dispatchers.IO) {
                    com.pbp.app.export.LogExporter.buildHtml(
                        roomName = name,
                        roomIcon = room.value?.icon ?: "",
                        messages = messages,
                    )
                }
                runCatching {
                    app.contentResolver.openFileDescriptor(uri, "w")!!.use { pfd ->
                        com.pbp.app.export.PdfExporter.write(app, html, name, pfd) == null
                    }
                }.getOrDefault(false)
            }
        }
    }

    /**
     * 방 로그 전체 리셋 — 로컬·서버·상대 로그까지 삭제.
     * 파괴적 작업이라 화면을 벗어나도 끝까지 돌도록 앱 수준 스코프에서 실행한다 (N1).
     */
    fun resetLogs(onResult: (Boolean) -> Unit) {
        app.syncManager.runInAppScope(work = { repo.resetLogs(roomId) }, onDone = onResult)
    }
}

@Composable
fun RoomSettingsScreen(nav: NavController, roomId: Long) {
    val context = LocalContext.current
    val app = context.applicationContext as PbpApp
    val vm: RoomSettingsViewModel = viewModel(key = "settings-$roomId", factory = viewModelFactory {
        initializer { RoomSettingsViewModel(app, roomId) }
    })
    val tokens = Pbp.colors
    val room by vm.room.collectAsState()
    // 초대 코드는 1회용이라 상대가 들어온 순간 죽는다 — 화면에 들고 있던 값을 그대로
    // 보여 주면 통하지 않는 코드를 불러 주게 된다. 열 때 살아 있는 것으로 맞춘다
    // 확인이 끝나기 전에는 코드를 내보이지 않는다 — 들고 있던 값이 이미 소비됐을 수
    // 있고, 그걸 읽어 불러 주면 통하지 않는다 (PC도 같은 규칙)
    var codeChecking by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        vm.refreshInviteCode()
        codeChecking = false
    }
    var showCustomTheme by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        if (it != null) vm.importBackground(it)
    }
    // 어떤 형식으로 저장할지 — 파일 선택창을 열기 전에 정한다
    var exportFormat by remember { mutableStateOf(ExportFormat.Html) }
    var showExportPicker by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        // MIME은 형식마다 다르지만 CreateDocument는 생성 시점에 고정된다 —
        // 어느 형식이든 열리도록 임의 바이너리로 두고 확장자로 구분한다
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) vm.exportTo(uri, exportFormat) { ok ->
            Toast.makeText(
                context,
                if (ok) "${exportFormat.label} 로그를 저장했습니다" else "저장에 실패했습니다",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val startExport = { format: ExportFormat ->
        exportFormat = format
        showExportPicker = false
        // 문서 프로바이더가 없는 기기에서 ActivityNotFoundException 방지 (C3)
        runCatching {
            exportLauncher.launch("${room?.name ?: "PbP"}_log.${format.extension}")
        }.onFailure {
            Toast.makeText(context, "파일 저장 화면을 열 수 없습니다", Toast.LENGTH_SHORT).show()
        }
        Unit
    }

    Scaffold(containerColor = tokens.bg) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // 타이틀은 버튼 위에 겹쳐 두고 좌우 인셋을 같게 준다 — 버튼 개수와
            // 무관하게 정중앙 (방 목록과 같은 구조, P1)
            Box(Modifier.fillMaxWidth().height(PbpDimens.appBarHeight)) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = PbpDimens.gap2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Text("←", fontSize = 20.sp, color = tokens.ink)
                    }
                }
                Text(
                    "방 설정 · ${room?.name ?: ""}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = PbpDimens.titleInsetNarrow),
                )
            }

            SectionTitle("테마 컬러")
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                // 프리셋 3종 + 커스텀 = 4셀이라 정확히 1행 (목업 01-B)
                modifier = Modifier.padding(horizontal = PbpDimens.gap4).height(76.dp),
                horizontalArrangement = Arrangement.spacedBy(PbpDimens.gap2),
                verticalArrangement = Arrangement.spacedBy(PbpDimens.gap2),
                userScrollEnabled = false,
            ) {
                items(PbpPalette.themePresets) { (color, label) ->
                    val sel = room?.themeColor == color
                    ThemeCell(
                        label = label,
                        selected = sel,
                        swatch = { modifier ->
                            Box(modifier.background(Color(color)))
                        },
                        onClick = { vm.setThemeColor(color) },
                    )
                }
                item {
                    ThemeCell(
                        label = "커스텀",
                        selected = PbpPalette.themePresets.none { it.first == room?.themeColor },
                        swatch = { modifier ->
                            Box(
                                modifier.background(
                                    com.pbp.app.ui.common.customColorBrush
                                )
                            )
                        },
                        onClick = { showCustomTheme = true },
                    )
                }
            }
            // 테마 그리드는 라벨 있는 셀 체계라, 최근 색은 그리드 아래 별도 줄로 (목업 01-B)
            com.pbp.app.ui.common.RecentColorRow(
                selected = room?.themeColor,
                slot = com.pbp.app.data.RecentColors.Slot.THEME,
                onSelect = { vm.setThemeColor(it) },
                modifier = Modifier.padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap2),
            )

            SectionTitle("배경 이미지")
            // 갤러리에서 고른 커스텀 배경이면 프리셋 뒤에 썸네일 셀이 추가된다 (3열 → 3행)
            val customBg = room?.backgroundKey
                ?.takeIf { PbpPalette.backgroundPresets[it] == null }
            val bgRows = if (customBg != null) 3 else 2
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .padding(horizontal = PbpDimens.gap4)
                    .height((72 * bgRows + 8 * (bgRows - 1) + 2).dp),
                horizontalArrangement = Arrangement.spacedBy(PbpDimens.gap2),
                verticalArrangement = Arrangement.spacedBy(PbpDimens.gap2),
                userScrollEnabled = false,
            ) {
                items(PbpPalette.backgroundPresets.keys.toList()) { key ->
                    val preset = PbpPalette.backgroundPresets.getValue(key)
                    val sel = room?.backgroundKey == key
                    Box(
                        Modifier
                            .height(72.dp)
                            .clip(RoundedCornerShape(PbpDimens.rCell))
                            .background(
                                Brush.verticalGradient(listOf(Color(preset.first), Color(preset.second)))
                            )
                            .border(
                                1.5.dp,
                                if (sel) tokens.signature else tokens.line,
                                RoundedCornerShape(PbpDimens.rCell),
                            )
                            .clickable { vm.setBackground(key) },
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (sel) UsingBadge()
                    }
                }
                if (customBg != null) {
                    item {
                        // 현재 커스텀 배경 미리보기 — 어떤 이미지인지 바로 보인다
                        Box(
                            Modifier
                                .height(72.dp)
                                .clip(RoundedCornerShape(PbpDimens.rCell))
                                .border(1.5.dp, tokens.signature, RoundedCornerShape(PbpDimens.rCell)),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            coil3.compose.AsyncImage(
                                model = java.io.File(customBg),
                                contentDescription = null,
                                modifier = Modifier.matchParentSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                            UsingBadge("커스텀 · 사용 중")
                        }
                    }
                }
                item {
                    Box(
                        Modifier
                            .height(72.dp)
                            .outlinedCell(tokens.line)
                            .clickable {
                                bgPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "갤러리에서\n선택",
                            fontSize = 10.sp,
                            color = tokens.inkDim,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            SectionTitle("공유·기타")
            SettingRow(
                title = "방 공유 · 초대 코드",
                subtitle = when {
                    codeChecking -> "코드 확인 중…"
                    room?.inviteCode != null -> "코드 ${room?.inviteCode} — 탭하면 복사됩니다"
                    else -> "초대 코드를 만들어 상대를 부릅니다"
                },
            ) {
                // 코드가 이미 있어도 share를 다시 호출한다 — 멤버 등록·코드 매핑·백필이
                // 중간에 실패해 '죽은 초대코드'가 된 방을 멱등 복구 (R2)
                vm.share { code ->
                    // 코드를 보여 주는 창을 한 번 더 여는 대신 바로 복사한다 —
                    // 여섯 글자를 확인하려고 단계를 하나 더 밟을 이유가 없다
                    if (code != null) copyInviteCode(context, code)
                    else Toast.makeText(context, "공유에 실패했습니다. 네트워크를 확인해주세요.", Toast.LENGTH_SHORT).show()
                }
            }
            SettingRow(
                title = "로그 내보내기",
                subtitle = "전체 대화를 HTML · 텍스트 · PDF로 저장합니다",
            ) { showExportPicker = true }
            SettingRow(
                title = "알림",
                subtitle = "미확인 메시지 도착 시 푸시 · 본문은 표시되지 않습니다",
            ) { }
            SettingRow(
                title = "방 로그 초기화",
                subtitle = "모든 메시지를 삭제합니다 · 상대방의 로그도 함께 삭제됩니다",
            ) { showResetConfirm = true }
            Spacer(Modifier.height(PbpDimens.gap6))
        }
    }

    if (showExportPicker) {
        AlertDialog(
            onDismissRequest = { showExportPicker = false },
            title = { PbpDialogTitle("로그 내보내기") },
            text = {
                Column {
                    ExportFormat.entries.forEach { format ->
                        SettingRow(title = format.label, subtitle = format.hint) {
                            startExport(format)
                        }
                    }
                }
            },
            confirmButton = {
                PbpDialogButton("취소", { showExportPicker = false }, kind = PbpButtonKind.Cancel)
            },
            containerColor = tokens.panel,
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { PbpDialogTitle("방 로그 초기화") },
            text = {
                Text(
                    "이 방의 모든 메시지가 삭제됩니다.\n" +
                        "공유된 방이면 서버와 상대방의 로그도 전부 삭제되며, 되돌릴 수 없습니다."
                )
            },
            confirmButton = {
                PbpDialogButton("전부 삭제", kind = PbpButtonKind.Danger, onClick = {
                    showResetConfirm = false
                    vm.resetLogs { ok ->
                        Toast.makeText(
                            context,
                            if (ok) "방 로그를 초기화했습니다"
                            else "서버 삭제가 완료되지 않아 중단했습니다. 일부만 삭제되었을 수 " +
                                "있으니 네트워크 확인 후 다시 시도해주세요.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                })
            },
            dismissButton = {
                PbpDialogButton("취소", { showResetConfirm = false }, kind = PbpButtonKind.Cancel)
            },
        )
    }

    if (showCustomTheme) {
        HexColorDialog(
            title = "테마 컬러 (커스텀)",
            onDismiss = { showCustomTheme = false },
            onPick = { color ->
                vm.setThemeColor(color)
                com.pbp.app.data.RecentColors.add(context, com.pbp.app.data.RecentColors.Slot.THEME, color)
                showCustomTheme = false
            },
            initial = room?.themeColor,
        )
    }

}

@Composable
private fun UsingBadge(label: String = "사용 중") {
    Text(
        label,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = Pbp.colors.onSignature,
        modifier = Modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Pbp.colors.signature)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun ThemeCell(
    label: String,
    selected: Boolean,
    swatch: @Composable (Modifier) -> Unit,
    onClick: () -> Unit,
) {
    val tokens = Pbp.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(PbpDimens.rCell))
            // 선택 표시는 배경 그리드와 동일하게 시그니처 옐로로 통일
            .background(if (selected) tokens.signature.copy(alpha = .12f) else tokens.panel2)
            .border(
                1.5.dp,
                if (selected) tokens.signature else tokens.line,
                RoundedCornerShape(PbpDimens.rCell),
            )
            .clickable(onClick = onClick)
            .padding(PbpDimens.gap3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        swatch(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.height(PbpDimens.gap1))
        Text(
            label,
            fontSize = 10.sp,
            color = if (selected) tokens.signatureInk else tokens.inkDim,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    // 섹션 사이 간격은 제목 앞 여백으로 분리 — 제목의 상하 패딩은 대칭 (CLAUDE.md §0).
    // 앞 여백 16 + 상하 8 = 제목 위 24dp로 규정에 맞춘다 (P3)
    Spacer(Modifier.height(PbpDimens.gap4))
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = PbpDimens.labelTracking,
        color = Pbp.colors.inkDim,
        // 좌우 대칭 — start만 주면 오른쪽 끝이 다른 항목과 어긋난다 (P2)
        modifier = Modifier.padding(
            horizontal = PbpDimens.gap4,
            vertical = PbpDimens.gap2,
        ),
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    val tokens = Pbp.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = tokens.ink)
            Text(subtitle, fontSize = 11.sp, color = tokens.inkDim)
        }
        Text("›", fontSize = 15.sp, color = tokens.inkDim)
    }
}

private fun Modifier.outlinedCell(color: Color): Modifier = this
    .clip(RoundedCornerShape(PbpDimens.rCell))
    .border(1.5.dp, color, RoundedCornerShape(PbpDimens.rCell))

/** 초대 코드를 클립보드에 담고, 무엇을 담았는지 그대로 알린다 */
private fun copyInviteCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PbP 초대 코드", code))
    Toast.makeText(context, "초대 코드 \"$code\" 복사했습니다", Toast.LENGTH_SHORT).show()
}

/**
 * 로그 내보내기 형식.
 *
 * HTML은 서식을 그대로 보는 용도, 텍스트는 다른 도구에 붙여 넣을 원문,
 * PDF는 서식을 지킨 채 인쇄·배포하기 좋은 형태다.
 */
enum class ExportFormat(val label: String, val extension: String, val hint: String) {
    Html("HTML", "html", "말풍선·색·서술까지 그대로 · 브라우저로 봅니다"),
    Text("텍스트", "txt", "서식 없이 날짜·시각·화자·본문만"),
    Pdf("PDF", "pdf", "서식을 지킨 채 페이지로 나눠 저장"),
}
