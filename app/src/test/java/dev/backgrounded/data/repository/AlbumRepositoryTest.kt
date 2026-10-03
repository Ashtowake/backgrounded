package dev.backgrounded.data.repository

import android.content.Context
import androidx.room.Room
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.importer.ImageStore
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.DisplayTarget
import dev.backgrounded.domain.model.FitMode
import dev.backgrounded.domain.model.FramingKey
import dev.backgrounded.domain.model.SourceType
import dev.backgrounded.domain.model.WallpaperSurface
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AlbumRepositoryTest {
    @Test
    fun `albums and pairs round trip`() =
        runBlocking {
            val context: Context = RuntimeEnvironment.getApplication()
            val database =
                Room.inMemoryDatabaseBuilder(context, BackgroundedDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            try {
                val repository = AlbumRepository(database, ImageStore(context))
                val albumId = repository.createAlbum("Test album")
                val pairId = repository.addPair(albumId, background(albumId))

                val albums = repository.observeAlbums().first()
                assertEquals(1, albums.size)
                assertEquals("Test album", albums.first().name)

                val pairs = repository.pairsFor(albumId)
                assertEquals(1, pairs.size)
                assertEquals(pairId, pairs.first().id)
                assertEquals(
                    FitMode.FILL,
                    pairs.first().home.framingFor(DisplayTarget.INNER, WallpaperSurface.HOME).fitMode,
                )
                assertEquals(
                    FitMode.FILL,
                    pairs.first().lock.framingFor(DisplayTarget.COVER, WallpaperSurface.LOCK).fitMode,
                )

                val stored = repository.getPair(pairId)
                assertNotNull(stored)
                val key = FramingKey(DisplayTarget.INNER, WallpaperSurface.HOME)
                val updatedFraming =
                    stored!!.home.framingFor(DisplayTarget.INNER, WallpaperSurface.HOME)
                        .copy(zoom = 2f)
                repository.updateAsset(
                    stored.home.copy(framings = stored.home.framings + (key to updatedFraming)),
                )
                assertEquals(
                    2f,
                    repository.getPair(pairId)!!
                        .home
                        .framingFor(DisplayTarget.INNER, WallpaperSurface.HOME)
                        .zoom,
                    0.0001f,
                )

                repository.updateRotationState(albumId, pairId, changedAt = 42L, shuffleBag = listOf(pairId))
                val album = repository.getAlbum(albumId)
                assertNotNull(album)
                assertEquals(pairId, album!!.lastAppliedPairId)
                assertEquals(42L, album.lastChangedAt)
            } finally {
                database.close()
            }
        }

    @Test
    fun `hiding an album keeps it in the list with the flag`() =
        runBlocking {
            val context: Context = RuntimeEnvironment.getApplication()
            val database =
                Room.inMemoryDatabaseBuilder(context, BackgroundedDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            try {
                val repository = AlbumRepository(database, ImageStore(context))
                val albumId = repository.createAlbum("Hidden")
                repository.setHidden(albumId, true)
                assertEquals(true, repository.getAlbum(albumId)?.isHidden)
            } finally {
                database.close()
            }
        }

    private fun background(albumId: Long): Background =
        Background(
            id = 0,
            albumId = albumId,
            sourceType = SourceType.IMPORT,
            storageRef = "unused",
            displayName = "image.jpg",
            sha256 = "hash",
            width = 100,
            height = 200,
            dimForLock = true,
            sortIndex = 0,
            addedAt = 1L,
            framings = Background.defaultFramings(),
        )
}
