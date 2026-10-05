package dev.backgrounded.ui.editor

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.backgrounded.core.display.DisplayRepository
import dev.backgrounded.core.display.DisplayTargetInfo
import dev.backgrounded.core.image.ThumbnailCache
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.BackdropType
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.BackgroundPair
import dev.backgrounded.domain.model.DisplayTarget
import dev.backgrounded.domain.model.FitMode
import dev.backgrounded.domain.model.Framing
import dev.backgrounded.domain.model.FramingKey
import dev.backgrounded.domain.model.ScrollMode
import dev.backgrounded.domain.model.WallpaperSurface
import dev.backgrounded.domain.render.BackgroundRenderer
import dev.backgrounded.domain.render.BitmapLoader
import dev.backgrounded.domain.render.FillAligner
import dev.backgrounded.domain.render.FitGeometry
import dev.backgrounded.domain.render.GestureTransform
import dev.backgrounded.domain.render.ScrollGeometry
import dev.backgrounded.ui.nav.EditorRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class EditorUiState(
    val pair: BackgroundPair? = null,
    val targets: List<DisplayTargetInfo> = emptyList(),
    val expandedKey: FramingKey? = null,
    val scrollPreview: Float = 0.5f,
    val previews: Map<FramingKey, Bitmap> = emptyMap(),
    val scrollStartPreview: Bitmap? = null,
    val scrollEndPreview: Bitmap? = null,
)

