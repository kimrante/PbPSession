package com.pbp.app.data

import androidx.room.withTransaction
import com.pbp.shared.ProfileStats
import com.pbp.shared.Protocol
import com.pbp.shared.DiceBot
import com.pbp.app.sync.SyncManager
import com.pbp.app.ui.theme.PbpPalette
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext

class PbpRepository(private val db: AppDatabase) {

    /** 공유된 방의 메시지를 Firestore로 밀어낼 때 사용. PbpApp에서 주입한다. */
    var syncManager: SyncManager? = null

    fun observeRooms() = db.roomDao().observeAll()
    fun observeRoom(id: Long) = db.roomDao().observe(id)
    fun observeLastMessages() = db.messageDao().observeLastPerRoom()
    fun observeUnreadCounts() = db.messageDao().observeUnreadCounts()
    fun observeLatestMessages(roomId: Long, limit: Int) =
        db.messageDao().observeLatestForRoom(roomId, limit)
    fun observeMessageCount(roomId: Long) = db.messageDao().observeCount(roomId)
    suspend fun allMessages(roomId: Long) = db.messageDao().listForRoom(roomId)
    fun observeProfilesForRoom(roomId: Long) = db.profileDao().observeForRoom(roomId)

    /** 클립보드에서 가져온 ccfolia 캐릭터를 전역 프로필로 등록 (리뷰 C1) */
    suspend fun createFromCode(imported: com.pbp.shared.CharacterCodec.Imported) {
        saveProfile(
            CharacterProfile(
                name = imported.name,
                stats = ProfileStats.encode(imported.stats),
            )
        )
    }

    /**
     * 방을 만든다. 마스터면 방에 귀속된 GM 프로필을 함께 생성해 기본 발화 프로필로,
     * 참여자(초대 참가)면 GM 없이 기본 캐릭터만 만든다 — 서술 권한은 마스터 전용.
     */
    suspend fun createRoom(
        name: String,
        icon: String = "",
        isMaster: Boolean = true,
        themeColor: Long = PbpPalette.DEFAULT_THEME_COLOR,
        backgroundKey: String = PbpPalette.DEFAULT_BACKGROUND,
        rule: String = com.pbp.shared.Rules.COC7,
        /**
         * 참여로 만드는 방이면 원격 정보를 **같은 트랜잭션에서** 함께 기록한다 (H6).
         * 나눠 쓰면 그 사이 크래시가 났을 때 원격과 이어지지 않은 고아 방이 남고,
         * 다시 참여하면 같은 방이 하나 더 생긴다.
         */
        remoteId: String? = null,
        inviteCode: String? = null,
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
        if (remoteId != null) db.roomDao().setRemote(roomId, remoteId, inviteCode ?: "")
        if (isMaster) {
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
        } else {
            // 초대받은 참여자는 GM(서술) 권한이 없다 — 기본 캐릭터로 시작
            val playerId = db.profileDao().insert(
                CharacterProfile(name = "플레이어", roomId = roomId)
            )
            db.roomDao().setActiveProfile(roomId, playerId)
        }
        roomId
    }

    /** 이 기기에서만 뺀다 — 서버와 상대 기기는 그대로 */
    suspend fun deleteRoom(room: ChatRoom) {
        syncManager?.detach(room.id)
        // 원격 멤버 문서(FCM 토큰) 정리 — 삭제한 방의 유령 푸시 방지 (P2-2)
        room.remoteId?.let { syncManager?.leaveRoom(it) }
        db.roomDao().delete(room)
    }

    /**
     * 세션을 서버에서 통째로 지운다 — **상대 기기에서도 사라진다.**
     * 공유하지 않은 방이면 그냥 로컬 삭제와 같다.
     */
    suspend fun destroyRoom(room: ChatRoom): Boolean {
        val remoteId = room.remoteId ?: run { deleteRoom(room); return true }
        syncManager?.detach(room.id)
        val known = db.messageDao().listRemoteIdsForWipe(room.id)
        val ok = syncManager?.destroyRoom(remoteId, known) ?: false
        db.roomDao().delete(room)
        return ok
    }

    /** 방 테마 컬러 변경(누구나 가능) — 공유 방이면 상대에게 실시간 전파 */
    suspend fun setThemeColor(roomId: Long, color: Long) {
        db.roomDao().setThemeColor(roomId, color)
        pushRoomSettings(roomId)
    }

