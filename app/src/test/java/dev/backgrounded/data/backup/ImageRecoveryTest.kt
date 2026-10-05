package dev.backgrounded.data.backup

import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.importer.ImageImporter
import dev.backgrounded.data.importer.ImageStore
import dev.backgrounded.data.importer.ManagedFolderStore
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageRecoveryTest {
    @Test
    fun `recovery matches content across renamed originals and preserves pair composition`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, BackgroundedDatabase::class.java).build()
            val original = File(context.cacheDir, "original-camera-name.png")
            val wrong = File(context.cacheDir, "internal-name.png")
            writeImage(original, Color.RED)
            writeImage(wrong, Color.BLUE)
            try {
                val store = ImageStore(context)
                val recovery =
                    ImageRecovery(
                        context,
                        database,
                        ImageImporter(context, store),
                        ManagedFolderStore(context),
                        SettingsStore(context),
                    )
                val repository = AlbumRepository(database, store)
                val album = repository.createAlbum("Album")
                val hash = hash(original)
                val asset = background(album, hash)
                val pair = repository.addPair(album, asset)
                val duplicate = repository.addPair(album, asset)
                val before = repository.pairsFor(album)

                val result = recovery.restoreFiles(sequenceOf(wrong, original))

                assertEquals(ImageRecoveryResult(2, 0), result)
                val after = repository.pairsFor(album)
                assertEquals(listOf(pair, duplicate), after.map { it.id })
                assertEquals(before.map { it.home.framings }, after.map { it.home.framings })
                after.forEach { assertEquals(hash, hash(File(it.home.storageRef))) }
                assertEquals(hash, hash(original))
                assertTrue(wrong.isFile)
            } finally {
                database.close()
                original.delete()
                wrong.delete()
            }
        }

    @Test
    fun `wrong image with expected filename does not replace a missing image`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, BackgroundedDatabase::class.java).build()
            val candidate = File(context.cacheDir, "internal-name.png")
            writeImage(candidate, Color.BLUE)
            try {
                val store = ImageStore(context)
                val repository = AlbumRepository(database, store)
                val album = repository.createAlbum("Album")
                val pair = repository.addPair(album, background(album, "a".repeat(64)))
                val recovery =
                    ImageRecovery(
                        context,
                        database,
                        ImageImporter(context, store),
                        ManagedFolderStore(context),
                        SettingsStore(context),
                    )

                assertEquals(ImageRecoveryResult(0, 1), recovery.restoreFiles(sequenceOf(candidate)))
                assertEquals("/missing/internal-name.png", repository.getPair(pair)!!.home.storageRef)
                assertTrue(candidate.isFile)
            } finally {
                database.close()
                candidate.delete()
            }
        }

    private fun background(
        album: Long,
        hash: String,
    ): Background =
        Background(
            id = 0,
            albumId = album,
            sourceType = SourceType.IMPORT,
            storageRef = "/missing/internal-name.png",
            displayName = "internal-name.png",
            sha256 = hash,
            width = 16,
            height = 16,
            dimForLock = false,
            sortIndex = 0,
            addedAt = 1L,
            framings = Background.defaultFramings().mapValues { it.value.copy(zoom = 1.7f, mirrorX = true) },
        )

    private fun writeImage(
        file: File,
        color: Int,
    ) {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun hash(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
