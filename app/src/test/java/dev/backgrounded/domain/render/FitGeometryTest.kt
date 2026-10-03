package dev.backgrounded.domain.render

import dev.backgrounded.domain.model.FitMode
import dev.backgrounded.domain.model.NormalizedCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FitGeometryTest {
    @Test
    fun `stretch fills exactly`() {
        val placement =
            FitGeometry.placement(
                framing = framing(FitMode.STRETCH),
                sourceWidth = 100,
                sourceHeight = 50,
                outWidth = 200,
                outHeight = 200,
            )
        assertEquals(0f, placement.destination.left)
        assertEquals(200f, placement.destination.right)
        assertEquals(0f, placement.destination.top)
        assertEquals(200f, placement.destination.bottom)
    }

    @Test
    fun `stretch axis factors scale independently`() {
        val placement =
            FitGeometry.placement(
                framing = framing(FitMode.STRETCH).copy(stretchX = 0.5f, stretchY = 2f),
                sourceWidth = 100,
                sourceHeight = 50,
                outWidth = 200,
                outHeight = 200,
            )
        assertEquals(100f, placement.destination.width(), 0.001f)
        assertEquals(400f, placement.destination.height(), 0.001f)
        assertEquals(100f, placement.destination.centerX(), 0.001f)
        assertEquals(100f, placement.destination.centerY(), 0.001f)
    }

    @Test
    fun `stretch factors are clamped`() {
        val placement =
            FitGeometry.placement(
                framing = framing(FitMode.STRETCH).copy(stretchX = 10f, stretchY = 0f),
                sourceWidth = 100,
                sourceHeight = 50,
                outWidth = 200,
                outHeight = 200,
            )
        assertEquals(200f * 3f, placement.destination.width(), 0.001f)
        assertEquals(200f * 0.1f, placement.destination.height(), 0.001f)
    }

    @Test
    fun `fill covers the frame without distortion`() {
        val placement =
            FitGeometry.placement(
                framing = framing(FitMode.FILL),
                sourceWidth = 100,
                sourceHeight = 50,
                outWidth = 200,
                outHeight = 200,
            )
        val width = placement.destination.width()
        val height = placement.destination.height()
        assertEquals(2f, width / height, 0.001f)
        assertTrue(placement.destination.left <= 0f)
        assertTrue(placement.destination.top <= 0f)
        assertTrue(placement.destination.right >= 200f)
        assertTrue(placement.destination.bottom >= 200f)
    }

    @Test
    fun `fit fully contains the image`() {
        val placement =
            FitGeometry.placement(
                framing = framing(FitMode.FIT),
                sourceWidth = 100,
                sourceHeight = 50,
                outWidth = 200,
                outHeight = 200,
            )
        assertTrue(placement.destination.left >= 0f)
        assertTrue(placement.destination.top >= 0f)
        assertTrue(placement.destination.right <= 200f)
        assertTrue(placement.destination.bottom <= 200f)
    }

    @Test
    fun `crop selects a normalized source region`() {
        val placement =
            FitGeometry.placement(
                framing =
                    framing(FitMode.FIT).copy(
                        crop = NormalizedCrop(left = 0.25f, top = 0.1f, width = 0.5f, height = 0.5f),
                    ),
                sourceWidth = 400,
                sourceHeight = 200,
                outWidth = 200,
                outHeight = 200,
            )
        assertEquals(100f, placement.source.left, 0.001f)
        assertEquals(20f, placement.source.top, 0.001f)
        assertEquals(300f, placement.source.right, 0.001f)
        assertEquals(120f, placement.source.bottom, 0.001f)
    }

    @Test
    fun `zoom and pan move the destination`() {
        val base =
            FitGeometry.placement(
                framing = framing(FitMode.FIT),
                sourceWidth = 100,
                sourceHeight = 100,
                outWidth = 200,
                outHeight = 200,
            )
        val zoomed =
            FitGeometry.placement(
                framing = framing(FitMode.FIT).copy(zoom = 2f, panX = 0.25f, panY = -0.1f),
                sourceWidth = 100,
                sourceHeight = 100,
                outWidth = 200,
                outHeight = 200,
            )
        assertEquals(base.destination.width() * 2f, zoomed.destination.width(), 0.001f)
        assertEquals(base.destination.centerX() + 50f, zoomed.destination.centerX(), 0.001f)
    }

    private fun framing(fitMode: FitMode): FitGeometry.Framing =
        FitGeometry.Framing(
            fitMode = fitMode,
            crop = NormalizedCrop(),
            zoom = 1f,
            panX = 0f,
            panY = 0f,
            stretchX = 1f,
            stretchY = 1f,
        )
}