    /**
     * 방 배경 변경 — **이 기기에만 적용된다.** 배경은 각자 취향대로 고르는 개인 설정이라
     * 서버에 올리지도, 상대 것을 받지도 않는다.
     */
    suspend fun setBackground(roomId: Long, key: String) {
        db.roomDao().setBackground(roomId, key)
    }

    private suspend fun pushRoomSettings(roomId: Long) {
        val room = db.roomDao().get(roomId) ?: return
        val remoteId = room.remoteId ?: return
        syncManager?.pushRoomSettings(remoteId, room.themeColor)
    }

    /**
     * 방에 들어왔을 때 호출 — 미확인 배지·푸시 기준 시각 갱신.
     * 공유 방이면 상대에게 읽음 확인도 알린다 (받은 메시지가 있을 때만).
     */
    suspend fun markRead(roomId: Long) {
        db.roomDao().setLastReadAt(roomId, System.currentTimeMillis())
        val remoteId = db.roomDao().get(roomId)?.remoteId ?: return
        val readAt = db.messageDao().latestIncomingAt(roomId) ?: return
        syncManager?.pushReadReceipt(remoteId, readAt)
    }

    /**
     * 상대(모바일)가 어디까지 읽었는지. 로컬 전용 방이거나 상대가 데스크톱이면 null —
     * 읽음 확인은 모바일끼리만 성립한다.
     */
    fun observePeerState(remoteId: String?) =
        if (remoteId == null) kotlinx.coroutines.flow.flowOf(SyncManager.PeerState())
        else syncManager?.observePeerState(remoteId)
            // 리스너가 죽으면(권한 만료·네트워크) 흐름이 끊긴다 — 다시 붙는다 (B7).
            // 즉시 재구독하면 오류 폭주 시 그대로 재시도 폭주가 되므로 5초 간격
            ?.retryWhen { _, attempt ->
                kotlinx.coroutines.delay(if (attempt == 0L) 1_000 else 5_000)
                true
            }
            ?: kotlinx.coroutines.flow.flowOf(SyncManager.PeerState())

    /**
     * 입력 이벤트가 있을 때만. 스로틀을 **먼저** 확인한다 —
     * 키를 누를 때마다 Room을 조회할 이유가 없다 (P4)
     */
    /**
     * 이 기기의 캐릭터 명단을 상대에게 알린다 (J0).
     *
     * GM은 빼고(대상이 될 일이 없다), 값은 **숫자만** 이름을 싣는다 — 주사위를 굴릴 수
     * 있어야 판정 대상이 되기 때문이다(채팅 팔레트와 같은 규칙).
     */
    suspend fun pushCharacters(roomId: Long, profiles: List<CharacterProfile>) {
        val remoteId = db.roomDao().get(roomId)?.remoteId ?: return
        val payload = profiles.filterNot { it.isGm }.map { profile ->
            mapOf(
                // 이름이 겹쳐도 상대가 이 캐릭터를 정확히 가리킬 수 있게
                com.pbp.shared.Protocol.Character.ID to profile.characterId,
                // 상대의 판정 요청 목록에 얼굴이 뜨도록 (메시지와 같은 아바타를 가리킨다)
                com.pbp.shared.Protocol.Character.AVATAR_ID to profile.imagePath?.let { path ->
                    syncManager?.avatarIdFor(remoteId, path)
                },
                com.pbp.shared.Protocol.Character.NAME to profile.name,
                com.pbp.shared.Protocol.Character.EMOJI to profile.emoji,
                com.pbp.shared.Protocol.Character.NAME_COLOR to profile.nameColor,
                com.pbp.shared.Protocol.Character.STATS to numericStatNames(profile),
            )
        }
        syncManager?.pushCharacters(remoteId, payload)
    }

    suspend fun pushTyping(roomId: Long, name: String) {
        val sync = syncManager ?: return
        if (!sync.typingDue(roomId)) return
        val remoteId = db.roomDao().get(roomId)?.remoteId ?: return
        sync.pushTyping(remoteId, name)
    }

    suspend fun clearTyping(roomId: Long) {
        val remoteId = db.roomDao().get(roomId)?.remoteId ?: return
        syncManager?.clearTyping(remoteId, roomId)
    }

