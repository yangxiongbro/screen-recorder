package com.screenrecorder.app.apps

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

/**
 * Detects which app is currently in the foreground using [UsageStatsManager].
 *
 * Requires the "Usage access" special permission; [hasPermission] returns `false` otherwise and
 * callers should simply disable the feature that depends on it.
 */
class ForegroundAppMonitor(private val context: Context) {

    private val usageStatsManager: UsageStatsManager? =
        context.getSystemService(UsageStatsManager::class.java)

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = runCatching {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }.getOrDefault(AppOpsManager.MODE_DEFAULT)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun currentForegroundPackage(): String? {
        if (!hasPermission()) return null
        val manager = usageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = manager.queryEvents(now - LOOKBACK_MS, now) ?: return null
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        var latestTimestamp = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= latestTimestamp) {
                latestPackage = event.packageName
                latestTimestamp = event.timeStamp
            }
        }
        return latestPackage
    }

    companion object {
        private const val LOOKBACK_MS = 60_000L

        fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }
}
