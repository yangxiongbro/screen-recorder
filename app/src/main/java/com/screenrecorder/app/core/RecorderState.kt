package com.screenrecorder.app.core

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.screenrecorder.app.capture.ScreenRecorderService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle of the recorder, observed by both the UI and the foreground service. */
sealed interface RecorderState {

    data object Idle : RecorderState

    data object Preparing : RecorderState

    data class Recording(
        val startedAtMillis: Long,
        val paused: Boolean,
        val fileName: String,
        val resolution: String,
    ) : RecorderState

    data class Finished(val fileName: String, val durationMillis: Long) : RecorderState

    data class Failed(val message: String) : RecorderState
}

/**
 * In-process bridge between [ScreenRecorderService] and the Compose UI.
 */
object RecorderController {

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    fun publish(newState: RecorderState) {
        _state.value = newState
    }

    fun reset() {
        _state.value = RecorderState.Idle
    }
}

/**
 * Small helper the UI uses to drive the service, so call sites do not deal with
 * `startForegroundService` / intent extras.
 */
object RecordingCommands {

    fun start(context: Context, resultCode: Int, data: Intent) {
        val intent = Intent(context, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_START
            putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecorderService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) = send(context, ScreenRecorderService.ACTION_STOP)

    fun pause(context: Context) = send(context, ScreenRecorderService.ACTION_PAUSE)

    fun resume(context: Context) = send(context, ScreenRecorderService.ACTION_RESUME)

    private fun send(context: Context, action: String) {
        val intent = Intent(context, ScreenRecorderService::class.java).setAction(action)
        context.startService(intent)
    }
}
