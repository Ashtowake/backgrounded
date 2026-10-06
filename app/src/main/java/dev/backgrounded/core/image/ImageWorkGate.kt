package dev.backgrounded.core.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One native decode, with visible wallpaper requests ahead of waiting preview requests. */
class ImageWorkGate {
    private class Ticket(val priority: Boolean) {
        val ready = CompletableDeferred<Unit>()
        var granted = false
    }

    private val mutex = Mutex()
    private val waiting = ArrayDeque<Ticket>()
    private var occupied = false

    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> run(
        priority: Boolean,
        work: suspend () -> T,
    ): T {
        val ticket = Ticket(priority)
        mutex.withLock {
            if (!occupied) {
                occupied = true
                ticket.granted = true
                ticket.ready.complete(Unit)
            } else {
                if (waiting.size >= 128) throw ImageResourceException("Image work queue full")
                waiting.addLast(ticket)
            }
        }
        try {
            ticket.ready.await()
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                mutex.withLock { if (ticket.granted) release() else waiting.remove(ticket) }
            }
            throw failure
        }
        try {
            return work()
        } finally {
            withContext(NonCancellable) { mutex.withLock { release() } }
        }
    }

    private fun release() {
        val next = waiting.firstOrNull { it.priority } ?: waiting.firstOrNull()
        if (next == null) {
            occupied = false
        } else {
            waiting.remove(next)
            next.granted = true
            next.ready.complete(Unit)
        }
    }
}
