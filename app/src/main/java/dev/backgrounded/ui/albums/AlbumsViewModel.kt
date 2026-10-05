package dev.backgrounded.ui.albums

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.backgrounded.core.security.EncryptedImageStore
import dev.backgrounded.core.security.PinVault
import dev.backgrounded.data.datastore.Settings
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.importer.ManagedSourceMover
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Album
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.rotation.AlbumSwitchMode
import dev.backgrounded.domain.usecase.ApplyNextBackground
import dev.backgrounded.domain.usecase.ApplyPreviousBackground
import dev.backgrounded.domain.usecase.NextAlbum
import dev.backgrounded.domain.usecase.NextAlbumResult
import dev.backgrounded.domain.usecase.TogglePause
import dev.backgrounded.widget.WidgetUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumsUiState(
    val visibleAlbums: List<Album> = emptyList(),
    val hiddenAlbums: List<Album> = emptyList(),
    val activeAlbumId: Long? = null,
    val paused: Boolean = false,
    val hiddenRevealed: Boolean = false,
    val encryptHidden: Boolean = false,
    val hideSourcesSystemwide: Boolean = false,
    val authenticateHiddenSwitch: Boolean = true,
) {
    val needsActiveSelection: Boolean
        get() = activeAlbumId != null && (visibleAlbums + hiddenAlbums).none { it.id == activeAlbumId }
}

