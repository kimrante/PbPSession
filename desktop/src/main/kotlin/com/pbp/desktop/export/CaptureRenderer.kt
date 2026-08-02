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
import com.pbp.shared.CaptureLayout

/**
 * 데스크톱 캡처 렌더러 — 모바일과 **같은 360dp 폭·같은 부품**으로 그린다.
 * 수치가 갈라지면 같은 대화가 기기마다 다른 이미지가 되므로, 폭·간격·머리글·낙관은
 * `app/export/CaptureRenderer.kt`와 같은 값을 쓴다.
 */
object CaptureRenderer {

    // 크기·분할 규칙은 :shared CaptureLayout이 단일 출처 — 모바일과 갈라지지 않게 (C1)
    const val SHEET_WIDTH_DP = CaptureLayout.SHEET_WIDTH_DP
    const val RENDER_DENSITY = CaptureLayout.RENDER_DENSITY
    const val MAX_HEIGHT_PX = CaptureLayout.MAX_HEIGHT_PX

    val widthPx = CaptureLayout.widthPx

    /** @return PNG 바이트 목록. 높이 상한을 넘으면 메시지 경계에서 나눠 여러 장 */
    @OptIn(ExperimentalComposeUiApi::class)
    fun render(
        room: JoinedRoom,
        messages: List<Message>,
        myUid: String,
        avatarCache: MutableMap<String, ImageBitmap?>,
        firestore: FirestoreRest?,
        withBackground: Boolean,
        /** 잡담(OOC)을 이미지에서 뺀다 — 선택 범위는 그대로, 그릴 때만 거른다 (모바일과 동일) */
        excludeOoc: Boolean = false,
    ): List<ByteArray> {
        @Suppress("NAME_SHADOWING")
        val messages = if (excludeOoc) messages.filterNot { it.isOoc } else messages
        if (messages.isEmpty()) return emptyList()
        // 추정으로 1차 분할한 뒤, 실제로 재 보고 넘치면 그 청크만 쪼개 다시 그린다.
        // 추정은 아무리 다듬어도 어긋나므로 **실측이 최종 판정**이어야 한다 (V2 — 모바일과 같은 방식).
        // 예외는 삼키지 않는다 — 중간 청크만 사라지면 "1/3, 3/3"짜리 캡처가 조용히 저장된다 (R5)
        val pending = ArrayDeque(splitByHeight(messages))
        val rendered = mutableListOf<List<Message>>()
        val pages = mutableListOf<ByteArray>()
        while (pending.isNotEmpty()) {
            val chunk = pending.removeFirst()
            val bytes = try {
                // 장수가 확정된 뒤에 낙관 번호를 넣어야 해서 여기서는 비워 둔다
                renderOne(room, chunk, myUid, avatarCache, firestore, withBackground, page = null)
            } catch (tooTall: TooTallException) {
                if (chunk.size <= 1) throw tooTall // 한 건이 상한을 넘으면 나눌 수가 없다
                val half = chunk.size / 2
                pending.addFirst(chunk.drop(half))
                pending.addFirst(chunk.take(half))
                continue
            }
            rendered += chunk
            pages += bytes
        }
        if (pages.size <= 1) return pages
        // 장수가 정해졌으니 여러 장이면 낙관에 n/N을 붙여 다시 그린다
        return rendered.mapIndexed { index, chunk ->
            renderOne(
                room, chunk, myUid, avatarCache, firestore, withBackground,
                page = "${index + 1}/${rendered.size}",
            )
        }
    }

    /** 실측 높이가 [MAX_HEIGHT_PX]에 닿았다 — 청크를 쪼개 다시 그려야 한다 (모바일과 같은 계약) */
    class TooTallException(val heightPx: Int) :
        IllegalStateException("한 장에 담기지 않는 높이(${heightPx}px)")

    @OptIn(ExperimentalComposeUiApi::class)
    private fun renderOne(
        room: JoinedRoom,
        messages: List<Message>,
        myUid: String,
        avatarCache: MutableMap<String, ImageBitmap?>,
        firestore: FirestoreRest?,
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
        // 잘라내지 않는다 — 예전에는 여기서 클램프해 페이지 하단(마지막 말풍선·낙관)이
        // 아무 말 없이 사라졌다. 프로브 씬이 상한 높이라 그 이상은 재지도 못하므로,
        // 상한에 닿았다면 넘친 것으로 보고 호출부가 쪼개게 한다 (V2)
        if (measured >= MAX_HEIGHT_PX) throw TooTallException(measured)
        val height = measured.coerceAtLeast(1)
        val content: @Composable () -> Unit = {
            CaptureSheet(room, messages, myUid, avatarCache, firestore, withBackground, page)
        }
        return ImageComposeScene(widthPx, height, Density(RENDER_DENSITY), content = content).use {
            it.render().encodeToData()!!.bytes
        }
    }

    private inline fun <T> ImageComposeScene.use(block: (ImageComposeScene) -> T): T =
        try { block(this) } finally { close() }

    /** 분할 — 규칙은 :shared가 갖고 있고 여기서는 인덱스를 메시지로 되돌리기만 한다 (C1) */
    private fun splitByHeight(messages: List<Message>): List<List<Message>> =
        CaptureLayout.splitByHeight(messages.map { it.layoutItem() })
            .map { range -> messages.slice(range) }
}

@Composable
private fun CaptureSheet(
    room: JoinedRoom,
    messages: List<Message>,
    myUid: String,
    avatarCache: MutableMap<String, ImageBitmap?>,
    firestore: FirestoreRest?,
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

/** 형식은 :shared가 단일 출처 (C1) */
fun formatDateRange(first: Long, last: Long): String =
    CaptureLayout.formatDateRange(first, last)

private fun dateOnly(millis: Long): String = CaptureLayout.dateOnly(millis)

/** 높이 계산에 필요한 것만 뽑는다 */
private fun Message.layoutItem() = CaptureLayout.Item(
    body = body,
    type = type,
    isOoc = isOoc,
    senderIsGm = senderIsGm,
)
