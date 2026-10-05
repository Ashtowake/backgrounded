package dev.backgrounded.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.widget.RemoteViews
import dagger.hilt.android.AndroidEntryPoint
import dev.backgrounded.R
import dev.backgrounded.data.datastore.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class WallpaperWidget : AppWidgetProvider() {
    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var widgetConfigStore: WidgetConfigStore

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val settings = runBlocking { settingsStore.settings.first() }
        appWidgetIds.forEach { id ->
            val config = widgetConfigStore.get(id, settings)
            widgetConfigStore.put(id, config)
            appWidgetManager.updateAppWidget(id, buildViews(context, id, config))
        }
    }

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach(widgetConfigStore::remove)
        super.onDeleted(context, appWidgetIds)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WallpaperWidget::class.java))
            if (ids.isEmpty()) return
            val intent =
                Intent(context, WallpaperWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }

        fun buildViews(
            context: Context,
            id: Int,
            config: WidgetConfig,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_wallpaper)
            views.setInt(
                R.id.widget_root,
                "setBackgroundColor",
                Color.argb(config.backgroundAlpha.coerceIn(0, 255), 0, 0, 0),
            )
            val bitmap = customIconBitmap(config.iconSource)
            if (bitmap != null) {
                views.setImageViewBitmap(R.id.widget_icon, bitmap)
            } else {
                views.setImageViewResource(R.id.widget_icon, builtinIcon(config.iconSource))
            }
            views.setInt(R.id.widget_icon, "setImageAlpha", config.iconAlpha.coerceIn(0, 255))
            views.setOnClickPendingIntent(R.id.widget_root, tapIntent(context, id))
            return views
        }

        private fun tapIntent(
            context: Context,
            id: Int,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, WidgetActionReceiver::class.java)
                    .setAction(WidgetActionReceiver.ACTION_TAP)
                    .setData(Uri.parse("backgrounded://widget/$id/tap"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun builtinIcon(source: String): Int =
            when (source) {
                "builtin:previous" -> R.drawable.ic_widget_previous
                "builtin:album" -> R.drawable.ic_widget_album
                "builtin:pause" -> R.drawable.ic_widget_pause
                "builtin:invisible" -> R.drawable.ic_widget_empty
                "builtin:app" -> R.mipmap.ic_launcher
                else -> R.drawable.ic_widget_next
            }

        private fun customIconBitmap(source: String): Bitmap? {
            if (!source.startsWith("file:")) return null
            val file = File(source.removePrefix("file:"))
            if (!file.isFile) return null
            val options = BitmapFactory.Options().apply { inSampleSize = iconSampleSize(file) }
            return BitmapFactory.decodeFile(file.absolutePath, options)
        }

        private fun iconSampleSize(file: File): Int {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > ICON_MAX_SIZE || bounds.outHeight / sample > ICON_MAX_SIZE) {
                sample *= 2
            }
            return sample
        }

        private const val ICON_MAX_SIZE = 192
    }
}
