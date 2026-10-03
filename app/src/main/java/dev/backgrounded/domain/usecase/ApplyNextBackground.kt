package dev.backgrounded.domain.usecase

import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.data.repository.HistoryRepository
import dev.backgrounded.domain.model.CurrentWallpaper
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.render.BitmapLoader
import dev.backgrounded.domain.rotation.RotationEngine
import dev.backgrounded.domain.state.WallpaperBus
import dev.backgrounded.schedule.ChangeScheduler
import dev.backgrounded.widget.WidgetUpdater
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApplyNextBackground
    @Inject
    constructor(
        private val albumRepository: AlbumRepository,
        private val historyRepository: HistoryRepository,
        private val settingsStore: SettingsStore,
        private val bitmapLoader: BitmapLoader,
        private val wallpaperBus: WallpaperBus,
        private val changeScheduler: ChangeScheduler,
        private val widgetUpdater: WidgetUpdater,
    ) {
        private val mutex = Mutex()

        suspend operator fun invoke(
            trigger: Trigger,
            albumIdOverride: Long? = null,
        ): Boolean =
            mutex.withLock {
                val settings = settingsStore.settings.first()
                if (settings.rotationPaused && trigger in AUTOMATIC_TRIGGERS) return@withLock false
                val albumId =
                    albumIdOverride ?: settings.activeAlbumId ?: albumRepository.firstVisibleAlbumId()
                        ?: return@withLock false
                val album = albumRepository.getAlbum(albumId) ?: return@withLock false
                val pairs =
                    albumRepository.pairsFor(albumId)
                        .filter { pair -> bitmapLoader.exists(pair.home) && bitmapLoader.exists(pair.lock) }
                if (pairs.isEmpty()) return@withLock false
                val ids = pairs.map { it.id }
                val currentId =
                    settings.currentPairId
                        ?.takeIf { it in ids }
                        ?: album.lastAppliedPairId?.takeIf { it in ids }
                val selection =
                    RotationEngine.next(
                        currentId = currentId,
                        orderedIds = ids,
                        order = album.rotationOrder,
                        shuffleRemaining = album.shuffleRemaining,
                    ) ?: return@withLock false
                val now = System.currentTimeMillis()
                albumRepository.updateRotationState(albumId, selection.backgroundId, now, selection.shuffleRemaining)
                settingsStore.setActiveAlbum(albumId)
                settingsStore.setCurrent(selection.backgroundId, now)
                historyRepository.record(selection.backgroundId, albumId, now, trigger)
                wallpaperBus.set(CurrentWallpaper(selection.backgroundId, albumId, now))
                changeScheduler.rearm()
                widgetUpdater.refreshAll()
                true
            }

        private companion object {
            val AUTOMATIC_TRIGGERS = setOf(Trigger.TIMER, Trigger.UNLOCK)
        }
    }
