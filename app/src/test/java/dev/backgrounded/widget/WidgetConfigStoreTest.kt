package dev.backgrounded.widget

import android.content.Context
import dev.backgrounded.domain.model.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WidgetConfigStoreTest {
    @Test
    fun `widget settings are isolated and deletion preserves other widgets`() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("widget_instances", Context.MODE_PRIVATE).edit().clear().commit()
        val store = WidgetConfigStore(context)
        val first = WidgetConfig(iconSource = "builtin:pause", tapAction = GestureAction.TOGGLE_PAUSE)
        val second = WidgetConfig(iconSource = "builtin:album", doubleTapAction = GestureAction.PREVIOUS)

        store.put(101, first)
        store.put(202, second)
        val reloaded = WidgetConfigStore(context)
        assertEquals(first, reloaded.get(101))
        assertEquals(second, reloaded.get(202))

        store.remove(101)
        assertEquals(WidgetConfig(), store.get(101))
        assertEquals(second, store.get(202))
    }
}
