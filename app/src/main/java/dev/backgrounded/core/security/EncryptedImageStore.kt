package dev.backgrounded.core.security

import android.net.Uri
import androidx.room.withTransaction
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.db.EncryptedAssetEntity
import dev.backgrounded.data.db.OperationJournalEntity
import dev.backgrounded.data.importer.ImageImporter
import dev.backgrounded.data.importer.ImageStore
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedImageStore
    @Inject
    constructor(
        private val db: BackgroundedDatabase,
        private val imageStore: ImageStore,
        private val imageImporter: ImageImporter,
        private val pinVault: PinVault,
    ) {
        private val random = SecureRandom()
        private val operationMutex = kotlinx.coroutines.sync.Mutex()

        suspend fun encryptHiddenAlbums(): Boolean {
            if (!pinVault.unlocked()) return false
            recoverOperations()
            val hiddenIds =
                db.albumDao().observeAll().first()
                    .filter { it.isHidden }.map { it.id }
            return hiddenIds.all { albumId ->
                db.backgroundDao().listForAlbum(albumId).all { encryptAsset(it.id) }
            }
        }

        suspend fun decryptHiddenAlbums(): Boolean {
            if (!pinVault.unlocked()) return false
            recoverOperations()
            val hiddenIds =
                db.albumDao().observeAll().first()
                    .filter { it.isHidden }.map { it.id }
            return hiddenIds.all { albumId ->
                db.backgroundDao().listForAlbum(albumId).all { entity ->
                    if (entity.sourceType == SourceType.ENCRYPTED_IMPORT.name) decryptAsset(entity.id) else true
                }
            }
        }

        suspend fun encryptAsset(assetId: Long): Boolean =
            withContext(Dispatchers.IO) {
                operationMutex.withLock {
                    var asset = db.backgroundDao().get(assetId) ?: return@withContext false
                    val legacy =
                        if (asset.sourceType == SourceType.ENCRYPTED_IMPORT.name) {
                            db.encryptedAssetDao().get(assetId) ?: return@withContext false
                        } else {
                            null
                        }
                    if (legacy?.formatVersion == FORMAT_VERSION) return@withContext true
                    if (asset.sourceType == SourceType.SAF_LINK.name) {
                        asset = copyLinkedAsset(asset) ?: return@withContext false
                    }
                    val source = File(asset.storageRef).takeIf { it.isFile } ?: return@withContext false
                    val prepared = prepareEncryption(asset, source, legacy) ?: return@withContext false
                    val destination = prepared.destination
                    val operation = prepared.operation
                    val metadata = prepared.metadata
                    db.withTransaction {
                        val current = requireNotNull(db.backgroundDao().get(assetId))
                        require(current.storageRef == asset.storageRef)
                        db.encryptedAssetDao().upsert(metadata)
                        db.backgroundDao().update(
                            current.copy(
                                sourceType = SourceType.ENCRYPTED_IMPORT.name,
                                storageRef = destination.absolutePath,
                            ),
                        )
                    }
                    imageStore.deleteIfUnreferenced(source.absolutePath, db.backgroundDao().allStorageRefs().toSet())
                    db.hardeningDao().finishOperation(operation.id)
                    true
                }
            }

        private data class PreparedEncryption(
            val destination: File,
            val operation: OperationJournalEntity,
            val metadata: EncryptedAssetEntity,
        )

        private suspend fun prepareEncryption(
            asset: dev.backgrounded.data.db.BackgroundEntity,
            source: File,
            legacy: EncryptedAssetEntity?,
        ): PreparedEncryption? {
            val vaultKey = pinVault.key() ?: return null
            val fileKey = randomBytes(KEY_BYTES)
            try {
                val keyNonce = randomBytes(NONCE_BYTES)
                val wrapped = crypt(Cipher.ENCRYPT_MODE, vaultKey, keyNonce, fileKey)
                val metadata = EncryptedAssetEntity(asset.id, FORMAT_VERSION, wrapped, keyNonce)
                val destination = File(imageStore.imagesDir, "enc-${UUID.randomUUID()}.bge")
                val temporary = File(imageStore.imagesDir, "${destination.name}.tmp")
                val operation =
                    OperationJournalEntity(
                        destination.name, "ENCRYPT", "COPYING", asset.id, source.absolutePath,
                        destination.absolutePath, asset.sha256, wrapped, keyNonce, System.currentTimeMillis(),
                    )
                db.hardeningDao().recordOperation(operation)
                val coroutineContext = kotlinx.coroutines.currentCoroutineContext()
                val encrypted =
                    attempt {
                        if (legacy != null) {
                            open(source, legacy, asset.sha256).use { input ->
                                EncryptedFileCodec.write(
                                    input,
                                    EncryptedFileCodec.plaintextLength(source, source.length()),
                                    temporary,
                                    fileKey,
                                    maxPlaintextBytes = source.length(),
                                ) { coroutineContext.ensureActive() }
                            }
                        } else {
                            EncryptedFileCodec.write(source, temporary, fileKey, source.length()) {
                                coroutineContext.ensureActive()
                            }
                        }
                    }
                if (!encrypted || !verifyEncrypted(temporary, metadata, asset.sha256) ||
                    !temporary.renameTo(destination)
                ) {
                    temporary.delete()
                    db.hardeningDao().finishOperation(operation.id)
                    return null
                }
                db.hardeningDao().recordOperation(operation.copy(stage = "VERIFIED"))
                return PreparedEncryption(destination, operation, metadata)
            } finally {
                fileKey.fill(0)
                vaultKey.fill(0)
            }
        }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private inline fun attempt(operation: () -> Unit): Boolean =
            try {
                operation()
                true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                false
            }

        suspend fun decryptAsset(assetId: Long): Boolean =
            withContext(Dispatchers.IO) {
                operationMutex.withLock {
                    val asset = db.backgroundDao().get(assetId) ?: return@withContext false
                    if (asset.sourceType != SourceType.ENCRYPTED_IMPORT.name) return@withContext true
                    val metadata = db.encryptedAssetDao().get(assetId) ?: return@withContext false
                    val encrypted = File(asset.storageRef)
                    val destination = File(imageStore.imagesDir, "plain-${UUID.randomUUID()}.jpg")
                    val temporary = File(imageStore.imagesDir, "${destination.name}.tmp")
                    val operation =
                        OperationJournalEntity(
                            temporary.name, "DECRYPT", "COPYING", assetId,
                            encrypted.absolutePath,
                            destination.absolutePath,
                            asset.sha256,
                            null,
                            null,
                            System.currentTimeMillis(),
                        )
                    db.hardeningDao().recordOperation(operation)
                    val success =
                        attempt {
                            open(encrypted, metadata, asset.sha256).use {
                                    input ->
                                temporary.outputStream().use { output ->
                                    input.copyTo(output)
                                    output.fd.sync()
                                }
                            }
                        }
                    if (!success) {
                        db.hardeningDao().finishOperation(operation.id)
                        temporary.delete()
                        return@withContext false
                    }
                    val plaintextHash = hash(temporary)
                    db.hardeningDao().recordOperation(
                        operation.copy(stage = "VERIFIED", sha256 = plaintextHash),
                    )
                    if (!temporary.renameTo(destination)) return@withContext false
                    db.withTransaction {
                        val current = requireNotNull(db.backgroundDao().get(assetId))
                        require(current.storageRef == asset.storageRef)
                        db.backgroundDao().update(
                            current.copy(
                                sourceType = SourceType.IMPORT.name,
                                storageRef = destination.absolutePath,
                            ),
                        )
                        db.encryptedAssetDao().delete(assetId)
                    }
                    encrypted.delete()
                    db.hardeningDao().finishOperation(operation.id)
                    true
                }
            }

        suspend fun recoverOperations() =
            withContext(Dispatchers.IO) {
                operationMutex.withLock {
                    if (!pinVault.unlocked()) return@withContext
                    db.hardeningDao().operations().filter { it.kind in setOf("ENCRYPT", "DECRYPT") }.forEach { op ->
                        val assetId = op.assetId ?: return@forEach
                        val asset = db.backgroundDao().get(assetId) ?: return@forEach
                        val destination = File(op.destinationRef)
                        if (asset.storageRef == op.destinationRef) {
                            // Database publication completed; only delete a private, unreferenced old file.
                            imageStore.deleteIfUnreferenced(op.sourceRef, db.backgroundDao().allStorageRefs())
                            db.hardeningDao().finishOperation(op.id)
                            return@forEach
                        }
                        if (asset.storageRef != op.sourceRef) return@forEach
                        val temporary = File("${op.destinationRef}.tmp")
                        val candidate = if (destination.isFile) destination else temporary
                        if (!candidate.isFile) {
                            discardInterruptedCopy(op, destination, temporary)
                            return@forEach
                        }
                        val valid =
                            attempt {
                                if (op.kind == "ENCRYPT") {
                                    val metadata =
                                        EncryptedAssetEntity(
                                            assetId,
                                            FORMAT_VERSION,
                                            requireNotNull(op.wrappedKey),
                                            requireNotNull(op.keyNonce),
                                        )
                                    open(candidate, metadata, op.sha256).use { stream ->
                                        val bytes = ByteArray(64 * 1024)
                                        while (stream.read(bytes) >= 0) Unit
                                    }
                                } else {
                                    require(op.sha256 != null && hash(candidate) == op.sha256)
                                }
                            }
                        if (!valid) {
                            discardInterruptedCopy(op, destination, temporary)
                            return@forEach
                        }
                        if (candidate != destination && !candidate.renameTo(destination)) return@forEach
                        db.withTransaction {
                            require(db.backgroundDao().get(assetId)?.storageRef == op.sourceRef)
                            if (op.kind == "ENCRYPT") {
                                db.encryptedAssetDao().upsert(
                                    EncryptedAssetEntity(
                                        assetId,
                                        FORMAT_VERSION,
                                        requireNotNull(op.wrappedKey),
                                        requireNotNull(op.keyNonce),
                                    ),
                                )
                            } else {
                                db.encryptedAssetDao().delete(assetId)
                            }
                            db.backgroundDao().update(
                                asset.copy(
                                    storageRef = destination.absolutePath,
                                    sourceType =
                                        if (op.kind == "ENCRYPT") {
                                            SourceType.ENCRYPTED_IMPORT.name
                                        } else {
                                            SourceType.IMPORT.name
                                        },
                                ),
                            )
                        }
                        imageStore.deleteIfUnreferenced(op.sourceRef, db.backgroundDao().allStorageRefs())
                        db.hardeningDao().finishOperation(op.id)
                    }
                }
            }

        private suspend fun discardInterruptedCopy(
            operation: OperationJournalEntity,
            destination: File,
            temporary: File,
        ) {
            val directory = imageStore.imagesDir.canonicalFile
            if (operation.stage != "COPYING" || destination.exists() || !File(operation.sourceRef).isFile) return
            val ownedDirectory =
                destination.canonicalFile.parentFile == directory &&
                    temporary.canonicalFile.parentFile == directory
            val ownedName = destination.name.startsWith("enc-") || destination.name.startsWith("plain-")
            if (!ownedDirectory || !ownedName) return
            // The asset still references its original. Only this incomplete operation's temp is disposable.
            if (!temporary.exists() || temporary.delete()) db.hardeningDao().finishOperation(operation.id)
        }

        private suspend fun copyLinkedAsset(
            asset: dev.backgrounded.data.db.BackgroundEntity,
        ): dev.backgrounded.data.db.BackgroundEntity? {
            val imported = imageImporter.import(Uri.parse(asset.storageRef)) ?: return null
            return db.withTransaction {
                val current = db.backgroundDao().get(asset.id) ?: return@withTransaction null
                if (current.storageRef != asset.storageRef || current.sourceType != SourceType.SAF_LINK.name) {
                    return@withTransaction null
                }
                current.copy(
                    sourceType = SourceType.IMPORT.name,
                    storageRef = imported.filePath,
                    sha256 = imported.sha256,
                ).also { db.backgroundDao().update(it) }
            }
        }

        private suspend fun verifyEncrypted(
            file: File,
            metadata: EncryptedAssetEntity,
            hash: String?,
        ): Boolean =
            attempt {
                open(file, metadata, hash).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (input.read(buffer) >= 0) Unit
                }
            }

        private suspend fun hash(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        suspend fun available(assetId: Long): Boolean =
            pinVault.unlocked() && db.encryptedAssetDao().get(assetId) != null

        suspend fun open(background: Background): InputStream? = openAsset(background.id)

        suspend fun openAsset(assetId: Long): InputStream? {
            val asset = db.backgroundDao().get(assetId) ?: return null
            val metadata = db.encryptedAssetDao().get(assetId) ?: return null
            return runCatching { open(File(asset.storageRef), metadata, asset.sha256) }.getOrNull()
        }

        private suspend fun open(
            file: File,
            metadata: EncryptedAssetEntity,
            expectedHash: String? = null,
        ): InputStream {
            require(metadata.formatVersion in 1..FORMAT_VERSION)
            val vaultKey = pinVault.key() ?: error("Hidden images are locked")
            val fileKey =
                try {
                    crypt(Cipher.DECRYPT_MODE, vaultKey, metadata.keyNonce, metadata.wrappedKey)
                } finally {
                    vaultKey.fill(0)
                }
            val coroutineContext = kotlinx.coroutines.currentCoroutineContext()
            return EncryptedFileCodec.open(file, fileKey, expectedHash, file.length()) {
                coroutineContext.ensureActive()
            }
        }

        private fun crypt(
            mode: Int,
            key: ByteArray,
            nonce: ByteArray,
            bytes: ByteArray,
        ): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                doFinal(bytes)
            }

        private fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

        companion object {
            private const val FORMAT_VERSION = EncryptedFileCodec.VERSION
            private const val KEY_BYTES = 32
            private const val NONCE_BYTES = 12
        }
    }
