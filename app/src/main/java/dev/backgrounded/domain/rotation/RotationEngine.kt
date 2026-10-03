package dev.backgrounded.domain.rotation

import dev.backgrounded.domain.model.RotationOrder
import kotlin.random.Random

object RotationEngine {
    data class Selection(val backgroundId: Long, val shuffleRemaining: List<Long>)

    fun next(
        currentId: Long?,
        orderedIds: List<Long>,
        order: RotationOrder,
        shuffleRemaining: List<Long>,
        random: Random = Random.Default,
    ): Selection? {
        if (orderedIds.isEmpty()) return null
        return when (order) {
            RotationOrder.SEQUENTIAL -> {
                val index = orderedIds.indexOf(currentId)
                val nextIndex = if (index < 0) 0 else (index + 1) % orderedIds.size
                Selection(orderedIds[nextIndex], emptyList())
            }

            RotationOrder.SHUFFLE -> {
                var bag = shuffleRemaining.filter { it in orderedIds && it != currentId }
                if (bag.isEmpty()) {
                    bag = orderedIds.filter { it != currentId }.shuffled(random)
                }
                if (bag.isEmpty()) return Selection(orderedIds.first(), emptyList())
                Selection(bag.first(), bag.drop(1))
            }
        }
    }

    fun previous(
        currentId: Long?,
        recentIds: List<Long>,
    ): Long? = recentIds.firstOrNull { it != currentId }
}
