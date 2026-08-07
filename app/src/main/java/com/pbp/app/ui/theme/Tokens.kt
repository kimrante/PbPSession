package com.pbp.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 디자인 토큰 (docs/ui-guidelines.md 2장).
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
    val cardBg: Color,        // 방 목록 카드 면 — 화면 코드의 다크 분기를 없애기 위한 토큰
    val chatBarBg: Color,     // 채팅 입력 바 면 (배경 위에 얹히므로 반투명)
    /** 배경 이미지 위 표시용 필·칩의 검정 스크림 (시스템 안내, 다이스, '이전 대화') */
    val scrim: Color,
    /** 스크림 위 글자 */
    val onScrim: Color,
    /** 상단 바가 배경 위에 얹힐 때의 스크림 — 라이트/다크 분기를 화면 코드에서 없앤다 */
    val barScrim: Color,
    /** 시그니처 옐로의 짙은 짝 — 로고 타일 그라데이션 전용 */
    val signatureDeep: Color,
    /** 잉크의 옅은 면 — 비활성 버튼 배경 */
    val inkFaint: Color,
    /** 비활성 버튼 위 글자 */
    val inkDisabled: Color,
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
    cardBg = Color(0x09FFFFFF),
    chatBarBg = Color(0xE0090C11),
    scrim = Color(0x59000000),
    onScrim = Color(0x99FFFFFF),
    barScrim = Color(0x73000000),
    signatureDeep = Color(0xFFEFB945),
    inkFaint = Color(0x14FFFFFF),
    inkDisabled = Color(0x57FFFFFF),
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
    cardBg = Color(0xFFFFFFFF),
    chatBarBg = Color(0xEDFFFFFF),
    scrim = Color(0x59000000),
    onScrim = Color(0x99FFFFFF),
    barScrim = Color(0x0F000000),
    signatureDeep = Color(0xFFEFB945),
    inkFaint = Color(0x1414191F),
    inkDisabled = Color(0x5714191F),
)

val LocalPbpColors = staticCompositionLocalOf { PbpDarkColors }

/**
 * 통일 스페이싱·반경 토큰 (docs/ui-guidelines.md 4장).
 * 여백은 4dp 그리드 6단계, 반경은 4단계만 사용한다 — 이 밖의 매직 넘버 금지.
 * 글자 크기는 18/15/13/11/10sp **5단계**를 리터럴로 쓴다 (9sp·12sp·14sp는 폐기).
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
    val rTail = 4.dp   // 말풍선 꼬리 쪽 모서리 — 다섯 곳에서 반복되던 리터럴

    // 고정 규격
    val appBarHeight = 56.dp // 상단 바 — 전 화면 동일
    val touchTarget = 40.dp  // 아이콘·전송 버튼
    val avatarChat = 38.dp   // 채팅 말풍선 아바타
    val avatarStrip = 36.dp  // 프로필 교체 스트립 아바타
    val avatarBar = 32.dp    // 상단 바의 오너 아바타 (PC와 같은 값)
    val avatarProfile = 92.dp // 프로필 편집 화면의 큰 사진 (오너·캐릭터 공통)
    val logoTile = 22.dp     // 상단 바 로고 타일 (앱 아이콘과 같은 d10)

    /**
     * 상단 바 중앙 타이틀 묶음이 좌우 버튼과 겹치지 않게 비워 두는 폭.
     * 좌우에 같은 값을 주므로 타이틀은 버튼 개수와 무관하게 화면 정중앙에 온다
     * (docs/mockups/final-design.html).
     */
    val titleInset = 96.dp      // 아이콘 버튼 2개 기준 — 채팅
    val titleInsetWide = 112.dp // 텍스트 버튼 2개 + 오너 아바타 — 방 목록

    /**
     * 버튼이 한쪽에 1개뿐인 상단 바용 인셋. 32(gap6)로 두면 부제가 버튼 밑으로
     * 파고든다 — 버튼 히트박스(40) + 가장자리(16)를 덮는 값.
     */
    val titleInsetNarrow = 56.dp

    // 규격 등재 (지시서 0-2) — 화면 코드의 매직 넘버를 여기로 올린다
    val bubbleMaxWidth = 240.dp // 말풍선 최대 폭
    val captureBarHeight = 70.dp // 캡처 하단 바 — 선택 상태가 바뀌어도 튀지 않게 고정

    /** 토글 1벌 — 잡담 토글과 캡처 옵션 토글이 같은 치수를 쓴다 */
    val toggleTrackW = 34.dp
    val toggleTrackH = 20.dp
    val toggleKnob = 16.dp

    /**
     * 인용 말풍선 전용 여백 — 장식 따옴표가 좌우로 튀어나온 만큼 본문을 안으로 민다.
     * 글리프 보정(9/5/+6)은 사용처 주석 참조.
     */
    val quotePadH = 26.dp
    val quotePadV = 14.dp

    /** 우측 프로필 전환 사이드바 폭 — 화면 320 − 스크림 스트립 56 (M3 모달 드로어 관례) */
    val drawerWidth = 264.dp

    /** 색 스와치와 그 간격 — 데스크톱과 같은 값 (P6) */
    val swatch = 26.dp
    val swatchGap = 4.dp

    /** 색 피커 규격 — 데스크톱과 같은 8-그리드 값 (P6) */
    val pickerBoard = 140.dp
    val pickerKnob = 16.dp
    val pickerGap = 8.dp

    /** 라벨 자간 — 대문자·코드성 라벨 1종으로 통일 */
    val labelTracking = 1.sp
}

/** 프리셋 팔레트 (목업 03·04 화면) */
object PbpPalette {
    // 팔레트 값·변환은 :shared Palette가 단일 출처 — 내보내기 색과 갈라지지 않게 (리뷰 A3)
    val themePresets = com.pbp.shared.Palette.themePresets
    val namePresets = com.pbp.shared.Palette.namePresets
    val bubblePresets = com.pbp.shared.Palette.bubblePresets
    val textPresets = com.pbp.shared.Palette.textPresets
    val gmQuoteBubble = com.pbp.shared.Palette.gmQuoteBubble

    fun nameColorForLight(argb: Long): Long = com.pbp.shared.Palette.nameColorForLight(argb)

    /**
     * 방 배경 프리셋 (key → 세로 그라데이션 색 쌍). 갤러리 이미지는 파일 경로를 key로 쓴다.
     * 리터럴을 복제해 두면 shared 값이 바뀔 때 모바일만 조용히 갈라진다 (C4).
     */
    val backgroundPresets = com.pbp.shared.Palette.backgroundPresets

    const val DEFAULT_BACKGROUND = com.pbp.shared.Protocol.DEFAULT_BACKGROUND
    const val DEFAULT_THEME_COLOR = com.pbp.shared.Protocol.DEFAULT_THEME_COLOR
}
