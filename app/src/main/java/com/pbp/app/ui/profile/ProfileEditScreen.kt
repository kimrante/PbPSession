package com.pbp.app.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.pbp.app.PbpApp
import com.pbp.app.data.CharacterProfile
import com.pbp.shared.ProfileStats
import com.pbp.app.ui.common.Avatar
import com.pbp.app.ui.common.HexColorDialog
import com.pbp.app.ui.common.ImageCropDialog
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import com.pbp.app.ui.theme.PbpPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ProfileEditViewModel(private val app: PbpApp) : ViewModel() {

    suspend fun load(profileId: Long): CharacterProfile? =
        if (profileId == 0L) null else app.database.profileDao().get(profileId)

    fun save(
        existing: CharacterProfile?,
        name: String,
        nameColor: Long?,
        bubbleColor: Long?,
        textColor: Long?,
        newImagePath: String?, // 크롭 다이얼로그가 만든 512px JPEG 경로
        stats: List<Pair<String, String>>,
        onDone: () -> Unit,
    ) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val profile = (existing ?: CharacterProfile(name = "")).copy(
                name = name.trim().ifEmpty { "이름 없음" },
                nameColor = nameColor,
                bubbleColor = bubbleColor,
                textColor = textColor,
                imagePath = newImagePath ?: existing?.imagePath,
                // 저장할 때 이름 가나다순으로 — 추가한 순서대로 쌓이면 찾기 어렵다
                stats = ProfileStats.encode(ProfileStats.sortByName(stats)),
            )
            app.repository.saveProfile(profile)
        }
        onDone()
    }

    fun delete(profile: CharacterProfile, onDone: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            app.repository.deleteProfile(profile)
            profile.imagePath?.let { File(it).delete() }
        }
        onDone()
    }
}

