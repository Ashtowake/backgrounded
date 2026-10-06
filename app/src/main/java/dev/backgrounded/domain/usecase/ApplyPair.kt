package dev.backgrounded.domain.usecase

import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.rotation.RotationCoordinator
import dev.backgrounded.domain.rotation.RotationResult
import dev.backgrounded.schedule.ChangeScheduler
import dev.backgrounded.widget.WidgetUpdater
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApplyPair
    @Inject
    constructor(
        private val albumRepository: AlbumRepository,
        private val coordinator: RotationCoordinator,
        private val widgetUpdater: WidgetUpdater,
        private val changeScheduler: ChangeScheduler,
        private val loader: dev.backgrounded.domain.render.BitmapLoader,
    ) {
        suspend operator fun invoke(
            pairId: Long,
            trigger: Trigger,
        ): Boolean = execute(pairId, trigger) == RotationResult.Applied

        suspend fun execute(
            pairId: Long,
            trigger: Trigger,
        ): RotationResult =
            coordinator.run(RotationResult.Busy) {
                val pair = albumRepository.getPair(pairId) ?: return@run RotationResult.Unavailable
                if (!loader.exists(pair.home) || !loader.exists(pair.lock)) return@run RotationResult.Unavailable
                val now = System.currentTimeMillis()
                coordinator.commit(pair.albumId, pair.id, now, emptyList(), trigger)
                changeScheduler.rearm()
                widgetUpdater.refreshPlayback()
                RotationResult.Applied
            }
    }
