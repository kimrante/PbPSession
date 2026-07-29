package com.pbp.app.ui.common

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 프로필 이미지 크롭 다이얼로그 (원형 프레임).
 * 드래그로 이동, 핀치로 확대/축소해 원하는 부분을 잘라낸다. 출력은 512px JPEG.
 */
@Composable
fun ImageCropDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onCropped: (String) -> Unit,
) {
    val context = LocalContext.current
    val tokens = Pbp.colors
    val density = LocalDensity.current
    val cropSizeDp = 260.dp
    val cropPx = with(density) { cropSizeDp.toPx() }

    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { loadBitmap(context, uri, maxSize = 2048) }
    }

    val source = bitmap
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이미지 조정") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (source == null) {
                    Box(Modifier.size(cropSizeDp), contentAlignment = Alignment.Center) {
                        Text("이미지를 불러오는 중…", fontSize = 12.sp, color = tokens.inkDim)
                    }
                } else {
                    // 커버 배율: zoom=1일 때 짧은 변이 프레임을 가득 채운다
                    val baseScale = cropPx / minOf(source.width, source.height)
                    val total = baseScale * zoom
                    val image = remember(source) { source.asImageBitmap() }

                    Box(
                        Modifier
                            .size(cropSizeDp)
                            .clip(CircleShape)
                            .background(Color.Black)
                            .border(2.dp, tokens.signature, CircleShape)
                            .pointerInput(source) {
                                // 주의: 제스처 람다는 재구성돼도 유지되므로,
                                // 이동 한계는 항상 '현재 zoom' 기준으로 다시 계산한다
                                detectTransformGestures { _, pan, gestureZoom, _ ->
                                    val newZoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                                    val zoomRatio = newZoom / zoom
                                    zoom = newZoom
                                    val current = baseScale * newZoom
                                    val maxX = maxOf(0f, (source.width * current - cropPx) / 2f)
                                    val maxY = maxOf(0f, (source.height * current - cropPx) / 2f)
                                    val candidate = offset * zoomRatio + pan
                                    offset = Offset(
                                        candidate.x.coerceIn(-maxX, maxX),
                                        candidate.y.coerceIn(-maxY, maxY),
                                    )
                                }
                            },
                    ) {
                        Canvas(Modifier.size(cropSizeDp)) {
                            val topLeftX = cropPx / 2f + offset.x - source.width * total / 2f
                            val topLeftY = cropPx / 2f + offset.y - source.height * total / 2f
                            translate(topLeftX, topLeftY) {
                                scale(total, total, pivot = Offset.Zero) {
                                    drawImage(image)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(PbpDimens.sp2))
                    Text(
                        "드래그로 이동 · 두 손가락으로 확대/축소",
                        fontSize = 11.sp,
                        color = tokens.inkDim,
                    )
                }
            }
        },
        confirmButton = {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            var saving by remember { mutableStateOf(false) }
            TextButton(
                enabled = source != null && !saving,
                onClick = {
                    val src = source ?: return@TextButton
                    saving = true
                    // 512px 렌더링+JPEG 압축은 메인 스레드에서 ANR 위험 (P3-5)
                    scope.launch {
                        val path = withContext(Dispatchers.IO) {
                            cropToFile(context, src, cropPx, zoom, offset)
                        }
                        saving = false
                        if (path != null) onCropped(path) else onDismiss()
                    }
                },
            ) { Text(if (saving) "저장 중…" else "적용") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = true),
    )
}

/** 화면과 같은 변환을 512px 출력 비트맵에 재현해 저장한다 */
private fun cropToFile(
    context: Context,
    source: android.graphics.Bitmap,
    cropPx: Float,
    zoom: Float,
    offset: Offset,
): String? = runCatching {
    val outSize = 512
    val k = outSize / cropPx
    val baseScale = cropPx / minOf(source.width, source.height)
    val total = baseScale * zoom
    val topLeftX = cropPx / 2f + offset.x - source.width * total / 2f
    val topLeftY = cropPx / 2f + offset.y - source.height * total / 2f

    val out = android.graphics.Bitmap.createBitmap(outSize, outSize, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val matrix = android.graphics.Matrix().apply {
        postScale(total, total)
        postTranslate(topLeftX, topLeftY)
        postScale(k, k)
    }
    canvas.drawBitmap(source, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))

    // 투명 영역이 있는 원본(PNG 등)은 JPEG로 저장하면 검게 채워진다 — PNG로 보존
    val keepAlpha = source.hasAlpha()
    val dir = File(context.filesDir, "avatars").apply { mkdirs() }
    val file = File(dir, "${UUID.randomUUID()}" + if (keepAlpha) ".png" else ".jpg")
    file.outputStream().use {
        if (keepAlpha) {
            out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } else {
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it)
        }
    }
    file.absolutePath
}.getOrNull()

private fun loadBitmap(context: Context, uri: Uri, maxSize: Int): android.graphics.Bitmap? =
    runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }?.let { decoded ->
            // 카메라 세로 사진 회전 보정 (P2-3)
            com.pbp.app.data.Images.applyExifOrientation(
                decoded,
                com.pbp.app.data.Images.exifOrientation(context, uri),
            )
        }
    }.getOrNull()
