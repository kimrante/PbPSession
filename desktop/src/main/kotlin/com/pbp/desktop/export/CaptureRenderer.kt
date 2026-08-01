package com.pbp.desktop.export

import androidx.compose.foundation.background
import com.pbp.desktop.rememberLocalBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.ImageComposeScene
import com.pbp.desktop.MessageBlock
import com.pbp.desktop.data.FirestoreRest
import com.pbp.desktop.data.JoinedRoom
import com.pbp.desktop.data.Message
import com.pbp.desktop.isContinuation
import com.pbp.desktop.sharesTimeLabel
import com.pbp.desktop.ui.DesktopDimens
import com.pbp.desktop.ui.GowunBatang
import com.pbp.desktop.ui.Tokens
import androidx.compose.ui.graphics.ImageBitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 데스크톱 캡처 렌더러 — 모바일과 **같은 360dp 폭·같은 부품**으로 그린다.
 * 수치가 갈라지면 같은 대화가 기기마다 다른 이미지가 되므로, 폭·간격·머리글·낙관은
 * `app/export/CaptureRenderer.kt`와 같은 값을 쓴다.
 */
object CaptureRenderer {

    const val SHEET_WIDTH_DP = 360
    const val RENDER_DENSITY = 2f
    const val MAX_HEIGHT_PX = 8_000

    val widthPx = (SHEET_WIDTH_DP * RENDER_DENSITY).toInt()

