package dev.backgrounded.domain.rotation

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class AlbumRotationTest {
    private val ids = listOf(10L, 20L, 30L)

    @Test
    fun nextWrapsAndLeavesCurrentAsLastFallback() {
        assertEquals(listOf(10L, 20L, 30L), AlbumRotation.candidates(ids, 30L, AlbumSwitchMode.NEXT))
    }

    @Test
    fun previousWrapsAndLeavesCurrentAsLastFallback() {
        assertEquals(listOf(30L, 20L, 10L), AlbumRotation.candidates(ids, 10L, AlbumSwitchMode.PREVIOUS))
    }

    @Test
    fun missingCurrentStartsAtCorrespondingEnd() {
        assertEquals(ids, AlbumRotation.candidates(ids, null, AlbumSwitchMode.NEXT))
        assertEquals(ids.reversed(), AlbumRotation.candidates(ids, 99L, AlbumSwitchMode.PREVIOUS))
    }

    @Test
    fun randomIncludesEveryCandidateOnceAndAvoidsCurrentUntilLast() {
        repeat(20) { seed ->
            val candidates = AlbumRotation.candidates(ids, 20L, AlbumSwitchMode.RANDOM, Random(seed))
            assertEquals(ids.toSet(), candidates.toSet())
            assertEquals(ids.size, candidates.size)
            assertEquals(20L, candidates.last())
        }
    }

    @Test
    fun emptyAndSingleAlbumWorkForEveryMode() {
        AlbumSwitchMode.entries.forEach { mode ->
            assertEquals(emptyList<Long>(), AlbumRotation.candidates(emptyList(), null, mode))
            assertEquals(listOf(10L), AlbumRotation.candidates(listOf(10L), 10L, mode))
        }
    }
}
