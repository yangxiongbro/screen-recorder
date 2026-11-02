package com.screenrecorder.app.core

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size

/**
 * Keeps the captured frame size as close to the physical display as possible.
 *
 * Downscaling the mirrored image is the main source of "blurry" recordings, so the exact display
 * size is always the first candidate. Reduction only happens when the *real* encoder refuses that
 * size (the engine retries with the next candidate), never based on a separate throw-away probe:
 * a probe can end up on the software encoder while the recording itself would have used the
 * hardware one, which silently downgraded recordings to 1080p.
 */
object EncoderSupport {

    fun candidateSizes(mimeType: String, desired: Size): List<Size> {
        val aligned = alignToEncoder(mimeType, desired)
        val candidates = LinkedHashSet<Size>()
        candidates += aligned
        // 16 pixel aligned size, which every encoder accepts.
        candidates += Size((aligned.width / 16) * 16, (aligned.height / 16) * 16)
        // Last resort for panels taller than the encoder supports: scale down 25%.
        candidates += Size(
            (aligned.width * 3 / 4 / 16) * 16,
            (aligned.height * 3 / 4 / 16) * 16,
        )
        return candidates.filter { it.width >= 320 && it.height >= 240 && it.width % 2 == 0 && it.height % 2 == 0 }
    }

    /** Best-effort profile/level hint; `null` when the encoder does not expose capabilities. */
    fun preferredProfileLevel(
        mimeType: String,
        width: Int,
        height: Int,
    ): CodecProfile? {
        val info = findEncoder(mimeType) ?: return null
        val profiles = runCatching {
            info.getCapabilitiesForType(mimeType).profileLevels.orEmpty()
        }.getOrNull().orEmpty()
        val pixels = width.toLong() * height

        return if (mimeType.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) {
            if (profiles.none { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }) {
                return null
            }
            CodecProfile(
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                when {
                    pixels <= 2_228_224L -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
                    pixels <= 5_652_480L -> MediaCodecInfo.CodecProfileLevel.AVCLevel51
                    else -> MediaCodecInfo.CodecProfileLevel.AVCLevel52
                },
            )
        } else {
            if (profiles.none { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain }) {
                return null
            }
            CodecProfile(
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                when {
                    pixels <= 2_228_224L -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41
                    pixels <= 8_912_896L -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51
                    else -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52
                },
            )
        }
    }

    data class CodecProfile(val profile: Int, val level: Int)

    private fun alignToEncoder(mimeType: String, size: Size): Size {
        val capabilities = videoCapabilities(mimeType) ?: return even(size)
        val alignWidth = capabilities.widthAlignment.coerceAtLeast(1)
        val alignHeight = capabilities.heightAlignment.coerceAtLeast(1)
        return Size(
            alignDown(size.width, alignWidth).coerceAtLeast(16),
            alignDown(size.height, alignHeight).coerceAtLeast(16),
        )
    }

    private fun videoCapabilities(mimeType: String): MediaCodecInfo.VideoCapabilities? {
        val info = findEncoder(mimeType) ?: return null
        return runCatching { info.getCapabilitiesForType(mimeType).videoCapabilities }.getOrNull()
    }

    private fun findEncoder(mimeType: String): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }

    private fun even(size: Size): Size =
        Size((size.width / 2) * 2, (size.height / 2) * 2)

    private fun alignDown(value: Int, alignment: Int): Int {
        val align = alignment.coerceAtLeast(1)
        return (value / align) * align
    }
}
