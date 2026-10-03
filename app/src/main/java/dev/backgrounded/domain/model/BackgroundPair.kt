package dev.backgrounded.domain.model

data class BackgroundPair(
    val id: Long,
    val albumId: Long,
    val home: Background,
    val lock: Background,
    val sortIndex: Int,
    val addedAt: Long,
) {
    fun imageFor(surface: WallpaperSurface): Background =
        when (surface) {
            WallpaperSurface.HOME -> home
            WallpaperSurface.LOCK -> lock
        }
}
