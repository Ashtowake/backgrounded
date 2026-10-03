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
    fun `fit with a gap is enlarged to cover`() {
        val framing = Framing.DEFAULT.copy(fitMode = FitMode.FIT, zoom = 0.5f)
        val filled = FillAligner.fill(framing, 200, 200, 100, 100)
        assertCovers(filled, 200, 200, 100, 100)
    }

    @Test
    fun `an already covering framing is unchanged`() {
        val framing = Framing.DEFAULT.copy(fitMode = FitMode.FILL)
        assertEquals(framing, FillAligner.fill(framing, 200, 200, 100, 100))
    }

    @Test
    fun `a shifted image is pushed back to the nearest edge`() {
        val framing = Framing.DEFAULT.copy(fitMode = FitMode.FILL, panX = -0.3f)
        val filled = FillAligner.fill(framing, 200, 200, 100, 100)
        assertCovers(filled, 200, 200, 100, 100)
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
