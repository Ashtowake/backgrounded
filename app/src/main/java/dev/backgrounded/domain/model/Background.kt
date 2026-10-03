package dev.backgrounded.domain.model

data class Background(
    val id: Long,
    val albumId: Long,
    val sourceType: SourceType,
    val storageRef: String,
    val displayName: String,
    val sha256: String?,
    val width: Int,
    val height: Int,
    val dimForLock: Boolean,
    val sortIndex: Int,
    val addedAt: Long,
    val framings: Map<FramingKey, Framing>,
) {
    fun framingFor(
        display: DisplayTarget,
        surface: WallpaperSurface,
    ): Framing = framings[FramingKey(display, surface)] ?: Framing.DEFAULT

    companion object {
        fun defaultFramings(): Map<FramingKey, Framing> = keys().associateWith { Framing.DEFAULT }

        fun keys(): List<FramingKey> =
            DisplayTarget.entries.flatMap { display ->
                WallpaperSurface.entries.map { surface -> FramingKey(display, surface) }
            }
    }
}

data class NormalizedCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f,
)

data class HistoryEntry(
    val id: Long,
    val pairId: Long,
    val albumId: Long,
    val appliedAt: Long,
    val trigger: Trigger,
)
