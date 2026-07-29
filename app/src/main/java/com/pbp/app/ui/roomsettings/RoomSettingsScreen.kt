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
        Images.importDownscaled(app, uri, "backgrounds", maxSize = 1600)
            ?.let { repo.setBackground(roomId, it) }
    }

    fun share(onResult: (String?) -> Unit) = viewModelScope.launch {
        onResult(app.syncManager.shareRoom(roomId))
    }

    /** 방 로그 전체 리셋 — 로컬·서버·상대 로그까지 삭제 */
    fun resetLogs(onResult: (Boolean) -> Unit) = viewModelScope.launch {
        onResult(repo.resetLogs(roomId))
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
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
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
                modifier = Modifier.padding(horizontal = 16.dp).height(150.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                                    Brush.sweepGradient(
                                        listOf(
                                            Color(0xFFFF6666), Color(0xFFFFCC66), Color(0xFF66DD66),
                                            Color(0xFF66CCFF), Color(0xFFCC66FF), Color(0xFFFF6666),
                                        )
                                    )
                                )
                            )
                        },
                        onClick = { showCustomTheme = true },
                    )
                }
            }

            SectionTitle("배경 이미지")
            // 갤러리에서 고른 커스텀 배경이면 프리셋 뒤에 썸네일 셀이 추가된다 (3열 → 3행)
            val customBg = room?.backgroundKey
                ?.takeIf { PbpPalette.backgroundPresets[it] == null }
            val bgRows = if (customBg != null) 3 else 2
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .height((76 * bgRows + 8 * (bgRows - 1) + 2).dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = false,
            ) {
                items(PbpPalette.backgroundPresets.keys.toList()) { key ->
                    val preset = PbpPalette.backgroundPresets.getValue(key)
                    val sel = room?.backgroundKey == key
                    Box(
                        Modifier
                            .height(76.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.verticalGradient(listOf(Color(preset.first), Color(preset.second)))
                            )
                            .border(
                                1.5.dp,
                                if (sel) tokens.signature else tokens.line,
                                RoundedCornerShape(12.dp),
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
                                .height(76.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.5.dp, tokens.signature, RoundedCornerShape(12.dp)),
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
                            .height(76.dp)
                            .dashedCell(tokens.line)
                            .clickable {
                                bgPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "갤러리에서\n선택",
                            fontSize = 9.5.sp,
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
                val existing = room?.inviteCode
                if (existing != null) {
                    shareCode = existing
                } else {
                    vm.share { code ->
                        if (code != null) shareCode = code
                        else Toast.makeText(context, "공유에 실패했습니다. 네트워크를 확인해주세요.", Toast.LENGTH_SHORT).show()
                    }
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
            Spacer(Modifier.height(30.dp))
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
                            else "로컬 로그는 지웠지만 서버 삭제에 실패했습니다. 네트워크를 확인해주세요.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) { Text("전부 삭제", color = Color(0xFFFF6B6B)) }
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
                showCustomTheme = false
            },
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
                    Spacer(Modifier.height(8.dp))
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
        fontSize = 8.5.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF1A1A1A),
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
            .clip(RoundedCornerShape(12.dp))
            // 선택 표시는 배경 그리드와 동일하게 시그니처 옐로로 통일
            .background(if (selected) tokens.signature.copy(alpha = .12f) else Color(0x08FFFFFF))
            .border(
                1.5.dp,
                if (selected) tokens.signature else tokens.line,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        swatch(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            fontSize = 9.sp,
            color = if (selected) tokens.signature else tokens.inkDim,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        color = Pbp.colors.inkDim,
        modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 10.dp),
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    val tokens = Pbp.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = tokens.ink)
            Text(subtitle, fontSize = 10.5.sp, color = tokens.inkDim)
        }
        Text("›", fontSize = 16.sp, color = tokens.inkDim)
    }
}

private fun Modifier.dashedCell(color: Color): Modifier = this
    .clip(RoundedCornerShape(12.dp))
    .border(1.5.dp, color, RoundedCornerShape(12.dp))
