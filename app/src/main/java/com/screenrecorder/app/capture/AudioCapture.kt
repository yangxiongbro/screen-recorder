package com.screenrecorder.app.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import com.screenrecorder.app.core.AudioSourcePreset
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Builds the [AudioRecord] used for a recording, or returns `null` when the requested source is
 * unavailable (missing permission, unsupported device, ...).
 */
internal object AudioInputFactory {

    const val SAMPLE_RATE = 44_100

    fun create(
        context: Context,
        projection: MediaProjection,
        source: AudioSourcePreset,
    ): AudioRecord? {
        if (source == AudioSourcePreset.NONE) return null
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferSize = max(minBuffer * 2, 8_192)

            val builder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)

            when (source) {
                AudioSourcePreset.INTERNAL -> {
                    val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    builder.setAudioPlaybackCaptureConfig(playbackConfig)
                }

                AudioSourcePreset.MIC -> builder.setAudioSource(MediaRecorder.AudioSource.MIC)
                AudioSourcePreset.NONE -> return null
            }

            val record = builder.build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                Log.w(TAG, "AudioRecord not initialised for $source")
                null
            } else {
                record
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to create AudioRecord for $source", t)
            null
        }
    }

    private const val TAG = "AudioInputFactory"
}

/**
 * Captures raw PCM from an [AudioRecord] and encodes it to AAC with [MediaCodec].
 */
internal class AudioCapture(
    private val record: AudioRecord,
    private val bitrate: Int,
    private val onFormat: (MediaFormat) -> Unit,
    private val onSample: (ByteBuffer, Long, Int) -> Unit,
    private val onError: (Throwable) -> Unit,
) {

    private val sampleRate: Int = record.sampleRate
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)

    @Volatile
    private var capturing = false

    private var captureThread: Thread? = null
    private var drainThread: Thread? = null

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            1,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun start() {
        codec.start()
        record.startRecording()
        capturing = true
        captureThread = Thread({ captureLoop() }, "AudioCapture").apply { start() }
        drainThread = Thread({ drainLoop() }, "AudioEncoderDrain").apply { start() }
    }

    fun stop() {
        if (!capturing) return
        capturing = false
        runCatching { record.stop() }
        captureThread?.let { thread -> runCatching { thread.join(1_000L) } }
        captureThread = null

        runCatching {
            val index = codec.dequeueInputBuffer(200_000L)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index,
                    0,
                    0,
                    0L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
        }
        drainThread?.let { thread -> runCatching { thread.join(1_500L) } }
        drainThread = null

        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { record.release() }
    }

    private fun captureLoop() {
        val pcm = ByteArray(READ_BUFFER_BYTES)
        val startUs = System.nanoTime() / 1_000L
        var framesRead = 0L
        try {
            while (capturing) {
                val read = record.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                var offset = 0
                while (offset < read) {
                    val index = codec.dequeueInputBuffer(10_000L)
                    if (index < 0) break
                    val input = codec.getInputBuffer(index) ?: break
                    input.clear()
                    val chunk = min(read - offset, input.remaining())
                    input.put(pcm, offset, chunk)
                    val ptsUs = startUs + framesRead * 1_000_000L / sampleRate
                    codec.queueInputBuffer(index, 0, chunk, ptsUs, 0)
                    offset += chunk
                    framesRead += chunk / 2L // mono, 16 bit
                }
            }
        } catch (t: Throwable) {
            if (capturing) {
                Log.e(TAG, "audio capture failed", t)
                onError(t)
            }
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                when (val index = codec.dequeueOutputBuffer(info, 10_000L)) {
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
            Log.w(TAG, "audio encoder stopped while draining", t)
        } catch (t: Throwable) {
            Log.e(TAG, "audio drain failed", t)
            onError(t)
        }
    }

    private companion object {
        const val TAG = "AudioCapture"
        const val READ_BUFFER_BYTES = 16 * 1024
    }
}
