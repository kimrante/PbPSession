package com.pbp.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * 갤러리 이미지를 내부 저장소로 가져올 때 축소 저장한다.
 * 원본(수 MB)을 그대로 복사하면 아바타(24~92dp) 렌더링마다 풀사이즈 디코딩이
 * 일어나므로, 가져오는 시점에 용도별 최대 크기로 줄인다.
 */
object Images {

    /**
     * EXIF Orientation을 픽셀에 적용한다 (P2-3) — 카메라 세로 사진이
     * 90° 돌아간 채 저장되는 문제. 가져오기 시점에 한 번만 보정하면
     * 이후 내부 파일들(크롭·축소 산출물)은 EXIF가 없어 그대로 안전하다.
     */
    fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = android.graphics.Matrix()
        when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun exifOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            androidx.exifinterface.media.ExifInterface(it)
                .getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
                )
        } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)

    fun importDownscaled(
        context: Context,
        uri: Uri,
        subDir: String,
        maxSize: Int,
        quality: Int = 85,
    ): String? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val oriented = applyExifOrientation(decoded, exifOrientation(context, uri))
        val scale = maxSize.toFloat() / maxOf(oriented.width, oriented.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * scale).toInt().coerceAtLeast(1),
                (oriented.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else oriented

        val dir = File(context.filesDir, subDir).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        file.absolutePath
    }.getOrNull()
}
