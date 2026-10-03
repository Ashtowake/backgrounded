package dev.backgrounded.widget

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetUpdater
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun refreshAll() {
            WallpaperWidget.updateAll(context)
        }
    }
