package dev.backgrounded.domain.model

enum class WallpaperSurface {
    HOME,
    LOCK,
    ;

    companion object {
        fun from(value: String?): WallpaperSurface = entries.firstOrNull { it.name == value } ?: HOME
    }
}

data class FramingKey(
    val display: DisplayTarget,
    val surface: WallpaperSurface,
)
