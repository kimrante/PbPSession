package com.pbp.desktop.ui

import androidx.compose.ui.unit.dp

/**
 * 데스크톱 치수 토큰 — Android PbpDimens와 의미가 겹치는 값 + PC 전용 규격 (리뷰 D1).
 * 일회성 패딩까지 옮기지는 않는다; 의미 있고 반복되는 것만.
 */
object DesktopDimens {
    /** 상단 바 높이 (모바일과 동일 규격) */
    val appBar = 56.dp

    /** 채팅 아바타 / 프로필 스트립 아바타 */
    val avatarChat = 38.dp
    val avatarStrip = 36.dp

    // ── PC 전용 (trpg-app-mockup-pc-light.html) ──
    /** 좌측 사이드바 고정 폭 */
    val sidebar = 280.dp
    /** 채팅 본문 최대 폭 (초광폭에서 말풍선이 늘어지지 않게) */
    val contentMax = 720.dp
    /** 말풍선 최대 폭 */
    val bubbleMax = 420.dp
    /** 오버레이(다이얼로그) 폭 */
    val overlay = 430.dp
    /** 채팅 영역 가장자리 (모바일 16 → PC 24) */
    val edge = 24.dp

    // ── 이미지 저장 크기(px) — 모바일 ImageSizes와 같은 값 ──
    const val PROFILE_PX = 512
    const val BACKGROUND_PX = 1600
}

/**
 * 폴링·타이밍 상수 (리뷰 D2) — perf 리뷰에서 의도적으로 튜닝한 값들이라
 * 이름을 붙여 튜닝 이력이 코드에 보이게 한다.
 */
object DesktopTiming {
    /** 최근 송수신이 이 시간 안이면 '활성'으로 보고 빠르게 폴링 */
    const val ACTIVE_WINDOW_MS = 120_000L
    /** 활성 / 유휴 / 창 미포커스 폴 주기 */
    const val ACTIVE_POLL_MS = 2_500L
    const val IDLE_POLL_MS = 20_000L
    const val UNFOCUSED_POLL_MS = 30_000L
    /** 방 메타(테마·배경·이름) 폴 주기 */
    const val META_POLL_MS = 60_000L
    /** 방 캐시 파일 저장 스로틀 */
    const val CACHE_SAVE_THROTTLE_MS = 30_000L
    /** 긴 대기 중 전송·포커스 변화를 감지하는 단위 */
    const val WAKE_STEP_MS = 1_000L
    /** 내 설정 변경 직후 폴링이 옛 서버 값으로 되돌리지 않도록 하는 유예 */
    const val META_FREEZE_MS = 15_000L
}
