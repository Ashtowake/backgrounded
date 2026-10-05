package dev.backgrounded.data.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.db.BackgroundEntity
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.importer.ImageImporter
import dev.backgrounded.data.importer.ManagedFolderStore
import dev.backgrounded.data.importer.SafFolders
import dev.backgrounded.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class ImageRecoveryResult(val restored: Int, val missing: Int)

/** Reconnect missing private copies to exact originals without trusting filenames or altering originals. */
@Singleton
class ImageRecovery
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: BackgroundedDatabase,
        private val importer: ImageImporter,
        private val folders: ManagedFolderStore,
        private val settingsStore: SettingsStore,
    ) {
        private val mutex = Mutex()

        suspend fun missingCount(): Int = withContext(Dispatchers.IO) { missingImages().size }

        suspend fun restore(tree: Uri? = null): ImageRecoveryResult =
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    val pending = pendingImages()
                    var restored = 0
                    val trees = tree?.let { listOf(it) } ?: folders.folders.value.map(Uri::parse)
                    for (folder in trees) {
                        for (uri in SafFolders.listImages(context, folder, recursive = true)) {
                            restored += restoreCandidate(pending) { context.contentResolver.openInputStream(uri) }
                            if (pending.isEmpty()) break
                        }
                    }
                    val sharedRoot =
                        if (tree == null) {
                            runCatching {
                                Environment.getExternalStorageDirectory().canonicalFile
                                    .takeIf { Environment.isExternalStorageManager() }
                            }.getOrNull()
                        } else {
                            null
                        }
                    if (sharedRoot != null && pending.isNotEmpty()) {
                        val root = sharedRoot
                        val files = root.walkTopDown().onEnter { it.name != "Android" }
                        restored += restoreFiles(pending, files.filter { it.isFile && isImage(it) })
                    }
                    ImageRecoveryResult(restored, missingImages().size)
                }
            }

        internal suspend fun restoreFiles(files: Sequence<File>): ImageRecoveryResult =
            withContext(Dispatchers.IO) {
                val restored = restoreFiles(pendingImages(), files)
                ImageRecoveryResult(restored, missingImages().size)
            }

        private suspend fun restoreFiles(
            pending: MutableMap<String, List<BackgroundEntity>>,
            files: Sequence<File>,
        ): Int {
            var restored = 0
            for (file in files) {
                if (pending.isEmpty()) break
                restored += restoreCandidate(pending) { file.inputStream() }
            }
            return restored
        }

        @Suppress("ReturnCount")
        private suspend fun restoreCandidate(
            pending: MutableMap<String, List<BackgroundEntity>>,
            open: () -> InputStream?,
        ): Int {
            if (pending.isEmpty()) return 0
            val hash = runCatching { open()?.use(::sha256) }.getOrNull() ?: return 0
            val matches = pending[hash] ?: return 0
            val imported =
                runCatching {
                    open()?.let { importer.importStream(it, matches.first().displayName) }
                }.getOrNull() ?: return 0
            if (imported.sha256 != hash || imported.width <= 0 || imported.height <= 0) return 0
            val restored =
                database.withTransaction {
                    matches.count { previous ->
                        val current = database.backgroundDao().get(previous.id)
                        if (current == previous) {
                            database.backgroundDao().update(
                                current.copy(sourceType = SourceType.IMPORT.name, storageRef = imported.filePath),
                            )
                            true
                        } else {
                            false
                        }
                    }
                }
            pending.remove(hash)
            return restored
        }

        private suspend fun pendingImages(): MutableMap<String, List<BackgroundEntity>> {
            val protectedAlbums =
                if (settingsStore.settings.first().encryptHidden) {
                    database.albumDao().observeAll().first().filter { it.isHidden }.map { it.id }.toSet()
                } else {
                    emptySet()
                }
            return missingImages().filter { it.sha256 != null && it.albumId !in protectedAlbums }
                .groupBy { it.sha256!!.lowercase() }.toMutableMap()
        }

        private suspend fun missingImages(): List<BackgroundEntity> =
            database.backgroundDao().listAll().filter { image ->
                when (SourceType.from(image.sourceType)) {
                    SourceType.IMPORT -> !File(image.storageRef).isFile
                    SourceType.ENCRYPTED_IMPORT ->
                        !File(image.storageRef).isFile || database.encryptedAssetDao().get(image.id) == null
                    SourceType.SAF_LINK ->
                        !runCatching {
                            context.contentResolver.openAssetFileDescriptor(Uri.parse(image.storageRef), "r")
                                ?.use { true } ?: false
                        }.getOrDefault(false)
                }
            }

        private fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun isImage(file: File): Boolean =
            file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "gif")
    }
