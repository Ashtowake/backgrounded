package dev.backgrounded.core.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageWorkGateTest {
    @Test fun `visible wallpaper goes before queued previews and cancelled tickets release`() =
        runBlocking {
            val gate = ImageWorkGate()
            val finish = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val active = launch(start = CoroutineStart.UNDISPATCHED) { gate.run(false) { finish.await() } }
            val preview = launch(start = CoroutineStart.UNDISPATCHED) { gate.run(false) { order.add("preview") } }
            val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { gate.run(true) { order.add("cancelled") } }
            cancelled.cancelAndJoin()
            val wallpaper = launch(start = CoroutineStart.UNDISPATCHED) { gate.run(true) { order.add("wallpaper") } }
            finish.complete(Unit)
            active.join()
            preview.join()
            wallpaper.join()
            assertEquals(listOf("wallpaper", "preview"), order)
            gate.run(true) { order.add("next") }
            assertEquals("next", order.last())
        }
}
