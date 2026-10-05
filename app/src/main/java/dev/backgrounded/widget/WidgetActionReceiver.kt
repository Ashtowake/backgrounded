package dev.backgrounded.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dagger.hilt.android.AndroidEntryPoint
import dev.backgrounded.MainActivity
import dev.backgrounded.core.di.ApplicationScope
import dev.backgrounded.core.security.HiddenSwitchAuthActivity
import dev.backgrounded.core.security.HiddenSwitchOperation
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.domain.model.GestureAction
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.usecase.ApplyNextBackground
import dev.backgrounded.domain.usecase.ApplyPreviousBackground
import dev.backgrounded.domain.usecase.HiddenAlbumSwitchPolicy
import dev.backgrounded.domain.usecase.NextAlbum
import dev.backgrounded.domain.usecase.NextAlbumResult
import dev.backgrounded.domain.usecase.TogglePause
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WidgetActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var widgetConfigStore: WidgetConfigStore

    @Inject
    lateinit var applyNextBackground: ApplyNextBackground

    @Inject
    lateinit var applyPreviousBackground: ApplyPreviousBackground

    @Inject
    lateinit var nextAlbum: NextAlbum

    @Inject
    lateinit var hiddenSwitchPolicy: HiddenAlbumSwitchPolicy

    @Inject
    lateinit var togglePause: TogglePause

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_TAP) return
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val tapKey = "$KEY_LAST_TAP.$widgetId"
        val now = SystemClock.elapsedRealtime()
        val lastTap = preferences.getLong(tapKey, 0L)
        if (lastTap != 0L && now >= lastTap && now - lastTap <= DOUBLE_TAP_WINDOW_MILLIS) {
            preferences.edit().putLong(tapKey, 0L).apply()
            val pendingResult = goAsync()
            applicationScope.launch {
                try {
                    val config = widgetConfigStore.get(widgetId, settingsStore.settings.first())
                    dispatch(context, config.doubleTapAction, config.pinnedAlbumId)
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }
        preferences.edit().putLong(tapKey, now).apply()
        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                delay(DOUBLE_TAP_WINDOW_MILLIS)
                if (preferences.getLong(tapKey, 0L) == now) {
                    preferences.edit().putLong(tapKey, 0L).apply()
                    val config = widgetConfigStore.get(widgetId, settingsStore.settings.first())
                    dispatch(context, config.tapAction, config.pinnedAlbumId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun dispatch(
        context: Context,
        action: GestureAction,
        pinnedAlbumId: Long?,
    ) {
        val settings = settingsStore.settings.first()
        when (action) {
            GestureAction.NEXT -> {
                if (pinnedAlbumId != null && pinnedAlbumId != settings.activeAlbumId &&
                    hiddenSwitchPolicy.requiresAuthentication(pinnedAlbumId)
                ) {
                    context.startActivity(
                        HiddenSwitchAuthActivity.intent(
                            context,
                            Trigger.WIDGET,
                            pinnedAlbumId,
                            HiddenSwitchOperation.APPLY_NEXT,
                        ),
                    )
                } else {
                    applyNextBackground(Trigger.WIDGET, albumIdOverride = pinnedAlbumId)
                }
            }
            GestureAction.PREVIOUS -> {
                if (pinnedAlbumId != null && pinnedAlbumId != settings.activeAlbumId &&
                    hiddenSwitchPolicy.requiresAuthentication(pinnedAlbumId)
                ) {
                    context.startActivity(
                        HiddenSwitchAuthActivity.intent(
                            context,
                            Trigger.WIDGET,
                            pinnedAlbumId,
                            HiddenSwitchOperation.APPLY_PREVIOUS,
                        ),
                    )
                } else {
                    if (pinnedAlbumId != null) settingsStore.setActiveAlbum(pinnedAlbumId)
                    applyPreviousBackground(Trigger.WIDGET)
                }
            }

            GestureAction.NEXT_ALBUM ->
                if (pinnedAlbumId != null) {
                    if (pinnedAlbumId != settings.activeAlbumId &&
                        hiddenSwitchPolicy.requiresAuthentication(pinnedAlbumId)
                    ) {
                        context.startActivity(
                            HiddenSwitchAuthActivity.intent(
                                context,
                                Trigger.WIDGET,
                                pinnedAlbumId,
                                HiddenSwitchOperation.APPLY_NEXT,
                            ),
                        )
                    } else {
                        settingsStore.setActiveAlbum(pinnedAlbumId)
                        applyNextBackground(Trigger.WIDGET, albumIdOverride = pinnedAlbumId)
                    }
                } else {
                    if (nextAlbum(Trigger.WIDGET) == NextAlbumResult.AUTH_REQUIRED) {
                        context.startActivity(HiddenSwitchAuthActivity.intent(context, Trigger.WIDGET))
                    }
                }

            GestureAction.TOGGLE_PAUSE -> togglePause()
            GestureAction.OPEN_APP ->
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
        }
    }

    companion object {
        const val ACTION_TAP = "dev.backgrounded.action.WIDGET_TAP"
        const val DOUBLE_TAP_WINDOW_MILLIS = 550L
        private const val PREFERENCES = "widget_actions"
        private const val KEY_LAST_TAP = "last_tap"
    }
}
