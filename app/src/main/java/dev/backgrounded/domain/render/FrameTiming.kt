package dev.backgrounded.domain.render

/** Frame deadlines use the previous display timestamp, rather than time spent drawing. */
object FrameTiming {
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val VSYNC_TOLERANCE_NANOS = 500_000L

    fun deadline(
        now: Long,
        previousFrame: Long,
        fps: Int,
        motionInterval: Long,
    ): Long = if (previousFrame == 0L) now else previousFrame + maxOf(NANOS_PER_SECOND / fps, motionInterval)

    fun due(
        frame: Long,
        deadline: Long,
    ): Boolean = frame >= deadline - VSYNC_TOLERANCE_NANOS

    /** Wake before the target vsync; the callback checks the deadline without millisecond rounding. */
    fun wakeDelayMillis(
        now: Long,
        deadline: Long,
        refreshRate: Float,
    ): Long {
        val refresh = refreshRate.takeIf { it.isFinite() && it > 0f } ?: 60f
        val vsync = (NANOS_PER_SECOND / refresh).toLong()
        return ((deadline - now - vsync).coerceAtLeast(0L) / 1_000_000L)
    }
}
