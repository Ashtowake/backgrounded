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

    @Test fun `unlock queued behind a timer consumes one change without another pending rotation`() {
        val decision = UnlockPolicyEvaluator.onUnlock(policy, fresh, 1000, 100, automaticChangedAt = 1200)
        assertFalse(decision.pending)
        assertEquals(1200L, decision.state.lastAppliedAt)
        assertEquals(1, decision.state.appliedToday)
        assertEquals(0, decision.state.unlocksSinceApply)
    }

    @Test fun `combined automatic change does not count the same application twice`() {
        val applied = fresh.copy(lastAppliedAt = 1200, appliedToday = 1)
        val decision = UnlockPolicyEvaluator.onUnlock(policy.copy(minMinutes = 0), applied, 1000, 100, 1200)
        assertEquals(applied, decision.state)
        assertFalse(decision.pending)
    }

    @Test fun `a timer before an unlock does not consume the new event`() {
        val decision = UnlockPolicyEvaluator.onUnlock(policy, fresh, 1200, 100, automaticChangedAt = 1000)
        assertTrue(decision.pending)
        assertEquals(1, decision.state.unlocksSinceApply)
    }

    @Test fun `ineligible unlock is counted without treating a timer as an unlock application`() {
        val decision = UnlockPolicyEvaluator.onUnlock(policy.copy(everyN = 2), fresh, 1000, 100, 1200)
        assertFalse(decision.pending)
        assertEquals(1, decision.state.unlocksSinceApply)
        assertEquals(0, decision.state.appliedToday)
    }

    @Test fun `unlock counters saturate`() {
        val state = fresh.copy(unlocksSinceApply = Int.MAX_VALUE, appliedToday = Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, UnlockPolicyEvaluator.stateAfterUnlock(state, 100).unlocksSinceApply)
        assertEquals(Int.MAX_VALUE, UnlockPolicyEvaluator.stateAfterApply(state, 1000, 100).appliedToday)
    }

    @Test fun `previous day unlock counts are not replayed on the first unlock of a new day`() {
        val previous = fresh.copy(unlocksSinceApply = Int.MAX_VALUE)
        val decision = UnlockPolicyEvaluator.onUnlock(policy.copy(everyN = 3), previous, 1000, 101)
        assertFalse(decision.pending)
        assertEquals(1, decision.state.unlocksSinceApply)
    }

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
