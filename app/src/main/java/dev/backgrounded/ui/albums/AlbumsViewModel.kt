package dev.backgrounded.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.backgrounded.data.datastore.Settings
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Album
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.usecase.ApplyNextBackground
import dev.backgrounded.domain.usecase.ApplyPreviousBackground
import dev.backgrounded.domain.usecase.NextAlbum
import dev.backgrounded.domain.usecase.TogglePause
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumsUiState(
    val visibleAlbums: List<Album> = emptyList(),
    val hiddenAlbums: List<Album> = emptyList(),
    val activeAlbumId: Long? = null,
    val paused: Boolean = false,
    val hiddenRevealed: Boolean = false,
)

@HiltViewModel
class AlbumsViewModel
    @Inject
    constructor(
        private val albumRepository: AlbumRepository,
        private val settingsStore: SettingsStore,
        private val applyNextBackground: ApplyNextBackground,
        private val applyPreviousBackground: ApplyPreviousBackground,
        private val nextAlbum: NextAlbum,
        private val togglePauseUseCase: TogglePause,
    ) : ViewModel() {
        private val hiddenRevealed = MutableStateFlow(false)

        private val stateFlow: Flow<AlbumsUiState> =
            combine(
                albumRepository.observeAlbums(),
                settingsStore.settings,
                hiddenRevealed,
            ) { albums: List<Album>, settings: Settings, revealed: Boolean ->
                AlbumsUiState(
                    visibleAlbums = albums.filter { !it.isHidden },
                    hiddenAlbums = albums.filter { it.isHidden },
                    activeAlbumId =
                        settings.activeAlbumId
                            ?: albums.firstOrNull { !it.isHidden }?.id,
                    paused = settings.rotationPaused,
                    hiddenRevealed = revealed,
                )
            }

        val state: StateFlow<AlbumsUiState> =
            stateFlow.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                AlbumsUiState(),
            )

        fun createAlbum(name: String) {
            viewModelScope.launch {
                val id = albumRepository.createAlbum(name.trim().ifEmpty { "Album" })
                settingsStore.setActiveAlbum(id)
            }
        }

        fun setActive(albumId: Long) {
            viewModelScope.launch { settingsStore.setActiveAlbum(albumId) }
        }

        fun setHidden(
            albumId: Long,
            hidden: Boolean,
        ) {
            viewModelScope.launch {
                albumRepository.setHidden(albumId, hidden)
                if (hidden) {
                    settingsStore.setActiveAlbum(null)
                    hiddenRevealed.value = false
                }
            }
        }

        fun next() = viewModelScope.launch { applyNextBackground(Trigger.MANUAL) }

        fun previous() = viewModelScope.launch { applyPreviousBackground(Trigger.MANUAL) }

        fun advanceAlbum() = viewModelScope.launch { nextAlbum(Trigger.MANUAL) }

        fun togglePause() = viewModelScope.launch { togglePauseUseCase() }

        fun revealHidden() {
            hiddenRevealed.value = true
        }

        fun concealHidden() {
            hiddenRevealed.value = false
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
