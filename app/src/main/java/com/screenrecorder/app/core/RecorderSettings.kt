package com.screenrecorder.app.core

import android.util.Size
import kotlin.math.roundToInt

/** How much of the screen is captured. */
enum class CaptureMode(val label: String) {
    FULL_SCREEN("整个屏幕"),
    SINGLE_APP("单个应用"),
}

/** Down-scaling target for the recorded video. */
enum class ResolutionPreset(val label: String, val maxDimension: Int) {
    ORIGINAL("原始分辨率", 0),
    FHD("1080p", 1080),
    HD("720p", 720),
    SD("480p", 480),
}

enum class FrameRatePreset(val label: String, val fps: Int) {
    FPS15("15 fps", 15),
    FPS24("24 fps", 24),
    FPS30("30 fps", 30),
    FPS60("60 fps", 60),
}

enum class VideoCodecPreset(val label: String, val mimeType: String) {
    H264("H.264 (兼容性最好)", "video/avc"),
    H265("H.265 (体积更小)", "video/hevc"),
}

enum class QualityPreset(val label: String, val bitrateScale: Float) {
    LOW("流畅", 0.6f),
    MEDIUM("标准", 1.0f),
    HIGH("高清", 1.6f),
}

enum class AudioSourcePreset(val label: String) {
    NONE("不录制声音"),
    MIC("麦克风"),
    INTERNAL("内部音频（应用声音）"),
}

enum class SaveLocation(val label: String) {
    MEDIA_STORE("相册 · Movies/ScreenRecorder"),
    CUSTOM_FOLDER("自定义文件夹"),
    APP_PRIVATE("应用专属目录"),
}

/**
 * Immutable snapshot of every user tunable recording option.
 */
data class RecorderSettings(
    val captureMode: CaptureMode = CaptureMode.FULL_SCREEN,
    val resolution: ResolutionPreset = ResolutionPreset.FHD,
    val frameRate: FrameRatePreset = FrameRatePreset.FPS30,
    val quality: QualityPreset = QualityPreset.MEDIUM,
    /** 0 means "derive from resolution / frame rate / quality". */
    val customBitrateKbps: Int = 0,
    val codec: VideoCodecPreset = VideoCodecPreset.H264,
    val audioSource: AudioSourcePreset = AudioSourcePreset.NONE,
    val audioBitrateKbps: Int = 128,
    val saveLocation: SaveLocation = SaveLocation.MEDIA_STORE,
    val customFolderUri: String? = null,
    val fileNamePrefix: String = "ScreenRecorder",
    val countdownSeconds: Int = 3,
    /** 0 means unlimited. */
    val maxDurationMinutes: Int = 0,
    val targetPackage: String? = null,
    val targetLabel: String? = null,
    val autoStopWhenTargetLeaves: Boolean = false,
    val keepCpuAwake: Boolean = true,
) {

    val hasAudio: Boolean get() = audioSource != AudioSourcePreset.NONE

    /**
     * Scales [displayWidth] x [displayHeight] down to the selected preset.
     *
     * The preset refers to the *short* side of the screen, so "1080p" keeps a 1080x2400 portrait
     * display untouched instead of shrinking it to 480 pixels wide. Only even dimensions are
     * enforced here; the encoder's own alignment is applied later by
     * [EncoderSupport.resolveCaptureSize] so the mirror never gets needlessly resampled.
     */
    fun captureSize(displayWidth: Int, displayHeight: Int): Size {
        var width = displayWidth.coerceAtLeast(16)
        var height = displayHeight.coerceAtLeast(16)
        val targetShortSide = resolution.maxDimension
        if (targetShortSide > 0) {
            val shortSide = minOf(width, height)
            if (shortSide > targetShortSide) {
                val scale = targetShortSide.toFloat() / shortSide
                width = (width * scale).toInt()
                height = (height * scale).toInt()
            }
        }
        return Size(makeEven(width), makeEven(height))
    }

    fun videoBitrateKbps(width: Int, height: Int): Int {
        if (customBitrateKbps > 0) return customBitrateKbps
        val bitsPerPixel = if (codec == VideoCodecPreset.H265) 0.075 else 0.10
        val raw = width.toDouble() * height.toDouble() * frameRate.fps * bitsPerPixel * quality.bitrateScale
        return (raw / 1000.0).roundToInt().coerceIn(1_000, 60_000)
    }

    private fun makeEven(value: Int): Int = (value / 2) * 2
}
