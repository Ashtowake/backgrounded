package dev.backgrounded.data.importer

import androidx.room.Room
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.db.OperationJournalEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class OperationRecoveryTest {
    @Test fun `unverified ready copy and its recovery record are retained`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val db = Room.inMemoryDatabaseBuilder(context, BackgroundedDatabase::class.java).build()
            val store = ImageStore(context)
            val partial = File(store.imagesDir, "import-mismatch.tmp").apply { writeText("safe copy") }
            val destination = File(store.imagesDir, "unpublished.jpg")
            try {
                db.hardeningDao().recordOperation(
                    OperationJournalEntity(
                        "import-mismatch", "IMPORT", "VERIFIED", null, "source",
                        destination.path, "00".repeat(32), null, null, 1,
                    ),
                )
                OperationRecovery(db, store).recover()
                assertEquals("safe copy", partial.readText())
                assertFalse(destination.exists())
                assertEquals(1, db.hardeningDao().pendingOperations())
            } finally {
                db.close()
                partial.delete()
            }
        }

    @Test fun `interrupted copy removes only its journal-owned temporary`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val db = Room.inMemoryDatabaseBuilder(context, BackgroundedDatabase::class.java).build()
            val store = ImageStore(context)
            val original = File(store.imagesDir, "earlier-original.jpg").apply { writeText("keep") }
            val partial = File(store.imagesDir, "import-interrupted.tmp").apply { writeText("partial") }
            try {
                db.hardeningDao().recordOperation(
                    OperationJournalEntity(
                        "import-interrupted", "IMPORT", "COPYING",
                        null, "source", partial.path, null, null, null, 1,
                    ),
                )
                OperationRecovery(db, store).recover()
                assertFalse(partial.exists())
                assertEquals("keep", original.readText())
                assertEquals(0, db.hardeningDao().pendingOperations())
            } finally {
                db.close()
                original.delete()
                partial.delete()
            }
        }

    @Test fun `verified unpublished copy survives and is published on recovery`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val db = Room.inMemoryDatabaseBuilder(context, BackgroundedDatabase::class.java).build()
            val store = ImageStore(context)
            val bytes = byteArrayOf(1, 2, 3)
            val partial = File(store.imagesDir, "import-ready.tmp").apply { writeBytes(bytes) }
            val destination = File(store.imagesDir, "recovered.jpg")
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            try {
                db.hardeningDao().recordOperation(
                    OperationJournalEntity(
                        "import-ready", "IMPORT", "VERIFIED",
                        null, "source", destination.path, hash, null, null, 1,
                    ),
                )
                OperationRecovery(db, store).recover()
                assertTrue(destination.isFile)
                org.junit.Assert.assertArrayEquals(bytes, destination.readBytes())
                assertEquals(0, db.hardeningDao().pendingOperations())
            } finally {
                db.close()
                partial.delete()
                destination.delete()
            }
        }
}
