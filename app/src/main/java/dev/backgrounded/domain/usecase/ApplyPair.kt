package dev.backgrounded.domain.usecase

import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.data.repository.HistoryRepository
import dev.backgrounded.domain.model.CurrentWallpaper
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.state.WallpaperBus
import dev.backgrounded.schedule.ChangeScheduler
import dev.backgrounded.widget.WidgetUpdater
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApplyPair
    @Inject
    constructor(
        private val albumRepository: AlbumRepository,
        private val historyRepository: HistoryRepository,
        private val settingsStore: SettingsStore,
        private val wallpaperBus: WallpaperBus,
        private val widgetUpdater: WidgetUpdater,
        private val changeScheduler: ChangeScheduler,
    ) {
        suspend operator fun invoke(
            pairId: Long,
            trigger: Trigger,
        ): Boolean {
            val pair = albumRepository.getPair(pairId) ?: return false
            val now = System.currentTimeMillis()
            albumRepository.updateRotationState(pair.albumId, pair.id, now, emptyList())
            settingsStore.setActiveAlbum(pair.albumId)
            settingsStore.setCurrent(pair.id, now)
            historyRepository.record(pair.id, pair.albumId, now, trigger)
            wallpaperBus.set(CurrentWallpaper(pair.id, pair.albumId, now))
            changeScheduler.rearm()
            widgetUpdater.refreshAll()
            return true
        }
    }
