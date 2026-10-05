package dev.backgrounded.domain.usecase

import android.content.Context
import androidx.room.Room
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.importer.ImageStore
import dev.backgrounded.data.repository.AlbumRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HiddenAlbumSwitchPolicyTest {
    @Test
    fun activeHiddenAlbumAvoidsRepeatedAuthenticationButVisibleAlbumDoesNot() =
        runBlocking {
            val context: Context = RuntimeEnvironment.getApplication()
            val database =
                Room.inMemoryDatabaseBuilder(context, BackgroundedDatabase::class.java)
                    .allowMainThreadQueries().build()
            try {
                val repository = AlbumRepository(database, ImageStore(context))
                val settings = SettingsStore(context)
                val visible = repository.createAlbum("Visible")
                val firstHidden = repository.createAlbum("First hidden")
                val secondHidden = repository.createAlbum("Second hidden")
                repository.setHidden(firstHidden, true)
                repository.setHidden(secondHidden, true)
                settings.setAuthenticateHiddenSwitch(true)
                settings.setActiveAlbum(visible)
                val policy = HiddenAlbumSwitchPolicy(settings, repository)
                assertTrue(policy.requiresAuthentication(firstHidden))
                assertFalse(policy.requiresAuthentication(visible))
                settings.setActiveAlbum(firstHidden)
                assertFalse(policy.requiresAuthentication(firstHidden))
                assertFalse(policy.requiresAuthentication(secondHidden))
                settings.setActiveAlbum(visible)
                assertTrue(policy.requiresAuthentication(secondHidden))
                settings.setAuthenticateHiddenSwitch(false)
                assertFalse(policy.requiresAuthentication(secondHidden))
            } finally {
                database.close()
            }
        }
}