@Composable
fun ProfileEditScreen(nav: NavController, profileId: Long) {
    val app = LocalContext.current.applicationContext as PbpApp
    val vm: ProfileEditViewModel = viewModel(factory = viewModelFactory {
        initializer { ProfileEditViewModel(app) }
    })
    val tokens = Pbp.colors

    // 폼 상태는 rememberSaveable — 화면 회전 시 DB 값으로 덮어써 입력이 날아가는 것 방지 (P2-4)
    var loaded by rememberSaveable { mutableStateOf(false) }
    var existing by remember { mutableStateOf<CharacterProfile?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var nameColor by rememberSaveable {
        mutableStateOf<Long?>(PbpPalette.namePresets.first())
    }
    var bubbleColor by rememberSaveable {
        mutableStateOf<Long?>(PbpPalette.bubblePresets.first())
    }
    // 말풍선 안 글씨색. null이면 테마 기본 잉크
    var textColor by rememberSaveable { mutableStateOf<Long?>(null) }
    var cropSource by remember { mutableStateOf<Uri?>(null) } // 크롭 대기 중인 갤러리 이미지
    var pickedPath by rememberSaveable {
        mutableStateOf<String?>(null) // 크롭 완료된 512px 파일
    }
    // 저장 전 프로세스가 죽으면 그 파일은 다음 시작 때 정리(ImageGc) 대상이라 사라진다 —
    // 경로만 복원되면 빈 아바타가 저장된다. 없으면 비운다 (V4)
    LaunchedEffect(Unit) {
        pickedPath?.let { if (!java.io.File(it).exists()) pickedPath = null }
    }
    var customTarget by remember { mutableStateOf<String?>(null) } // "name" | "bubble"
    val stats = rememberSaveable(
        saver = listSaver(
            save = { listOf(ProfileStats.encode(it)) },
            restore = { saved ->
                ProfileStats.decode(saved.first()).let { decoded ->
                    mutableStateListOf<Pair<String, String>>()
                        .apply { addAll(decoded) }
                }
            },
        ),
    ) { mutableStateListOf<Pair<String, String>>() }
    var newStatName by rememberSaveable { mutableStateOf("") }
    var newStatValue by rememberSaveable { mutableStateOf("") }
    // 꾹 눌러 편집 중인 항목 — 그 줄만 입력 폼으로 바뀐다
    var editingStat by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 되돌릴 수 없는 데이터 손실 — 방 삭제·메시지 삭제와 같은 위계로 한 번 묻는다 (E1)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var editName by rememberSaveable { mutableStateOf("") }
    var editValue by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(profileId) {
        existing = vm.load(profileId)
        // DB 로드는 최초 1회만 — 회전 후 재실행 시 편집 중이던 폼을 덮어쓰지 않는다 (P2-4)
        if (!loaded) {
            existing?.let {
                textColor = it.textColor
                name = it.name
                nameColor = it.nameColor ?: PbpPalette.namePresets.first()
                bubbleColor = it.bubbleColor ?: PbpPalette.bubblePresets.first()
                stats.addAll(ProfileStats.decode(it.stats))
            }
            loaded = true
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        if (it != null) cropSource = it // 선택 후 크롭 다이얼로그로
    }

    if (!loaded) return

    Scaffold(containerColor = tokens.bg) { padding ->
        // Scaffold가 적용한 인셋을 소비해 imePadding 이중 적용(키보드 위 틈) 방지
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            // 상단 바
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(PbpDimens.appBarHeight)
                    .padding(horizontal = PbpDimens.gap2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Text("←", fontSize = 20.sp, color = tokens.ink)
                }
                Text("프로필 편집", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
                Spacer(Modifier.weight(1f))
                // 기존 프로필이 아직 로드되지 않은 상태로 저장하면 새 프로필이 복제된다 (N9)
                val canSave = profileId == 0L || existing != null
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (canSave) tokens.signature else tokens.signature.copy(alpha = .4f))
                        .clickable(enabled = canSave) {
                            // 추가 버튼을 누르지 않고 저장해도 입력 중인 값은 반영
                            val pending = newStatName.trim()
                                .takeIf { it.isNotEmpty() }
                                ?.let { listOf(it to newStatValue.trim()) }
                                .orEmpty()
                            vm.save(existing, name, nameColor, bubbleColor, textColor, pickedPath, stats + pending) {
                                nav.popBackStack()
                            }
                        }
                        .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap2),
                ) {
                    Text("저장", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Pbp.colors.onSignature)
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding() // 키보드가 필드를 가리지 않도록
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PbpDimens.gap4),
            ) {
                // 사진
                Column(
                    Modifier.fillMaxWidth().padding(vertical = PbpDimens.gap3),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        if (pickedPath != null) {
                            Box(
                                Modifier
                                    .size(PbpDimens.avatarProfile)
                                    .border(3.dp, tokens.signature, CircleShape)
                                    .clip(CircleShape)
                            ) {
                                AsyncImage(
                                    model = File(pickedPath!!),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            }
                        } else {
                            Avatar(
                                emoji = existing?.emoji ?: "🙂",
                                imagePath = existing?.imagePath,
                                size = PbpDimens.avatarProfile,
                                ringColor = tokens.signature,
                            )
                        }
                    }
                    Spacer(Modifier.height(PbpDimens.gap2))
                    OutlinedButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("이미지 선택", fontSize = 11.sp) }
                    Text(
                        "프로필 이미지는 원형으로 잘려 표시됩니다",
                        fontSize = 10.sp,
                        color = tokens.inkDim,
                    )
                }

                FieldLabel("캐릭터 이름")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(PbpDimens.gap5))

                FieldLabel("이름 색")
                com.pbp.app.ui.common.ColorSwatchRow(
                    presets = PbpPalette.namePresets,
                    selected = nameColor,
                    slot = com.pbp.app.data.RecentColors.Slot.NAME,
                    onSelect = { nameColor = it },
                    onCustom = { customTarget = "name" },
                )
                Spacer(Modifier.height(PbpDimens.gap5))

                FieldLabel("말풍선 색")
                com.pbp.app.ui.common.ColorSwatchRow(
                    presets = PbpPalette.bubblePresets,
                    selected = bubbleColor,
                    slot = com.pbp.app.data.RecentColors.Slot.BUBBLE,
                    onSelect = { bubbleColor = it },
                    onCustom = { customTarget = "bubble" },
                )
                Spacer(Modifier.height(PbpDimens.gap5))

                FieldLabel("말풍선 글씨색")
                com.pbp.app.ui.common.ColorSwatchRow(
                    presets = PbpPalette.textPresets,
                    selected = textColor ?: PbpPalette.textPresets.first(),
                    slot = com.pbp.app.data.RecentColors.Slot.TEXT,
                    onSelect = { textColor = it },
                    onCustom = { customTarget = "text" },
                )
                Spacer(Modifier.height(PbpDimens.gap5))

                FieldLabel("캐릭터 값")
                // 값 목록은 패널 카드로 묶는다 (목업 mockup-profile-values)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PbpDimens.rCard))
                        .background(tokens.panel)
                        .border(1.dp, tokens.line, RoundedCornerShape(PbpDimens.rCard))
                        .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap2),
                ) {
                    stats.forEach { statEntry ->
                        val (statName, statValue) = statEntry
                        if (statEntry == editingStat) {
                            // 꾹 누른 항목은 입력할 때와 같은 두 칸짜리 폼으로 바뀐다
                            StatInputRow(
                                name = editName,
                                value = editValue,
                                onName = { editName = it },
                                onValue = { editValue = it },
                                confirmLabel = "확인",
                                onConfirm = {
                                    val newName = editName.trim()
                                    val index = stats.indexOf(statEntry)
                                    if (index >= 0) {
                                        val updated = newName to editValue.trim()
                                        stats[index] = updated
                                        // 이름을 다른 항목과 같게 바꿨다면 그쪽을 지운다 (중복 방지)
                                        stats.removeAll { it !== updated && it.first == newName }
                                    }
                                    editingStat = null
                                },
                                onCancel = { editingStat = null },
                            )
                        } else {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        editName = statName
                                        editValue = statValue
                                        editingStat = statEntry
                                    },
                                )
                                .padding(vertical = PbpDimens.gap3),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                statName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Pbp.colors.statBlue,
                                modifier = Modifier.weight(1f),
                            )
                            Text(statValue, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
                            Spacer(Modifier.width(PbpDimens.gap2))
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(tokens.panel2)
                                    // index 캡처는 연타 시 stale — 항목 자체로 제거 (P3-6)
                                    .clickable { stats.remove(statEntry) },
                                contentAlignment = Alignment.Center,
                            ) { Text("✕", fontSize = 11.sp, color = tokens.inkDim) }
                        }
                        }
                        HorizontalDivider(thickness = 1.dp, color = tokens.line)
                    }
                    StatInputRow(
                        name = newStatName,
                        value = newStatValue,
                        onName = { newStatName = it },
                        onValue = { newStatValue = it },
                        confirmLabel = "추가",
                        onConfirm = {
                            val statName = newStatName.trim()
                            // 같은 이름이 있으면 값을 교체 — 중복 항목 방지 (P1-8)
                            val existingIdx = stats.indexOfFirst { it.first == statName }
                            if (existingIdx >= 0) {
                                stats[existingIdx] = statName to newStatValue.trim()
                            } else {
                                stats.add(statName to newStatValue.trim())
                            }
                            newStatName = ""
                            newStatValue = ""
                        },
                    )
                }
                Spacer(Modifier.height(PbpDimens.gap2))
                Text(
                    "메시지에 {값이름}을 쓰면 값으로 바뀌고, 입력창에 값 이름을 입력하면 판정 매크로가 추천됩니다",
                    fontSize = 10.sp,
                    color = tokens.inkDim,
                )
                Spacer(Modifier.height(PbpDimens.gap5))

                // 실시간 미리보기 — 방 배경 위 조합 확인 (스펙 3장 화면3)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PbpDimens.rCard))
                        .background(Brush.linearGradient(listOf(Color(0xFF233248), Color(0xFF141D2B))))
                        .border(1.dp, Color.White.copy(alpha = .2f), RoundedCornerShape(PbpDimens.rCard))
                        .padding(PbpDimens.gap4),
                ) {
                    Text("미 리 보 기 — 방 배경 위", fontSize = 10.sp, letterSpacing = 3.sp, color = tokens.inkDim)
                    Spacer(Modifier.height(PbpDimens.gap3))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                name.ifEmpty { "이름" },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(nameColor ?: PbpPalette.namePresets.first()),
                            )
                            Spacer(Modifier.height(PbpDimens.gap1))
                            Box(
                                Modifier
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = PbpDimens.rCard,
                                            topEnd = PbpDimens.rTail,
                                            bottomEnd = PbpDimens.rCard,
                                            bottomStart = PbpDimens.rCard,
                                        )
                                    )
                                    .background(Color(bubbleColor ?: PbpPalette.bubblePresets.first()))
                                    .padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2),
                            ) {
                                Text("이 색으로 말하게 됩니다.", fontSize = 13.sp, color = tokens.bubbleInk)
                            }
                        }
                        Spacer(Modifier.width(PbpDimens.gap2))
                        if (pickedPath != null) {
                            Box(Modifier.size(PbpDimens.avatarChat).clip(CircleShape)) {
                                AsyncImage(
                                    model = File(pickedPath!!),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            }
                        } else {
                            Avatar(
                                emoji = existing?.emoji ?: "🙂",
                                imagePath = existing?.imagePath,
                                size = PbpDimens.avatarChat,
                            )
                        }
                    }
                }

                // 삭제 (GM 프로필은 방과 운명을 같이하므로 삭제 불가)
                if (existing != null && existing?.isGm != true) {
                    Spacer(Modifier.height(PbpDimens.gap4))
                    TextButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("캐릭터 삭제", color = tokens.danger, fontSize = 13.sp) }
                }
                Spacer(Modifier.height(PbpDimens.gap6))
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("캐릭터 삭제", fontSize = 15.sp, color = tokens.ink) },
            text = {
                Text(
                    "'${existing?.name}' 캐릭터를 삭제합니다. 되돌릴 수 없습니다.",
                    fontSize = 13.sp,
                    color = tokens.inkDim,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    existing?.let { target -> vm.delete(target) { nav.popBackStack() } }
                }) { Text("삭제", color = tokens.danger, fontSize = 13.sp) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("취소", color = tokens.inkDim, fontSize = 13.sp)
                }
            },
            containerColor = tokens.panel,
        )
    }

    // 갤러리에서 고른 이미지를 원형 프레임에서 이동·확대해 잘라낸다
    cropSource?.let { uri ->
        ImageCropDialog(
            uri = uri,
            onDismiss = { cropSource = null },
            onCropped = { path ->
                pickedPath = path
                cropSource = null
            },
        )
    }

    customTarget?.let { target ->
        HexColorDialog(
            title = when (target) {
                "name" -> "이름 색 (커스텀)"
                "text" -> "말풍선 글씨색 (커스텀)"
                else -> "말풍선 색 (커스텀)"
            },
            onDismiss = { customTarget = null },
            onPick = { color ->
                when (target) {
                    "name" -> nameColor = color
                    "text" -> textColor = color
                    else -> bubbleColor = color
                }
                // 커스텀 적용만 기록 — 프리셋은 이미 줄에 있어 중복 (목업 01장).
                // 자리별 목록이라 이름 색이 말풍선 색 줄에 섞이지 않는다
                com.pbp.app.data.RecentColors.add(
                    app,
                    when (target) {
                        "name" -> com.pbp.app.data.RecentColors.Slot.NAME
                        "text" -> com.pbp.app.data.RecentColors.Slot.TEXT
                        else -> com.pbp.app.data.RecentColors.Slot.BUBBLE
                    },
                    color,
                )
                customTarget = null
            },
            initial = when (target) {
                "name" -> nameColor
                "text" -> textColor
                else -> bubbleColor
            },
        )
    }
}

