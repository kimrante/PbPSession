package com.pbp.app.sync

import com.google.firebase.firestore.FirebaseFirestore
import com.pbp.app.data.AppDatabase
import com.pbp.app.data.CharacterProfile
import kotlinx.coroutines.tasks.await

/**
 * 캐릭터 프로필을 계정에 보관해 기기 사이로 옮긴다.
 *
 *   users/{uid}/profiles/{characterId}
 *
 * **상대에게는 보이지 않는다.** 판정 요청 목록에 나가는 요약(이름·값 이름)은 방의
 * members 문서에 따로 올라가고, 여기 있는 것은 값(숫자)까지 포함한 내 사본이다.
 *
 * 같은 프로필을 두 기기에서 고쳤을 때는 **나중에 고친 쪽**이 남는다(updatedAt).
 * 지운 프로필은 문서도 지운다 — 다만 아직 내려받지 않은 기기가 그 프로필을 들고
 * 있다가 다시 올릴 수는 있다. 2인·소수 프로필 규모에서 받아들이는 한계다.
 */
internal class ProfileSync(
    private val db: AppDatabase,
    private val firestore: () -> FirebaseFirestore,
    private val avatars: AvatarStore,
) {
    private fun collection(uid: String) =
        firestore().collection("users").document(uid).collection("profiles")

    /** 프로필 하나를 계정에 올린다. 이미지가 있으면 함께 올려 id로 가리킨다 */
    suspend fun push(uid: String, profile: CharacterProfile) {
        runCatching {
            val avatarId = profile.imagePath?.let { path ->
                runCatching { avatars.ensureUploadedForUser(uid, path) }.getOrNull()
            }
            // 방 전용 프로필은 그 방의 **원격 id**로 가리킨다 — 로컬 방 번호는 기기마다 다르다
            val roomRemoteId = profile.roomId?.let { db.roomDao().get(it)?.remoteId }
            collection(uid).document(profile.characterId).set(
                mapOf(
                    "name" to profile.name,
                    "emoji" to profile.emoji,
                    "nameColor" to profile.nameColor,
                    "bubbleColor" to profile.bubbleColor,
                    "textColor" to profile.textColor,
                    "isGm" to profile.isGm,
                    "roomId" to roomRemoteId,
                    "stats" to profile.stats,
                    "avatarId" to avatarId,
                    "updatedAt" to profile.updatedAt,
                )
            ).await()
        }.onFailure { android.util.Log.w("PbpSync", "프로필 올리기 실패 ${profile.name}", it) }
    }

    suspend fun delete(uid: String, characterId: String) {
        runCatching { collection(uid).document(characterId).delete().await() }
    }

    /** 아직 한 번도 올리지 않은 프로필을 모두 올린다 (계정을 붙인 직후 한 번) */
    suspend fun pushAll(uid: String) {
        db.profileDao().all().forEach { profile ->
            val stamped = if (profile.updatedAt == 0L) {
                profile.copy(updatedAt = System.currentTimeMillis())
                    .also { db.profileDao().update(it) }
            } else profile
            push(uid, stamped)
        }
    }

    /**
     * 계정에 있는 프로필을 이 기기로 가져온다.
     *
     * 방 전용 프로필은 **그 방이 이 기기에 있을 때만** 만든다 — 없으면 방을 먼저
     * 가져온 뒤 다음 동기화에서 따라온다(방 없는 프로필은 어디에도 보이지 않는다).
     *
     * @return 새로 만들거나 갱신한 프로필 수
     */
    suspend fun pull(uid: String): Int = runCatching {
        val remote = collection(uid).get().await().documents
        var changed = 0
        remote.forEach { doc ->
            val characterId = doc.id
            val updatedAt = doc.getLong("updatedAt") ?: 0L
            val local = db.profileDao().byCharacterId(characterId)
            if (local != null && local.updatedAt >= updatedAt) return@forEach

            val roomRemoteId = doc.getString("roomId")
            val roomId = if (roomRemoteId == null) null else {
                db.roomDao().findByRemoteId(roomRemoteId)?.id ?: return@forEach
            }
            // 이미지는 실패해도 프로필은 살린다 — 이름·값이 훨씬 중요하다
            val imagePath = doc.getString("avatarId")?.let { avatarId ->
                runCatching { avatars.resolveForUser(uid, avatarId) }.getOrNull()
            } ?: local?.imagePath

            val merged = (local ?: CharacterProfile(characterId = characterId, name = "")).copy(
                characterId = characterId,
                name = doc.getString("name") ?: local?.name ?: "이름 없음",
                emoji = doc.getString("emoji") ?: "🙂",
                imagePath = imagePath,
                isGm = doc.getBoolean("isGm") ?: false,
                roomId = roomId,
                nameColor = doc.getLong("nameColor"),
                bubbleColor = doc.getLong("bubbleColor"),
                textColor = doc.getLong("textColor"),
                stats = doc.getString("stats") ?: "",
                updatedAt = updatedAt,
            )
            if (local == null) db.profileDao().insert(merged) else db.profileDao().update(merged)
            changed++
        }
        changed
    }.getOrElse {
        android.util.Log.w("PbpSync", "프로필 내려받기 실패", it)
        0
    }
}
