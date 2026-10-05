package dev.backgrounded.domain.usecase

import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HiddenAlbumSwitchPolicy
    @Inject
    constructor(
        private val settingsStore: SettingsStore,
        private val albumRepository: AlbumRepository,
    ) {
        suspend fun requiresAuthentication(albumId: Long): Boolean {
            val settings = settingsStore.settings.first()
            val currentHidden = settings.activeAlbumId?.let { albumRepository.getAlbum(it)?.isHidden } == true
            val targetHidden = albumRepository.getAlbum(albumId)?.isHidden == true
            return settings.authenticateHiddenSwitch && !currentHidden && targetHidden
        }
    }
