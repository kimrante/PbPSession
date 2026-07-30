package com.pbp.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 오너 프로필 — 세션 캐릭터와 별개인 '플레이어 본인' 프로필 (이미지·이름·컬러).
 * - 잡담(isOoc)은 어떤 캐릭터(GM 포함)가 활성이든 항상 이 이름·컬러로 나간다.
 * - 방에 처음 참여하면 "'이름' 님이 참여하셨습니다." 인사를 남긴다.
 * - 채팅 프로필 스트립에는 나타나지 않으며, 이 프로필로 전환해 발화할 수 없다.
 * 미설정이면 방 목록 진입 시 설정 팝업이 뜬다.
 */
object OwnerProfile {
    const val DEFAULT_COLOR = 0xFFFFD9A8

    var name by mutableStateOf("")
        private set
    var color by mutableStateOf(DEFAULT_COLOR)
        private set
    var imagePath by mutableStateOf<String?>(null)
        private set

    /** 말풍선 안 글씨색. null이면 테마 기본 잉크 */
    var textColor by mutableStateOf<Long?>(null)
        private set

    val isSet: Boolean get() = name.isNotBlank()

    private fun prefs(context: Context) =
        context.getSharedPreferences("pbp-settings", Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = prefs(context)
        name = p.getString("ownerName", "") ?: ""
        color = p.getLong("ownerColor", DEFAULT_COLOR)
        imagePath = p.getString("ownerImagePath", null)
        textColor = if (p.contains("ownerTextColor")) p.getLong("ownerTextColor", 0) else null
    }

    fun set(
        context: Context,
        name: String,
        color: Long,
        imagePath: String?,
        textColor: Long? = null,
    ) {
        this.name = name.trim()
        this.color = color
        this.imagePath = imagePath
        this.textColor = textColor
        prefs(context).edit()
            .putString("ownerName", this.name)
            .putLong("ownerColor", color)
            .putString("ownerImagePath", imagePath)
            .apply {
                if (textColor != null) putLong("ownerTextColor", textColor)
                else remove("ownerTextColor")
            }
            .apply()
    }
}
