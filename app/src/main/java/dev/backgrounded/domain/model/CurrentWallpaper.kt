package dev.backgrounded.domain.model

data class CurrentWallpaper(
    val pairId: Long?,
    val albumId: Long?,
    val changedAt: Long,
) {
    companion object {
        val NONE = CurrentWallpaper(pairId = null, albumId = null, changedAt = 0L)
    }
}
