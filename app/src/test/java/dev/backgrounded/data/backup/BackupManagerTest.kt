package dev.backgrounded.data.backup

import androidx.room.Room
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.importer.ImageImporter
import dev.backgrounded.data.importer.ImageStore
import dev.backgrounded.data.importer.ManagedFolderStore
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.SourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {
    @Test
    fun `invalid pair indexes roll back import without clearing current albums`() =
        runBlocking {
            withFixture { database, manager, _, repository ->
                val album = repository.createAlbum("Existing")
                val pair = repository.addPair(album, background(album))
                val before = repository.getPair(pair)

                val invalid = """{"albums":[{"name":"Invalid","pairs":[{"homeIndex":9,"lockIndex":9}]}]}"""
                assertFalse(manager.importJson(invalid))
                assertEquals(before, repository.getPair(pair))
                assertEquals(1, database.albumDao().observeAll().first().size)
            }
        }

    @Test
    fun `config import preserves editing metadata and identifies unavailable images`() =
        runBlocking {
            withFixture { _, manager, recovery, repository ->
                val album = repository.createAlbum("Existing")
                repository.addPair(album, background(album))
                val expected = repository.pairsFor(album).first().home.framings
                val exported = manager.exportJson()

                assertTrue(manager.importJson(exported))
                val restoredAlbum = repository.observeAlbums().first().single()
                assertEquals(expected, repository.pairsFor(restoredAlbum.id).single().home.framings)
                assertEquals(1, recovery.missingCount())
            }
        }

    @Test
    fun `invalid schemas settings and unbounded values preserve existing data`() =
        runBlocking {
            withFixture { database, manager, _, repository ->
                val album = repository.createAlbum("Keep")
                for (invalid in listOf(
                    "{}",
                    "{\"schemaVersion\":999,\"albums\":[]}",
                    "{\"schemaVersion\":null,\"albums\":[]}",
                    "{\"schemaVersion\":\"unsupported\",\"albums\":[]}",
                    "{\"albums\":[],\"settings\":{\"folderScanSeconds\":2}}",
                    "{\"albums\":[{\"name\":\"bad\",\"intervalMinutes\":2147483647}]}",
                )) {
                    assertFalse(invalid, manager.importJson(invalid))
                    assertEquals(album, database.albumDao().observeAll().first().single().id)
                }
            }
        }

    @Test
    fun `managed originals block configuration replacement`() =
        runBlocking {
            withFixture { database, manager, _, repository ->
                val album = repository.createAlbum("Protected")
                val pair = repository.addPair(album, background(album))
                val image = requireNotNull(repository.getPair(pair)).home.id
                database.managedSourceDao().upsert(
                    dev.backgrounded.data.db.ManagedSourceEntity(
                        image,
                        "tree",
                        "document",
                        "original.jpg",
                        "hash",
                        "MOVED",
                    ),
                )
                assertFalse(manager.importJson("{\"albums\":[]}"))
                assertEquals(1, database.managedSourceDao().count())
                assertEquals(album, database.albumDao().observeAll().first().single().id)
            }
        }

    private suspend fun withFixture(
        block: suspend (BackgroundedDatabase, BackupManager, ImageRecovery, AlbumRepository) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, BackgroundedDatabase::class.java).build()
        try {
            val store = ImageStore(context)
            val settings = SettingsStore(context)
            val recovery =
                ImageRecovery(
                    context,
                    database,
                    ImageImporter(context, store, database),
                    ManagedFolderStore(context),
                    settings,
                )
            block(
                database,
                BackupManager(context, database, settings, recovery),
                recovery,
                AlbumRepository(database, store),
            )
        } finally {
            database.close()
        }
    }

    private fun background(album: Long): Background =
        Background(
            id = 0,
            albumId = album,
            sourceType = SourceType.IMPORT,
            storageRef = "/missing/private-copy.png",
            displayName = "private-copy.png",
            sha256 = "a".repeat(64),
            width = 100,
            height = 100,
            dimForLock = false,
            sortIndex = 0,
            addedAt = 1L,
            framings = Background.defaultFramings().mapValues { it.value.copy(rotationDegrees = 37, mirrorY = true) },
        )
}
