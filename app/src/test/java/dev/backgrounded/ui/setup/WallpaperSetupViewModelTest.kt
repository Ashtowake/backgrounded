package dev.backgrounded.ui.setup

import androidx.lifecycle.ViewModelStore
import dev.backgrounded.core.wallpaper.LiveWallpaperController
import dev.backgrounded.data.datastore.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WallpaperSetupViewModelTest {
    @Test
    fun `setup gates first launch but completed setup survives restart without reapplying`() =
        runBlocking {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val store = ViewModelStore()
            try {
                val context = RuntimeEnvironment.getApplication()
                val settings = SettingsStore(context)
                assertFalse(LiveWallpaperController.isApplied(context, false))
                val firstLaunch = WallpaperSetupViewModel(context, settings)
                store.put("first", firstLaunch)
                assertFalse(withTimeout(10000) { firstLaunch.ready.filterNotNull().first() })

                settings.completeWallpaperSetup()
                assertTrue(withTimeout(10000) { firstLaunch.ready.first { it == true } } == true)
                val restarted = WallpaperSetupViewModel(context, settings)
                store.put("restarted", restarted)
                assertTrue(withTimeout(10000) { restarted.ready.filterNotNull().first() })
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
}
