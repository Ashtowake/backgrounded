package dev.backgrounded.domain.usecase

import dev.backgrounded.core.diagnostics.LocalDiagnostics
import dev.backgrounded.core.security.EncryptedImageStore
import dev.backgrounded.core.security.PinVault
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.importer.LinkedFolderScanner
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.data.repository.HistoryRepository
import dev.backgrounded.domain.model.CurrentWallpaper
import dev.backgrounded.domain.model.RotationOrder
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
@Suppress("LongParameterList")
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
        private val linkedFolderScanner: LinkedFolderScanner,
        private val encryptedImages: EncryptedImageStore,
        private val pinVault: PinVault,
        private val diagnostics: LocalDiagnostics,
    ) {
        private val mutex = Mutex()

        @Suppress("CyclomaticComplexMethod")
        suspend operator fun invoke(
            trigger: Trigger,
            albumIdOverride: Long? = null,
            expectedScheduleAt: Long? = null,
            randomize: Boolean = false,
        ): Boolean =
            mutex.withLock {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val settings = settingsStore.settings.first()
                if (expectedScheduleAt != null && expectedScheduleAt != settings.nextTriggerAt) return@withLock false
                if (settings.rotationPaused && trigger in AUTOMATIC_TRIGGERS) return@withLock false
                val albumId =
                    albumIdOverride ?: settings.activeAlbumId ?: albumRepository.firstVisibleAlbumId()
                        ?: return@withLock false
                val album = albumRepository.getAlbum(albumId) ?: return@withLock false
                if (album.isHidden && settings.encryptHidden && !pinVault.unlocked()) return@withLock false
                linkedFolderScanner.scan(albumId)
                if (album.isHidden && settings.encryptHidden && !encryptedImages.encryptHiddenAlbums()) {
                    return@withLock false
                }
                val pairs = albumRepository.pairsFor(albumId)
                if (pairs.isEmpty()) return@withLock false
                val ids = pairs.map { it.id }
                val currentId =
                    settings.currentPairId
                        ?.takeIf { it in ids }
                        ?: album.lastAppliedPairId?.takeIf { it in ids }
                val firstSelection =
                    RotationEngine.next(
                        currentId = currentId,
                        orderedIds = ids,
                        order = if (randomize) RotationOrder.SHUFFLE else album.rotationOrder,
                        shuffleRemaining = if (randomize) emptyList() else album.shuffleRemaining,
                    ) ?: return@withLock false
                val selectedPair = pairs.first { it.id == firstSelection.backgroundId }
                val selection =
                    if (bitmapLoader.exists(selectedPair.home) && bitmapLoader.exists(selectedPair.lock)) {
                        firstSelection
                    } else {
                        val available =
                            pairs.filter { bitmapLoader.exists(it.home) && bitmapLoader.exists(it.lock) }
                        val availableIds = available.map { it.id }
                        val availableCurrent =
                            settings.currentPairId?.takeIf { it in availableIds }
                                ?: album.lastAppliedPairId?.takeIf { it in availableIds }
                        RotationEngine.next(
                            currentId = availableCurrent,
                            orderedIds = availableIds,
                            order = if (randomize) RotationOrder.SHUFFLE else album.rotationOrder,
                            shuffleRemaining = if (randomize) emptyList() else album.shuffleRemaining,
                        ) ?: return@withLock false
                    }
                val now = System.currentTimeMillis()
                albumRepository.updateRotationState(albumId, selection.backgroundId, now, selection.shuffleRemaining)
                settingsStore.setActiveAlbum(albumId)
                settingsStore.setCurrent(selection.backgroundId, now)
                historyRepository.record(selection.backgroundId, albumId, now, trigger)
                wallpaperBus.set(CurrentWallpaper(selection.backgroundId, albumId, now))
                changeScheduler.rearm()
                widgetUpdater.refreshAll()
                diagnostics.record(
                    settings.debugDiagnostics,
                    "rotation ${trigger.name}",
                    android.os.SystemClock.elapsedRealtime() - startedAt,
                )
                true
            }

        private companion object {
            val AUTOMATIC_TRIGGERS = setOf(Trigger.TIMER, Trigger.UNLOCK)
        }
    }
