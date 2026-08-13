package com.pbp.desktop

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.desktop.data.JoinedRoom
import com.pbp.desktop.ui.GowunBatang
import com.pbp.desktop.ui.Pretendard
import com.pbp.desktop.ui.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import com.pbp.desktop.data.AppPaths
import com.pbp.desktop.ui.DesktopDimens

/** 공용 오버레이 부품과 일반 다이얼로그 — Main.kt에서 분리 (리뷰 B1) */

/**
 * 입력 문법 도움말 — 입력창 오른쪽 끝 "?"로 연다. 오른쪽 위 X로 닫는다.
 * 목록은 [com.pbp.shared.MarkupHelp]가 단일 출처라 모바일과 항상 같다.
 *
 * 제목은 센터, 항목은 좌측 정렬 — 문법 예시는 세로로 줄이 맞아야 훑어읽기 좋다.
 */
@Composable
internal fun MarkupHelpOverlay(onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Tokens.Scrim).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(DesktopDimens.overlay)
                .clip(RoundedCornerShape(DesktopDimens.rSheet))
                .background(Tokens.Panel)
                .clickable(enabled = false) {}
                .padding(DesktopDimens.gap5)
                .verticalScroll(rememberScrollState()),
        ) {
            Box(Modifier.fillMaxWidth()) {
                Text(
                    "입력 문법", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = Tokens.Ink, modifier = Modifier.align(Alignment.Center),
                )
                Box(
                    Modifier.align(Alignment.CenterEnd).size(DesktopDimens.touchTarget)
                        .clip(CircleShape).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", fontSize = 13.sp, color = Tokens.InkSub)
                }
            }
            Spacer(Modifier.height(DesktopDimens.gap4))
            // 항목 패딩 대신 부모 간격으로 — 항목마다 상하 패딩을 주면 사이가 두 배로 벌어진다
            Column(verticalArrangement = Arrangement.spacedBy(DesktopDimens.gap2)) {
                com.pbp.shared.MarkupHelp.entries.forEach { entry ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            entry.syntax, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            // 링용 골드가 아니라 텍스트용 (P0 1-3)
                            fontFamily = FontFamily.Monospace, color = Tokens.SignatureInk,
                        )
                        Spacer(Modifier.height(DesktopDimens.gap1))
                        Text(entry.summary, fontSize = 13.sp, color = Tokens.Ink)
                        entry.example?.let {
                            Spacer(Modifier.height(DesktopDimens.gap1))
                            Text(it, fontSize = 11.sp, color = Tokens.InkDim)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun OverlayScaffold(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        // 라이트 모드 딤 — rgba(30,35,45,.38) (목업 final-design.html)
        // 닫기는 **마우스 클릭만** 받는다 — clickable은 포커스가 오면 키보드 엔터·스페이스도
        // 클릭으로 치기 때문에, 입력창에서 친 엔터가 창을 닫아 버렸다
        Modifier.fillMaxSize().background(Tokens.Scrim)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(DesktopDimens.overlay)
                .clip(RoundedCornerShape(DesktopDimens.rSheet))
                .background(Tokens.Panel)
                // 패널 안쪽 클릭은 여기서 삼킨다 (딤까지 내려가면 창이 닫힌다)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(DesktopDimens.gap5)
                .verticalScroll(rememberScrollState()),
        ) {
            // 타이틀은 정중앙 — 도움말 오버레이만 센터였던 것을 전 오버레이로 (P1)
            Text(
                title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Tokens.Ink,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(DesktopDimens.gap4))
            content()
        }
    }
}

@Composable
internal fun OverlayField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    /** 엔터로 확인. 넘기지 않으면 엔터는 아무 일도 하지 않는다 */
    onSubmit: (() -> Unit)? = null,
    /** 열자마자 바로 칠 수 있게 커서를 둔다 */
    autoFocus: Boolean = false,
) {
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                // 한 줄 입력에서 엔터는 줄바꿈이 아니라 '확인'이다. 여기서 받지 않으면
                // 위쪽 딤이 그 엔터를 클릭으로 받아 창이 그냥 닫혔다
                val submit = onSubmit
                if (submit != null && event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    submit()
                    true
                } else false
            }
            .clip(RoundedCornerShape(12.dp))
            .background(Tokens.FieldBg)
            .border(1.dp, Tokens.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = DesktopDimens.gap3, vertical = DesktopDimens.gap3),
        textStyle = TextStyle(color = Tokens.Ink, fontSize = 13.sp),
        cursorBrush = SolidColor(Tokens.SignatureRing),
        singleLine = true,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, fontSize = 13.sp, color = Tokens.InkDim)
                inner()
            }
        },
    )
}

