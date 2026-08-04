package com.pbp.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.request.ImageRequest
import com.pbp.app.data.Message
import com.pbp.app.data.judgeKey
import com.pbp.shared.CaptureLayout
import com.pbp.app.ui.chat.JudgeState
import com.pbp.app.ui.chat.MessageBlock
import com.pbp.app.ui.chat.isContinuation
import com.pbp.app.ui.theme.GowunBatang
import com.pbp.app.ui.theme.PbpDimens
import com.pbp.app.ui.theme.PbpLightColors
import com.pbp.app.ui.theme.PbpPalette
import com.pbp.app.ui.theme.PbpTheme
import com.pbp.app.ui.theme.LocalPbpColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.withContext
import java.io.File

/** ComposeView를 붙일 Activity 찾기 — Compose의 LocalContext는 ContextWrapper일 수 있다 */
fun Context.findActivity(): ComponentActivity? {
    var context: Context? = this
    while (context is android.content.ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    return null
}

/**
 * 선택한 대화를 **오프스크린에서 다시 그려** PNG용 비트맵으로 만든다.
 *
 * 화면을 찍지 않는 이유: 상단 바·입력줄·딤이 함께 찍히고, 화면 밖 부분은 아예 없다.
 * 폭을 [SHEET_WIDTH_DP]로 고정하고 밀도도 [RENDER_DENSITY]로 **고정**하는 이유는
 * 기기마다 줄바꿈과 픽셀 크기가 달라지지 않게 하려는 것이다 — 어느 기기에서 만들어도
 * 같은 이미지가 나온다.
 */
object CaptureRenderer {

    // 크기·분할 규칙은 :shared CaptureLayout이 단일 출처 — PC와 갈라지지 않게 (C1)
    const val SHEET_WIDTH_DP = CaptureLayout.SHEET_WIDTH_DP
    const val RENDER_DENSITY = CaptureLayout.RENDER_DENSITY
    const val MAX_HEIGHT_PX = CaptureLayout.MAX_HEIGHT_PX
    const val MAX_TOTAL_HEIGHT_PX = CaptureLayout.MAX_TOTAL_HEIGHT_PX

    val widthPx = CaptureLayout.widthPx

    /**
     * @return 만들어진 비트맵 목록(여러 장이면 순서대로). 실패하면 빈 목록.
     */
    /**
     * @param excludeOoc 잡담(OOC)을 이미지에서 뺀다. 선택 범위는 그대로 두고 **그릴 때만**
     *   거르므로, 머리글의 개수·시각 범위도 걸러낸 뒤 기준이 된다.
     */
    suspend fun render(
        context: Context,
        roomName: String,
        backgroundKey: String,
        messages: List<Message>,
        withBackground: Boolean,
        excludeOoc: Boolean = false,
        themeColor: Long = PbpPalette.DEFAULT_THEME_COLOR,
        rolledRefs: Set<String> = emptySet(),
    ): List<Bitmap> {
        @Suppress("NAME_SHADOWING")
        val messages = if (excludeOoc) messages.filterNot { it.isOoc } else messages
        if (messages.isEmpty()) return emptyList()
        // 총 높이 상한 — 넘으면 만들다 OOM으로 죽는 대신 이유를 말하고 멈춘다 (R2)
        val estimated = estimateHeightPx(messages)
        require(estimated <= MAX_TOTAL_HEIGHT_PX) {
            "범위가 너무 깁니다 (약 ${estimated}px) — 나눠서 만들어 주세요"
        }
        // 아바타는 Coil AsyncImage라 두 프레임만으로는 안 붙는다 — 캐시를 먼저 채운다
        preloadAvatars(context, messages, backgroundKey.takeIf { withBackground })
        // 추정으로 1차 분할한 뒤, 실제로 그려 보고 넘치면 그 청크만 쪼개 다시 그린다.
        // 추정은 아무리 다듬어도 어긋나므로 **실측이 최종 판정**이어야 한다 (R1)
        val planned = splitByHeight(messages)
        // 낙관의 n/N은 장수를 알아야 찍는다. 예전에는 전 페이지를 **두 번** 그렸는데
        // (32,000px 기준 최대 8회·수백 MB), 추정 장수를 낙관으로 먼저 믿고 그린 뒤
        // 실측 재분할로 장수가 달라졌을 때만 다시 그린다 (E3)
        val guessedPages = planned.size
        val pending = ArrayDeque(planned)
        val rendered = mutableListOf<List<Message>>()
        val bitmaps = mutableListOf<Bitmap>()
        while (pending.isNotEmpty()) {
            val chunk = pending.removeFirst()
            val bitmap = try {
                renderOne(
                    roomName, backgroundKey, chunk, withBackground, themeColor, rolledRefs,
                    page = if (guessedPages > 1) "${rendered.size + 1}/$guessedPages" else null,
                )
            } catch (tooTall: TooTallException) {
                if (chunk.size <= 1) throw tooTall // 한 건이 상한을 넘으면 나눌 수가 없다
                val half = chunk.size / 2
                pending.addFirst(chunk.drop(half))
                pending.addFirst(chunk.take(half))
                continue
            }
            rendered += chunk
            bitmaps += bitmap
        }
        // 추정이 맞았으면 여기서 끝 — 재렌더 없음
        if (rendered.size == guessedPages) return bitmaps
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
        return rendered.mapIndexed { index, chunk ->
            renderOne(
                roomName, backgroundKey, chunk, withBackground, themeColor, rolledRefs,
                page = if (rendered.size > 1) "${index + 1}/${rendered.size}" else null,
            )
        }
    }

    /** 실측 높이가 [MAX_HEIGHT_PX]를 넘었다 — 청크를 쪼개 다시 그려야 한다 */
    class TooTallException(val heightPx: Int) :
        IllegalStateException("한 장에 담기지 않는 높이(${heightPx}px)")

    /** 분할 — 규칙은 :shared가 갖고 있고 여기서는 인덱스를 메시지로 되돌리기만 한다 (C1) */
    private fun splitByHeight(messages: List<Message>): List<List<Message>> =
        CaptureLayout.splitByHeight(messages.map { it.layoutItem() })
            .map { range -> messages.slice(range) }

    /**
     * 결과 이미지의 대략 높이(px). 실제 렌더 전이라 정확할 수 없어 화면 문구에 '약'을 붙인다.
     * **분할 판정과 같은 함수를 써야** 하단 바가 말한 높이와 실제 장수가 어긋나지 않는다.
     */
    fun estimateHeightPx(messages: List<Message>): Int =
        CaptureLayout.estimateHeightPx(messages.map { it.layoutItem() })

    /** 아바타·배경 이미지를 Coil 캐시에 미리 올린다 — 빈 원으로 찍히는 것을 막는다 */
    private suspend fun preloadAvatars(
        context: Context,
        messages: List<Message>,
        backgroundPath: String?,
    ) {
        // 실제 렌더의 AsyncImage는 싱글턴 로더를 쓴다 — 새 로더로 미리 받으면
        // 캐시가 공유되지 않아 프리로드가 통째로 헛돈다 (R3)
        val loader = coil3.SingletonImageLoader.get(context)
        val paths = messages.mapNotNull { it.senderImagePath }.distinct() +
            listOfNotNull(backgroundPath?.takeIf { !it.startsWith(com.pbp.shared.Protocol.PRESET_PREFIX) })
        paths.forEach { path ->
            runCatching {
                loader.execute(ImageRequest.Builder(context).data(File(path)).build())
            }
        }
    }

    private suspend fun renderOne(
        roomName: String,
        backgroundKey: String,
        messages: List<Message>,
        withBackground: Boolean,
        themeColor: Long,
        rolledRefs: Set<String>,
        page: String?,
    ): Bitmap = withContext(Dispatchers.Main) { // measure/layout/draw는 메인 스레드
        // 붙이기 **직전에** 최신 액티비티를 꺼낸다 — 클릭 시점 것을 붙들고 있으면
        // 도중에 회전했을 때 파괴된 decorView에 붙어 "높이 0"으로 실패한다 (A2)
        val activity = CaptureHolder.activity
            ?: error("화면이 없어 이미지를 만들 수 없습니다")
        val view = ComposeView(activity).apply {
            setContent {
                // 밀도를 고정해야 기기와 무관하게 같은 픽셀 크기가 나온다
                CompositionLocalProvider(
                    LocalDensity provides Density(RENDER_DENSITY, 1f),
                ) {
                    // 캡처 이미지는 항상 라이트 토큰 — 기기 다크 설정에 결과가 끌려가지 않게
                    PbpTheme(darkTheme = false) {
                        CompositionLocalProvider(LocalPbpColors provides PbpLightColors) {
                            CaptureSheet(
                                roomName, backgroundKey, messages, withBackground,
                                themeColor, rolledRefs, page,
                            )
                        }
                    }
                }
            }
        }
        // ComposeView는 컴포지션을 돌리기 전에 이 세 주인을 찾아야 한다. 보통은 부모를
        // 타고 올라가 decorView에서 찾지만, 못 찾으면 붙는 순간 예외가 난다 — 직접 걸어 둔다
        view.setViewTreeLifecycleOwner(activity)
        view.setViewTreeSavedStateRegistryOwner(activity)
        view.setViewTreeViewModelStoreOwner(activity)
        val root = activity.window.decorView as ViewGroup
        // 화면 밖에 두되 붙여 둔다 — 떼어 두면 컴포지션이 돌지 않는다(GONE도 마찬가지)
        view.alpha = 0f
        root.addView(view, ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT))
        try {
            // 컴포지션 → 레이아웃까지. 첫 프레임에 아직 높이가 0이면 몇 프레임 더 기다린다
            // (아바타 디코드처럼 늦게 붙는 것이 있다)
            var height = 0
            var frames = 0
            while (height <= 0 && frames < 8) {
                awaitFrame()
                frames++
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                height = view.measuredHeight
            }
            check(height > 0) { "레이아웃 높이가 0" }
            // 잘라내지 않는다 — 예전에는 여기서 클램프해 페이지 하단(마지막 말풍선·낙관)이
            // 아무 말 없이 사라졌다. 넘치면 호출부가 청크를 쪼개 다시 그린다 (R1)
            if (height > MAX_HEIGHT_PX) throw TooTallException(height)
            view.layout(0, 0, widthPx, height)
            Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                .also { view.draw(Canvas(it)) }
        } finally {
            root.removeView(view)
        }
    }
}

