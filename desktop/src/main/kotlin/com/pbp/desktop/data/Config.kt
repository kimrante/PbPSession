package com.pbp.desktop.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.util.UUID

/** 로컬 캐릭터 프로필 — 모바일과 같은 개념 (이름/이모지/이름색/말풍선색) */
data class Profile(
    val name: String,
    val emoji: String = "🙂",
    val nameColor: Long = 0xFFFFC46B,
    val bubbleColor: Long = 0xFFFFD9A8,
    val isGm: Boolean = false,
)

data class JoinedRoom(
    val remoteId: String,
    var name: String,
    var icon: String,
    var inviteCode: String?,
    var themeColor: Long,
    var backgroundKey: String,
    val isMaster: Boolean,
    var activeProfileIndex: Int = 0,
)

/** ~/.pbp-desktop/config.json — 기기 ID, 프로필, 참여한 방 목록 */
class AppConfig private constructor(
    val deviceId: String,
    val profiles: MutableList<Profile>,
    val rooms: MutableList<JoinedRoom>,
) {
    companion object {
        private val file = File(System.getProperty("user.home"), ".pbp-desktop/config.json")
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        fun load(): AppConfig {
            val loaded = runCatching {
                gson.fromJson(file.readText(Charsets.UTF_8), Saved::class.java)
            }.getOrNull()
            return AppConfig(
                deviceId = loaded?.deviceId ?: "desktop-${UUID.randomUUID()}",
                profiles = (loaded?.profiles ?: defaultProfiles()).toMutableList(),
                rooms = (loaded?.rooms ?: emptyList()).toMutableList(),
            ).also { it.save() }
        }

        private fun defaultProfiles() = listOf(
            Profile(name = "GM", emoji = "敍", nameColor = 0xFFFFD972, bubbleColor = 0xFFE7E2D4, isGm = true),
            Profile(name = "플레이어", emoji = "🙂"),
        )
    }

    private data class Saved(
        val deviceId: String?,
        val profiles: List<Profile>?,
        val rooms: List<JoinedRoom>?,
    )

    @Synchronized
    fun save() {
        file.parentFile?.mkdirs()
        file.writeText(
            gson.toJson(Saved(deviceId, profiles.toList(), rooms.toList())),
            Charsets.UTF_8,
        )
    }
}
