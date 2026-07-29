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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.pbp.app.ui.common.Avatar
import com.pbp.app.ui.common.HexColorDialog
import com.pbp.app.ui.common.ImageCropDialog
import com.pbp.app.ui.theme.Pbp
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
        onDone: () -> Unit,
    ) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val profile = (existing ?: CharacterProfile(name = "")).copy(
                name = name.trim().ifEmpty { "이름 없음" },
                nameColor = nameColor,
                bubbleColor = bubbleColor,
                imagePath = newImagePath ?: existing?.imagePath,
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

    var loaded by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<CharacterProfile?>(null) }
    var name by remember { mutableStateOf("") }
    var nameColor by remember { mutableStateOf<Long?>(PbpPalette.namePresets.first()) }
    var bubbleColor by remember { mutableStateOf<Long?>(PbpPalette.bubblePresets.first()) }
    var cropSource by remember { mutableStateOf<Uri?>(null) } // 크롭 대기 중인 갤러리 이미지
    var pickedPath by remember { mutableStateOf<String?>(null) } // 크롭 완료된 512px 파일
    var customTarget by remember { mutableStateOf<String?>(null) } // "name" | "bubble"

    LaunchedEffect(profileId) {
        existing = vm.load(profileId)
        existing?.let {
            name = it.name
            nameColor = it.nameColor ?: PbpPalette.namePresets.first()
            bubbleColor = it.bubbleColor ?: PbpPalette.bubblePresets.first()
        }
        loaded = true
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        if (it != null) cropSource = it // 선택 후 크롭 다이얼로그로
    }

    if (!loaded) return

    Scaffold(containerColor = tokens.bg) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 상단 바
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Text("←", fontSize = 20.sp, color = tokens.ink)
                }
                Text("프로필 편집", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(tokens.signature)
                        .clickable {
                            vm.save(existing, name, nameColor, bubbleColor, pickedPath) {
                                nav.popBackStack()
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text("저장", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding() // 키보드가 필드를 가리지 않도록
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                // 사진
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("이미지 선택", fontSize = 12.sp) }
                    Text(
                        "프로필 이미지는 원형으로 잘려 표시됩니다",
                        fontSize = 10.5.sp,
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
                Spacer(Modifier.height(18.dp))

                FieldLabel("이름 색")
                SwatchRow(
                    presets = PbpPalette.namePresets,
                    selected = nameColor,
                    onSelect = { nameColor = it },
                    onCustom = { customTarget = "name" },
                )
                Spacer(Modifier.height(18.dp))

                FieldLabel("말풍선 색")
                SwatchRow(
                    presets = PbpPalette.bubblePresets,
                    selected = bubbleColor,
                    onSelect = { bubbleColor = it },
                    onCustom = { customTarget = "bubble" },
                )
                Spacer(Modifier.height(20.dp))

                // 실시간 미리보기 — 방 배경 위 조합 확인 (스펙 3장 화면3)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF233248), Color(0xFF141D2B))))
                        .border(1.dp, Color.White.copy(alpha = .2f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                ) {
                    Text("미 리 보 기 — 방 배경 위", fontSize = 9.sp, letterSpacing = 3.sp, color = tokens.inkDim)
                    Spacer(Modifier.height(10.dp))
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
                            Spacer(Modifier.height(3.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(topStart = 15.dp, topEnd = 4.dp, bottomEnd = 15.dp, bottomStart = 15.dp))
                                    .background(Color(bubbleColor ?: PbpPalette.bubblePresets.first()))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text("이 색으로 말하게 됩니다.", fontSize = 12.5.sp, color = Color(0xFF10151C))
                            }
                        }
                        Spacer(Modifier.width(9.dp))
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
                    Spacer(Modifier.height(14.dp))
                    TextButton(
                        onClick = { vm.delete(existing!!) { nav.popBackStack() } },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("캐릭터 삭제", color = tokens.signature, fontSize = 13.sp) }
                }
                Spacer(Modifier.height(30.dp))
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
        modifier = Modifier.padding(bottom = 7.dp),
    )
}

@Composable
private fun SwatchRow(
    presets: List<Long>,
    selected: Long?,
    onSelect: (Long) -> Unit,
    onCustom: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { color ->
            val sel = selected == color
            Box(
                Modifier
                    .size(30.dp)
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
                .size(30.dp)
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
