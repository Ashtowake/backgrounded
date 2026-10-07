package dev.backgrounded.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import dev.backgrounded.core.image.ImageMemoryBudget
import dev.backgrounded.domain.model.BackdropType
import dev.backgrounded.domain.model.FitMode
import dev.backgrounded.domain.model.Framing
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max

@Singleton
class BackgroundRenderer
    constructor(private val memory: ImageMemoryBudget? = null) {
        data class Viewport(val width: Int, val height: Int)

        data class Frame(
            val framing: Framing,
            val scroll: ScrollGeometry.Scroll,
            val scrollOffset: Float,
            val dim: Boolean,
            val alpha: Int,
            val allowScroll: Boolean = true,
            val gyroShiftX: Float = 0f,
            val gyroShiftY: Float = 0f,
            val gyroMargin: Float = 0f,
            val slide: SlideMotion.Position? = null,
            val preparedBackdrop: Bitmap? = null,
        )

        private data class BlurKey(val source: Bitmap, val intensity: Int)

        private val blurCache = LruCache<BlurKey, Bitmap>(BLUR_CACHE_ENTRIES)

        /** Prepare expensive blur work before a layer reaches the wallpaper drawing thread. */
        fun prepareBackdrop(
            source: Bitmap,
            framing: Framing,
        ): Bitmap? = if (framing.backdrop == BackdropType.BLUR) blurredBackdrop(source, framing.blurIntensity) else null

        fun rotated(
            source: Bitmap,
            degrees: Int,
        ): Bitmap {
            val normalized = ((degrees % 360) + 360) % 360
            if (normalized == 0) return source
            val matrix =
                Matrix().apply {
                    setRotate(normalized.toFloat(), source.width / 2f, source.height / 2f)
                }
            val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
            matrix.mapRect(bounds)
            val output =
                createBitmap(
                    ceil(bounds.width()).toInt().coerceAtLeast(1),
                    ceil(bounds.height()).toInt().coerceAtLeast(1),
                )
            output.eraseColor(Color.TRANSPARENT)
            Canvas(output).apply {
                translate(-bounds.left, -bounds.top)
                drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            return output
        }

        /** Renders the visible window at the frame's scroll offset into a new screen-sized bitmap. */
        fun renderWindow(
            framing: Framing,
            source: Bitmap,
            viewport: Viewport,
            scroll: ScrollGeometry.Scroll,
            scrollOffset: Float,
            dim: Boolean,
            allowScroll: Boolean = true,
        ): Bitmap? {
            if (viewport.width <= 0 || viewport.height <= 0) return null
            val rotated = rotated(source, framing.rotationDegrees)
            val available = memory?.remaining() ?: Long.MAX_VALUE
            val scale =
                kotlin.math.sqrt(available.toDouble() / (viewport.width.toLong() * viewport.height * 4))
                    .coerceAtMost(1.0)
            val actual =
                Viewport(
                    (viewport.width * scale).toInt().coerceAtLeast(1),
                    (viewport.height * scale).toInt().coerceAtLeast(1),
                )
            var output: Bitmap? = null
            var completed = false
            try {
                output = createBitmap(actual.width, actual.height)
                draw(
                    canvas = Canvas(requireNotNull(output)),
                    rotatedSource = rotated,
                    viewport = actual,
                    frame =
                        Frame(
                            framing = framing,
                            scroll = scroll.copy(slackPixels = (scroll.slackPixels * scale).toInt()),
                            scrollOffset = scrollOffset,
                            dim = dim,
                            alpha = 255,
                            allowScroll = allowScroll,
                            preparedBackdrop = prepareBackdrop(rotated, framing),
                        ),
                )
                completed = true
            } finally {
                if (!completed) output?.recycle()
                if (rotated !== source) rotated.recycle()
            }
            return output
        }

        /** Draws the visible window directly onto [canvas]; only the visible pixels are painted. */
        @Suppress("LongMethod")
        fun draw(
            canvas: Canvas,
            rotatedSource: Bitmap,
            viewport: Viewport,
            frame: Frame,
        ) {
            if (viewport.width <= 0 || viewport.height <= 0) return
            val framing = frame.framing
            val virtualWidth = viewport.width + frame.scroll.slackPixels
            val translation =
                ScrollGeometry.translationPixels(frame.scroll, frame.scrollOffset, frame.allowScroll)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = frame.alpha }
            canvas.save()
            canvas.translate(-translation.toFloat() + frame.gyroShiftX, frame.gyroShiftY)
            val margin = frame.gyroMargin
            if (framing.backdrop != BackdropType.NONE) {
                drawBackdrop(
                    canvas,
                    framing,
                    virtualWidth,
                    viewport.height,
                    frame.alpha,
                    margin,
                    frame.preparedBackdrop,
                )
            }
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
                    sourceWidth = rotatedSource.width,
                    sourceHeight = rotatedSource.height,
                    outWidth = viewport.width,
                    outHeight = viewport.height,
                )
            val sourceRect =
                Rect(
                    placement.source.left.toInt(),
                    placement.source.top.toInt(),
                    placement.source.right.toInt(),
                    placement.source.bottom.toInt(),
                )
            val destination =
                RectF(
                    placement.destination.left,
                    placement.destination.top,
                    placement.destination.right,
                    placement.destination.bottom,
                ).apply {
                    if (margin > 0f) inset(-margin, -margin)
                }
            if (frame.allowScroll) {
                destination.offset(
                    translation +
                        ScrollGeometry.edgeShiftPixels(
                            destination.left,
                            destination.right,
                            viewport.width,
                            frame.scroll,
                            translation,
                        ),
                    0f,
                )
            }
            frame.slide?.let { slide ->
                val coverageScale =
                    if (framing.fitMode == FitMode.FIT) {
                        1f
                    } else {
                        max(
                            1f + slide.travelX / destination.width(),
                            1f + slide.travelY / destination.height(),
                        )
                    }
                val scale = coverageScale * slide.zoom
                destination.inset(
                    -(scale - 1f) * destination.width() / 2f,
                    -(scale - 1f) * destination.height() / 2f,
                )
                destination.offset(slide.offsetX, slide.offsetY)
            }
            if (framing.mirrorX || framing.mirrorY) {
                canvas.save()
                canvas.scale(
                    if (framing.mirrorX) -1f else 1f,
                    if (framing.mirrorY) -1f else 1f,
                    destination.centerX(),
                    destination.centerY(),
                )
            }
            canvas.drawBitmap(rotatedSource, sourceRect, destination, paint)
            if (framing.mirrorX || framing.mirrorY) canvas.restore()
            canvas.restore()
            if (frame.dim) {
                val overlay =
                    Paint().apply {
                        color = DIM_COLOR
                        alpha = (DIM_ALPHA * frame.alpha / 255).coerceIn(0, 255)
                    }
                canvas.drawRect(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat(), overlay)
            }
        }

        private fun drawBackdrop(
            canvas: Canvas,
            framing: Framing,
            virtualWidth: Int,
            screenHeight: Int,
            alpha: Int,
            margin: Float,
            preparedBackdrop: Bitmap?,
        ) {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
            when (framing.backdrop) {
                BackdropType.NONE -> Unit
                BackdropType.COLOR -> {
                    val fill =
                        Paint().apply {
                            color = framing.backdropColor
                            this.alpha = alpha
                        }
                    canvas.drawRect(
                        -margin,
                        -margin,
                        virtualWidth.toFloat() + margin,
                        screenHeight.toFloat() + margin,
                        fill,
                    )
                }

                BackdropType.BLUR -> {
                    val blurred = requireNotNull(preparedBackdrop) { "Blur must be prepared before drawing" }
                    val coverScale =
                        max(
                            virtualWidth.toFloat() / blurred.width,
                            screenHeight.toFloat() / blurred.height,
                        ) *
                            framing.backdropZoom.coerceIn(MIN_BACKDROP_ZOOM, MAX_BACKDROP_ZOOM) *
                            OVERSCAN
                    val drawWidth = blurred.width * coverScale
                    val drawHeight = blurred.height * coverScale
                    val centerX = virtualWidth / 2f + framing.backdropPanX * virtualWidth
                    val centerY = screenHeight / 2f + framing.backdropPanY * screenHeight
                    canvas.drawBitmap(
                        blurred,
                        null,
                        RectF(
                            centerX - drawWidth / 2f,
                            centerY - drawHeight / 2f,
                            centerX + drawWidth / 2f,
                            centerY + drawHeight / 2f,
                        ),
                        paint,
                    )
                }
            }
        }

        private fun blurredBackdrop(
            source: Bitmap,
            intensity: Int,
        ): Bitmap {
            val clamped = intensity.coerceIn(0, 100)
            val key = BlurKey(source, clamped)
            blurCache.get(key)?.let { return it }
            val width = (source.width / BLUR_DOWNSCALE).coerceAtLeast(1)
            val height = (source.height / BLUR_DOWNSCALE).coerceAtLeast(1)
            val reservation = memory?.reserve(width.toLong() * height * 16)
            var small: Bitmap? = null
            try {
                small = Bitmap.createScaledBitmap(source, width, height, true)
                val radius = (clamped / 100f * max(width, height) / 10f).toInt().coerceAtLeast(1)
                val passes = if (clamped >= BLUR_EXTRA_PASS_THRESHOLD) 3 else 2
                val blurred = BoxBlur.blur(small, radius, passes)
                memory?.track(blurred)
                blurCache.put(key, blurred)
                return blurred
            } finally {
                if (small !== source) small?.recycle()
                reservation?.close()
            }
        }

        private fun createBitmap(
            width: Int,
            height: Int,
        ): Bitmap =
            memory?.create(width, height)
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        private companion object {
            const val BLUR_DOWNSCALE = 8
            const val OVERSCAN = 1.2f
            const val BLUR_EXTRA_PASS_THRESHOLD = 60
            const val MIN_BACKDROP_ZOOM = 0.1f
            const val MAX_BACKDROP_ZOOM = 4f
            const val BLUR_CACHE_ENTRIES = 2
            const val DIM_ALPHA = 90
            val DIM_COLOR = Color.argb(90, 0, 0, 0)
        }
    }
