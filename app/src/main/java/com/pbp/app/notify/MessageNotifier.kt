package com.pbp.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.app.NotificationCompat
import com.pbp.app.MainActivity
import com.pbp.app.R
import com.pbp.app.data.Message

/**
 * 푸시 알림 (스펙 7장).
 * 형식 고정: 보낸 이 원형 프로필 아이콘 + 이름 + "~님의 메시지가 도착했습니다."
 * 채팅 본문은 어떤 경우에도 노출하지 않는다 (GM 서술 포함).
 */
class MessageNotifier(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "messages"
    }

    init {
        val channel = NotificationChannel(
            CHANNEL_ID, "새 메시지", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "미확인 메시지 도착 알림" }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun notify(senderName: String, notificationId: Int, imagePath: String?) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(senderName)
            .setContentText("${senderName}님의 메시지가 도착했습니다.")
            .setContentIntent(pending)
            .setAutoCancel(true)
        circularAvatar(imagePath)?.let { builder.setLargeIcon(it) }

        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, builder.build())
    }

    /** 경로→원형 비트맵 캐시 — 알림마다 디코드+합성하지 않는다 (F3) */
    private val avatarCache = HashMap<String, Pair<Long, Bitmap>>()

    /** 프로필 이미지는 항상 원형 — 알림 아이콘에서도 유지한다. */
    private fun circularAvatar(path: String?): Bitmap? {
        if (path == null) return null
        val file = java.io.File(path)
        if (!file.exists()) return null
        synchronized(avatarCache) {
            avatarCache[path]?.let { (modified, bitmap) ->
                if (modified == file.lastModified()) return bitmap
            }
        }
        val source = BitmapFactory.decodeFile(path) ?: return null
        val size = minOf(source.width, source.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val clip = Path().apply {
            addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
        }
        canvas.clipPath(clip)
        canvas.drawBitmap(source, (size - source.width) / 2f, (size - source.height) / 2f, Paint(Paint.ANTI_ALIAS_FLAG))
        synchronized(avatarCache) { avatarCache[path] = file.lastModified() to output }
        return output
    }
}
