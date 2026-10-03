package dev.backgrounded.domain.render

import dev.backgrounded.domain.model.Framing
import dev.backgrounded.domain.model.ScrollMode

object ScrollGeometry {
    data class Scroll(
        val slackPixels: Int,
        val startFraction: Float,
        val spanFraction: Float,
    )

    fun scrollFor(
        framing: Framing,
        screenWidth: Int,
    ): Scroll {
        val width = screenWidth.coerceAtLeast(1)
        return when (framing.scrollMode) {
            ScrollMode.OFF -> Scroll(0, 0f, 1f)
            ScrollMode.AMOUNT ->
                Scroll(percentToPixels(framing.scrollAmountPercent, width), 0f, 1f)

            ScrollMode.PAGES ->
                Scroll(width * (framing.scrollPages.coerceIn(1, MAX_PAGES) - 1), 0f, 1f)

            ScrollMode.CUSTOM -> {
                val slack = percentToPixels(framing.scrollAmountPercent, width)
                val start = framing.scrollStartFraction.coerceIn(0f, 1f)
                val maxSpan = (1f - start).coerceAtLeast(MIN_SPAN)
                val span = framing.scrollSpanFraction.coerceIn(MIN_SPAN, maxSpan)
                Scroll(slack, start, span)
            }
        }
    }

    fun translationPixels(
        scroll: Scroll,
        xOffset: Float,
        allowScroll: Boolean = true,
    ): Int {
        if (scroll.slackPixels <= 0 || !allowScroll) return 0
        val fraction = scroll.startFraction + xOffset.coerceIn(0f, 1f) * scroll.spanFraction
        return (fraction * scroll.slackPixels).toInt().coerceIn(0, scroll.slackPixels)
    }

    private fun percentToPixels(
        percent: Int,
        width: Int,
    ): Int = (width * percent.coerceIn(0, MAX_AMOUNT_PERCENT) / 100f).toInt()

    const val MAX_AMOUNT_PERCENT = 200
    const val MAX_PAGES = 5
    const val MIN_SPAN = 0.05f
}
