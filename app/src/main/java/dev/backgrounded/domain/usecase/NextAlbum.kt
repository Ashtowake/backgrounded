package dev.backgrounded.domain.usecase

import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Trigger
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NextAlbum
    @Inject
    constructor(
        private val albumRepository: AlbumRepository,
        private val settingsStore: SettingsStore,
        private val applyNextBackground: ApplyNextBackground,
    ) {
        suspend operator fun invoke(trigger: Trigger = Trigger.ALBUM_SWITCH): Boolean {
            val settings = settingsStore.settings.first()
            val albums = albumRepository.observeAlbums().first()
            if (albums.isEmpty()) return false
            val currentId = settings.activeAlbumId
            val cycle = albums.filter { !it.isHidden || it.id == currentId }
            if (cycle.isEmpty()) return false
            val index = cycle.indexOfFirst { it.id == currentId }
            val next = if (index < 0) cycle.first() else cycle[(index + 1) % cycle.size]
            settingsStore.setActiveAlbum(next.id)
            return applyNextBackground(trigger, albumIdOverride = next.id)
        }
    }
