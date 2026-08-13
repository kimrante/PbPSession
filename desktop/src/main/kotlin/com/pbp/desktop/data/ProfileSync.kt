package com.pbp.desktop.data

import com.google.gson.JsonObject

/**
 * 캐릭터 프로필을 계정에 보관해 기기 사이로 옮긴다 (모바일 ProfileSync와 같은 문서).
 *
 *   users/{uid}/profiles/{characterId}
 *
 * 상대에게는 보이지 않는다 — 판정 후보 목록에 나가는 요약은 방의 members 문서에 따로 있다.
 * 같은 프로필을 두 기기에서 고쳤으면 **나중에 고친 쪽**이 남는다(updatedAt).
 *
 * 프로필은 방에 속하므로, 이 PC가 들고 있는 방의 것만 자리를 잡을 수 있다.
 */
internal object ProfileSyncKeys {
    const val NAME = "name"
    const val EMOJI = "emoji"
    const val NAME_COLOR = "nameColor"
    const val BUBBLE_COLOR = "bubbleColor"
    const val TEXT_COLOR = "textColor"
    const val IS_GM = "isGm"
    const val ROOM_ID = "roomId"
    const val STATS = "stats"
    const val AVATAR_ID = "avatarId"
    const val UPDATED_AT = "updatedAt"
}

/** 계정에서 읽어 온 프로필 한 건 */
internal data class RemoteProfile(
    val characterId: String,
    val name: String,
    val emoji: String,
    val nameColor: Long?,
    val bubbleColor: Long?,
    val textColor: Long?,
    val isGm: Boolean,
    /** 방 전용이면 그 방의 원격 id. PC는 이 값이 있는 프로필을 가져오지 않는다 */
    val roomId: String?,
    val stats: Map<String, String>,
    val avatarId: String?,
    val updatedAt: Long,
)

/**
 * 능력치는 **공용 규격**(`com.pbp.shared.ProfileStats`)으로 주고받는다.
 *
 * 예전에는 여기서 "이름=값" 줄바꿈 형식을 따로 쓰고 있었다. 폰은 공용 규격으로
 * 올리므로 서로 읽지 못했고, PC가 빈 값으로 되받아 저장한 뒤 그것을 다시 올려
 * **폰의 캐릭터 시트까지 지웠다.** 규격은 한 곳에만 있어야 한다.
 */
internal object ProfileStatsCodec {
    fun encode(stats: Map<String, String>): String =
        com.pbp.shared.ProfileStats.encode(stats.toList())

    fun decode(raw: String): Map<String, String> =
        com.pbp.shared.ProfileStats.decode(raw).toMap()
}

/** Firestore 문서 → 프로필 */
internal fun parseRemoteProfile(id: String, fields: JsonObject?): RemoteProfile? {
    if (fields == null) return null
    fun str(name: String): String? =
        runCatching { fields.getAsJsonObject(name)?.get("stringValue")?.asString }.getOrNull()
    fun long(name: String): Long? = runCatching {
        fields.getAsJsonObject(name)?.get("integerValue")?.asString?.toLongOrNull()
    }.getOrNull()
    fun bool(name: String): Boolean = runCatching {
        fields.getAsJsonObject(name)?.get("booleanValue")?.asBoolean ?: false
    }.getOrDefault(false)

    return RemoteProfile(
        characterId = id,
        name = str(ProfileSyncKeys.NAME) ?: return null,
        emoji = str(ProfileSyncKeys.EMOJI) ?: "",
        nameColor = long(ProfileSyncKeys.NAME_COLOR),
        bubbleColor = long(ProfileSyncKeys.BUBBLE_COLOR),
        textColor = long(ProfileSyncKeys.TEXT_COLOR),
        isGm = bool(ProfileSyncKeys.IS_GM),
        roomId = str(ProfileSyncKeys.ROOM_ID),
        stats = ProfileStatsCodec.decode(str(ProfileSyncKeys.STATS).orEmpty()),
        avatarId = str(ProfileSyncKeys.AVATAR_ID),
        updatedAt = long(ProfileSyncKeys.UPDATED_AT) ?: 0L,
    )
}
