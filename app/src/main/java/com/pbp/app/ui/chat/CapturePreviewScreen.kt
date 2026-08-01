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
import androidx.navigation.NavController
import com.pbp.app.data.CaptureSettings
import com.pbp.app.export.CaptureHolder
import com.pbp.app.export.CaptureRenderer
import com.pbp.app.export.CaptureSaver
import com.pbp.app.export.findActivity
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import kotlinx.coroutines.launch

/**
 * 캡처 미리보기 — 만들어진 이미지를 보고 저장·공유한다.
 *
 * 비트맵은 같은 방의 [ChatViewModel]이 들고 있다(회전해도 다시 그리지 않는다).
 */
@Composable
fun CapturePreviewScreen(nav: NavController) {
    val context = LocalContext.current
    val tokens = Pbp.colors
    // 결과는 CaptureHolder에 있다 — viewModel()은 화면마다 저장소가 달라
    // 채팅 화면의 VM을 여기서 볼 수 없다 (v0.7.0에서 미리보기가 비어 있던 원인)
    val bitmaps = CaptureHolder.pages
    val roomName = CaptureHolder.request?.roomName ?: "PbP"
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    // 공유용 캐시는 화면을 벗어날 때 비운다
    DisposableEffect(Unit) { onDispose { CaptureSaver.clearShareCache(context) } }
    // 뒤로 나가면(회전이 아니라 실제 이탈) 페이지를 놓는다 — 수백 MB가 남아 있을 수 있다 (R7)
    val backStackEntry = nav.currentBackStackEntry
    DisposableEffect(backStackEntry) {
        onDispose { if (nav.currentBackStackEntry != backStackEntry) CaptureHolder.clear() }
    }

    // 배경·잡담 설정은 이미지에 구워져 있어 다시 그리는 것 말고는 방법이 없다
    val rerender = {
        val request = CaptureHolder.request
        val activity = context.findActivity()
        if (request != null && activity != null) {
            busy = true
            // 회전으로 화면이 재생성돼도 재렌더가 끊기면 안 된다 — 설정만 바뀌고
            // 이미지는 옛 상태로 남는다 (R7)
            CaptureHolder.scope.launch {
                val result = runCatching {
                    CaptureRenderer.render(
                        activity = activity,
                        roomName = request.roomName,
                        backgroundKey = request.backgroundKey,
                        messages = request.messages,
                        withBackground = CaptureSettings.withBackground,
                        excludeOoc = CaptureSettings.excludeOoc,
                    )
                }
                busy = false
                val pages = result.getOrDefault(emptyList())
                when {
                    // 고른 범위가 전부 잡담이면 남는 게 없다 — 설정을 되돌려 준다
                    pages.isEmpty() && CaptureSettings.excludeOoc &&
                        request.messages.all { it.isOoc } -> {
                        CaptureSettings.setExcludeOoc(context, false)
                        Toast.makeText(
                            context,
                            "고른 범위가 전부 잡담이라 뺄 수 없습니다",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    pages.isEmpty() -> Toast.makeText(
                        context,
                        "다시 그리지 못했습니다 — ${result.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                    else -> CaptureHolder.set(request, pages)
                }
            }
        }
        Unit
    }

    val doSave = {
        if (!busy && bitmaps.isNotEmpty()) {
            busy = true
            scope.launch {
                val ok = bitmaps.mapIndexed { index, bitmap ->
                    CaptureSaver.saveToGallery(
                        context,
                        bitmap,
                        CaptureSaver.fileName(roomName, index, bitmaps.size),
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
                Column(Modifier.weight(1f)) {
                    CaptureToggle(
                        label = "배경 포함",
                        checked = CaptureSettings.withBackground,
                        enabled = !busy,
                    ) {
                        CaptureSettings.setBackground(context, !CaptureSettings.withBackground)
                        rerender()
                    }
                    Spacer(Modifier.height(PbpDimens.gap1))
                    CaptureToggle(
                        label = "잡담 제외",
                        checked = CaptureSettings.excludeOoc,
                        enabled = !busy,
                    ) {
                        CaptureSettings.setExcludeOoc(context, !CaptureSettings.excludeOoc)
                        rerender()
                    }
                }
                Text(
                    "공유",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    color = tokens.signatureInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(enabled = !busy && bitmaps.isNotEmpty()) {
                            // 공유 중에는 토글을 잠근다 — 압축하는 사이 재렌더가 끼면
                            // 쓰던 비트맵이 recycle돼 빈 이미지가 공유된다 (R4)
                            busy = true
                            scope.launch {
                                val intent = CaptureSaver.shareIntent(context, bitmaps, roomName)
                                busy = false
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

/** 캡처 설정 토글 — 두 줄이 같은 모양이어야 해서 부품으로 뺐다 */
@Composable
private fun CaptureToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val tokens = Pbp.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = PbpDimens.gap1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 34.dp, height = 20.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (checked) tokens.signature else tokens.ink.copy(alpha = .16f)),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
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
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
    }
}
