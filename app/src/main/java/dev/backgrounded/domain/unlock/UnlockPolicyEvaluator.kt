package dev.backgrounded.domain.unlock

import dev.backgrounded.domain.model.UnlockPolicy
import dev.backgrounded.domain.model.UnlockState

object UnlockPolicyEvaluator {
    data class Decision(val state: UnlockState, val pending: Boolean)

    /** A timer that completed after this unlock can satisfy the same eligible automatic change. */
    fun onUnlock(
        policy: UnlockPolicy,
        state: UnlockState,
        unlockedAt: Long,
        epochDay: Long,
        automaticChangedAt: Long? = null,
    ): Decision {
        if (!policy.enabled) return Decision(state, false)
        val combined = automaticChangedAt != null && automaticChangedAt >= unlockedAt
        if (combined && state.lastAppliedAt == automaticChangedAt) return Decision(state, false)
        val eligible = shouldChange(policy, state, unlockedAt, epochDay)
        val counted = stateAfterUnlock(state, epochDay)
        return if (eligible && combined) {
            Decision(stateAfterApply(counted, requireNotNull(automaticChangedAt), epochDay), false)
        } else {
            Decision(counted, eligible)
        }
    }

    /**
     * Decides whether the unlock that just happened should change the wallpaper.
     * [state] must describe the state before this unlock was counted.
     */
    fun shouldChange(
        policy: UnlockPolicy,
        state: UnlockState,
        nowMillis: Long,
        epochDay: Long,
    ): Boolean {
        if (!policy.enabled) return false
        val withinMinInterval =
            policy.minMinutes <= 0 ||
                state.lastAppliedAt <= 0L ||
                nowMillis - state.lastAppliedAt >= policy.minMinutes * MILLIS_PER_MINUTE
        val withinDailyLimit = policy.maxPerDay <= 0 || appliedToday(state, epochDay) < policy.maxPerDay
        val count = if (state.dayEpochDay == epochDay) state.unlocksSinceApply else 0
        val reachedUnlockCount = policy.everyN <= 1 || increment(count) >= policy.everyN
        return withinMinInterval && withinDailyLimit && reachedUnlockCount
    }

    fun stateAfterUnlock(
        state: UnlockState,
        epochDay: Long,
    ): UnlockState =
        state.copy(
            unlocksSinceApply = if (state.dayEpochDay == epochDay) increment(state.unlocksSinceApply) else 1,
            appliedToday = appliedToday(state, epochDay),
            dayEpochDay = epochDay,
        )

    fun stateAfterApply(
        state: UnlockState,
        nowMillis: Long,
        epochDay: Long,
    ): UnlockState =
        state.copy(
            lastAppliedAt = nowMillis,
            unlocksSinceApply = 0,
            appliedToday = increment(appliedToday(state, epochDay)),
            dayEpochDay = epochDay,
        )

    private fun appliedToday(
        state: UnlockState,
        epochDay: Long,
    ): Int = if (state.dayEpochDay == epochDay) state.appliedToday else 0

    private fun increment(value: Int): Int = if (value >= Int.MAX_VALUE) Int.MAX_VALUE else value.coerceAtLeast(0) + 1

    private const val MILLIS_PER_MINUTE = 60_000L
}
