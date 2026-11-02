package com.screenrecorder.app.util

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Size
import android.view.Display
import android.view.WindowManager

/** Display helpers shared by the service (no Activity available there). */
object DisplayUtils {

    /**
     * Full physical size of the default display, i.e. exactly the area that MediaProjection mirrors.
     *
     * `currentWindowMetrics` must not be used here: it excludes the status/navigation bars, so a
     * VirtualDisplay created with it would squeeze the mirrored image (1440x3200 -> 1440x3088) and
     * soften every frame before it even reaches the encoder.
     */
    fun currentDisplaySize(context: Context): Size {
        val windowManager = context.getSystemService(WindowManager::class.java)
        if (windowManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.maximumWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return Size(bounds.width(), bounds.height())
                }
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return Size(metrics.widthPixels, metrics.heightPixels)
                }
            }
        }
        // Fallback: ask the display itself.
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return Size(metrics.widthPixels, metrics.heightPixels)
            }
        }
        return Size(1080, 1920)
    }

    fun currentDensityDpi(context: Context): Int =
        context.resources.displayMetrics.densityDpi
}
