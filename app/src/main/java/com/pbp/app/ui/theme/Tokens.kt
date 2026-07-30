package com.pbp.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 디자인 토큰 (docs/PbP-design-spec.md 2장).
 * 다크/화이트 모드는 이 토큰 스왑만으로 구성된다.
 */
@Immutable
data class PbpColors(
    val isDark: Boolean,
    val bg: Color,            // 베이스 배경
    val panel: Color,         // 패널
    val panel2: Color,
    val line: Color,          // 구분선
    val ink: Color,           // 본문 잉크
    val inkDim: Color,        // 보조 텍스트
    val inkSub: Color,        // 상단 바 부제 — inkDim보다 진해 배경 위에서도 또렷
    val signature: Color,     // 시그니처 옐로 (앱 타이틀 강조는 titleAccent)
    val signatureInk: Color,  // 밝은 배경 위 옐로 '텍스트'용 — 화이트 모드에선 진한 골드
    val danger: Color,        // 파괴적 동작 (삭제)
    val titleAccent: Color,   // "PbP" 강조색 — 화이트 모드에선 잉크 블랙
    val themeDefault: Color,  // 방 테마 컬러 기본값(새벽 하늘)
    val chatterBubble: Color, // 잡담 말풍선
    val chatterInk: Color,
    val bubbleInk: Color,     // 말풍선 안 글자색
    val onSignature: Color,   // 시그니처 옐로 면 위의 잉크 (리뷰 D3)
    val statBlue: Color,      // 캐릭터 값 치환 표시(파랑)
    val narrInk: Color,       // GM 서술 문단 글자색
    val narrBg: Color,        // GM 서술 문단 배경
    val veilTop: Color,       // 배경 이미지 가독성 베일
    val veilMid: Color,
)

val PbpDarkColors = PbpColors(
    isDark = true,
    bg = Color(0xFF0D1117),
    panel = Color(0xFF161C24),
    panel2 = Color(0xFF1D2530),
    line = Color(0x14FFFFFF),
    ink = Color(0xFFE8ECF2),
    inkDim = Color(0xFF8B95A5),
    inkSub = Color(0xB8E8ECF2),
    signature = Color(0xFFFFD972),
    signatureInk = Color(0xFFFFD972),
    danger = Color(0xFFFF6B6B),
    titleAccent = Color(0xFFFFD972),
    themeDefault = Color(0xFF8EC5E8),
    chatterBubble = Color(0x24FFFFFF),
    chatterInk = Color(0x9EFFFFFF),
    bubbleInk = Color(0xFF10151C),
    onSignature = Color(0xFF1A1A1A),
    statBlue = Color(0xFF3B82F6),
    narrInk = Color(0xFFF0EAD8),
    narrBg = Color(0x73060A0E),
    veilTop = Color(0x9E0A0E14),
    veilMid = Color(0x470A0E14),
)

val PbpLightColors = PbpColors(
    isDark = false,
    bg = Color(0xFFF4F2EC),
    panel = Color(0xFFFFFFFF),
    panel2 = Color(0xFFF7F4EC),
    line = Color(0x1414191F),
    ink = Color(0xFF23272E),
    inkDim = Color(0xFF6E7683),
    inkSub = Color(0xB814191F),
    signature = Color(0xFFFFD05C),
    signatureInk = Color(0xFFA3781A),
    danger = Color(0xFFC94F4F),
    titleAccent = Color(0xFF23272E),
    themeDefault = Color(0xFF5F9EC7),
    chatterBubble = Color(0x0F141920),
    chatterInk = Color(0x9923272E),
    bubbleInk = Color(0xFF10151C),
    onSignature = Color(0xFF1A1A1A),
    statBlue = Color(0xFF3B82F6),
    narrInk = Color(0xFF3D3628),
    narrBg = Color(0xB3FFFFFF),
    veilTop = Color(0x8CF4F2EC),
    veilMid = Color(0x40F4F2EC),
)

val LocalPbpColors = staticCompositionLocalOf { PbpDarkColors }

