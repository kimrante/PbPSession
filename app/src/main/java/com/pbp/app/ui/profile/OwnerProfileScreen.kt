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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
 * 오너 프로필 설정 — 프로필 편집과 같은 전체 화면 규격 (목업 mockup-owner-profile 02장).
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(PbpDimens.appBarHeight)
                    .padding(horizontal = PbpDimens.gap2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { if (!locked) nav.popBackStack() }, enabled = !locked) {
                    Text(
                        "←", fontSize = 20.sp, color = tokens.ink,
                        modifier = Modifier.alpha(if (locked) 0.28f else 1f),
                    )
                }
                Text("오너 프로필", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
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
                        .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap2),
                ) {
                    Text("저장", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tokens.onSignature)
                }
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
                    Box(Modifier.border(3.dp, tokens.signature, CircleShape).padding(3.dp)) {
                        if (imagePath != null) {
                            Box(Modifier.size(PbpDimens.avatarProfile).clip(CircleShape)) {
                                AsyncImage(
                                    model = File(imagePath!!),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            }
                        } else {
                            OwnerAvatar(name, color, null, PbpDimens.avatarProfile)
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
                                fontSize = 13.sp, // 실제 말풍선 본문과 같은 단 (프로필 편집과 통일)
                                color = textColor?.let { Color(it) } ?: tokens.chatterInk,
                            )
                        }
                    }
                    Spacer(Modifier.width(PbpDimens.gap2))
                    OwnerAvatar(name, color, imagePath, PbpDimens.avatarChat)
                }
                Spacer(Modifier.height(PbpDimens.gap6)) // 화면 하단 여유 — 대칭 원칙상 Spacer로
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
