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

    /** 삭제된 프로필을 가리키는 activeProfileId 정리 (GM 프로필 일괄 제거 후) */
    @Query(
        "UPDATE rooms SET activeProfileId = NULL WHERE activeProfileId IS NOT NULL " +
            "AND activeProfileId NOT IN (SELECT id FROM profiles)"
    )
    suspend fun clearDanglingActiveProfiles()

    @Query("SELECT * FROM rooms WHERE id = :id")
    suspend fun get(id: Long): ChatRoom?

    @Query("UPDATE rooms SET remoteId = :remoteId, inviteCode = :inviteCode WHERE id = :roomId")
    suspend fun setRemote(roomId: Long, remoteId: String, inviteCode: String)

    @Query("SELECT * FROM rooms WHERE remoteId IS NOT NULL")
    suspend fun listSynced(): List<ChatRoom>

    @Query("SELECT * FROM rooms WHERE isMaster = 0")
    suspend fun listJoined(): List<ChatRoom>

    @Query("SELECT * FROM rooms WHERE inviteCode = :code LIMIT 1")
    suspend fun findByInviteCode(code: String): ChatRoom?

    @Query("SELECT * FROM rooms WHERE remoteId = :remoteId LIMIT 1")
    suspend fun findByRemoteId(remoteId: String): ChatRoom?

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

data class RemoteMessageRow(val remoteId: String, val body: String, val editedAt: Long?)

@Dao
interface ProfileDao {
    /** 프로필 관리 목록 — 전역·방 귀속(GM 포함) 전부 */
    @Query("SELECT * FROM profiles ORDER BY isGm DESC, name")
    fun observeAllProfiles(): Flow<List<CharacterProfile>>

    @Query("SELECT * FROM profiles WHERE roomId IS NULL OR roomId = :roomId ORDER BY isGm DESC, name")
    fun observeForRoom(roomId: Long): Flow<List<CharacterProfile>>

    /** 과거 버전이 참여자 방에도 만들었던 GM 프로필 제거 — 서술 권한은 마스터 전용 */
    @Query("DELETE FROM profiles WHERE isGm = 1 AND roomId IN (SELECT id FROM rooms WHERE isMaster = 0)")
    suspend fun deleteGmProfilesOfJoinedRooms(): Int

    /** 이 방에서 고를 수 있는 프로필 수(전역 + 방 귀속) */
    @Query("SELECT COUNT(*) FROM profiles WHERE roomId IS NULL OR roomId = :roomId")
    suspend fun countForRoom(roomId: Long): Int

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

    /** '이전 대화 불러오기' 버튼 표시 판정용 총 개수 (P3-7) */
    @Query("SELECT COUNT(*) FROM messages WHERE roomId = :roomId")
    fun observeCount(roomId: Long): Flow<Int>

    @Query("SELECT * FROM messages WHERE roomId = :roomId ORDER BY createdAt ASC, id ASC")
    suspend fun listForRoom(roomId: Long): List<Message>

    /** 수신 dedup 일괄 조회 — 문서마다 쿼리하지 않도록 */
    @Query("SELECT remoteId FROM messages WHERE remoteId IN (:ids)")
    suspend fun existingRemoteIds(ids: List<String>): List<String>

    /** 서버 반영이 확인되지 않은 메시지 — 시작 시 재전송용 아웃박스 (멱등: remoteId 고정) */
    @Query("SELECT * FROM messages WHERE roomId = :roomId AND uploaded = 0 ORDER BY createdAt ASC, id ASC")
    suspend fun listUnsent(roomId: Long): List<Message>

    /**
     * remoteId 원자 선점 (L3) — 이미 다른 경로(동시 전송·백필)가 선점했으면 0을 돌려준다.
     * 백필과 pushMessage가 같은 메시지에 서로 다른 원격 문서를 만드는 레이스 방지.
     */
    @Query("UPDATE messages SET remoteId = :remoteId WHERE id = :id AND remoteId IS NULL")
    suspend fun claimRemoteId(id: Long, remoteId: String): Int

    @Query("UPDATE messages SET uploaded = 1 WHERE id = :id")
    suspend fun setUploaded(id: Long)

    /** 삭제 동기화 대조용 — 서버에 존재해야 하는(업로드 확인된) remoteId 전체 */
    @Query("SELECT remoteId FROM messages WHERE roomId = :roomId AND remoteId IS NOT NULL AND uploaded = 1")
    suspend fun listRemoteIdsForRoom(roomId: Long): List<String>

    /** 수신 dedup + 변경 감지용 — remoteId와 현재 본문·편집시각만 (P4) */
    @Query("SELECT remoteId, body, editedAt FROM messages WHERE remoteId IN (:remoteIds)")
    suspend fun listByRemoteIds(remoteIds: List<String>): List<RemoteMessageRow>

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
           WHERE m.incoming = 1 AND m.createdAt > r.lastReadAt AND m.type != 'SYSTEM'
           GROUP BY m.roomId"""
    )
    fun observeUnreadCounts(): Flow<List<UnreadCount>>

    /** 상대에게서 받은 마지막 메시지 시각 — 읽음 확인으로 올릴 기준값 */
    @Query("SELECT MAX(createdAt) FROM messages WHERE roomId = :roomId AND incoming = 1")
    suspend fun latestIncomingAt(roomId: Long): Long?

    // remoteId 유니크 인덱스와 조합해 수신 레이스의 중복 삽입을 DB 차원에서 차단
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insert(message: Message): Long
}
