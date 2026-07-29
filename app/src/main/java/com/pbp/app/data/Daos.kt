package com.pbp.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RoomDao {
    @Query("SELECT * FROM rooms ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ChatRoom>>

    @Query("SELECT * FROM rooms WHERE id = :id")
    fun observe(id: Long): Flow<ChatRoom?>

    @Insert
    suspend fun insert(room: ChatRoom): Long

    @Query("UPDATE rooms SET activeProfileId = :profileId WHERE id = :roomId")
    suspend fun setActiveProfile(roomId: Long, profileId: Long)

    @Query("UPDATE rooms SET activeProfileId = NULL WHERE activeProfileId = :profileId")
    suspend fun clearActiveProfile(profileId: Long)

    @Query("SELECT * FROM rooms WHERE id = :id")
    suspend fun get(id: Long): ChatRoom?

    @Query("UPDATE rooms SET remoteId = :remoteId, inviteCode = :inviteCode WHERE id = :roomId")
    suspend fun setRemote(roomId: Long, remoteId: String, inviteCode: String)

    @Query("SELECT * FROM rooms WHERE remoteId IS NOT NULL")
    suspend fun listSynced(): List<ChatRoom>

    @Query("SELECT * FROM rooms WHERE inviteCode = :code LIMIT 1")
    suspend fun findByInviteCode(code: String): ChatRoom?

    @Query("UPDATE rooms SET themeColor = :color WHERE id = :roomId")
    suspend fun setThemeColor(roomId: Long, color: Long)

    @Query("UPDATE rooms SET backgroundKey = :key WHERE id = :roomId")
    suspend fun setBackground(roomId: Long, key: String)

    @Query("UPDATE rooms SET lastReadAt = :time WHERE id = :roomId")
    suspend fun setLastReadAt(roomId: Long, time: Long)

    @Delete
    suspend fun delete(room: ChatRoom)
}

data class UnreadCount(val roomId: Long, val count: Int)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE roomId IS NULL ORDER BY name")
    fun observeGlobal(): Flow<List<CharacterProfile>>

    @Query("SELECT * FROM profiles WHERE roomId IS NULL OR roomId = :roomId ORDER BY isGm DESC, name")
    fun observeForRoom(roomId: Long): Flow<List<CharacterProfile>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: Long): CharacterProfile?

    @Insert
    suspend fun insert(profile: CharacterProfile): Long

    @Update
    suspend fun update(profile: CharacterProfile)

    @Delete
    suspend fun delete(profile: CharacterProfile)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE roomId = :roomId ORDER BY createdAt ASC, id ASC")
    fun observeForRoom(roomId: Long): Flow<List<Message>>

    /** 최신 limit개만 시간순으로 — 장기 캠페인에서 전체 로딩 방지 */
    @Query(
        """SELECT * FROM (
             SELECT * FROM messages WHERE roomId = :roomId
             ORDER BY createdAt DESC, id DESC LIMIT :limit
           ) ORDER BY createdAt ASC, id ASC"""
    )
    fun observeLatestForRoom(roomId: Long, limit: Int): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE id IN (SELECT MAX(id) FROM messages GROUP BY roomId)")
    fun observeLastPerRoom(): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE roomId = :roomId ORDER BY createdAt ASC, id ASC")
    suspend fun listForRoom(roomId: Long): List<Message>

    @Query("SELECT COUNT(*) FROM messages WHERE remoteId = :remoteId")
    suspend fun countByRemoteId(remoteId: String): Int

    /** 수신 dedup 일괄 조회 — 문서마다 쿼리하지 않도록 */
    @Query("SELECT remoteId FROM messages WHERE remoteId IN (:ids)")
    suspend fun existingRemoteIds(ids: List<String>): List<String>

    /** 전송 실패(remoteId 미기록) 메시지 — 시작 시 재전송용 아웃박스 */
    @Query("SELECT * FROM messages WHERE roomId = :roomId AND remoteId IS NULL ORDER BY createdAt ASC, id ASC")
    suspend fun listUnsent(roomId: Long): List<Message>

    @Query("UPDATE messages SET remoteId = :remoteId WHERE id = :id")
    suspend fun setRemoteId(id: Long, remoteId: String)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): Message?

    @Query("UPDATE messages SET body = :body, editedAt = :editedAt WHERE id = :id")
    suspend fun updateBody(id: Long, body: String, editedAt: Long)

    @Query("UPDATE messages SET body = :body, editedAt = :editedAt WHERE remoteId = :remoteId")
    suspend fun updateBodyByRemoteId(remoteId: String, body: String, editedAt: Long?)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE remoteId = :remoteId")
    suspend fun deleteByRemoteId(remoteId: String)

    @Query("DELETE FROM messages WHERE roomId = :roomId")
    suspend fun deleteForRoom(roomId: Long)

    @Query(
        """SELECT m.roomId AS roomId, COUNT(*) AS count FROM messages m
           JOIN rooms r ON r.id = m.roomId
           WHERE m.incoming = 1 AND m.createdAt > r.lastReadAt
           GROUP BY m.roomId"""
    )
    fun observeUnreadCounts(): Flow<List<UnreadCount>>

    @Insert
    suspend fun insert(message: Message): Long
}
