package com.pbp.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pbp.desktop.data.AppConfig
import com.pbp.desktop.data.FirestoreRest
import com.pbp.desktop.data.JoinedRoom
import com.pbp.desktop.data.Message
import com.pbp.desktop.data.Profile
import com.pbp.desktop.data.RoomCacheStore
import com.pbp.shared.CharacterCodec
import com.pbp.shared.DiceBot
import com.pbp.shared.ProfileStats
import com.pbp.shared.Rules
import com.pbp.shared.GmSpeech
import com.pbp.desktop.notify.DesktopNotifier
import com.pbp.desktop.ui.GowunBatang
import com.pbp.desktop.ui.MarkupText
import com.pbp.desktop.ui.Pretendard
import com.pbp.desktop.ui.Tokens
import com.pbp.desktop.ui.appFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pbp.shared.Protocol

/** 방 동기화 상태·메시지 문서 조립 — Main.kt에서 분리 (리뷰 B1) */

/** 방별 세션 캐시 (P3) — 메시지 목록·증분 커서·삭제 억제 목록. 프로세스 수명 동안 유지 */
internal class RoomSession {
    var messages: List<Message> = emptyList()
    var lastCreatedAt: Long = 0L
    val deletedDocIds: MutableSet<String> = mutableSetOf()
    /** 파일 캐시(P3 근본 수정)에서 이미 복원 시도했는지 */
    var diskLoaded: Boolean = false
    /** 마지막 파일 캐시 저장 시각 — 30초 스로틀 */
    var lastSavedAt: Long = 0L
}


internal fun messageValues(
    type: String,
    body: String,
    sender: Profile,
    isOoc: Boolean,
    authorUid: String,
    diceExpr: String? = null,
    isBot: Boolean = false,
    diceOutcome: String? = null,
    avatarId: String? = null,
): Map<String, Any?> = mapOf(
    "type" to type,
    "body" to body,
    "diceExpr" to diceExpr,
    "diceOutcome" to diceOutcome,
    "senderName" to sender.name,
    "senderEmoji" to sender.emoji,
    "senderIsGm" to sender.isGm,
    "senderIsBot" to isBot,
    "senderNameColor" to sender.nameColor,
    "senderBubbleColor" to sender.bubbleColor,
    "senderTextColor" to sender.textColor,
    "isOoc" to isOoc,
    "createdAt" to System.currentTimeMillis(),
    "authorUid" to authorUid,
    "avatarId" to avatarId,
)

// ══════════════ 왼쪽 패널: 방 목록 ══════════════

/**
 * SYSTEM 안내 메시지 문서 — 프로필 전환·로그 초기화·참여 인사가 같은 스키마를 쓰도록 (리뷰 C2).
 * 스키마가 바뀌어도 데스크톱은 이 한 곳만 고치면 된다.
 */
internal fun systemMessageValues(body: String, authorUid: String): Map<String, Any?> = mapOf(
    "type" to Protocol.MessageType.SYSTEM,
    "body" to body,
    "createdAt" to System.currentTimeMillis(),
    "authorUid" to authorUid,
    "isOoc" to false, "senderIsGm" to false, "senderIsBot" to false,
)
