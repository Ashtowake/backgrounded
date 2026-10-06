package dev.backgrounded.widget

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetUpdater
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsStore: SettingsStore,
        private val albumRepository: AlbumRepository,
    ) {
        private var lastState: Pair<String?, Boolean>? = null

        suspend fun refreshPlayback() {
            val settings = settingsStore.settings.first()
            val name = settings.activeAlbumId?.let { albumRepository.getAlbum(it)?.name }
            val state = name to settings.rotationPaused
            if (state == lastState) return
            lastState = state
            refreshAll()
        }

        fun refreshAll() {
            WallpaperWidget.updateAll(context)
            PlaybackWidget.updateAll(context)
        }
    }
