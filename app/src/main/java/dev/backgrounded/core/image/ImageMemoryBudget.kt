package dev.backgrounded.core.image

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton

class ImageResourceException(message: String) : RuntimeException(message)

/** Tracks live UI/layer ownership weakly; cache eviction cannot hide a still-displayed bitmap. */
@Singleton
class ImageMemoryBudget
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        val limitBytes = minOf(192L * MIB, context.getSystemService(ActivityManager::class.java).memoryClass * MIB / 2)
        val cacheBytes = minOf(16L * MIB, limitBytes / 8).toInt()
        private val bitmaps = WeakHashMap<Bitmap, Long>()
        private var reservedBytes = 0L

        @Synchronized
        fun remaining(): Long {
            val iterator = bitmaps.entries.iterator()
            var used = reservedBytes
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.isRecycled) iterator.remove() else used += entry.value
            }
            return (limitBytes - used).coerceAtLeast(0)
        }

        @Synchronized
        fun track(bitmap: Bitmap): Bitmap {
            bitmaps[bitmap] = bitmap.allocationByteCount.toLong()
            return bitmap
        }

        @Synchronized
        fun reserve(bytes: Long): Reservation {
            if (bytes <= 0 || bytes > remaining()) throw ImageResourceException("Image memory budget exceeded")
            reservedBytes += bytes
            return Reservation(bytes)
        }

        inner class Reservation(private val bytes: Long) : AutoCloseable {
            private var closed = false

            override fun close() =
                synchronized(this@ImageMemoryBudget) {
                    if (!closed) {
                        reservedBytes -= bytes
                        closed = true
                    }
                }
        }

        @Synchronized
        fun create(
            width: Int,
            height: Int,
        ): Bitmap {
            val pixels = width.toLong() * height
            if (width <= 0 || height <= 0 || pixels > remaining() / 4) {
                throw ImageResourceException(
                    "Image memory budget exceeded",
                )
            }
            return track(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
        }

        companion object {
            private const val MIB = 1024L * 1024
        }
    }
