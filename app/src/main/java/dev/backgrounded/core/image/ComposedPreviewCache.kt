package dev.backgrounded.core.image

import android.graphics.Bitmap
import dev.backgrounded.core.di.ApplicationScope
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.DisplayTarget
import dev.backgrounded.domain.model.WallpaperSurface
import dev.backgrounded.domain.render.BackgroundRenderer
import dev.backgrounded.domain.render.BitmapLoader
import dev.backgrounded.domain.render.ScrollGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ComposedPreviewCache
    @Inject
    constructor(
        private val loader: BitmapLoader,
        private val renderer: BackgroundRenderer,
        private val memory: ImageMemoryBudget,
        private val thumbnails: ThumbnailCache,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private data class Key(
            val background: Background,
            val surface: WallpaperSurface,
            val target: DisplayTarget,
            val width: Int,
        )

        @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
        private class Entry(val key: Key, val state: MutableStateFlow<Bitmap?> = MutableStateFlow(null)) :
            StateFlow<Bitmap?> by state {
            var loading = false
            var failedAt = 0L
        }

        private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
        private val live = mutableListOf<WeakReference<Entry>>()
        private val worker = Semaphore(1)
        private var epoch = 0L
        private var pending = 0

        init {
            scope.launch {
                var first = true
                thumbnails.version.collect {
                    if (first) {
                        first = false
                        return@collect
                    }
                    synchronized(this@ComposedPreviewCache) {
                        epoch++
                        entries.clear()
                        live.removeAll { it.get() == null }
                        live.mapNotNull { it.get() }.filter { it.state.value == null }.forEach {
                            it.failedAt = 0
                            request(it)
                        }
                    }
                }
            }
        }

        @Synchronized
        fun observe(
            background: Background,
            surface: WallpaperSurface,
            target: DisplayTarget,
            aspect: Float,
        ): StateFlow<Bitmap?> {
            val key = Key(background, surface, target, (256 * aspect).toInt().coerceIn(1, 1024))
            val entry = entries.getOrPut(key) { Entry(key).also { live.add(WeakReference(it)) } }
            live.removeAll { it.get() == null }
            request(entry)
            trim()
            return entry
        }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private fun request(entry: Entry) {
            if (entry.loading || entry.state.value != null) return
            if (entry.failedAt != 0L && android.os.SystemClock.elapsedRealtime() - entry.failedAt < 60000) return
            if (pending >= 128) {
                entry.failedAt = android.os.SystemClock.elapsedRealtime()
                return
            }
            pending++
            entry.loading = true
            val generation = epoch
            scope.launch(Dispatchers.Default) {
                var source: Bitmap? = null
                var preview: Bitmap? = null
                try {
                    worker.withPermit {
                        if (generation != epoch) return@withPermit
                        val key = entry.key
                        source = loader.decodeThumbnail(key.background, 512) ?: return@withPermit
                        val framing = key.background.framingFor(key.target, key.surface)
                        preview =
                            renderer.renderWindow(
                                framing, requireNotNull(source),
                                BackgroundRenderer.Viewport(
                                    key.width,
                                    256,
                                ),
                                ScrollGeometry.scrollFor(
                                    framing,
                                    key.width,
                                ),
                                0.5f, key.background.dimForLock && key.surface == WallpaperSurface.LOCK,
                                key.surface == WallpaperSurface.HOME,
                            )
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: OutOfMemoryError) {
                    preview = null
                } catch (failure: Exception) {
                    preview = null
                } finally {
                    source?.recycle()
                    synchronized(this@ComposedPreviewCache) {
                        pending--
                        entry.loading = false
                        if (generation == epoch) {
                            entry.state.value = preview
                            if (preview == null) entry.failedAt = android.os.SystemClock.elapsedRealtime()
                        }
                        if (generation != epoch && entry.state.subscriptionCount.value > 0) request(entry)
                        trim()
                    }
                }
            }
        }

        private fun trim() {
            while (entries.size > 128 || entries.values.sumOf {
                    it.state.value?.allocationByteCount?.toLong() ?: 0
                } > memory.cacheBytes
            ) {
                entries.entries.iterator().apply {
                    next()
                    remove()
                }
            }
        }
    }
