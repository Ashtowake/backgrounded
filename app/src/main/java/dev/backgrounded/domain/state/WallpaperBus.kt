package dev.backgrounded.domain.state

import dev.backgrounded.domain.model.CurrentWallpaper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** In-process channel between wallpaper use cases and the wallpaper engines. */
@Singleton
class WallpaperBus
    @Inject
    constructor() {
        private val mutableState = MutableStateFlow(CurrentWallpaper.NONE)
        val state: StateFlow<CurrentWallpaper> = mutableState.asStateFlow()

        fun set(value: CurrentWallpaper) {
            mutableState.value = value
        }
    }