/**
 * 결과 이미지의 내용 — 머리글 / 본문 / 낙관.
 * 본문은 채팅 화면과 **같은 [MessageBlock]**을 `mark = NONE`으로 재사용하고,
 * 간격·여백도 같은 토큰을 쓴다. 그래야 화면과 결과가 갈라지지 않는다.
 */
@Composable
private fun CaptureSheet(
    roomName: String,
    backgroundKey: String,
    messages: List<Message>,
    withBackground: Boolean,
    themeColorArgb: Long,
    /** 굴림이 끝난 요청 키 — 방 전체 기준이라 범위 밖 굴림도 반영된다 (E6) */
    rolledRefs: Set<String>,
    page: String?,
) {
    val tokens = PbpLightColors
    // 방 테마를 그대로 쓴다 — 기본색으로 고정하면 시간 표기 색이 화면과 갈라졌다 (E6)
    val themeColor = Color(themeColorArgb)
    Column(Modifier.fillMaxWidth().background(tokens.panel2)) {
        CaptureHeader(roomName, messages)
        Box(Modifier.fillMaxWidth()) {
            if (withBackground) {
                val preset = PbpPalette.backgroundPresets[backgroundKey]
                if (preset != null) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(listOf(Color(preset.first), Color(preset.second)))
                            )
                    )
                } else {
                    coil3.compose.AsyncImage(
                        model = File(backgroundKey),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(listOf(tokens.veilTop, tokens.veilMid, tokens.veilTop))
                        )
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PbpDimens.gap4, vertical = PbpDimens.gap3),
            ) {
                messages.forEachIndexed { index, message ->
                    val grouped = isContinuation(messages.getOrNull(index - 1), message)
                    Box(
                        Modifier.padding(
                            top = when {
                                index == 0 -> 0.dp
                                grouped -> PbpDimens.gap1
                                else -> PbpDimens.gap3
                            }
                        )
                    ) {
                        MessageBlock(
                            message = message,
                            grouped = grouped,
                            // 이미지에는 시각을 찍지 않는다 — 범위는 머리글이 말한다
                            showTime = false,
                            themeColor = themeColor,
                            // 이미지에는 "내 차례" 같은 것이 없다 — 굴렸으면 완료, 아니면 대기
                            judgeState = if (judgeKey(message) in rolledRefs) {
                                JudgeState.Done
                            } else JudgeState.Waiting,
                            onLongPress = {},
                        )
                    }
                }
            }
        }
        CaptureFooter(roomName, messages, page)
    }
}

