package com.pbp.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.pbp.app.R

/**
 * 서체 (스펙 2장): 서술(내레이션) = Gowun Batang(명조),
 * 대사·UI = Noto Sans KR — Android 기본 한글 서체(Noto Sans CJK KR)를 그대로 사용한다.
 *
 * APK 최적화: 볼드 페이스는 번들하지 않는다 (8MB 절감).
 * 굵은 명조가 필요한 곳은 플랫폼이 합성 볼드로 렌더링한다.
 */
val GowunBatang = FontFamily(
    Font(R.font.gowun_batang_regular, FontWeight.Normal),
)
