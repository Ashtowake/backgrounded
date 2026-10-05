package dev.backgrounded.core.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DisplayRepositoryTest {
    @Test
    fun `single screen previews follow rotation without changing saved panel dimensions`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("display_panels", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        shadowOf(display).setRotation(Surface.ROTATION_0)
        val repository = DisplayRepository(context)
        val portrait = repository.targets().single()
        val savedPanels = preferences.getStringSet("panels", emptySet())

        shadowOf(display).setRotation(Surface.ROTATION_90)
        val landscape = repository.targets().single()
        assertEquals(portrait.target, landscape.target)
        assertEquals(portrait.width, landscape.height)
        assertEquals(portrait.height, landscape.width)
        assertEquals(savedPanels, preferences.getStringSet("panels", emptySet()))

        shadowOf(display).setRotation(Surface.ROTATION_0)
        assertEquals(portrait, repository.targets().single())
    }

    @Test
    fun `fold panels retain their individual compositions when the app rotates`() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("display_panels", Context.MODE_PRIVATE).edit()
            .clear()
            .putStringSet("panels", setOf("display:inner=2000x2100", "display:cover=1000x2400"))
            .commit()
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        shadowOf(display).setRotation(Surface.ROTATION_0)
        val repository = DisplayRepository(context)
        val portrait = repository.targets()
        assertEquals(2, portrait.size)

        shadowOf(display).setRotation(Surface.ROTATION_90)
        assertEquals(portrait, repository.targets())
    }
}
