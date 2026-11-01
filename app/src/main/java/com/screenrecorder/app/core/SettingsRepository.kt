package com.screenrecorder.app.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists [RecorderSettings] in SharedPreferences and exposes them as a hot [StateFlow].
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<RecorderSettings> = _settings.asStateFlow()

    fun update(transform: (RecorderSettings) -> RecorderSettings) {
        val updated = transform(_settings.value)
        if (updated == _settings.value) return
        write(updated)
        _settings.value = updated
    }

    private fun read(): RecorderSettings {
        val defaults = RecorderSettings()
        return RecorderSettings(
            captureMode = enum(prefs.getString(KEY_CAPTURE_MODE, null), defaults.captureMode),
            resolution = enum(prefs.getString(KEY_RESOLUTION, null), defaults.resolution),
            frameRate = enum(prefs.getString(KEY_FRAME_RATE, null), defaults.frameRate),
            quality = enum(prefs.getString(KEY_QUALITY, null), defaults.quality),
            customBitrateKbps = prefs.getInt(KEY_CUSTOM_BITRATE, defaults.customBitrateKbps),
            codec = enum(prefs.getString(KEY_CODEC, null), defaults.codec),
            audioSource = enum(prefs.getString(KEY_AUDIO_SOURCE, null), defaults.audioSource),
            audioBitrateKbps = prefs.getInt(KEY_AUDIO_BITRATE, defaults.audioBitrateKbps),
            saveLocation = enum(prefs.getString(KEY_SAVE_LOCATION, null), defaults.saveLocation),
            customFolderUri = prefs.getString(KEY_CUSTOM_FOLDER, null),
            fileNamePrefix = prefs.getString(KEY_FILE_PREFIX, null)?.ifBlank { defaults.fileNamePrefix }
                ?: defaults.fileNamePrefix,
            countdownSeconds = prefs.getInt(KEY_COUNTDOWN, defaults.countdownSeconds),
            maxDurationMinutes = prefs.getInt(KEY_MAX_DURATION, defaults.maxDurationMinutes),
            targetPackage = prefs.getString(KEY_TARGET_PACKAGE, null),
            targetLabel = prefs.getString(KEY_TARGET_LABEL, null),
            autoStopWhenTargetLeaves = prefs.getBoolean(
                KEY_AUTO_STOP,
                defaults.autoStopWhenTargetLeaves,
            ),
            keepCpuAwake = prefs.getBoolean(KEY_KEEP_AWAKE, defaults.keepCpuAwake),
        )
    }

    private fun write(settings: RecorderSettings) {
        prefs.edit()
            .putString(KEY_CAPTURE_MODE, settings.captureMode.name)
            .putString(KEY_RESOLUTION, settings.resolution.name)
            .putString(KEY_FRAME_RATE, settings.frameRate.name)
            .putString(KEY_QUALITY, settings.quality.name)
            .putInt(KEY_CUSTOM_BITRATE, settings.customBitrateKbps)
            .putString(KEY_CODEC, settings.codec.name)
            .putString(KEY_AUDIO_SOURCE, settings.audioSource.name)
            .putInt(KEY_AUDIO_BITRATE, settings.audioBitrateKbps)
            .putString(KEY_SAVE_LOCATION, settings.saveLocation.name)
            .putString(KEY_CUSTOM_FOLDER, settings.customFolderUri)
            .putString(KEY_FILE_PREFIX, settings.fileNamePrefix)
            .putInt(KEY_COUNTDOWN, settings.countdownSeconds)
            .putInt(KEY_MAX_DURATION, settings.maxDurationMinutes)
            .putString(KEY_TARGET_PACKAGE, settings.targetPackage)
            .putString(KEY_TARGET_LABEL, settings.targetLabel)
            .putBoolean(KEY_AUTO_STOP, settings.autoStopWhenTargetLeaves)
            .putBoolean(KEY_KEEP_AWAKE, settings.keepCpuAwake)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enum(name: String?, fallback: T): T {
        if (name == null) return fallback
        return runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
    }

    companion object {
        private const val PREFS_NAME = "screen_recorder_settings"

        private const val KEY_CAPTURE_MODE = "capture_mode"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_FRAME_RATE = "frame_rate"
        private const val KEY_QUALITY = "quality"
        private const val KEY_CUSTOM_BITRATE = "custom_bitrate"
        private const val KEY_CODEC = "codec"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_AUDIO_BITRATE = "audio_bitrate"
        private const val KEY_SAVE_LOCATION = "save_location"
        private const val KEY_CUSTOM_FOLDER = "custom_folder"
        private const val KEY_FILE_PREFIX = "file_prefix"
        private const val KEY_COUNTDOWN = "countdown"
        private const val KEY_MAX_DURATION = "max_duration"
        private const val KEY_TARGET_PACKAGE = "target_package"
        private const val KEY_TARGET_LABEL = "target_label"
        private const val KEY_AUTO_STOP = "auto_stop"
        private const val KEY_KEEP_AWAKE = "keep_awake"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
