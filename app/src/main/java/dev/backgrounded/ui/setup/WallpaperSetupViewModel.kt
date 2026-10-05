package dev.backgrounded.ui.setup

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.core.wallpaper.LiveWallpaperController
import dev.backgrounded.data.datastore.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WallpaperSetupViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsStore: SettingsStore,
    ) : ViewModel() {
        private val mutableReady = MutableStateFlow<Boolean?>(null)
        val ready = mutableReady.asStateFlow()

        init {
            viewModelScope.launch {
                settingsStore.wallpaperSetupCompleted.collect { completed ->
                    if (completed) mutableReady.value = true else refresh()
                }
            }
        }

        fun refresh() {
            viewModelScope.launch {
                val completed = settingsStore.wallpaperSetupCompleted.first()
                val applied =
                    completed || LiveWallpaperController.isApplied(context, false) ||
                        LiveWallpaperController.isApplied(context, true)
                if (applied && !completed) settingsStore.completeWallpaperSetup()
                mutableReady.value = applied
            }
        }
    }
