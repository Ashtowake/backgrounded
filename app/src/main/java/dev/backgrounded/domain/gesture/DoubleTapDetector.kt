package dev.backgrounded.domain.gesture

class DoubleTapDetector(private val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {
    private var lastTapAt = 0L

    fun onTap(nowMillis: Long): Boolean {
        val delta = nowMillis - lastTapAt
        lastTapAt = if (delta in 1..windowMillis) 0L else nowMillis
        return lastTapAt == 0L
    }

    fun reset() {
        lastTapAt = 0L
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 350L
    }
}
