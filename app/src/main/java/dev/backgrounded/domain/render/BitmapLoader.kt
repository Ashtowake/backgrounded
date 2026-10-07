package dev.backgrounded.domain.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.core.image.ImageMemoryBudget
import dev.backgrounded.core.security.EncryptedFileCodec
import dev.backgrounded.core.security.EncryptedImageStore
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class BitmapLoader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val encryptedImageStore: EncryptedImageStore,
        private val memory: ImageMemoryBudget,
        private val diagnostics: dev.backgrounded.core.diagnostics.LocalDiagnostics,
        private val settings: dev.backgrounded.data.datastore.SettingsStore,
        @dev.backgrounded.core.di.ApplicationScope scope: kotlinx.coroutines.CoroutineScope,
    ) {
        private val decodeGate = dev.backgrounded.core.image.ImageWorkGate()
        private val failures = LinkedHashMap<String, Long>(16, 0.75f, true)

        @Synchronized fun clearFailures() {
            failures.clear()
        }

        @Synchronized private fun failed(background: Background) {
            failures[identity(background)] = android.os.SystemClock.elapsedRealtime()
            while (failures.size > 128) failures.entries.iterator().apply {
                next()
                remove()
            }
        }

        @Synchronized private fun unavailable(background: Background): Boolean {
            val key = identity(background)
            val at = failures[key] ?: return false
            if (android.os.SystemClock.elapsedRealtime() - at < 60000) return true
            failures.remove(key)
            return false
        }

        private fun identity(background: Background): String {
            val revision =
                if (background.sourceType == SourceType.SAF_LINK) {
                    ""
                } else {
                    val file = File(background.storageRef)
                    "${file.length()}:${file.lastModified()}"
                }
            return "${background.id}:${background.storageRef}:${background.sha256}:$revision"
        }

        @Volatile private var diagnosticsEnabled = false

        init {
            scope.launch {
                settings.settings.collect { diagnosticsEnabled = it.debugDiagnostics }
            }
        }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun decode(
            background: Background,
            targetWidth: Int,
            targetHeight: Int,
            wallpaperPriority: Boolean = true,
            editorPreview: Boolean = false,
        ): Bitmap? =
            withContext(Dispatchers.IO) {
                if (unavailable(background)) return@withContext null
                if (background.sourceType == SourceType.ENCRYPTED_IMPORT &&
                    !encryptedImageStore.available(background.id)
                ) {
                    return@withContext null
                }
                diagnostics.count(diagnosticsEnabled, "decode_queued")
                decodeGate.run(wallpaperPriority) {
                    diagnostics.count(diagnosticsEnabled, "decode_started")
                    currentCoroutineContext().ensureActive()
                    var encoded: ImageMemoryBudget.Reservation? = null
                    var pixels: ImageMemoryBudget.Reservation? = null
                    var decoded: Bitmap? = null
                    var plaintext: ByteArray? = null
                    try {
                        val source =
                            if (background.sourceType == SourceType.ENCRYPTED_IMPORT) {
                                val length = EncryptedFileCodec.plaintextLength(File(background.storageRef)).toInt()
                                if (!editorPreview) encoded = memory.reserve(length.toLong() + 1024 * 1024)
                                val bytes = ByteArray(length)
                                plaintext = bytes
                                encryptedImageStore.open(background)?.use { input ->
                                    java.io.DataInputStream(input).readFully(bytes)
                                    require(input.read() == -1)
                                } ?: error("Encrypted image could not be opened")
                                ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                            } else {
                                source(background.storageRef, background.sourceType)
                                    ?: error("Image source unavailable")
                            }
                        decoded =
                            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                val width = info.size.width
                                val height = info.size.height
                                require(width > 0 && height > 0 && targetWidth > 0 && targetHeight > 0)
                                val requested =
                                    minOf(1.0, targetWidth.toDouble() / width, targetHeight.toDouble() / height)
                                // Allow source plus a potentially doubled rotated ARGB bitmap before publication.
                                val maxPixels =
                                    if (editorPreview) width.toDouble() * height else memory.remaining() / 16.0
                                val scale =
                                    minOf(requested, kotlin.math.sqrt(maxPixels / (width.toDouble() * height)))
                                if (!scale.isFinite() || scale <= 0) error("Image memory budget exceeded")
                                val outWidth = max(1, (width * scale).toInt())
                                val outHeight = max(1, (height * scale).toInt())
                                if (!editorPreview) pixels = memory.reserve(outWidth.toLong() * outHeight * 8)
                                decoder.setTargetSize(outWidth, outHeight)
                            }
                        if (!editorPreview) memory.track(requireNotNull(decoded))
                        diagnostics.count(diagnosticsEnabled, "decode_completed")
                        diagnostics.gauge(
                            diagnosticsEnabled,
                            "image_budget_bytes",
                            memory.limitBytes - memory.remaining(),
                        )
                        currentCoroutineContext().ensureActive()
                        decoded
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        decoded?.recycle()
                        throw cancelled
                    } catch (failure: OutOfMemoryError) {
                        decoded?.recycle()
                        failed(background)
                        diagnostics.count(diagnosticsEnabled, "decode_memory_failure")
                        null
                    } catch (failure: Exception) {
                        failed(background)
                        diagnostics.count(diagnosticsEnabled, "decode_failed")
                        decoded?.recycle()
                        null
                    } finally {
                        plaintext?.fill(0)
                        pixels?.close()
                        encoded?.close()
                    }
                }
            }

        suspend fun decodeThumbnail(
            background: Background,
            size: Int,
        ): Bitmap? = decode(background, size, size, wallpaperPriority = false)

        suspend fun exists(background: Background): Boolean =
            withContext(Dispatchers.IO) {
                if (unavailable(background)) return@withContext false
                when (background.sourceType) {
                    SourceType.IMPORT -> File(background.storageRef).isFile
                    SourceType.ENCRYPTED_IMPORT ->
                        File(background.storageRef).isFile && encryptedImageStore.available(background.id)
                    SourceType.SAF_LINK ->
                        runCatching {
                            context.contentResolver.openAssetFileDescriptor(Uri.parse(background.storageRef), "r")
                                ?.use { true } ?: false
                        }.getOrDefault(false)
                }
            }

        private fun source(
            storageRef: String,
            sourceType: SourceType,
        ): ImageDecoder.Source? =
            when (sourceType) {
                SourceType.IMPORT -> File(storageRef).takeIf { it.isFile }?.let { ImageDecoder.createSource(it) }
                SourceType.ENCRYPTED_IMPORT -> null
                SourceType.SAF_LINK ->
                    runCatching {
                        ImageDecoder.createSource(context.contentResolver, Uri.parse(storageRef))
                    }.getOrNull()
            }
    }
