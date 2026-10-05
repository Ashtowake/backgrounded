package dev.backgrounded.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.backgrounded.core.di.ApplicationScope
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.usecase.ApplyNextBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ChangeAlarmReceiver : BroadcastReceiver() {
    @Inject
    lateinit var applyNextBackground: ApplyNextBackground

    @Inject
    lateinit var changeScheduler: ChangeScheduler

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                applyNextBackground(Trigger.TIMER, expectedScheduleAt = intent.getLongExtra(EXTRA_EXPECTED_AT, 0L))
            } finally {
                changeScheduler.rearm()
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION = "dev.backgrounded.action.CHANGE_ALARM"
        const val EXTRA_EXPECTED_AT = "expected_schedule_at"
    }
}
