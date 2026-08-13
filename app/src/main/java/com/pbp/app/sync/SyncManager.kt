package com.pbp.app.sync

import android.content.Context
import androidx.room.withTransaction
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.pbp.app.R
import com.pbp.app.data.AppDatabase
import com.pbp.app.data.Message
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
    var createLocalRoom: (
        suspend (
            name: String, themeColor: Long, backgroundKey: String, rule: String,
            remoteId: String, inviteCode: String, isMaster: Boolean,
        ) -> Long
        )? = null

    /** 프로필 이미지 업로드·복원 (B5로 분리) */
    private val avatars by lazy { AvatarStore(context) { firestore } }

    /** 캐릭터 프로필을 계정에 보관해 기기 사이로 옮긴다 */
    private val profileSync by lazy { ProfileSync(db, { firestore }, avatars) }

    /** 상대 메시지 수신 시 호출(알림용, 두 번째 인자는 원격 방 ID). PbpApp에서 주입한다. */
    var onIncomingMessage: ((Message, String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 여러 IO 스레드 + FCM 바인더 스레드에서 접근하므로 동시성 컬렉션 (N7)
    private val listeners = java.util.concurrent.ConcurrentHashMap<Long, ListenerRegistration>()
    private val roomListeners = java.util.concurrent.ConcurrentHashMap<Long, ListenerRegistration>()
    private val attachedRemotes = java.util.concurrent.ConcurrentHashMap<Long, String>()

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
                val restored = awaitRestoredUser(auth)
                when {
                    restored != null -> restored.uid
                    // **한 번이라도 신원이 있었다면 새로 만들지 않는다.**
                    // 여기서 익명 계정을 새로 파면 구글 연결도, 방 멤버십도 통째로 잃는다 —
                    // "연결이 자꾸 풀린다"로 나타난다. 복구를 다음 실행에 맡기는 편이 낫다
                    knownUid() != null -> {
                        android.util.Log.w(
                            "PbpSync",
                            "저장된 계정을 복원하지 못했습니다 — 새로 만들지 않고 기다립니다",
                        )
                        null
                    }
                    else -> auth.signInAnonymously().await().user?.uid
                }
            }.getOrNull()
            if (uid != null) {
                authUid = uid
                rememberUid(uid)
            }
        }
        return myUid
    }

    /**
     * 디스크에 저장된 로그인을 복원할 때까지 잠깐 기다린다.
     *
     * `currentUser`는 보통 즉시 채워지지만, 그렇지 않은 순간에 익명 로그인을 새로 하면
     * 기존 신원이 사라진다. 리스너는 등록 즉시 현재 상태로 한 번 불리므로, 이미 있으면
     * 바로 돌아온다.
     */
    private suspend fun awaitRestoredUser(
        auth: com.google.firebase.auth.FirebaseAuth,
    ): com.google.firebase.auth.FirebaseUser? {
        auth.currentUser?.let { return it }
        if (knownUid() == null) return null // 처음 켠 기기 — 기다릴 것이 없다
        return kotlinx.coroutines.withTimeoutOrNull(RESTORE_WAIT_MS) {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                val listener = object : com.google.firebase.auth.FirebaseAuth.AuthStateListener {
                    override fun onAuthStateChanged(
                        instance: com.google.firebase.auth.FirebaseAuth,
                    ) {
                        val user = instance.currentUser ?: return
                        instance.removeAuthStateListener(this)
                        if (continuation.isActive) continuation.resumeWith(Result.success(user))
                    }
                }
                auth.addAuthStateListener(listener)
                continuation.invokeOnCancellation { auth.removeAuthStateListener(listener) }
            }
        }
    }

    /** 마지막으로 쓰던 신원 — 앱 데이터와 함께 지워지므로 Firebase 저장본과 수명이 같다 */
    private fun knownUid(): String? =
        context.getSharedPreferences("pbp", Context.MODE_PRIVATE).getString("lastAuthUid", null)

    private fun rememberUid(uid: String) {
        context.getSharedPreferences("pbp", Context.MODE_PRIVATE)
            .edit().putString("lastAuthUid", uid).apply()
    }

    /** 구글 계정 연결 — 폰과 PC가 같은 신원을 쓰기 위한 첫 단계 */
    private val googleAccount by lazy { GoogleAccountLinker(context, { firebaseApp }) }

    /** 연결된 구글 계정 주소. 연결 전이면 null */
    val linkedGoogleEmail: String? get() = if (isDemo) null else googleAccount.linkedEmail

    /** 지금 신원이 익명인지 (구글 계정이 붙지 않은 상태) */
    val isAnonymousAccount: Boolean get() = !isDemo && googleAccount.isAnonymous

    internal suspend fun linkGoogleAccount(
        activity: android.app.Activity,
    ): GoogleAccountLinker.Result {
        val before = myUid
        val result = googleAccount.link(activity)
        if (result is GoogleAccountLinker.Result.Recovered) {
            // 신원이 바뀌었다 — 새 uid로 방마다 멤버를 다시 등록하지 않으면
            // 규칙상 지금 쓰던 방을 읽지도 쓰지도 못한다
            authUid = result.uid
            rememberUid(result.uid)
            android.util.Log.i("PbpSync", "계정 복구: $before → ${result.uid}")
            db.roomDao().listSynced().forEach { room ->
                val remote = room.remoteId ?: return@forEach
                runCatching { ensureMembership(remote) }
                indexRoom(remote, room.name, room.isMaster)
            }
            registerFcmToken()
        }
        return result
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
        ensureAuth()
        // 공유 중인 방이 하나도 없어도 계정에 적힌 세션은 가져와야 한다 —
        // 앱을 새로 깐 기기가 여기로 이어받는다
        if (synced.isEmpty()) {
            adoptAccountRooms()
            syncProfiles()
            return@launch
        }
        // 구버전(deviceId 키) 방들에 auth UID 멤버 문서를 1회 보충 — 규칙 배포 후에도 접근 유지
        val prefs = context.getSharedPreferences("pbp", Context.MODE_PRIVATE)
        val memberFixKey = "memberFix-$myUid"
        val needMemberFix = !prefs.getBoolean(memberFixKey, false)
        // 세션 목록도 uid마다 한 번 채운다 — 구글 계정으로 갈아타면 새 uid에는 목록이 비어 있다.
        // **키에 2를 붙인 이유**: 예전 판은 쓰기가 거부돼도 '했음'을 찍어서, 규칙 배포 전에
        // 한 번 켠 기기는 영영 목록을 올리지 않았다. 키를 갈아 그 기기들이 한 번 더 올리게 한다
        val indexKey = "roomIndex2-$myUid"
        val needIndex = !prefs.getBoolean(indexKey, false)
        val memberFixOk = java.util.concurrent.atomic.AtomicBoolean(true)
        // 색인 쓰기가 실패했는데도 '했음'으로 표시하면 다시는 시도하지 않는다 —
        // 규칙 배포 전에 한 번 켰다는 이유로 이어하기가 영영 비어 있게 된다
        val indexOk = java.util.concurrent.atomic.AtomicBoolean(true)
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
                if (needIndex && !indexRoom(remote, room.name, room.isMaster)) indexOk.set(false)
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
        adoptAccountRooms()
        syncProfiles()
        if (needMemberFix && memberFixOk.get()) prefs.edit().putBoolean(memberFixKey, true).apply()
        if (needIndex && indexOk.get()) prefs.edit().putBoolean(indexKey, true).apply()
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
     * 내 세션 목록에 이 방을 적어 둔다 — 같은 계정으로 붙은 **다른 기기가 방을 찾는
     * 유일한 통로**다(방 목록 열거는 규칙이 막는다). 실패해도 이 기기의 동작에는
     * 영향이 없으므로 호출부를 막지 않는다.
     */
    private suspend fun indexRoom(remoteRoomId: String, name: String, isMaster: Boolean): Boolean =
        runCatching {
            firestore.collection("users").document(myUid)
                .collection("rooms").document(remoteRoomId)
                .set(
                    mapOf(
                        "name" to name,
                        "joinedAt" to System.currentTimeMillis(),
                        // 내가 이 방의 마스터인지 — 다른 기기에서도 GM 권한이 이어져야 한다
                        "master" to isMaster,
                    )
                )
                .await()
        }.onFailure {
            android.util.Log.w("PbpSync", "세션 목록 기록 실패 room=$remoteRoomId", it)
        }.isSuccess

    /**
     * members/{myUid} 문서 보장 — 보안 규칙의 방 접근 근거.
     * platform을 함께 남긴다: 읽음 확인은 모바일끼리만 성립해서 상대가
     * 어느 기기인지 알아야 표시 여부를 정할 수 있다.
     */
    private suspend fun ensureMembership(remoteRoomId: String) {
        firestore.collection("rooms").document(remoteRoomId)
            .collection("members").document(myUid)
            .set(
                mapOf(
                    Protocol.Field.JOINED_AT to System.currentTimeMillis(),
                    Protocol.Field.PLATFORM to Protocol.Platform.ANDROID,
                ),
                com.google.firebase.firestore.SetOptions.merge(),
            )
            .await()
    }

    /** 방마다 마지막으로 올린 읽음 시각 — 같은 값을 되풀이해 쓰지 않기 위한 가드 */
    private val pushedReadAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 읽음 확인 — 내가 읽은 마지막 상대 메시지 시각을 내 멤버 문서에 남긴다.
     * [readAt]이 직전에 올린 값 이하면 쓰지 않는다(불필요한 쓰기 억제).
     *
     * platform을 함께 쓰는 이유: 이 기능 이전에 만들어진 멤버 문서에는 platform이 없어
     * 상대가 나를 모바일로 알아보지 못한다. 읽음을 올리는 순간 같이 붙여 두면
     * 별도 마이그레이션 없이 자연스럽게 채워진다.
     */
    suspend fun pushReadReceipt(remoteRoomId: String, readAt: Long) {
        if ((pushedReadAt[remoteRoomId] ?: 0L) >= readAt) return
        runCatching {
            ensureAuth()
            firestore.collection("rooms").document(remoteRoomId)
                .collection("members").document(myUid)
                .set(
                    mapOf(
                        Protocol.Field.LAST_READ_AT to readAt,
                        Protocol.Field.PLATFORM to Protocol.Platform.ANDROID,
                    ),
                    com.google.firebase.firestore.SetOptions.merge(),
                )
                .await()
        }.onSuccess { pushedReadAt[remoteRoomId] = readAt }
    }

    /**
     * 상대 멤버 문서에서 뽑아 쓰는 상태.
     *
     * @param readAt 상대(모바일)가 읽은 마지막 메시지 시각. 데스크톱뿐이면 null
     * @param typingUntil 이 시각까지 "입력 중". 플랫폼을 가리지 않는다 — PC에서 치는 것도 보인다
     */
    data class PeerState(
        val readAt: Long? = null,
        val typingUntil: Long = 0L,
        val typingName: String? = null,
        /** 상대 기기가 올린 캐릭터 명단 — 판정 요청 대상 목록 (J0) */
        val peerCharacters: List<PeerCharacter> = emptyList(),
    )

    /**
     * 상대 기기의 캐릭터 한 명. **값은 이름만** 들어 있다 — 숫자는 굴리는 쪽에서 그때
     * 자기 프로필에서 읽는다.
     */
    data class PeerCharacter(
        /** 상대가 붙인 고유 id. 구버전 상대는 없다(null) — 그때만 이름으로 다룬다 */
        val id: String?,
        /** 프로필 이미지의 아바타 id — 받는 쪽이 파일로 풀어 쓴다 */
        val avatarId: String?,
        val name: String,
        val emoji: String,
        val nameColor: Long?,
        val stats: List<String>,
    )

    /**
     * 상대 상태 구독 — **리스너는 하나**다. 읽음 확인과 입력 중 표시가 같은 members
     * 스냅샷에서 나오므로, 입력 중 표시를 켜도 읽기가 추가로 늘지 않는다.
     */
    fun observePeerState(remoteRoomId: String): kotlinx.coroutines.flow.Flow<PeerState> =
        kotlinx.coroutines.flow.callbackFlow {
            ensureAuth() // 규칙상 인증 없이는 읽을 수 없다
            val registration = firestore.collection("rooms").document(remoteRoomId)
                .collection("members")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        // 예전에는 빈 상태만 흘리고 끝이라 읽음·입력 중이 조용히 죽었다.
                        // 흐름을 끊어 호출부의 retryWhen이 다시 붙게 한다 (B7)
                        android.util.Log.w("PbpSync", "멤버 리스너 오류 room=$remoteRoomId", error)
                        trySend(PeerState())
                        close(error)
                        return@addSnapshotListener
                    }
                    if (snapshot == null) {
                        trySend(PeerState())
                        return@addSnapshotListener
                    }
                    val peers = snapshot.documents.filter { it.id != myUid }
                    // 읽음 확인은 모바일끼리만 — 데스크톱은 lastReadAt을 쓰지 않는다
                    val peerRead = peers
                        .filter { it.getString(Protocol.Field.PLATFORM) == Protocol.Platform.ANDROID }
                        .mapNotNull { it.getLong(Protocol.Field.LAST_READ_AT) }
                        .maxOrNull()
                    val typing = peers.maxByOrNull {
                        it.getLong(Protocol.Field.TYPING_UNTIL) ?: 0L
                    }
                    // typingUntil은 상대 시계로 찍힌 값이라 내 시계와 어긋날 수 있다.
                    // 내 시계 기준 TTL을 넘지 않게 잘라 잔상을 막는다 (읽음 확인과 달리
                    // 타이핑만 두 시계를 비교하는 구조라 이 보정이 필요하다, P2)
                    val cap = System.currentTimeMillis() + Protocol.TYPING_TTL_MS
                    val characters = peers.flatMap { doc ->
                        parseCharacters(doc.get(Protocol.Field.CHARACTERS))
                    }
                    trySend(
                        PeerState(
                            peerCharacters = characters,
                            readAt = peerRead,
                            typingUntil = minOf(
                                typing?.getLong(Protocol.Field.TYPING_UNTIL) ?: 0L,
                                cap,
                            ),
                            typingName = typing?.getString(Protocol.Field.TYPING_NAME),
                        )
                    )
                }
            awaitClose { registration.remove() }
        }

    /**
     * 스냅샷의 characters 파싱 — 모양이 어긋난 항목은 그 항목만 버린다.
     * 상대가 쓴 데이터라 형식을 믿을 수 없다 (SyncMapping.fromMap과 같은 방어 방식).
     */
    private fun parseCharacters(raw: Any?): List<PeerCharacter> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = (map[Protocol.Character.NAME] as? String)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            PeerCharacter(
                id = (map[Protocol.Character.ID] as? String)?.takeIf { it.isNotBlank() },
                avatarId = (map[Protocol.Character.AVATAR_ID] as? String)?.takeIf { it.isNotBlank() },
                name = name,
                emoji = map[Protocol.Character.EMOJI] as? String ?: "",
                nameColor = (map[Protocol.Character.NAME_COLOR] as? Number)?.toLong(),
                stats = (map[Protocol.Character.STATS] as? List<*>)
                    ?.filterIsInstance<String>()
                    ?.filter { it.isNotBlank() }
                    .orEmpty(),
            )
        }
    }

    /** 방마다 마지막으로 올린 캐릭터 명단 — 같은 내용을 다시 쓰지 않기 위한 가드 */
    private val pushedCharacters = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 이 기기의 캐릭터 명단을 멤버 문서에 올린다 (J0).
     *
     * 읽음 확인·입력 중이 쓰는 members 리스너에 얹히므로 **추가 읽기가 0**이다.
     * 명단이 그대로면 쓰지 않는다 — 프로필은 자주 바뀌지 않아 실제 쓰기는 거의 없다.
     */
    /** 프로필 이미지를 방 avatars에 올려 두고 그 id를 돌려준다 (판정 후보 목록용) */
    suspend fun avatarIdFor(remoteRoomId: String, imagePath: String): String? =
        runCatching { avatars.ensureUploaded(remoteRoomId, imagePath) }.getOrNull()

    /** 받아 둔 아바타 id를 로컬 파일 경로로 — 없으면 내려받는다 */
    suspend fun avatarPath(remoteRoomId: String, avatarId: String): String? =
        runCatching { avatars.resolve(remoteRoomId, avatarId) }.getOrNull()

    suspend fun pushCharacters(remoteRoomId: String, characters: List<Map<String, Any?>>) {
        // payload 전체를 그대로 비교한다 — 이름·값만 보다가 emoji·이름색만 바꾸면
        // 전파되지 않았다 (B4). 필드가 늘어도 여기를 고칠 일이 없다
        val signature = characters.toString()
        if (pushedCharacters[remoteRoomId] == signature) return
        runCatching {
            ensureAuth()
            firestore.collection("rooms").document(remoteRoomId)
                .collection("members").document(myUid)
                .set(
                    mapOf(Protocol.Field.CHARACTERS to characters),
                    // 다른 필드(lastReadAt·typingUntil·fcmToken)를 지우면 안 된다
                    com.google.firebase.firestore.SetOptions.merge(),
                )
                .await()
        }.onSuccess { pushedCharacters[remoteRoomId] = signature }
    }

    /** 방마다 마지막으로 올린 입력 중 시각 — 스로틀 기준 */
    private val lastTypingPushAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 로컬 방 id 기준 스로틀 — 키 입력마다 DB를 조회하지 않으려고 먼저 본다 (P4) */
    private val lastTypingDueAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** 지금 올릴 차례인가. true를 돌려주면 그 시각을 소비한 것으로 친다 */
    fun typingDue(localRoomId: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = lastTypingDueAt[localRoomId] ?: 0L
        if (now - last < Protocol.TYPING_THROTTLE_MS) return false
        lastTypingDueAt[localRoomId] = now
        return true
    }

    /**
     * 입력 중 알림 — **실제 입력 이벤트가 있을 때만** 부른다.
     * 포커스만 있고 가만히 있거나, 써 둔 글을 그대로 두고 있는 상태는 입력 중이 아니다.
     * 손을 멈추면 아무것도 쓰지 않고 typingUntil이 지나 저절로 꺼진다.
     */
    suspend fun pushTyping(remoteRoomId: String, name: String) {
        // 스로틀은 typingDue가 이미 판정했다 — 여기서 또 재면 ms 차이로 한 번씩
        // 조용히 건너뛰어 표시가 깜빡인다 (V5)
        val now = System.currentTimeMillis()
        lastTypingPushAt[remoteRoomId] = now
        runCatching {
            ensureAuth()
            firestore.collection("rooms").document(remoteRoomId)
                .collection("members").document(myUid)
                .set(
                    mapOf(
                        Protocol.Field.TYPING_UNTIL to now + Protocol.TYPING_TTL_MS,
                        Protocol.Field.TYPING_NAME to name,
                        Protocol.Field.PLATFORM to Protocol.Platform.ANDROID,
                    ),
                    com.google.firebase.firestore.SetOptions.merge(),
                )
                .await()
        }
    }

    /**
     * 전송·입력창 비움·포커스 해제 때 즉시 끈다. 올린 적이 없으면 쓰지 않는다.
     *
     * 스로틀 슬롯도 함께 비운다 — 안 그러면 전송 직후 다시 치기 시작해도 최대 스로틀
     * 시간만큼 상대에게 안 보인다 (V5).
     */
    suspend fun clearTyping(remoteRoomId: String, localRoomId: Long? = null) {
        localRoomId?.let { lastTypingDueAt.remove(it) }
        if (lastTypingPushAt.remove(remoteRoomId) == null) return
        runCatching {
            ensureAuth()
            firestore.collection("rooms").document(remoteRoomId)
                .collection("members").document(myUid)
                .set(
                    mapOf(Protocol.Field.TYPING_UNTIL to 0L),
                    com.google.firebase.firestore.SetOptions.merge(),
                )
                .await()
        }
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
        var code = room.inviteCode ?: randomCode()
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
                    // 배경은 공유하지 않는다 — 각자 원하는 배경을 쓴다 (기기 로컬 설정)
                )
            ).await()
            // 원격 생성 직후 로컬에 기록 — 이후 단계가 실패해도 재시도가 이 문서를 이어받는다
            db.roomDao().setRemote(roomId, roomDoc.id, code)
        }
        // 멤버 등록이 규칙상 메시지 쓰기의 전제 — 백필보다 먼저 (멱등)
        ensureMembership(roomDoc.id)
        // 초대 코드 → 방 문서 매핑 (규칙이 rooms 컬렉션 쿼리를 막으므로 참가는 이 경로로).
        // 규칙상 update는 금지라 이미 있으면 건드리지 않는다 — 재시도가 여기서 죽지 않게 (R2)
        // 충돌하면 **코드를 바꿔 가며** 빈자리를 찾는다. 예전에는 실패로 끝냈는데
        // 재시도가 room.inviteCode(=충돌한 그 코드)를 그대로 다시 써서 그 방은
        // 영영 공유할 수 없었다 (A4)
        var mapped = false
        repeat(CODE_ATTEMPTS) {
            if (mapped) return@repeat
            val codeDoc = firestore.collection("inviteCodes").document(code)
            val existingMapping = codeDoc.get().await()
            when {
                !existingMapping.exists() -> {
                    // 참가에 쓰인 코드는 사라진다 (SV2). 그대로 되살리면 1회용이
                    // 무의미해지므로, 예전에 나눠 준 코드라면 새 코드로 바꿔 발급한다
                    if (code == room.inviteCode && room.remoteId != null) {
                        code = randomCode()
                        return@repeat
                    }
                    codeDoc.set(
                        mapOf("roomId" to roomDoc.id, "createdAt" to System.currentTimeMillis())
                    ).await()
                    mapped = true
                }
                // 이전 시도가 여기까지 갔던 경우 — 그대로 쓴다 (멱등)
                existingMapping.getString("roomId") == roomDoc.id -> mapped = true
                else -> code = randomCode()
            }
        }
        if (!mapped) error("초대 코드를 발급하지 못했습니다 — 잠시 후 다시 시도해주세요")
        // 충돌로 코드가 바뀌었으면 방 문서와 로컬을 함께 맞춘다
        if (code != room.inviteCode) {
            roomDoc.update("inviteCode", code).await()
            db.roomDao().setRemote(roomId, roomDoc.id, code)
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
        indexRoom(roomDoc.id, room.name, room.isMaster)
        code
    }.getOrNull()

    /**
     * 같은 계정으로 다른 기기에서 참여 중인 세션을 이 기기에도 만든다.
     *
     * 초대 코드로 들어오는 것과 달리 **참여 인사를 남기지 않는다** — 새 사람이 온 게
     * 아니라 같은 사람이 기기를 하나 더 켠 것이다. 멤버 문서도 이미 내 uid 것이 있다.
     *
     * @return 로컬 방 ID. 이미 있으면 그 방
     */
    private suspend fun adoptRoom(remoteRoomId: String, isMaster: Boolean): Long? = runCatching {
        db.roomDao().findByRemoteId(remoteRoomId)?.let { return it.id }
        val create = createLocalRoom ?: return null
        val roomDoc = firestore.collection("rooms").document(remoteRoomId).get().await()
            .takeIf { it.exists() } ?: return null
        // 계정이 같아도 멤버 문서는 방마다 있어야 한다 (규칙의 접근 근거) — 멱등
        ensureMembership(remoteRoomId)
        val roomId = create(
            roomDoc.getString("name") ?: "공유 캠페인",
            roomDoc.getLong("themeColor") ?: com.pbp.shared.Protocol.DEFAULT_THEME_COLOR,
            com.pbp.shared.Protocol.DEFAULT_BACKGROUND,
            roomDoc.getString("rule") ?: com.pbp.shared.Rules.COC7,
            remoteRoomId,
            roomDoc.getString("inviteCode") ?: "",
            isMaster,
        )
        attach(roomId, remoteRoomId) // 초기 스냅샷이 지금까지의 대화를 채운다
        registerFcmTokenForRoom(remoteRoomId)
        roomId
    }.getOrNull()

    /** 프로필 하나를 계정에 올린다 (만들거나 고친 직후) */
    fun pushProfile(profile: com.pbp.app.data.CharacterProfile) = scope.launch {
        if (linkedGoogleEmail == null) return@launch
        ensureAuth()
        profileSync.push(myUid, profile)
    }

    /** 지운 프로필은 계정에서도 뺀다 */
    fun deleteProfileRemote(characterId: String) = scope.launch {
        if (linkedGoogleEmail == null) return@launch
        ensureAuth()
        profileSync.delete(myUid, characterId)
    }

    /**
     * 계정의 프로필을 이 기기로 가져오고, 아직 안 올린 내 프로필은 올린다.
     *
     * 방을 먼저 가져온 뒤에 불러야 한다 — 방 전용 프로필은 그 방이 있어야 자리를 잡는다.
     *
     * @return 새로 오거나 갱신된 프로필 수
     */
    suspend fun syncProfiles(): Int {
        if (linkedGoogleEmail == null) return 0
        ensureAuth()
        val prefs = context.getSharedPreferences("pbp", Context.MODE_PRIVATE)
        val key = "profilesPushed-$myUid"
        if (!prefs.getBoolean(key, false)) {
            profileSync.pushAll(myUid)
            prefs.edit().putBoolean(key, true).apply()
        }
        return profileSync.pull(myUid)
    }

    /**
     * 계정에 적혀 있는 세션 중 이 기기에 없는 것을 가져온다.
     * 구글 계정을 연결하지 않았으면 목록이 곧 내 로컬 방이라 읽지 않는다(읽기 과금).
     *
     * @return 새로 가져온 세션 수
     */
    suspend fun adoptAccountRooms(): Int = runCatching {
        if (linkedGoogleEmail == null) return 0
        ensureAuth()
        val indexed = firestore.collection("users").document(myUid)
            .collection("rooms").get().await().documents
        var adopted = 0
        indexed.forEach { entry ->
            val mine = entry.getBoolean("master") ?: false
            if (db.roomDao().findByRemoteId(entry.id) == null && adoptRoom(entry.id, mine) != null) {
                adopted++
            }
        }
        adopted
    }.getOrElse {
        android.util.Log.w("PbpSync", "계정 세션 가져오기 실패", it)
        0
    }

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

        // 방 생성과 remoteId 기록을 한 트랜잭션에서 (H6) — 나눠 하면 그 사이 크래시에
        // 원격과 이어지지 않은 고아 방이 남고, 다시 참여할 때 방이 하나 더 생긴다
        val roomId = create(
            roomDoc.getString("name") ?: "공유 캠페인",
            roomDoc.getLong("themeColor") ?: com.pbp.shared.Protocol.DEFAULT_THEME_COLOR,
            // 배경은 공유 대상이 아니다 — 참여자는 기본 배경으로 시작하고 각자 바꾼다
            com.pbp.shared.Protocol.DEFAULT_BACKGROUND,
            roomDoc.getString("rule") ?: com.pbp.shared.Rules.COC7,
            roomDoc.id,
            code,
            false,
        )
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
        indexRoom(roomDoc.id, roomDoc.getString("name") ?: "공유 캠페인", isMaster = false)
        // 다 들어온 뒤 코드를 없앤다 (SV2) — 코드는 1회용이라, 이후 그 코드를 손에
        // 넣은 제3자는 방을 찾지 못한다. 참가가 끝난 다음이라야 실패해도 잃는 게 없다
        runCatching {
            firestore.collection("inviteCodes").document(code).delete().await()
        }
        roomId
    }.getOrNull()

    /**
     * 세션을 서버에서 통째로 지운다 — 상대 기기에서도 사라진다.
     *
     * 메시지·아바타를 먼저 비우고 마지막에 방 문서를 지운다. 순서가 반대면 방이
     * 사라진 뒤에는 하위 문서를 지울 권한(멤버 확인)이 없어져 쓰레기가 남는다.
     * 상대의 멤버 문서는 규칙상 내가 지울 수 없다(SV6) — 방이 없으니 무해하다.
     *
     * @return 방 문서까지 지웠으면 true
     */
    suspend fun destroyRoom(remoteRoomId: String, knownRemoteIds: List<String>): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(60_000) {
            runCatching {
                ensureAuth()
                wipeMessagesInternal(remoteRoomId, knownRemoteIds)
                val room = firestore.collection("rooms").document(remoteRoomId)
                // 남은 메시지·아바타 정리 — 로컬이 모르는 문서도 여기서 걷는다
                listOf("messages", "avatars").forEach { name ->
                    runCatching {
                        room.collection(name).get().await().documents
                            .chunked(Protocol.BATCH_SIZE)
                            .forEach { chunk ->
                                val batch = firestore.batch()
                                chunk.forEach { batch.delete(it.reference) }
                                batch.commit().await()
                            }
                    }
                }
                runCatching { room.collection("members").document(myUid).delete().await() }
                runCatching {
                    firestore.collection("users").document(myUid)
                        .collection("rooms").document(remoteRoomId).delete().await()
                }
                room.delete().await()
                true
            }.getOrDefault(false)
        } ?: false

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
        // 데스크톱 상대는 폴링이라 '문서가 사라졌다'를 볼 수 없다 — 방 문서에 표식을 남겨
        // 그쪽 메타 폴이 자기 캐시를 비우게 한다 (A6). 실패해도 삭제 자체는 성공이다
        runCatching {
            firestore.collection("rooms").document(remoteRoomId)
                // 로컬 시계로 적으면 시계가 앞선 기기에서 초기화했을 때 안내 메시지가
                // 상대 화면에서 걸러진다 (DC5) — 재는 자를 서버 시각으로 통일
                .update(
                    Protocol.Field.LOGS_CLEARED_AT,
                    com.google.firebase.firestore.FieldValue.serverTimestamp(),
                )
                .await()
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

    /**
     * 테마 컬러 변경을 상대에게 전파.
     * **배경은 전파하지 않는다** — 방 배경은 기기마다 따로 고르는 개인 설정이다.
     */
    fun pushRoomSettings(remoteRoomId: String, themeColor: Long) {
        scope.launch {
            runCatching {
                ensureAuth()
                firestore.collection("rooms").document(remoteRoomId)
                    .update(mapOf("themeColor" to themeColor)).await()
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
                    .update(
                        mapOf(
                            "body" to body,
                            "editedAt" to editedAt,
                            // 커서를 함께 밀어야 상대 데스크톱의 증분 질의에 다시 걸린다.
                            // 재수신은 editedAt 병합이 이미 멱등이라 안전하다 (B3)
                            Protocol.Field.SYNC_AT to
                                com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        )
                    ).await()
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

    /**
     * attach 세대 (B2). 등록은 코루틴에서 일어나는데, 그 사이에 detach→reattach가 끼면
     * attachedRemotes에 키가 **다시** 있어 "취소되지 않았다"고 오판했다. 그러면 구
     * registration이 살아남고 listeners는 새 attach의 것을 덮어써, 이후 detach가 엉뚱한
     * 쪽을 제거한다 — 방 전체 스냅샷을 계속 받는 유령 리스너(= read 과금 누수)다.
     */
    private val attachSequence = java.util.concurrent.atomic.AtomicLong(0)
    private val attachGeneration = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    private fun attach(localRoomId: Long, remoteRoomId: String) {
        // 확인-후-등록 레이스 방지 — putIfAbsent로 자리를 선점한다 (S4).
        // detach도 이 맵 기준이라 선점 실패 = 이미 attach 진행/완료.
        if (attachedRemotes.putIfAbsent(localRoomId, remoteRoomId) != null) return
        val generation = attachSequence.incrementAndGet()
        attachGeneration[localRoomId] = generation
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
                        // 오류 = 등록 종료. 종류를 가리지 않고 다시 붙인다 (G3).
                        // 권한 거부일 때만 인증/멤버십부터 다시 세운다 (N5)
                        recoverListener(
                            localRoomId, remoteRoomId,
                            reauth = error.code ==
                                com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED,
                        )
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
            // 등록하는 사이 detach(또는 detach→reattach)가 왔으면 즉시 해제 (S4·B2).
            // 키 존재가 아니라 **내 세대가 아직 현역인가**로 판단해야 재attach와 구분된다
            if (attachGeneration[localRoomId] != generation) {
                registration.remove()
                channel.close()
                return@launch
            }
            listeners[localRoomId] = registration
            // 맵에 넣는 사이에도 detach가 끼어들 수 있다 (H5) — 넣고 나서 한 번 더 본다.
            // 어긋났으면 맵 밖 유령 리스너가 남아 재시작까지 읽기가 새어 나간다
            if (attachGeneration[localRoomId] != generation) {
                listeners.remove(localRoomId)?.remove()
                channel.close()
                return@launch
            }
            // 방 문서 리스너도 세대를 확인한 뒤에 건다 (H5) — attach 동기 구간에서
            // 걸면 그 사이 들어온 detach가 지나간 뒤에 등록돼 그대로 남는다
            attachRoomDoc(localRoomId, remoteRoomId)
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
                } else if (avatarPath != null) {
                    // 이미 있는 메시지인데 이번엔 아바타를 받아 냈다 — 비어 있으면 채운다 (H7).
                    // 처음 받을 때 네트워크가 잠깐 끊기면 그 메시지는 영영 빈 원이었다.
                    // avatarId를 로컬에 두지 않으므로, 다시 붙을 때 오는 스냅샷이 유일한 기회다
                    db.messageDao().fillSenderImageIfMissing(doc.id, avatarPath)
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

    /**
     * 리스너가 죽었다 — 백오프 뒤 다시 붙인다 (G3).
     *
     * **Firestore 리스너는 오류 한 번으로 등록이 끝난다**(SDK 계약). 예전에는
     * PERMISSION_DENIED만 복구해서, RESOURCE_EXHAUSTED(무료 쿼터 소진)·INTERNAL 같은
     * 오류가 오면 리스너가 영구히 죽은 채였다 — FCM 알림은 오는데 열어 보면 메시지가
     * 없는 상태가 앱을 다시 켤 때까지 이어졌다. 그래서 **오류 종류를 가리지 않고**
     * 재attach하고, 재인증(authUid = null)만 권한 거부일 때로 좁힌다.
     */
    private fun recoverListener(
        localRoomId: Long,
        remoteRoomId: String,
        reauth: Boolean,
    ) {
        if (recovering.putIfAbsent(localRoomId, true) != null) return
        scope.launch {
            try {
                // 재접속 전까지는 리스너가 죽은 상태 — FCM 경로가 알림을 맡아야 한다
                detach(localRoomId)
                // 오류가 지속되면(설정 오류·쿼터 소진 등) 지수 백오프로 재시도 폭주 방지
                val attempt = recoverAttempts.merge(localRoomId, 1, Int::plus) ?: 1
                val delayMs = (3_000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(120_000L)
                kotlinx.coroutines.delay(delayMs)
                if (reauth) {
                    authUid = null // 익명 로그인 재시도
                    ensureAuth()
                    runCatching { ensureMembership(remoteRoomId) }
                }
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
                    // 메시지 리스너와 같은 경로로 되살린다 (G3) — 이쪽만 로그로 끝내면
                    // 상대가 바꾼 테마·이름이 재시작까지 영영 안 온다
                    recoverListener(
                        localRoomId, remoteRoomId,
                        reauth = error.code ==
                            com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    )
                }
                if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
                if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
                // 배경은 읽지 않는다 — 상대가 바꿔도 내 배경은 그대로다 (개인 설정)
                val themeColor = snapshot.getLong("themeColor")
                scope.launch {
                    val room = db.roomDao().get(localRoomId) ?: return@launch
                    if (themeColor != null && themeColor != room.themeColor) {
                        db.roomDao().setThemeColor(localRoomId, themeColor)
                    }
                }
            }
    }

    fun detach(localRoomId: Long) {
        attachGeneration.remove(localRoomId)
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
                // 지울 수 있는 것은 내 멤버 문서뿐이다 (SV6) — 상대 문서를 지울 수 있던
                // 때에는 상대의 푸시를 끊고 방에서 쫓아낼 수 있었다
                firestore.collection("rooms").document(remoteRoomId)
                    .collection("members").document(myUid).delete().await()
                // 내 세션 목록에서도 뺀다 — 남겨 두면 다음 시작 때 이 방이 되살아난다
                runCatching {
                    firestore.collection("users").document(myUid)
                        .collection("rooms").document(remoteRoomId).delete().await()
                }
            }
        }
    }

    private fun randomCode(): String = com.pbp.shared.Identifiers.newInviteCode()

    private companion object {
        /** 초대 코드 자리를 찾는 시도 횟수 (A4). 32^8 공간이라 한 번이면 거의 끝난다 */
        const val CODE_ATTEMPTS = 5

        /** 저장된 로그인이 복원되기를 기다리는 상한 */
        const val RESTORE_WAIT_MS = 3_000L
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
    }
}
