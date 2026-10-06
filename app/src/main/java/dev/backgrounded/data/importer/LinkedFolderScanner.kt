package dev.backgrounded.data.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.core.security.EncryptedImageStore
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.db.LinkedFolderEntity
import dev.backgrounded.data.db.ScanDocumentEntity
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        private val vault: dev.backgrounded.core.security.PinVault,
    ) {
        private val mutex = Mutex()

        private data class FailedScan(val retryAt: Long, val nextDelaySeconds: Long)

        private val failedScans = LinkedHashMap<Long, FailedScan>(16, 0.75f, true)

        suspend fun link(
            albumId: Long,
            treeUri: Uri,
        ): Int {
            val before = db.backgroundDao().countForAlbum(albumId)
            if (!folders.grant(treeUri)) return 0
            db.linkedFolderDao().insert(LinkedFolderEntity(albumId = albumId, treeUri = treeUri.toString()))
            scan(albumId, force = true)
            return (db.backgroundDao().countForAlbum(albumId) - before).coerceAtLeast(0)
        }

        @Suppress("CyclomaticComplexMethod", "ComplexCondition", "LongMethod", "TooGenericExceptionCaught")
        suspend fun scan(
            albumId: Long,
            force: Boolean = false,
        ): Int =
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    val settings = settingsStore.settings.first()
                    val dim = settings.lockDimDefault
                    val hidden = db.albumDao().get(albumId)?.isHidden == true
                    if (hidden && settings.encryptHidden && !vault.unlocked()) return@withContext 0
                    val existing = db.backgroundDao().listForAlbum(albumId).map { it.storageRef }.toMutableSet()
                    val hashes = db.backgroundDao().listForAlbum(albumId).mapNotNull { it.sha256 }.toMutableSet()
                    val movedDocuments = db.managedSourceDao().forAlbum(albumId).map { it.documentId }.toSet()
                    var added = 0
                    db.linkedFolderDao().forAlbum(albumId).forEach { folder ->
                        val retryAt = failedScans[folder.id]?.retryAt ?: 0L
                        if (!force && retryAt > android.os.SystemClock.elapsedRealtime()) {
                            return@forEach
                        }
                        if (!force && settings.folderScanSeconds > 0 &&
                            System.currentTimeMillis() - folder.lastScanAt in 0 until settings.folderScanSeconds * 1000L
                        ) {
                            return@forEach
                        }
                        val treeUri = Uri.parse(folder.treeUri)
                        try {
                            SafFolders.forEachImage(context, treeUri) document@{ document ->
                                val uri = document.uri
                                val previous = db.hardeningDao().document(folder.id, document.id)
                                if (document.size >= 0 && document.modifiedAt > 0 &&
                                    previous?.size == document.size && previous.modifiedAt == document.modifiedAt &&
                                    previous.status in setOf("ADDED", "DUPLICATE", "MOVED")
                                ) {
                                    return@document
                                }

                                suspend fun record(status: String) {
                                    db.hardeningDao().recordDocument(
                                        ScanDocumentEntity(
                                            folder.id,
                                            document.id,
                                            document.size,
                                            document.modifiedAt,
                                            status,
                                        ),
                                    )
                                }

                                val movedId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                                if (movedId in movedDocuments) {
                                    record("MOVED")
                                    return@document
                                }
                                val image = imageImporter.link(uri) ?: return@document
                                val hash =
                                    runCatching {
                                        val digest = MessageDigest.getInstance("SHA-256")
                                        context.contentResolver.openInputStream(uri)?.use { stream ->
                                            val buffer = ByteArray(64 * 1024)
                                            while (true) {
                                                currentCoroutineContext().ensureActive()
                                                val read = stream.read(buffer)
                                                if (read < 0) break
                                                digest.update(buffer, 0, read)
                                            }
                                            digest.digest().joinToString("") { "%02x".format(it) }
                                        }
                                    }.getOrElse {
                                        if (it is CancellationException) throw it
                                        null
                                    } ?: return@document
                                if (uri.toString() in existing) {
                                    db.backgroundDao().listForAlbum(albumId).filter { it.storageRef == uri.toString() }
                                        .forEach { asset ->
                                            db.backgroundDao().update(
                                                asset.copy(
                                                    sha256 = hash,
                                                    width = image.width,
                                                    height = image.height,
                                                ),
                                            )
                                        }
                                    hashes += hash
                                    record("ADDED")
                                    return@document
                                }
                                if (hash in hashes) {
                                    record("DUPLICATE")
                                    return@document
                                }
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
                                hashes += hash
                                record("ADDED")
                                existing += uri.toString()
                                added++
                            }
                            db.linkedFolderDao().markScanned(folder.id, System.currentTimeMillis())
                            failedScans.remove(folder.id)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            val delay = failedScans[folder.id]?.nextDelaySeconds ?: 60L
                            failedScans[folder.id] =
                                FailedScan(
                                    android.os.SystemClock.elapsedRealtime() + delay * 1000,
                                    (delay * 2).coerceAtMost(900),
                                )
                            while (failedScans.size > 256) failedScans.entries.iterator().apply {
                                next()
                                remove()
                            }
                            settingsStore.setLastError("Folder unavailable: ${failure.message ?: folder.treeUri}")
                        }
                    }
                    added
                }
            }
    }
