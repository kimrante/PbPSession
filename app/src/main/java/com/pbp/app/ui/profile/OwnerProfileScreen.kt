package com.pbp.app.ui.profile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.pbp.app.data.ImageSizes
import com.pbp.app.data.Images
import com.pbp.app.data.OwnerProfile
import com.pbp.app.data.RecentColors
import com.pbp.app.ui.common.ColorSwatchRow
import com.pbp.app.ui.common.HexColorDialog
import com.pbp.app.ui.common.OwnerAvatar
import com.pbp.app.ui.common.dashedBorder
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import com.pbp.app.ui.theme.PbpPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 오너 프로필 설정 — 프로필 편집과 같은 전체 화면 규격 (목업 final-design.html 02장).
 *
 * 상단 바 56dp(좌 ← / 우 저장 캡슐) · 본문 좌우 16dp · 사진 원형 + 골드 링을 공유하되,
 * 오너는 판정 주체가 아니므로 **캐릭터 값 섹션이 없고 컬러도 1개**다
 * (잡담 이름 색과 아바타 배경에 함께 쓰인다).
 *
 * 이름이 비어 있으면(최초 실행) 뒤로 가기와 저장이 모두 막힌다 — 기존 다이얼로그의
 * `forced` 규칙을 그대로 옮긴 것.
 */
