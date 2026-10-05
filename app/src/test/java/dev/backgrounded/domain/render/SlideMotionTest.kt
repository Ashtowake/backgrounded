package dev.backgrounded.domain.render

import dev.backgrounded.domain.model.SlideMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.hypot

class SlideMotionTest {
    @Test
    fun `horizontal motion travels at the selected pixels per second`() {
        val start = position(SlideMode.LEFT_TO_RIGHT, 0)
        val next = position(SlideMode.LEFT_TO_RIGHT, 1_000)
        assertEquals(10f, next.offsetX - start.offsetX, 0.001f)
        assertEquals(start.offsetY, next.offsetY, 0f)
        val faster = SlideMotion.position(SlideMode.LEFT_TO_RIGHT, 20f, 1_000, 1_000, 2_000)!!
        assertEquals(20f, faster.offsetX - start.offsetX, 0.001f)
    }

    @Test
    fun `right to left reverses horizontal direction`() {
        val start = position(SlideMode.RIGHT_TO_LEFT, 0)
        val next = position(SlideMode.RIGHT_TO_LEFT, 1_000)
        assertEquals(-10f, next.offsetX - start.offsetX, 0.001f)
    }

    @Test
    fun `diagonal motion uses path speed rather than speed on each axis`() {
        val start = position(SlideMode.DIAGONAL_UP_RIGHT, 0)
        val next = position(SlideMode.DIAGONAL_UP_RIGHT, 1_000)
        assertEquals(10f, hypot(next.offsetX - start.offsetX, next.offsetY - start.offsetY), 0.001f)
    }

    @Test
    fun `motion reverses at its limit and returns without a jump`() {
        val before = position(SlideMode.LEFT_TO_RIGHT, 19_999)
        val after = position(SlideMode.LEFT_TO_RIGHT, 20_001)
        assertEquals(before.offsetX, after.offsetX, 0.001f)
        assertEquals(position(SlideMode.LEFT_TO_RIGHT, 0), position(SlideMode.LEFT_TO_RIGHT, 40_000))
    }

    @Test
    fun `off produces no motion`() {
        assertNull(SlideMotion.position(SlideMode.OFF, 10f, 1_000, 1_000, 2_000))
    }

    private fun position(
        mode: SlideMode,
        millis: Long,
    ): SlideMotion.Position = SlideMotion.position(mode, 10f, millis, 1_000, 2_000)!!
}
