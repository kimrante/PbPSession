package com.pbp.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 시나리오 뷰어의 보기 설정 (V2.5).
 * [CaptureSettings]와 같은 SharedPreferences·같은 규칙을 쓴다 — 기기에 남는다.
 */
object ScenarioSettings {

    /** 표시·이동 단위를 문단으로 */
    var paragraphMode by mutableStateOf(false)
        private set

    private fun prefs(context: Context) =
        context.getSharedPreferences("pbp-settings", Context.MODE_PRIVATE)

    fun load(context: Context) {
        paragraphMode = prefs(context).getBoolean("scenarioParagraphMode", false)
    }

    fun setParagraphMode(context: Context, value: Boolean) {
        paragraphMode = value
        prefs(context).edit().putBoolean("scenarioParagraphMode", value).apply()
    }
}
