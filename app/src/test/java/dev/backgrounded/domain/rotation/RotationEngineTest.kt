package dev.backgrounded.domain.rotation

import dev.backgrounded.domain.model.RotationOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RotationEngineTest {
    @Test
    fun `sequential advances and wraps`() {
        val ids = listOf(10L, 20L, 30L)
        assertEquals(20L, RotationEngine.next(10L, ids, RotationOrder.SEQUENTIAL, emptyList())?.backgroundId)
        assertEquals(10L, RotationEngine.next(30L, ids, RotationOrder.SEQUENTIAL, emptyList())?.backgroundId)
        assertEquals(10L, RotationEngine.next(null, ids, RotationOrder.SEQUENTIAL, emptyList())?.backgroundId)
    }

    @Test
    fun `sequential with single item repeats it`() {
        val result = RotationEngine.next(1L, listOf(1L), RotationOrder.SEQUENTIAL, emptyList())
        assertEquals(1L, result?.backgroundId)
    }

    @Test
    fun `shuffle never repeats immediately`() {
        val ids = listOf(1L, 2L, 3L, 4L)
        var current: Long? = 1L
        repeat(20) {
            val selection =
                RotationEngine.next(
                    currentId = current,
                    orderedIds = ids,
                    order = RotationOrder.SHUFFLE,
                    shuffleRemaining = emptyList(),
                    random = Random(7),
                )
            assertNotEquals(current, selection?.backgroundId)
            current = selection?.backgroundId
        }
    }

    @Test
    fun `shuffle drains the bag before reshuffling`() {
        val ids = listOf(1L, 2L, 3L)
        var bag = emptyList<Long>()
        val seen = mutableListOf<Long>()
        var current: Long? = null
        repeat(3) {
            val selection = RotationEngine.next(current, ids, RotationOrder.SHUFFLE, bag, Random(3))
            bag = selection?.shuffleRemaining.orEmpty()
            seen += selection?.backgroundId ?: -1L
            current = selection?.backgroundId
        }
        assertEquals(ids.toSet(), seen.toSet())
    }

    @Test
    fun `shuffle single item repeats`() {
        val selection = RotationEngine.next(5L, listOf(5L), RotationOrder.SHUFFLE, emptyList())
        assertEquals(5L, selection?.backgroundId)
    }

    @Test
    fun `empty album yields null`() {
        assertNull(RotationEngine.next(null, emptyList(), RotationOrder.SEQUENTIAL, emptyList()))
    }

    @Test
    fun `previous skips the current id and uses history order`() {
        assertEquals(7L, RotationEngine.previous(9L, listOf(9L, 7L, 3L)))
        assertEquals(3L, RotationEngine.previous(9L, listOf(3L, 9L)))
        assertNull(RotationEngine.previous(9L, listOf(9L)))
        assertTrue(RotationEngine.previous(null, listOf(1L, 2L)) == 1L)
    }
}