    /** @return PNG 바이트 목록. 높이 상한을 넘으면 메시지 경계에서 나눠 여러 장 */
    @OptIn(ExperimentalComposeUiApi::class)
    fun render(
        room: JoinedRoom,
        messages: List<Message>,
        myUid: String,
        avatarCache: MutableMap<String, ImageBitmap?>,
        firestore: FirestoreRest,
        withBackground: Boolean,
        /** 잡담(OOC)을 이미지에서 뺀다 — 선택 범위는 그대로, 그릴 때만 거른다 (모바일과 동일) */
        excludeOoc: Boolean = false,
    ): List<ByteArray> {
        @Suppress("NAME_SHADOWING")
        val messages = if (excludeOoc) messages.filterNot { it.isOoc } else messages
        if (messages.isEmpty()) return emptyList()
        val chunks = splitByHeight(messages)
        // 예외를 삼키지 않는다 — 중간 청크만 사라지면 "1/3, 3/3"짜리 캡처가 조용히 저장된다.
        // 모바일은 v0.7.2에서 같은 패턴을 걷어냈다 (R5)
        return chunks.mapIndexed { index, chunk ->
            renderOne(
                room, chunk, myUid, avatarCache, firestore, withBackground,
                page = if (chunks.size > 1) "${index + 1}/${chunks.size}" else null,
            )
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun renderOne(
        room: JoinedRoom,
        messages: List<Message>,
        myUid: String,
        avatarCache: MutableMap<String, ImageBitmap?>,
        firestore: FirestoreRest,
        withBackground: Boolean,
        page: String?,
    ): ByteArray {
        // ImageComposeScene은 콘텐츠 높이를 알려주지 않는다 — 넉넉한 캔버스에서 한 번
        // 재고(onSizeChanged) 그 높이로 다시 그린다. 두 번 그려도 순수 계산이라 빠르다.
        var measured = 0
        val probe: @Composable () -> Unit = {
            Box(Modifier.onSizeChanged { measured = it.height }) {
                CaptureSheet(room, messages, myUid, avatarCache, firestore, withBackground, page)
            }
        }
        ImageComposeScene(widthPx, MAX_HEIGHT_PX, Density(RENDER_DENSITY), content = probe).use {
            it.render()
        }
        val height = measured.coerceIn(1, MAX_HEIGHT_PX)
        val content: @Composable () -> Unit = {
            CaptureSheet(room, messages, myUid, avatarCache, firestore, withBackground, page)
        }
        return ImageComposeScene(widthPx, height, Density(RENDER_DENSITY), content = content).use {
            it.render().encodeToData()!!.bytes
        }
    }

    private inline fun <T> ImageComposeScene.use(block: (ImageComposeScene) -> T): T =
        try { block(this) } finally { close() }

    private fun splitByHeight(messages: List<Message>): List<List<Message>> {
        val chunks = mutableListOf<List<Message>>()
        var current = mutableListOf<Message>()
        var height = CHROME_DP
        messages.forEach { message ->
            val h = estimate(message)
            if (current.isNotEmpty() && height + h > MAX_HEIGHT_PX / RENDER_DENSITY) {
                chunks += current
                current = mutableListOf()
                height = CHROME_DP
            }
            current += message
            height += h
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private const val CHROME_DP = 120f

    /** 모바일 CaptureRenderer.estimate와 같은 규칙이어야 한다 (R1) */
    private fun estimate(message: Message): Float = when {
        message.type != "TEXT" || message.isOoc -> 28f
        message.senderIsGm -> 34f + lines(message.body, perLine = 26) * 20f
        else -> 30f + lines(message.body, perLine = 17) * 20f
    }

    private fun lines(body: String, perLine: Int): Int =
        body.split('\n').sumOf { line ->
            maxOf(1, (line.length + perLine - 1) / perLine)
        }.coerceAtLeast(1)
}

@Composable
private fun CaptureSheet(
    room: JoinedRoom,
    messages: List<Message>,
    myUid: String,
    avatarCache: MutableMap<String, ImageBitmap?>,
    firestore: FirestoreRest,
    withBackground: Boolean,
    page: String?,
) {
    Column(Modifier.fillMaxWidth().background(Tokens.Panel2)) {
        CaptureHeader(room.name, messages)
        Box(Modifier.fillMaxWidth()) {
            if (withBackground) {
                val preset = Tokens.backgroundPresets[room.backgroundKey]
                if (preset != null) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(listOf(Color(preset.first), Color(preset.second)))
                            )
                    )
                } else {
                    // 프리셋이 아니면 로컬 파일 배경 — 화면과 같은 그림이 나와야 한다 (R7)
                    rememberLocalBitmap(room.backgroundKey)?.let { bitmap ->
                        Image(
                            bitmap, null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(listOf(Tokens.VeilTop, Tokens.VeilMid, Tokens.VeilTop))
                        )
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DesktopDimens.gap4, vertical = DesktopDimens.gap3),
            ) {
                messages.forEachIndexed { index, message ->
                    val grouped = isContinuation(messages.getOrNull(index - 1), message)
                    val showTime = !sharesTimeLabel(message, messages.getOrNull(index + 1))
                    Box(
                        Modifier.padding(
                            top = when {
                                index == 0 -> 0.dp
                                grouped -> DesktopDimens.gap1
                                else -> DesktopDimens.gap3
                            }
                        )
                    ) {
                        MessageBlock(
                            message, myUid, room, avatarCache, firestore, grouped,
                            showTime = showTime,
                        )
                    }
                }
            }
        }
        CaptureFooter(room.name, messages, page)
    }
}

@Composable
private fun CaptureHeader(roomName: String, messages: List<Message>) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = .9f))
            .padding(horizontal = DesktopDimens.gap3, vertical = DesktopDimens.gap2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFFFD05C), Color(0xFFEFB945)))),
            contentAlignment = Alignment.Center,
        ) {
            Text("◆", fontSize = 11.sp, color = Tokens.Ink)
        }
        Spacer(Modifier.width(DesktopDimens.gap2))
        Column(Modifier.weight(1f)) {
            Text(
                roomName,
                fontFamily = GowunBatang,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = Tokens.Ink,
                maxLines = 1,
            )
            Text(
                "${formatDateRange(messages.first().createdAt, messages.last().createdAt)} · ${messages.size}개 메시지",
                fontSize = 9.sp,
                color = Tokens.InkDim,
            )
        }
    }
}

@Composable
private fun CaptureFooter(roomName: String, messages: List<Message>, page: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = .9f))
            .padding(horizontal = DesktopDimens.gap3, vertical = DesktopDimens.gap2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("PbP · $roomName", fontSize = 9.sp, color = Tokens.InkDim)
        Spacer(Modifier.weight(1f))
        Text(
            page ?: dateOnly(messages.first().createdAt),
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            color = Tokens.SignatureInk,
        )
    }
}

/** 모바일 CaptureRenderer.formatDateRange와 같은 형식 */
fun formatDateRange(first: Long, last: Long): String {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val time = SimpleDateFormat("HH:mm", Locale.getDefault())
    val sameDay = day.format(Date(first)) == day.format(Date(last))
    return if (sameDay) {
        "${day.format(Date(first))} ${time.format(Date(first))} – ${time.format(Date(last))}"
    } else {
        "${day.format(Date(first))} ${time.format(Date(first))} – " +
            "${day.format(Date(last))} ${time.format(Date(last))}"
    }
}

private fun dateOnly(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
