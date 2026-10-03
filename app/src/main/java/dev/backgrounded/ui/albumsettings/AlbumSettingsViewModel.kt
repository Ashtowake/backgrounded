package dev.backgrounded.ui.albumsettings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Album
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.RotationOrder
import dev.backgrounded.domain.model.ScheduleType
import dev.backgrounded.domain.model.UnlockPolicy
import dev.backgrounded.domain.model.WallpaperSurface
import dev.backgrounded.ui.nav.AlbumSettingsRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
    ) : ViewModel() {
        private val albumId: Long = savedStateHandle.toRoute<AlbumSettingsRoute>().albumId

        val album: StateFlow<Album?> =
            albumRepository.observeAlbum(albumId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

        val assets: StateFlow<List<Background>> =
            albumRepository.observePairs(albumId)
                .map { pairs -> pairs.flatMap { listOf(it.home, it.lock) }.distinctBy { it.id } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

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

        fun setSchedule(
            type: ScheduleType,
            intervalMinutes: Int?,
            fixedTimes: List<LocalTime>,
        ) {
            viewModelScope.launch { albumRepository.setSchedule(albumId, type, intervalMinutes, fixedTimes) }
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
                albumRepository.deleteAlbum(albumId)
                onDeleted()
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
