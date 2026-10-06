package dev.backgrounded.data.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.db.OperationJournalEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ImportedImage(
    val filePath: String,
    val sha256: String,
    val width: Int,
    val height: Int,
    val orientationDegrees: Int,
    val displayName: String,
)

data class LinkedImage(
    val uri: String,
    val width: Int,
    val height: Int,
    val orientationDegrees: Int,
    val displayName: String,
)

@Singleton
class ImageImporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val imageStore: ImageStore,
        private val db: BackgroundedDatabase,
        private val recovery: OperationRecovery? = null,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun importStream(
            input: InputStream,
            displayName: String,
        ): ImportedImage? =
            withContext(Dispatchers.IO) {
                recovery?.recover()
                val id = "import-${UUID.randomUUID()}"
                val temp = File(imageStore.imagesDir, "$id.tmp")
                var operation =
                    OperationJournalEntity(
                        id, "IMPORT", "COPYING", null, displayName,
                        temp.absolutePath, null, null, null, System.currentTimeMillis(),
                    )
                db.hardeningDao().recordOperation(operation)
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    input.use { stream ->
                        temp.outputStream().use { output ->
                            copyWithDigest(stream, output, digest)
                            output.fd.sync()
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    val hash = digest.digest().toHex()
                    val extension =
                        displayName.substringAfterLast('.', "jpg").lowercase()
                            .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "gif") }
                            ?: "jpg"
                    val canonical = File(imageStore.imagesDir, "$hash.$extension")
                    val target =
                        if (canonical.exists() && canonical.sha256() != hash) {
                            File(imageStore.imagesDir, "$hash-${UUID.randomUUID()}.$extension")
                        } else {
                            canonical
                        }
                    val bounds = ImageStore.readDimensions(temp)
                    require(bounds.first > 0 && bounds.second > 0)
                    require(temp.sha256() == hash)
                    operation = operation.copy(stage = "VERIFIED", destinationRef = target.absolutePath, sha256 = hash)
                    db.hardeningDao().recordOperation(operation)
                    if (target.isFile && target.sha256() == hash) {
                        temp.delete()
                    } else {
                        check(temp.renameTo(target)) { "Could not publish image copy" }
                    }
                    val result =
                        ImportedImage(
                            target.absolutePath,
                            hash,
                            bounds.first,
                            bounds.second,
                            ImageStore.readOrientationDegrees(target),
                            displayName,
                        )
                    db.hardeningDao().finishOperation(id)
                    result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    null
                } finally {
                    // Keep a verified unpublished copy for recovery if publication was interrupted.
                    if (operation.stage == "COPYING") {
                        temp.delete()
                        db.hardeningDao().finishOperation(id)
                    }
                }
            }

        suspend fun import(uri: Uri): ImportedImage? =
            withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
                importStream(input, displayName(uri) ?: "image.${extensionFor(context.contentResolver.getType(uri))}")
            }

        suspend fun link(uri: Uri): LinkedImage? =
            withContext(Dispatchers.IO) {
                runCatching {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    val bounds = ImageStore.readDimensions(context, uri)
                    LinkedImage(
                        uri = uri.toString(),
                        width = bounds.first,
                        height = bounds.second,
                        orientationDegrees = ImageStore.readOrientationDegrees(context, uri),
                        displayName = displayName(uri) ?: uri.lastPathSegment.orEmpty(),
                    )
                }.getOrNull()
            }

        private fun displayName(uri: Uri): String? =
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }

        private fun extensionFor(mimeType: String?): String =
            when (mimeType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heic", "image/heif" -> "heic"
                "image/avif" -> "avif"
                "image/gif" -> "gif"
                else -> "jpg"
            }

        private suspend fun copyWithDigest(
            input: java.io.InputStream,
            output: java.io.OutputStream,
            digest: MessageDigest,
        ) {
            val buffer = ByteArray(BUFFER_SIZE)
            var bytes = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                bytes += read
                require(bytes <= dev.backgrounded.core.security.EncryptedFileCodec.MAX_BYTES) { "Image exceeds 64 MiB" }
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
            }
        }

        private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

        private fun File.sha256(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }

        private companion object {
            const val BUFFER_SIZE = 64 * 1024
        }
    }

@Singleton
class ImageStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        val imagesDir: File
            get() = File(context.filesDir, "images").apply { mkdirs() }

        suspend fun deleteIfUnreferenced(
            storageRef: String,
            referenced: Collection<String>,
        ) {
            if (storageRef in referenced) return
            withContext(Dispatchers.IO) {
                val file = storageRef.toFileOrNull() ?: return@withContext
                if (file.parentFile == imagesDir && file.exists()) file.delete()
            }
        }

        companion object {
            fun readDimensions(file: File): Pair<Int, Int> {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                file.inputStream().use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                return options.outWidth to options.outHeight
            }

            fun readDimensions(
                context: Context,
                uri: Uri,
            ): Pair<Int, Int> {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, options)
                }
                return options.outWidth to options.outHeight
            }

            fun readOrientationDegrees(file: File): Int =
                runCatching {
                    val exif = androidx.exifinterface.media.ExifInterface(file)
                    val orientation =
                        exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)
                    exifOrientationToDegrees(orientation)
                }.getOrDefault(0)

            fun readOrientationDegrees(
                context: Context,
                uri: Uri,
            ): Int =
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val exif = androidx.exifinterface.media.ExifInterface(stream)
                        val orientation =
                            exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)
                        exifOrientationToDegrees(orientation)
                    } ?: 0
                }.getOrDefault(0)

            private fun exifOrientationToDegrees(orientation: Int): Int =
                when (orientation) {
                    6 -> 90
                    3 -> 180
                    8 -> 270
                    else -> 0
                }

            private fun String.toFileOrNull(): File? = runCatching { File(this) }.getOrNull()
        }
    }
