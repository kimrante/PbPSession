package com.pbp.app.data

import androidx.room.withTransaction
import com.pbp.app.dice.DiceBot
import com.pbp.app.sync.SyncManager
import com.pbp.app.ui.theme.PbpPalette

class PbpRepository(private val db: AppDatabase) {

    /** 공유된 방의 메시지를 Firestore로 밀어낼 때 사용. PbpApp에서 주입한다. */
    var syncManager: SyncManager? = null

    fun observeRooms() = db.roomDao().observeAll()
    fun observeRoom(id: Long) = db.roomDao().observe(id)
    fun observeLastMessages() = db.messageDao().observeLastPerRoom()
    fun observeUnreadCounts() = db.messageDao().observeUnreadCounts()
    fun observeMessages(roomId: Long) = db.messageDao().observeForRoom(roomId)
    fun observeLatestMessages(roomId: Long, limit: Int) =
        db.messageDao().observeLatestForRoom(roomId, limit)
    suspend fun allMessages(roomId: Long) = db.messageDao().listForRoom(roomId)
    fun observeProfilesForRoom(roomId: Long) = db.profileDao().observeForRoom(roomId)
    fun observeGlobalProfiles() = db.profileDao().observeGlobal()

    /** 방을 만들고, 그 방에 귀속된 GM 프로필을 함께 생성해 기본 발화 프로필로 지정한다. */
    suspend fun createRoom(
        name: String,
        icon: String,
        isMaster: Boolean = true,
        themeColor: Long = PbpPalette.DEFAULT_THEME_COLOR,
        backgroundKey: String = PbpPalette.DEFAULT_BACKGROUND,
        rule: String = com.pbp.app.dice.Rules.COC7,
    ): Long = db.withTransaction {
        val roomId = db.roomDao().insert(
            ChatRoom(
                name = name,
                icon = icon,
                createdAt = System.currentTimeMillis(),
                isMaster = isMaster,
                themeColor = themeColor,
                backgroundKey = backgroundKey,
                rule = rule,
            )
        )
        val gmId = db.profileDao().insert(
            CharacterProfile(
                name = "GM",
                emoji = "", // 문자 없이 컬러만 있는 아바타
                isGm = true,
                roomId = roomId,
                nameColor = 0xFF000000, // 검정 이름색
                bubbleColor = PbpPalette.gmQuoteBubble,
            )
        )
        db.roomDao().setActiveProfile(roomId, gmId)
        roomId
    }

    suspend fun deleteRoom(room: ChatRoom) {
        syncManager?.detach(room.id)
        db.roomDao().delete(room)
    }

    /** 방 테마 컬러 변경(누구나 가능) — 공유 방이면 상대에게 실시간 전파 */
    suspend fun setThemeColor(roomId: Long, color: Long) {
        db.roomDao().setThemeColor(roomId, color)
        pushRoomSettings(roomId)
    }

    /** 방 배경(프리셋 key 또는 파일 경로) 변경(누구나 가능) — 공유 방이면 상대에게 실시간 전파 */
    suspend fun setBackground(roomId: Long, key: String) {
        db.roomDao().setBackground(roomId, key)
        pushRoomSettings(roomId)
    }

    private suspend fun pushRoomSettings(roomId: Long) {
        val room = db.roomDao().get(roomId) ?: return
        val remoteId = room.remoteId ?: return
        syncManager?.pushRoomSettings(remoteId, room.themeColor, room.backgroundKey)
    }

    /** 방에 들어왔을 때 호출 — 미확인 배지·푸시 기준 시각 갱신 */
    suspend fun markRead(roomId: Long) = db.roomDao().setLastReadAt(roomId, System.currentTimeMillis())