@Composable
private fun CaptureHeader(roomName: String, messages: List<Message>) {
    val tokens = PbpLightColors
    Row(
        Modifier
            .fillMaxWidth()
            .background(tokens.panel.copy(alpha = .9f))
            .padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(PbpDimens.logoTile)
                .clip(RoundedCornerShape(7.dp))
                .background(Brush.linearGradient(listOf(tokens.signature, tokens.signatureDeep))),
            contentAlignment = Alignment.Center,
        ) {
            Text("◆", fontSize = 11.sp, color = tokens.ink)
        }
        Spacer(Modifier.width(PbpDimens.gap2))
        Column(Modifier.weight(1f)) {
            Text(
                roomName,
                fontFamily = GowunBatang,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = tokens.ink,
                maxLines = 1,
            )
            Text(
                "${formatDateRange(messages.first().createdAt, messages.last().createdAt)} · ${messages.size}개 메시지",
                fontSize = 10.sp,
                color = tokens.inkDim,
            )
        }
    }
}

@Composable
private fun CaptureFooter(roomName: String, messages: List<Message>, page: String?) {
    val tokens = PbpLightColors
    Row(
        Modifier
            .fillMaxWidth()
            .background(tokens.panel.copy(alpha = .9f))
            .padding(horizontal = PbpDimens.gap3, vertical = PbpDimens.gap2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("PbP · $roomName", fontSize = 10.sp, color = tokens.inkDim)
        Spacer(Modifier.weight(1f))
        Text(
            page ?: dateOnly(messages.first().createdAt),
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            color = tokens.signatureInk,
        )
    }
}

/** "2026-07-30 21:03 – 21:14" — 형식은 :shared가 단일 출처 (C1) */
fun formatDateRange(first: Long, last: Long): String =
    CaptureLayout.formatDateRange(first, last)

private fun dateOnly(millis: Long): String = CaptureLayout.dateOnly(millis)

/** 높이 계산에 필요한 것만 뽑는다 — :shared는 Room 엔티티를 모른다 */
private fun Message.layoutItem() = CaptureLayout.Item(
    body = body,
    type = type.name,
    isOoc = isOoc,
    senderIsGm = senderIsGm,
)
