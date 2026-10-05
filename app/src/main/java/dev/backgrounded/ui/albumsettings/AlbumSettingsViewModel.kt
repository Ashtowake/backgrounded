package dev.backgrounded.ui.albumsettings

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.backgrounded.core.security.EncryptedImageStore
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.importer.ImageImporter
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Album
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.RotationOrder
import dev.backgrounded.domain.model.ScheduleType
import dev.backgrounded.domain.model.SlideMode
import dev.backgrounded.domain.model.SourceType
import dev.backgrounded.domain.model.UnlockPolicy
import dev.backgrounded.domain.model.WallpaperSurface
import dev.backgrounded.schedule.ChangeScheduler
import dev.backgrounded.ui.nav.AlbumSettingsRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

@HiltViewModel
class AlbumSettingsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val albumRepository: AlbumRepository,
        private val imageImporter: ImageImporter,
        private val settingsStore: SettingsStore,
        private val encryptedImages: EncryptedImageStore,
        private val changeScheduler: ChangeScheduler,
    ) : ViewModel() {
        private val albumId: Long = savedStateHandle.toRoute<AlbumSettingsRoute>().albumId
        val deleteError = MutableStateFlow(false)

        val album: StateFlow<Album?> =
            albumRepository.observeAlbum(albumId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

        val assets: StateFlow<List<Background>> =
            albumRepository.observeAssets(albumId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        fun pickFixedAsset(
            surface: WallpaperSurface,
            uri: Uri,
        ) {
            viewModelScope.launch {
                val imported = imageImporter.import(uri) ?: return@launch
                val asset =
                    Background(
                        id = 0,
                        albumId = albumId,
                        sourceType = SourceType.IMPORT,
                        storageRef = imported.filePath,
                        displayName = imported.displayName,
                        sha256 = imported.sha256,
                        width = imported.width,
                        height = imported.height,
                        dimForLock = settingsStore.settings.first().lockDimDefault,
                        sortIndex = 0,
                        addedAt = System.currentTimeMillis(),
                        framings =
                            Background.defaultFramings().mapValues { (_, framing) ->
                                framing.copy(rotationDegrees = imported.orientationDegrees)
                            },
                    )
                val id = albumRepository.addStandaloneAsset(albumId, asset)
                if (album.value?.isHidden == true && settingsStore.settings.first().encryptHidden) {
                    encryptedImages.encryptAsset(id)
                }
                albumRepository.setFixedAsset(albumId, surface, id)
            }
        }

        fun setFixedAsset(
            surface: WallpaperSurface,
            assetId: Long?,
        ) {
            viewModelScope.launch { albumRepository.setFixedAsset(albumId, surface, assetId) }
        }

        fun rename(name: String) {
            viewModelScope.launch { albumRepository.renameAlbum(albumId, name.trim().ifEmpty { "Album" }) }
        }

        fun setOrder(order: RotationOrder) {
            viewModelScope.launch { albumRepository.setRotationOrder(albumId, order) }
        }

        fun setRotationEnabled(enabled: Boolean) {
            viewModelScope.launch { albumRepository.setRotationEnabled(albumId, enabled) }
        }

        fun setCrossfade(
            enabled: Boolean,
            durationMs: Int,
        ) {
            viewModelScope.launch { albumRepository.setCrossfade(albumId, enabled, durationMs) }
        }

        fun setSlideOptions(
            mode: SlideMode,
            speedPxPerSecond: Float,
        ) {
            viewModelScope.launch {
                albumRepository.setSlideOptions(albumId, mode, speedPxPerSecond)
            }
        }

        fun setSchedule(
            type: ScheduleType,
            intervalSeconds: Int?,
            fixedTimes: List<LocalTime>,
        ) {
            viewModelScope.launch {
                albumRepository.setSchedule(albumId, type, intervalSeconds, fixedTimes)
                changeScheduler.rearm()
            }
        }

        fun addFixedTime(time: LocalTime) {
            val current = album.value ?: return
            val times = (current.fixedTimes + time).distinct().sorted()
            setSchedule(ScheduleType.FIXED_TIMES, null, times)
        }

        fun removeFixedTime(time: LocalTime) {
            val current = album.value ?: return
            setSchedule(ScheduleType.FIXED_TIMES, null, current.fixedTimes - time)
        }

        fun setUnlockPolicy(
            enabled: Boolean,
            minMinutes: Int,
            everyN: Int,
            maxPerDay: Int,
        ) {
            viewModelScope.launch {
                albumRepository.setUnlockPolicy(
                    albumId,
                    UnlockPolicy(
                        enabled = enabled,
                        minMinutes = minMinutes.coerceAtLeast(0),
                        everyN = everyN.coerceAtLeast(1),
                        maxPerDay = maxPerDay.coerceAtLeast(0),
                    ),
                )
            }
        }

        fun setHidden(hidden: Boolean) {
            viewModelScope.launch { albumRepository.setHidden(albumId, hidden) }
        }

        fun delete(onDeleted: () -> Unit) {
            viewModelScope.launch {
                if (albumRepository.deleteAlbum(albumId)) {
                    onDeleted()
                } else {
                    deleteError.value = true
                }
            }
        }

        fun clearDeleteError() {
            deleteError.value = false
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
