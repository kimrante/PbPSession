package com.pbp.app.ui.chat

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import com.pbp.app.PbpApp
import com.pbp.app.data.CaptureSettings
import com.pbp.app.export.CaptureSaver
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import kotlinx.coroutines.launch

/**
 * 캡처 미리보기 — 만들어진 이미지를 보고 저장·공유한다.
 *
 * 비트맵은 같은 방의 [ChatViewModel]이 들고 있다(회전해도 다시 그리지 않는다).
 */
@Composable
fun CapturePreviewScreen(nav: NavController, roomId: Long) {
    val context = LocalContext.current
    val app = context.applicationContext as PbpApp
    // 채팅 화면과 **같은 키**여야 그 화면이 만든 비트맵을 볼 수 있다
    val vm: ChatViewModel = viewModel(key = "chat-$roomId", factory = viewModelFactory {
        initializer { ChatViewModel(app, roomId) }
    })
    val tokens = Pbp.colors
    val room by vm.room.collectAsState()
    val bitmaps = vm.captureResult
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    // 공유용 캐시는 화면을 벗어날 때 비운다
    DisposableEffect(Unit) { onDispose { CaptureSaver.clearShareCache(context) } }

    val doSave = {
        if (!busy && bitmaps.isNotEmpty()) {
            busy = true
            scope.launch {
                val name = room?.name ?: "PbP"
                val ok = bitmaps.mapIndexed { index, bitmap ->
                    CaptureSaver.saveToGallery(
                        context,
                        bitmap,
                        CaptureSaver.fileName(name, index, bitmaps.size),
                    ) != null
                }.all { it }
                busy = false
                Toast.makeText(
                    context,
                    if (ok) "갤러리에 저장했습니다" else "저장에 실패했습니다",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    // API 28 이하에서만, 저장을 누른 그 순간에만 묻는다
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) doSave()
        else Toast.makeText(
            context,
            "저장 권한이 없어 갤러리에 넣을 수 없습니다. 공유로 보내 보세요.",
            Toast.LENGTH_LONG,
        ).show()
    }

    Scaffold(containerColor = tokens.bg) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── 상단 바
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(PbpDimens.appBarHeight)
                    .background(tokens.panel)
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = PbpDimens.gap2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Text("←", fontSize = 20.sp, color = tokens.ink)
                    }
                }
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .height(PbpDimens.appBarHeight)
                        .padding(horizontal = PbpDimens.gap6),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "캡처 미리보기",
                        fontSize = 15.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.ink,
                    )
                    Spacer(Modifier.height(PbpDimens.gap1))
                    Text(
                        bitmaps.firstOrNull()?.let { first ->
                            if (bitmaps.size > 1) "${bitmaps.size}장 · ${first.width}px 폭"
                            else "${first.width} × ${first.height}"
                        } ?: "이미지가 없습니다",
                        fontSize = 11.sp,
                        lineHeight = 11.sp,
                        color = tokens.inkSub,
                    )
                }
            }

            // ── 결과 이미지 — 화면 폭에 맞춰 축소, 위쪽 정렬
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(PbpDimens.gap4),
                verticalArrangement = Arrangement.spacedBy(PbpDimens.gap3),
            ) {
                bitmaps.forEach { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(PbpDimens.rCell)),
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }

            // ── 하단 바: 배경 토글 + 공유 + 저장
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.panel)
                    .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(enabled = !busy) {
                            CaptureSettings.set(context, !CaptureSettings.withBackground)
                            busy = true
                            vm.rerenderCapture(context) { ok ->
                                busy = false
                                if (!ok) {
                                    Toast.makeText(context, "다시 그리지 못했습니다", Toast.LENGTH_SHORT)
                                        .show()
                                }
                            }
                        }
                        .padding(vertical = PbpDimens.gap1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val on = CaptureSettings.withBackground
                    Box(
                        Modifier
                            .size(width = 34.dp, height = 20.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (on) tokens.signature else tokens.ink.copy(alpha = .16f)),
                        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
                    ) {
                        Box(
                            Modifier
                                .padding(2.dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(tokens.panel)
                        )
                    }
                    Spacer(Modifier.width(PbpDimens.gap2))
                    Text(
                        "배경 포함",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.ink,
                    )
                }
                Text(
                    "공유",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    color = tokens.signatureInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(enabled = !busy && bitmaps.isNotEmpty()) {
                            scope.launch {
                                val intent = CaptureSaver.shareIntent(
                                    context, bitmaps, room?.name ?: "PbP",
                                )
                                if (intent == null) {
                                    Toast.makeText(context, "공유할 이미지가 없습니다", Toast.LENGTH_SHORT)
                                        .show()
                                } else {
                                    runCatching { context.startActivity(intent) }.onFailure {
                                        Toast.makeText(context, "공유할 앱이 없습니다", Toast.LENGTH_SHORT)
                                            .show()
                                    }
                                }
                            }
                        }
                        .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap2),
                )
                Spacer(Modifier.width(PbpDimens.gap2))
                Text(
                    if (busy) "처리 중…" else "저장",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    color = tokens.onSignature,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(tokens.signature)
                        .clickable(enabled = !busy && bitmaps.isNotEmpty()) {
                            if (CaptureSaver.needsPermission()) {
                                permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                doSave()
                            }
                        }
                        .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap2),
                )
            }
        }
    }
}
