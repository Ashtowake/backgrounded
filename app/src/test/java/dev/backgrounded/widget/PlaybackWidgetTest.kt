package dev.backgrounded.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.ImageView
import android.widget.TextView
import dev.backgrounded.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlaybackWidgetTest {
    @Test
    fun searchOpensPopupDirectlyAndIsIndependentForEachWidget() {
        val context: Context = RuntimeEnvironment.getApplication()
        val intents =
            listOf(101, 202).map { id ->
                val view = PlaybackWidget.buildViews(context, id, "Album", false).apply(context, null)
                assertEquals("Choose wallpaper", view.findViewById<ImageView>(R.id.control_search).contentDescription)
                val intent =
                    Intent(context, GalleryPickerActivity::class.java)
                        .setData(Uri.parse("backgrounded://gallery/$id"))
                val pending =
                    PendingIntent.getActivity(
                        context,
                        id,
                        intent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                    )
                assertNotNull(pending)
                assertEquals(GalleryPickerActivity::class.java.name, shadowOf(pending).savedIntent.component?.className)
                pending
            }
        assertNotEquals(intents[0], intents[1])
    }

    @Test
    fun widgetShowsAlbumAndPauseStateAndLabelsEveryControl() {
        val context: Context = RuntimeEnvironment.getApplication()
        val views = PlaybackWidget.buildViews(context, 101, "Test album", false).apply(context, null)
        assertEquals("Test album", views.findViewById<TextView>(R.id.playback_album_name).text.toString())
        PlaybackAction.entries.forEach { action ->
            assertEquals(action.label(false), views.findViewById<ImageView>(action.viewId).contentDescription)
        }
        val paused = PlaybackWidget.buildViews(context, 101, "Other album", true).apply(context, null)
        val button = paused.findViewById<ImageView>(PlaybackAction.TOGGLE_PAUSE.viewId)
        assertEquals("Play", button.contentDescription)
        assertNotNull(button.drawable)
    }

    @Test
    fun buttonIntentsRemainDistinctAcrossActionsAndWidgetInstances() {
        val context: Context = RuntimeEnvironment.getApplication()
        PlaybackWidget.buildViews(context, 101, "One", false)
        PlaybackWidget.buildViews(context, 202, "Two", false)
        val intents = mutableListOf<PendingIntent>()
        listOf(101, 202).forEach { id ->
            PlaybackAction.entries.forEach { action ->
                val intent =
                    Intent(context, WidgetActionReceiver::class.java)
                        .setAction(WidgetActionReceiver.ACTION_CONTROL)
                        .setData(Uri.parse("backgrounded://playback/$id/${action.name}"))
                val pending =
                    PendingIntent.getBroadcast(
                        context,
                        id,
                        intent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                    )
                assertNotNull(pending)
                assertEquals(
                    action.name,
                    shadowOf(pending).savedIntent.getStringExtra(WidgetActionReceiver.EXTRA_CONTROL),
                )
                intents.forEach { assertNotEquals(it, pending) }
                intents.add(pending)
            }
        }
    }
}
