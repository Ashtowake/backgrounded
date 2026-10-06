package dev.backgrounded.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTimingTest {
    @Test
    fun `sixty fps catches consecutive display frames despite timestamp rounding`() {
        var previous = 1_000_000_000L
        repeat(120) {
            val next = previous + 16_666_666L
            val deadline = FrameTiming.deadline(previous + 5_000_000L, previous, 60, 0L)
            assertEquals(0L, FrameTiming.wakeDelayMillis(previous + 5_000_000L, deadline, 60f))
            assertTrue(FrameTiming.due(next, deadline))
            previous = next
        }
    }

    @Test
    fun `thirty fps on sixty hz skips one vsync`() {
        val previous = 1_000_000_000L
        val deadline = FrameTiming.deadline(previous, previous, 30, 0L)
        assertFalse(FrameTiming.due(previous + 16_666_666L, deadline))
        assertTrue(FrameTiming.due(previous + 33_333_332L, deadline))
    }

    @Test
    fun `slide deadlines exclude rendering time and preserve adaptive rate`() {
        val previous = 1_000_000_000L
        val deadline = FrameTiming.deadline(previous + 8_000_000L, previous, 60, 50_000_000L)
        assertEquals(previous + 50_000_000L, deadline)
        assertTrue(FrameTiming.due(previous + 49_999_998L, deadline))
    }

    @Test
    fun `sixty fps on one hundred twenty hz skips one vsync`() {
        val previous = 1_000_000_000L
        val deadline = FrameTiming.deadline(previous, previous, 60, 0L)
        assertFalse(FrameTiming.due(previous + 8_333_333L, deadline))
        assertTrue(FrameTiming.due(previous + 16_666_666L, deadline))
    }
}
