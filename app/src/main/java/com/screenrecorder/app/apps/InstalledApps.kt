package com.screenrecorder.app.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A launchable app installed on the device. */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

object InstalledApps {

    suspend fun load(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_ALL,
        )
        resolved.asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() }
                    ?: packageName
                InstalledApp(packageName, label)
            }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
