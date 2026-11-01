package com.screenrecorder.app.util

import java.util.concurrent.TimeUnit

object Formatters {

    fun duration(millis: Long): String {
        val safe = millis.coerceAtLeast(0L)
        val hours = TimeUnit.MILLISECONDS.toHours(safe)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(safe) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun fileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    fun bitrate(kbps: Int): String = if (kbps >= 1000) {
        val mbps = kbps / 1000.0
        if (mbps % 1.0 == 0.0) "${mbps.toInt()} Mbps" else String.format("%.1f Mbps", mbps)
    } else {
        "$kbps Kbps"
    }

    fun date(millis: Long): String {
        if (millis <= 0L) return "-"
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return format.format(java.util.Date(millis))
    }
}
