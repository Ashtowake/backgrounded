package dev.backgrounded.core.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.domain.model.DisplayTarget
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class DisplayTargetInfo(
    val target: DisplayTarget,
    val width: Int,
    val height: Int,
)

/**
 * Classifies the device's panels as inner (larger, near-square) and cover (smaller, tall).
 *
 * Foldables only expose the currently enabled logical display to apps, so panels are
 * remembered by their stable [Display.getUniqueId] and merged with whatever is enabled now.
 * A panel seen for the first time is classified by aspect ratio until both sizes are known.
 */
@Singleton
class DisplayRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val displayManager = context.getSystemService(DisplayManager::class.java)
        private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        private val knownPanels = load()

        fun targets(): List<DisplayTargetInfo> {
            refreshFromEnabledDisplays()
            val sizes = knownPanels.values.distinct()
            if (sizes.isEmpty()) return listOf(DisplayTargetInfo(DisplayTarget.INNER, 0, 0))

            val sorted = sizes.sortedByDescending { it.first.toLong() * it.second }
            val largest = sorted.first()
            val second = sorted.getOrNull(1)

            if (second == null && aspectOf(largest) < FOLD_ASPECT_THRESHOLD) {
                return listOf(DisplayTargetInfo(DisplayTarget.COVER, largest.first, largest.second))
            }

            val result = mutableListOf(DisplayTargetInfo(DisplayTarget.INNER, largest.first, largest.second))
            if (second != null) {
                result += DisplayTargetInfo(DisplayTarget.COVER, second.first, second.second)
            }
            return result
        }

        fun targetForSurface(
            width: Int,
            height: Int,
        ): DisplayTarget {
            val targets = targets()
            val fallback = targets.firstOrNull()?.target ?: DisplayTarget.INNER
            if (targets.size < 2 || width <= 0 || height <= 0) return fallback
            targets.firstOrNull { it.width == width && it.height == height }?.let { return it.target }
            val surfaceAspect = width.toFloat() / height
            return targets
                .minByOrNull { info ->
                    if (info.height <= 0) return@minByOrNull Float.MAX_VALUE
                    abs(surfaceAspect - info.width.toFloat() / info.height)
                }?.target ?: fallback
        }

        /** Records a surface size reported by the wallpaper engine so unseen panels are learned. */
        fun rememberSurfaceSize(
            width: Int,
            height: Int,
        ) {
            if (width <= 0 || height <= 0) return
            if (knownPanels.values.any { it.first == width && it.second == height }) return
            knownPanels["$SURFACE_PREFIX$width" + "x" + height] = width to height
            persist()
        }

        private fun refreshFromEnabledDisplays() {
            currentDisplays().forEach { display ->
                val size = displaySize(display) ?: return@forEach
                val id = "$DISPLAY_PREFIX${display.displayId}"
                if (knownPanels[id] != size) {
                    knownPanels[id] = size
                    persist()
                }
            }
        }

        /**
         * Asks for all displays including disabled ones; foldables keep the inactive panel disabled,
         * and the default query hides it. Falls back to the standard query on any failure.
         */
        private fun currentDisplays(): List<Display> {
            val all = displayManager?.getDisplays(CATEGORY_ALL_INCLUDING_DISABLED)?.toList()
            if (!all.isNullOrEmpty()) return all
            return displayManager?.displays.orEmpty().toList()
        }

        @Suppress("DEPRECATION")
        private fun displaySize(display: Display): Pair<Int, Int>? {
            val mode = runCatching { display.mode }.getOrNull()
            if (mode != null && mode.physicalWidth > 0 && mode.physicalHeight > 0) {
                return mode.physicalWidth to mode.physicalHeight
            }
            val supportedModes =
                runCatching { display.supportedModes }
                    .getOrNull()
                    .orEmpty()
                    .filter { it.physicalWidth > 0 && it.physicalHeight > 0 }
            val best = supportedModes.maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight }
            if (best != null) return best.physicalWidth to best.physicalHeight
            if (display.width > 0 && display.height > 0) return display.width to display.height
            return null
        }

        private fun aspectOf(size: Pair<Int, Int>): Float = size.first.toFloat() / size.second

        private fun load(): MutableMap<String, Pair<Int, Int>> {
            val stored = preferences.getStringSet(KEY_PANELS, emptySet()).orEmpty()
            return stored
                .mapNotNull { entry ->
                    val parts = entry.split('=')
                    if (parts.size != 2) return@mapNotNull null
                    val dimensions = parts[1].split('x')
                    val width = dimensions.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    val height = dimensions.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    if (width <= 0 || height <= 0) return@mapNotNull null
                    parts[0] to (width to height)
                }
                .toMap()
                .toMutableMap()
        }

        private fun persist() {
            preferences.edit()
                .putStringSet(
                    KEY_PANELS,
                    knownPanels.entries
                        .map { (id, size) -> "$id=${size.first}x${size.second}" }
                        .toSet(),
                )
                .apply()
        }

        private companion object {
            const val PREFERENCES = "display_panels"
            const val KEY_PANELS = "panels"
            const val DISPLAY_PREFIX = "display:"
            const val SURFACE_PREFIX = "surface:"
            const val CATEGORY_ALL_INCLUDING_DISABLED =
                "android.hardware.display.category.ALL_INCLUDING_DISABLED"
            const val FOLD_ASPECT_THRESHOLD = 0.7f
        }
    }
