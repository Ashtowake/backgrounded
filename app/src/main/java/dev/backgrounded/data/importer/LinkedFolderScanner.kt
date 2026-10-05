package dev.backgrounded.data.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.core.security.EncryptedImageStore
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.db.LinkedFolderEntity
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("LongParameterList")
class LinkedFolderScanner
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: BackgroundedDatabase,
        private val imageImporter: ImageImporter,
        private val albumRepository: AlbumRepository,
        private val settingsStore: SettingsStore,
        private val folders: ManagedFolderStore,
        private val sourceMover: ManagedSourceMover,
        private val encryptedImages: EncryptedImageStore,
    ) {
        private val mutex = Mutex()

        suspend fun link(
            albumId: Long,
            treeUri: Uri,
        ): Int {
            if (!folders.grant(treeUri)) return 0
            db.linkedFolderDao().insert(LinkedFolderEntity(albumId = albumId, treeUri = treeUri.toString()))
            return scan(albumId)
        }

        suspend fun scan(albumId: Long): Int =
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    val settings = settingsStore.settings.first()
                    val dim = settings.lockDimDefault
                    val hidden = db.albumDao().get(albumId)?.isHidden == true
                    val existing = db.backgroundDao().listForAlbum(albumId).map { it.storageRef }.toMutableSet()
                    val movedDocuments = db.managedSourceDao().forAlbum(albumId).map { it.documentId }.toSet()
                    var added = 0
                    db.linkedFolderDao().forAlbum(albumId).forEach { folder ->
                        val treeUri = Uri.parse(folder.treeUri)
                        SafFolders.listImages(context, treeUri).forEach { uri ->
                            if (uri.toString() in existing) return@forEach
                            if (runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() in movedDocuments) {
                                return@forEach
                            }
                            val image = imageImporter.link(uri) ?: return@forEach
                            val hash =
                                runCatching {
                                    val digest = MessageDigest.getInstance("SHA-256")
                                    context.contentResolver.openInputStream(uri)?.use { stream ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            val read = stream.read(buffer)
                                            if (read < 0) break
                                            digest.update(buffer, 0, read)
                                        }
                                        digest.digest().joinToString("") { "%02x".format(it) }
                                    }
                                }.getOrNull() ?: return@forEach
                            if (db.backgroundDao().findByHash(hash) != null) return@forEach
                            val pairId =
                                albumRepository.addPair(
                                    albumId,
                                    Background(
                                        id = 0,
                                        albumId = albumId,
                                        sourceType = SourceType.SAF_LINK,
                                        storageRef = image.uri,
                                        displayName = image.displayName,
                                        sha256 = hash,
                                        width = image.width,
                                        height = image.height,
                                        dimForLock = dim,
                                        sortIndex = 0,
                                        addedAt = System.currentTimeMillis(),
                                        framings =
                                            Background.defaultFramings().mapValues { (_, framing) ->
                                                framing.copy(rotationDegrees = image.orientationDegrees)
                                            },
                                    ),
                                )
                            val assetId = db.pairDao().get(pairId)?.homeBackgroundId
                            if (assetId != null && hidden) {
                                if (settings.hideSourcesSystemwide) sourceMover.move(assetId, treeUri, uri)
                                if (settings.encryptHidden) encryptedImages.encryptAsset(assetId)
                            }
                            existing += uri.toString()
                            added++
                        }
                        db.linkedFolderDao().markScanned(folder.id, System.currentTimeMillis())
                    }
                    added
                }
            }
    }
