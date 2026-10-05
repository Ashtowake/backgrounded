package dev.backgrounded.domain.render

import dev.backgrounded.domain.model.SlideMode
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Position of the current image along a repeating, reversing path. Speed is in screen pixels per second. */
object SlideMotion {
    data class Position(
        val offsetX: Float,
        val offsetY: Float,
        val zoom: Float,
        val travelX: Float,
        val travelY: Float,
    )

    fun position(
        mode: SlideMode,
        speedPxPerSecond: Float,
        elapsedMillis: Long,
        width: Int,
        height: Int,
    ): Position? {
        if (mode == SlideMode.OFF || width <= 0 || height <= 0) return null
        val travelX = width * TRAVEL_FRACTION
        val travelY = height * TRAVEL_FRACTION
        val pathLength =
            when (mode) {
                SlideMode.OFF -> return null
                SlideMode.LEFT_TO_RIGHT, SlideMode.RIGHT_TO_LEFT -> travelX
                SlideMode.DIAGONAL_UP_RIGHT -> hypot(travelX, travelY)
                SlideMode.ZOOM_IN -> min(width, height) * ZOOM_EDGE_TRAVEL_FRACTION
            }
        val distance = max(0L, elapsedMillis) / 1000.0 * speedPxPerSecond.coerceIn(0.1f, 120f)
        val phase = (distance % (pathLength * 2)).toFloat()
        val progress = if (phase <= pathLength) phase / pathLength else 2f - phase / pathLength
        return when (mode) {
            SlideMode.OFF -> null
            SlideMode.LEFT_TO_RIGHT -> Position((progress - 0.5f) * travelX, 0f, 1f, travelX, 0f)
            SlideMode.RIGHT_TO_LEFT -> Position((0.5f - progress) * travelX, 0f, 1f, travelX, 0f)
            SlideMode.DIAGONAL_UP_RIGHT ->
                Position(
                    (progress - 0.5f) * travelX,
                    (0.5f - progress) * travelY,
                    1f,
                    travelX,
                    travelY,
                )
            SlideMode.ZOOM_IN -> Position(0f, 0f, 1f + ZOOM_SCALE_RANGE * progress, 0f, 0f)
        }
    }

    private const val TRAVEL_FRACTION = 0.2f
    private const val ZOOM_EDGE_TRAVEL_FRACTION = 0.1f
    private const val ZOOM_SCALE_RANGE = 0.2f
}
