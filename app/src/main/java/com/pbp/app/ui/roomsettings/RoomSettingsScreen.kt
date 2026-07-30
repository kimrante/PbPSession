package com.pbp.app.ui.roomsettings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.pbp.app.PbpApp
import com.pbp.app.data.Images
import com.pbp.app.ui.common.HexColorDialog
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import com.pbp.app.ui.theme.PbpPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RoomSettingsViewModel(private val app: PbpApp, private val roomId: Long) : ViewModel() {
    private val repo = app.repository

    val room = repo.observeRoom(roomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setThemeColor(color: Long) = viewModelScope.launch { repo.setThemeColor(roomId, color) }

    fun setBackground(key: String) = viewModelScope.launch { repo.setBackground(roomId, key) }

    fun importBackground(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        // 배경은 최대 1600px로 축소 저장
        Images.importDownscaled(app, uri, "backgrounds", maxSize = com.pbp.app.data.ImageSizes.BACKGROUND)
            ?.let { repo.setBackground(roomId, it) }
    }

    fun share(onResult: (String?) -> Unit) = viewModelScope.launch {
        onResult(app.syncManager.shareRoom(roomId))
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
    var showCustomTheme by remember { mutableStateOf(false) }
    var shareCode by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        if (it != null) vm.importBackground(it)
    }

    Scaffold(containerColor = tokens.bg) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.fillMaxWidth().height(PbpDimens.appBarHeight).padding(horizontal = PbpDimens.gap2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Text("←", fontSize = 20.sp, color = tokens.ink)
                }
                Text(
                    "방 설정 · ${room?.name ?: ""}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.ink,
                    maxLines = 1,
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
                subtitle = room?.inviteCode?.let { "코드 $it — 탭하여 확인" } ?: "초대 코드를 만들어 상대를 부릅니다",
            ) {
                // 코드가 이미 있어도 share를 다시 호출한다 — 멤버 등록·코드 매핑·백필이
                // 중간에 실패해 '죽은 초대코드'가 된 방을 멱등 복구 (R2)
                vm.share { code ->
                    if (code != null) shareCode = code
                    else Toast.makeText(context, "공유에 실패했습니다. 네트워크를 확인해주세요.", Toast.LENGTH_SHORT).show()
                }
            }
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

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("방 로그 초기화") },
            text = {
                Text(
                    "이 방의 모든 메시지가 삭제됩니다.\n" +
                        "공유된 방이면 서버와 상대방의 로그도 전부 삭제되며, 되돌릴 수 없습니다."
                )
            },
            confirmButton = {
                TextButton(onClick = {
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
                }) { Text("전부 삭제", color = Pbp.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("취소") } },
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

    shareCode?.let { code ->
        AlertDialog(
            onDismissRequest = { shareCode = null },
            title = { Text("초대 코드") },
            text = {
                Column {
                    Text(
                        code,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.signature,
                        letterSpacing = 4.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(PbpDimens.gap2))
                    Text(
                        "상대가 방 목록의 '참여'에서 이 코드를 입력하면 같은 방에 연결됩니다.",
                        fontSize = 13.sp,
                        color = tokens.inkDim,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { shareCode = null }) { Text("닫기") } },
        )
    }
}

@Composable
private fun UsingBadge(label: String = "사용 중") {
    Text(
        label,
        fontSize = 9.sp,
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
            .background(if (selected) tokens.signature.copy(alpha = .12f) else Color(0x08FFFFFF))
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
            color = if (selected) tokens.signature else tokens.inkDim,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    // 섹션 사이 간격은 제목 앞 여백으로 분리 — 제목의 상하 패딩은 대칭 (CLAUDE.md §0)
    Spacer(Modifier.height(PbpDimens.gap5))
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        color = Pbp.colors.inkDim,
        modifier = Modifier.padding(
            start = PbpDimens.gap4,
            top = PbpDimens.gap2,
            bottom = PbpDimens.gap2,
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
