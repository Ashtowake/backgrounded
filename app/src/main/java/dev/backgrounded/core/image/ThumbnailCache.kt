package dev.backgrounded.core.image

import android.graphics.Bitmap
import android.util.LruCache
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.render.BitmapLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Small LRU cache for album grid thumbnails. Version counter drives recomposition. */
@Singleton
class ThumbnailCache
    @Inject
    constructor(private val bitmapLoader: BitmapLoader) {
        private val cache = LruCache<String, Bitmap>(MAX_ENTRIES)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val inFlight = mutableSetOf<String>()
        private val mutableVersion = MutableStateFlow(0)
        val version: StateFlow<Int> = mutableVersion.asStateFlow()

        fun get(
            background: Background,
            size: Int,
        ): Bitmap? {
            val key = key(background.id, size)
            val cached = cache.get(key)
            if (cached != null) return cached
            request(background, size, key)
            return null
        }

        fun evictAll() {
            cache.evictAll()
            mutableVersion.value++
        }

        private fun request(
            background: Background,
            size: Int,
            key: String,
        ) {
            synchronized(inFlight) {
                if (key in inFlight) return
                inFlight += key
            }
            scope.launch {
                try {
                    val bitmap = bitmapLoader.decodeThumbnail(background, size)
                    if (bitmap != null) {
                        cache.put(key, bitmap)
                        mutableVersion.value++
                    }
                } finally {
                    synchronized(inFlight) { inFlight -= key }
                }
            }
        }

        private fun key(
            backgroundId: Long,
            size: Int,
        ): String = "$backgroundId@$size"

        private companion object {
            const val MAX_ENTRIES = 24
        }
    }
