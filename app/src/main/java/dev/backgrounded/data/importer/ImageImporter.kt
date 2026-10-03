package dev.backgrounded.data.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
    ) {
        suspend fun import(uri: Uri): ImportedImage? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val digest = MessageDigest.getInstance("SHA-256")
                    val temp = File(imageStore.imagesDir, "import-${UUID.randomUUID()}.tmp")
                    resolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> copyWithDigest(input, output, digest) }
                    } ?: return@runCatching null
                    val hash = digest.digest().toHex()
                    val extension = extensionFor(resolver.getType(uri))
                    val target = File(imageStore.imagesDir, "$hash.$extension")
                    if (target.exists()) {
                        temp.delete()
                    } else if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                    val bounds = ImageStore.readDimensions(target)
                    ImportedImage(
                        filePath = target.absolutePath,
                        sha256 = hash,
                        width = bounds.first,
                        height = bounds.second,
                        orientationDegrees = ImageStore.readOrientationDegrees(target),
                        displayName = displayName(uri) ?: target.name,
                    )
                }.getOrNull()
            }

        suspend fun link(uri: Uri): LinkedImage? =
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
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

        private fun copyWithDigest(
            input: java.io.InputStream,
            output: java.io.OutputStream,
            digest: MessageDigest,
        ) {
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
            }
        }

        private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

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
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
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
