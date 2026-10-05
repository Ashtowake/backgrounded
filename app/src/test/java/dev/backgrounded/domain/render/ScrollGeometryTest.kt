package dev.backgrounded.domain.render

import dev.backgrounded.domain.model.Framing
import dev.backgrounded.domain.model.ScrollMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollGeometryTest {
    @Test
    fun `off keeps the frame fixed`() {
        val scroll = ScrollGeometry.scrollFor(Framing.DEFAULT, screenWidth = 1000)
        assertEquals(0, scroll.slackPixels)
        assertEquals(0, ScrollGeometry.translationPixels(scroll, 0f))
        assertEquals(0, ScrollGeometry.translationPixels(scroll, 1f))
    }

    @Test
    fun `amount maps a percentage of the screen width`() {
        val framing = Framing.DEFAULT.copy(scrollMode = ScrollMode.AMOUNT, scrollAmountPercent = 50)
        val scroll = ScrollGeometry.scrollFor(framing, screenWidth = 1000)
        assertEquals(500, scroll.slackPixels)
        assertEquals(0, ScrollGeometry.translationPixels(scroll, 0f))
        assertEquals(250, ScrollGeometry.translationPixels(scroll, 0.5f))
        assertEquals(500, ScrollGeometry.translationPixels(scroll, 1f))
    }

    @Test
    fun `pages spread the image across the home panels`() {
        val framing = Framing.DEFAULT.copy(scrollMode = ScrollMode.PAGES, scrollPages = 3)
        val scroll = ScrollGeometry.scrollFor(framing, screenWidth = 1000)
        assertEquals(2000, scroll.slackPixels)
        assertEquals(1000, ScrollGeometry.translationPixels(scroll, 0.5f))
        assertEquals(2000, ScrollGeometry.translationPixels(scroll, 1f))
    }

    @Test
    fun `custom start and span window the scroll range`() {
        val framing =
            Framing.DEFAULT.copy(
                scrollMode = ScrollMode.CUSTOM,
                scrollAmountPercent = 100,
                scrollStartFraction = 0.5f,
                scrollSpanFraction = 0.25f,
            )
        val scroll = ScrollGeometry.scrollFor(framing, screenWidth = 1000)
        assertEquals(1000, scroll.slackPixels)
        assertEquals(500, ScrollGeometry.translationPixels(scroll, 0f))
        assertEquals(750, ScrollGeometry.translationPixels(scroll, 1f))
    }

    @Test
    fun `custom span is clamped to the remaining range`() {
        val framing =
            Framing.DEFAULT.copy(
                scrollMode = ScrollMode.CUSTOM,
                scrollAmountPercent = 100,
                scrollStartFraction = 0.8f,
                scrollSpanFraction = 1f,
            )
        val scroll = ScrollGeometry.scrollFor(framing, screenWidth = 1000)
        assertEquals(0.8f, scroll.startFraction, 0.0001f)
        assertEquals(0.2f, scroll.spanFraction, 0.0001f)
        assertEquals(1000, ScrollGeometry.translationPixels(scroll, 1f))
    }

    @Test
    fun `lock screens ignore the scroll window entirely`() {
        val framing =
            Framing.DEFAULT.copy(
                scrollMode = ScrollMode.CUSTOM,
                scrollAmountPercent = 100,
                scrollStartFraction = 0.25f,
                scrollSpanFraction = 1f,
            )
        val scroll = ScrollGeometry.scrollFor(framing, screenWidth = 1000)
        assertEquals(0, ScrollGeometry.translationPixels(scroll, 0f, allowScroll = false))
        assertEquals(0, ScrollGeometry.translationPixels(scroll, 1f, allowScroll = false))
    }

    @Test
    fun `amount is clamped to the maximum`() {
        val framing = Framing.DEFAULT.copy(scrollMode = ScrollMode.AMOUNT, scrollAmountPercent = 500)
        val scroll = ScrollGeometry.scrollFor(framing, screenWidth = 1000)
        assertEquals(2000, scroll.slackPixels)
    }

    @Test
    fun `wide image reaches both edges of the scroll band`() {
        val scroll = ScrollGeometry.Scroll(1000, 0f, 1f)
        val first = ScrollGeometry.edgeShiftPixels(-500f, 2500f, 2000, scroll, 0)
        val last = ScrollGeometry.edgeShiftPixels(-500f, 2500f, 2000, scroll, 1000)
        assertEquals(0f, -500f + first, 0.001f)
        assertEquals(2000f, 2500f + last, 0.001f)
    }
}