    suspend fun switchProfile(roomId: Long, profile: CharacterProfile) {
        val message = Message(
            roomId = roomId,
            type = MessageType.SYSTEM,
            body = "프로필을 '${profile.name}'(으)로 전환했습니다",
            createdAt = System.currentTimeMillis(),
        )
        var inserted: Message? = null
        db.withTransaction {
            db.roomDao().setActiveProfile(roomId, profile.id)
            inserted = message.copy(id = db.messageDao().insert(message))
        }
        inserted?.let { pushIfSynced(roomId, listOf(it)) }
    }

    /**
     * 메시지를 보낸다. 발신 캐릭터의 value가 있으면 {값이름}을 값으로 치환하고,
     * 잡담(isOoc)이 아니고 본문이 다이스 명령이면 다이스봇의 결과 메시지를 이어서 남긴다.
     */
    suspend fun sendMessage(roomId: Long, sender: CharacterProfile, text: String, isOoc: Boolean = false) {
        val raw = text.trim()
        if (raw.isEmpty()) return
        // plain은 다이스 파싱용({은신}→50), body는 저장용({은신}→{{50}} 파란색 마커)
        val (plain, body) = ProfileStats.substitute(raw, ProfileStats.decode(sender.stats).toMap())
        val inserted = mutableListOf<Message>()
        db.withTransaction {
            val textMessage = Message(
                roomId = roomId,
                type = MessageType.TEXT,
                body = body,
                senderName = sender.name,
                senderEmoji = sender.emoji,
                senderImagePath = sender.imagePath,
                senderIsGm = sender.isGm,
                senderNameColor = sender.nameColor,
                senderBubbleColor = sender.bubbleColor,
                isOoc = isOoc,
                createdAt = System.currentTimeMillis(),
            )
            inserted += textMessage.copy(id = db.messageDao().insert(textMessage))
            if (!isOoc) {
                DiceBot.parse(plain)?.let { command ->
                    val result = DiceBot.roll(command)
                    val diceMessage = Message(
                        roomId = roomId,
                        type = MessageType.DICE,
                        body = result.breakdown,
                        diceExpr = "${sender.name} · ${command.expr}",
                        diceOutcome = result.success?.let { if (it) "success" else "fail" },
                        senderName = "다이스봇",
                        senderEmoji = "🎲",
                        senderIsBot = true,
                        createdAt = System.currentTimeMillis(),
                    )
                    inserted += diceMessage.copy(id = db.messageDao().insert(diceMessage))
                }
            }
        }
        pushIfSynced(roomId, inserted)
    }

    /** 메시지 수정 — 로컬 갱신 후 공유 방이면 상대에게 전파 */
    suspend fun editMessage(messageId: Long, newBody: String) {
        val body = newBody.trim()
        if (body.isEmpty()) return
        val editedAt = System.currentTimeMillis()
        db.messageDao().updateBody(messageId, body, editedAt)
        val message = db.messageDao().get(messageId) ?: return
        val room = db.roomDao().get(message.roomId) ?: return
        val remoteRoom = room.remoteId ?: return
        val remoteMessage = message.remoteId ?: return
        syncManager?.pushEdit(remoteRoom, remoteMessage, body, editedAt)
    }

    /** 메시지 삭제 — 로컬 삭제 후 공유 방이면 상대에게 전파 */
    suspend fun deleteMessage(message: Message) {
        db.messageDao().deleteById(message.id)
        val room = db.roomDao().get(message.roomId) ?: return
        val remoteRoom = room.remoteId ?: return
        val remoteMessage = message.remoteId ?: return
        syncManager?.pushDelete(remoteRoom, remoteMessage)
    }

    suspend fun saveProfile(profile: CharacterProfile) {
        if (profile.id == 0L) db.profileDao().insert(profile) else db.profileDao().update(profile)
    }

    suspend fun deleteProfile(profile: CharacterProfile) = db.withTransaction {
        db.roomDao().clearActiveProfile(profile.id)
        db.profileDao().delete(profile)
    }

    private suspend fun pushIfSynced(roomId: Long, messages: List<Message>) {
        val remoteId = db.roomDao().get(roomId)?.remoteId ?: return
        syncManager?.push(remoteId, messages)
    }
}
