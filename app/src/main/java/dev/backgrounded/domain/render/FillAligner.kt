package dev.backgrounded.domain.render

import android.graphics.RectF
import dev.backgrounded.domain.model.FitMode
import dev.backgrounded.domain.model.Framing
import kotlin.math.max

/** Aligns the current framing; legacy [fill] remains available for explicit coverage calculations. */
object FillAligner {
    /** Snaps the existing image to the viewport without changing its size or fit mode. */
    fun align(
        framing: Framing,
        outWidth: Int,
        outHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Framing {
        if (outWidth <= 0 || outHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return framing
        val destination = placement(framing, outWidth, outHeight, sourceWidth, sourceHeight).destination
        val shiftX = edgeCorrection(destination.left, destination.right, outWidth.toFloat())
        val shiftY = edgeCorrection(destination.top, destination.bottom, outHeight.toFloat())
        return framing.copy(
            panX = (framing.panX + shiftX / outWidth).coerceIn(-MAX_PAN, MAX_PAN),
            panY = (framing.panY + shiftY / outHeight).coerceIn(-MAX_PAN, MAX_PAN),
        )
    }

    private fun edgeCorrection(
        start: Float,
        end: Float,
        frameSize: Float,
    ): Float =
        if (end - start < frameSize) {
            (frameSize - (end - start)) / 2f - start
        } else {
            when {
                start > 0f -> -start
                end < frameSize -> frameSize - end
                else -> 0f
            }
        }

    fun fill(
        framing: Framing,
        outWidth: Int,
        outHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        coverageWidth: Int = outWidth,
    ): Framing {
        if (outWidth <= 0 || outHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return framing
        val frame = RectF(0f, 0f, coverageWidth.coerceAtLeast(outWidth).toFloat(), outHeight.toFloat())
        val first = placement(framing, outWidth, outHeight, sourceWidth, sourceHeight)
        if (covers(first, frame)) return framing

        val adjusted =
            if (framing.fitMode == FitMode.STRETCH) {
                val stretchX =
                    if (first.destination.width() < frame.width()) {
                        framing.stretchX * frame.width() / first.destination.width()
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
                        frame.width() / first.destination.width(),
                        frame.height() / first.destination.height(),
                    )
                framing.copy(zoom = (framing.zoom * factor).coerceAtMost(FitGeometry.MAX_ZOOM))
            }

        val second = placement(adjusted, outWidth, outHeight, sourceWidth, sourceHeight)
        val shiftX =
            when {
                second.destination.left > frame.left -> frame.left - second.destination.left
                second.destination.right < frame.right -> frame.right - second.destination.right
                else -> 0f
            }
        val shiftY =
            when {
                second.destination.top > frame.top -> frame.top - second.destination.top
                second.destination.bottom < frame.bottom -> frame.bottom - second.destination.bottom
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
