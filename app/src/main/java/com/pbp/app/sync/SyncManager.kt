package com.pbp.app.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.room.withTransaction
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.pbp.app.R
import com.pbp.app.data.AppDatabase
import com.pbp.app.data.Message
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
// awaitClose는 ProducerScope 확장 함수라 정규화된 이름으로는 호출할 수 없다 — 임포트 필요
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import com.pbp.shared.Protocol

/**
 * 2인 동기화 담당. Firestore 구조:
 *   rooms/{roomId}: name, icon, createdAt, inviteCode
 *   rooms/{roomId}/messages/{messageId}: SyncMapping 참조
 *
 * 로컬 Room DB가 항상 화면의 소스이고, Firestore는 전송 채널이다.
 * 내가 보낸 메시지는 로컬 삽입 → push, 상대 메시지는 리스너 → 로컬 삽입.
 * 중복은 remoteId와 authorUid로 거른다.
 */
class SyncManager(private val context: Context, private val db: AppDatabase) {

    /**
     * 참여(join) 시 로컬 방 생성 — Repository 전체가 아니라 이 한 가지 능력만 주입받는다.
     * 과거에는 서로를 `var …? = null`로 물고 있어 두 클래스를 독립적으로 읽을 수 없었다 (리뷰 B6).
     */
    var createLocalRoom: (suspend (name: String, themeColor: Long, backgroundKey: String, rule: String) -> Long)? = null

    /** 프로필 이미지 업로드·복원 (B5로 분리) */
    private val avatars by lazy { AvatarStore(context) { firestore } }

    /** 상대 메시지 수신 시 호출(알림용, 두 번째 인자는 원격 방 ID). PbpApp에서 주입한다. */
    var onIncomingMessage: ((Message, String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 여러 IO 스레드 + FCM 바인더 스레드에서 접근하므로 동시성 컬렉션 (N7)
    private val listeners = java.util.concurrent.ConcurrentHashMap<Long, ListenerRegistration>()
    private val roomListeners = java.util.concurrent.ConcurrentHashMap<Long, ListenerRegistration>()
    private val attachedRemotes = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** 이 원격 방의 리스너가 살아 있는가 — FCM 경로의 이중 알림 판정 (P2-2) */
    fun isAttached(remoteRoomId: String): Boolean = attachedRemotes.containsValue(remoteRoomId)

    /** 계정 없이 발신자를 구분하기 위한 기기 고유 ID (앱 설치당 1개) */
    val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("pbp", Context.MODE_PRIVATE)
        prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    private val projectId: String by lazy { context.getString(R.string.firebase_project_id) }

    /** demo- 프로젝트는 로컬 에뮬레이터 모드 — FCM 등 GMS 기능은 건너뛴다 */
    private val isDemo: Boolean get() = projectId.startsWith("demo-")

    private val firebaseApp: FirebaseApp by lazy {
        FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId(projectId)
                .setApplicationId(context.getString(R.string.firebase_app_id))
                .setApiKey(context.getString(R.string.firebase_api_key))
                .setGcmSenderId(context.getString(R.string.firebase_sender_id))
                .build(),
        )!!
    }

    /**
     * 익명 인증(P0-1). 성공하면 auth UID가 이 기기의 신원이 되고,
     * 실패(콘솔에서 익명 로그인 미활성 등)하면 기존 deviceId로 동작한다
     * — 보안 규칙 배포 전까지의 하위 호환.
     */
    @Volatile
    private var authUid: String? = null

    private val authMutex = kotlinx.coroutines.sync.Mutex()

    /** 현재 신원 — 메시지 authorUid·멤버 문서 키에 쓴다 */
    val myUid: String get() = authUid ?: deviceId

    suspend fun ensureAuth(): String {
        if (isDemo) return deviceId
        authUid?.let { return it }
        authMutex.withLock {
            authUid?.let { return it }
            val uid = runCatching {
                val auth = com.google.firebase.auth.FirebaseAuth.getInstance(firebaseApp)
                (auth.currentUser ?: auth.signInAnonymously().await().user)?.uid
            }.getOrNull()
            if (uid != null) authUid = uid
        }
        return myUid
    }

    private val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance(firebaseApp).apply {
            if (isDemo) {
                // demo- 프로젝트는 로컬 에뮬레이터로 (10.0.2.2 = 에뮬레이터에서 본 호스트 PC)
                useEmulator("10.0.2.2", 8080)
            }
            // 기본 PersistentCacheSettings 유지 (P1): 디스크 캐시가 있어야 SDK가 스냅샷을
            // 이어받아, 프로세스 재시작 때 initial snapshot 전량 재다운로드·재과금이 사라진다.
            // (과거 "Room이 소스라 이중 저장" 이유로 메모리 캐시를 강제했으나, 그 비용은
            // 수 MB 디스크일 뿐이고 read 과금이 콜드 스타트마다 방 전체 크기로 발생했다.
            // reconcile 기준선은 캐시 사용 시에도 첫 스냅샷의 전체 집합이라 그대로 동작.)
        }
    }

