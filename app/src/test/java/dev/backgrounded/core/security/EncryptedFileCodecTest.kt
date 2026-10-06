package dev.backgrounded.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class EncryptedFileCodecTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = ByteArray(32) { it.toByte() }

    private fun fixture(size: Int = 300000): Pair<File, ByteArray> {
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val source = temporary.newFile().apply { writeBytes(bytes) }
        val encrypted = temporary.newFile()
        EncryptedFileCodec.write(source, encrypted, key)
        return encrypted to bytes
    }

    private fun read(
        file: File,
        hash: String? = null,
    ): ByteArray = EncryptedFileCodec.open(file, key.copyOf(), hash).use { it.readBytes() }

    @Test fun readsLegacyVersionAndUpgradesWithoutChangingPlaintext() {
        val plain = ByteArray(400) { it.toByte() }
        val prefix = ByteArray(8) { (it + 1).toByte() }
        val nonce = java.nio.ByteBuffer.allocate(12).put(prefix).putInt(0).array()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(java.nio.ByteBuffer.allocate(8).putInt(0).putInt(plain.size).array())
        val old = temporary.newFile()
        java.io.DataOutputStream(old.outputStream()).use {
            it.writeInt(0x42474531)
            it.writeInt(1)
            it.writeLong(plain.size.toLong())
            it.write(prefix)
            it.writeInt(plain.size)
            it.write(cipher.doFinal(plain))
        }
        assertArrayEquals(plain, read(old))
        assertTrue(runCatching { read(old, "00".repeat(32)) }.isFailure)
        val upgraded = temporary.newFile()
        EncryptedFileCodec.open(old, key.copyOf()).use {
            EncryptedFileCodec.write(it, plain.size.toLong(), upgraded, key)
        }
        assertArrayEquals(plain, read(upgraded))
    }

    @Test fun roundTripAcrossChunks() {
        val (file, bytes) = fixture()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertArrayEquals(bytes, read(file, hash))
    }

    @Test fun cancellationStopsWriterAndClosesItsSource() {
        val source = temporary.newFile().apply { writeBytes(ByteArray(600000) { 7 }) }
        val destination = temporary.newFile()
        var closed = false
        val input =
            object : java.io.FilterInputStream(source.inputStream()) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        var chunks = 0
        val failure =
            runCatching {
                EncryptedFileCodec.write(input, source.length(), destination, key) {
                    if (++chunks == 2) throw kotlinx.coroutines.CancellationException("cancelled")
                }
            }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue(closed)
        assertTrue(source.isFile)
        assertTrue(runCatching { read(destination) }.isFailure)
    }

    @Test fun cancellationStopsReaderAndCloseClearsItsKey() {
        val (file, _) = fixture()
        val ownedKey = key.copyOf()
        val failure =
            runCatching {
                EncryptedFileCodec.open(file, ownedKey) {
                    throw kotlinx.coroutines.CancellationException("cancelled")
                }.use { it.read() }
            }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertArrayEquals(ByteArray(32), ownedKey)
    }

    @Test fun authenticatesHeaderCiphertextAndTerminal() {
        val (file, _) = fixture()
        val original = file.readBytes()
        for (position in listOf(4, 15, 20, 28, original.lastIndex)) {
            val altered = original.copyOf()
            altered[position] = (altered[position].toInt() xor 1).toByte()
            file.writeBytes(altered)
            assertTrue("altered byte $position", runCatching { read(file) }.isFailure)
        }
    }

    @Test fun rejectsTruncatedAndAppendedData() {
        val (file, _) = fixture()
        val original = file.readBytes()
        for (length in listOf(0, 12, 24, 28, original.size - 20, original.size - 1)) {
            file.writeBytes(original.copyOf(length))
            assertTrue("truncation $length", runCatching { read(file) }.isFailure)
        }
        file.writeBytes(original + byteArrayOf(1))
        assertTrue(runCatching { read(file) }.isFailure)
    }

    @Test fun rejectsWrongKeyAndWrongHash() {
        val (file, _) = fixture(1)
        assertTrue(runCatching { EncryptedFileCodec.open(file, ByteArray(32)).use { it.readBytes() } }.isFailure)
        assertTrue(runCatching { read(file, "00".repeat(32)) }.isFailure)
    }

    @Test fun refusesOversizedPlaintextBeforeAllocating() {
        val source = temporary.newFile()
        java.io.RandomAccessFile(source, "rw").use { it.setLength(EncryptedFileCodec.MAX_BYTES + 1) }
        assertTrue(runCatching { EncryptedFileCodec.write(source, temporary.newFile(), key) }.isFailure)
    }

    @Test fun missingFileClearsOwnedReadKey() {
        val owned = key.copyOf()
        val missing = File(temporary.root, "missing.bge")
        assertTrue(runCatching { EncryptedFileCodec.open(missing, owned) }.isFailure)
        assertArrayEquals(ByteArray(32), owned)
    }

    @Test fun rejectedLengthClosesInput() {
        var closed = false
        val input =
            object : java.io.ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() {
                    closed = true
                }
            }
        assertTrue(
            runCatching { EncryptedFileCodec.write(input, EncryptedFileCodec.MAX_BYTES + 1, temporary.newFile(), key) }
                .isFailure,
        )
        assertTrue(closed)
    }

    @Test fun streamingMaintenanceReadsAndUpgradesLegacyAboveDisplayBufferLimit() {
        val length = EncryptedFileCodec.MAX_BYTES + 1
        val file = temporary.newFile()
        val prefix = ByteArray(8) { 3 }
        val digest = MessageDigest.getInstance("SHA-256")
        java.io.DataOutputStream(file.outputStream().buffered()).use { output ->
            output.writeInt(0x42474531)
            output.writeInt(1)
            output.writeLong(length)
            output.write(prefix)
            var remaining = length
            var index = 0
            while (remaining > 0) {
                val size = minOf(256 * 1024L, remaining).toInt()
                val plain = ByteArray(size)
                digest.update(plain)
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    javax.crypto.Cipher.ENCRYPT_MODE,
                    javax.crypto.spec.SecretKeySpec(key, "AES"),
                    javax.crypto.spec.GCMParameterSpec(
                        128,
                        java.nio.ByteBuffer.allocate(12).put(prefix).putInt(index++).array(),
                    ),
                )
                cipher.updateAAD(java.nio.ByteBuffer.allocate(8).putInt(index - 1).putInt(size).array())
                output.writeInt(size)
                output.write(cipher.doFinal(plain))
                remaining -= size
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        assertTrue(runCatching { EncryptedFileCodec.plaintextLength(file) }.isFailure)
        assertTrue(runCatching { EncryptedFileCodec.open(file, key.copyOf()).close() }.isFailure)
        val upgraded = temporary.newFile()
        EncryptedFileCodec.open(file, key.copyOf(), hash, file.length()).use {
            EncryptedFileCodec.write(it, length, upgraded, key, maxPlaintextBytes = length)
        }
        assertTrue(runCatching { EncryptedFileCodec.plaintextLength(upgraded) }.isFailure)
        EncryptedFileCodec.open(upgraded, key.copyOf(), hash, upgraded.length()).use {
            assertTrue(it.copyTo(java.io.OutputStream.nullOutputStream()) == length)
        }
    }

    @Test fun rejectsReorderedAuthenticatedChunks() {
        val (file, _) = fixture(600000)
        val bytes = file.readBytes()
        val recordSize = 4 + 256 * 1024 + 16
        val first = bytes.copyOfRange(24, 24 + recordSize)
        val second = bytes.copyOfRange(24 + recordSize, 24 + recordSize * 2)
        second.copyInto(bytes, 24)
        first.copyInto(bytes, 24 + recordSize)
        file.writeBytes(bytes)
        assertTrue(runCatching { read(file) }.isFailure)
    }
}
