package dev.backgrounded.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import dagger.hilt.android.AndroidEntryPoint
import dev.backgrounded.MainActivity
import dev.backgrounded.R
import dev.backgrounded.core.di.ApplicationScope
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackWidget : AppWidgetProvider() {
    @Inject lateinit var settingsStore: SettingsStore

    @Inject lateinit var albumRepository: AlbumRepository

    @Inject @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        applicationScope.launch {
            try {
                val settings = settingsStore.settings.first()
                val name = settings.activeAlbumId?.let { albumRepository.getAlbum(it)?.name } ?: "No active album"
                appWidgetIds.forEach { id ->
                    appWidgetManager.updateAppWidget(id, buildViews(context, id, name, settings.rotationPaused))
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) = onUpdate(context, appWidgetManager, intArrayOf(appWidgetId))

    companion object {
        fun updateAll(context: Context) {
            val ids =
                AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, PlaybackWidget::class.java))
            if (ids.isNotEmpty()) {
                context.sendBroadcast(
                    Intent(context, PlaybackWidget::class.java)
                        .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
                )
            }
        }

        fun buildViews(
            context: Context,
            widgetId: Int,
            albumName: String,
            paused: Boolean,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_playback)
            views.setOnClickPendingIntent(
                R.id.control_search,
                PendingIntent.getActivity(
                    context,
                    widgetId,
                    Intent(context, GalleryPickerActivity::class.java)
                        .setData(Uri.parse("backgrounded://gallery/$widgetId"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            views.setTextViewText(R.id.playback_album_name, albumName)
            views.setOnClickPendingIntent(
                R.id.playback_album_name,
                PendingIntent.getActivity(
                    context,
                    widgetId,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            PlaybackAction.entries.forEach { action ->
                views.setImageViewResource(action.viewId, action.icon(paused))
                views.setContentDescription(action.viewId, action.label(paused))
                val intent =
                    Intent(context, WidgetActionReceiver::class.java)
                        .setAction(WidgetActionReceiver.ACTION_CONTROL)
                        .setData(Uri.parse("backgrounded://playback/$widgetId/${action.name}"))
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        .putExtra(WidgetActionReceiver.EXTRA_CONTROL, action.name)
                views.setOnClickPendingIntent(
                    action.viewId,
                    PendingIntent.getBroadcast(
                        context,
                        widgetId,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            return views
        }
    }
}