    /**
     * 앱 시작 시: 공유된 방들의 수신 리스너 복구 + FCM 토큰 등록 +
     * 전송에 실패했던 메시지(아웃박스, remoteId 미기록) 재전송.
     */
    fun start() = scope.launch {
        val synced = db.roomDao().listSynced()
        if (synced.isEmpty()) return@launch
        ensureAuth()
        // 구버전(deviceId 키) 방들에 auth UID 멤버 문서를 1회 보충 — 규칙 배포 후에도 접근 유지
        val prefs = context.getSharedPreferences("pbp", Context.MODE_PRIVATE)
        val memberFixKey = "memberFix-$myUid"
        val needMemberFix = !prefs.getBoolean(memberFixKey, false)
        val memberFixOk = java.util.concurrent.atomic.AtomicBoolean(true)
        // 방별 독립 코루틴 (S5) — 오프라인의 무기한 await가 다른 방의 attach를 막지 않는다.
        // attach를 먼저 해도 안전: 삭제 대조 기준선이 attach 시점의 uploaded=1 집합이라
        // 이후 올라가는 아웃박스 메시지는 기준선 밖이다 (R3/L4).
        val jobs = synced.mapNotNull { room ->
            val remote = room.remoteId ?: return@mapNotNull null
            scope.launch {
                if (needMemberFix) {
                    if (!runCatching { ensureMembership(remote) }.isSuccess) {
                        memberFixOk.set(false)
                    }
                }
                attach(room.id, remote)
                // 아웃박스는 메시지별로 격리 — 1건 실패가 나머지를 막지 않는다 (C7 패턴)
                db.messageDao().listUnsent(room.id).forEach { message ->
                    runCatching { pushMessage(remote, message) }.onFailure {
                        android.util.Log.w("PbpSync", "아웃박스 재전송 실패 id=${message.id}", it)
                    }
                }
            }
        }
        registerFcmToken()
        jobs.joinAll()
        if (needMemberFix && memberFixOk.get()) prefs.edit().putBoolean(memberFixKey, true).apply()
    }

