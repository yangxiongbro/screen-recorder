package com.screenrecorder.app.util

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Size
import android.view.WindowManager

/** Display helpers shared by the service (no Activity available there). */
object DisplayUtils {

    fun currentDisplaySize(context: Context): Size {
        val windowManager = context.getSystemService(WindowManager::class.java)
            ?: return Size(1080, 1920)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Size(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Size(metrics.widthPixels, metrics.heightPixels)
        }
    }

    fun currentDensityDpi(context: Context): Int =
        context.resources.displayMetrics.densityDpi
}
