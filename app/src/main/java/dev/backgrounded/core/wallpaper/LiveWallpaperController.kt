package dev.backgrounded.core.wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.backgrounded.wallpaper.BackgroundedWallpaperService

/** Launch our live wallpaper preview with fallbacks for unsupported system entry points. */
object LiveWallpaperController {
    fun componentName(context: Context): ComponentName =
        ComponentName(context, BackgroundedWallpaperService::class.java)

    // Package visibility can hide a launchable activity from resolveActivity(). Try launching instead.
    @Suppress("SwallowedException")
    fun launchApply(context: Context): Boolean {
        val component = componentName(context)
        val direct =
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        val intents =
            listOf(
                direct,
                Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
                Intent(Intent.ACTION_SET_WALLPAPER),
            )
        for (intent in intents) {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Unsupported entry point; try the next system picker.
            } catch (_: SecurityException) {
                // Some OEMs restrict an otherwise available entry point.
            }
        }
        return false
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
}
