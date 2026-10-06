package dev.backgrounded.domain.render

import android.graphics.Bitmap
import android.graphics.Color
import dev.backgrounded.domain.model.BackdropType
import dev.backgrounded.domain.model.FitMode
import dev.backgrounded.domain.model.Framing
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundRendererBackdropTest {
    @Test
    fun `tiny blur never recycles its aliased source`() {
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        val renderer = BackgroundRenderer()
        val framing = Framing.DEFAULT.copy(backdrop = BackdropType.BLUR)
        renderer.prepareBackdrop(source, framing)
        org.junit.Assert.assertFalse(source.isRecycled)
        val result =
            renderer.renderWindow(
                framing,
                source,
                BackgroundRenderer.Viewport(40, 80),
                ScrollGeometry.scrollFor(framing, 40),
                0f,
                false,
            )!!
        assertEquals(Color.RED, result.getPixel(20, 40))
        source.recycle()
        result.recycle()
    }

    @Test
    fun `rotating an opaque image leaves transparent corners`() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        val rotated = BackgroundRenderer().rotated(source, 45)

        assertEquals(0, Color.alpha(rotated.getPixel(0, 0)))
        assertEquals(Color.RED, rotated.getPixel(rotated.width / 2, rotated.height / 2))
        source.recycle()
        rotated.recycle()
    }

    @Test
    fun `adding a color backdrop does not change fit placement`() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        val renderer = BackgroundRenderer()
        val viewport = BackgroundRenderer.Viewport(100, 200)
        val plain = Framing.DEFAULT.copy(fitMode = FitMode.FIT, backdrop = BackdropType.NONE)
        val colored = plain.copy(backdrop = BackdropType.COLOR, backdropColor = Color.BLUE)

        val without =
            renderer.renderWindow(plain, source, viewport, ScrollGeometry.scrollFor(plain, 100), 0f, false)!!
        val with =
            renderer.renderWindow(colored, source, viewport, ScrollGeometry.scrollFor(colored, 100), 0f, false)!!

        assertEquals(Color.TRANSPARENT, without.getPixel(5, 5))
        assertEquals(Color.BLUE, with.getPixel(5, 5))
        assertEquals(Color.RED, without.getPixel(50, 100))
        assertEquals(Color.RED, with.getPixel(50, 100))
        source.recycle()
        without.recycle()
        with.recycle()
    }
}
