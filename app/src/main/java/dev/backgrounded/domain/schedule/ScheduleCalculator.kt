package dev.backgrounded.domain.schedule

import dev.backgrounded.domain.model.ScheduleType
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

object ScheduleCalculator {
    fun nextTrigger(
        now: ZonedDateTime,
        type: ScheduleType,
        intervalMinutes: Int?,
        fixedTimes: List<LocalTime>,
        lastChangedAtMillis: Long,
    ): ZonedDateTime? =
        when (type) {
            ScheduleType.NONE -> null
            ScheduleType.INTERVAL -> nextInterval(now, intervalMinutes, lastChangedAtMillis)
            ScheduleType.FIXED_TIMES -> nextFixedTime(now, fixedTimes)
        }

    private fun nextInterval(
        now: ZonedDateTime,
        intervalMinutes: Int?,
        lastChangedAtMillis: Long,
    ): ZonedDateTime? {
        val minutes = (intervalMinutes ?: 0).coerceAtLeast(MIN_INTERVAL_MINUTES).toLong()
        var candidate =
            if (lastChangedAtMillis > 0L) {
                Instant.ofEpochMilli(lastChangedAtMillis).atZone(now.zone).plusMinutes(minutes)
            } else {
                now.plusMinutes(minutes)
            }
        while (!candidate.isAfter(now)) {
            candidate = candidate.plusMinutes(minutes)
        }
        return candidate
    }

    private fun nextFixedTime(
        now: ZonedDateTime,
        fixedTimes: List<LocalTime>,
    ): ZonedDateTime? {
        if (fixedTimes.isEmpty()) return null
        val sorted = fixedTimes.sorted()
        val today = now.toLocalDate()
        sorted.forEach { time ->
            val candidate = today.atTime(time).atZone(now.zone)
            if (candidate.isAfter(now)) return candidate
        }
        return now.plusDays(1).toLocalDate().atTime(sorted.first()).atZone(now.zone)
    }

    const val MIN_INTERVAL_MINUTES = 1
}
