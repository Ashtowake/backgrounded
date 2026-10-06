package dev.backgrounded.core.image

import android.app.ActivityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageMemoryBudgetTest {
    @Test fun `tracks live bitmaps and reservations without recycling consumers`() {
        val context = RuntimeEnvironment.getApplication()
        val budget = ImageMemoryBudget(context)
        val expected =
            minOf(
                192L * 1024 * 1024,
                context.getSystemService(ActivityManager::class.java).memoryClass * 1024L * 1024 / 2,
            )
        assertEquals(expected, budget.limitBytes)
        val bitmap = budget.create(100, 100)
        val reservation = budget.reserve(1000)
        assertEquals(expected - bitmap.allocationByteCount - 1000, budget.remaining())
        reservation.close()
        reservation.close()
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
        assertEquals(expected, budget.remaining())
    }

    @Test fun `overflow dimensions are rejected before native allocation`() {
        val budget = ImageMemoryBudget(RuntimeEnvironment.getApplication())
        assertTrue(
            runCatching { budget.create(Int.MAX_VALUE, Int.MAX_VALUE) }.exceptionOrNull() is ImageResourceException,
        )
        assertEquals(budget.limitBytes, budget.remaining())
    }
}
