package com.screenrecorder.app

import android.app.Application

/**
 * Application entry point.
 *
 * The notification channel is created lazily by [com.screenrecorder.app.capture.ScreenRecorderService]
 * so it always exists before the foreground notification is posted.
 */
class ScreenRecorderApp : Application()
