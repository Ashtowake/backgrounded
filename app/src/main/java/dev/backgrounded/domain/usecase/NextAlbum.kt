package dev.backgrounded.domain.usecase

import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Trigger
import kotlinx.coroutines.flow.first
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
        suspend operator fun invoke(
            trigger: Trigger = Trigger.ALBUM_SWITCH,
            authenticated: Boolean = false,
        ): NextAlbumResult {
            val settings = settingsStore.settings.first()
            val albums = albumRepository.observeAlbums().first().filter { it.rotationEnabled }
            if (albums.isEmpty()) return NextAlbumResult.UNCHANGED
            val currentId = settings.activeAlbumId
            val index = albums.indexOfFirst { it.id == currentId }
            albums.indices.forEach { step ->
                val next = albums[(index + step + 1) % albums.size]
                if (settings.authenticateHiddenSwitch && next.isHidden && next.id != currentId && !authenticated) {
                    return NextAlbumResult.AUTH_REQUIRED
                }
                if (applyNextBackground(trigger, albumIdOverride = next.id)) {
                    return NextAlbumResult.CHANGED
                }
            }
            return NextAlbumResult.UNCHANGED
        }
    }
