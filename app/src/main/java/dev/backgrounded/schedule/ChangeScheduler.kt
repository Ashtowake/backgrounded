package dev.backgrounded.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.schedule.ScheduleCalculator
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChangeScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val alarmManager: AlarmManager,
        private val settingsStore: SettingsStore,
        private val albumRepository: AlbumRepository,
    ) {
        suspend fun rearm() {
            cancel()
            val settings = settingsStore.settings.first()
            if (settings.rotationPaused) {
                settingsStore.setNextTriggerAt(0L)
                return
            }
            val albumId = settings.activeAlbumId ?: return
            val album = albumRepository.getAlbum(albumId) ?: return
            val next =
                ScheduleCalculator.nextTrigger(
                    now = ZonedDateTime.now(),
                    type = album.scheduleType,
                    intervalMinutes = album.intervalMinutes,
                    fixedTimes = album.fixedTimes,
                    lastChangedAtMillis = album.lastChangedAt,
                ) ?: run {
                    settingsStore.setNextTriggerAt(0L)
                    return
                }
            val triggerAt = next.toInstant().toEpochMilli()
            settingsStore.setNextTriggerAt(triggerAt)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent())
        }

        fun cancel() {
            alarmManager.cancel(pendingIntent())
        }

        private fun pendingIntent(): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, ChangeAlarmReceiver::class.java).setAction(ChangeAlarmReceiver.ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private companion object {
            const val REQUEST_CODE = 100
        }
    }
