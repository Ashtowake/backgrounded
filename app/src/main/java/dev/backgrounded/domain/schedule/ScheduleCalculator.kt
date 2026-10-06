package dev.backgrounded.domain.schedule

import dev.backgrounded.domain.model.ScheduleType
import java.time.LocalTime
import java.time.ZonedDateTime

object ScheduleCalculator {
    fun nextTrigger(
        now: ZonedDateTime,
        type: ScheduleType,
        intervalMinutes: Int?,
        fixedTimes: List<LocalTime>,
        lastChangedAtMillis: Long,
        intervalSeconds: Int? = null,
    ): ZonedDateTime? =
        when (type) {
            ScheduleType.NONE -> null
            ScheduleType.INTERVAL ->
                nextInterval(
                    now,
                    intervalSeconds
                        ?: intervalMinutes?.toLong()?.times(60)
                            ?.coerceIn(1L, MAX_INTERVAL_SECONDS.toLong())?.toInt(),
                    lastChangedAtMillis,
                )
            ScheduleType.FIXED_TIMES -> nextFixedTime(now, fixedTimes)
        }

    private fun nextInterval(
        now: ZonedDateTime,
        intervalSeconds: Int?,
        lastChangedAtMillis: Long,
    ): ZonedDateTime? {
        val seconds = (intervalSeconds ?: 0).coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS).toLong()
        val intervalMillis = seconds * 1000L
        val nowMillis = now.toInstant().toEpochMilli()
        if (lastChangedAtMillis <= 0L || lastChangedAtMillis > nowMillis) return now.plusSeconds(seconds)
        val elapsed = nowMillis - lastChangedAtMillis
        val remaining = intervalMillis - elapsed % intervalMillis
        val candidate = now.plusNanos(remaining * 1_000_000L)
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

    const val MAX_INTERVAL_SECONDS = 359_999
    const val MIN_INTERVAL_MINUTES = 1
    const val MIN_INTERVAL_SECONDS = 1
}
