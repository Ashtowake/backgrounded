package dev.backgrounded.domain.render

import dev.backgrounded.domain.model.FitMode
import dev.backgrounded.domain.model.Framing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FillAlignerTest {
    @Test
    fun `alignment preserves manually chosen image size and fit mode`() {
        val framing = Framing.DEFAULT.copy(fitMode = FitMode.FIT, zoom = 0.6f, panX = 0.8f)
        val aligned = FillAligner.align(framing, 200, 200, 100, 100)
        assertEquals(FitMode.FIT, aligned.fitMode)
        assertEquals(0.6f, aligned.zoom, 0.0001f)
        assertEquals(0f, aligned.panX, 0.0001f)
    }

    @Test
    fun `a gap is closed by enlarging until the frame is covered`() {
        val framing = Framing.DEFAULT.copy(fitMode = FitMode.FIT, zoom = 0.5f)
        val filled = FillAligner.fill(framing, 200, 200, 100, 100)
        assertCovers(filled, 200, 200, 100, 100)
        assertEquals(1f, filled.zoom, 0.0001f)
    }

    @Test
    fun `an already covering framing is unchanged`() {
        val framing = Framing.DEFAULT.copy(fitMode = FitMode.FILL)
        assertEquals(FitMode.FILL, framing.fitMode)
        assertEquals(framing, FillAligner.fill(framing, 200, 200, 100, 100))
    }

    @Test
    fun `a shifted image is pushed back to the nearest edge without extra zoom`() {
        val framing = Framing.DEFAULT.copy(fitMode = FitMode.FILL, panX = -0.3f)
        val filled = FillAligner.fill(framing, 200, 200, 100, 100)
        assertCovers(filled, 200, 200, 100, 100)
        assertEquals(1f, filled.zoom, 0.0001f)
    }

    @Test
    fun `cover is minimal - the image only overflows where its aspect requires`() {
        val framing = Framing.DEFAULT.copy(fitMode = FitMode.FIT, zoom = 0.5f)
        val filled = FillAligner.fill(framing, 200, 400, 100, 100)
        assertCovers(filled, 200, 400, 100, 100)
        val placement =
            FitGeometry.placement(
                framing =
                    FitGeometry.Framing(
                        fitMode = filled.fitMode,
                        crop = filled.crop,
                        zoom = filled.zoom,
                        panX = filled.panX,
                        panY = filled.panY,
                        stretchX = filled.stretchX,
                        stretchY = filled.stretchY,
                    ),
                sourceWidth = 100,
                sourceHeight = 100,
                outWidth = 200,
                outHeight = 400,
            )
        assertEquals(400f, placement.destination.height(), 0.5f)
        assertTrue(placement.destination.width() >= 200f - 0.5f)
    }

    @Test
    fun `a fitting image already covering the scroll band is unchanged`() {
        val framing = Framing.DEFAULT.copy(fitMode = FitMode.FIT)
        assertEquals(framing, FillAligner.fill(framing, 200, 100, 200, 100))
    }

    @Test
    fun `a scroll band wider than the screen is covered across its full width`() {
        val framing = Framing.DEFAULT.copy(fitMode = FitMode.FIT)
        val filled =
            FillAligner.fill(
                framing = framing,
                outWidth = 100,
                outHeight = 100,
                sourceWidth = 100,
                sourceHeight = 100,
                coverageWidth = 200,
            )
        val placement =
            FitGeometry.placement(
                framing =
                    FitGeometry.Framing(
                        fitMode = filled.fitMode,
                        crop = filled.crop,
                        zoom = filled.zoom,
                        panX = filled.panX,
                        panY = filled.panY,
                        stretchX = filled.stretchX,
                        stretchY = filled.stretchY,
                    ),
                sourceWidth = 100,
                sourceHeight = 100,
                outWidth = 100,
                outHeight = 100,
            )
        assertEquals(2f, filled.zoom, 0.0001f)
        assertTrue(placement.destination.left <= 0.5f)
        assertTrue(placement.destination.right >= 200f - 0.5f)
    }

    @Test
    fun `stretch axes are grown only where they fall short`() {
        val framing =
            Framing.DEFAULT.copy(
                fitMode = FitMode.STRETCH,
                stretchX = 0.5f,
                stretchY = 1f,
            )
        val filled = FillAligner.fill(framing, 200, 200, 100, 100)
        assertEquals(1f, filled.stretchX, 0.0001f)
        assertEquals(1f, filled.stretchY, 0.0001f)
        assertCovers(filled, 200, 200, 100, 100)
    }

    private fun assertCovers(
        framing: Framing,
        outWidth: Int,
        outHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ) {
        val placement =
            FitGeometry.placement(
                framing =
                    FitGeometry.Framing(
                        fitMode = framing.fitMode,
                        crop = framing.crop,
                        zoom = framing.zoom,
                        panX = framing.panX,
                        panY = framing.panY,
                        stretchX = framing.stretchX,
                        stretchY = framing.stretchY,
                    ),
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                outWidth = outWidth,
                outHeight = outHeight,
            )
        assertTrue(placement.destination.left <= 0.5f)
        assertTrue(placement.destination.top <= 0.5f)
        assertTrue(placement.destination.right >= outWidth - 0.5f)
        assertTrue(placement.destination.bottom >= outHeight - 0.5f)
    }
}
