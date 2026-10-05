package dev.backgrounded.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.core.diagnostics.LocalDiagnostics
import dev.backgrounded.core.security.EncryptedImageStore
import dev.backgrounded.core.security.PinVault
import dev.backgrounded.data.backup.BackupManager
import dev.backgrounded.data.backup.ImageRecovery
import dev.backgrounded.data.backup.ImageRecoveryResult
import dev.backgrounded.data.datastore.Settings
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.importer.ManagedFolderStore
import dev.backgrounded.data.importer.ManagedSourceMover
import dev.backgrounded.domain.model.DoubleTapMode
import dev.backgrounded.domain.model.GestureAction
import dev.backgrounded.domain.usecase.TogglePause
import dev.backgrounded.shortcuts.ActionTrampolineActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
@Suppress("LongParameterList")
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsStore: SettingsStore,
        private val togglePauseUseCase: TogglePause,
        private val backupManager: BackupManager,
        private val imageRecovery: ImageRecovery,
        private val managedFolderStore: ManagedFolderStore,
        private val encryptedImageStore: EncryptedImageStore,
        private val pinVault: PinVault,
        private val diagnostics: LocalDiagnostics,
        private val db: BackgroundedDatabase,
        private val sourceMover: ManagedSourceMover,
    ) : ViewModel() {
        val settings: StateFlow<Settings> =
            settingsStore.settings
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), Settings.DEFAULTS)

        private val mutableMissingImages = MutableStateFlow(0)
        val missingImages = mutableMissingImages.asStateFlow()
        private val mutableRecoveringImages = MutableStateFlow(false)
        val recoveringImages = mutableRecoveringImages.asStateFlow()

        init {
            viewModelScope.launch { mutableMissingImages.value = imageRecovery.missingCount() }
        }

        fun restoreImages(
            tree: Uri? = null,
            onResult: (ImageRecoveryResult) -> Unit,
        ) {
            if (mutableRecoveringImages.value) return
            mutableRecoveringImages.value = true
            viewModelScope.launch {
                try {
                    val result = imageRecovery.restore(tree)
                    mutableMissingImages.value = result.missing
                    onResult(result)
                } finally {
                    mutableRecoveringImages.value = false
                }
            }
        }

        val managedFolders: StateFlow<Set<String>> = managedFolderStore.folders

        val unresolvedFiles =
            db.managedSourceDao().observeUnresolved()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        fun retryRestoration(onResult: (List<String>) -> Unit) {
            viewModelScope.launch {
                val albumIds =
                    unresolvedFiles.value.mapNotNull { db.backgroundDao().get(it.assetId)?.albumId }
                        .distinct().filter { db.albumDao().get(it)?.isHidden == false }
                onResult(albumIds.flatMap { sourceMover.restoreAlbum(it) })
            }
        }

        fun grantFolder(uri: Uri): Boolean = managedFolderStore.grant(uri)

        fun hasFolderWrite(uri: Uri): Boolean = managedFolderStore.hasWrite(uri)

        fun revokeFolder(uri: Uri) = managedFolderStore.revoke(uri)

        fun setHideSources(enabled: Boolean) =
            viewModelScope.launch {
                settingsStore.setHideSourcesSystemwide(enabled)
            }

        fun setAuthenticateHiddenSwitch(enabled: Boolean) =
            viewModelScope.launch { settingsStore.setAuthenticateHiddenSwitch(enabled) }

        fun pinConfigured() = pinVault.configured()

        fun systemConfigured() = pinVault.biometricConfigured()

        fun prepareSystemKey() = pinVault.prepareSystemKey()

        fun removePin() = pinVault.removePin()

        fun unlockWithSystem() = pinVault.unlockWithSystem()

        fun pinUnlocked() = pinVault.unlocked()

        fun setupPin(
            pin: String,
            recovery: Boolean,
        ) = pinVault.setup(pin, recovery)

        fun unlockPin(pin: String) = pinVault.unlock(pin)

        fun pinRecoveryEnabled() = pinVault.recoveryEnabled()

        fun recoverPin() = pinVault.recover()

        fun setEncryptHidden(
            enabled: Boolean,
            onResult: (Boolean) -> Unit,
        ) = viewModelScope.launch {
            val success =
                if (enabled) {
                    encryptedImageStore.encryptHiddenAlbums()
                } else {
                    encryptedImageStore.decryptHiddenAlbums()
                }
            if (success) {
                settingsStore.setEncryptHidden(enabled)
            } else {
                if (enabled) {
                    encryptedImageStore.decryptHiddenAlbums()
                } else {
                    encryptedImageStore.encryptHiddenAlbums()
                }
            }
            onResult(success)
        }

        fun setDebugDiagnostics(enabled: Boolean) =
            viewModelScope.launch {
                settingsStore.setDebugDiagnostics(enabled)
            }

        fun exportDiagnostics(
            uri: Uri,
            onResult: (Boolean) -> Unit,
        ) {
            viewModelScope.launch {
                val success =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { output ->
                                output.write(diagnostics.export().toByteArray())
                                true
                            } ?: false
                        }.getOrDefault(false)
                    }
                onResult(success)
            }
        }

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

        fun setLockDimDefault(enabled: Boolean) =
            viewModelScope.launch {
                settingsStore.setLockDimDefault(enabled)
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
                mutableMissingImages.value = imageRecovery.missingCount()
                onResult(success)
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
