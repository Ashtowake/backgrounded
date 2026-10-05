package dev.backgrounded.widget

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.backgrounded.core.display.DisplayRepository
import dev.backgrounded.core.image.ThumbnailCache
import dev.backgrounded.core.security.PinVault
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Album
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.BackgroundPair
import dev.backgrounded.domain.model.DisplayTarget
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.model.WallpaperSurface
import dev.backgrounded.domain.render.BackgroundRenderer
import dev.backgrounded.domain.render.BitmapLoader
import dev.backgrounded.domain.render.ScrollGeometry
import dev.backgrounded.domain.usecase.ApplyPair
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GalleryPickerState(
    val albums: List<Album> = emptyList(),
    val activeAlbumId: Long? = null,
    val selectedAlbumId: Long? = null,
    val pairs: List<BackgroundPair> = emptyList(),
    val authenticated: Boolean = false,
    val encryptionEnabled: Boolean = false,
) {
    val showHidden: Boolean get() = authenticated || albums.any { it.id == activeAlbumId && it.isHidden }
    val visibleAlbums: List<Album> get() = albums.filter { showHidden || !it.isHidden }
    val selectedAlbum: Album? get() = visibleAlbums.firstOrNull { it.id == selectedAlbumId }
    val visiblePairs: List<BackgroundPair>
        get() = if (selectedAlbum == null) emptyList() else pairs.filter { it.albumId == selectedAlbumId }
}

@HiltViewModel
@Suppress("LongParameterList")
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryPickerViewModel
    @Inject
    constructor(
        private val albumRepository: AlbumRepository,
        private val settingsStore: SettingsStore,
        private val thumbnailCache: ThumbnailCache,
        private val displayRepository: DisplayRepository,
        private val renderer: BackgroundRenderer,
        private val bitmapLoader: BitmapLoader,
        private val pinVault: PinVault,
        private val applyPair: ApplyPair,
    ) : ViewModel() {
        private val selected = MutableStateFlow<Long?>(null)
        private val revealed = MutableStateFlow(false)
        private val mutableError = MutableStateFlow<String?>(null)
        val error = mutableError.asStateFlow()
        private val mutableApplying = MutableStateFlow(false)
        val applying = mutableApplying.asStateFlow()
        val thumbnailVersion = thumbnailCache.version
        private val pairs =
            selected.flatMapLatest { id ->
                id?.let(albumRepository::observeResolvedPairs) ?: flowOf(emptyList())
            }
        val state =
            combine(albumRepository.observeAlbums(), settingsStore.settings, selected, revealed, pairs) {
                    albums, settings, albumId, authenticated, images ->
                GalleryPickerState(
                    albums,
                    settings.activeAlbumId,
                    albumId,
                    images,
                    authenticated,
                    settings.encryptHidden,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryPickerState())

        val albumPreviews =
            state.map { it.visibleAlbums }.distinctUntilChanged().flatMapLatest { albums ->
                if (albums.isEmpty()) {
                    flowOf(emptyMap<Long, BackgroundPair?>())
                } else {
                    combine(
                        albums.map { album ->
                            albumRepository.observeResolvedPairs(album.id).map { images ->
                                album.id to (images.firstOrNull { it.id == album.coverPairId } ?: images.firstOrNull())
                            }
                        },
                    ) { it.toMap() }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

        init {
            viewModelScope.launch { selected.value = settingsStore.settings.first().activeAlbumId }
        }

        fun openAlbum(albumId: Long) {
            if (state.value.visibleAlbums.any { it.id == albumId }) selected.value = albumId
            mutableError.value = null
        }

        fun up() {
            selected.value = null
            mutableError.value = null
        }

        fun revealHidden() {
            revealed.value = true
            thumbnailCache.evictAll()
        }

        fun concealHidden() {
            revealed.value = false
        }

        fun needsUnlock(): Boolean = !pinVault.unlocked()

        fun selectPair(
            pairId: Long,
            onAuthenticationRequired: () -> Unit,
            onApplied: () -> Unit,
        ) {
            if (mutableApplying.value) return
            mutableApplying.value = true
            viewModelScope.launch {
                try {
                    val pair = albumRepository.resolvedPair(pairId) ?: return@launch
                    val settings = settingsStore.settings.first()
                    val album = albumRepository.getAlbum(pair.albumId) ?: return@launch
                    val activeHidden = settings.activeAlbumId?.let { albumRepository.getAlbum(it)?.isHidden } == true
                    if (album.isHidden && !revealed.value && !activeHidden) return@launch
                    if (album.isHidden && settings.encryptHidden && !pinVault.unlocked()) {
                        onAuthenticationRequired()
                    } else if (!bitmapLoader.exists(pair.home) || !bitmapLoader.exists(pair.lock)) {
                        mutableError.value = "Image unavailable. Restore its original or folder access."
                    } else if (applyPair(pairId, Trigger.WIDGET)) {
                        onApplied()
                    }
                } finally {
                    mutableApplying.value = false
                }
            }
        }

        fun activeTarget(): DisplayTarget = displayRepository.activeTarget()

        fun aspect(target: DisplayTarget): Float {
            val info = displayRepository.targets().firstOrNull { it.target == target }
            return if (info != null && info.height > 0) info.width.toFloat() / info.height else 0.96f
        }

        fun preview(
            background: Background,
            surface: WallpaperSurface,
            target: DisplayTarget,
            aspect: Float,
        ): Bitmap? {
            val source = thumbnailCache.get(background, 512) ?: return null
            val framing = background.framingFor(target, surface)
            val width = (256 * aspect).toInt().coerceAtLeast(1)
            return renderer.renderWindow(
                framing,
                source,
                BackgroundRenderer.Viewport(width, 256),
                ScrollGeometry.scrollFor(framing, width),
                scrollOffset = 0.5f,
                dim = background.dimForLock && surface == WallpaperSurface.LOCK,
                allowScroll = surface == WallpaperSurface.HOME,
            )
        }
    }