@HiltViewModel
@Suppress("LongParameterList")
class AlbumsViewModel
    @Inject
    constructor(
        private val albumRepository: AlbumRepository,
        private val settingsStore: SettingsStore,
        private val applyNextBackground: ApplyNextBackground,
        private val applyPreviousBackground: ApplyPreviousBackground,
        private val nextAlbum: NextAlbum,
        private val togglePauseUseCase: TogglePause,
        private val pinVault: PinVault,
        private val encryptedImageStore: EncryptedImageStore,
        private val sourceMover: ManagedSourceMover,
        private val widgetUpdater: WidgetUpdater,
    ) : ViewModel() {
        private val hiddenRevealed = MutableStateFlow(false)
        val operationError = MutableStateFlow<String?>(null)

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
                    hiddenRevealed = revealed || albums.any { it.id == settings.activeAlbumId && it.isHidden },
                    encryptHidden = settings.encryptHidden,
                    hideSourcesSystemwide = settings.hideSourcesSystemwide,
                    authenticateHiddenSwitch = settings.authenticateHiddenSwitch,
                )
            }

        val state: StateFlow<AlbumsUiState> =
            stateFlow.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                AlbumsUiState(),
            )

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val albumPreviews: StateFlow<Map<Long, List<Background>>> =
            albumRepository.observeAlbums()
                .flatMapLatest { albums ->
                    if (albums.isEmpty()) {
                        flowOf(emptyMap())
                    } else {
                        combine(albums.map { albumRepository.observePairs(it.id) }) { lists ->
                            albums.mapIndexed { index, album ->
                                album.id to lists[index].map { it.home }
                            }.toMap()
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyMap())

        fun createAlbum(name: String) {
            viewModelScope.launch {
                val id = albumRepository.createAlbum(name.trim().ifEmpty { "Album" })
                settingsStore.setActiveAlbum(id)
                widgetUpdater.refreshAll()
            }
        }

        fun rename(
            albumId: Long,
            name: String,
        ) {
            viewModelScope.launch {
                albumRepository.renameAlbum(albumId, name.trim().ifEmpty { "Album" })
                widgetUpdater.refreshAll()
            }
        }

        fun delete(albumId: Long) {
            viewModelScope.launch {
                if (!albumRepository.deleteAlbum(albumId)) {
                    operationError.value = "Restore moved originals before deleting this album"
                }
                widgetUpdater.refreshAll()
            }
        }

        fun setActive(
            albumId: Long,
            authenticated: Boolean = false,
        ) {
            viewModelScope.launch {
                val album = albumRepository.getAlbum(albumId) ?: return@launch
                if (album.isHidden && settingsStore.settings.first().authenticateHiddenSwitch &&
                    !authenticated && !state.value.hiddenRevealed
                ) {
                    return@launch
                }
                settingsStore.setActiveAlbum(albumId)
                widgetUpdater.refreshAll()
            }
        }

        @Suppress("CyclomaticComplexMethod")
        fun setHidden(
            albumId: Long,
            hidden: Boolean,
            sourceFolder: Uri? = null,
            useFullAccess: Boolean = false,
        ) {
            viewModelScope.launch {
                val settings = settingsStore.settings.first()
                if (hidden && settings.encryptHidden && !pinVault.unlocked()) return@launch
                if (hidden && settings.hideSourcesSystemwide) {
                    if (sourceFolder == null && !useFullAccess) {
                        operationError.value = "Select a source folder or grant all-files access"
                        return@launch
                    }
                    val moved =
                        if (useFullAccess) {
                            sourceMover.moveAlbumSourcesFullAccess(albumId)
                        } else {
                            sourceMover.moveAlbumSources(albumId, requireNotNull(sourceFolder))
                        }
                    if (!moved.complete) {
                        val unrestored = sourceMover.restoreAlbum(albumId)
                        operationError.value = sourceMoveError(moved, unrestored)
                        return@launch
                    }
                }
                if (hidden && settings.encryptHidden) {
                    val assets =
                        albumRepository.pairsFor(
                            albumId,
                        ).flatMap { listOf(it.home, it.lock) }.distinctBy { it.id }
                    if (!assets.all { encryptedImageStore.encryptAsset(it.id) }) {
                        assets.forEach { encryptedImageStore.decryptAsset(it.id) }
                        val unrestored = sourceMover.restoreAlbum(albumId)
                        operationError.value =
                            "Could not encrypt every image" +
                            if (unrestored.isEmpty()) "" else "; originals not restored: ${unrestored.joinToString()}"
                        return@launch
                    }
                }
                if (!hidden) {
                    val assets =
                        albumRepository.pairsFor(
                            albumId,
                        ).flatMap { listOf(it.home, it.lock) }.distinctBy { it.id }
                    if (!assets.all { encryptedImageStore.decryptAsset(it.id) }) {
                        assets.forEach { encryptedImageStore.encryptAsset(it.id) }
                        operationError.value = "Unlock the PIN to restore encrypted images"
                        return@launch
                    }
                    val unresolved = sourceMover.restoreAlbum(albumId)
                    if (unresolved.isNotEmpty()) {
                        operationError.value =
                            "Originals not restored: ${unresolved.joinToString()}. Safe app copies remain."
                    }
                }
                albumRepository.setHidden(albumId, hidden)
                if (hidden) hiddenRevealed.value = false
            }
        }

        fun moveSources(
            albumId: Long,
            sourceFolder: Uri? = null,
            useFullAccess: Boolean = false,
        ) {
            viewModelScope.launch {
                if (albumRepository.getAlbum(albumId)?.isHidden != true) return@launch
                if (settingsStore.settings.first().encryptHidden && !pinVault.unlocked()) {
                    operationError.value = "Unlock hidden images before moving originals"
                    return@launch
                }
                val moved =
                    if (useFullAccess) {
                        sourceMover.moveAlbumSourcesFullAccess(albumId)
                    } else if (sourceFolder != null) {
                        sourceMover.moveAlbumSources(albumId, sourceFolder)
                    } else {
                        return@launch
                    }
                if (!moved.complete) operationError.value = sourceMoveError(moved)
            }
        }

        private fun sourceMoveError(
            result: ManagedSourceMover.MoveResult,
            unrestored: List<String> = emptyList(),
        ): String =
            buildString {
                if (result.unmatched.isNotEmpty()) {
                    append("No unique original found for: ")
                    append(result.unmatched.take(3).joinToString())
                    if (result.unmatched.size > 3) append(" (+${result.unmatched.size - 3} more)")
                    append(". Check source access or duplicate copies and retry.")
                }
                if (result.failed.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append("Could not move: ${result.failed.take(3).joinToString()}.")
                }
                if (unrestored.isNotEmpty()) append(" Originals not restored: ${unrestored.take(3).joinToString()}.")
            }

        fun next() = viewModelScope.launch { applyNextBackground(Trigger.MANUAL) }

        fun previous() = viewModelScope.launch { applyPreviousBackground(Trigger.MANUAL) }

        fun randomImage() = viewModelScope.launch { applyNextBackground(Trigger.MANUAL, randomize = true) }

        fun advanceAlbum(
            mode: AlbumSwitchMode = AlbumSwitchMode.NEXT,
            trigger: Trigger = Trigger.MANUAL,
            onAuthenticationRequired: () -> Unit,
        ) = viewModelScope.launch {
            if (nextAlbum(trigger, authenticated = state.value.hiddenRevealed, mode = mode) ==
                NextAlbumResult.AUTH_REQUIRED
            ) {
                onAuthenticationRequired()
            }
        }

        fun advanceAlbumAuthorized(
            trigger: Trigger,
            mode: AlbumSwitchMode,
        ) = viewModelScope.launch { nextAlbum(trigger, authenticated = true, mode = mode) }

        fun togglePause() = viewModelScope.launch { togglePauseUseCase() }

        fun revealHidden() {
            hiddenRevealed.value = true
        }

        fun concealHidden() {
            if (state.value.hiddenAlbums.none { it.id == state.value.activeAlbumId }) hiddenRevealed.value = false
        }

        fun clearError() {
            operationError.value = null
        }

        fun pinConfigured(): Boolean = pinVault.configured()

        fun systemConfigured(): Boolean = pinVault.biometricConfigured()

        fun prepareSystemKey(): Boolean = pinVault.prepareSystemKey()

        fun unlockWithSystem(): Boolean = pinVault.unlockWithSystem()

        fun pinRecoveryEnabled(): Boolean = pinVault.recoveryEnabled()

        fun setupPin(
            pin: String,
            recovery: Boolean,
        ): Boolean = pinVault.setup(pin, recovery)

        fun unlockPin(pin: String): Boolean = pinVault.unlock(pin)

        fun recoverPin(): Boolean = pinVault.recover()

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