@Composable
internal fun YellowButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = DesktopDimens.touchTarget)
            .clip(RoundedCornerShape(999.dp)).background(Tokens.Signature)
            .clickable(onClick = onClick)
            .padding(horizontal = DesktopDimens.gap4, vertical = DesktopDimens.gap2),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.OnSignature)
    }
}

/**
 * 파괴적 동작 전용 (P0 1-1) — 되돌릴 수 없는 것을 옐로(=긍정 강조)로 두면
 * "적용"과 구분이 안 된다. 모바일은 이미 danger를 쓴다.
 */
@Composable
internal fun DangerButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = DesktopDimens.touchTarget)
            .clip(RoundedCornerShape(999.dp)).background(Tokens.Danger)
            .clickable(onClick = onClick)
            .padding(horizontal = DesktopDimens.gap4, vertical = DesktopDimens.gap2),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.Panel)
    }
}

@Composable
internal fun GhostButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GhostButtonBase(label, modifier, Tokens.InkDim, onClick)
}

/** 파괴적 동작의 약한 형태 — 면은 그대로 두고 글자만 danger (P0 1-1) */
@Composable
internal fun GhostDangerButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GhostButtonBase(label, modifier, Tokens.Danger, onClick)
}

@Composable
private fun GhostButtonBase(
    label: String,
    modifier: Modifier,
    contentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier.heightIn(min = DesktopDimens.touchTarget)
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, Tokens.Line, RoundedCornerShape(999.dp))
            .background(Tokens.Panel)
            .clickable(onClick = onClick)
            .padding(horizontal = DesktopDimens.gap4, vertical = DesktopDimens.gap2),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = contentColor)
    }
}

@Composable
internal fun JoinOverlay(onDismiss: () -> Unit, onJoin: (String, onFail: () -> Unit) -> Unit) {
    var code by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    // 참여는 네트워크 왕복이라 창이 몇 초간 열려 있다 — 그동안 한 번 더 누르면
    // 같은 방이 목록에 둘 들어가 앱이 죽는다 (DC1). 한 번만 보낸다
    var joining by remember { mutableStateOf(false) }
    OverlayScaffold("초대 코드로 참여", onDismiss) {
        // 버튼과 같은 조건으로 — 빈 칸에서 친 엔터로 헛되이 참여를 시도하지 않는다
        val join = {
            if (!joining && code.isNotBlank()) {
                joining = true
                onJoin(code) { joining = false; failed = true }
            }
        }
        OverlayField(code, { code = it; failed = false }, "초대 코드", onSubmit = join, autoFocus = true)
        if (failed) {
            Spacer(Modifier.height(DesktopDimens.gap2))
            Text("방을 찾지 못했습니다. 코드를 확인해주세요.", fontSize = 11.sp, color = Tokens.Danger)
        }
        Spacer(Modifier.height(DesktopDimens.gap4))
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.gap2)) {
            YellowButton(if (joining) "참여 중…" else "참여", Modifier.weight(1f)) { join() }
            GhostButton("취소", Modifier.weight(1f), onDismiss)
        }
    }
}

