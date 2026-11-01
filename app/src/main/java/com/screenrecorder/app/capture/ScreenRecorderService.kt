package com.screenrecorder.app.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.screenrecorder.app.MainActivity
import com.screenrecorder.app.R
import com.screenrecorder.app.core.RecorderController
import com.screenrecorder.app.core.RecorderState
import com.screenrecorder.app.core.SettingsRepository
import com.screenrecorder.app.util.DisplayUtils

/**
 * Foreground service that keeps the screen recording alive while the app is in the background.
 *
 * On Android 14+ the service must be in the foreground *before* `getMediaProjection()` is called,
 * which is why [startForeground] happens first and the projection is created afterwards.
 */
class ScreenRecorderService : Service() {

    private var engine: RecordingEngine? = null
    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var paused = false
    private var startedAtMillis = 0L
    private var resolution = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> engine?.stop() ?: stopSelfSafely()
            ACTION_PAUSE -> engine?.pause()
            ACTION_RESUME -> engine?.resume()
            else -> stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { engine?.stop() }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun handleStart(intent: Intent) {
        if (engine != null) return

        startForeground(
            NOTIFICATION_ID,
            buildNotification(NotificationState.PREPARING),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA) as Intent?
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            fail(getString(R.string.recording_denied))
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection failed", t)
            null
        }
        if (mediaProjection == null) {
            fail("无法获取投屏实例，请重试")
            return
        }

        val settings = SettingsRepository.get(this).settings.value
        val displaySize = DisplayUtils.currentDisplaySize(this)

        projection = mediaProjection
        acquireWakeLock(settings.keepCpuAwake)
        RecorderController.publish(RecorderState.Preparing)

        val newEngine = RecordingEngine(this, mediaProjection, settings, engineListener)
        engine = newEngine
        newEngine.start(displaySize.width, displaySize.height)
    }

    private val engineListener = object : RecordingEngine.Listener {

        override fun onStarted(fileName: String, width: Int, height: Int) {
            startedAtMillis = System.currentTimeMillis()
            resolution = "$width × $height"
            paused = false
            publishRecording(fileName)
            updateNotification()
        }

        override fun onPaused() {
            paused = true
            publishRecording(null)
            updateNotification()
        }

        override fun onResumed() {
            paused = false
            publishRecording(null)
            updateNotification()
        }

        override fun onStopped(fileName: String, durationMillis: Long, errorMessage: String?) {
            engine = null
            projection = null
            releaseWakeLock()
            RecorderController.publish(
                if (errorMessage != null) {
                    RecorderState.Failed(errorMessage)
                } else {
                    RecorderState.Finished(fileName, durationMillis)
                },
            )
            stopSelfSafely()
        }
    }

    private fun publishRecording(fileName: String?) {
        val name = fileName ?: currentFileName()
        RecorderController.publish(
            RecorderState.Recording(
                startedAtMillis = startedAtMillis,
                paused = paused,
                fileName = name,
                resolution = resolution,
            ),
        )
    }

    private fun currentFileName(): String {
        val state = RecorderController.state.value
        return (state as? RecorderState.Recording)?.fileName.orEmpty()
    }

    private fun fail(message: String) {
        RecorderController.publish(RecorderState.Failed(message))
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    // region notification

    private enum class NotificationState { PREPARING, RECORDING, PAUSED }

    private fun updateNotification() {
        val state = when {
            engine == null -> return
            paused -> NotificationState.PAUSED
            else -> NotificationState.RECORDING
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(state)) }
    }

    private fun buildNotification(state: NotificationState): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .setContentTitle(getString(R.string.notification_title_preparing))
            .setProgress(0, 0, true)

        when (state) {
            NotificationState.PREPARING -> Unit

            NotificationState.RECORDING -> builder
                .setProgress(0, 0, false)
                .setContentTitle(getString(R.string.notification_title_recording))
                .setContentText(resolution)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setWhen(startedAtMillis)
                .addAction(
                    0,
                    getString(R.string.notification_action_pause),
                    serviceIntent(ACTION_PAUSE, REQUEST_PAUSE),
                )
                .addAction(
                    0,
                    getString(R.string.notification_action_stop),
                    serviceIntent(ACTION_STOP, REQUEST_STOP),
                )

            NotificationState.PAUSED -> builder
                .setProgress(0, 0, false)
                .setContentTitle(getString(R.string.notification_title_paused))
                .setContentText(resolution)
                .addAction(
                    0,
                    getString(R.string.notification_action_resume),
                    serviceIntent(ACTION_RESUME, REQUEST_RESUME),
                )
                .addAction(
                    0,
                    getString(R.string.notification_action_stop),
                    serviceIntent(ACTION_STOP, REQUEST_STOP),
                )
        }
        return builder.build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, ScreenRecorderService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // endregion

    private fun acquireWakeLock(enabled: Boolean) {
        if (!enabled || wakeLock != null) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenRecorder::recording")
            .apply {
                setReferenceCounted(false)
                runCatching { acquire(MAX_WAKE_LOCK_MS) }
            }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "ScreenRecorderService"

        const val ACTION_START = "com.screenrecorder.app.action.START"
        const val ACTION_STOP = "com.screenrecorder.app.action.STOP"
        const val ACTION_PAUSE = "com.screenrecorder.app.action.PAUSE"
        const val ACTION_RESUME = "com.screenrecorder.app.action.RESUME"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "screen_recording"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN_APP = 10
        private const val REQUEST_PAUSE = 11
        private const val REQUEST_RESUME = 12
        private const val REQUEST_STOP = 13
        private const val MAX_WAKE_LOCK_MS = 6 * 60 * 60 * 1000L

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenRecorderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
    }
}