    /**
     * 화면 수명과 무관하게 끝까지 실행해야 하는 작업용 (N1).
     * 결과 콜백은 메인 스레드에서 호출한다.
     */
    fun runInAppScope(work: suspend () -> Boolean, onDone: (Boolean) -> Unit) {
        scope.launch {
            val ok = runCatching { work() }.getOrDefault(false)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(ok) }
        }
    }

    /**
     * 방 참여 인원 — members 문서 수를 실시간으로 흘려보낸다 (상단 바 "N명 참여 중").
     * 로컬 전용 방(공유 안 됨)은 나 혼자이므로 호출부에서 1을 쓴다.
     * 오류·최초 응답 전에는 null을 흘려 호출부가 표기를 생략할 수 있게 한다.
     */
    fun observeMemberCount(remoteRoomId: String): kotlinx.coroutines.flow.Flow<Int?> =
        kotlinx.coroutines.flow.callbackFlow {
            ensureAuth() // 규칙상 인증 없이는 읽을 수 없다
            val registration = firestore.collection("rooms").document(remoteRoomId)
                .collection("members")
                .addSnapshotListener { snapshot, error ->
                    trySend(if (error != null || snapshot == null) null else snapshot.size())
                }
            awaitClose { registration.remove() }
        }

    /** members/{myUid} 문서 보장 — 보안 규칙의 방 접근 근거 */
    private suspend fun ensureMembership(remoteRoomId: String) {
        firestore.collection("rooms").document(remoteRoomId)
            .collection("members").document(myUid)
            .set(mapOf("joinedAt" to System.currentTimeMillis()), com.google.firebase.firestore.SetOptions.merge())
            .await()
    }

    /** 참여/공유 더블탭 가드 — 같은 키의 동시 실행을 차단한다 (L2) */
    private val joinInFlight = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val shareInFlight = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    /** 방을 Firestore에 올리고 초대 코드를 돌려준다. 이미 공유된 방이면 기존 코드. */
    suspend fun shareRoom(roomId: Long): String? {
        // 더블탭 동시 실행 시 원격 방 이중 생성·백필 중복 방지 (L2)
        if (shareInFlight.putIfAbsent(roomId, true) != null) return null
        try {
            return shareRoomInternal(roomId)
        } finally {
            shareInFlight.remove(roomId)
        }
    }

    private suspend fun shareRoomInternal(roomId: Long): String? = runCatching {
        val room = db.roomDao().get(roomId) ?: return null
        ensureAuth()

        // 부분 실패 재시도 시 기존 원격 문서를 재사용 — 고아 문서·이중 생성 방지 (P3-3)
        val code = room.inviteCode ?: randomCode()
        val roomDoc = room.remoteId
            ?.let { firestore.collection("rooms").document(it) }
            ?: firestore.collection("rooms").document()
        if (room.remoteId == null) {
            roomDoc.set(
                mapOf(
                    "name" to room.name,
                    "icon" to room.icon,
                    "createdAt" to room.createdAt,
                    "inviteCode" to code,
                    "themeColor" to room.themeColor,
                    "rule" to room.rule,
                    // 갤러리 이미지는 기기 로컬 파일이라 프리셋만 상대에게 전달
                    "backgroundKey" to room.backgroundKey.takeIf { it.startsWith("preset_") },
                )
            ).await()
            // 원격 생성 직후 로컬에 기록 — 이후 단계가 실패해도 재시도가 이 문서를 이어받는다
            db.roomDao().setRemote(roomId, roomDoc.id, code)
        }
        // 멤버 등록이 규칙상 메시지 쓰기의 전제 — 백필보다 먼저 (멱등)
        ensureMembership(roomDoc.id)
        // 초대 코드 → 방 문서 매핑 (규칙이 rooms 컬렉션 쿼리를 막으므로 참가는 이 경로로).
        // 규칙상 update는 금지라 이미 있으면 건드리지 않는다 — 재시도가 여기서 죽지 않게 (R2)
        val codeDoc = firestore.collection("inviteCodes").document(code)
        val existingMapping = codeDoc.get().await()
        if (!existingMapping.exists()) {
            codeDoc.set(mapOf("roomId" to roomDoc.id, "createdAt" to System.currentTimeMillis()))
                .await()
        } else if (existingMapping.getString("roomId") != roomDoc.id) {
            // 코드 충돌(사실상 없음) — 새 코드로 다시 시도하도록 실패 처리
            error("초대 코드 $code 가 다른 방에 이미 매핑되어 있습니다")
        }
        // 미업로드분 백필 — WriteBatch로 왕복 최소화 (배치당 최대 450건).
        // remoteId를 커밋 전에 저장해 중간 크래시 시에도 아웃박스가 같은 문서로 재시도(멱등)
        db.messageDao().listUnsent(roomId).chunked(Protocol.BATCH_SIZE).forEach { chunk ->
            val batch = firestore.batch()
            val refs = chunk.mapNotNull { message ->
                val avatarId = message.senderImagePath?.let { path ->
                    runCatching { avatars.ensureUploaded(roomDoc.id, path) }.getOrNull()
                }
                // 원자 선점 (L3) — 동시 전송 중인 메시지에 두 번째 문서를 만들지 않는다
                val remoteId = resolveRemoteId(
                    message, roomDoc.collection("messages").document().id,
                ) ?: return@mapNotNull null
                batch.set(
                    roomDoc.collection("messages").document(remoteId),
                    SyncMapping.toMap(message, myUid, avatarId),
                )
                message.id
            }
            if (refs.isNotEmpty()) {
                batch.commit().await()
                refs.forEach { localId -> db.messageDao().setUploaded(localId) }
            }
        }
        attach(roomId, roomDoc.id)
        registerFcmTokenForRoom(roomDoc.id) // 새 방의 멤버 문서에만 등록 (F3)
        code
    }.getOrNull()

    /** 초대 코드로 상대의 방에 참여한다. 성공하면 로컬 방 ID를 돌려준다. */
    suspend fun joinRoom(code: String): Long? {
        // 더블탭 동시 실행 시 같은 원격 방에 로컬 방 2개가 생기는 분열 방지 (L2)
        if (joinInFlight.putIfAbsent(code, true) != null) return null
        try {
            return joinRoomInternal(code)
        } finally {
            joinInFlight.remove(code)
        }
    }

    private suspend fun joinRoomInternal(code: String): Long? = runCatching {
        db.roomDao().findByInviteCode(code)?.let { return it.id } // 이미 참여한 방
        ensureAuth()

        // inviteCodes/{code} → 방 문서. (구버전 코드용 폴백: rooms 컬렉션 쿼리 —
        // 보안 규칙 배포 후에는 실패하므로 방을 다시 공유해 새 코드를 쓰면 된다)
        val roomDoc = runCatching {
            firestore.collection("inviteCodes").document(code).get().await()
                .getString("roomId")
                ?.let { firestore.collection("rooms").document(it).get().await() }
                ?.takeIf { it.exists() }
        }.getOrNull() ?: firestore.collection("rooms")
            .whereEqualTo("inviteCode", code).limit(1).get().await()
            .documents.firstOrNull() ?: return null
        val create = createLocalRoom ?: return null

        // 코드가 달라도 같은 원격 방이면 기존 로컬 방을 재사용 (L2)
        db.roomDao().findByRemoteId(roomDoc.id)?.let { return it.id }

        // 멤버 등록이 규칙상 메시지 읽기의 전제 — 리스너 attach보다 먼저
        ensureMembership(roomDoc.id)

        val roomId = create(
            roomDoc.getString("name") ?: "공유 캠페인",
            roomDoc.getLong("themeColor") ?: com.pbp.shared.Protocol.DEFAULT_THEME_COLOR,
            roomDoc.getString("backgroundKey") ?: com.pbp.shared.Protocol.DEFAULT_BACKGROUND,
            roomDoc.getString("rule") ?: com.pbp.shared.Rules.COC7,
        )
        db.roomDao().setRemote(roomId, roomDoc.id, code)
        // 참여 인사 — 오너 프로필명으로 (처음 참여할 때 한 번)
        val greeting = Message(
            roomId = roomId,
            type = com.pbp.app.data.MessageType.SYSTEM,
            body = "'${com.pbp.app.data.OwnerProfile.name.ifBlank { "플레이어" }}' 님이 참여하셨습니다.",
            createdAt = System.currentTimeMillis(),
        )
        val insertedGreeting = greeting.copy(id = db.messageDao().insert(greeting))
        push(roomDoc.id, listOf(insertedGreeting))
        attach(roomId, roomDoc.id) // 리스너 초기 스냅샷이 기존 대화를 채운다
        registerFcmTokenForRoom(roomDoc.id) // 새 방의 멤버 문서에만 등록 (F3)
        roomId
    }.getOrNull()

    /** 로그 초기화 중 리스너 재접속용 — attach의 공개 통로 (L1) */
    fun reattach(localRoomId: Long, remoteRoomId: String) = attach(localRoomId, remoteRoomId)

    /**
     * 방의 서버 메시지를 전부 삭제한다(로그 리셋). 문서 삭제가 상대 기기의
     * REMOVED 리스너로 전파되어 상대의 로컬 로그도 함께 지워진다.
     * Firestore 쓰기는 자체 타임아웃이 없어 오프라인이면 무한 대기하므로 60초 상한 (L1).
     */
    suspend fun wipeMessages(remoteRoomId: String, knownRemoteIds: List<String>): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(60_000) {
            wipeMessagesInternal(remoteRoomId, knownRemoteIds)
        } ?: false

    private suspend fun wipeMessagesInternal(
        remoteRoomId: String,
        knownRemoteIds: List<String>,
    ): Boolean = runCatching {
        ensureAuth()
        // 사전 전체 get() 없이 로컬이 아는 remoteId로 직접 삭제 (P7) — 초기화 비용 절반.
        // 로컬이 모르는 극소수 잔여 문서(detach~wipe 사이 도착분)는 reattach 후 다시 내려온다.
        val collection = firestore.collection("rooms").document(remoteRoomId)
            .collection("messages")
        knownRemoteIds.chunked(Protocol.BATCH_SIZE).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { batch.delete(collection.document(it)) }
            batch.commit().await()
        }
        true
    }.getOrDefault(false)

    /** 로컬에 저장된 메시지들을 Firestore로 올린다. 실패해도 로컬은 이미 저장된 상태. */
    fun push(remoteRoomId: String, messages: List<Message>) {
        scope.launch {
            runCatching { ensureAuth() }
            // 한 건이 실패해도 나머지(예: 짝이 되는 다이스 메시지)는 시도한다 (C7)
            messages.forEach { message ->
                runCatching { pushMessage(remoteRoomId, message) }.onFailure {
                    android.util.Log.w("PbpSync", "메시지 전송 실패 id=${message.id}", it)
                }
            }
        }
    }

    /**
     * remoteId를 원자적으로 확보한다 (L3): 스냅샷의 remoteId → 원자 선점 시도 →
     * 선점 실패면 다른 경로(동시 전송·백필)가 먼저 발급한 값을 DB에서 재조회.
     * 같은 메시지가 서로 다른 원격 문서로 두 번 올라가는 레이스를 막는다.
     */
    private suspend fun resolveRemoteId(message: Message, candidate: String): String? {
        message.remoteId?.let { return it }
        if (db.messageDao().claimRemoteId(message.id, candidate) == 1) return candidate
        return db.messageDao().get(message.id)?.remoteId
    }

    private suspend fun pushMessage(remoteRoomId: String, message: Message) {
        val avatarId = message.senderImagePath?.let { path ->
            runCatching { avatars.ensureUploaded(remoteRoomId, path) }.getOrNull()
        }
        val collection = firestore.collection("rooms").document(remoteRoomId)
            .collection("messages")
        // 멱등 전송(P1-2): 문서 ID를 먼저 로컬에 고정(원자 선점, L3) — 업로드 후
        // 크래시해도 재전송이 같은 문서를 덮어써 중복이 생기지 않는다
        val remoteId = resolveRemoteId(message, collection.document().id) ?: return
        collection.document(remoteId).set(SyncMapping.toMap(message, myUid, avatarId)).await()
        db.messageDao().setUploaded(message.id)
    }

    /** 마스터의 테마·배경 변경을 상대에게 전파 (갤러리 이미지는 로컬 파일이라 프리셋만) */
    fun pushRoomSettings(remoteRoomId: String, themeColor: Long, backgroundKey: String) {
        scope.launch {
            runCatching {
                ensureAuth()
                firestore.collection("rooms").document(remoteRoomId)
                    .update(
                        mapOf(
                            "themeColor" to themeColor,
                            "backgroundKey" to backgroundKey.takeIf { it.startsWith("preset_") },
                        )
                    ).await()
            }.onFailure { android.util.Log.w("PbpSync", "방 설정 전파 실패", it) }
        }
    }

    /** 내 수정을 상대에게 전파 */
    fun pushEdit(remoteRoomId: String, messageRemoteId: String, body: String, editedAt: Long) {
        scope.launch {
            runCatching {
                ensureAuth()
                firestore.collection("rooms").document(remoteRoomId)
                    .collection("messages").document(messageRemoteId)
                    .update(mapOf("body" to body, "editedAt" to editedAt)).await()
            }.onFailure {
                // 구버전 신원(deviceId)으로 올린 메시지는 규칙상 수정 불가 — 조용한 분기 방지 (N6)
                android.util.Log.w("PbpSync", "메시지 수정 전파 실패 doc=$messageRemoteId", it)
            }
        }
    }

    /** 내 삭제를 상대에게 전파 */
    fun pushDelete(remoteRoomId: String, messageRemoteId: String) {
        scope.launch {
            runCatching {
                ensureAuth()
                firestore.collection("rooms").document(remoteRoomId)
                    .collection("messages").document(messageRemoteId).delete().await()
            }.onFailure { android.util.Log.w("PbpSync", "메시지 삭제 전파 실패 doc=$messageRemoteId", it) }
        }
    }

    /** 스냅샷 1건 — 방별 채널로 직렬 처리해 순서 역전(빠른 편집 유실)을 막는다 (P2-1) */
    private class SnapshotEvent(
        val changes: List<DocumentChange>,
        val allIds: Set<String>,
        val fromCache: Boolean,
    )

    private val eventChannels =
        java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.channels.Channel<SnapshotEvent>>()

    private fun attach(localRoomId: Long, remoteRoomId: String) {
        // 확인-후-등록 레이스 방지 — putIfAbsent로 자리를 선점한다 (S4).
        // isAttached·detach도 이 맵 기준이라 선점 실패 = 이미 attach 진행/완료.
        if (attachedRemotes.putIfAbsent(localRoomId, remoteRoomId) != null) return
        attachRoomDoc(localRoomId, remoteRoomId)
        val channel =
            kotlinx.coroutines.channels.Channel<SnapshotEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        eventChannels[localRoomId] = channel
        scope.launch {
            // 삭제 대조 기준선을 리스너 등록 **전에** 고정 (L4) — 등록 후 조회하면
            // 그 밀리초 틈에 업로드 완료된 메시지가 기준선에 들어가 잘못 지워질 수 있다.
            val baseline = runCatching { db.messageDao().listRemoteIdsForRoom(localRoomId) }
                .getOrDefault(emptyList()).toSet()
            // 전체 ID 집합은 삭제 대조(reconcile) 한 번에만 쓰인다 — 그 뒤에는
            // 이벤트마다 수천 개짜리 집합을 재구성하지 않는다 (M1)
            val reconcilePending = java.util.concurrent.atomic.AtomicBoolean(true)
            val registration = firestore.collection("rooms").document(remoteRoomId)
                .collection("messages").orderBy("createdAt")
                .addSnapshotListener { snapshot, error ->
                    // 리스너 에러를 무시하면 동기화가 죽어도 무신호 (P3-4)
                    if (error != null) {
                        android.util.Log.w("PbpSync", "메시지 리스너 오류 room=$remoteRoomId", error)
                        // 권한 거부 = 인증/멤버십 문제 — 재인증 후 다시 붙는다 (N5)
                        if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            recoverAuth(localRoomId, remoteRoomId)
                        }
                    }
                    if (snapshot == null) return@addSnapshotListener
                    channel.trySend(
                        SnapshotEvent(
                            changes = snapshot.documentChanges.toList(),
                            allIds = if (reconcilePending.get()) {
                                snapshot.documents.mapTo(mutableSetOf()) { it.id }
                            } else emptySet(),
                            fromCache = snapshot.metadata.isFromCache,
                        )
                    )
                }
            // 코루틴이 등록하는 사이 detach가 왔으면 즉시 해제 — 리스너 누수 방지 (S4)
            if (!attachedRemotes.containsKey(localRoomId)) {
                registration.remove()
                channel.close()
                return@launch
            }
            listeners[localRoomId] = registration
            var needReconcile = true
            for (event in channel) {
                // 삭제 대조는 서버 전체 상태가 확실한 첫 서버 스냅샷에서만 (캐시 스냅샷 제외)
                val doReconcile = needReconcile && !event.fromCache
                // 처리 실패(FK 위반·SQLite 예외 등)가 앱 크래시로 번지지 않게 (P1-3)
                val ok = runCatching {
                    processSnapshot(
                        localRoomId, remoteRoomId, event,
                        if (doReconcile) baseline else null,
                    )
                }.isSuccess
                // 실패한 스냅샷에서 reconcile 기회를 소진하지 않는다 (S6)
                if (doReconcile && ok) {
                    needReconcile = false
                    reconcilePending.set(false) // 이후 이벤트는 allIds 구성 생략 (M1)
                }
                // 정상 수신 중이면 권한 복구 백오프 카운터 리셋
                if (ok) recoverAttempts.remove(localRoomId)
            }
        }
    }

    private suspend fun processSnapshot(
        localRoomId: Long,
        remoteRoomId: String,
        event: SnapshotEvent,
        /** null이 아니면 이 기준선과 첫 서버 스냅샷을 대조해 사라진 문서를 정리 */
        reconcileBaseline: Set<String>?,
    ) {
        val changes = event.changes
        val addedDocs = changes
            .filter { it.type == DocumentChange.Type.ADDED }
            .map { it.document }
            .filter {
                val author = it.getString("authorUid")
                // 내 신원(auth UID)과 구버전 신원(deviceId) 모두 내 발신으로 취급
                !it.metadata.hasPendingWrites() && author != myUid && author != deviceId
            }
        // ADDED dedup은 일괄 조회로 — IN 절은 SQLite 변수 한도(999) 아래로 청크 (P1-3).
        // 본문·편집시각도 함께 가져와 실제로 달라진 것만 갱신한다 (P4)
        val knownRows = addedDocs.map { it.id }.chunked(900)
            .flatMap { db.messageDao().listByRemoteIds(it) }.associateBy { it.remoteId }
        val pendingUpdates = mutableListOf<Triple<String, String, Long?>>()

        for (doc in addedDocs) {
            // 문서 1건의 예외가 같은 스냅샷의 나머지 문서를 삼키지 않게 격리 (S6)
            runCatching {
                val knownRow = knownRows[doc.id]
                if (knownRow != null) {
                    // 이미 있는 문서가 ADDED로 재도착 = 리스너가 없던 사이의 편집 가능성 (P1-1).
                    // 콜드 스타트의 initial snapshot은 전부 여길 지나므로, 변경 없으면
                    // UPDATE를 건너뛰어 Flow 무효화 홍수를 막는다 (P4)
                    val newBody = doc.getString("body") ?: ""
                    val newEditedAt = doc.getLong("editedAt")
                    if (knownRow.body != newBody || knownRow.editedAt != newEditedAt) {
                        pendingUpdates += Triple(doc.id, newBody, newEditedAt)
                    }
                    return@runCatching
                }
                // 발신자 프로필 이미지가 함께 온 경우 내려받아 로컬 경로로 연결
                val avatarPath = doc.getString("avatarId")?.let { avatarId ->
                    runCatching { avatars.resolve(remoteRoomId, avatarId) }.getOrNull()
                }
                val message = SyncMapping.fromMap(doc.id, doc.data ?: emptyMap(), localRoomId)
                    .copy(senderImagePath = avatarPath)
                // 유니크 인덱스 충돌(-1)이면 이미 있는 메시지 — 알림을 다시 띄우지 않는다 (C6)
                if (db.messageDao().insert(message) != -1L) {
                    onIncomingMessage?.invoke(message, remoteRoomId)
                }
            }.onFailure {
                android.util.Log.w("PbpSync", "수신 메시지 처리 실패 doc=${doc.id}", it)
            }
        }

        // 실제 변경분만 한 트랜잭션으로 일괄 갱신 (P4) — 건별 UPDATE가 messages 테이블을
        // 수천 번 무효화해 시작 직후 채팅 화면이 버벅이던 원인 제거
        if (pendingUpdates.isNotEmpty()) {
            db.withTransaction {
                pendingUpdates.forEach { (remoteId, body, editedAt) ->
                    db.messageDao().updateBodyByRemoteId(remoteId, body, editedAt)
                }
            }
        }

        for (change in changes) {
            val doc = change.document
            if (doc.metadata.hasPendingWrites()) continue
            when (change.type) {
                DocumentChange.Type.MODIFIED -> {
                    db.messageDao().updateBodyByRemoteId(
                        remoteId = doc.id,
                        body = doc.getString("body") ?: "",
                        editedAt = doc.getLong("editedAt"),
                    )
                }
                DocumentChange.Type.REMOVED -> {
                    db.messageDao().deleteByRemoteId(doc.id)
                }
                DocumentChange.Type.ADDED -> {} // 위에서 일괄 처리
            }
        }

        if (reconcileBaseline != null) {
            SyncReconcile.deletedRemoteIds(reconcileBaseline, event.allIds).forEach { missing ->
                db.messageDao().deleteByRemoteId(missing)
            }
        }
    }

    /** 권한 거부 복구 (N5): 리스너를 떼고 재인증·멤버십 보강 후 다시 붙는다 */
    private val recovering = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
    private val recoverAttempts = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    private fun recoverAuth(localRoomId: Long, remoteRoomId: String) {
        if (recovering.putIfAbsent(localRoomId, true) != null) return
        scope.launch {
            try {
                // 재접속 전까지는 리스너가 죽은 상태 — FCM 경로가 알림을 맡아야 한다
                detach(localRoomId)
                // 권한 거부가 지속되면(설정 오류 등) 지수 백오프로 무한 재시도 폭주 방지
                val attempt = recoverAttempts.merge(localRoomId, 1, Int::plus) ?: 1
                val delayMs = (3_000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(120_000L)
                kotlinx.coroutines.delay(delayMs)
                authUid = null // 익명 로그인 재시도
                ensureAuth()
                runCatching { ensureMembership(remoteRoomId) }
                attach(localRoomId, remoteRoomId)
            } finally {
                recovering.remove(localRoomId)
            }
        }
    }

    /** 방 문서 리스너 — 마스터가 바꾼 테마·배경을 실시간 반영 */
    private fun attachRoomDoc(localRoomId: Long, remoteRoomId: String) {
        if (roomListeners.containsKey(localRoomId)) return
        roomListeners[localRoomId] = firestore.collection("rooms").document(remoteRoomId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.w("PbpSync", "방 문서 리스너 오류 room=$remoteRoomId", error)
                }
                if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
                if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
                val themeColor = snapshot.getLong("themeColor")
                val backgroundKey = snapshot.getString("backgroundKey")
                scope.launch {
                    val room = db.roomDao().get(localRoomId) ?: return@launch
                    if (themeColor != null && themeColor != room.themeColor) {
                        db.roomDao().setThemeColor(localRoomId, themeColor)
                    }
                    if (backgroundKey != null && backgroundKey.startsWith("preset_") &&
                        backgroundKey != room.backgroundKey
                    ) {
                        db.roomDao().setBackground(localRoomId, backgroundKey)
                    }
                }
            }
    }

    fun detach(localRoomId: Long) {
        listeners.remove(localRoomId)?.remove()
        roomListeners.remove(localRoomId)?.remove()
        eventChannels.remove(localRoomId)?.close()
        attachedRemotes.remove(localRoomId)
    }

    /** 방을 나가면서 원격 멤버 문서(FCM 토큰 포함)를 정리 — 유령 푸시 방지 (P2-2) */
    fun leaveRoom(remoteRoomId: String) {
        scope.launch {
            runCatching {
                ensureAuth()
                val members = firestore.collection("rooms").document(remoteRoomId)
                    .collection("members")
                // 레거시(deviceId) 문서를 먼저 — 내 문서를 먼저 지우면 멤버가 아니게 되어
                // 이어지는 삭제가 규칙에 거부되고 fcmToken이 남아 유령 푸시가 계속된다 (R5)
                if (myUid != deviceId) {
                    runCatching { members.document(deviceId).delete().await() }
                }
                members.document(myUid).delete().await()
            }
        }
    }

    private fun randomCode(): String {
        val alphabet = Protocol.INVITE_ALPHABET
        return (1..6).map { alphabet.random() }.joinToString("")
    }

    // ── FCM 백그라운드 푸시 ──────────────────────────────
    // 각 기기의 FCM 토큰을 rooms/{id}/members/{deviceId}에 등록해 두면,
    // Cloud Functions(functions/index.js)가 새 메시지마다 상대 토큰으로
    // 데이터 푸시(본문 비노출, 보낸 이 이름만)를 보낸다.

    /** 현재 토큰을 모든 공유 방의 멤버 문서에 등록. 토큰이 안 바뀌었으면 건너뛴다. */
    fun registerFcmToken(force: Boolean = false) {
        if (isDemo) return
        scope.launch {
            runCatching {
                ensureAuth()
                firestore // Firebase 초기화 보장
                val token = com.google.firebase.messaging.FirebaseMessaging.getInstance()
                    .token.await()
                val prefs = context.getSharedPreferences("pbp", Context.MODE_PRIVATE)
                if (!force && prefs.getString("lastFcmToken", null) == token) return@runCatching
                uploadFcmTokenInternal(token)
                prefs.edit().putString("lastFcmToken", token).apply()
            }
        }
    }

    /** 특정 방에만 토큰 등록 — 공유/참가 직후 그 방만. 전체 방 순회 쓰기 제거 (F3) */
    fun registerFcmTokenForRoom(remoteRoomId: String) {
        if (isDemo) return
        scope.launch {
            runCatching {
                ensureAuth()
                firestore // Firebase 초기화 보장
                val token = com.google.firebase.messaging.FirebaseMessaging.getInstance()
                    .token.await()
                uploadFcmTokenTo(remoteRoomId, token)
                context.getSharedPreferences("pbp", Context.MODE_PRIVATE)
                    .edit().putString("lastFcmToken", token).apply()
            }
        }
    }

    /** FcmService.onNewToken에서 호출 — 갱신된 토큰 재등록 */
    fun onNewFcmToken(token: String) {
        if (isDemo) return
        scope.launch {
            runCatching {
                ensureAuth()
                uploadFcmTokenInternal(token)
                context.getSharedPreferences("pbp", Context.MODE_PRIVATE)
                    .edit().putString("lastFcmToken", token).apply()
            }
        }
    }

    private suspend fun uploadFcmTokenInternal(token: String) {
        db.roomDao().listSynced().forEach { room ->
            val remote = room.remoteId ?: return@forEach
            uploadFcmTokenTo(remote, token)
        }
    }

    private suspend fun uploadFcmTokenTo(remote: String, token: String) {
        firestore.collection("rooms").document(remote)
            .collection("members").document(myUid)
            .set(
                mapOf("fcmToken" to token, "updatedAt" to System.currentTimeMillis()),
                com.google.firebase.firestore.SetOptions.merge(),
            )
            .await()
        // 구버전 deviceId 키 멤버 문서 정리 — 같은 토큰으로 이중 푸시 방지
        if (myUid != deviceId) {
            runCatching {
                firestore.collection("rooms").document(remote)
                    .collection("members").document(deviceId).delete().await()
            }
        }
    }
}