@Composable
internal fun FieldLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = Pbp.colors.inkDim,
        modifier = Modifier.padding(bottom = PbpDimens.gap2),
    )
}

/**
 * 값 입력 행 — 새로 추가할 때와 꾹 눌러 고칠 때가 같은 모양이어야 한다.
 * [onCancel]이 있으면 편집 모드로 보고 취소 버튼을 함께 보여 준다.
 */
@Composable
private fun StatInputRow(
    name: String,
    value: String,
    onName: (String) -> Unit,
    onValue: (String) -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val tokens = Pbp.colors
    Row(
        Modifier.padding(vertical = PbpDimens.gap2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            modifier = Modifier.weight(1.2f),
            label = { Text("값 이름 (예: 은신)", fontSize = 10.sp) },
            singleLine = true,
        )
        Spacer(Modifier.width(PbpDimens.gap2))
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.weight(0.8f),
            label = { Text("값 (예: 50)", fontSize = 10.sp) },
            singleLine = true,
        )
        Spacer(Modifier.width(PbpDimens.gap2))
        Column {
            TextButton(onClick = onConfirm, enabled = name.isNotBlank()) {
                Text(confirmLabel, fontSize = 11.sp, color = tokens.signatureInk)
            }
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text("취소", fontSize = 11.sp, color = tokens.inkDim)
                }
            }
        }
    }
}
