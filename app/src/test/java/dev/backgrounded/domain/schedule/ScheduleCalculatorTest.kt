package dev.backgrounded.domain.schedule

import dev.backgrounded.domain.model.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleCalculatorTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun `no schedule yields null`() {
        assertNull(
            ScheduleCalculator.nextTrigger(
                now = ZonedDateTime.of(2026, 3, 1, 12, 0, 0, 0, zone),
                type = ScheduleType.NONE,
                intervalMinutes = null,
                fixedTimes = emptyList(),
                lastChangedAtMillis = 0L,
            ),
        )
    }

    @Test
    fun `interval is anchored to the last change and moves past now`() {
        val now = ZonedDateTime.of(2026, 3, 1, 12, 0, 0, 0, zone)
        val lastChanged = now.minusMinutes(90).toInstant().toEpochMilli()
        val next =
            ScheduleCalculator.nextTrigger(
                now = now,
                type = ScheduleType.INTERVAL,
                intervalMinutes = 60,
                fixedTimes = emptyList(),
                lastChangedAtMillis = lastChanged,
            )
        assertNotNull(next)
        assertEquals(now.minusMinutes(90).plusMinutes(120).toInstant(), next!!.toInstant())
    }

    @Test
    fun `interval without history starts one interval from now`() {
        val now = ZonedDateTime.of(2026, 3, 1, 12, 0, 0, 0, zone)
        val next =
            ScheduleCalculator.nextTrigger(
                now = now,
                type = ScheduleType.INTERVAL,
                intervalMinutes = 15,
                fixedTimes = emptyList(),
                lastChangedAtMillis = 0L,
            )
        assertEquals(now.plusMinutes(15).toInstant(), next!!.toInstant())
    }

    @Test
    fun `fixed times pick the next occurrence today`() {
        val now = ZonedDateTime.of(2026, 3, 1, 12, 0, 0, 0, zone)
        val next =
            ScheduleCalculator.nextTrigger(
                now = now,
                type = ScheduleType.FIXED_TIMES,
                intervalMinutes = null,
                fixedTimes = listOf(LocalTime.of(8, 0), LocalTime.of(18, 30)),
                lastChangedAtMillis = 0L,
            )
        assertEquals(
            ZonedDateTime.of(2026, 3, 1, 18, 30, 0, 0, zone).toInstant(),
            next!!.toInstant(),
        )
    }

    @Test
    fun `fixed times roll over to tomorrow`() {
        val now = ZonedDateTime.of(2026, 3, 1, 20, 0, 0, 0, zone)
        val next =
            ScheduleCalculator.nextTrigger(
                now = now,
                type = ScheduleType.FIXED_TIMES,
                intervalMinutes = null,
                fixedTimes = listOf(LocalTime.of(8, 0), LocalTime.of(18, 30)),
                lastChangedAtMillis = 0L,
            )
        assertEquals(
            ZonedDateTime.of(2026, 3, 2, 8, 0, 0, 0, zone).toInstant(),
            next!!.toInstant(),
        )
    }

    @Test
    fun `fixed times handle the spring DST gap without failing`() {
        val now = ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, zone)
        val next =
            ScheduleCalculator.nextTrigger(
                now = now,
                type = ScheduleType.FIXED_TIMES,
                intervalMinutes = null,
                fixedTimes = listOf(LocalTime.of(2, 30)),
                lastChangedAtMillis = 0L,
            )
        assertNotNull(next)
        assertTrue(next!!.toInstant().isAfter(Instant.from(now)))
    }
}
