package dev.backgrounded.domain.usecase

import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.data.repository.HistoryRepository
import dev.backgrounded.domain.model.CurrentWallpaper
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.rotation.RotationEngine
import dev.backgrounded.domain.state.WallpaperBus
import dev.backgrounded.widget.WidgetUpdater
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApplyPreviousBackground
    @Inject
    constructor(
        private val albumRepository: AlbumRepository,
        private val historyRepository: HistoryRepository,
        private val settingsStore: SettingsStore,
        private val wallpaperBus: WallpaperBus,
        private val widgetUpdater: WidgetUpdater,
    ) {
        private val mutex = Mutex()

        suspend operator fun invoke(trigger: Trigger = Trigger.MANUAL): Boolean =
            mutex.withLock {
                val settings = settingsStore.settings.first()
                val albumId = settings.activeAlbumId ?: return@withLock false
                val currentId = settings.currentPairId
                val recent = historyRepository.recentForAlbum(albumId, limit = 5).map { it.pairId }
                val previousId = RotationEngine.previous(currentId, recent) ?: return@withLock false
                val now = System.currentTimeMillis()
                albumRepository.updateRotationState(albumId, previousId, now, emptyList())
                settingsStore.setCurrent(previousId, now)
                historyRepository.record(previousId, albumId, now, trigger)
                wallpaperBus.set(CurrentWallpaper(previousId, albumId, now))
                widgetUpdater.refreshAll()
                true
            }
    }
