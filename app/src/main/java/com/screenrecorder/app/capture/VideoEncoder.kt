package com.screenrecorder.app.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Hardware H.264/H.265 encoder fed through an input [Surface].
 *
 * The surface is handed to the [android.hardware.display.VirtualDisplay], so frames are pushed
 * by the platform; a dedicated thread drains the encoder output.
 */
internal class VideoEncoder(
    mimeType: String,
    val width: Int,
    val height: Int,
    bitrate: Int,
    frameRate: Int,
    private val onFormat: (MediaFormat) -> Unit,
    private val onSample: (ByteBuffer, Long, Int) -> Unit,
    private val onError: (Throwable) -> Unit,
) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(mimeType)

    val inputSurface: Surface

    @Volatile
    private var draining = true

    private var drainThread: Thread? = null

    init {
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                )
            }
            // Frame pacing is handled by GlFramePump, which feeds this surface at a fixed rate.
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        drainThread = Thread({ drainLoop() }, "VideoEncoderDrain").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        if (!draining) return
        draining = false
        runCatching { codec.signalEndOfInputStream() }
        drainThread?.let { thread ->
            runCatching { thread.join(2_000L) }
        }
        drainThread = null
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        var idlePollsWhileStopping = 0
        try {
            while (true) {
                when (val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Keep draining briefly after stop() so buffered frames reach the muxer.
                        if (!draining && ++idlePollsWhileStopping > MAX_IDLE_POLLS_WHILE_STOPPING) break
                    }

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)

                    else -> {
                        if (index < 0) continue
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            onSample(buffer, info.presentationTimeUs, info.flags)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (t: IllegalStateException) {
            Log.w(TAG, "encoder stopped while draining", t)
        } catch (t: Throwable) {
            Log.e(TAG, "video drain failed", t)
            onError(t)
        }
    }

    private companion object {
        const val TAG = "VideoEncoder"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val MAX_IDLE_POLLS_WHILE_STOPPING = 120
    }
}
