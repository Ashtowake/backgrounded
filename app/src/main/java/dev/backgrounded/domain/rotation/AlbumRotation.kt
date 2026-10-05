package dev.backgrounded.domain.rotation

import kotlin.random.Random

enum class AlbumSwitchMode { NEXT, PREVIOUS, RANDOM }

object AlbumRotation {
    fun candidates(
        ids: List<Long>,
        currentId: Long?,
        mode: AlbumSwitchMode,
        random: Random = Random.Default,
    ): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val index = ids.indexOf(currentId)
        return when (mode) {
            AlbumSwitchMode.NEXT -> ids.indices.map { ids[(index + it + 1) % ids.size] }
            AlbumSwitchMode.PREVIOUS ->
                ids.indices.map {
                    ids[((if (index < 0) 0 else index) - it - 1 + ids.size * 2) % ids.size]
                }
            AlbumSwitchMode.RANDOM -> ids.filter { it != currentId }.shuffled(random) + ids.filter { it == currentId }
        }
    }
}
