package dev.backgrounded.core.wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveWallpaperControllerTest {
    @Test
    fun `hidden query results never prevent launching the exact wallpaper preview`() {
        val context = RecordingContext()
        assertTrue(LiveWallpaperController.launchApply(context))
        val intent = context.attempts.single()
        assertEquals(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER, intent.action)
        assertEquals(
            LiveWallpaperController.componentName(context),
            intent.getParcelableExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                android.content.ComponentName::class.java,
            ),
        )
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `missing direct preview falls back to live wallpaper chooser`() {
        val context =
            RecordingContext(mapOf(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER to ActivityNotFoundException()))
        assertTrue(LiveWallpaperController.launchApply(context))
        assertEquals(
            listOf(
                WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER,
                WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER,
            ),
            context.attempts.map {
                it.action
            },
        )
    }

    @Test
    fun `restricted direct entry and missing chooser fall back to generic picker`() {
        val context =
            RecordingContext(
                mapOf(
                    WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER to SecurityException(),
                    WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER to ActivityNotFoundException(),
                ),
            )
        assertTrue(LiveWallpaperController.launchApply(context))
        assertEquals(Intent.ACTION_SET_WALLPAPER, context.attempts.last().action)
        assertEquals(3, context.attempts.size)
    }

    @Test
    fun `no usable picker returns failure without crashing`() {
        val context =
            RecordingContext(
                mapOf(
                    WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER to ActivityNotFoundException(),
                    WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER to SecurityException(),
                    Intent.ACTION_SET_WALLPAPER to ActivityNotFoundException(),
                ),
            )
        assertFalse(LiveWallpaperController.launchApply(context))
        assertEquals(3, context.attempts.size)
    }

    @Test
    fun `activity launch preserves the current task`() {
        val activity = Robolectric.buildActivity(RecordingActivity::class.java).create().get()
        try {
            assertTrue(LiveWallpaperController.launchApply(activity))
            assertEquals(0, activity.attempts.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        } finally {
            activity.finish()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unexpected errors are not hidden by picker fallbacks`() {
        LiveWallpaperController.launchApply(
            RecordingContext(mapOf(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER to IllegalArgumentException())),
        )
    }

    private class RecordingContext(
        private val rejected: Map<String, RuntimeException> = emptyMap(),
    ) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val attempts = mutableListOf<Intent>()

        override fun getPackageManager(): PackageManager = error("Picker launches must not query package visibility")

        override fun startActivity(intent: Intent) {
            attempts.add(Intent(intent))
            rejected[intent.action]?.let { throw it }
        }
    }

    class RecordingActivity : Activity() {
        val attempts = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            attempts.add(Intent(intent))
        }
    }
}