/**
 * 통일 스페이싱·반경 토큰 (docs/mockups/trpg-app-mockup-v2.html 0장).
 * 여백은 4dp 그리드 6단계, 반경은 4단계만 사용한다 — 이 밖의 매직 넘버 금지.
 * 글자 크기는 18/15/13/11/10sp 5단계(+배지 한정 9sp)를 리터럴로 쓴다.
 *
 * **본문 스케일 밖의 글리프**: 텍스트가 아니라 아이콘·장식으로 쓰는 문자는
 * 이 스케일을 따르지 않는다 — 뒤로 '←' 20 · 인용 따옴표 24 · FAB '＋' 24 ·
 * 빈 상태 '🎲' 40 · 초대 코드 대형 표기 32. 이 목록 밖의 새 크기는 금지
 * (리뷰 E: 문서가 현실과 어긋나지 않도록 예외를 여기에 명시).
 */
object PbpDimens {
    // 여백 스케일 — 이름은 gap*: 텍스트 크기 단위 `.sp`와 헷갈리지 않게 (리뷰 E)
    val gap1 = 4.dp   // 밀착: 이름↔말풍선, 연속 말풍선 간격
    val gap2 = 8.dp   // 요소 기본 간격, 라벨↔필드
    val gap3 = 12.dp  // 카드 사이, 메시지 그룹 사이
    val gap4 = 16.dp  // ★ 모든 화면 가장자리, 카드 내부
    val gap5 = 24.dp  // 섹션 사이
    val gap6 = 32.dp  // 화면 하단 여유

    // 반경 스케일 (pill은 999.dp 리터럴 유지)
    val rCell = 12.dp  // 그리드 셀, 입력 필드, 썸네일
    val rCard = 16.dp  // 카드, 말풍선, 패널
    val rSheet = 20.dp // 다이얼로그, 시트

    // 고정 규격
    val appBarHeight = 56.dp // 상단 바 — 전 화면 동일
    val touchTarget = 40.dp  // 아이콘·전송 버튼
    val avatarChat = 38.dp   // 채팅 말풍선 아바타
    val avatarStrip = 36.dp  // 프로필 교체 스트립 아바타
    val logoTile = 22.dp     // 상단 바 로고 타일 (앱 아이콘과 같은 d10)

    /**
     * 상단 바 중앙 타이틀 묶음이 좌우 버튼과 겹치지 않게 비워 두는 폭.
     * 좌우에 같은 값을 주므로 타이틀은 버튼 개수와 무관하게 화면 정중앙에 온다
     * (docs/mockups/mockup-chat-header.html · mockups/mockup-home-header.html).
     */
    val titleInset = 96.dp      // 아이콘 버튼 2개 기준 — 채팅
    val titleInsetWide = 112.dp // 텍스트 버튼 2개 + 오너 아바타 — 방 목록
}

/** 프리셋 팔레트 (목업 03·04 화면) */
object PbpPalette {
    /** 방 테마 컬러 3종 — 이름·말풍선과 같은 개수로 (+ 커스텀은 UI에서 별도 처리) */
    val themePresets = listOf(
        0xFF8EC5E8 to "새벽 하늘",
        0xFFC9A7E8 to "라일락",
        0xFFE8B48E to "호박등",
    )

    // 팔레트 값·변환은 :shared Palette가 단일 출처 — 내보내기 색과 갈라지지 않게 (리뷰 A3)
    val namePresets = com.pbp.shared.Palette.namePresets
    val bubblePresets = com.pbp.shared.Palette.bubblePresets
    val gmQuoteBubble = com.pbp.shared.Palette.gmQuoteBubble

    fun nameColorForLight(argb: Long): Long = com.pbp.shared.Palette.nameColorForLight(argb)

    /** 방 배경 프리셋 (key → 세로 그라데이션 색 쌍). 갤러리 이미지는 파일 경로를 key로 쓴다. */
    val backgroundPresets = linkedMapOf(
        "preset_lighthouse" to (0xFF26374D to 0xFF101A28),
        "preset_lilac" to (0xFF33253F to 0xFF141020),
        "preset_desert" to (0xFF4F4A2C to 0xFF211D12),
        "preset_forest" to (0xFF173226 to 0xFF0A120E),
        "preset_ember" to (0xFF3A1F22 to 0xFF140B0C),
    )

    const val DEFAULT_BACKGROUND = com.pbp.shared.Protocol.DEFAULT_BACKGROUND
    const val DEFAULT_THEME_COLOR = com.pbp.shared.Protocol.DEFAULT_THEME_COLOR
}
