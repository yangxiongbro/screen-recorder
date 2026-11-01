package com.screenrecorder.app.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import com.screenrecorder.app.apps.ForegroundAppMonitor
import com.screenrecorder.app.core.RecorderSettings
import com.screenrecorder.app.storage.RecordingOutput
import com.screenrecorder.app.storage.RecordingStorage
import com.screenrecorder.app.util.DisplayUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Owns the whole capture pipeline for one `MediaProjection` grant.
 *
 * Note that since Android 14 a `MediaProjection` may only create a single `VirtualDisplay`, so
 * the capture size is fixed for the whole recording and the encoder is never re-created.
 * Rotating the device keeps the original frame size (the mirror is scaled into it).
 */
class RecordingEngine(
    private val context: Context,
    private val projection: MediaProjection,
    private val settings: RecorderSettings,
    private val listener: Listener,
) {

    interface Listener {
        fun onStarted(fileName: String, width: Int, height: Int)
        fun onPaused()
        fun onResumed()
        fun onStopped(fileName: String, durationMillis: Long, errorMessage: String?)
    }

    private class Segment(
        val output: RecordingOutput,
        val video: VideoEncoder,
        val display: VirtualDisplay,
        val audio: AudioCapture?,
        val pump: GlFramePump,
        val muxer: Mp4Muxer,
        val clock: TimelineClock,
        val size: Size,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RecordingEngine")
    }

    private val storage = RecordingStorage(context, settings)
    private val sessionStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private val foregroundMonitor = ForegroundAppMonitor(context)

    private var segment: Segment? = null
    private var startedAtMs = 0L
    private var lastFileName = ""
    private var foregroundMisses = 0

    @Volatile
    private var paused = false

    @Volatile
    private var stopping = false

    // region public API

    fun start(displayWidth: Int, displayHeight: Int) {
        worker.execute { startInternal(displayWidth, displayHeight) }
    }

    fun pause() {
        worker.execute {
            if (stopping || paused) return@execute
            val current = segment ?: return@execute
            paused = true
            current.clock.pause()
            current.pump.setPaused(true)
            mainHandler.post { listener.onPaused() }
        }
    }

    fun resume() {
        worker.execute {
            if (stopping || !paused) return@execute
            val current = segment ?: return@execute
            paused = false
            current.clock.resume()
            current.pump.setPaused(false)
            mainHandler.post { listener.onResumed() }
        }
    }

    fun stop() {
        worker.execute { stopInternal(error = null) }
    }

    // endregion

    private fun startInternal(displayWidth: Int, displayHeight: Int) {
        if (stopping) return
        try {
            startedAtMs = SystemClock.elapsedRealtime()
            projection.registerCallback(projectionCallback, mainHandler)
            scheduleLimits()

            startSegmentLocked(displayWidth, displayHeight)
            val current = requireNotNull(segment) { "录制管线创建失败" }
            mainHandler.post {
                listener.onStarted(current.output.displayName, current.size.width, current.size.height)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startInternal failed", t)
            stopInternal(error = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun startSegmentLocked(displayWidth: Int, displayHeight: Int) {
        val captureSize = settings.captureSize(displayWidth, displayHeight)
        val output = storage.create(sessionStamp, 0)

        var muxer: Mp4Muxer? = null
        var video: VideoEncoder? = null
        var display: VirtualDisplay? = null
        var audio: AudioCapture? = null
        var pump: GlFramePump? = null

        try {
            val clock = TimelineClock()
            val audioRecord: AudioRecord? =
                AudioInputFactory.create(context, projection, settings.audioSource)
            val expectedTracks = if (audioRecord != null) 2 else 1

            val localMuxer = Mp4Muxer(
                output.fileDescriptor?.fileDescriptor,
                output.absolutePath,
                expectedTracks,
            )
            muxer = localMuxer

            val localVideo = VideoEncoder(
                mimeType = settings.codec.mimeType,
                width = captureSize.width,
                height = captureSize.height,
                bitrate = settings.videoBitrateKbps(captureSize.width, captureSize.height),
                frameRate = settings.frameRate.fps,
                onFormat = { format -> localMuxer.addTrack(TRACK_VIDEO, format) },
                onSample = { buffer, ptsUs, flags ->
                    val pts = clock.translateIfActive(ptsUs)
                    if (pts >= 0L) localMuxer.write(TRACK_VIDEO, buffer, pts, flags)
                },
                onError = ::reportPipelineError,
            )
            video = localVideo

            // The pump resamples the mirrored frames at exactly the configured frame rate before
            // they reach the encoder; the VirtualDisplay therefore renders into the pump's surface.
            val localPump = GlFramePump(
                encoderSurface = localVideo.inputSurface,
                width = captureSize.width,
                height = captureSize.height,
                frameRate = settings.frameRate.fps,
                onError = ::reportPipelineError,
            )
            pump = localPump
            val displaySurface = localPump.start()

            display = projection.createVirtualDisplay(
                "ScreenRecorder",
                captureSize.width,
                captureSize.height,
                DisplayUtils.currentDensityDpi(context),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                displaySurface,
                null,
                mainHandler,
            )

            if (audioRecord != null) {
                val localAudio = AudioCapture(
                    record = audioRecord,
                    bitrate = settings.audioBitrateKbps * 1000,
                    onFormat = { format -> localMuxer.addTrack(TRACK_AUDIO, format) },
                    onSample = { buffer, ptsUs, flags ->
                        val pts = clock.translateIfActive(ptsUs)
                        if (pts >= 0L) localMuxer.write(TRACK_AUDIO, buffer, pts, flags)
                    },
                    onError = ::reportPipelineError,
                )
                audio = localAudio
                localAudio.start()
            }

            segment = Segment(
                output = output,
                video = localVideo,
                display = display,
                audio = audio,
                pump = localPump,
                muxer = localMuxer,
                clock = clock,
                size = captureSize,
            )
            lastFileName = output.displayName
            if (paused) {
                clock.pause()
                localPump.setPaused(true)
            }
            Log.i(TAG, "recording started ${captureSize.width}x${captureSize.height}")
        } catch (t: Throwable) {
            Log.e(TAG, "startSegment failed", t)
            runCatching { display?.release() }
            runCatching { pump?.stop() }
            runCatching { audio?.stop() }
            runCatching { video?.stop() }
            runCatching { muxer?.stop() }
            runCatching { output.discard() }
            throw t
        }
    }

    private fun stopSegmentLocked() {
        val current = segment ?: return
        segment = null
        runCatching { current.display.release() }
        runCatching { current.pump.stop() }
        runCatching { current.audio?.stop() }
        runCatching { current.video.stop() }
        runCatching { current.muxer.stop() }

        val healthy = current.muxer.failure == null && current.muxer.samplesWritten > 0
        if (healthy) {
            current.output.commit()
        } else {
            Log.w(TAG, "discarding unusable output: ${current.output.displayName}")
            current.output.discard()
        }
    }

    private fun stopInternal(error: String?) {
        if (stopping) return
        stopping = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { stopSegmentLocked() }
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }

        val duration = if (startedAtMs > 0L) SystemClock.elapsedRealtime() - startedAtMs else 0L
        val fileName = lastFileName
        Log.i(TAG, "recording stopped, duration=${duration}ms, error=$error")
        mainHandler.post { listener.onStopped(fileName, duration, error) }
    }

    private fun reportPipelineError(error: Throwable) {
        Log.e(TAG, "pipeline error", error)
        worker.execute { if (!stopping) stopInternal(error.message ?: "编码器异常") }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            worker.execute { if (!stopping) stopInternal(error = "投屏已被系统停止") }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            // A single MediaProjection can only own one VirtualDisplay, so the frame size is
            // fixed for the session; just keep the information for diagnostics.
            Log.i(TAG, "captured content resized to ${width}x$height (frame size unchanged)")
        }
    }

    // region limits

    private fun scheduleLimits() {
        if (settings.maxDurationMinutes > 0) {
            mainHandler.postDelayed(
                {
                    worker.execute {
                        if (!stopping) stopInternal(error = "已达到最长录制时长")
                    }
                },
                settings.maxDurationMinutes * 60_000L,
            )
        }
        if (settings.autoStopWhenTargetLeaves &&
            settings.targetPackage != null &&
            foregroundMonitor.hasPermission()
        ) {
            mainHandler.postDelayed(foregroundCheck, FOREGROUND_CHECK_INTERVAL_MS)
        }
    }

    private val foregroundCheck = object : Runnable {
        override fun run() {
            if (stopping) return
            val target = settings.targetPackage
            val foreground = foregroundMonitor.currentForegroundPackage()
            if (target != null && foreground != null) {
                if (foreground == target) {
                    foregroundMisses = 0
                } else {
                    foregroundMisses++
                    if (foregroundMisses >= MAX_FOREGROUND_MISSES) {
                        worker.execute { stopInternal(error = "目标应用已退到后台") }
                        return
                    }
                }
            }
            mainHandler.postDelayed(this, FOREGROUND_CHECK_INTERVAL_MS)
        }
    }

    // endregion

    private companion object {
        const val TAG = "RecordingEngine"
        const val TRACK_VIDEO = 0
        const val TRACK_AUDIO = 1
        const val FOREGROUND_CHECK_INTERVAL_MS = 1_500L
        const val MAX_FOREGROUND_MISSES = 3
    }
}
