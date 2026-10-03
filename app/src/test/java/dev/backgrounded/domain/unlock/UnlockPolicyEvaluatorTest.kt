package dev.backgrounded.domain.unlock

import dev.backgrounded.domain.model.UnlockPolicy
import dev.backgrounded.domain.model.UnlockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockPolicyEvaluatorTest {
    private val policy = UnlockPolicy(enabled = true, minMinutes = 5, everyN = 1, maxPerDay = 20)
    private val fresh = UnlockState(lastAppliedAt = 0L, unlocksSinceApply = 0, appliedToday = 0, dayEpochDay = 100L)

    @Test
    fun `disabled policy never changes`() {
        assertFalse(
            UnlockPolicyEvaluator.shouldChange(policy.copy(enabled = false), fresh, 1_000_000L, 100L),
        )
    }

    @Test
    fun `fresh state changes immediately`() {
        assertTrue(UnlockPolicyEvaluator.shouldChange(policy, fresh, 1_000_000L, 100L))
    }

    @Test
    fun `minimum minutes block early unlocks`() {
        val recent = fresh.copy(lastAppliedAt = 1_000_000L)
        assertFalse(UnlockPolicyEvaluator.shouldChange(policy, recent, 1_000_000L + 60_000L, 100L))
        assertTrue(UnlockPolicyEvaluator.shouldChange(policy, recent, 1_000_000L + 6 * 60_000L, 100L))
    }

    @Test
    fun `every N unlocks waits for the Nth`() {
        val everyThree = policy.copy(everyN = 3)
        assertFalse(
            UnlockPolicyEvaluator.shouldChange(
                everyThree,
                fresh.copy(unlocksSinceApply = 0),
                1_000_000L,
                100L,
            ),
        )
        assertFalse(
            UnlockPolicyEvaluator.shouldChange(
                everyThree,
                fresh.copy(unlocksSinceApply = 1),
                1_000_000L,
                100L,
            ),
        )
        assertTrue(
            UnlockPolicyEvaluator.shouldChange(
                everyThree,
                fresh.copy(unlocksSinceApply = 2),
                1_000_000L,
                100L,
            ),
        )
    }

    @Test
    fun `max per day blocks after the limit and resets on a new day`() {
        val limited = policy.copy(maxPerDay = 2)
        val atLimit = fresh.copy(appliedToday = 2, dayEpochDay = 100L)
        assertFalse(UnlockPolicyEvaluator.shouldChange(limited, atLimit, 1_000_000L, 100L))
        assertTrue(UnlockPolicyEvaluator.shouldChange(limited, atLimit, 1_000_000L, 101L))
    }

    @Test
    fun `state transitions count unlocks and reset after apply`() {
        val afterUnlock = UnlockPolicyEvaluator.stateAfterUnlock(fresh, 100L)
        assertEquals(1, afterUnlock.unlocksSinceApply)
        val afterApply = UnlockPolicyEvaluator.stateAfterApply(afterUnlock, 2_000_000L, 100L)
        assertEquals(0, afterApply.unlocksSinceApply)
        assertEquals(1, afterApply.appliedToday)
        assertEquals(2_000_000L, afterApply.lastAppliedAt)
    }
}
