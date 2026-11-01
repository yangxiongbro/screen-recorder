package com.screenrecorder.app.capture

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * A [MediaMuxer] that tolerates out-of-order track registration.
 *
 * The muxer can only be started once *all* expected tracks (video + optional audio) are known,
 * but encoders emit their format asynchronously. Samples produced before that point are buffered
 * and flushed as soon as the muxer starts.
 */
internal class Mp4Muxer(
    fileDescriptor: FileDescriptor?,
    path: String?,
    private val expectedTracks: Int,
) {

    private val muxer: MediaMuxer = if (fileDescriptor != null) {
        MediaMuxer(fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    } else {
        MediaMuxer(
            requireNotNull(path) { "Either a file descriptor or a path is required" },
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
    }

    private class PendingSample(
        val trackId: Int,
        val payload: ByteArray,
        val ptsUs: Long,
        val flags: Int,
    )

    private val lock = Any()
    private val createdAtMs = SystemClock.elapsedRealtime()
    private val trackIndexes = HashMap<Int, Int>()
    private val lastPtsByTrack = HashMap<Int, Long>()
    private val pending = ArrayDeque<PendingSample>()
    private val bufferInfo = MediaCodec.BufferInfo()
    private var started = false
    private var closed = false

    /** Number of samples actually written; zero means the file is useless. */
    var samplesWritten: Int = 0
        private set

    var failure: Throwable? = null
        private set

    fun addTrack(trackId: Int, format: MediaFormat) {
        synchronized(lock) {
            if (closed || started) return
            try {
                trackIndexes[trackId] = muxer.addTrack(format)
            } catch (t: Throwable) {
                failure = t
                Log.e(TAG, "addTrack($trackId) failed", t)
                return
            }
            if (trackIndexes.size >= expectedTracks) startLocked()
        }
    }

    fun write(trackId: Int, buffer: ByteBuffer, ptsUs: Long, flags: Int) {
        synchronized(lock) {
            if (closed || failure != null) return
            // If one encoder never reports its format (e.g. audio failed to start) fall back to
            // muxing whatever tracks we have instead of silently writing nothing.
            if (!started && trackIndexes.isNotEmpty() &&
                SystemClock.elapsedRealtime() - createdAtMs > TRACK_GRACE_MS
            ) {
                startLocked()
            }
            val trackIndex = trackIndexes[trackId]
            if (!started || trackIndex == null) {
                if (pending.size >= MAX_PENDING_SAMPLES) return
                val copy = ByteArray(buffer.remaining())
                buffer.duplicate().get(copy)
                pending.addLast(PendingSample(trackId, copy, ptsUs, flags))
                return
            }
            writeLocked(trackIndex, buffer, ptsUs, flags)
        }
    }

    fun stop() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (started) {
                try {
                    muxer.stop()
                } catch (t: Throwable) {
                    // Thrown when no sample was ever written; the caller deletes the empty file.
                    Log.w(TAG, "muxer.stop() failed", t)
                    failure = failure ?: t
                }
            }
            try {
                muxer.release()
            } catch (t: Throwable) {
                Log.w(TAG, "muxer.release() failed", t)
            }
        }
    }

    private fun startLocked() {
        try {
            muxer.start()
            started = true
        } catch (t: Throwable) {
            failure = t
            Log.e(TAG, "muxer.start() failed", t)
            return
        }
        while (pending.isNotEmpty()) {
            val sample = pending.removeFirst()
            val trackIndex = trackIndexes[sample.trackId] ?: continue
            writeLocked(trackIndex, ByteBuffer.wrap(sample.payload), sample.ptsUs, sample.flags)
        }
    }

    private fun writeLocked(trackIndex: Int, buffer: ByteBuffer, ptsUs: Long, flags: Int) {
        if (failure != null) return
        try {
            // MediaMuxer aborts the whole file when a track goes backwards in time.
            val previous = lastPtsByTrack[trackIndex]
            val safePts = if (previous != null && ptsUs < previous) {
                Log.w(TAG, "non-monotonic timestamp on track $trackIndex: $ptsUs < $previous")
                previous
            } else {
                ptsUs
            }
            lastPtsByTrack[trackIndex] = safePts
            bufferInfo.set(0, buffer.remaining(), safePts.coerceAtLeast(0L), flags)
            muxer.writeSampleData(trackIndex, buffer, bufferInfo)
            samplesWritten++
        } catch (t: Throwable) {
            failure = t
            Log.e(TAG, "writeSampleData failed", t)
        }
    }

    private companion object {
        const val TAG = "Mp4Muxer"
        const val MAX_PENDING_SAMPLES = 240
        const val TRACK_GRACE_MS = 1_500L
    }
}
