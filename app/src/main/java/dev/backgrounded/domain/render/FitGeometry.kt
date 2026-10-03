package dev.backgrounded.domain.render

import android.graphics.RectF
import dev.backgrounded.domain.model.FitMode
import dev.backgrounded.domain.model.NormalizedCrop
import kotlin.math.max
import kotlin.math.min

object FitGeometry {
    data class Placement(val source: RectF, val destination: RectF)

    data class Framing(
        val fitMode: FitMode,
        val crop: NormalizedCrop,
        val zoom: Float,
        val panX: Float,
        val panY: Float,
        val stretchX: Float,
        val stretchY: Float,
    )

    fun placement(
        framing: Framing,
        sourceWidth: Int,
        sourceHeight: Int,
        outWidth: Int,
        outHeight: Int,
    ): Placement {
        val source =
            RectF(
                framing.crop.left * sourceWidth,
                framing.crop.top * sourceHeight,
                (framing.crop.left + framing.crop.width) * sourceWidth,
                (framing.crop.top + framing.crop.height) * sourceHeight,
            )
        val contentWidth = max(1f, source.width())
        val contentHeight = max(1f, source.height())
        val frameWidth = outWidth.toFloat()
        val frameHeight = outHeight.toFloat()

        if (framing.fitMode == FitMode.STRETCH) {
            val zoom = framing.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
            val width = frameWidth * framing.stretchX.coerceIn(MIN_STRETCH, MAX_STRETCH) * zoom
            val height = frameHeight * framing.stretchY.coerceIn(MIN_STRETCH, MAX_STRETCH) * zoom
            val centerX = frameWidth / 2f + framing.panX * frameWidth
            val centerY = frameHeight / 2f + framing.panY * frameHeight
            return Placement(
                source,
                RectF(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f),
            )
        }

        val baseScale =
            when (framing.fitMode) {
                FitMode.FILL -> max(frameWidth / contentWidth, frameHeight / contentHeight)
                else -> min(frameWidth / contentWidth, frameHeight / contentHeight)
            }
        val scale = baseScale * framing.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val width = contentWidth * scale
        val height = contentHeight * scale
        val centerX = frameWidth / 2f + framing.panX * frameWidth
        val centerY = frameHeight / 2f + framing.panY * frameHeight
        return Placement(
            source,
            RectF(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f),
        )
    }

    const val MIN_ZOOM = 0.2f
    const val MAX_ZOOM = 8f
    const val MIN_STRETCH = 0.1f
    const val MAX_STRETCH = 3f
}
