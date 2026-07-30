package com.pbp.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 캡처 이미지 설정 — 지금은 '배경 포함' 하나.
 * [OwnerProfile]과 같은 SharedPreferences·같은 규칙을 쓴다.
 */
object CaptureSettings {

    /** 켜면 방 배경 + 가독성 베일, 끄면 종이 톤 */
    var withBackground by mutableStateOf(true)
        private set

    private fun prefs(context: Context) =
        context.getSharedPreferences("pbp-settings", Context.MODE_PRIVATE)

    fun load(context: Context) {
        withBackground = prefs(context).getBoolean("captureWithBackground", true)
    }

    fun set(context: Context, value: Boolean) {
        withBackground = value
        prefs(context).edit().putBoolean("captureWithBackground", value).apply()
    }
}
