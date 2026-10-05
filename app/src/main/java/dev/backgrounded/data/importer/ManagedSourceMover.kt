package dev.backgrounded.data.importer

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.core.security.EncryptedImageStore
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.db.ManagedSourceEntity
import dev.backgrounded.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps a verified private copy before deleting any SAF document. */
@Singleton
class ManagedSourceMover
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: BackgroundedDatabase,
        private val importer: ImageImporter,
        private val folders: ManagedFolderStore,
        private val imageStore: ImageStore,
        private val encryptedImages: EncryptedImageStore,
    ) {
        data class MoveResult(
            val moved: Int,
            val unmatched: List<String>,
            val failed: List<String>,
        ) {
            val complete: Boolean get() = unmatched.isEmpty() && failed.isEmpty()
        }

        /** Full access is limited to indexed images on local shared storage. */
        suspend fun moveAlbumSourcesFullAccess(albumId: Long): MoveResult =
            withContext(Dispatchers.IO) {
                if (!Environment.isExternalStorageManager()) {
                    return@withContext MoveResult(0, emptyList(), listOf("Grant all-files access in Settings"))
                }
                val assets =
                    db.backgroundDao().listForAlbum(albumId)
                        .filter { db.managedSourceDao().get(it.id)?.moveState !in COMPLETED_STATES }
                        .distinctBy { if (it.sourceType == SourceType.IMPORT.name) it.storageRef else it.id }
                val candidates =
                    runCatching { localImages() }.getOrElse {
                        return@withContext MoveResult(0, emptyList(), listOf("Could not list local images"))
                    }
                val hashes = mutableMapOf<String, String?>()
                val used = mutableSetOf<String>()
                val matches = mutableListOf<Pair<Long, LocalImage>>()
                val unmatched = mutableListOf<String>()
                assets.forEach { asset ->
                    val expectedLength =
                        if (asset.sourceType == SourceType.IMPORT.name) File(asset.storageRef).length() else null
                    val expectedHash =
                        asset.sha256 ?: if (asset.sourceType == SourceType.SAF_LINK.name) {
                            runCatching {
                                context.contentResolver.openInputStream(Uri.parse(asset.storageRef))?.use(::sha256)
                            }.getOrNull()
                        } else {
                            null
                        }
                    val matching =
                        candidates.filter { candidate ->
                            candidate.file.path !in used &&
                                (expectedLength == null || candidate.file.length() == expectedLength) &&
                                expectedHash != null &&
                                hashes.getOrPut(candidate.file.path) {
                                    runCatching { candidate.file.inputStream().use(::sha256) }.getOrNull()
                                } == expectedHash
                        }
                    if (matching.size == 1) {
                        matches += asset.id to matching.single()
                        used += matching.single().file.path
                    } else {
                        unmatched += asset.displayName
                    }
                }
                if (unmatched.isNotEmpty()) return@withContext MoveResult(0, unmatched, emptyList())
                var moved = 0
                val failed = mutableListOf<String>()
                matches.forEach { (assetId, candidate) ->
                    if (moveFile(assetId, candidate)) moved++ else failed += candidate.file.name
                }
                MoveResult(moved, emptyList(), failed)
            }

        suspend fun moveAssetFromFullAccess(assetId: Long): Boolean =
            withContext(Dispatchers.IO) {
                if (!Environment.isExternalStorageManager()) return@withContext false
                val asset = db.backgroundDao().get(assetId) ?: return@withContext false
                val expectedHash = asset.sha256 ?: return@withContext false
                val expectedLength =
                    if (asset.sourceType == SourceType.IMPORT.name) File(asset.storageRef).length() else null
                val candidates = runCatching { localImages() }.getOrNull() ?: return@withContext false
                val matching =
                    candidates.filter {
                        (expectedLength == null || it.file.length() == expectedLength) &&
                            runCatching { it.file.inputStream().use(::sha256) }.getOrNull() == expectedHash
                    }
                matching.size == 1 && moveFile(assetId, matching.single())
            }

        private data class LocalImage(val file: File, val uri: Uri)

        @Suppress("LoopWithTooManyJumpStatements")
        private fun localImages(): List<LocalImage> {
            val result = mutableListOf<LocalImage>()
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
            context.contentResolver.query(collection, columns, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(pathColumn) ?: continue
                    val file = safeSharedFile(path) ?: continue
                    if (file.isFile) {
                        val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                        result += LocalImage(file, uri)
                    }
                }
            }
            return result
        }

        private fun safeSharedFile(path: String): File? =
            runCatching {
                val root = Environment.getExternalStorageDirectory().canonicalFile
                val file = File(path).canonicalFile
                file.takeIf {
                    it.path.startsWith(root.path + File.separator) &&
                        !it.path.removePrefix(root.path + File.separator).startsWith("Android${File.separator}")
                }
            }.getOrNull()

        @Suppress("ReturnCount")
        private suspend fun moveFile(
            assetId: Long,
            candidate: LocalImage,
        ): Boolean {
            if (!Environment.isExternalStorageManager()) return false
            val source = safeSharedFile(candidate.file.path)?.takeIf { it.isFile } ?: return false
            val asset = db.backgroundDao().get(assetId) ?: return false
            if (asset.sourceType !in SUPPORTED_SOURCE_TYPES) return false
            val originalHash = runCatching { source.inputStream().use(::sha256) }.getOrNull() ?: return false
            if (asset.sha256 != null && asset.sha256 != originalHash) return false
            val imported = if (asset.sourceType == SourceType.SAF_LINK.name) importer.import(candidate.uri) else null
            if (asset.sourceType == SourceType.SAF_LINK.name && imported == null) return false
            val privateFile = File(imported?.filePath ?: asset.storageRef)
            val privateHash =
                if (asset.sourceType == SourceType.ENCRYPTED_IMPORT.name) {
                    encryptedImages.openAsset(assetId)?.use(::sha256)
                } else {
                    privateFile.takeIf { it.isFile }?.inputStream()?.use(::sha256)
                }
            if (privateHash != originalHash) return false
            val record =
                ManagedSourceEntity(assetId, FULL_ACCESS, source.path, source.name, originalHash, "COPY_READY_FILE")
            db.withTransaction {
                db.managedSourceDao().upsert(record)
                if (imported != null) {
                    db.backgroundDao().update(
                        asset.copy(
                            sourceType = SourceType.IMPORT.name,
                            storageRef = imported.filePath,
                            sha256 = originalHash,
                        ),
                    )
                }
            }
            val deleted = runCatching { source.delete() }.getOrDefault(false)
            if (deleted) {
                db.managedSourceDao().upsert(record.copy(moveState = "MOVED_FILE"))
                android.media.MediaScannerConnection.scanFile(context, arrayOf(source.path), null, null)
            }
            return deleted
        }

        /** Matches existing album assets to originals in writable selected folders before deleting anything. */
        suspend fun moveAlbumSources(
            albumId: Long,
            selectedFolder: Uri,
        ): MoveResult =
            withContext(Dispatchers.IO) {
                if (!isLocalFolder(selectedFolder) || !folders.grant(selectedFolder)) {
                    return@withContext MoveResult(0, emptyList(), listOf("Select a writable local device folder"))
                }
                val assets =
                    db.backgroundDao().listForAlbum(albumId)
                        .filter { db.managedSourceDao().get(it.id)?.moveState !in COMPLETED_STATES }
                        .distinctBy { if (it.sourceType == SourceType.IMPORT.name) it.storageRef else it.id }
                val candidates =
                    folders.folders.value.map(Uri::parse).filter { isLocalFolder(it) && folders.hasWrite(it) }
                        .flatMap { tree -> SafFolders.listImages(context, tree).map { tree to it } }
                val used = mutableSetOf<Uri>()
                val hashes = mutableMapOf<Uri, String?>()
                val matches = mutableListOf<Triple<Long, Uri, Uri>>()
                val unmatched = mutableListOf<String>()
                assets.forEach { asset ->
                    val linkedDocumentId =
                        if (asset.sourceType == SourceType.SAF_LINK.name) {
                            runCatching { DocumentsContract.getDocumentId(Uri.parse(asset.storageRef)) }.getOrNull()
                        } else {
                            null
                        }
                    val matching =
                        candidates.filter { (_, uri) ->
                            if (uri in used) return@filter false
                            if (linkedDocumentId != null) {
                                return@filter runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ==
                                    linkedDocumentId
                            }
                            val hash =
                                hashes.getOrPut(uri) {
                                    runCatching {
                                        context.contentResolver.openInputStream(
                                            uri,
                                        )?.use(::sha256)
                                    }.getOrNull()
                                }
                            hash != null && hash == asset.sha256
                        }
                    if (matching.size == 1) {
                        val (tree, uri) = matching.single()
                        matches += Triple(asset.id, tree, uri)
                        used += uri
                    } else {
                        unmatched += asset.displayName
                    }
                }
                if (unmatched.isNotEmpty()) return@withContext MoveResult(0, unmatched, emptyList())
                val failed = mutableListOf<String>()
                var moved = 0
                matches.forEach { (assetId, tree, uri) ->
                    if (move(assetId, tree, uri)) {
                        moved++
                    } else {
                        failed += db.backgroundDao().get(assetId)?.displayName ?: uri.lastPathSegment.orEmpty()
                    }
                }
                MoveResult(moved, emptyList(), failed)
            }

        @Suppress("CyclomaticComplexMethod")
        suspend fun move(
            assetId: Long,
            treeUri: Uri,
            sourceUri: Uri,
        ): Boolean =
            withContext(Dispatchers.IO) {
                if (!isLocalFolder(treeUri) || !folders.hasWrite(treeUri)) return@withContext false
                val asset = db.backgroundDao().get(assetId) ?: return@withContext false
                if (asset.sourceType !in SUPPORTED_SOURCE_TYPES) return@withContext false
                val importedSource = asset.sourceType != SourceType.SAF_LINK.name
                val imported =
                    if (asset.sourceType == SourceType.SAF_LINK.name) importer.import(sourceUri) else null
                if (asset.sourceType == SourceType.SAF_LINK.name && imported == null) return@withContext false
                val privatePath = imported?.filePath ?: asset.storageRef
                val privateFile = File(privatePath)
                val originalHash = context.contentResolver.openInputStream(sourceUri)?.use(::sha256)
                val privateHash =
                    if (asset.sourceType == SourceType.ENCRYPTED_IMPORT.name) {
                        encryptedImages.openAsset(assetId)?.use(::sha256)
                    } else if (privateFile.isFile) {
                        privateFile.inputStream().use(::sha256)
                    } else {
                        null
                    }
                if (originalHash == null ||
                    (asset.sha256 != null && originalHash != asset.sha256) ||
                    privateHash != originalHash
                ) {
                    return@withContext false
                }
                val documentId =
                    runCatching { DocumentsContract.getDocumentId(sourceUri) }.getOrNull()
                        ?: return@withContext false
                val originalName = documentName(sourceUri) ?: return@withContext false
                db.withTransaction {
                    db.managedSourceDao().upsert(
                        ManagedSourceEntity(
                            assetId,
                            treeUri.toString(),
                            documentId,
                            originalName,
                            originalHash,
                            if (importedSource) "COPY_READY_IMPORT" else "COPY_READY",
                        ),
                    )
                    db.backgroundDao().update(
                        if (imported != null) {
                            asset.copy(
                                sourceType = SourceType.IMPORT.name,
                                storageRef = imported.filePath,
                                sha256 = originalHash,
                            )
                        } else {
                            asset
                        },
                    )
                }
                val deleted =
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, sourceUri) }
                        .getOrDefault(false)
                if (deleted) {
                    db.managedSourceDao().upsert(
                        ManagedSourceEntity(
                            assetId,
                            treeUri.toString(),
                            documentId,
                            originalName,
                            originalHash,
                            if (importedSource) "MOVED_IMPORT" else "MOVED",
                        ),
                    )
                }
                deleted
            }

        private fun documentName(uri: Uri): String? =
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()

        private fun isLocalFolder(uri: Uri): Boolean = uri.authority == "com.android.externalstorage.documents"

        suspend fun restoreAlbum(albumId: Long): List<String> =
            withContext(Dispatchers.IO) {
                db.managedSourceDao().forAlbum(albumId).mapNotNull { record ->
                    if (record.treeUri == FULL_ACCESS) {
                        val restored = runCatching { restoreFile(record) }.getOrDefault(false)
                        return@mapNotNull if (restored) null else record.originalName
                    }
                    val importedSource = record.moveState.endsWith("_IMPORT")
                    val restored =
                        if (record.moveState.startsWith("COPY_READY")) {
                            val tree = Uri.parse(record.treeUri)
                            val original = DocumentsContract.buildDocumentUriUsingTree(tree, record.documentId)
                            val hash =
                                runCatching {
                                    context.contentResolver.openInputStream(original)?.use(::sha256)
                                }.getOrNull()
                            if (hash == record.sha256) {
                                if (importedSource) {
                                    db.managedSourceDao().delete(record.assetId)
                                    true
                                } else {
                                    runCatching { relinkOriginal(record, original) }.getOrDefault(false)
                                }
                            } else if (hash == null) {
                                db.managedSourceDao().upsert(
                                    record.copy(moveState = if (importedSource) "MOVED_IMPORT" else "MOVED"),
                                )
                                runCatching { restore(record, importedSource) }.getOrDefault(false)
                            } else {
                                false
                            }
                        } else {
                            runCatching { restore(record, importedSource) }.getOrDefault(false)
                        }
                    if (restored) null else record.originalName
                }
            }

        @Suppress("ReturnCount")
        private suspend fun restoreFile(record: ManagedSourceEntity): Boolean {
            if (!Environment.isExternalStorageManager()) return false
            val target = safeSharedFile(record.documentId) ?: return false
            if (target.exists()) {
                if (target.inputStream().use(::sha256) != record.sha256) return false
                db.managedSourceDao().delete(record.assetId)
                return true
            }
            val asset = db.backgroundDao().get(record.assetId) ?: return false
            val source = File(asset.storageRef).takeIf { it.isFile } ?: return false
            if (source.inputStream().use(::sha256) != record.sha256) return false
            val parent = target.parentFile?.takeIf { it.isDirectory } ?: return false
            val temp = File.createTempFile(".backgrounded-restore-", ".tmp", parent)
            try {
                source.inputStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                if (temp.inputStream().use(::sha256) != record.sha256 || target.exists() || !temp.renameTo(target)) {
                    return false
                }
                db.managedSourceDao().delete(record.assetId)
                android.media.MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
                return true
            } finally {
                temp.delete()
            }
        }

        @Suppress("ReturnCount")
        private suspend fun relinkOriginal(
            record: ManagedSourceEntity,
            original: Uri,
        ): Boolean {
            val asset = db.backgroundDao().get(record.assetId) ?: return false
            if (asset.sourceType != SourceType.IMPORT.name) return false
            val privateFile = File(asset.storageRef).takeIf { it.isFile } ?: return false
            if (privateFile.inputStream().use(::sha256) != record.sha256) return false
            db.withTransaction {
                db.backgroundDao().update(
                    asset.copy(
                        sourceType = SourceType.SAF_LINK.name,
                        storageRef = original.toString(),
                    ),
                )
                db.managedSourceDao().delete(record.assetId)
            }
            imageStore.deleteIfUnreferenced(privateFile.absolutePath, db.backgroundDao().allStorageRefs().toSet())
            return true
        }

        @Suppress("ReturnCount")
        private suspend fun restore(
            record: ManagedSourceEntity,
            keepPrivateAsset: Boolean,
        ): Boolean {
            val tree = Uri.parse(record.treeUri)
            if (!folders.hasWrite(tree)) return false
            val asset = db.backgroundDao().get(record.assetId) ?: return false
            if (asset.sourceType != SourceType.IMPORT.name) return false
            val source = File(asset.storageRef).takeIf { it.isFile } ?: return false
            if (source.inputStream().use(::sha256) != record.sha256) return false
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val mime =
                android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(record.originalName.substringAfterLast('.', "jpg")) ?: "image/jpeg"
            val alreadyRestored =
                SafFolders.listImages(context, tree).firstOrNull { uri ->
                    val name =
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    name == record.originalName &&
                        context.contentResolver.openInputStream(uri)?.use(::sha256) == record.sha256
                }
            val created =
                if (alreadyRestored != null) {
                    alreadyRestored
                } else {
                    runCatching {
                        DocumentsContract.createDocument(context.contentResolver, root, mime, record.originalName)
                    }.getOrNull() ?: return false
                }
            if (alreadyRestored == null) {
                val copied =
                    runCatching {
                        context.contentResolver.openOutputStream(created, "w")?.use { output ->
                            source.inputStream().use { it.copyTo(output) }
                        } ?: error("Unable to write restored file")
                        context.contentResolver.openInputStream(created)?.use(::sha256) == record.sha256
                    }.getOrDefault(false)
                if (!copied) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, created) }
                    return false
                }
            }
            db.withTransaction {
                if (!keepPrivateAsset) {
                    db.backgroundDao().update(
                        asset.copy(sourceType = SourceType.SAF_LINK.name, storageRef = created.toString()),
                    )
                }
                db.managedSourceDao().delete(record.assetId)
            }
            if (!keepPrivateAsset) {
                imageStore.deleteIfUnreferenced(source.absolutePath, db.backgroundDao().allStorageRefs().toSet())
            }
            return true
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

        private companion object {
            val SUPPORTED_SOURCE_TYPES =
                setOf(SourceType.SAF_LINK.name, SourceType.IMPORT.name, SourceType.ENCRYPTED_IMPORT.name)
            val COMPLETED_STATES = setOf("MOVED", "MOVED_IMPORT", "MOVED_FILE")
            const val FULL_ACCESS = "FULL_ACCESS"
        }
    }
