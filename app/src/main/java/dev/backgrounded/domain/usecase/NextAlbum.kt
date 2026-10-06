package dev.backgrounded.domain.usecase

import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.rotation.AlbumRotation
import dev.backgrounded.domain.rotation.AlbumSwitchMode
import dev.backgrounded.domain.rotation.RotationCoordinator
import dev.backgrounded.domain.rotation.RotationResult
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NextAlbum
    @Inject
    constructor(
        private val albumRepository: AlbumRepository,
        private val coordinator: RotationCoordinator,
        private val settingsStore: SettingsStore,
        private val applyNextBackground: ApplyNextBackground,
    ) {
        suspend operator fun invoke(
            trigger: Trigger = Trigger.ALBUM_SWITCH,
            authenticated: Boolean = false,
            mode: AlbumSwitchMode = AlbumSwitchMode.NEXT,
            targetAlbumId: Long? = null,
        ): RotationResult =
            coordinator.run(RotationResult.Busy) {
                val settings = settingsStore.settings.first()
                val allAlbums = albumRepository.observeAlbums().first()
                val albums = allAlbums.filter { it.rotationEnabled }
                if (albums.isEmpty()) return@run RotationResult.Unavailable
                val currentId = settings.activeAlbumId
                val currentHidden = allAlbums.any { it.id == currentId && it.isHidden }
                val candidates =
                    targetAlbumId?.let { id -> listOf(id).filter { target -> albums.any { it.id == target } } }
                        ?: AlbumRotation.candidates(albums.map { it.id }, currentId, mode)
                candidates.forEach { id ->
                    val next = albums.first { it.id == id }
                    if (albumRepository.pairsFor(next.id).isEmpty()) return@forEach
                    val authenticationNeeded = settings.authenticateHiddenSwitch && !authenticated && !currentHidden
                    if (authenticationNeeded && next.isHidden && next.id != currentId) {
                        return@run RotationResult.AuthenticationRequired(next.id)
                    }
                    val outcome = applyNextBackground.execute(trigger, albumIdOverride = next.id)
                    if (outcome == RotationResult.Locked) {
                        return@run RotationResult.AuthenticationRequired(next.id)
                    }
                    if (outcome == RotationResult.Applied) {
                        return@run RotationResult.Applied
                    }
                }
                RotationResult.Unavailable
            }
    }