@Composable
fun OwnerProfileScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokens = Pbp.colors

    var name by rememberSaveable { mutableStateOf(OwnerProfile.name) }
    var color by rememberSaveable { mutableStateOf(OwnerProfile.color) }
    var imagePath by rememberSaveable { mutableStateOf(OwnerProfile.imagePath) }
    var textColor by rememberSaveable { mutableStateOf(OwnerProfile.textColor) }
    /** null=닫힘, "owner"=오너 컬러, "text"=말풍선 글씨색 */
    var customTarget by rememberSaveable { mutableStateOf<String?>(null) }

    // 저장하지 않은 채 프로세스가 죽으면 그 파일은 다음 시작 때 정리(ImageGc) 대상이라
    // 사라진다 — 경로만 복원되면 빈 아바타가 저장된다. 없으면 비운다 (V4)
    LaunchedEffect(Unit) {
        imagePath?.let { if (!java.io.File(it).exists()) imagePath = null }
    }

    val canSave = name.isNotBlank()
    // 미설정 상태에서는 나갈 수 없다 (목업 02-B)
    val locked = !OwnerProfile.isSet
    BackHandler(enabled = locked) { /* 이름을 정할 때까지 뒤로 가기 차단 */ }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                Images.importDownscaled(context, uri, "owner", maxSize = ImageSizes.PROFILE)
                    ?.let { imagePath = it }
            }
        }
    }

    Scaffold(containerColor = tokens.bg) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 상단 바 — 프로필 편집과 같은 규격
            Box(Modifier.fillMaxWidth().height(PbpDimens.appBarHeight)) {
                Row(
                Modifier.fillMaxSize().padding(horizontal = PbpDimens.gap2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { if (!locked) nav.popBackStack() }, enabled = !locked) {
                    Text(
                        "←", fontSize = 20.sp, color = tokens.ink,
                        modifier = Modifier.alpha(if (locked) 0.28f else 1f),
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (canSave) tokens.signature else tokens.signature.copy(alpha = .4f)
                        )
                        .clickable(enabled = canSave) {
                            OwnerProfile.set(context, name, color, imagePath, textColor)
                            nav.popBackStack()
                        }
                        .heightIn(min = PbpDimens.touchTarget)
                        .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap2),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("저장", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tokens.onSignature)
                }
                }
                // 타이틀은 좌우 인셋을 같게 준 오버레이 — 버튼 개수와 무관하게 정중앙 (P1)
                Text(
                    "오너 프로필",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = tokens.ink,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth()
                        .padding(horizontal = PbpDimens.titleInsetWide),
                )
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PbpDimens.gap4),
            ) {
                // 사진 — 이미지가 없으면 컬러 원 + 이름 첫 글자
                Column(
                    Modifier.fillMaxWidth().padding(vertical = PbpDimens.gap3),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 링을 이미지에 직접 — 캐릭터 편집 화면과 같은 규격 (파일 주석대로)
                    Box {
                        if (imagePath != null) {
                            Box(
                                Modifier
                                    .size(PbpDimens.avatarProfile)
                                    .border(3.dp, tokens.signature, CircleShape)
                                    .clip(CircleShape)
                            ) {
                                AsyncImage(
                                    model = File(imagePath!!),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            }
                        } else {
                            OwnerAvatar(
                                name, color, null, PbpDimens.avatarProfile,
                                ringColor = tokens.signature,
                            )
                        }
                    }
                    Spacer(Modifier.height(PbpDimens.gap2))
                    Row {
                        OutlinedButton(onClick = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }) {
                            Text(if (imagePath == null) "이미지 선택" else "이미지 변경", fontSize = 11.sp)
                        }
                        if (imagePath != null) {
                            Spacer(Modifier.width(PbpDimens.gap2))
                            OutlinedButton(onClick = { imagePath = null }) {
                                Text("제거", fontSize = 11.sp)
                            }
                        }
                    }
                    Text(
                        "잡담과 참여 인사에 쓰이는 플레이어 본인 프로필입니다 · " +
                            "세션 캐릭터 목록에는 나타나지 않습니다",
                        fontSize = 10.sp,
                        color = tokens.inkDim,
                    )
                }

                // 최초 실행 안내 밴드 (목업 02-B)
                if (locked) {
                    Surface(
                        color = tokens.signature.copy(alpha = .18f),
                        shape = RoundedCornerShape(PbpDimens.rCell),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "이름을 정해야 시작할 수 있습니다",
                            fontSize = 11.sp,
                            color = tokens.signatureInk,
                            modifier = Modifier.padding(PbpDimens.gap3),
                        )
                    }
                    Spacer(Modifier.height(PbpDimens.gap4))
                }

                FieldLabel("이름")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(PbpDimens.gap5))

                // 오너는 컬러 1개 — 잡담 이름 색과 아바타 배경에 함께 쓰인다
                FieldLabel("컬러")
                ColorSwatchRow(
                    presets = PbpPalette.bubblePresets,
                    selected = color,
                    slot = RecentColors.Slot.OWNER,
                    onSelect = { color = it },
                    onCustom = { customTarget = "owner" },
                )
                Spacer(Modifier.height(PbpDimens.gap5))

                FieldLabel("말풍선 글씨색")
                ColorSwatchRow(
                    presets = PbpPalette.textPresets,
                    selected = textColor ?: PbpPalette.textPresets.first(),
                    slot = RecentColors.Slot.TEXT,
                    onSelect = { textColor = it },
                    onCustom = { customTarget = "text" },
                )
                Spacer(Modifier.height(PbpDimens.gap5))

                // 미리보기 — 오너는 잡담으로만 발화하므로 점선 잡담 말풍선
                FieldLabel("미리보기 — 잡담 말풍선")
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            name.ifBlank { "이름" },
                            fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = Color(PbpPalette.nameColorForLight(color)),
                        )
                        Spacer(Modifier.height(PbpDimens.gap1))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(PbpDimens.rCard))
                                .background(tokens.chatterBubble)
                                .dashedBorder(tokens.inkDim.copy(alpha = .4f), PbpDimens.rCard)
                                .padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2),
                        ) {
                            Text(
                                "〔잡담〕 이 색으로 잡담하게 됩니다.",
                                fontSize = 11.sp,
                                color = textColor?.let { Color(it) } ?: tokens.chatterInk,
                            )
                        }
                    }
                    Spacer(Modifier.width(PbpDimens.gap2))
                    OwnerAvatar(name, color, imagePath, PbpDimens.avatarChat)
                }

                // 이름을 정하기 전에는 계정 이야기를 꺼내지 않는다 (목업 02-B의 잠금 상태)
                if (!locked) {
                    Spacer(Modifier.height(PbpDimens.gap5))
                    FieldLabel("구글 계정")
                    GoogleAccountRow()
                }
                // 화면 하단 여유 — 캐릭터 편집 화면과 같은 방식 (Spacer로 통일)
                Spacer(Modifier.height(PbpDimens.gap6))
            }
        }
    }

    customTarget?.let { target ->
        val isText = target == "text"
        HexColorDialog(
            title = if (isText) "말풍선 글씨색 (커스텀)" else "오너 컬러 (커스텀)",
            onDismiss = { customTarget = null },
            onPick = { picked ->
                if (isText) textColor = picked else color = picked
                RecentColors.add(
                    context,
                    if (isText) RecentColors.Slot.TEXT else RecentColors.Slot.OWNER,
                    picked,
                )
                customTarget = null
            },
            initial = if (isText) textColor else color,
        )
    }
}