    /**
     * 발화 프로필 교체. 화면에 안내 메시지를 남기지 않는다 —
     * 프로필 스트립의 선택 표시로 충분하고, 전환할 때마다 로그가 끊겨 읽기 나빴다.
     */
    suspend fun switchProfile(roomId: Long, profile: CharacterProfile) {
        db.roomDao().setActiveProfile(roomId, profile.id)
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
        // 잡담은 극 밖의 대화 — 어떤 캐릭터(GM 포함)가 활성이든 오너 프로필로 나간다
        val asOwner = isOoc && OwnerProfile.isSet
        val inserted = mutableListOf<Message>()
        db.withTransaction {
            val textMessage = Message(
                roomId = roomId,
                type = MessageType.TEXT,
                body = body,
                senderName = if (asOwner) OwnerProfile.name else sender.name,
                senderEmoji = sender.emoji,
                senderImagePath = if (asOwner) OwnerProfile.imagePath else sender.imagePath,
                senderIsGm = if (isOoc) false else sender.isGm,
                senderNameColor = if (asOwner) OwnerProfile.color else sender.nameColor,
                senderBubbleColor = if (asOwner) OwnerProfile.color else sender.bubbleColor,
                senderTextColor = if (asOwner) OwnerProfile.textColor else sender.textColor,
                isOoc = isOoc,
                createdAt = System.currentTimeMillis(),
            )
            inserted += textMessage.copy(id = db.messageDao().insert(textMessage))
            val rule = db.roomDao().get(roomId)?.rule ?: com.pbp.shared.Rules.COC7
            if (!isOoc) {
                DiceBot.parse(plain)?.let { command ->
                    val result = DiceBot.roll(command)
                    val diceMessage = Message(
                        roomId = roomId,
                        type = MessageType.DICE,
                        body = result.breakdown,
                        diceExpr = "${sender.name} · ${command.expr}",
                        diceOutcome = com.pbp.shared.Rules.judgeOutcome(rule, result),
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

    /**
     * 판정 요청을 보낸다 (J4). **여기서 굴리지 않는다** — 굴림은 대상 캐릭터를 가진
     * 기기에서 그때의 값으로 한다.
     *
     * `diceExpr`에는 `{값이름}` 플레이스홀더를 **치환하지 않은 채** 넣는다. 보통 발신
     * 경로(sendMessage)는 보낼 때 치환하지만, 여기서는 굴리는 쪽이 자기 값으로 치환해야
     * 하므로 반대다. 그래서 요청 뒤에 값을 고쳐도 항상 최신 값으로 굴러간다.
     */
    suspend fun sendJudgeRequest(
        roomId: Long,
        sender: CharacterProfile,
        targetId: String?,
        targetName: String,
        statName: String,
    ) {
        val rule = db.roomDao().get(roomId)?.rule ?: com.pbp.shared.Rules.COC7
        val message = Message(
            roomId = roomId,
            type = MessageType.JUDGE,
            // 구버전 클라이언트에서는 이 문구가 그대로 말풍선으로 보인다
            body = "$targetName, $statName 판정",
            diceExpr = com.pbp.shared.Rules.judgeCommand(rule, statName),
            judgeTarget = targetName,
            judgeTargetId = targetId,
            senderName = sender.name,
            senderEmoji = sender.emoji,
            senderImagePath = sender.imagePath,
            senderIsGm = true,
            senderNameColor = sender.nameColor,
            senderBubbleColor = sender.bubbleColor,
            senderTextColor = sender.textColor,
            createdAt = System.currentTimeMillis(),
        )
        val inserted = message.copy(id = db.messageDao().insert(message))
        pushIfSynced(roomId, listOf(inserted))
    }

    /**
     * 요청을 눌러 굴린다 (J6). 굴림은 **요청이 지목한 캐릭터**로 나간다 —
     * 지금 활성 프로필이 무엇이든 상관없고, 활성 프로필을 바꾸지도 않는다.
     *
     * @return 값이 없어 굴리지 못했으면 그 값 이름 (호출부가 입력 다이얼로그를 띄운다)
     */
    suspend fun rollJudge(request: Message): String? {
        val key = judgeKey(request)
        // 여기서도 한 번 걸러 굴림 자체를 아낀다 — 확정 판정은 아래 트랜잭션이 한다
        if (db.messageDao().hasJudgeResult(request.roomId, key)) return null
        val profile = judgeProfile(request) ?: return null
        val expr = request.diceExpr ?: return null
        val stats = ProfileStats.decode(profile.stats).toMap()
        val (plain, _) = ProfileStats.substitute(expr, stats)
        val command = DiceBot.parse(plain)
            // 치환이 안 됐다 = 그 캐릭터에 그 값이 없다. 호출부가 값을 받아 채우게 한다
            ?: return statNameOf(expr)
        val rule = db.roomDao().get(request.roomId)?.rule ?: com.pbp.shared.Rules.COC7
        val result = DiceBot.roll(command)
        val dice = Message(
            roomId = request.roomId,
            type = MessageType.DICE,
            body = result.breakdown,
            diceExpr = "${profile.name} · ${command.expr}",
            diceOutcome = com.pbp.shared.Rules.judgeOutcome(rule, result),
            senderName = "다이스봇",
            senderEmoji = "🎲",
            senderIsBot = true,
            judgeRef = key,
            createdAt = System.currentTimeMillis(),
        )
        // 검사와 삽입을 한 트랜잭션으로 묶는다 (G4). 카드를 연타하면 탭마다 코루틴이
        // 뜨는데, 둘 다 위 검사를 통과한 뒤 각자 insert하면 결과가 2건 남고 둘 다
        // 상대에게 전파됐다 — "결과는 1건"이 주석으로만 있고 코드로는 없었다
        val inserted = db.withTransaction {
            if (db.messageDao().hasJudgeResult(request.roomId, key)) null
            else dice.copy(id = db.messageDao().insert(dice))
        } ?: return null
        pushIfSynced(request.roomId, listOf(inserted))
        return null
    }

    /**
     * 요청이 지목한 내 캐릭터.
     *
     * **고유 id를 먼저 본다** — 이름으로만 찾으면 같은 이름의 프로필이 둘 있을 때
     * 엉뚱한 쪽이 굴렸다. 구버전이 보낸 요청에는 id가 없으니 그때만 이름으로 찾는다.
     */
    private suspend fun judgeProfile(request: Message): CharacterProfile? {
        val profiles = db.profileDao().forRoom(request.roomId)
        request.judgeTargetId?.let { id ->
            return profiles.find { it.characterId == id }
        }
        val target = request.judgeTarget ?: return null
        return profiles.find { it.name == target }
    }

    /** 요청에 값을 채워 넣고 바로 굴린다 — 대상 캐릭터에 그 값이 없었을 때 (J6) */
    suspend fun addStatAndRoll(request: Message, statName: String, value: Int) {
        val profile = judgeProfile(request) ?: return
        val stats = ProfileStats.decode(profile.stats).filterNot { it.first == statName }
        db.profileDao().update(
            profile.copy(
                stats = ProfileStats.encode(ProfileStats.sortByName(stats + (statName to value.toString())))
            )
        )
        rollJudge(request)
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

    /**
     * 방 로그 전체 리셋 — 서버를 먼저 비우고, 성공한 뒤에 로컬을 지운다.
     * 서버 문서가 지워지면 상대 기기의 리스너(REMOVED)가 상대 로그도 지운다.
     *
     * 순서가 중요하다(N2): 로컬을 먼저 지우면 서버 wipe 실패 시 내 쪽만 비고
     * 다음 시작 때 상대 메시지만 되살아나는 영구 분기가 생긴다.
     * 화면 이탈로 중간에 취소되지 않도록 NonCancellable로 감싼다(N1).
     *
     * @return 서버 삭제까지 성공했는지 (로컬 전용 방이면 true)
     */
    suspend fun resetLogs(roomId: Long): Boolean =
        withContext(kotlinx.coroutines.NonCancellable) {
            val room = db.roomDao().get(roomId) ?: return@withContext false
            val remoteId = room.remoteId
            if (remoteId != null) {
                // 리스너를 떼고 지운다 (L1): 붙여둔 채 지우면 배치가 커밋될 때마다
                // 내 리스너의 REMOVED가 로컬을 즉시 삭제해 '실패 시 로컬 보존'이 깨진다.
                syncManager?.detach(roomId)
                val serverOk = syncManager
                    ?.wipeMessages(remoteId, db.messageDao().listRemoteIdsForWipe(roomId))
                    ?: false
                if (!serverOk) {
                    // 로컬은 건드리지 않는다. 붙여 두었던 리스너만 되돌린다
                    syncManager?.reattach(roomId, remoteId)
                    return@withContext false
                }
            }
            db.messageDao().deleteForRoom(roomId)
            // 리셋 흔적을 양쪽에 남긴다 (서버 삭제가 끝난 뒤에 보내야 함께 지워지지 않는다)
            val notice = Message(
                roomId = roomId,
                type = MessageType.SYSTEM,
                body = Protocol.Notice.LOGS_RESET,
                createdAt = System.currentTimeMillis(),
            )
            val inserted = notice.copy(id = db.messageDao().insert(notice))
            // 재접속은 **로컬을 비운 뒤**에 (H4). 먼저 붙이면 detach~wipe 사이에 상대가
            // 보낸 메시지가 초기 스냅샷으로 들어왔다가 바로 위 deleteForRoom에 쓸려 나가,
            // 서버엔 있는데 내 로컬에만 없는 상태가 된다
            if (remoteId != null) syncManager?.reattach(roomId, remoteId)
            pushIfSynced(roomId, listOf(inserted))
            true
        }

    /** 방 이름 조회 — 다른 방 캐릭터를 어느 방 것인지 보여 줄 때 */
    suspend fun roomNamesOf(roomIds: List<Long>): Map<Long, String> =
        roomIds.mapNotNull { id -> db.roomDao().get(id)?.let { id to it.name } }.toMap()

    /** 이 방으로 데려올 수 있는 다른 방의 캐릭터 */
    suspend fun profilesFromOtherRooms(roomId: Long) = db.profileDao().fromOtherRooms(roomId)

    /**
     * 다른 방의 캐릭터를 이 방으로 **복사**한다 — 원본은 그 방에 그대로 남는다.
     * 사본은 새 id를 받아 따로 산다(한쪽을 고쳐도 다른 쪽은 그대로).
     */
    suspend fun copyProfileInto(source: CharacterProfile, roomId: Long) {
        saveProfile(
            source.copy(
                id = 0,
                characterId = java.util.UUID.randomUUID().toString(),
                roomId = roomId,
                updatedAt = 0,
            )
        )
    }

    suspend fun saveProfile(profile: CharacterProfile) {
        // 고친 시각을 남긴다 — 다른 기기와 부딪히면 나중 것이 남는 기준이 된다
        val stamped = profile.copy(updatedAt = System.currentTimeMillis())
        val saved = if (stamped.id == 0L) {
            stamped.copy(id = db.profileDao().insert(stamped))
        } else {
            stamped.also { db.profileDao().update(it) }
        }
        syncManager?.pushProfile(saved)
    }

    suspend fun deleteProfile(profile: CharacterProfile) {
        db.withTransaction {
            db.roomDao().clearActiveProfile(profile.id)
            db.profileDao().delete(profile)
        }
        syncManager?.deleteProfileRemote(profile.characterId)
    }

    private suspend fun pushIfSynced(roomId: Long, messages: List<Message>) {
        val remoteId = db.roomDao().get(roomId)?.remoteId ?: return
        syncManager?.push(remoteId, messages)
    }
}

/** 주사위를 굴릴 수 있는 값(숫자)만 — 글자 값은 판정 대상이 아니다 */
fun numericStatNames(profile: CharacterProfile): List<String> =
    ProfileStats.decode(profile.stats)
        .filter { it.second.trim().toIntOrNull() != null }
        .map { it.first }
        .distinct()

/**
 * 요청을 가리키는 키 — 굴림 결과의 judgeRef가 이 값을 갖는다.
 * 공유 방에서는 상대가 요청을 보는 시점에 이미 remoteId가 있고(서버를 거쳐 왔으므로),
 * 로컬 전용 방은 동기화가 없어 로컬 id로 충분하다.
 */
fun judgeKey(request: Message): String = request.remoteId ?: "local-${request.id}"

/** "1d100<={민첩}" → "민첩". 규칙은 :shared가 단일 출처 — 데스크톱도 같은 것을 쓴다 */
fun statNameOf(diceExpr: String): String? = ProfileStats.statNameOf(diceExpr)
