package dev.backgrounded.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.ScheduleType
import dev.backgrounded.domain.schedule.ScheduleCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Only visible live engines own timers. No timer here can wake a sleeping device. */
@Singleton
class ChangeScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val alarmManager: AlarmManager,
        private val settingsStore: SettingsStore,
        private val albumRepository: AlbumRepository,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val visibleEngines = ConcurrentHashMap.newKeySet<Any>()
        private val mutex = Mutex()
        private var timer: Job? = null
        private var fingerprint: String? = null
        private var monotonicAt = 0L
        private var armedWallAt = 0L
        private var backoffSeconds = 60L
        private var blocked = false
        private val mutableDue =
            MutableSharedFlow<Long>(
                extraBufferCapacity = 1,
                onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
            )
        val due = mutableDue.asSharedFlow()

        fun visible(
            engine: Any,
            visible: Boolean,
        ) {
            val changed = if (visible) visibleEngines.add(engine) else visibleEngines.remove(engine)
            if (!changed) return
            if (visibleEngines.isEmpty()) {
                timer?.cancel()
                mutableDue.tryEmit(Long.MIN_VALUE)
            }
            scope.launch { rearm() }
        }

        fun hasVisibleEngine(): Boolean = visibleEngines.isNotEmpty()

        suspend fun authenticated() {
            mutex.withLock { blocked = false }
            rearm()
        }

        suspend fun failed(locked: Boolean = false) =
            mutex.withLock {
                timer?.cancel()
                blocked = locked
                if (!locked && hasVisibleEngine()) {
                    val wait = backoffSeconds
                    backoffSeconds = (backoffSeconds * 2).coerceAtMost(900)
                    timer =
                        scope.launch {
                            delay(wait * 1000)
                            if (hasVisibleEngine()) mutableDue.emit(settingsStore.settings.first().nextTriggerAt)
                        }
                }
            }

        @Suppress("CyclomaticComplexMethod", "ComplexCondition")
        suspend fun rearm(forceClock: Boolean = false) =
            mutex.withLock {
                cancelLegacyAlarm()
                timer?.cancel()
                val settings = settingsStore.settings.first()
                if (settings.rotationPaused) {
                    settingsStore.setNextTriggerAt(0)
                    return@withLock
                }
                if (!hasVisibleEngine()) return@withLock
                val albumId = settings.activeAlbumId ?: return@withLock
                val album = albumRepository.getAlbum(albumId) ?: return@withLock
                val signature =
                    "$albumId:${album.scheduleType}:${album.intervalSeconds}:${album.intervalMinutes}:" +
                        "${album.fixedTimes}:${album.lastChangedAt}"
                val changed = signature != fingerprint
                if (changed) {
                    blocked = false
                    backoffSeconds = 60
                }
                if (blocked) return@withLock
                val now = System.currentTimeMillis()
                val intervalSeconds =
                    (album.intervalSeconds?.toLong() ?: album.intervalMinutes?.toLong()?.times(60) ?: 1L)
                        .coerceIn(1L, ScheduleCalculator.MAX_INTERVAL_SECONDS.toLong()).toInt()
                val saved =
                    settings.nextTriggerAt.takeIf {
                        it > album.lastChangedAt &&
                            it > 0 &&
                            (!changed || fingerprint == null)
                    }
                val elapsed = SystemClock.elapsedRealtime()
                val retainIntervalDeadline =
                    forceClock && !changed && monotonicAt != 0L &&
                        album.scheduleType == ScheduleType.INTERVAL && !settings.pendingUnlock
                val at =
                    when {
                        settings.pendingUnlock -> now
                        retainIntervalDeadline -> now + (monotonicAt - elapsed).coerceAtLeast(0)
                        album.scheduleType == ScheduleType.NONE -> null
                        saved != null && !forceClock -> saved
                        album.scheduleType == ScheduleType.INTERVAL ->
                            if (album.lastChangedAt in 1..now) {
                                album.lastChangedAt + intervalSeconds * 1000L
                            } else {
                                now + intervalSeconds * 1000L
                            }
                        else ->
                            ScheduleCalculator.nextTrigger(
                                ZonedDateTime.now(),
                                album.scheduleType,
                                album.intervalMinutes,
                                album.fixedTimes,
                                album.lastChangedAt,
                                album.intervalSeconds,
                            )
                                ?.toInstant()?.toEpochMilli()
                    }
                fingerprint = signature
                if (at == null) {
                    settingsStore.setNextTriggerAt(0)
                    return@withLock
                }
                if (settings.nextTriggerAt != at) settingsStore.setNextTriggerAt(at)
                if (!retainIntervalDeadline && (
                        changed || monotonicAt == 0L || at != armedWallAt ||
                            forceClock && album.scheduleType != ScheduleType.INTERVAL
                    )
                ) {
                    monotonicAt = elapsed + (at - now).coerceIn(0, intervalSeconds * 1000L)
                }
                armedWallAt = at
                val wait =
                    if (album.scheduleType == ScheduleType.INTERVAL && !settings.pendingUnlock) {
                        (monotonicAt - elapsed).coerceAtLeast(0)
                    } else {
                        (at - now).coerceAtLeast(0)
                    }
                timer =
                    scope.launch {
                        delay(wait)
                        if (hasVisibleEngine()) mutableDue.emit(at)
                    }
            }

        fun cancel() {
            timer?.cancel()
            cancelLegacyAlarm()
        }

        private fun cancelLegacyAlarm() {
            val pending =
                PendingIntent.getBroadcast(
                    context,
                    100,
                    Intent(context, ChangeAlarmReceiver::class.java).setAction(ChangeAlarmReceiver.ACTION),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
            pending?.let(alarmManager::cancel)
        }
    }
