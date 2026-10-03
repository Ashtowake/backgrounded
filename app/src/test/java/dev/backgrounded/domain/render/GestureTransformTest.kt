package dev.backgrounded.domain.render

import dev.backgrounded.domain.model.Framing
import org.junit.Assert.assertEquals
import org.junit.Test

class GestureTransformTest {
    @Test
    fun `translate maps the fingertips one to one`() {
        val startFraming = Framing.DEFAULT
        val settled =
            GestureTransform.settle(
                startFraming = startFraming,
                gesture =
                    gesture(
                        start = fingerPair(centroidX = 400f, vectorX = 200f),
                        current = fingerPair(centroidX = 450f, vectorX = 200f),
                    ),
            )
        assertEquals(0f, settled.panX - 50f / FRAME_SIZE, 0.0001f)
        assertEquals(0f, settled.panY, 0.0001f)
        assertEquals(1f, settled.zoom, 0.0001f)
        assertEquals(0, settled.rotationDegrees)
    }

    @Test
    fun `scaling about the centroid keeps the anchored point fixed`() {
        val settled =
            GestureTransform.settle(
                startFraming = Framing.DEFAULT,
                gesture =
                    gesture(
                        start = fingerPair(centroidX = 0.25f * FRAME_SIZE, vectorX = 100f),
                        current = fingerPair(centroidX = 0.25f * FRAME_SIZE, vectorX = 200f),
                    ),
            )
        assertEquals(2f, settled.zoom, 0.0001f)
        assertEquals(0.25f, settled.panX, 0.0001f)
        assertEquals(0f, settled.panY, 0.0001f)
    }

    @Test
    fun `rotation follows the finger vector`() {
        val settled =
            GestureTransform.settle(
                startFraming = Framing.DEFAULT,
                gesture =
                    gesture(
                        start = fingerPair(centroidX = 400f, vectorX = 200f, vectorY = 0f),
                        current = fingerPair(centroidX = 400f, vectorX = 0f, vectorY = 200f),
                    ),
            )
        assertEquals(89, settled.rotationDegrees)
        assertEquals(0f, settled.panX, 0.0001f)
        assertEquals(0f, settled.panY, 0.0001f)
    }

    @Test
    fun `small rotations stay inside the deadzone`() {
        val settled =
            GestureTransform.settle(
                startFraming = Framing.DEFAULT,
                gesture =
                    gesture(
                        start = fingerPair(centroidX = 400f, vectorX = 200f, vectorY = 0f),
                        current = fingerPair(centroidX = 400f, vectorX = 200f, vectorY = 17f),
                    ),
            )
        assertEquals(0, settled.rotationDegrees)
    }

    @Test
    fun `rotation ramps out of the deadzone without jumping to its full value`() {
        val start = fingerPair(centroidX = 400f, vectorX = 200f, vectorY = 0f)
        val justOver =
            GestureTransform.settle(
                startFraming = Framing.DEFAULT,
                gesture = gesture(start = start, current = rotatedVector(degrees = 12f)),
            )
        assertEquals(4, justOver.rotationDegrees)

        val wellOver =
            GestureTransform.settle(
                startFraming = Framing.DEFAULT,
                gesture = gesture(start = start, current = rotatedVector(degrees = 30f)),
            )
        assertEquals(27, wellOver.rotationDegrees)
    }

    @Test
    fun `settling twice from the same start is idempotent`() {
        val startFraming = Framing.DEFAULT.copy(zoom = 1.5f, rotationDegrees = 20)
        val gesture =
            gesture(
                start = fingerPair(centroidX = 300f, vectorX = 120f),
                current = fingerPair(centroidX = 420f, vectorX = 180f, vectorY = 30f),
            )
        val first = GestureTransform.settle(startFraming, gesture)
        val second = GestureTransform.settle(startFraming, gesture)
        assertEquals(first, second)
    }

    @Test
    fun `snap aligns the absolute angle to the nearest 45 degree grid line`() {
        assertEquals(
            0,
            GestureTransform.commitSnap(Framing.DEFAULT.copy(rotationDegrees = 8)).rotationDegrees,
        )
        assertEquals(
            90,
            GestureTransform.commitSnap(Framing.DEFAULT.copy(rotationDegrees = 98)).rotationDegrees,
        )
        assertEquals(
            45,
            GestureTransform.commitSnap(Framing.DEFAULT.copy(rotationDegrees = 38)).rotationDegrees,
        )
        assertEquals(
            180,
            GestureTransform.commitSnap(Framing.DEFAULT.copy(rotationDegrees = 170)).rotationDegrees,
        )
    }

    @Test
    fun `rotation normalizes into zero to 359`() {
        assertEquals(10, GestureTransform.normalize(-350f))
        assertEquals(350, GestureTransform.normalize(710f))
    }

    private fun gesture(
        start: GestureTransform.FingerPair,
        current: GestureTransform.FingerPair,
    ): GestureTransform.Gesture =
        GestureTransform.Gesture(
            start = start,
            current = current,
            frameWidth = FRAME_SIZE,
            frameHeight = FRAME_SIZE,
        )

    private fun fingerPair(
        centroidX: Float,
        centroidY: Float = FRAME_SIZE / 2f,
        vectorX: Float,
        vectorY: Float = 0f,
    ): GestureTransform.FingerPair =
        GestureTransform.FingerPair(
            centroidX = centroidX,
            centroidY = centroidY,
            vectorX = vectorX,
            vectorY = vectorY,
        )

    private fun rotatedVector(degrees: Float): GestureTransform.FingerPair {
        val radians = Math.toRadians(degrees.toDouble())
        val radius = 200.0
        return fingerPair(
            centroidX = 400f,
            vectorX = (radius * kotlin.math.cos(radians)).toFloat(),
            vectorY = (radius * kotlin.math.sin(radians)).toFloat(),
        )
    }

    private companion object {
        const val FRAME_SIZE = 800f
    }
}
