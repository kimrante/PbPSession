package com.pbp.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.pbp.desktop.data.FirestoreRest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pbp.shared.Protocol
import com.pbp.desktop.data.AppPaths

/** 이미지 선택·축소·아바타 캐시 — Main.kt에서 분리 (리뷰 B1) */

/**
 * OS 파일 대화상자를 **AWT EDT에서** 띄운다 (E13).
 *
 * IO 스레드에서 만들고 보이면 AWT 규칙 위반이라 플랫폼에 따라 교착한다. 표시만
 * 여기서 하고 실제 파일 읽기·쓰기는 호출부(IO)에 남긴다.
 *
 * @return (디렉터리, 파일명). 취소하면 null.
 */
internal fun showFileDialog(
    title: String,
    mode: Int,
    defaultName: String? = null,
    filter: java.io.FilenameFilter? = null,
): Pair<String, String>? {
    var picked: Pair<String, String>? = null
    val show = Runnable {
        val fd = java.awt.FileDialog(null as java.awt.Frame?, title, mode)
        if (defaultName != null) fd.file = defaultName
        if (filter != null) fd.filenameFilter = filter
        fd.isVisible = true // 고를 때까지 블록
        val dir = fd.directory
        val file = fd.file
        picked = if (dir != null && file != null) dir to file else null
    }
    if (java.awt.EventQueue.isDispatchThread()) show.run()
    else java.awt.EventQueue.invokeAndWait(show)
    return picked
}

/**
 * OS 파일 선택창으로 이미지를 골라 설정 폴더(~/.pbp-desktop/<subDir>)에 저장,
 * 저장본 경로를 돌려준다. 원본이 크면 maxSize(긴 변)로 줄여 JPEG로 저장 —
 * 모바일 Images.kt와 동일 정책 (풀사이즈 디코딩으로 인한 메모리·지연 방지).
 */
internal fun pickAndStoreImage(title: String, subDir: String, maxSize: Int): String? {
    val (dir, file) = showFileDialog(
        title = title,
        mode = java.awt.FileDialog.LOAD,
        filter = { _, name ->
            name.lowercase().substringAfterLast('.', "") in
                setOf("png", "jpg", "jpeg", "webp", "bmp")
        },
    ) ?: return null
    val src = java.io.File(dir, file)
    val destDir = AppPaths.dir(subDir)
    return runCatching {
        destDir.mkdirs()
        val image = org.jetbrains.skia.Image.makeFromEncoded(src.readBytes())
        val maxDim = maxOf(image.width, image.height)
        if (maxDim <= maxSize) {
            val dest = java.io.File(destDir, "img-${System.currentTimeMillis()}.${src.extension.ifEmpty { "img" }}")
            src.copyTo(dest, overwrite = true)
            dest.absolutePath
        } else {
            val scale = maxSize.toFloat() / maxDim
            val w = (image.width * scale).toInt().coerceAtLeast(1)
            val h = (image.height * scale).toInt().coerceAtLeast(1)
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
            surface.canvas.drawImageRect(
                image,
                org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()),
            )
            val jpeg = surface.makeImageSnapshot()
                .encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 85)
                ?: error("이미지 인코딩 실패")
            val dest = java.io.File(destDir, "img-${System.currentTimeMillis()}.jpg")
            dest.writeBytes(jpeg.bytes)
            dest.absolutePath
        }
    }.onFailure { System.err.println("이미지 저장 실패: $it") }.getOrNull()
}

/**
 * 아바타 업로드용 축소 인코딩 — 모바일 downscaleToJpeg와 동일 정책:
 * 긴 변 256px, 투명이 있으면 PNG, 아니면 JPEG(82).
 */
internal fun encodeAvatarBytes(path: String, maxSize: Int = Protocol.AVATAR_MAX_PX): ByteArray? = runCatching {
    val image = org.jetbrains.skia.Image.makeFromEncoded(java.io.File(path).readBytes())
    val maxDim = maxOf(image.width, image.height)
    val scale = if (maxDim > maxSize) maxSize.toFloat() / maxDim else 1f
    val w = (image.width * scale).toInt().coerceAtLeast(1)
    val h = (image.height * scale).toInt().coerceAtLeast(1)
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(
        image,
        org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()),
    )
    val opaque = image.imageInfo.isOpaque
    surface.makeImageSnapshot()
        .encodeToData(
            if (opaque) org.jetbrains.skia.EncodedImageFormat.JPEG
            else org.jetbrains.skia.EncodedImageFormat.PNG,
            if (opaque) 82 else 100,
        )?.bytes
}.getOrNull()

internal fun md5Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("MD5").digest(bytes)
        .joinToString("") { "%02x".format(it) }

/**
 * 아바타 다운로드 디스크 캐시 (P9) — Android의 filesDir/avatars와 동일하게
 * 해시 키 파일로 저장해 실행마다 재다운로드(문서 크기만큼 read 대역폭 과금)를 없앤다.
 */
internal fun fetchAvatarCached(
    firestore: FirestoreRest,
    remoteRoomId: String,
    avatarId: String,
): ByteArray? {
    val dir = AppPaths.dir(AppPaths.AVATARS_REMOTE)
    val cached = java.io.File(dir, avatarId)
    runCatching { if (cached.exists()) return cached.readBytes() }
    val bytes = firestore.fetchAvatar(remoteRoomId, avatarId) ?: return null
    runCatching {
        dir.mkdirs()
        // 임시 파일 + 교체 — 쓰다 중단된 깨진 캐시 방지 (모바일 R7-2와 동일)
        val tmp = java.io.File(dir, "$avatarId.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(cached)) tmp.delete()
    }
    return bytes
}

/**
 * 디코드에 실패한 원격 아바타 캐시 파일을 지운다 (L2).
 * 바이트는 읽히는데 Skia가 못 여는 파일이면 재시도해도 같은 파일을 다시 읽어
 * 그 아바타가 영구히 이모지 폴백으로 남는다 — 지워야 다음에 새로 받는다.
 */
internal fun dropBrokenAvatarCache(avatarId: String) {
    runCatching { java.io.File(AppPaths.dir(AppPaths.AVATARS_REMOTE), avatarId).delete() }
}

/** 방별 업로드 완료 표시 — 같은 이미지의 중복 업로드 방지 (모바일 uploadedAvatars와 동일) */
internal val uploadedAvatarKeys: MutableSet<String> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()

/** 전송마다 아바타 재인코딩·재해시하지 않는다 (F3) — lastModified 기준 캐시 */
internal val avatarEncodeCache =
    java.util.concurrent.ConcurrentHashMap<String, Triple<Long, ByteArray, String>>()

internal fun encodedAvatarFor(path: String): Pair<ByteArray, String>? {
    val file = java.io.File(path)
    if (!file.exists()) return null
    avatarEncodeCache[path]?.let { (modified, bytes, hash) ->
        if (modified == file.lastModified()) return bytes to hash
    }
    val bytes = encodeAvatarBytes(path) ?: return null
    val hash = md5Hex(bytes)
    avatarEncodeCache[path] = Triple(file.lastModified(), bytes, hash)
    return bytes to hash
}

/** 로컬 이미지 파일 로더 — 실패는 캐시하지 않고 null (호출부는 이모지 폴백) */
@Composable
internal fun rememberLocalBitmap(path: String?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching {
                org.jetbrains.skia.Image.makeFromEncoded(java.io.File(path).readBytes())
                    .toComposeImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}
