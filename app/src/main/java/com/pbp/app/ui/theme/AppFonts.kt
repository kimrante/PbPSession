package com.pbp.app.ui.theme

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily

/**
 * 앱 전체 본문 글꼴 설정. 방 목록의 'Aa' 버튼에서 바꾸고 즉시 반영된다.
 * (GM 서술 문단은 설정과 무관하게 항상 고운 바탕 명조를 유지한다.)
 */
object AppFonts {
    const val SYSTEM = "system" // 시스템 기본
    const val GOWUN = "gowun"   // 고운 바탕(명조)
    const val PRETENDARD = "pretendard" // 프리텐다드(고딕)

    var choice by mutableStateOf(SYSTEM)
        private set

    private fun prefs(context: Context) =
        context.getSharedPreferences("pbp-settings", Context.MODE_PRIVATE)

    fun load(context: Context) {
        choice = prefs(context).getString("appFont", SYSTEM) ?: SYSTEM
    }

    fun set(context: Context, value: String) {
        choice = value
        prefs(context).edit().putString("appFont", value).apply()
    }

    /** 현재 설정의 기본 FontFamily. null이면 시스템 기본 */
    val fontFamily: FontFamily?
        get() = when (choice) {
            GOWUN -> GowunBatang
            PRETENDARD -> Pretendard
            else -> null
        }
}

/** Typography의 모든 스타일에 기본 글꼴을 입힌다 */
fun typographyWith(fontFamily: FontFamily?): Typography {
    val base = Typography()
    if (fontFamily == null) return base
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = fontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = fontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = fontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = fontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = fontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = fontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = fontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = fontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = fontFamily),
    )
}
