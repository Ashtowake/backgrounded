package dev.backgrounded.domain.usecase

import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.rotation.AlbumRotation
import dev.backgrounded.domain.rotation.AlbumSwitchMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

enum class NextAlbumResult { CHANGED, AUTH_REQUIRED, UNCHANGED }

@Singleton
class NextAlbum
    @Inject
    constructor(
        private val albumRepository: AlbumRepository,
        private val settingsStore: SettingsStore,
        private val applyNextBackground: ApplyNextBackground,
    ) {
        private val mutex = Mutex()

        suspend operator fun invoke(
            trigger: Trigger = Trigger.ALBUM_SWITCH,
            authenticated: Boolean = false,
            mode: AlbumSwitchMode = AlbumSwitchMode.NEXT,
        ): NextAlbumResult =
            mutex.withLock {
                val settings = settingsStore.settings.first()
                val allAlbums = albumRepository.observeAlbums().first()
                val albums = allAlbums.filter { it.rotationEnabled }
                if (albums.isEmpty()) return@withLock NextAlbumResult.UNCHANGED
                val currentId = settings.activeAlbumId
                val currentHidden = allAlbums.any { it.id == currentId && it.isHidden }
                AlbumRotation.candidates(albums.map { it.id }, currentId, mode).forEach { id ->
                    val next = albums.first { it.id == id }
                    if (albumRepository.pairsFor(next.id).isEmpty()) return@forEach
                    val authenticationNeeded = settings.authenticateHiddenSwitch && !authenticated && !currentHidden
                    if (authenticationNeeded && next.isHidden && next.id != currentId) {
                        return@withLock NextAlbumResult.AUTH_REQUIRED
                    }
                    if (applyNextBackground(trigger, albumIdOverride = next.id)) {
                        return@withLock NextAlbumResult.CHANGED
                    }
                }
                NextAlbumResult.UNCHANGED
            }
    }
