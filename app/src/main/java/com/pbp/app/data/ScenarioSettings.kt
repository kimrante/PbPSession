package com.pbp.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 시나리오 뷰어의 보기 설정과 **읽던 자리** (V2.5).
 * [CaptureSettings]와 같은 SharedPreferences·같은 규칙을 쓴다 — 기기에 남는다.
 *
 * 보기 단위는 읽는 습관이라 앱 전체에 하나지만, **문서와 자리는 방마다 따로** 둔다 —
 * 캠페인이 둘이면 시나리오도 둘이고, 하나로 두면 방을 옮길 때마다 서로를 덮어쓴다.
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

    // ── 방마다 기억하는 문서와 자리 ────────────────────────

    private fun linkKey(roomId: Long) = "scenarioLink_$roomId"
    private fun indexKey(roomId: Long) = "scenarioIndex_$roomId"

    /** 마지막으로 읽던 문서 링크. 없으면 null — 패널은 링크 입력으로 시작한다 */
    fun savedLink(context: Context, roomId: Long): String? =
        prefs(context).getString(linkKey(roomId), null)?.takeIf { it.isNotBlank() }

    /** 그 문서에서 읽던 자리(문장 번호). 문서가 바뀌었으면 호출부가 범위로 자른다 */
    fun savedIndex(context: Context, roomId: Long): Int =
        prefs(context).getInt(indexKey(roomId), 0)

    /** 문서를 열거나 자리를 옮길 때마다 남긴다 */
    fun rememberPlace(context: Context, roomId: Long, link: String, index: Int) {
        prefs(context).edit()
            .putString(linkKey(roomId), link)
            .putInt(indexKey(roomId), index)
            .apply()
    }

    /** "다른 문서로 바꾸기" — 기억도 함께 지운다. 안 지우면 다음에 옛 문서가 다시 열린다 */
    fun forgetPlace(context: Context, roomId: Long) {
        prefs(context).edit().remove(linkKey(roomId)).remove(indexKey(roomId)).apply()
    }
}
