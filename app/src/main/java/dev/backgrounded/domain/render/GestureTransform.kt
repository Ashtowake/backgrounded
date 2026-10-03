package dev.backgrounded.domain.render

import dev.backgrounded.domain.model.Framing
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Two-finger transform math. Each update is computed from the gesture start, not composed
 * incrementally: the affine that maps the initial fingertip pair onto the current pair is applied
 * to the framing that was active when the gesture began, so the image points under the fingers
 * stay under the fingers. [commitSnap] runs once when the fingers lift.
 */
object GestureTransform {
    data class FingerPair(
        val centroidX: Float,
        val centroidY: Float,
        val vectorX: Float,
        val vectorY: Float,
    )

    data class Gesture(
        val start: FingerPair,
        val current: FingerPair,
        val frameWidth: Float,
        val frameHeight: Float,
    )

    fun settle(
        startFraming: Framing,
        gesture: Gesture,
    ): Framing {
        if (gesture.frameWidth <= 0f || gesture.frameHeight <= 0f) return startFraming
        val startLength = hypot(gesture.start.vectorX, gesture.start.vectorY)
        val currentLength = hypot(gesture.current.vectorX, gesture.current.vectorY)
        if (startLength <= 0f || currentLength <= 0f) return startFraming

        val zoom =
            (startFraming.zoom * (currentLength / startLength))
                .coerceIn(FitGeometry.MIN_ZOOM, FitGeometry.MAX_ZOOM)
        val effectiveZoom = if (startFraming.zoom == 0f) 1f else zoom / startFraming.zoom

        var radians =
            atan2(gesture.current.vectorY, gesture.current.vectorX) -
                atan2(gesture.start.vectorY, gesture.start.vectorX)
        val fullTurn = 2f * Math.PI.toFloat()
        radians = ((radians + Math.PI.toFloat()) % fullTurn + fullTurn) % fullTurn - Math.PI.toFloat()
        if (abs(Math.toDegrees(radians.toDouble())) < ROTATION_DEADZONE_DEGREES) radians = 0f
        val cosTheta = cos(radians).toFloat()
        val sinTheta = sin(radians).toFloat()

        val startCenterX = (0.5f + startFraming.panX) * gesture.frameWidth
        val startCenterY = (0.5f + startFraming.panY) * gesture.frameHeight
        val offsetX = startCenterX - gesture.start.centroidX
        val offsetY = startCenterY - gesture.start.centroidY
        val rotatedX = offsetX * cosTheta - offsetY * sinTheta
        val rotatedY = offsetX * sinTheta + offsetY * cosTheta
        val centerX = gesture.current.centroidX + rotatedX * effectiveZoom
        val centerY = gesture.current.centroidY + rotatedY * effectiveZoom

        return startFraming.copy(
            zoom = zoom,
            panX = (centerX / gesture.frameWidth - 0.5f).coerceIn(-MAX_PAN, MAX_PAN),
            panY = (centerY / gesture.frameHeight - 0.5f).coerceIn(-MAX_PAN, MAX_PAN),
            rotationDegrees =
                normalize(
                    startFraming.rotationDegrees + Math.toDegrees(radians.toDouble()).toFloat(),
                ),
        )
    }

    /** Applies one snap to the settled angle (call on gesture end only). */
    fun commitSnap(framing: Framing): Framing =
        framing.copy(rotationDegrees = normalize(snap(framing.rotationDegrees.toFloat())))

    /** Snaps an absolute canvas angle to the nearest grid line. */
    fun snap(angleDegrees: Float): Float = (angleDegrees / SNAP_STEP_DEGREES).roundToInt() * SNAP_STEP_DEGREES

    fun normalize(angleDegrees: Float): Int {
        val normalized = ((angleDegrees % 360f) + 360f) % 360f
        return normalized.roundToInt() % 360
    }

    const val SNAP_STEP_DEGREES = 45f
    const val ROTATION_DEADZONE_DEGREES = 10f
    private const val MAX_PAN = 1.5f
}
