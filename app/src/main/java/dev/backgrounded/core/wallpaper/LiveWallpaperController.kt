package dev.backgrounded.core.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.backgrounded.wallpaper.BackgroundedWallpaperService

/** Resolve chain for applying our live wallpaper, plus applied-state detection. */
object LiveWallpaperController {
    fun componentName(context: Context): ComponentName =
        ComponentName(context, BackgroundedWallpaperService::class.java)

    /**
     * Best available entry point on this device: the direct live wallpaper preview when the
     * platform exposes it, otherwise the system wallpaper picker.
     */
    fun applyIntent(context: Context): Intent {
        val component = componentName(context)
        val direct =
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        if (direct.resolveActivity(context.packageManager) != null) return direct
        val chooser = Intent(ACTION_LIVE_WALLPAPER_CHOOSER)
        if (chooser.resolveActivity(context.packageManager) != null) return chooser
        return Intent(Intent.ACTION_SET_WALLPAPER)
    }

    fun isApplied(
        context: Context,
        lockScreen: Boolean,
    ): Boolean {
        val manager = WallpaperManager.getInstance(context)
        val component = componentName(context)
        return runCatching {
            val info =
                if (lockScreen) {
                    if (android.os.Build.VERSION.SDK_INT >= 34) {
                        manager.getWallpaperInfo(WallpaperManager.FLAG_LOCK)
                    } else {
                        null
                    }
                } else {
                    @Suppress("DEPRECATION")
                    manager.wallpaperInfo
                }
            info?.component?.equals(component) == true
        }.getOrDefault(false)
    }

    const val ACTION_LIVE_WALLPAPER_CHOOSER = "android.service.wallpaper.LIVE_WALLPAPER_CHOOSER"
}
