package com.pbp.app.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 캡처 이미지 저장·공유.
 *
 * 저장은 **갤러리(MediaStore)**로만 한다 — 사용자가 위치를 고르는 방식(SAF)은
 * "저장했는데 어디 갔지"가 되기 때문이다. 공유는 캐시 파일 + FileProvider라 권한이 필요 없다.
 */
object CaptureSaver {

    /** 이 버전 이하에서만 저장 권한이 필요하다 (Q부터는 MediaStore가 권한 없이 써 준다) */
    const val LEGACY_STORAGE_MAX_SDK = 28

    fun needsPermission(): Boolean = Build.VERSION.SDK_INT <= LEGACY_STORAGE_MAX_SDK

    /** "PbP_등대에서 만나요_20260730_2144.png" — 파일명에 못 쓰는 글자는 치환한다 */
    fun fileName(roomName: String, index: Int, total: Int): String {
        val safe = roomName.replace(Regex("""[/\\:*?"<>|]"""), "_").ifBlank { "PbP" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val suffix = if (total > 1) "_${index + 1}of$total" else ""
        return "PbP_${safe}_$stamp$suffix.png"
    }

    /** 갤러리(Pictures/PbP)에 PNG로 저장. 실패하면 null */
    suspend fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
    ): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Q 이상: 앱 폴더를 지정할 수 있고, 쓰는 동안 갤러리에서 감춘다
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PbP")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                // API 28 이하: RELATIVE_PATH가 없으므로 절대 경로로 폴더를 만든다.
                // DATA는 Q 이상에서 쓰면 안 되므로 분기를 섞지 않는다.
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "PbP",
                )
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, fileName).absolutePath)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null
        runCatching {
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri
        }.getOrElse {
            // 절반만 쓰인 0바이트 항목이 갤러리에 남지 않게 되돌린다
            resolver.delete(uri, null, null)
            null
        }
    }

    /** 공유용 캐시 파일 — 권한이 필요 없는 경로 */
    suspend fun shareIntent(
        context: Context,
        bitmaps: List<Bitmap>,
        roomName: String,
    ): Intent? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val uris = ArrayList<Uri>()
        bitmaps.forEachIndexed { index, bitmap ->
            val file = File(dir, fileName(roomName, index, bitmaps.size))
            runCatching {
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                uris += FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }
        }
        if (uris.isEmpty()) return@withContext null
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.first()) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
        }
        intent.apply {
            type = "image/png"
            // 없으면 받는 앱에서 SecurityException이 난다
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** 화면을 벗어날 때 공유용 캐시를 비운다 */
    fun clearShareCache(context: Context) {
        File(context.cacheDir, "capture").listFiles()?.forEach { it.delete() }
    }
}
