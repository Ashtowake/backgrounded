package dev.backgrounded.domain.unlock

import dev.backgrounded.domain.model.UnlockPolicy
import dev.backgrounded.domain.model.UnlockState

object UnlockPolicyEvaluator {
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
        val reachedUnlockCount = policy.everyN <= 1 || state.unlocksSinceApply + 1 >= policy.everyN
        return withinMinInterval && withinDailyLimit && reachedUnlockCount
    }

    fun stateAfterUnlock(
        state: UnlockState,
        epochDay: Long,
    ): UnlockState =
        state.copy(
            unlocksSinceApply = if (state.dayEpochDay == epochDay) state.unlocksSinceApply + 1 else 1,
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
            appliedToday = appliedToday(state, epochDay) + 1,
            dayEpochDay = epochDay,
        )

    private fun appliedToday(
        state: UnlockState,
        epochDay: Long,
    ): Int = if (state.dayEpochDay == epochDay) state.appliedToday else 0

    private const val MILLIS_PER_MINUTE = 60_000L
}
