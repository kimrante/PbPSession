package com.pbp.app.ui.profile

import android.net.Uri
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
        newImagePath: String?, // 크롭 다이얼로그가 만든 512px JPEG 경로
        stats: List<Pair<String, String>>,
        onDone: () -> Unit,
    ) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val profile = (existing ?: CharacterProfile(name = "")).copy(
                name = name.trim().ifEmpty { "이름 없음" },
                nameColor = nameColor,
                bubbleColor = bubbleColor,
                imagePath = newImagePath ?: existing?.imagePath,
                stats = ProfileStats.encode(stats),
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
    var cropSource by remember { mutableStateOf<Uri?>(null) } // 크롭 대기 중인 갤러리 이미지
    var pickedPath by rememberSaveable {
        mutableStateOf<String?>(null) // 크롭 완료된 512px 파일
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

    LaunchedEffect(profileId) {
        existing = vm.load(profileId)
        // DB 로드는 최초 1회만 — 회전 후 재실행 시 편집 중이던 폼을 덮어쓰지 않는다 (P2-4)
        if (!loaded) {
            existing?.let {
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
                    .padding(horizontal = PbpDimens.sp2),
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
                            vm.save(existing, name, nameColor, bubbleColor, pickedPath, stats + pending) {
                                nav.popBackStack()
                            }
                        }
                        .padding(horizontal = PbpDimens.sp4, vertical = PbpDimens.sp2),
                ) {
                    Text("저장", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding() // 키보드가 필드를 가리지 않도록
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PbpDimens.sp4),
            ) {
                // 사진
                Column(
                    Modifier.fillMaxWidth().padding(vertical = PbpDimens.sp3),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        if (pickedPath != null) {
                            Box(
                                Modifier
                                    .size(92.dp)
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
                                size = 92.dp,
                                ringColor = tokens.signature,
                            )
                        }
                    }
                    Spacer(Modifier.height(PbpDimens.sp2))
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
                Spacer(Modifier.height(PbpDimens.sp5))

                FieldLabel("이름 색")
                SwatchRow(
                    presets = PbpPalette.namePresets,
                    selected = nameColor,
                    onSelect = { nameColor = it },
                    onCustom = { customTarget = "name" },
                )
                Spacer(Modifier.height(PbpDimens.sp5))

                FieldLabel("말풍선 색")
                SwatchRow(
                    presets = PbpPalette.bubblePresets,
                    selected = bubbleColor,
                    onSelect = { bubbleColor = it },
                    onCustom = { customTarget = "bubble" },
                )
                Spacer(Modifier.height(PbpDimens.sp5))

                FieldLabel("캐릭터 값")
                // 값 목록은 패널 카드로 묶는다 (목업 mockup-profile-values)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PbpDimens.rCard))
                        .background(tokens.panel)
                        .border(1.dp, tokens.line, RoundedCornerShape(PbpDimens.rCard))
                        .padding(horizontal = PbpDimens.sp4, vertical = PbpDimens.sp2),
                ) {
                    stats.forEach { statEntry ->
                        val (statName, statValue) = statEntry
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = PbpDimens.sp3),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                statName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF3B82F6),
                                modifier = Modifier.weight(1f),
                            )
                            Text(statValue, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
                            Spacer(Modifier.width(PbpDimens.sp2))
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
                        HorizontalDivider(thickness = 1.dp, color = tokens.line)
                    }
                    Row(
                        Modifier.padding(vertical = PbpDimens.sp2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = newStatName,
                            onValueChange = { newStatName = it },
                            modifier = Modifier.weight(1.2f),
                            label = { Text("값 이름 (예: 은신)", fontSize = 10.sp) },
                            singleLine = true,
                        )
                        Spacer(Modifier.width(PbpDimens.sp2))
                        OutlinedTextField(
                            value = newStatValue,
                            onValueChange = { newStatValue = it },
                            modifier = Modifier.weight(0.8f),
                            label = { Text("값 (예: 50)", fontSize = 10.sp) },
                            singleLine = true,
                        )
                        Spacer(Modifier.width(PbpDimens.sp2))
                        TextButton(
                            onClick = {
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
                            enabled = newStatName.isNotBlank(),
                        ) { Text("추가", fontSize = 11.sp, color = tokens.signatureInk) }
                    }
                }
                Text(
                    "메시지에 {값이름}을 쓰면 값으로 바뀌고, 입력창에 값 이름을 입력하면 판정 매크로가 추천됩니다",
                    fontSize = 10.sp,
                    color = tokens.inkDim,
                    modifier = Modifier.padding(top = PbpDimens.sp2),
                )
                Spacer(Modifier.height(PbpDimens.sp5))

                // 실시간 미리보기 — 방 배경 위 조합 확인 (스펙 3장 화면3)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PbpDimens.rCard))
                        .background(Brush.linearGradient(listOf(Color(0xFF233248), Color(0xFF141D2B))))
                        .border(1.dp, Color.White.copy(alpha = .2f), RoundedCornerShape(PbpDimens.rCard))
                        .padding(PbpDimens.sp4),
                ) {
                    Text("미 리 보 기 — 방 배경 위", fontSize = 9.sp, letterSpacing = 3.sp, color = tokens.inkDim)
                    Spacer(Modifier.height(PbpDimens.sp3))
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
                            Spacer(Modifier.height(PbpDimens.sp1))
                            Box(
                                Modifier
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = PbpDimens.rCard,
                                            topEnd = 4.dp,
                                            bottomEnd = PbpDimens.rCard,
                                            bottomStart = PbpDimens.rCard,
                                        )
                                    )
                                    .background(Color(bubbleColor ?: PbpPalette.bubblePresets.first()))
                                    .padding(horizontal = PbpDimens.sp3, vertical = PbpDimens.sp2),
                            ) {
                                Text("이 색으로 말하게 됩니다.", fontSize = 13.sp, color = Color(0xFF10151C))
                            }
                        }
                        Spacer(Modifier.width(PbpDimens.sp2))
                        if (pickedPath != null) {
                            Box(Modifier.size(34.dp).clip(CircleShape)) {
                                AsyncImage(
                                    model = File(pickedPath!!),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            }
                        } else {
                            Avatar(emoji = existing?.emoji ?: "🙂", imagePath = existing?.imagePath, size = 34.dp)
                        }
                    }
                }

                // 삭제 (GM 프로필은 방과 운명을 같이하므로 삭제 불가)
                if (existing != null && existing?.isGm != true) {
                    Spacer(Modifier.height(PbpDimens.sp4))
                    TextButton(
                        onClick = { vm.delete(existing!!) { nav.popBackStack() } },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("캐릭터 삭제", color = tokens.signatureInk, fontSize = 13.sp) }
                }
                Spacer(Modifier.height(PbpDimens.sp6))
            }
        }
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
            title = if (target == "name") "이름 색 (커스텀)" else "말풍선 색 (커스텀)",
            onDismiss = { customTarget = null },
            onPick = { color ->
                if (target == "name") nameColor = color else bubbleColor = color
                customTarget = null
            },
            initial = if (target == "name") nameColor else bubbleColor,
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = Pbp.colors.inkDim,
        modifier = Modifier.padding(bottom = PbpDimens.sp2),
    )
}

@Composable
private fun SwatchRow(
    presets: List<Long>,
    selected: Long?,
    onSelect: (Long) -> Unit,
    onCustom: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(PbpDimens.sp2)) {
        presets.forEach { color ->
            val sel = selected == color
            Box(
                Modifier
                    .size(32.dp)
                    .then(
                        if (sel) Modifier.border(2.dp, Color.White, CircleShape)
                        else Modifier
                    )
                    .clip(CircleShape)
                    .background(Color(color))
                    .clickable { onSelect(color) },
                contentAlignment = Alignment.Center,
            ) {
                if (sel) Text("✓", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color(0xFF10151C))
            }
        }
        // 커스텀 컬러
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    Brush.sweepGradient(
                        listOf(
                            Color(0xFFFF6666), Color(0xFFFFCC66), Color(0xFF66DD66),
                            Color(0xFF66CCFF), Color(0xFFCC66FF), Color(0xFFFF6666),
                        )
                    )
                )
                .clickable(onClick = onCustom),
            contentAlignment = Alignment.Center,
        ) { Text("＋", color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp) }
    }
}
