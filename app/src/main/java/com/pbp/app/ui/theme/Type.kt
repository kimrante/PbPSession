package com.pbp.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.pbp.app.R

/**
 * 서체 (스펙 2장): 서술(내레이션) = Gowun Batang(명조),
 * 대사·UI = Noto Sans KR — Android 기본 한글 서체(Noto Sans CJK KR)를 그대로 사용한다.
 */
val GowunBatang = FontFamily(
    Font(R.font.gowun_batang_regular, FontWeight.Normal),
    Font(R.font.gowun_batang_bold, FontWeight.Bold),
)
