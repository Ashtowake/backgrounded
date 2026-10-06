package dev.backgrounded.core.image

import android.graphics.Bitmap
import dev.backgrounded.core.di.ApplicationScope
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.render.BitmapLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThumbnailCache
    @Inject
    constructor(
        private val bitmapLoader: BitmapLoader,
        private val memory: ImageMemoryBudget,
        private val vault: dev.backgrounded.core.security.PinVault,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
        private class Entry(
            val background: Background,
            val size: Int,
            val image: MutableStateFlow<Bitmap?> = MutableStateFlow(null),
        ) : StateFlow<Bitmap?> by image {
            var loading = false
            var failedAt = 0L
        }

        private val live = mutableListOf<java.lang.ref.WeakReference<Entry>>()
        private var pending = 0

        private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
        private val mutableVersion = MutableStateFlow(0)
        val version: StateFlow<Int> = mutableVersion.asStateFlow()
        private var epoch = 0L

        init {
            scope.launch {
                vault.unlockedState.collect { evictAll() }
            }
        }

        @Synchronized
        fun observe(
            background: Background,
            size: Int,
        ): StateFlow<Bitmap?> {
            val key = "${background.id}:${background.sourceType}:${background.storageRef}:${background.sha256}:$size"
            val entry =
                entries.getOrPut(key) {
                    Entry(background, size).also { live.add(java.lang.ref.WeakReference(it)) }
                }
            live.removeAll { it.get() == null }
            request(entry)
            trim()
            return entry
        }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private fun request(entry: Entry) {
            if (entry.loading || entry.image.value != null) return
            if (entry.failedAt != 0L && android.os.SystemClock.elapsedRealtime() - entry.failedAt < 60000) return
            if (pending >= 128) {
                entry.failedAt = android.os.SystemClock.elapsedRealtime()
                return
            }
            pending++
            entry.loading = true
            val generation = epoch
            scope.launch(Dispatchers.IO) {
                var image: Bitmap? = null
                try {
                    image = bitmapLoader.decodeThumbnail(entry.background, entry.size)
                } catch (
                    cancelled: kotlinx.coroutines.CancellationException,
                ) {
                    throw cancelled
                } catch (failure: Exception) {
                    image = null
                } finally {
                    synchronized(this@ThumbnailCache) {
                        pending--
                        entry.loading = false
                        if (generation == epoch) {
                            entry.image.value = image
                            if (image == null) entry.failedAt = android.os.SystemClock.elapsedRealtime()
                        } else if (entry.image.subscriptionCount.value > 0) {
                            request(entry)
                        }
                        trim()
                    }
                }
            }
        }

        fun get(
            background: Background,
            size: Int,
        ): Bitmap? = observe(background, size).value

        @Synchronized
        fun evictAll() {
            bitmapLoader.clearFailures()
            epoch++
            entries.clear()
            live.removeAll { it.get() == null }
            live.mapNotNull { it.get() }.filter { it.image.value == null }.forEach {
                it.failedAt = 0
                request(it)
            }
            mutableVersion.update { it + 1 }
        }

        private fun trim() {
            while (entries.size > 128 || entries.values.sumOf {
                    it.image.value?.allocationByteCount?.toLong() ?: 0
                } > memory.cacheBytes
            ) {
                entries.entries.iterator().apply {
                    next()
                    remove()
                }
            }
        }
    }
