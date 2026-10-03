package dev.backgrounded.domain.render

import android.graphics.RectF
import dev.backgrounded.domain.model.FitMode
import dev.backgrounded.domain.model.Framing
import kotlin.math.max

/**
 * Double-tap helper: enlarges the image just enough to cover the frame while keeping the
 * existing framing as close as possible (only the missing edges are pushed out).
 */
object FillAligner {
    fun fill(
        framing: Framing,
        outWidth: Int,
        outHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Framing {
        if (outWidth <= 0 || outHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return framing
        val frame = RectF(0f, 0f, outWidth.toFloat(), outHeight.toFloat())
        val first = placement(framing, outWidth, outHeight, sourceWidth, sourceHeight)
        if (covers(first, frame)) return framing

        val adjusted =
            if (framing.fitMode == FitMode.STRETCH) {
                val stretchX =
                    if (first.destination.width() < outWidth) {
                        framing.stretchX * outWidth / first.destination.width()
                    } else {
                        framing.stretchX
                    }
                val stretchY =
                    if (first.destination.height() < outHeight) {
                        framing.stretchY * outHeight / first.destination.height()
                    } else {
                        framing.stretchY
                    }
                framing.copy(
                    stretchX = stretchX.coerceAtMost(FitGeometry.MAX_STRETCH),
                    stretchY = stretchY.coerceAtMost(FitGeometry.MAX_STRETCH),
                )
            } else {
                val factor =
                    max(
                        outWidth / first.destination.width(),
                        outHeight / first.destination.height(),
                    )
                framing.copy(zoom = (framing.zoom * factor).coerceAtMost(FitGeometry.MAX_ZOOM))
            }

        val second = placement(adjusted, outWidth, outHeight, sourceWidth, sourceHeight)
        val shiftX =
            when {
                second.destination.left > 0f -> -second.destination.left
                second.destination.right < outWidth -> outWidth - second.destination.right
                else -> 0f
            }
        val shiftY =
            when {
                second.destination.top > 0f -> -second.destination.top
                second.destination.bottom < outHeight -> outHeight - second.destination.bottom
                else -> 0f
            }
        return adjusted.copy(
            panX = (adjusted.panX + shiftX / outWidth).coerceIn(-MAX_PAN, MAX_PAN),
            panY = (adjusted.panY + shiftY / outHeight).coerceIn(-MAX_PAN, MAX_PAN),
        )
    }

    private fun covers(
        placement: FitGeometry.Placement,
        frame: RectF,
    ): Boolean =
        placement.destination.left <= frame.left &&
            placement.destination.top <= frame.top &&
            placement.destination.right >= frame.right &&
            placement.destination.bottom >= frame.bottom

    private fun placement(
        framing: Framing,
        outWidth: Int,
        outHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): FitGeometry.Placement =
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

    private const val MAX_PAN = 1.5f
}
