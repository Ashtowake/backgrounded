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
import dev.backgrounded.domain.rotation.AlbumSwitchMode
import dev.backgrounded.domain.rotation.RotationResult
import dev.backgrounded.domain.usecase.ApplyNextBackground
import dev.backgrounded.domain.usecase.ApplyPreviousBackground
import dev.backgrounded.domain.usecase.HiddenAlbumSwitchPolicy
import dev.backgrounded.domain.usecase.NextAlbum
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
        if (intent.action == ACTION_CONTROL) {
            receiveControl(context, intent)
            return
        }
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
            pendingResult.finish()
            applicationScope.launch {
                val config = widgetConfigStore.get(widgetId, settingsStore.settings.first())
                dispatch(context, config.doubleTapAction, config.pinnedAlbumId)
            }
            return
        }
        preferences.edit().putLong(tapKey, now).apply()
        val pendingResult = goAsync()
        pendingResult.finish()
        applicationScope.launch {
            delay(DOUBLE_TAP_WINDOW_MILLIS)
            if (preferences.getLong(tapKey, 0L) == now) {
                preferences.edit().putLong(tapKey, 0L).apply()
                val config = widgetConfigStore.get(widgetId, settingsStore.settings.first())
                dispatch(context, config.tapAction, config.pinnedAlbumId)
            }
        }
    }

    private fun receiveControl(
        context: Context,
        intent: Intent,
    ) {
        val actionName = intent.getStringExtra(EXTRA_CONTROL)
        val action = PlaybackAction.entries.firstOrNull { it.name == actionName } ?: return
        val pending = goAsync()
        pending.finish()
        applicationScope.launch {
            dispatchControl(context, action)
        }
    }

    private suspend fun dispatchControl(
        context: Context,
        action: PlaybackAction,
    ) {
        when (action) {
            PlaybackAction.PREVIOUS_IMAGE -> applyPreviousBackground(Trigger.WIDGET)
            PlaybackAction.NEXT_IMAGE -> applyNextBackground(Trigger.WIDGET)
            PlaybackAction.RANDOM_IMAGE -> applyNextBackground(Trigger.WIDGET, randomize = true)
            PlaybackAction.TOGGLE_PAUSE -> togglePause()
            PlaybackAction.PREVIOUS_ALBUM, PlaybackAction.NEXT_ALBUM, PlaybackAction.RANDOM_ALBUM -> {
                val mode =
                    when (action) {
                        PlaybackAction.PREVIOUS_ALBUM -> AlbumSwitchMode.PREVIOUS
                        PlaybackAction.RANDOM_ALBUM -> AlbumSwitchMode.RANDOM
                        else -> AlbumSwitchMode.NEXT
                    }
                val selection = nextAlbum(Trigger.WIDGET, mode = mode)
                if (selection is RotationResult.AuthenticationRequired) {
                    val operation =
                        when (mode) {
                            AlbumSwitchMode.PREVIOUS -> HiddenSwitchOperation.PREVIOUS_ALBUM
                            AlbumSwitchMode.RANDOM -> HiddenSwitchOperation.RANDOM_ALBUM
                            AlbumSwitchMode.NEXT -> HiddenSwitchOperation.NEXT_ALBUM
                        }
                    context.startActivity(
                        HiddenSwitchAuthActivity.intent(
                            context,
                            Trigger.WIDGET,
                            albumId = selection.albumId,
                            operation = operation,
                        ),
                    )
                }
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
                    applyPreviousBackground(Trigger.WIDGET, albumIdOverride = pinnedAlbumId)
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
                        applyNextBackground(Trigger.WIDGET, albumIdOverride = pinnedAlbumId)
                    }
                } else {
                    val selection = nextAlbum(Trigger.WIDGET)
                    if (selection is RotationResult.AuthenticationRequired) {
                        context.startActivity(
                            HiddenSwitchAuthActivity.intent(
                                context,
                                Trigger.WIDGET,
                                albumId = selection.albumId,
                            ),
                        )
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
        const val ACTION_CONTROL = "dev.backgrounded.action.WIDGET_CONTROL"
        const val EXTRA_CONTROL = "control"
        const val ACTION_TAP = "dev.backgrounded.action.WIDGET_TAP"
        const val DOUBLE_TAP_WINDOW_MILLIS = 550L
        private const val PREFERENCES = "widget_actions"
        private const val KEY_LAST_TAP = "last_tap"
    }
}