@Composable
internal fun CreateOverlay(onDismiss: () -> Unit, onCreate: (String, onFail: () -> Unit) -> Unit) {
    var name by remember { mutableStateOf("") }
    // 만들기도 왕복이다 — 두 번 누르면 서버에 방이 둘 생긴다 (DC3)
    var creating by remember { mutableStateOf(false) }
    OverlayScaffold("새 세션", onDismiss) {
        val create = {
            if (!creating) {
                creating = true
                onCreate(name) { creating = false }
            }
        }
        // 이름 한 줄만 받는 창이다 — 열면 바로 치고 엔터로 끝낸다
        OverlayField(name, { name = it }, "방 이름", onSubmit = create, autoFocus = true)
        Spacer(Modifier.height(DesktopDimens.gap2))
        // 방 아이콘 폐지 — 배경으로만 구분. TRPG 룰은 크툴루의 부름 7판 고정 (모바일과 동일)
        Text("TRPG 룰: 크툴루의 부름 7판", fontSize = 11.sp, color = Tokens.Ink)
        Spacer(Modifier.height(DesktopDimens.gap1))
        Text("방을 만들면 마스터 권한과 초대 코드가 부여됩니다.", fontSize = 11.sp, color = Tokens.InkDim)
        Spacer(Modifier.height(DesktopDimens.gap4))
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.gap2)) {
            YellowButton(if (creating) "만드는 중…" else "만들기", Modifier.weight(1f)) { create() }
            GhostButton("취소", Modifier.weight(1f), onDismiss)
        }
    }
}

@Composable
internal fun CodeOverlay(code: String, onDismiss: () -> Unit) {
    // 데스크톱에는 토스트가 없다 — 안내 문구를 잠깐 바꿔 복사됐음을 알린다
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    OverlayScaffold("초대 코드", onDismiss) {
        Text(
            code, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Tokens.SignatureInk,
            letterSpacing = 4.sp, // 모바일과 동일 — 여섯 글자를 한 글자씩 읽게
            textAlign = TextAlign.Center, // 모바일과 동일하게 센터 (CLAUDE.md §0-(a))
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DesktopDimens.rCell))
                // 클릭하면 바로 복사 — 여섯 글자를 눈으로 옮겨 적을 이유가 없다 (모바일과 동일)
                .clickable {
                    runCatching {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                            java.awt.datatransfer.StringSelection(code), null,
                        )
                    }
                    copied = true
                }
                .padding(vertical = DesktopDimens.gap2),
        )
        Text(
            if (copied) "초대 코드 \"$code\" 복사했습니다" else "클릭하면 복사됩니다",
            fontSize = 11.sp,
            color = if (copied) Tokens.SignatureInk else Tokens.InkDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(DesktopDimens.gap2))
        Text(
            "상대가 모바일/PC의 '참여'에서 이 코드를 입력하면 같은 방에 연결됩니다.",
            fontSize = 13.sp, color = Tokens.InkDim,
        )
        Spacer(Modifier.height(DesktopDimens.gap4))
        YellowButton("닫기", Modifier.fillMaxWidth(), onDismiss)
    }
}

