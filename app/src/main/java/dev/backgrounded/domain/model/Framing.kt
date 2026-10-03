package dev.backgrounded.domain.model

enum class ScrollMode {
    OFF,
    AMOUNT,
    PAGES,
    CUSTOM,
    ;

    companion object {
        fun from(value: String?): ScrollMode = entries.firstOrNull { it.name == value } ?: OFF
    }
}

data class Framing(
    val fitMode: FitMode,
    val crop: NormalizedCrop,
    val zoom: Float,
    val panX: Float,
    val panY: Float,
    val stretchX: Float,
    val stretchY: Float,
    val rotationDegrees: Int,
    val backdrop: BackdropType,
    val blurIntensity: Int,
    val backdropZoom: Float,
    val backdropPanX: Float,
    val backdropPanY: Float,
    val backdropColor: Int,
    val scrollMode: ScrollMode,
    val scrollAmountPercent: Int,
    val scrollPages: Int,
    val scrollStartFraction: Float,
    val scrollSpanFraction: Float,
    val gyroParallax: Boolean,
    val gyroIntensity: Int,
) {
    companion object {
        const val DEFAULT_BLUR_INTENSITY = 35
        const val DEFAULT_STRETCH = 1f
        const val MIN_STRETCH = 0.1f
        const val MAX_STRETCH = 3f
        const val DEFAULT_GYRO_INTENSITY = 50

        val DEFAULT =
            Framing(
                fitMode = FitMode.FILL,
                crop = NormalizedCrop(),
                zoom = 1f,
                panX = 0f,
                panY = 0f,
                stretchX = DEFAULT_STRETCH,
                stretchY = DEFAULT_STRETCH,
                rotationDegrees = 0,
                backdrop = BackdropType.BLUR,
                blurIntensity = DEFAULT_BLUR_INTENSITY,
                backdropZoom = 1f,
                backdropPanX = 0f,
                backdropPanY = 0f,
                backdropColor = 0xFF000000.toInt(),
                scrollMode = ScrollMode.OFF,
                scrollAmountPercent = 0,
                scrollPages = 3,
                scrollStartFraction = 0f,
                scrollSpanFraction = 1f,
                gyroParallax = false,
                gyroIntensity = DEFAULT_GYRO_INTENSITY,
            )
    }
}
