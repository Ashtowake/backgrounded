package dev.backgrounded.widget

import android.content.Intent
import dev.backgrounded.core.security.HiddenSwitchAuthActivity
import dev.backgrounded.core.security.HiddenSwitchOperation
import dev.backgrounded.domain.model.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GalleryAuthenticationIntentTest {
    @Test
    fun galleryAuthenticationReturnsToCallerInsteadOfLaunchingSeparateTask() {
        val context = RuntimeEnvironment.getApplication()
        val reveal = HiddenSwitchAuthActivity.intent(context, Trigger.WIDGET, operation = HiddenSwitchOperation.REVEAL)
        assertEquals(0, reveal.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        val switch = HiddenSwitchAuthActivity.intent(context, Trigger.WIDGET)
        assertNotEquals(0, switch.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
