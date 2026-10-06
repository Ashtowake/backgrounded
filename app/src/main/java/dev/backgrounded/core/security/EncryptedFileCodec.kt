package dev.backgrounded.core.security

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated, bounded, streaming file format. No plaintext file cache. */
object EncryptedFileCodec {
    const val VERSION = 2
    const val MAX_BYTES = 64L * 1024 * 1024
    const val MAX_FILE_BYTES = 256L * 1024 * (Int.MAX_VALUE - 1L)
    private const val MAGIC = 0x42474531
    private const val CHUNK_BYTES = 256 * 1024

    fun write(
        source: File,
        destination: File,
        key: ByteArray,
        maxPlaintextBytes: Long = MAX_BYTES,
        checkActive: () -> Unit = {},
    ) = source.inputStream().use { write(it, source.length(), destination, key, maxPlaintextBytes, checkActive) }

    fun write(
        source: InputStream,
        length: Long,
        destination: File,
        key: ByteArray,
        maxPlaintextBytes: Long = MAX_BYTES,
        checkActive: () -> Unit = {},
    ) {
        source.use { input ->
            require(length in 1..minOf(maxPlaintextBytes, MAX_FILE_BYTES))
            val prefix = ByteArray(8).also(SecureRandom()::nextBytes)
            val header = header(VERSION, length, prefix)
            destination.outputStream().use { file ->
                DataOutputStream(file.buffered()).use { output ->
                    output.write(header)
                    writeChunks(input, length, prefix, header, key, output, checkActive)
                    output.flush()
                    file.fd.sync()
                }
            }
        }
    }

    private fun writeChunks(
        source: InputStream,
        length: Long,
        prefix: ByteArray,
        header: ByteArray,
        key: ByteArray,
        output: DataOutputStream,
        checkActive: () -> Unit,
    ) {
        source.buffered().use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            var remaining = length
            var index = 0
            try {
                while (remaining > 0) {
                    checkActive()
                    val size = minOf(CHUNK_BYTES.toLong(), remaining).toInt()
                    DataInputStream(input).readFully(buffer, 0, size)
                    output.writeInt(size)
                    writeChunk(buffer.copyOf(size), prefix, index++, header, key, output)
                    remaining -= size
                }
                require(input.read() == -1) { "Source changed during encryption" }
                output.writeInt(0)
                output.write(crypt(key, prefix, index, ByteArray(0), header, 0, Cipher.ENCRYPT_MODE))
            } finally {
                buffer.fill(0)
            }
        }
    }

    private fun writeChunk(
        plaintext: ByteArray,
        prefix: ByteArray,
        index: Int,
        header: ByteArray,
        key: ByteArray,
        output: DataOutputStream,
    ) {
        try {
            output.write(crypt(key, prefix, index, plaintext, header, plaintext.size, Cipher.ENCRYPT_MODE))
        } finally {
            plaintext.fill(0)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun open(
        file: File,
        key: ByteArray,
        expectedHash: String? = null,
        maxPlaintextBytes: Long = MAX_BYTES,
        checkActive: () -> Unit = {},
    ): InputStream {
        var input: DataInputStream? = null
        return try {
            val opened = DataInputStream(file.inputStream().buffered())
            input = opened
            ChunkStream(opened, key, expectedHash, maxPlaintextBytes, checkActive)
        } catch (failure: Throwable) {
            key.fill(0)
            input?.close()
            throw failure
        }
    }

    fun plaintextLength(
        file: File,
        maxPlaintextBytes: Long = MAX_BYTES,
    ): Long =
        DataInputStream(file.inputStream()).use {
            require(it.readInt() == MAGIC)
            require(it.readInt() in 1..VERSION)
            it.readLong().also { size -> require(size in 1..minOf(maxPlaintextBytes, MAX_FILE_BYTES)) }
        }

    private class ChunkStream(
        private val input: DataInputStream,
        private val key: ByteArray,
        private val expectedHash: String?,
        maxPlaintextBytes: Long,
        private val checkActive: () -> Unit,
    ) : InputStream() {
        private val version: Int
        private val header: ByteArray
        private val prefix = ByteArray(8)
        private val digest = MessageDigest.getInstance("SHA-256")
        private var remaining: Long
        private var index = 0
        private var chunk = ByteArray(0)
        private var cursor = 0
        private var complete = false

        init {
            require(input.readInt() == MAGIC)
            version = input.readInt()
            require(version in 1..VERSION)
            remaining = input.readLong()
            require(remaining in 1..minOf(maxPlaintextBytes, MAX_FILE_BYTES))
            input.readFully(prefix)
            header = header(version, remaining, prefix)
        }

        override fun read(): Int = if (fill()) chunk[cursor++].toInt() and 255 else -1

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length == 0) return 0
            if (!fill()) return -1
            val count = minOf(length, chunk.size - cursor)
            chunk.copyInto(bytes, offset, cursor, cursor + count)
            cursor += count
            return count
        }

        private fun fill(): Boolean {
            checkActive()
            if (cursor < chunk.size) return true
            if (complete) return false
            chunk.fill(0)
            if (remaining == 0L) {
                if (version == VERSION) {
                    require(input.readInt() == 0)
                    val tag = ByteArray(16).also(input::readFully)
                    crypt(key, prefix, index, tag, header, 0, Cipher.DECRYPT_MODE)
                }
                require(input.read() == -1) { "Unexpected trailing data" }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                require(expectedHash == null || hash.equals(expectedHash, ignoreCase = true)) { "Image hash mismatch" }
                complete = true
                return false
            }
            val size = input.readInt()
            require(size in 1..CHUNK_BYTES && size <= remaining)
            val ciphertext = ByteArray(size + 16).also(input::readFully)
            chunk =
                crypt(
                    key, prefix, index++, ciphertext, if (version == VERSION) header else null,
                    size, Cipher.DECRYPT_MODE,
                )
            digest.update(chunk)
            cursor = 0
            remaining -= size
            return true
        }

        override fun close() {
            chunk.fill(0)
            key.fill(0)
            input.close()
        }
    }

    private fun header(
        version: Int,
        length: Long,
        prefix: ByteArray,
    ): ByteArray = ByteBuffer.allocate(24).putInt(MAGIC).putInt(version).putLong(length).put(prefix).array()

    private fun crypt(
        key: ByteArray,
        prefix: ByteArray,
        index: Int,
        bytes: ByteArray,
        header: ByteArray?,
        size: Int,
        mode: Int,
    ): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                mode,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, ByteBuffer.allocate(12).put(prefix).putInt(index).array()),
            )
            header?.let(::updateAAD)
            updateAAD(ByteBuffer.allocate(8).putInt(index).putInt(size).array())
            doFinal(bytes)
        }
}