@Composable
internal fun SettingsOverlay(
    room: JoinedRoom?,
    recentColors: Map<String, List<Long>> = emptyMap(),
    onColorUsed: (String, Long) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
    onApply: (Long, String) -> Unit,
    onResetLogs: ((Boolean) -> Unit) -> Unit,
    /** HTML 로그 내보내기 — 채팅 상단 바에 있던 것을 여기로 옮겼다 (A) */
    onExport: () -> Unit = {},
) {
    if (room == null) return
    val scope = rememberCoroutineScope()
    var theme by remember { mutableStateOf(room.themeColor) }
    var background by remember { mutableStateOf(room.backgroundKey) }
    var hexOpen by remember {
        mutableStateOf(Tokens.themePresets.none { it.first == room.themeColor })
    }
    OverlayScaffold("방 설정 · ${room.name}", onDismiss) {
        Text("테마 컬러", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Spacer(Modifier.height(DesktopDimens.gap2))
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.gap2)) {
            SwatchRow(Tokens.themePresets.map { it.first }, theme, recentColors["theme"].orEmpty()) {
                theme = it
                hexOpen = false
            }
            // 커스텀 — 무지개 스와치, 프리셋 밖의 색이 선택되어 있으면 선택 표시 (모바일과 동일)
            CustomSwatch(on = Tokens.themePresets.none { it.first == theme }) {
                hexOpen = !hexOpen
            }
        }
        if (hexOpen) {
            Spacer(Modifier.height(DesktopDimens.gap2))
            ColorPalettePicker(theme) { theme = it; onColorUsed("theme", it) }
        }
        Spacer(Modifier.height(DesktopDimens.gap4))
        Text("배경", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.InkDim)
        Spacer(Modifier.height(DesktopDimens.gap2))
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.gap2)) {
            Tokens.backgroundPresets.forEach { (key, colors) ->
                val on = background == key
                Box(
                    Modifier.size(width = 64.dp, height = 44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.verticalGradient(listOf(Color(colors.first), Color(colors.second)))
                        )
                        .border(
                            1.5.dp,
                            if (on) Tokens.Signature else Tokens.Line,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { background = key },
                )
            }
        }
        Spacer(Modifier.height(DesktopDimens.gap2))
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.gap2)) {
            // 현재 커스텀 배경 미리보기 (모바일 '커스텀 · 사용 중' 셀과 동일 역할)
            if (Tokens.backgroundPresets[background] == null) {
                Box(
                    Modifier.size(width = 64.dp, height = 44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.5.dp, Tokens.Signature, RoundedCornerShape(10.dp))
                ) {
                    BackgroundLayer(background, Modifier.fillMaxSize())
                }
            }
            // 파일에서 선택 — 이미지를 ~/.pbp-desktop/backgrounds/에 저장해 경로를 보관
            var picking by remember { mutableStateOf(false) }
            Box(
                Modifier.size(width = 64.dp, height = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Tokens.Line, RoundedCornerShape(10.dp))
                    .clickable(enabled = !picking) {
                        picking = true
                        scope.launch {
                            try {
                                val picked = withContext(Dispatchers.IO) {
                                    pickAndStoreImage(
                                        "배경 이미지 선택", AppPaths.BACKGROUNDS, DesktopDimens.BACKGROUND_PX,
                                    )
                                }
                                if (picked != null) background = picked // 상태는 UI 스코프 (H3)
                            } finally {
                                picking = false
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "파일\n선택", fontSize = 10.sp, color = Tokens.InkDim, lineHeight = 13.sp,
                    textAlign = TextAlign.Center, // 2줄 라벨도 센터 (모바일과 동일)
                )
            }
        }
        Spacer(Modifier.height(DesktopDimens.gap2))
        Text(
            "커스텀 배경은 이 PC에서만 보입니다 (모바일과 동일)",
            fontSize = 10.sp, color = Tokens.InkDim,
        )
        Spacer(Modifier.height(DesktopDimens.gap3))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Line))
        Spacer(Modifier.height(DesktopDimens.gap3))
        Text("로그 내보내기", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tokens.Ink)
        Spacer(Modifier.height(DesktopDimens.gap1))
        Text(
            "전체 대화를 저장합니다 · 파일 이름을 .txt로 바꾸면 서식 없는 원문",
            fontSize = 10.sp, color = Tokens.InkDim,
        )
        Spacer(Modifier.height(DesktopDimens.gap2))
        GhostButton("내보내기", Modifier.fillMaxWidth()) { onExport() }
        Spacer(Modifier.height(DesktopDimens.gap3))
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.gap2)) {
            YellowButton("적용", Modifier.weight(1f)) { onApply(theme, background) }
            GhostButton("취소", Modifier.weight(1f), onDismiss)
        }
        // 방 로그 초기화 — 앱 방 설정과 동일 (로컬·서버·상대 로그 전부 삭제)
        Spacer(Modifier.height(DesktopDimens.gap4))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Line))
        Spacer(Modifier.height(DesktopDimens.gap3))
        var resetConfirm by remember { mutableStateOf(false) }
        var resetting by remember { mutableStateOf(false) }
        var resetResult by remember { mutableStateOf<String?>(null) }
        if (!resetConfirm) {
            GhostButton("방 로그 초기화", Modifier.fillMaxWidth()) { resetConfirm = true }
        } else {
            Text(
                "이 방의 모든 메시지가 삭제됩니다. 상대방의 로그도 함께 삭제되며, 되돌릴 수 없습니다.",
                fontSize = 11.sp, color = Tokens.Danger,
            )
            Spacer(Modifier.height(DesktopDimens.gap2))
            Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.gap2)) {
                DangerButton(if (resetting) "삭제 중…" else "전부 삭제", Modifier.weight(1f)) {
                    if (!resetting) {
                        resetting = true
                        onResetLogs { ok ->
                            resetting = false
                            resetConfirm = false
                            resetResult = if (ok) "방 로그를 초기화했습니다"
                            else "서버 삭제가 완료되지 않아 중단했습니다 — 네트워크 확인 후 다시 시도해주세요"
                        }
                    }
                }
                GhostButton("취소", Modifier.weight(1f)) { resetConfirm = false }
            }
        }
        resetResult?.let {
            Spacer(Modifier.height(DesktopDimens.gap2))
            Text(it, fontSize = 11.sp, color = Tokens.InkDim)
        }
    }
}