/**
 * 구글 계정 연결 — 폰과 PC가 같은 신원을 쓰기 위한 첫 단계.
 *
 * 지금 신원은 기기마다 따로 만들어진 익명 계정이라, 같은 사람이 써도 서로 다른
 * 사용자다. 여기서 구글 계정을 **덧붙이면** 그 UID가 그대로 유지되면서 계정에
 * 이름이 생긴다 — 참여 중인 방과 지난 대화는 하나도 건드리지 않는다.
 */
@Composable
private fun GoogleAccountRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokens = Pbp.colors
    val sync = remember(context) {
        (context.applicationContext as com.pbp.app.PbpApp).syncManager
    }
    var email by remember { mutableStateOf(sync.linkedGoogleEmail) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    // 저장된 로그인은 복원에 잠깐 걸릴 수 있다 — 처음 읽어 비어 있으면 한 번 더 본다.
    // 이 확인이 없으면 연결해 둔 계정이 "연결 안 됨"으로 보여 풀린 것처럼 읽힌다
    LaunchedEffect(Unit) {
        if (email == null) {
            repeat(6) {
                kotlinx.coroutines.delay(500)
                sync.linkedGoogleEmail?.let { email = it; return@LaunchedEffect }
            }
        }
    }

    Surface(
        color = tokens.panel2,
        shape = RoundedCornerShape(PbpDimens.rCell),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(PbpDimens.gap3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    email ?: "연결 안 됨",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.ink,
                )
                Spacer(Modifier.height(PbpDimens.gap1))
                Text(
                    note ?: if (email != null) {
                        "이 계정으로 다른 기기와 이어집니다"
                    } else {
                        "연결해 두면 이후 PC에서도 같은 계정으로 이어갈 수 있습니다"
                    },
                    fontSize = 10.sp,
                    color = tokens.inkDim,
                )
            }
            if (email == null) {
                Spacer(Modifier.width(PbpDimens.gap2))
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        val activity = context as? android.app.Activity ?: return@OutlinedButton
                        busy = true
                        note = null
                        scope.launch {
                            when (val result = sync.linkGoogleAccount(activity)) {
                                is com.pbp.app.sync.GoogleAccountLinker.Result.Linked -> {
                                    email = result.email ?: sync.linkedGoogleEmail
                                    // 다른 기기에서 하던 세션이 있으면 지금 가져온다
                                    // (연결 직후에 보이지 않으면 됐는지 알 수가 없다)
                                    val adopted = sync.adoptAccountRooms()
                                    if (adopted > 0) note = "다른 기기의 세션 ${adopted}개를 가져왔습니다"
                                }
                                is com.pbp.app.sync.GoogleAccountLinker.Result.Recovered -> {
                                    // 덧붙이지는 못했지만 원래 계정을 되찾았다 — 결과는 같다
                                    email = result.email ?: sync.linkedGoogleEmail
                                    note = "이 계정으로 쓰던 신원을 되찾았습니다"
                                    val adopted = sync.adoptAccountRooms()
                                    if (adopted > 0) note = "다른 기기의 세션 ${adopted}개를 가져왔습니다"
                                }
                                com.pbp.app.sync.GoogleAccountLinker.Result.NoAccount ->
                                    note = "기기에 구글 계정이 없습니다"
                                // 아무 표시도 없으면 "눌렀는데 그냥 풀렸다"로 읽힌다
                                com.pbp.app.sync.GoogleAccountLinker.Result.Cancelled ->
                                    note = "계정 선택이 취소되었습니다"
                                is com.pbp.app.sync.GoogleAccountLinker.Result.Failed ->
                                    note = result.message + signingHint(context, result.message)
                            }
                            busy = false
                        }
                    },
                ) {
                    Text(if (busy) "연결 중…" else "연결", fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * "Developer console is not set up correctly" — 이 빌드의 서명 지문이 Firebase
 * 프로젝트에 등록돼 있지 않을 때 나온다. 어느 값을 등록해야 하는지 화면에서
 * 바로 읽을 수 있어야 한다 (콘솔과 대조할 값을 사람이 따로 뽑기 어렵다).
 */
private fun signingHint(context: android.content.Context, message: String): String {
    if (!message.contains("Developer console", ignoreCase = true)) return ""
    val sha1 = runCatching {
        val flag = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val info = context.packageManager.getPackageInfo(context.packageName, flag)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return ""
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(cert.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }.getOrNull() ?: return ""
    return "\n\n이 빌드의 SHA-1이 Firebase 프로젝트에 등록돼 있어야 합니다:\n$sha1"
}
