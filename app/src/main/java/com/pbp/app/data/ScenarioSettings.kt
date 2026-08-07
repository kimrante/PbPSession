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

    /**
     * 문단을 띄워 두고 **읽은 문장까지 진하게**. 문단 보기에서만 뜻이 있다 —
     * 문장 하나만 띄우는 모드에서는 진하게 할 "앞부분"이 없다.
     */
    var boldRead by mutableStateOf(true)
        private set

    /** 표시·이동 단위를 문단으로 */
    var paragraphMode by mutableStateOf(false)
        private set

    private fun prefs(context: Context) =
        context.getSharedPreferences("pbp-settings", Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = prefs(context)
        boldRead = p.getBoolean("scenarioBoldRead", true)
        paragraphMode = p.getBoolean("scenarioParagraphMode", false)
    }

    fun setBoldRead(context: Context, value: Boolean) {
        boldRead = value
        prefs(context).edit().putBoolean("scenarioBoldRead", value).apply()
    }

    fun setParagraphMode(context: Context, value: Boolean) {
        paragraphMode = value
        prefs(context).edit().putBoolean("scenarioParagraphMode", value).apply()
    }
}
