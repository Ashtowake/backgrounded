package dev.backgrounded.domain.rotation

import androidx.room.Room
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.db.PlaybackCommitEntity
import dev.backgrounded.domain.state.WallpaperBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RotationCoordinatorTest {
    @Test fun `unfinished playback commit recovers preferences and published selection`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val db = Room.inMemoryDatabaseBuilder(context, BackgroundedDatabase::class.java).build()
            try {
                val store = SettingsStore(context)
                val bus = WallpaperBus()
                val coordinator = RotationCoordinator(db, store, bus, context)
                db.hardeningDao().recordPlayback(
                    PlaybackCommitEntity(
                        albumId = 7,
                        pairId = 8,
                        changedAt = 100,
                        paused = true,
                        unlockCommit = "100,0,1,20000",
                    ),
                )
                coordinator.run(Unit) { }
                val settings = store.settings.first()
                assertEquals(7L, settings.activeAlbumId)
                assertEquals(8L, settings.currentPairId)
                assertEquals(1, settings.unlockState.appliedToday)
                assertTrue(settings.rotationPaused)
                assertEquals(8L, bus.state.value.pairId)
                assertNull(db.hardeningDao().pendingPlayback())
            } finally {
                db.close()
            }
        }

    @Test fun `rapid manual commands reject overflow and queued work stays serialized`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val db = Room.inMemoryDatabaseBuilder(context, BackgroundedDatabase::class.java).build()
            try {
                val coordinator = RotationCoordinator(db, SettingsStore(context), WallpaperBus(), context)
                val finish = CompletableDeferred<Unit>()
                val started = CompletableDeferred<Unit>()
                val active =
                    launch {
                        coordinator.run(RotationResult.Busy) {
                            started.complete(Unit)
                            finish.await()
                            RotationResult.Applied
                        }
                    }
                started.await()
                val results = mutableListOf<RotationResult>()
                val queued =
                    (1..40).map {
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            results.add(coordinator.run(RotationResult.Busy) { RotationResult.Applied })
                        }
                    }
                finish.complete(Unit)
                active.join()
                queued.forEach { it.join() }
                assertTrue(results.count { it == RotationResult.Busy } >= 8)
                assertTrue(results.any { it == RotationResult.Applied })
            } finally {
                db.close()
            }
        }
}