@HiltViewModel
class EditorViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val albumRepository: AlbumRepository,
        private val bitmapLoader: BitmapLoader,
        private val backgroundRenderer: BackgroundRenderer,
        private val thumbnailCache: ThumbnailCache,
        private val displayRepository: DisplayRepository,
    ) : ViewModel() {
        private val pairId: Long = savedStateHandle.toRoute<EditorRoute>().pairId

        private val mutableState = MutableStateFlow(EditorUiState())
        val state: StateFlow<EditorUiState> = mutableState.asStateFlow()

        private val renderRequests = Channel<Unit>(Channel.CONFLATED)
        private var viewportWidth = 0
        private var viewportHeight = 0
        private var cachedSource: Bitmap? = null
        private var cachedSourceKey: String? = null

        init {
            viewModelScope.launch {
                val targets = displayRepository.targets()
                val pair = albumRepository.resolvedPair(pairId)
                mutableState.update { it.copy(pair = pair, targets = targets) }
                renderRequests.trySend(Unit)
            }
            viewModelScope.launch {
                renderRequests.consumeAsFlow().collect {
                    renderPreviews()
                }
            }
        }

        fun refreshDisplayTargets() {
            val targets = displayRepository.targets()
            if (mutableState.value.targets == targets) return
            mutableState.update { it.copy(targets = targets) }
            renderRequests.trySend(Unit)
        }

        fun setViewport(
            widthPx: Int,
            heightPx: Int,
        ) {
            if (widthPx == viewportWidth && heightPx == viewportHeight) return
            viewportWidth = widthPx
            viewportHeight = heightPx
            renderRequests.trySend(Unit)
        }

        fun requestRender() {
            renderRequests.trySend(Unit)
        }

        fun expand(key: FramingKey) {
            mutableState.update { it.copy(expandedKey = key) }
            renderRequests.trySend(Unit)
        }

        fun collapse() {
            mutableState.update { it.copy(expandedKey = null) }
            renderRequests.trySend(Unit)
        }

        fun panBy(
            deltaX: Float,
            deltaY: Float,
        ) = updateFraming { framing ->
            framing.copy(
                panX = (framing.panX + deltaX).coerceIn(-MAX_PAN, MAX_PAN),
                panY = (framing.panY + deltaY).coerceIn(-MAX_PAN, MAX_PAN),
            )
        }

        fun setFitMode(mode: FitMode) = updateFraming { it.copy(fitMode = mode) }

        fun setBackdropType(type: BackdropType) = updateFraming { it.copy(backdrop = type) }

        fun setBlurIntensity(intensity: Int) = updateFraming { it.copy(blurIntensity = intensity.coerceIn(0, 100)) }

        fun setBackdropColor(color: Int) = updateFraming { it.copy(backdropColor = color) }

        fun setMirrorX(enabled: Boolean) = updateFraming { it.copy(mirrorX = enabled) }

        fun setMirrorY(enabled: Boolean) = updateFraming { it.copy(mirrorY = enabled) }

        fun setScrollEnabled(enabled: Boolean) =
            updateFraming { framing ->
                if (enabled) {
                    framing.copy(
                        scrollMode = ScrollMode.CUSTOM,
                        scrollAmountPercent = FULL_SLACK_PERCENT,
                        scrollStartFraction =
                            framing.scrollStartFraction.coerceIn(0f, 1f - ScrollGeometry.MIN_SPAN),
                        scrollSpanFraction =
                            framing.scrollSpanFraction.coerceIn(ScrollGeometry.MIN_SPAN, 1f),
                    )
                } else {
                    framing.copy(scrollMode = ScrollMode.OFF)
                }
            }

        /** Left edge of the visible window at the first home page, as a fraction of the band. */
        fun setScrollLeft(fraction: Float) =
            updateFraming { framing ->
                val right =
                    (framing.scrollStartFraction + framing.scrollSpanFraction)
                        .coerceIn(ScrollGeometry.MIN_SPAN, 1f)
                val left = fraction.coerceIn(0f, right - ScrollGeometry.MIN_SPAN)
                framing.copy(
                    scrollStartFraction = left,
                    scrollSpanFraction = (right - left).coerceAtLeast(ScrollGeometry.MIN_SPAN),
                )
            }

        /** Right edge of the visible window at the last home page, as a fraction of the band. */
        fun setScrollRight(fraction: Float) =
            updateFraming { framing ->
                val left = framing.scrollStartFraction.coerceIn(0f, 1f - ScrollGeometry.MIN_SPAN)
                val right = fraction.coerceIn(left + ScrollGeometry.MIN_SPAN, 1f)
                framing.copy(
                    scrollStartFraction = left,
                    scrollSpanFraction = right - left,
                )
            }

        fun setStretchX(value: Float) =
            updateFraming {
                it.copy(stretchX = value.coerceIn(FitGeometry.MIN_STRETCH, FitGeometry.MAX_STRETCH))
            }

        fun setStretchY(value: Float) =
            updateFraming {
                it.copy(stretchY = value.coerceIn(FitGeometry.MIN_STRETCH, FitGeometry.MAX_STRETCH))
            }

        fun setGyroParallax(enabled: Boolean) = updateFraming { it.copy(gyroParallax = enabled) }

        fun setGyroIntensity(intensity: Int) =
            updateFraming {
                it.copy(gyroIntensity = intensity.coerceIn(0, 100))
            }

        fun setDimForLock(dim: Boolean) {
            val pair = mutableState.value.pair ?: return
            val lock = pair.lock.copy(dimForLock = dim)
            mutableState.update {
                it.copy(
                    pair =
                        pair.copy(
                            lock = lock,
                            home = if (pair.home.id == pair.lock.id) lock else pair.home,
                        ),
                )
            }
            renderRequests.trySend(Unit)
        }

        private var gestureStart: Framing? = null

        fun beginGesture() {
            val state = mutableState.value
            val pair = state.pair ?: return
            val key = state.expandedKey ?: return
            gestureStart = pair.imageFor(key.surface).framingFor(key.display, key.surface)
        }

        /** Applies the affine mapping the initial fingertip pair onto the current pair. */
        fun updateGesture(
            start: GestureTransform.FingerPair,
            current: GestureTransform.FingerPair,
            frameWidth: Float,
            frameHeight: Float,
        ) {
            val startFraming = gestureStart ?: return
            if (frameWidth <= 0f || frameHeight <= 0f) return
            val state = mutableState.value
            val pair = state.pair ?: return
            val key = state.expandedKey ?: return
            val settled =
                GestureTransform.settle(
                    startFraming = startFraming,
                    gesture =
                        GestureTransform.Gesture(
                            start = start,
                            current = current,
                            frameWidth = frameWidth,
                            frameHeight = frameHeight,
                        ),
                )
            mutableState.update { it.copy(pair = pair.withFraming(key, settled)) }
            renderRequests.trySend(Unit)
        }

        fun endGesture(snap: Boolean) {
            val startFraming = gestureStart ?: return
            gestureStart = null
            if (!snap) return
            val state = mutableState.value
            val pair = state.pair ?: return
            val key = state.expandedKey ?: return
            val current = pair.imageFor(key.surface).framingFor(key.display, key.surface)
            if (current.rotationDegrees != startFraming.rotationDegrees) {
                snapRotation()
            }
        }

        fun snapRotation() = updateFraming { GestureTransform.commitSnap(it) }

        fun transform(
            zoomChange: Float,
            panDeltaX: Float,
            panDeltaY: Float,
        ) = updateFraming { current ->
            current.copy(
                zoom = (current.zoom * zoomChange).coerceIn(FitGeometry.MIN_ZOOM, FitGeometry.MAX_ZOOM),
                panX = (current.panX + panDeltaX).coerceIn(-MAX_PAN, MAX_PAN),
                panY = (current.panY + panDeltaY).coerceIn(-MAX_PAN, MAX_PAN),
            )
        }

        fun transformBackdrop(
            zoomChange: Float,
            panDeltaX: Float,
            panDeltaY: Float,
        ) = updateFraming { current ->
            current.copy(
                backdropZoom =
                    (current.backdropZoom * zoomChange)
                        .coerceIn(MIN_BACKDROP_ZOOM, MAX_BACKDROP_ZOOM),
                backdropPanX = (current.backdropPanX + panDeltaX).coerceIn(-MAX_PAN, MAX_PAN),
                backdropPanY = (current.backdropPanY + panDeltaY).coerceIn(-MAX_PAN, MAX_PAN),
            )
        }

        /** Double tap: align the current placement while retaining manual size and fit mode. */
        fun align() {
            val state = mutableState.value
            val pair = state.pair ?: return
            val key = state.expandedKey ?: return
            if (viewportWidth <= 0 || viewportHeight <= 0) return
            viewModelScope.launch {
                val asset = pair.imageFor(key.surface)
                val framing = asset.framingFor(key.display, key.surface)
                val source = obtainSource(asset) ?: return@launch
                val rotated = framing.rotationDegrees % 180 == 90
                val rotatedWidth = if (rotated) source.height else source.width
                val rotatedHeight = if (rotated) source.width else source.height
                updateFraming { current ->
                    FillAligner.align(
                        framing = current,
                        outWidth = viewportWidth,
                        outHeight = viewportHeight,
                        sourceWidth = rotatedWidth,
                        sourceHeight = rotatedHeight,
                    )
                }
            }
        }

        /** Copies the current placement to the sibling destination of the same screen. */
        fun syncToCounterpart() {
            val state = mutableState.value
            val pair = state.pair ?: return
            val key = state.expandedKey ?: return
            val otherSurface =
                if (key.surface == WallpaperSurface.HOME) WallpaperSurface.LOCK else WallpaperSurface.HOME
            val sourceFraming = pair.imageFor(key.surface).framingFor(key.display, key.surface)
            val targetKey = FramingKey(key.display, otherSurface)
            val target = pair.imageFor(otherSurface)
            val updated = target.copy(framings = target.framings + (targetKey to sourceFraming))
            val sameAsset = pair.home.id == pair.lock.id
            val updatedPair =
                when (otherSurface) {
                    WallpaperSurface.HOME -> pair.copy(home = updated, lock = if (sameAsset) updated else pair.lock)
                    WallpaperSurface.LOCK -> pair.copy(lock = updated, home = if (sameAsset) updated else pair.home)
                }
            mutableState.update { it.copy(pair = updatedPair) }
            renderRequests.trySend(Unit)
        }

        fun reset() {
            val state = mutableState.value
            val pair = state.pair ?: return
            val key = state.expandedKey ?: return
            val framing = Background.defaultFramings()[key] ?: Framing.DEFAULT
            mutableState.update { it.copy(pair = pair.withFraming(key, framing)) }
            renderRequests.trySend(Unit)
        }

        fun save(onSaved: () -> Unit) {
            val pair = mutableState.value.pair ?: return
            viewModelScope.launch {
                albumRepository.updateAsset(pair.home)
                if (pair.lock.id != pair.home.id) {
                    albumRepository.updateAsset(pair.lock)
                }
                val reloaded = albumRepository.resolvedPair(pairId)
                if (reloaded != null) {
                    mutableState.update { it.copy(pair = reloaded) }
                }
                thumbnailCache.evictAll()
                renderRequests.trySend(Unit)
                onSaved()
            }
        }

        private fun updateFraming(transform: (Framing) -> Framing) {
            val state = mutableState.value
            val pair = state.pair ?: return
            val key = state.expandedKey ?: return
            val framing = transform(pair.imageFor(key.surface).framingFor(key.display, key.surface))
            mutableState.update { it.copy(pair = pair.withFraming(key, framing)) }
            renderRequests.trySend(Unit)
        }

        private fun BackgroundPair.withFraming(
            key: FramingKey,
            framing: Framing,
        ): BackgroundPair {
            fun update(asset: Background): Background = asset.copy(framings = asset.framings + (key to framing))

            val target = imageFor(key.surface)
            val updated = update(target)
            val sameAsset = home.id == lock.id
            return when (key.surface) {
                WallpaperSurface.HOME -> copy(home = updated, lock = if (sameAsset) updated else lock)
                WallpaperSurface.LOCK -> copy(lock = updated, home = if (sameAsset) updated else home)
            }
        }

        private fun previewKeys(state: EditorUiState): List<FramingKey> {
            state.expandedKey?.let { return listOf(it) }
            if (state.targets.isEmpty()) return Background.keys()
            return state.targets.flatMap { info ->
                WallpaperSurface.entries.map { surface -> FramingKey(info.target, surface) }
            }
        }

        private suspend fun renderPreviews() {
            val state = mutableState.value
            val pair = state.pair ?: return
            if (viewportHeight <= 0) return
            val height = viewportHeight.coerceAtMost(PREVIEW_MAX_HEIGHT_PX).coerceAtLeast(MIN_PREVIEW_PX)
            val rendered =
                withContext(Dispatchers.Default) {
                    val previews =
                        previewKeys(state).associateWith { key ->
                            val asset = pair.imageFor(key.surface)
                            val source = obtainSource(asset) ?: return@associateWith null
                            val width = (height * aspectOf(state, key.display)).toInt().coerceAtLeast(MIN_PREVIEW_PX)
                            val framing = asset.framingFor(key.display, key.surface)
                            val scroll = ScrollGeometry.scrollFor(framing, width)
                            backgroundRenderer.renderWindow(
                                framing = framing,
                                source = source,
                                viewport = BackgroundRenderer.Viewport(width, height),
                                scroll = scroll,
                                scrollOffset = state.scrollPreview,
                                dim = asset.dimForLock && key.surface == WallpaperSurface.LOCK,
                                allowScroll = key.surface == WallpaperSurface.HOME,
                            )
                        }.filterValues { it != null }.mapValues { entry -> entry.value!! }

                    var startPreview: Bitmap? = null
                    var endPreview: Bitmap? = null
                    val expanded = state.expandedKey
                    if (expanded != null && expanded.surface == WallpaperSurface.HOME) {
                        val asset = pair.imageFor(expanded.surface)
                        val framing = asset.framingFor(expanded.display, expanded.surface)
                        if (framing.scrollMode != ScrollMode.OFF) {
                            val source = obtainSource(asset)
                            if (source != null) {
                                val smallHeight = (height / 2).coerceAtLeast(MIN_PREVIEW_PX)
                                val smallWidth =
                                    (smallHeight * aspectOf(state, expanded.display))
                                        .toInt()
                                        .coerceAtLeast(MIN_PREVIEW_PX)
                                val smallViewport = BackgroundRenderer.Viewport(smallWidth, smallHeight)
                                val scroll = ScrollGeometry.scrollFor(framing, smallWidth)
                                startPreview =
                                    backgroundRenderer.renderWindow(
                                        framing = framing,
                                        source = source,
                                        viewport = smallViewport,
                                        scroll = scroll,
                                        scrollOffset = 0f,
                                        dim = false,
                                        allowScroll = true,
                                    )
                                endPreview =
                                    backgroundRenderer.renderWindow(
                                        framing = framing,
                                        source = source,
                                        viewport = smallViewport,
                                        scroll = scroll,
                                        scrollOffset = 1f,
                                        dim = false,
                                        allowScroll = true,
                                    )
                            }
                        }
                    }
                    Triple(previews, startPreview, endPreview)
                }
            mutableState.update {
                it.copy(
                    previews = rendered.first,
                    scrollStartPreview = rendered.second,
                    scrollEndPreview = rendered.third,
                )
            }
        }

        private suspend fun obtainSource(asset: Background): Bitmap? {
            val key = "${asset.id}:${asset.storageRef}"
            val cached = cachedSource
            if (cached != null && cachedSourceKey == key && !cached.isRecycled) return cached
            cached?.recycle()
            val decoded = bitmapLoader.decode(asset, MAX_SOURCE_DIMENSION, MAX_SOURCE_DIMENSION)
            cachedSource = decoded
            cachedSourceKey = if (decoded != null) key else null
            return decoded
        }

        private fun aspectOf(
            state: EditorUiState,
            display: DisplayTarget,
        ): Float {
            val info = state.targets.firstOrNull { it.target == display }
            if (info == null || info.width <= 0 || info.height <= 0) return DEFAULT_ASPECT
            return info.width.toFloat() / info.height
        }

        override fun onCleared() {
            renderRequests.close()
            cachedSource?.recycle()
            cachedSource = null
            super.onCleared()
        }

        private companion object {
            const val MAX_PAN = 1.5f
            const val MIN_BACKDROP_ZOOM = 0.5f
            const val MAX_BACKDROP_ZOOM = 4f
            const val PREVIEW_MAX_HEIGHT_PX = 1400
            const val MIN_PREVIEW_PX = 64
            const val MAX_SOURCE_DIMENSION = 2048
            const val DEFAULT_ASPECT = 0.462f
            const val FULL_SLACK_PERCENT = 100
        }
    }
