package dev.backgrounded.ui.album

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.backgrounded.core.image.ThumbnailCache
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.importer.ImageImporter
import dev.backgrounded.data.importer.ImportedImage
import dev.backgrounded.data.importer.LinkedImage
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Album
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.BackgroundPair
import dev.backgrounded.domain.model.SourceType
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.model.WallpaperSurface
import dev.backgrounded.domain.usecase.ApplyPair
import dev.backgrounded.ui.nav.AlbumRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val albumRepository: AlbumRepository,
        private val imageImporter: ImageImporter,
        private val thumbnailCache: ThumbnailCache,
        private val settingsStore: SettingsStore,
        private val applyPair: ApplyPair,
    ) : ViewModel() {
        private val albumId: Long = savedStateHandle.toRoute<AlbumRoute>().albumId

        val album: StateFlow<Album?> =
            albumRepository.observeAlbum(albumId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

        val pairs: StateFlow<List<BackgroundPair>> =
            albumRepository.observePairs(albumId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        private val mutableMessage = MutableStateFlow<Message?>(null)
        val message: StateFlow<Message?> = mutableMessage.asStateFlow()

        fun addImages(uris: List<Uri>) {
            if (uris.isEmpty()) return
            viewModelScope.launch {
                val dimDefault = settingsStore.settings.first().lockDimDefault
                var added = 0
                var skipped = 0
                uris.forEach { uri ->
                    val imported = imageImporter.import(uri)
                    if (imported == null || albumRepository.findAssetByHash(imported.sha256) != null) {
                        skipped++
                    } else {
                        albumRepository.addPair(albumId, imported.toAsset(dimDefault))
                        added++
                    }
                }
                mutableMessage.value = Message(added, skipped)
            }
        }

        fun addLinkedImages(uris: List<Uri>) {
            if (uris.isEmpty()) return
            viewModelScope.launch {
                val dimDefault = settingsStore.settings.first().lockDimDefault
                var added = 0
                var skipped = 0
                uris.forEach { uri ->
                    val linked = imageImporter.link(uri)
                    if (linked == null) {
                        skipped++
                    } else {
                        albumRepository.addPair(albumId, linked.toAsset(dimDefault))
                        added++
                    }
                }
                mutableMessage.value = Message(added, skipped)
            }
        }

        fun setSlotImage(
            pairId: Long,
            surface: WallpaperSurface,
            uri: Uri,
        ) {
            viewModelScope.launch {
                val dimDefault = settingsStore.settings.first().lockDimDefault
                val imported = imageImporter.import(uri) ?: return@launch
                albumRepository.setPairImage(pairId, surface, imported.toAsset(dimDefault))
                thumbnailCache.evictAll()
            }
        }

        fun setSlotLinkedImage(
            pairId: Long,
            surface: WallpaperSurface,
            uri: Uri,
        ) {
            viewModelScope.launch {
                val dimDefault = settingsStore.settings.first().lockDimDefault
                val linked = imageImporter.link(uri) ?: return@launch
                albumRepository.setPairImage(pairId, surface, linked.toAsset(dimDefault))
                thumbnailCache.evictAll()
            }
        }

        fun applyNow(pairId: Long) {
            viewModelScope.launch { applyPair(pairId, Trigger.MANUAL) }
        }

        fun setCover(pairId: Long) {
            viewModelScope.launch { albumRepository.setCover(albumId, pairId) }
        }

        fun deletePair(pairId: Long) {
            viewModelScope.launch {
                albumRepository.deletePair(pairId)
                thumbnailCache.evictAll()
            }
        }

        fun move(
            pairId: Long,
            delta: Int,
        ) {
            val current = pairs.value
            val index = current.indexOfFirst { it.id == pairId }
            val target = index + delta
            if (index < 0 || target !in current.indices) return
            val reordered = current.toMutableList().apply { add(target, removeAt(index)) }
            viewModelScope.launch { albumRepository.reorderPairs(reordered.map { it.id }) }
        }

        fun clearMessage() {
            mutableMessage.value = null
        }

        private fun ImportedImage.toAsset(dimDefault: Boolean): Background =
            asset(
                spec =
                    AssetSpec(
                        sourceType = SourceType.IMPORT,
                        storageRef = filePath,
                        displayName = displayName,
                        sha256 = sha256,
                        width = width,
                        height = height,
                        rotationDegrees = orientationDegrees,
                    ),
                dimDefault = dimDefault,
            )

        private fun LinkedImage.toAsset(dimDefault: Boolean): Background =
            asset(
                spec =
                    AssetSpec(
                        sourceType = SourceType.SAF_LINK,
                        storageRef = uri,
                        displayName = displayName,
                        sha256 = null,
                        width = width,
                        height = height,
                        rotationDegrees = orientationDegrees,
                    ),
                dimDefault = dimDefault,
            )

        private fun asset(
            spec: AssetSpec,
            dimDefault: Boolean,
        ): Background {
            val framings =
                Background.defaultFramings().mapValues { (_, framing) ->
                    framing.copy(rotationDegrees = spec.rotationDegrees)
                }
            return Background(
                id = 0,
                albumId = albumId,
                sourceType = spec.sourceType,
                storageRef = spec.storageRef,
                displayName = spec.displayName,
                sha256 = spec.sha256,
                width = spec.width,
                height = spec.height,
                dimForLock = dimDefault,
                sortIndex = 0,
                addedAt = System.currentTimeMillis(),
                framings = framings,
            )
        }

        private data class AssetSpec(
            val sourceType: SourceType,
            val storageRef: String,
            val displayName: String,
            val sha256: String?,
            val width: Int,
            val height: Int,
            val rotationDegrees: Int,
        )

        data class Message(
            val added: Int,
            val skipped: Int,
        )

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
