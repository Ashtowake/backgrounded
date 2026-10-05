package dev.backgrounded.domain.render

import android.graphics.Bitmap
import android.graphics.Color
import dev.backgrounded.domain.model.FitMode
import dev.backgrounded.domain.model.Framing
import dev.backgrounded.domain.model.ScrollMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackgroundRendererScrollTest {
    @Test
    fun `enabling scroll does not change image scale when it has no horizontal overflow`() {
        val source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        val renderer = BackgroundRenderer()
        val viewport = BackgroundRenderer.Viewport(100, 100)
        val framing = Framing.DEFAULT.copy(fitMode = FitMode.FIT)
        val withoutScroll =
            renderer.renderWindow(
                framing,
                source,
                viewport,
                ScrollGeometry.scrollFor(framing, 100),
                0.5f,
                false,
            )
        val scrolling = framing.copy(scrollMode = ScrollMode.CUSTOM, scrollAmountPercent = 100)
        val withScroll =
            renderer.renderWindow(
                scrolling,
                source,
                viewport,
                ScrollGeometry.scrollFor(scrolling, 100),
                0.5f,
                false,
            )
        assertTrue(withoutScroll!!.sameAs(withScroll))
        withoutScroll.recycle()
        withScroll!!.recycle()
        source.recycle()
    }
}
