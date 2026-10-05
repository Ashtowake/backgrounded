package dev.backgrounded.widget

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.data.datastore.Settings
import dev.backgrounded.domain.model.GestureAction
import javax.inject.Inject

data class WidgetConfig(
    val iconSource: String = "builtin:app",
    val iconAlpha: Int = 255,
    val backgroundAlpha: Int = 0,
    val tapAction: GestureAction = GestureAction.NEXT,
    val doubleTapAction: GestureAction = GestureAction.NEXT_ALBUM,
    val pinnedAlbumId: Long? = null,
)

class WidgetConfigStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val preferences = context.getSharedPreferences("widget_instances", Context.MODE_PRIVATE)

        fun get(
            id: Int,
            legacy: Settings? = null,
        ): WidgetConfig {
            val prefix = "$id."
            if (!preferences.contains(prefix + "icon")) {
                return legacy?.let {
                    WidgetConfig(
                        iconSource =
                            if (it.widgetIconSource == "builtin:next") "builtin:app" else it.widgetIconSource,
                        iconAlpha = it.widgetIconAlpha,
                        backgroundAlpha = it.widgetBackgroundAlpha,
                        tapAction = it.widgetTapAction,
                        doubleTapAction = it.widgetDoubleTapAction,
                        pinnedAlbumId = it.widgetPinnedAlbumId,
                    )
                } ?: WidgetConfig()
            }
            return WidgetConfig(
                iconSource = preferences.getString(prefix + "icon", "builtin:app") ?: "builtin:app",
                iconAlpha = preferences.getInt(prefix + "icon_alpha", 255),
                backgroundAlpha = preferences.getInt(prefix + "background_alpha", 0),
                tapAction = GestureAction.from(preferences.getString(prefix + "tap", null)),
                doubleTapAction = GestureAction.from(preferences.getString(prefix + "double_tap", null)),
                pinnedAlbumId = preferences.getLong(prefix + "album", -1L).takeIf { it >= 0L },
            )
        }

        fun put(
            id: Int,
            config: WidgetConfig,
        ) {
            val prefix = "$id."
            preferences.edit()
                .putString(prefix + "icon", config.iconSource)
                .putInt(prefix + "icon_alpha", config.iconAlpha)
                .putInt(prefix + "background_alpha", config.backgroundAlpha)
                .putString(prefix + "tap", config.tapAction.name)
                .putString(prefix + "double_tap", config.doubleTapAction.name)
                .putLong(prefix + "album", config.pinnedAlbumId ?: -1L)
                .apply()
        }

        fun remove(id: Int) {
            val prefix = "$id."
            preferences.edit().also { editor ->
                preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
            }.apply()
        }
    }
