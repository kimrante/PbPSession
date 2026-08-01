package com.pbp.app.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.firebase.firestore.FirebaseFirestore
import com.pbp.app.data.ImageSizes
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.tasks.await

/**
 * 프로필 이미지 동기화 — SyncManager에서 분리한 독립 블록 (리뷰 B5).
 *
 * Storage 없이 Firestore 문서에 축소 이미지(base64)를 내장한다:
 *   rooms/{roomId}/avatars/{contentHash}: { data: base64(JPEG/PNG ≤256px) }
 * 메시지 문서의 avatarId가 이 해시를 가리키고, 수신 측은 파일로 복원해 캐시한다.
 */
internal class AvatarStore(
    private val context: Context,
    private val firestore: () -> FirebaseFirestore,
) {
    private val uploadedAvatars: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>().also { set ->
            // 완료 기록을 영속화해 프로세스 재시작 후 첫 전송마다 방당 1회
            // 대형 문서(50-300KB)를 다시 쓰던 것 방지 (F3)
            runCatching {
                context.getSharedPreferences("pbp", Context.MODE_PRIVATE)
                    .getStringSet("uploadedAvatars", emptySet())
                    ?.let(set::addAll)
            }
        }

    private fun persistUploadedAvatars() {
        runCatching {
            context.getSharedPreferences("pbp", Context.MODE_PRIVATE)
                .edit().putStringSet("uploadedAvatars", uploadedAvatars.toSet()).apply()
        }
    }

    /** 경로→축소 JPEG 캐시 (lastModified 기준) — 메시지마다 디코딩·해시 재계산 방지 */
    private val avatarBytesCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, ByteArray>>()

    private fun avatarBytes(path: String): ByteArray? {
        val file = File(path)
        if (!file.exists()) return null
        avatarBytesCache[path]?.let { (modified, bytes) ->
            if (modified == file.lastModified()) return bytes
        }
        val bytes = downscaleForUpload(path) ?: return null
        avatarBytesCache[path] = file.lastModified() to bytes
        return bytes
    }

    suspend fun ensureUploaded(remoteRoomId: String, imagePath: String): String? {
        val bytes = avatarBytes(imagePath) ?: return null
        val hash = md5(bytes)
        val key = "$remoteRoomId/$hash"
        if (key !in uploadedAvatars) {
            firestore().collection("rooms").document(remoteRoomId)
                .collection("avatars").document(hash)
                .set(mapOf("data" to Base64.encodeToString(bytes, Base64.NO_WRAP)))
                .await()
            uploadedAvatars += key
            persistUploadedAvatars() // 재시작 후 재업로드 방지 (F3)
        }
        return hash
    }

    suspend fun resolve(remoteRoomId: String, avatarId: String): String? {
        val file = File(context.filesDir, "avatars/remote-$avatarId.jpg")
        if (file.exists()) return file.absolutePath
        val doc = firestore().collection("rooms").document(remoteRoomId)
            .collection("avatars").document(avatarId).get().await()
        val data = doc.getString("data") ?: return null
        file.parentFile?.mkdirs()
        // 임시 파일에 쓴 뒤 교체 — 쓰다 중단되면 깨진 파일이 영구 캐시되는 것 방지
        val tmp = File(file.parentFile, "remote-$avatarId.tmp")
        // 쓰기까지 통째로 감싼다 — 디스크가 차서 writeBytes가 던지면 부분 파일이 남는다 (L5)
        val moved = runCatching {
            tmp.writeBytes(Base64.decode(data, Base64.NO_WRAP))
            tmp.renameTo(file)
        }.getOrDefault(false)
        if (!moved) {
            tmp.delete()
            return null
        }
        return file.absolutePath
    }

    /**
     * 긴 변 256px 이하로 축소 (Firestore 1MB 문서 제한을 넉넉히 하회).
     * 투명 영역이 있으면 PNG, 아니면 JPEG — JPEG는 알파를 검정으로 채운다.
     */
    private fun downscaleForUpload(path: String, maxSize: Int = ImageSizes.AVATAR_UPLOAD): ByteArray? {
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
        if (bitmap.hasAlpha()) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        }
        return out.toByteArray()
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
