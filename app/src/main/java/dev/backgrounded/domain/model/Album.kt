package dev.backgrounded.domain.model

import java.time.LocalTime

data class Album(
    val id: Long,
    val name: String,
    val coverPairId: Long?,
    val fixedHomeAssetId: Long?,
    val fixedLockAssetId: Long?,
    val isHidden: Boolean,
    val rotationOrder: RotationOrder,
    val scheduleType: ScheduleType,
    val intervalMinutes: Int?,
    val fixedTimes: List<LocalTime>,
    val unlockPolicy: UnlockPolicy,
    val lastAppliedPairId: Long?,
    val lastChangedAt: Long,
    val shuffleRemaining: List<Long>,
    val sortIndex: Int,
)

data class UnlockPolicy(
    val enabled: Boolean,
    val minMinutes: Int,
    val everyN: Int,
    val maxPerDay: Int,
)

data class UnlockState(
    val lastAppliedAt: Long,
    val unlocksSinceApply: Int,
    val appliedToday: Int,
    val dayEpochDay: Long,
)