/** 메시지 편집 — 앱의 편집 다이얼로그와 동일 흐름 (여러 줄 입력 + 저장/취소) */
@Composable
internal fun EditMessageOverlay(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var body by remember { mutableStateOf(initial) }
    OverlayScaffold("메시지 편집", onDismiss) {
        BasicTextField(
            value = body,
            onValueChange = { body = it },
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Tokens.FieldBg)
                .border(1.dp, Tokens.Line, RoundedCornerShape(12.dp))
                .padding(horizontal = DesktopDimens.gap3, vertical = DesktopDimens.gap3),
            textStyle = TextStyle(color = Tokens.Ink, fontSize = 13.sp),
            cursorBrush = SolidColor(Tokens.SignatureRing),
            maxLines = 8,
        )
        Spacer(Modifier.height(DesktopDimens.gap4))
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopDimens.gap2)) {
            YellowButton("저장", Modifier.weight(1f)) {
                if (body.isNotBlank()) onSave(body)
            }
            GhostButton("취소", Modifier.weight(1f), onDismiss)
        }
    }
}

/** 캐릭터 추가 방식 선택 — 신규 작성이 위, 클립보드 코드가 아래 (모바일과 동일 순서) */
@Composable
internal fun AddProfileChoiceOverlay(
    onDismiss: () -> Unit,
    onEmpty: () -> Unit,
    /** true = 생성 성공, false = 클립보드에서 코드를 찾지 못함 */
    onClipboard: () -> Boolean,
) {
    var clipboardError by remember { mutableStateOf(false) }
    OverlayScaffold("캐릭터 추가", onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onEmpty)
                .padding(10.dp),
        ) {
            Text(
                "신규 캐릭터 작성",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Tokens.SignatureInk,
            )
            Text("이름과 색만 정해 새로 만들기", fontSize = 11.sp, color = Tokens.InkDim)
        }
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { clipboardError = !onClipboard() }
                .padding(10.dp),
        ) {
            Text(
                "클립보드 코드로 생성",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Tokens.SignatureInk,
            )
            Text(
                "복사해 둔 캐릭터 코드(JSON)의 이름·능력치를 값으로 자동 등록",
                fontSize = 11.sp, color = Tokens.InkDim,
            )
        }
        if (clipboardError) {
            Spacer(Modifier.height(DesktopDimens.gap2))
            Text("클립보드에서 캐릭터 코드를 찾지 못했습니다", fontSize = 11.sp, color = Tokens.Danger)
        }
        Spacer(Modifier.height(DesktopDimens.gap3))
        GhostButton("취소", Modifier.fillMaxWidth(), onDismiss)
    }
}

/** 앱 전체 글꼴 선택 — 모바일 FontSettingDialog와 동일 선택지, 즉시 반영·config.json 유지 */
@Composable
internal fun FontOverlay(current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    OverlayScaffold("앱 글꼴", onDismiss) {
        listOf(
            "system" to "시스템 기본",
            "gowun" to "고운 바탕 (명조)",
            "pretendard" to "프리텐다드 (고딕)",
        ).forEach { (value, label) ->
            val selected = current == value
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(value) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selected) "●" else "○",
                    color = if (selected) Tokens.SignatureRing else Tokens.InkDim,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    label, fontSize = 13.sp, color = Tokens.Ink,
                    fontFamily = when (value) {
                        "gowun" -> GowunBatang
                        "pretendard" -> Pretendard
                        else -> FontFamily.Default
                    },
                )
            }
        }
        Spacer(Modifier.height(DesktopDimens.gap4))
        GhostButton("닫기", Modifier.fillMaxWidth(), onDismiss)
    }
}
