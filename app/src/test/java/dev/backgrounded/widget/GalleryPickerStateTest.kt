package dev.backgrounded.widget

import dev.backgrounded.domain.model.Album
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.BackgroundPair
import dev.backgrounded.domain.model.RotationOrder
import dev.backgrounded.domain.model.ScheduleType
import dev.backgrounded.domain.model.SourceType
import dev.backgrounded.domain.model.UnlockPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryPickerStateTest {
    private val visible = album(1, false)
    private val hidden = album(2, true)
    private val otherHidden = album(3, true)
    private val albums = listOf(visible, hidden, otherHidden)

    @Test
    fun visibleActiveAlbumConcealsHiddenRowsAndPairsUntilAuthentication() {
        val state = GalleryPickerState(albums, visible.id, hidden.id, listOf(pair(hidden.id)))
        assertEquals(listOf(visible), state.visibleAlbums)
        assertTrue(state.visiblePairs.isEmpty())
        val revealed = state.copy(authenticated = true)
        assertEquals(albums, revealed.visibleAlbums)
        assertEquals(1, revealed.visiblePairs.size)
        assertEquals(hidden.name, revealed.selectedAlbum?.name)
        assertTrue(revealed.copy(authenticated = false).visiblePairs.isEmpty())
    }

    @Test
    fun activeHiddenAlbumShowsAllAlbumsAndNamesWithoutAnotherPrompt() {
        val state = GalleryPickerState(albums, hidden.id, otherHidden.id, listOf(pair(otherHidden.id)))
        assertFalse(state.authenticated)
        assertTrue(state.showHidden)
        assertEquals(albums, state.visibleAlbums)
        assertEquals(otherHidden.name, state.selectedAlbum?.name)
        assertEquals(1, state.visiblePairs.size)
        assertTrue(state.copy(activeAlbumId = visible.id).visiblePairs.isEmpty())
    }

    @Test
    fun switchingDirectoriesDoesNotShowPairsFromPreviousAlbum() {
        val state = GalleryPickerState(albums, hidden.id, visible.id, listOf(pair(hidden.id)))
        assertTrue(state.visiblePairs.isEmpty())
        assertTrue(state.copy(selectedAlbumId = null).visiblePairs.isEmpty())
        assertTrue(state.copy(selectedAlbumId = 999).visiblePairs.isEmpty())
    }

    private fun album(
        id: Long,
        hidden: Boolean,
    ) = Album(
        id = id, name = "Album $id", coverPairId = null, fixedHomeAssetId = null, fixedLockAssetId = null,
        isHidden = hidden, rotationOrder = RotationOrder.SEQUENTIAL, scheduleType = ScheduleType.INTERVAL,
        intervalMinutes = 60, fixedTimes = emptyList(), unlockPolicy = UnlockPolicy(false, 0, 1, 0),
        lastAppliedPairId = null, lastChangedAt = 0, shuffleRemaining = emptyList(), sortIndex = id.toInt(),
    )

    private fun pair(albumId: Long): BackgroundPair {
        val image =
            Background(
                id = albumId, albumId = albumId, sourceType = SourceType.IMPORT, storageRef = "test.png",
                displayName = "test", sha256 = null, width = 1, height = 1, dimForLock = false, sortIndex = 0,
                addedAt = 0, framings = Background.defaultFramings(),
            )
        return BackgroundPair(id = albumId, albumId = albumId, home = image, lock = image, sortIndex = 0, addedAt = 0)
    }
}
