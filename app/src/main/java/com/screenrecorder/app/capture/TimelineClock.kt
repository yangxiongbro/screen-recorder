package com.screenrecorder.app.capture

/**
 * Shared A/V timeline for a recording.
 *
 * MediaProjection / `System.nanoTime()` timestamps are absolute, so the first sample that arrives
 * defines the zero point. Pausing moves the zero point forward by the paused duration, which keeps
 * audio and video in sync after a pause/resume cycle.
 *
 * The "should this sample be written, and with which timestamp" decision is made atomically:
 * a sample that is translated while the recording is paused is dropped instead of being written
 * with an out-of-date timestamp (that used to make `MediaMuxer` fail with non-monotonic PTS).
 */
internal class TimelineClock {

    private var baseUs: Long = -1L
    private var pausedAtUs: Long = -1L
    private var pausedTotalUs: Long = 0L

    /**
     * Returns the timestamp to mux for [ptsUs], or `-1` when the sample must be dropped because
     * the recording is currently paused.
     */
    @Synchronized
    fun translateIfActive(ptsUs: Long): Long {
        if (pausedAtUs >= 0L) return -1L
        if (baseUs < 0L) baseUs = ptsUs
        return (ptsUs - pausedTotalUs - baseUs).coerceAtLeast(0L)
    }

    @Synchronized
    fun isPaused(): Boolean = pausedAtUs >= 0L

    @Synchronized
    fun pause() {
        if (pausedAtUs < 0L) pausedAtUs = nowUs()
    }

    @Synchronized
    fun resume() {
        if (pausedAtUs >= 0L) {
            pausedTotalUs += nowUs() - pausedAtUs
            pausedAtUs = -1L
        }
    }

    private fun nowUs(): Long = System.nanoTime() / 1_000L
}
