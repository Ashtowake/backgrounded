package dev.backgrounded.data.importer

import dev.backgrounded.data.db.BackgroundedDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Only journal-owned import temporaries are disposable. Verified originals are always retained. */
@Singleton
class OperationRecovery
    @Inject
    constructor(private val db: BackgroundedDatabase, private val store: ImageStore) {
        private val mutex = Mutex()
        private var finished = false

        suspend fun recover() =
            mutex.withLock {
                if (finished) return@withLock
                withContext(Dispatchers.IO) {
                    db.hardeningDao().operations().filter { it.kind == "IMPORT" }.forEach { operation ->
                        val temporary = File(store.imagesDir, "${operation.id}.tmp")
                        if (!operation.id.startsWith("import-") ||
                            temporary.canonicalFile.parentFile != store.imagesDir.canonicalFile
                        ) {
                            return@forEach
                        }
                        if (operation.stage == "COPYING") {
                            if (!temporary.exists() || temporary.delete()) {
                                db.hardeningDao().finishOperation(
                                    operation.id,
                                )
                            }
                            return@forEach
                        }
                        val destination = File(operation.destinationRef).canonicalFile
                        if (destination.parentFile != store.imagesDir.canonicalFile) return@forEach
                        val candidate = if (destination.isFile) destination else temporary
                        val verified =
                            candidate.isFile &&
                                runCatching { hash(candidate) == operation.sha256 }.getOrDefault(false)
                        if (!verified) return@forEach
                        if (candidate != destination && !candidate.renameTo(destination)) return@forEach
                        if (temporary.exists()) temporary.delete()
                        db.hardeningDao().finishOperation(operation.id)
                    }
                }
                finished = true
            }

        private fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
