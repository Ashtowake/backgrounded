package dev.backgrounded.domain.usecase

import dev.backgrounded.core.diagnostics.LocalDiagnostics
import dev.backgrounded.core.security.EncryptedImageStore
import dev.backgrounded.core.security.PinVault
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.importer.LinkedFolderScanner
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.RotationOrder
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.render.BitmapLoader
import dev.backgrounded.domain.rotation.RotationCoordinator
import dev.backgrounded.domain.rotation.RotationEngine
import dev.backgrounded.domain.rotation.RotationResult
import dev.backgrounded.schedule.ChangeScheduler
import dev.backgrounded.widget.WidgetUpdater
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("LongParameterList")
class ApplyNextBackground
    @Inject
    constructor(
        private val albumRepository: AlbumRepository,
        private val coordinator: RotationCoordinator,
        private val settingsStore: SettingsStore,
        private val bitmapLoader: BitmapLoader,
        private val changeScheduler: ChangeScheduler,
        private val widgetUpdater: WidgetUpdater,
        private val linkedFolderScanner: LinkedFolderScanner,
        private val encryptedImages: EncryptedImageStore,
        private val pinVault: PinVault,
        private val diagnostics: LocalDiagnostics,
    ) {
        suspend operator fun invoke(
            trigger: Trigger,
            albumIdOverride: Long? = null,
            expectedScheduleAt: Long? = null,
            randomize: Boolean = false,
        ): Boolean =
            execute(trigger, albumIdOverride, expectedScheduleAt, randomize) ==
                RotationResult.Applied

        @Suppress("CyclomaticComplexMethod")
        suspend fun execute(
            trigger: Trigger,
            albumIdOverride: Long? = null,
            expectedScheduleAt: Long? = null,
            randomize: Boolean = false,
        ): RotationResult =
            coordinator.run(RotationResult.Busy, automatic = trigger in AUTOMATIC_TRIGGERS) {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val settings = settingsStore.settings.first()
                if (expectedScheduleAt != null && expectedScheduleAt != settings.nextTriggerAt) {
                    return@run RotationResult.Unavailable
                }
                if (settings.rotationPaused && trigger in AUTOMATIC_TRIGGERS) return@run RotationResult.Unavailable
                val albumId =
                    albumIdOverride ?: settings.activeAlbumId ?: albumRepository.firstVisibleAlbumId()
                        ?: return@run RotationResult.Unavailable
                val album = albumRepository.getAlbum(albumId) ?: return@run RotationResult.Unavailable
                if (album.isHidden && settings.encryptHidden && !pinVault.unlocked()) {
                    return@run RotationResult.Locked
                }
                encryptedImages.recoverOperations()
                if (trigger in AUTOMATIC_TRIGGERS && !changeScheduler.hasVisibleEngine()) {
                    return@run RotationResult.Unavailable
                }
                linkedFolderScanner.scan(albumId)
                if (trigger in AUTOMATIC_TRIGGERS && !changeScheduler.hasVisibleEngine()) {
                    return@run RotationResult.Unavailable
                }
                val pairs = albumRepository.pairsFor(albumId)
                if (pairs.isEmpty()) return@run RotationResult.Unavailable
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
                    ) ?: return@run RotationResult.Unavailable
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
                        ) ?: return@run RotationResult.Unavailable
                    }
                val now = System.currentTimeMillis()
                coordinator.commit(albumId, selection.backgroundId, now, selection.shuffleRemaining, trigger)
                changeScheduler.rearm()
                widgetUpdater.refreshPlayback()
                diagnostics.record(
                    settings.debugDiagnostics,
                    "rotation ${trigger.name}",
                    android.os.SystemClock.elapsedRealtime() - startedAt,
                )
                RotationResult.Applied
            }

        private companion object {
            val AUTOMATIC_TRIGGERS = setOf(Trigger.TIMER, Trigger.UNLOCK)
        }
    }
