package dev.backgrounded.core.security

import android.net.Uri
import androidx.room.withTransaction
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.db.EncryptedAssetEntity
import dev.backgrounded.data.importer.ImageImporter
import dev.backgrounded.data.importer.ImageStore
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
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

        suspend fun encryptHiddenAlbums(): Boolean {
            if (!pinVault.unlocked()) return false
            val hiddenIds =
                db.albumDao().observeAll().first()
                    .filter { it.isHidden }.map { it.id }
            return hiddenIds.all { albumId ->
                db.backgroundDao().listForAlbum(albumId).all { encryptAsset(it.id) }
            }
        }

        suspend fun decryptHiddenAlbums(): Boolean {
            if (!pinVault.unlocked()) return false
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
                var asset = db.backgroundDao().get(assetId) ?: return@withContext false
                if (asset.sourceType == SourceType.ENCRYPTED_IMPORT.name) return@withContext true
                if (asset.sourceType == SourceType.SAF_LINK.name) {
                    val imported = imageImporter.import(Uri.parse(asset.storageRef)) ?: return@withContext false
                    asset =
                        asset.copy(
                            sourceType = SourceType.IMPORT.name, storageRef = imported.filePath,
                            sha256 = imported.sha256,
                        )
                    db.backgroundDao().update(asset)
                }
                val source = File(asset.storageRef).takeIf { it.isFile } ?: return@withContext false
                val vaultKey = pinVault.key() ?: return@withContext false
                val fileKey = randomBytes(KEY_BYTES)
                val keyNonce = randomBytes(NONCE_BYTES)
                val wrapped = crypt(Cipher.ENCRYPT_MODE, vaultKey, keyNonce, fileKey)
                val destination = File(imageStore.imagesDir, "enc-${UUID.randomUUID()}.bge")
                val temporary = File(imageStore.imagesDir, "${destination.name}.tmp")
                val encrypted = runCatching { writeEncrypted(source, temporary, fileKey) }.isSuccess
                val verified =
                    if (encrypted) {
                        runCatching {
                            val metadata = EncryptedAssetEntity(assetId, FORMAT_VERSION, wrapped, keyNonce)
                            open(temporary, metadata).use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (input.read(buffer) >= 0) Unit
                            }
                        }.isSuccess
                    } else {
                        false
                    }
                fileKey.fill(0)
                vaultKey.fill(0)
                if (!verified || !temporary.renameTo(destination)) {
                    temporary.delete()
                    return@withContext false
                }
                db.withTransaction {
                    db.encryptedAssetDao().upsert(EncryptedAssetEntity(assetId, FORMAT_VERSION, wrapped, keyNonce))
                    db.backgroundDao().update(
                        asset.copy(
                            sourceType = SourceType.ENCRYPTED_IMPORT.name,
                            storageRef = destination.absolutePath,
                        ),
                    )
                }
                imageStore.deleteIfUnreferenced(source.absolutePath, db.backgroundDao().allStorageRefs().toSet())
                true
            }

        suspend fun decryptAsset(assetId: Long): Boolean =
            withContext(Dispatchers.IO) {
                val asset = db.backgroundDao().get(assetId) ?: return@withContext false
                if (asset.sourceType != SourceType.ENCRYPTED_IMPORT.name) return@withContext true
                val metadata = db.encryptedAssetDao().get(assetId) ?: return@withContext false
                val encrypted = File(asset.storageRef)
                val temporary = File(imageStore.imagesDir, "plain-${UUID.randomUUID()}.tmp")
                val success =
                    runCatching {
                        open(encrypted, metadata).use {
                                input ->
                            temporary.outputStream().use { output -> input.copyTo(output) }
                        }
                    }.isSuccess
                if (!success) {
                    temporary.delete()
                    return@withContext false
                }
                val destination = File(imageStore.imagesDir, "${UUID.randomUUID()}.jpg")
                if (!temporary.renameTo(destination)) return@withContext false
                db.withTransaction {
                    db.backgroundDao().update(
                        asset.copy(
                            sourceType = SourceType.IMPORT.name,
                            storageRef = destination.absolutePath,
                        ),
                    )
                    db.encryptedAssetDao().delete(assetId)
                }
                encrypted.delete()
                true
            }

        suspend fun open(background: Background): InputStream? = openAsset(background.id)

        suspend fun openAsset(assetId: Long): InputStream? {
            val asset = db.backgroundDao().get(assetId) ?: return null
            val metadata = db.encryptedAssetDao().get(assetId) ?: return null
            return runCatching { open(File(asset.storageRef), metadata) }.getOrNull()
        }

        private fun open(
            file: File,
            metadata: EncryptedAssetEntity,
        ): InputStream {
            require(metadata.formatVersion == FORMAT_VERSION)
            val vaultKey = pinVault.key() ?: error("Hidden images are locked")
            val fileKey = crypt(Cipher.DECRYPT_MODE, vaultKey, metadata.keyNonce, metadata.wrappedKey)
            vaultKey.fill(0)
            return ChunkInputStream(DataInputStream(file.inputStream().buffered()), fileKey)
        }

        @Suppress("NestedBlockDepth")
        private fun writeEncrypted(
            source: File,
            destination: File,
            key: ByteArray,
        ) {
            val noncePrefix = randomBytes(8)
            DataOutputStream(destination.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeLong(source.length())
                output.write(noncePrefix)
                source.inputStream().buffered().use { input ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    var index = 0
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        val nonce = nonce(noncePrefix, index)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                        cipher.updateAAD(aad(index, size))
                        output.writeInt(size)
                        output.write(cipher.doFinal(buffer, 0, size))
                        index++
                    }
                }
            }
        }

        private class ChunkInputStream(private val input: DataInputStream, private val key: ByteArray) : InputStream() {
            private val prefix = ByteArray(8)
            private var remaining: Long
            private var index = 0
            private var chunk = ByteArray(0)
            private var cursor = 0

            init {
                require(input.readInt() == MAGIC)
                require(input.readInt() == FORMAT_VERSION)
                remaining = input.readLong()
                require(remaining >= 0)
                input.readFully(prefix)
            }

            override fun read(): Int {
                if (!fill()) return -1
                return chunk[cursor++].toInt() and 0xff
            }

            override fun read(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                if (length == 0) return 0
                if (!fill()) return -1
                val count = minOf(length, chunk.size - cursor)
                chunk.copyInto(bytes, offset, cursor, cursor + count)
                cursor += count
                return count
            }

            private fun fill(): Boolean {
                if (cursor < chunk.size) return true
                if (remaining == 0L) return false
                val size = input.readInt()
                require(size in 1..CHUNK_BYTES && size <= remaining)
                val ciphertext = ByteArray(size + 16)
                input.readFully(ciphertext)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, nonce(prefix, index)),
                )
                cipher.updateAAD(aad(index, size))
                chunk = cipher.doFinal(ciphertext)
                cursor = 0
                remaining -= size
                index++
                return true
            }

            override fun close() {
                key.fill(0)
                input.close()
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
            private const val MAGIC = 0x42474531
            private const val FORMAT_VERSION = 1
            private const val KEY_BYTES = 32
            private const val NONCE_BYTES = 12
            private const val CHUNK_BYTES = 256 * 1024

            private fun nonce(
                prefix: ByteArray,
                index: Int,
            ): ByteArray = ByteBuffer.allocate(12).put(prefix).putInt(index).array()

            private fun aad(
                index: Int,
                size: Int,
            ): ByteArray =
                ByteBuffer.allocate(
                    8,
                ).putInt(index).putInt(size).array()
        }
    }
