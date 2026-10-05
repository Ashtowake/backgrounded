package dev.backgrounded.core.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.domain.model.DisplayTarget
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

data class DisplayTargetInfo(
    val target: DisplayTarget,
    val width: Int,
    val height: Int,
)

/**
 * Classifies the device's panels as inner (larger, near-square) and cover (smaller, tall).
 *
 * Foldables can expose only the currently enabled logical display to apps. Physical mode
 * sizes from enabled displays are remembered; wallpaper surface sizes are never panel sizes.
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

        init {
            persist()
        }

        fun targets(): List<DisplayTargetInfo> {
            refreshFromEnabledDisplays()
            val sizes = knownPanels.values.distinct()
            if (sizes.isEmpty()) return listOf(DisplayTargetInfo(DisplayTarget.INNER, 0, 0))
            val inner =
                sizes.filter { aspectOf(it) >= FOLD_ASPECT_THRESHOLD }
                    .maxByOrNull { it.first.toLong() * it.second }
            val cover =
                sizes.filter { aspectOf(it) < FOLD_ASPECT_THRESHOLD }
                    .maxByOrNull { it.first.toLong() * it.second }
            return buildList {
                if (inner != null) add(DisplayTargetInfo(DisplayTarget.INNER, inner.first, inner.second))
                if (cover != null) add(DisplayTargetInfo(DisplayTarget.COVER, cover.first, cover.second))
            }
        }

        fun targetForSurface(
            width: Int,
            height: Int,
        ): DisplayTarget {
            if (width <= 0 || height <= 0) return targets().firstOrNull()?.target ?: DisplayTarget.INNER
            return if (min(width, height).toFloat() / max(width, height) < FOLD_ASPECT_THRESHOLD) {
                DisplayTarget.COVER
            } else {
                DisplayTarget.INNER
            }
        }

        fun activeTarget(): DisplayTarget {
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            val size = display?.let(::displaySize) ?: return targets().firstOrNull()?.target ?: DisplayTarget.INNER
            return targetForSurface(size.first, size.second)
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

        private fun aspectOf(size: Pair<Int, Int>): Float =
            min(size.first, size.second).toFloat() / max(size.first, size.second)

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
                    if (!parts[0].startsWith(DISPLAY_PREFIX)) return@mapNotNull null
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
            const val CATEGORY_ALL_INCLUDING_DISABLED =
                "android.hardware.display.category.ALL_INCLUDING_DISABLED"
            const val FOLD_ASPECT_THRESHOLD = 0.7f
        }
    }
