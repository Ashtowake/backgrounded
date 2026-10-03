package dev.backgrounded.domain.render

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BoxBlurTest {
    @Test
    fun `blur spreads a bright pixel and preserves alpha`() {
        val size = 5
        val source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size) { Color.BLACK }
        pixels[12] = Color.WHITE
        source.setPixels(pixels, 0, size, 0, 0, size, size)

        val blurred = BoxBlur.blur(source, radius = 1, passes = 1)
        val output = IntArray(size * size)
        blurred.getPixels(output, 0, size, 0, 0, size, size)

        assertEquals(255, Color.alpha(output[12]))
        assertTrue(Color.red(output[12]) in 1..254)
        assertTrue(Color.red(output[11]) > 0)
        assertTrue(Color.red(output[7]) > 0)
        assertEquals(255, Color.alpha(output[0]))
    }

    @Test
    fun `blur of a uniform image is stable`() {
        val size = 4
        val source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size) { Color.rgb(40, 80, 120) }
        source.setPixels(pixels, 0, size, 0, 0, size, size)

        val blurred = BoxBlur.blur(source, radius = 2, passes = 2)
        val output = IntArray(size * size)
        blurred.getPixels(output, 0, size, 0, 0, size, size)

        output.forEach { pixel ->
            assertEquals(40, Color.red(pixel))
            assertEquals(80, Color.green(pixel))
            assertEquals(120, Color.blue(pixel))
            assertEquals(255, Color.alpha(pixel))
        }
    }
}
