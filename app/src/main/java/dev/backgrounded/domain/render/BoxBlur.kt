package dev.backgrounded.domain.render

import android.graphics.Bitmap

object BoxBlur {
    /**
     * Deterministic separable box blur. [radius] is in pixels of [source];
     * [passes] approximates a Gaussian as passes increase.
     */
    fun blur(
        source: Bitmap,
        radius: Int,
        passes: Int = 2,
    ): Bitmap {
        val clampedRadius = radius.coerceIn(1, MAX_RADIUS)
        val width = source.width
        val height = source.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        var scratch = IntArray(width * height)
        repeat(passes.coerceIn(1, MAX_PASSES)) {
            blurHorizontal(pixels, scratch, width, height, clampedRadius)
            blurVertical(scratch, pixels, width, height, clampedRadius)
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun blurHorizontal(
        source: IntArray,
        target: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val window = radius * 2 + 1
        for (y in 0 until height) {
            val rowStart = y * width
            var sumA = 0L
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            for (dx in -radius..radius) {
                val pixel = source[rowStart + dx.coerceIn(0, width - 1)]
                sumA += pixel ushr 24 and 0xFF
                sumR += pixel ushr 16 and 0xFF
                sumG += pixel ushr 8 and 0xFF
                sumB += pixel and 0xFF
            }
            for (x in 0 until width) {
                target[rowStart + x] =
                    pack(
                        (sumA / window).toInt(),
                        (sumR / window).toInt(),
                        (sumG / window).toInt(),
                        (sumB / window).toInt(),
                    )
                val outPixel = source[rowStart + (x - radius).coerceIn(0, width - 1)]
                val inPixel = source[rowStart + (x + radius + 1).coerceIn(0, width - 1)]
                sumA += (inPixel ushr 24 and 0xFF) - (outPixel ushr 24 and 0xFF)
                sumR += (inPixel ushr 16 and 0xFF) - (outPixel ushr 16 and 0xFF)
                sumG += (inPixel ushr 8 and 0xFF) - (outPixel ushr 8 and 0xFF)
                sumB += (inPixel and 0xFF) - (outPixel and 0xFF)
            }
        }
    }

    private fun blurVertical(
        source: IntArray,
        target: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var sumA = 0L
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            for (dy in -radius..radius) {
                val pixel = source[dy.coerceIn(0, height - 1) * width + x]
                sumA += pixel ushr 24 and 0xFF
                sumR += pixel ushr 16 and 0xFF
                sumG += pixel ushr 8 and 0xFF
                sumB += pixel and 0xFF
            }
            for (y in 0 until height) {
                target[y * width + x] =
                    pack(
                        (sumA / window).toInt(),
                        (sumR / window).toInt(),
                        (sumG / window).toInt(),
                        (sumB / window).toInt(),
                    )
                val outPixel = source[(y - radius).coerceIn(0, height - 1) * width + x]
                val inPixel = source[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                sumA += (inPixel ushr 24 and 0xFF) - (outPixel ushr 24 and 0xFF)
                sumR += (inPixel ushr 16 and 0xFF) - (outPixel ushr 16 and 0xFF)
                sumG += (inPixel ushr 8 and 0xFF) - (outPixel ushr 8 and 0xFF)
                sumB += (inPixel and 0xFF) - (outPixel and 0xFF)
            }
        }
    }

    private fun pack(
        a: Int,
        r: Int,
        g: Int,
        b: Int,
    ): Int =
        (a.coerceIn(0, 255) shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)

    private const val MAX_RADIUS = 64
    private const val MAX_PASSES = 4
}
