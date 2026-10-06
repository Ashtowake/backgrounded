package dev.backgrounded.domain.usecase

import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.data.repository.HistoryRepository
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.rotation.RotationCoordinator
import dev.backgrounded.domain.rotation.RotationEngine
import dev.backgrounded.domain.rotation.RotationResult
import dev.backgrounded.widget.WidgetUpdater
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApplyPreviousBackground
    @Inject
    constructor(
        private val albumRepository: AlbumRepository,
        private val coordinator: RotationCoordinator,
        private val historyRepository: HistoryRepository,
        private val settingsStore: SettingsStore,
        private val widgetUpdater: WidgetUpdater,
        private val changeScheduler: dev.backgrounded.schedule.ChangeScheduler,
        private val loader: dev.backgrounded.domain.render.BitmapLoader,
    ) {
        suspend operator fun invoke(
            trigger: Trigger = Trigger.MANUAL,
            albumIdOverride: Long? = null,
        ): Boolean = execute(trigger, albumIdOverride) == RotationResult.Applied

        suspend fun execute(
            trigger: Trigger = Trigger.MANUAL,
            albumIdOverride: Long? = null,
        ): RotationResult =
            coordinator.run(RotationResult.Busy) {
                val settings = settingsStore.settings.first()
                val albumId = albumIdOverride ?: settings.activeAlbumId ?: return@run RotationResult.Unavailable
                val currentId = settings.currentPairId
                val recent = historyRepository.recentForAlbum(albumId, limit = 5).map { it.pairId }
                val previousId = RotationEngine.previous(currentId, recent) ?: return@run RotationResult.Unavailable
                val pair =
                    albumRepository.pairsFor(albumId).firstOrNull {
                        it.id == previousId
                    } ?: return@run RotationResult.Unavailable
                if (!loader.exists(pair.home) || !loader.exists(pair.lock)) return@run RotationResult.Unavailable
                val now = System.currentTimeMillis()
                coordinator.commit(albumId, previousId, now, emptyList(), trigger)
                changeScheduler.rearm()
                widgetUpdater.refreshPlayback()
                RotationResult.Applied
            }
    }
