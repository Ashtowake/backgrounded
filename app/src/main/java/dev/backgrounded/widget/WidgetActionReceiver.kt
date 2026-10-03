package dev.backgrounded.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dagger.hilt.android.AndroidEntryPoint
import dev.backgrounded.MainActivity
import dev.backgrounded.core.di.ApplicationScope
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.domain.model.GestureAction
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.usecase.ApplyNextBackground
import dev.backgrounded.domain.usecase.ApplyPreviousBackground
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
    lateinit var applyNextBackground: ApplyNextBackground

    @Inject
    lateinit var applyPreviousBackground: ApplyPreviousBackground

    @Inject
    lateinit var nextAlbum: NextAlbum

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
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val lastTap = preferences.getLong(KEY_LAST_TAP, 0L)
        if (lastTap != 0L && now - lastTap <= DOUBLE_TAP_WINDOW_MILLIS) {
            preferences.edit().putLong(KEY_LAST_TAP, 0L).apply()
            applicationScope.launch { dispatch(context, doubleTapAction()) }
            return
        }
        preferences.edit().putLong(KEY_LAST_TAP, now).apply()
        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                delay(DOUBLE_TAP_WINDOW_MILLIS)
                if (preferences.getLong(KEY_LAST_TAP, 0L) == now) {
                    preferences.edit().putLong(KEY_LAST_TAP, 0L).apply()
                    dispatch(context, tapAction())
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun tapAction(): GestureAction = settingsStore.settings.first().widgetTapAction

    private suspend fun doubleTapAction(): GestureAction = settingsStore.settings.first().widgetDoubleTapAction

    private suspend fun dispatch(
        context: Context,
        action: GestureAction,
    ) {
        val pinnedAlbumId = settingsStore.settings.first().widgetPinnedAlbumId
        when (action) {
            GestureAction.NEXT -> applyNextBackground(Trigger.WIDGET, albumIdOverride = pinnedAlbumId)
            GestureAction.PREVIOUS -> {
                if (pinnedAlbumId != null) settingsStore.setActiveAlbum(pinnedAlbumId)
                applyPreviousBackground(Trigger.WIDGET)
            }

            GestureAction.NEXT_ALBUM ->
                if (pinnedAlbumId != null) {
                    settingsStore.setActiveAlbum(pinnedAlbumId)
                    applyNextBackground(Trigger.WIDGET, albumIdOverride = pinnedAlbumId)
                } else {
                    nextAlbum(Trigger.WIDGET)
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
        const val DOUBLE_TAP_WINDOW_MILLIS = 350L
        private const val PREFERENCES = "widget_actions"
        private const val KEY_LAST_TAP = "last_tap"
    }
}
