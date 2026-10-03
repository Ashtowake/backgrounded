package dev.backgrounded.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.data.backup.BackupManager
import dev.backgrounded.data.datastore.Settings
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.domain.model.DoubleTapMode
import dev.backgrounded.domain.model.GestureAction
import dev.backgrounded.domain.usecase.TogglePause
import dev.backgrounded.shortcuts.ActionTrampolineActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsStore: SettingsStore,
        private val togglePauseUseCase: TogglePause,
        private val backupManager: BackupManager,
    ) : ViewModel() {
        val settings: StateFlow<Settings> =
            settingsStore.settings
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), Settings.DEFAULTS)

        fun togglePause() = viewModelScope.launch { togglePauseUseCase() }

        fun setDoubleTapEnabled(enabled: Boolean) {
            val current = settings.value
            viewModelScope.launch {
                settingsStore.setDoubleTap(enabled, current.doubleTapMode, current.doubleTapAction)
            }
        }

        fun setDoubleTapMode(mode: DoubleTapMode) {
            val current = settings.value
            viewModelScope.launch {
                settingsStore.setDoubleTap(current.doubleTapEnabled, mode, current.doubleTapAction)
            }
        }

        fun setDoubleTapAction(action: GestureAction) {
            val current = settings.value
            viewModelScope.launch {
                settingsStore.setDoubleTap(current.doubleTapEnabled, current.doubleTapMode, action)
            }
        }

        fun setCrossfade(enabled: Boolean) =
            viewModelScope.launch {
                settingsStore.setCrossfade(enabled)
            }

        fun setLockDimDefault(enabled: Boolean) =
            viewModelScope.launch {
                settingsStore.setLockDimDefault(enabled)
            }

        fun setCrossfadeDuration(durationMs: Int) =
            viewModelScope.launch {
                settingsStore.setCrossfadeDuration(durationMs)
            }

        fun setExternalControl(enabled: Boolean) {
            val newState =
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, ActionTrampolineActivity::class.java),
                newState,
                PackageManager.DONT_KILL_APP,
            )
            viewModelScope.launch { settingsStore.setExternalControl(enabled) }
        }

        fun exportTo(
            uri: Uri,
            onResult: (Boolean) -> Unit,
        ) {
            viewModelScope.launch {
                val success =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { stream ->
                                stream.write(backupManager.exportJson().toByteArray())
                                true
                            } ?: false
                        }.getOrDefault(false)
                    }
                onResult(success)
            }
        }

        fun importFrom(
            uri: Uri,
            onResult: (Boolean) -> Unit,
        ) {
            viewModelScope.launch {
                val success =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val text =
                                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                                    it.readText()
                                } ?: return@runCatching false
                            backupManager.importJson(text)
                        }.getOrDefault(false)
                    }
                onResult(success)
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
