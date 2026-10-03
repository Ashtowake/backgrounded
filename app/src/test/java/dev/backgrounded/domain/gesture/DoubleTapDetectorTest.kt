package dev.backgrounded.domain.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapDetectorTest {
    @Test
    fun `two taps within the window are a double tap`() {
        val detector = DoubleTapDetector(windowMillis = 350L)
        assertFalse(detector.onTap(1_000L))
        assertTrue(detector.onTap(1_200L))
    }

    @Test
    fun `taps outside the window are separate single taps`() {
        val detector = DoubleTapDetector(windowMillis = 350L)
        assertFalse(detector.onTap(1_000L))
        assertFalse(detector.onTap(1_500L))
    }

    @Test
    fun `a double tap resets so a third tap starts over`() {
        val detector = DoubleTapDetector(windowMillis = 350L)
        detector.onTap(1_000L)
        assertTrue(detector.onTap(1_100L))
        assertFalse(detector.onTap(1_150L))
    }

    @Test
    fun `reset clears pending taps`() {
        val detector = DoubleTapDetector(windowMillis = 350L)
        detector.onTap(1_000L)
        detector.reset()
        assertFalse(detector.onTap(1_100L))
    }
}
