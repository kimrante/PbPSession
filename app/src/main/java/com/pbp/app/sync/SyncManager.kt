package com.pbp.app.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.pbp.app.R
import com.pbp.app.data.AppDatabase
import com.pbp.app.data.Message
import com.pbp.app.data.PbpRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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

    /** 참여(join) 시 방+GM 생성에 필요. PbpApp에서 주입한다. */
    var repository: PbpRepository? = null

    /** 상대 메시지 수신 시 호출(알림용). PbpApp에서 주입한다. */
    var onIncomingMessage: ((Message) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableMapOf<Long, ListenerRegistration>()
    private val roomListeners = mutableMapOf<Long, ListenerRegistration>()

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

    private val firestore: FirebaseFirestore by lazy {
        val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId(projectId)
                .setApplicationId(context.getString(R.string.firebase_app_id))
                .setApiKey(context.getString(R.string.firebase_api_key))
                .setGcmSenderId(context.getString(R.string.firebase_sender_id))
                .build(),
        )
        FirebaseFirestore.getInstance(app!!).apply {
            // demo- 프로젝트는 로컬 에뮬레이터로 (10.0.2.2 = 에뮬레이터에서 본 호스트 PC)
            if (isDemo) useEmulator("10.0.2.2", 8080)
        }
    }

    /** 앱 시작 시 공유된 방들의 수신 리스너를 다시 걸고 FCM 토큰을 등록한다. */
    fun start() = scope.launch {
        val synced = db.roomDao().listSynced()
        synced.forEach { room -> room.remoteId?.let { attach(room.id, it) } }
        if (synced.isNotEmpty()) registerFcmToken()
    }

    /** 방을 Firestore에 올리고 초대 코드를 돌려준다. 이미 공유된 방이면 기존 코드. */
    suspend fun shareRoom(roomId: Long): String? = runCatching {
        val room = db.roomDao().get(roomId) ?: return null
        room.inviteCode?.let { return it }

        val code = randomCode()
        val roomDoc = firestore.collection("rooms").document()
        roomDoc.set(
            mapOf(
                "name" to room.name,
                "icon" to room.icon,
                "createdAt" to room.createdAt,
                "inviteCode" to code,
                "themeColor" to room.themeColor,
                // 갤러리 이미지는 기기 로컬 파일이라 프리셋만 상대에게 전달
                "backgroundKey" to room.backgroundKey.takeIf { it.startsWith("preset_") },
            )
        ).await()
        // 지금까지의 대화를 백필
        db.messageDao().listForRoom(roomId).forEach { message ->
            pushMessage(roomDoc.id, message)
        }
        db.roomDao().setRemote(roomId, roomDoc.id, code)
        attach(roomId, roomDoc.id)
        registerFcmToken()
        code
    }.getOrNull()

    /** 초대 코드로 상대의 방에 참여한다. 성공하면 로컬 방 ID를 돌려준다. */
    suspend fun joinRoom(code: String): Long? = runCatching {
        db.roomDao().findByInviteCode(code)?.let { return it.id } // 이미 참여한 방

        val snapshot = firestore.collection("rooms")
            .whereEqualTo("inviteCode", code).limit(1).get().await()
        val roomDoc = snapshot.documents.firstOrNull() ?: return null
        val repo = repository ?: return null

        val roomId = repo.createRoom(
            name = roomDoc.getString("name") ?: "공유 캠페인",
            icon = roomDoc.getString("icon") ?: "🎲",
            isMaster = false, // 참여자는 마스터가 아니다 — 테마·배경 읽기 전용
            themeColor = roomDoc.getLong("themeColor")
                ?: com.pbp.app.ui.theme.PbpPalette.DEFAULT_THEME_COLOR,
            backgroundKey = roomDoc.getString("backgroundKey")
                ?: com.pbp.app.ui.theme.PbpPalette.DEFAULT_BACKGROUND,
        )
        db.roomDao().setRemote(roomId, roomDoc.id, code)
        attach(roomId, roomDoc.id) // 리스너 초기 스냅샷이 기존 대화를 채운다
        registerFcmToken()
        roomId
    }.getOrNull()

    /** 로컬에 저장된 메시지들을 Firestore로 올린다. 실패해도 로컬은 이미 저장된 상태. */
    fun push(remoteRoomId: String, messages: List<Message>) {
        scope.launch {
            runCatching { messages.forEach { pushMessage(remoteRoomId, it) } }
        }
    }

    private suspend fun pushMessage(remoteRoomId: String, message: Message) {
        val avatarId = message.senderImagePath?.let { path ->
            runCatching { ensureAvatarUploaded(remoteRoomId, path) }.getOrNull()
        }
        val doc = firestore.collection("rooms").document(remoteRoomId)
            .collection("messages").document()
        doc.set(SyncMapping.toMap(message, deviceId, avatarId)).await()
        db.messageDao().setRemoteId(message.id, doc.id)
    }

    /** 마스터의 테마·배경 변경을 상대에게 전파 (갤러리 이미지는 로컬 파일이라 프리셋만) */
    fun pushRoomSettings(remoteRoomId: String, themeColor: Long, backgroundKey: String) {
        scope.launch {
            runCatching {
                firestore.collection("rooms").document(remoteRoomId)
                    .update(
                        mapOf(
                            "themeColor" to themeColor,
                            "backgroundKey" to backgroundKey.takeIf { it.startsWith("preset_") },
                        )
                    ).await()
            }
        }
    }

    /** 내 수정을 상대에게 전파 */
    fun pushEdit(remoteRoomId: String, messageRemoteId: String, body: String, editedAt: Long) {
        scope.launch {
            runCatching {
                firestore.collection("rooms").document(remoteRoomId)
                    .collection("messages").document(messageRemoteId)
                    .update(mapOf("body" to body, "editedAt" to editedAt)).await()
            }
        }
    }

    /** 내 삭제를 상대에게 전파 */
    fun pushDelete(remoteRoomId: String, messageRemoteId: String) {
        scope.launch {
            runCatching {
                firestore.collection("rooms").document(remoteRoomId)
                    .collection("messages").document(messageRemoteId).delete().await()
            }
        }
    }

    private fun attach(localRoomId: Long, remoteRoomId: String) {
        if (listeners.containsKey(localRoomId)) return
        attachRoomDoc(localRoomId, remoteRoomId)
        listeners[localRoomId] = firestore.collection("rooms").document(remoteRoomId)
            .collection("messages").orderBy("createdAt")
            .addSnapshotListener { snapshot, _ ->
                val changes = snapshot?.documentChanges.orEmpty()
                if (changes.isEmpty()) return@addSnapshotListener
                scope.launch {
                    for (change in changes) {
                        val doc = change.document
                        if (doc.metadata.hasPendingWrites()) continue // 내 로컬 쓰기 에코
                        when (change.type) {
                            DocumentChange.Type.ADDED -> {
                                if (doc.getString("authorUid") == deviceId) continue
                                if (db.messageDao().countByRemoteId(doc.id) > 0) continue
                                // 발신자 프로필 이미지가 함께 온 경우 내려받아 로컬 경로로 연결
                                val avatarPath = doc.getString("avatarId")?.let { avatarId ->
                                    runCatching { resolveAvatar(remoteRoomId, avatarId) }.getOrNull()
                                }
                                val message =
                                    SyncMapping.fromMap(doc.id, doc.data ?: emptyMap(), localRoomId)
                                        .copy(senderImagePath = avatarPath)
                                db.messageDao().insert(message)
                                onIncomingMessage?.invoke(message)
                            }
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
                        }
                    }
                }
            }
    }

    /** 방 문서 리스너 — 마스터가 바꾼 테마·배경을 실시간 반영 */
    private fun attachRoomDoc(localRoomId: Long, remoteRoomId: String) {
        if (roomListeners.containsKey(localRoomId)) return
        roomListeners[localRoomId] = firestore.collection("rooms").document(remoteRoomId)
            .addSnapshotListener { snapshot, _ ->
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
    }

    private fun randomCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { alphabet.random() }.joinToString("")
    }

    // ── FCM 백그라운드 푸시 ──────────────────────────────
    // 각 기기의 FCM 토큰을 rooms/{id}/members/{deviceId}에 등록해 두면,
    // Cloud Functions(functions/index.js)가 새 메시지마다 상대 토큰으로
    // 데이터 푸시(본문 비노출, 보낸 이 이름만)를 보낸다.

    /** 현재 토큰을 모든 공유 방의 멤버 문서에 등록 */
    fun registerFcmToken() {
        if (isDemo) return
        scope.launch {
            runCatching {
                firestore // Firebase 초기화 보장
                val token = com.google.firebase.messaging.FirebaseMessaging.getInstance()
                    .token.await()
                uploadFcmTokenInternal(token)
            }
        }
    }

    /** FcmService.onNewToken에서 호출 — 갱신된 토큰 재등록 */
    fun onNewFcmToken(token: String) {
        if (isDemo) return
        scope.launch { runCatching { uploadFcmTokenInternal(token) } }
    }

    private suspend fun uploadFcmTokenInternal(token: String) {
        db.roomDao().listSynced().forEach { room ->
            val remote = room.remoteId ?: return@forEach
            firestore.collection("rooms").document(remote)
                .collection("members").document(deviceId)
                .set(mapOf("fcmToken" to token, "updatedAt" to System.currentTimeMillis()))
                .await()
        }
    }

    // ── 프로필 이미지 동기화 ──────────────────────────────
    // Storage 없이 Firestore 문서에 축소 이미지(base64)를 내장한다.
    //   rooms/{roomId}/avatars/{contentHash}: { data: base64(JPEG ≤256px) }
    // 메시지 문서의 avatarId가 이 해시를 가리키고, 수신 측은 파일로 복원해 캐시한다.

    private val uploadedAvatars = mutableSetOf<String>()

    private suspend fun ensureAvatarUploaded(remoteRoomId: String, imagePath: String): String? {
        val bytes = downscaleToJpeg(imagePath) ?: return null
        val hash = md5(bytes)
        val key = "$remoteRoomId/$hash"
        if (key !in uploadedAvatars) {
            firestore.collection("rooms").document(remoteRoomId)
                .collection("avatars").document(hash)
                .set(mapOf("data" to Base64.encodeToString(bytes, Base64.NO_WRAP)))
                .await()
            uploadedAvatars += key
        }
        return hash
    }

    private suspend fun resolveAvatar(remoteRoomId: String, avatarId: String): String? {
        val file = File(context.filesDir, "avatars/remote-$avatarId.jpg")
        if (file.exists()) return file.absolutePath
        val doc = firestore.collection("rooms").document(remoteRoomId)
            .collection("avatars").document(avatarId).get().await()
        val data = doc.getString("data") ?: return null
        file.parentFile?.mkdirs()
        file.writeBytes(Base64.decode(data, Base64.NO_WRAP))
        return file.absolutePath
    }

    /** 긴 변 256px 이하 JPEG로 축소 (Firestore 1MB 문서 제한을 넉넉히 하회) */
    private fun downscaleToJpeg(path: String, maxSize: Int = 256): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = maxSize.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else decoded
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return out.toByteArray()
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
