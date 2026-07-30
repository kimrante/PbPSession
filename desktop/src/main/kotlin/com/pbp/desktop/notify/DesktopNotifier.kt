package com.pbp.desktop.notify

import java.awt.Color
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * OS 트레이 알림 — 모바일 알림과 동일 규칙(스펙 7장):
 * 본문은 어떤 경로로도 노출하지 않고 "~님의 메시지가 도착했습니다."만 띄운다.
 * 창이 포커스를 잃었을 때만 호출되며, 트레이 미지원 환경이면 조용히 무시한다.
 */
object DesktopNotifier {

    private val trayIcon: TrayIcon? by lazy {
        if (!SystemTray.isSupported()) return@lazy null
        runCatching {
            TrayIcon(appIcon(), "PbP").apply {
                isImageAutoSize = true
                SystemTray.getSystemTray().add(this)
            }
        }.getOrNull()
    }

    /** 앱 아이콘 축소판 — 어두운 남색 바탕 + 크림색 d10 마름모 (아이콘 자산 없이 생성) */
    private fun appIcon(): BufferedImage {
        val size = 16
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x2A, 0x33, 0x40)
        g.fillRoundRect(0, 0, size, size, 6, 6)
        g.color = Color(0xEF, 0xE8, 0xD6)
        val c = size / 2
        g.fillPolygon(intArrayOf(c, size - 3, c, 3), intArrayOf(3, c, size - 3, c), 4)
        g.dispose()
        return img
    }

    fun notifyMessage(senderName: String) {
        runCatching {
            trayIcon?.displayMessage(
                "PbP",
                "${senderName}님의 메시지가 도착했습니다.",
                TrayIcon.MessageType.NONE,
            )
        }
    }
}
