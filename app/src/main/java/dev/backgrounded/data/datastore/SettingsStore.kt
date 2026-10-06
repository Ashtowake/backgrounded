package dev.backgrounded.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.domain.model.DoubleTapMode
import dev.backgrounded.domain.model.GestureAction
import dev.backgrounded.domain.model.UnlockState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        val settings: Flow<Settings> = context.settingsDataStore.data.map { prefs -> prefs.toSettings() }

        val wallpaperSetupCompleted: Flow<Boolean> =
            context.settingsDataStore.data.map { it[Keys.WALLPAPER_SETUP_COMPLETED] ?: false }

        suspend fun completeWallpaperSetup() = edit { it[Keys.WALLPAPER_SETUP_COMPLETED] = true }

        suspend fun setActiveAlbum(albumId: Long?) =
            edit { prefs ->
                prefs[Keys.ACTIVE_ALBUM] = albumId ?: Keys.UNSET
            }

        suspend fun setRotationPaused(paused: Boolean) =
            edit { prefs ->
                prefs[Keys.ROTATION_PAUSED] = paused
            }

        suspend fun setCurrent(
            backgroundId: Long?,
            changedAt: Long,
        ) = edit { prefs ->
            prefs[Keys.CURRENT_BACKGROUND] = backgroundId ?: Keys.UNSET
            prefs[Keys.CURRENT_CHANGED_AT] = changedAt
        }

        suspend fun setUnlockState(state: UnlockState) =
            edit { prefs ->
                prefs[Keys.UNLOCK_LAST_APPLIED_AT] = state.lastAppliedAt
                prefs[Keys.UNLOCK_SINCE_APPLY] = state.unlocksSinceApply
                prefs[Keys.UNLOCK_APPLIED_TODAY] = state.appliedToday
                prefs[Keys.UNLOCK_DAY] = state.dayEpochDay
            }

        suspend fun setDoubleTap(
            enabled: Boolean,
            mode: DoubleTapMode,
            action: GestureAction,
        ) = edit { prefs ->
            prefs[Keys.DOUBLE_TAP_ENABLED] = enabled
            prefs[Keys.DOUBLE_TAP_MODE] = mode.name
            prefs[Keys.DOUBLE_TAP_ACTION] = action.name
        }

        /** Read only when upgrading global crossfade preferences to album columns. */
        suspend fun legacyCrossfade(): Pair<Boolean, Int> {
            val prefs = context.settingsDataStore.data.first()
            return (prefs[Keys.CROSSFADE] ?: true) to (prefs[Keys.CROSSFADE_DURATION] ?: 800)
        }

        suspend fun setLockDimDefault(enabled: Boolean) =
            edit { prefs ->
                prefs[Keys.LOCK_DIM_DEFAULT] = enabled
            }

        suspend fun setExternalControl(enabled: Boolean) =
            edit { prefs ->
                prefs[Keys.EXTERNAL_CONTROL] = enabled
            }

        suspend fun setWidgetConfig(
            iconSource: String,
            iconAlpha: Int,
            backgroundAlpha: Int,
            tapAction: GestureAction,
            doubleTapAction: GestureAction,
            pinnedAlbumId: Long?,
        ) = edit { prefs ->
            prefs[Keys.WIDGET_ICON_SOURCE] = iconSource
            prefs[Keys.WIDGET_ICON_ALPHA] = iconAlpha
            prefs[Keys.WIDGET_BACKGROUND_ALPHA] = backgroundAlpha
            prefs[Keys.WIDGET_TAP_ACTION] = tapAction.name
            prefs[Keys.WIDGET_DOUBLE_TAP_ACTION] = doubleTapAction.name
            prefs[Keys.WIDGET_PINNED_ALBUM] = pinnedAlbumId ?: Keys.UNSET
        }

        suspend fun setLiveWallpaperStatus(
            home: Boolean,
            lock: Boolean,
        ) = edit { prefs ->
            prefs[Keys.LIVE_WALLPAPER_HOME] = home
            prefs[Keys.LIVE_WALLPAPER_LOCK] = lock
        }

        suspend fun setNextTriggerAt(at: Long) =
            edit { prefs ->
                prefs[Keys.NEXT_TRIGGER_AT] = at
            }

        suspend fun setLastError(message: String?) =
            edit { prefs ->
                prefs[Keys.LAST_ERROR] = message ?: ""
            }

        suspend fun setHideSourcesSystemwide(enabled: Boolean) = edit { it[Keys.HIDE_SOURCES] = enabled }

        suspend fun setAuthenticateHiddenSwitch(enabled: Boolean) =
            edit { it[Keys.AUTHENTICATE_HIDDEN_SWITCH] = enabled }

        suspend fun setEncryptHidden(enabled: Boolean) = edit { it[Keys.ENCRYPT_HIDDEN] = enabled }

        suspend fun setDebugDiagnostics(enabled: Boolean) = edit { it[Keys.DEBUG_DIAGNOSTICS] = enabled }

        suspend fun setPendingUnlock(pending: Boolean) = edit { it[Keys.PENDING_UNLOCK] = pending }

        suspend fun setAnimationFps(fps: Int) = edit { it[Keys.ANIMATION_FPS] = if (fps == 60) 60 else 30 }

        suspend fun setFolderScanSeconds(seconds: Int) =
            edit {
                require(seconds in listOf(0, 60, 300, 900))
                it[Keys.FOLDER_SCAN_SECONDS] = seconds
            }

        suspend fun commitPlayback(
            albumId: Long?,
            pairId: Long?,
            changedAt: Long,
            paused: Boolean,
            unlock: UnlockState? = null,
        ) = edit {
            val selectionChanged =
                it[Keys.CURRENT_CHANGED_AT] != changedAt ||
                    it[Keys.CURRENT_BACKGROUND] != (pairId ?: Keys.UNSET) ||
                    it[Keys.ACTIVE_ALBUM] != (albumId ?: Keys.UNSET)
            it[Keys.ACTIVE_ALBUM] = albumId ?: Keys.UNSET
            it[Keys.CURRENT_BACKGROUND] = pairId ?: Keys.UNSET
            it[Keys.CURRENT_CHANGED_AT] = changedAt
            it[Keys.ROTATION_PAUSED] = paused
            if (unlock != null) {
                it[Keys.UNLOCK_LAST_APPLIED_AT] = unlock.lastAppliedAt
                it[Keys.UNLOCK_SINCE_APPLY] = unlock.unlocksSinceApply
                it[Keys.UNLOCK_APPLIED_TODAY] = unlock.appliedToday
                it[Keys.UNLOCK_DAY] = unlock.dayEpochDay
            }
            if (selectionChanged) {
                it[Keys.NEXT_TRIGGER_AT] = 0L
                it[Keys.PENDING_UNLOCK] = false
            }
        }

        suspend fun restoreConfiguration(
            backup: dev.backgrounded.data.backup.SettingsBackup?,
            albumIds: List<Long>,
        ) = edit { prefs ->
            val saved = backup ?: dev.backgrounded.data.backup.SettingsBackup()
            prefs[Keys.ROTATION_PAUSED] = saved.rotationPaused
            prefs[Keys.ANIMATION_FPS] = saved.animationFps
            prefs[Keys.FOLDER_SCAN_SECONDS] = saved.folderScanSeconds
            prefs[Keys.AUTHENTICATE_HIDDEN_SWITCH] = saved.authenticateHiddenSwitch
            prefs[Keys.DOUBLE_TAP_ENABLED] = saved.doubleTapEnabled
            prefs[Keys.DOUBLE_TAP_MODE] = saved.doubleTapMode
            prefs[Keys.DOUBLE_TAP_ACTION] = saved.doubleTapAction
            prefs[Keys.EXTERNAL_CONTROL] = saved.externalControlEnabled
            prefs[Keys.WIDGET_ICON_SOURCE] = saved.widgetIconSource
            prefs[Keys.WIDGET_ICON_ALPHA] = saved.widgetIconAlpha
            prefs[Keys.WIDGET_BACKGROUND_ALPHA] = saved.widgetBackgroundAlpha
            prefs[Keys.WIDGET_TAP_ACTION] = saved.widgetTapAction
            prefs[Keys.WIDGET_DOUBLE_TAP_ACTION] = saved.widgetDoubleTapAction
            prefs[Keys.WIDGET_PINNED_ALBUM] = saved.widgetPinnedAlbumIndex?.let { albumIds.getOrNull(it) } ?: Keys.UNSET
            prefs[Keys.ACTIVE_ALBUM] = saved.activeAlbumIndex?.let { albumIds.getOrNull(it) } ?: Keys.UNSET
            prefs[Keys.CURRENT_BACKGROUND] = Keys.UNSET
            prefs[Keys.CURRENT_CHANGED_AT] = 0L
            prefs[Keys.NEXT_TRIGGER_AT] = 0L
            prefs[Keys.PENDING_UNLOCK] = false
        }

        private suspend fun edit(transform: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
            context.settingsDataStore.edit(transform)
        }

        @Suppress("CyclomaticComplexMethod")
        private fun Preferences.toSettings(): Settings =
            Settings(
                activeAlbumId = this[Keys.ACTIVE_ALBUM]?.takeIf { it != Keys.UNSET },
                rotationPaused = this[Keys.ROTATION_PAUSED] ?: false,
                currentPairId = this[Keys.CURRENT_BACKGROUND]?.takeIf { it != Keys.UNSET },
                currentChangedAt = this[Keys.CURRENT_CHANGED_AT] ?: 0L,
                unlockState =
                    UnlockState(
                        lastAppliedAt = this[Keys.UNLOCK_LAST_APPLIED_AT] ?: 0L,
                        unlocksSinceApply = this[Keys.UNLOCK_SINCE_APPLY] ?: 0,
                        appliedToday = this[Keys.UNLOCK_APPLIED_TODAY] ?: 0,
                        dayEpochDay = this[Keys.UNLOCK_DAY] ?: 0L,
                    ),
                doubleTapEnabled = this[Keys.DOUBLE_TAP_ENABLED] ?: true,
                doubleTapMode = DoubleTapMode.from(this[Keys.DOUBLE_TAP_MODE]),
                doubleTapAction = GestureAction.from(this[Keys.DOUBLE_TAP_ACTION]),
                lockDimDefault = this[Keys.LOCK_DIM_DEFAULT] ?: false,
                externalControlEnabled = this[Keys.EXTERNAL_CONTROL] ?: false,
                widgetIconSource = this[Keys.WIDGET_ICON_SOURCE] ?: "builtin:next",
                widgetIconAlpha = this[Keys.WIDGET_ICON_ALPHA] ?: 255,
                widgetBackgroundAlpha = this[Keys.WIDGET_BACKGROUND_ALPHA] ?: 0,
                widgetTapAction = GestureAction.from(this[Keys.WIDGET_TAP_ACTION]),
                widgetDoubleTapAction = GestureAction.from(this[Keys.WIDGET_DOUBLE_TAP_ACTION]),
                widgetPinnedAlbumId = this[Keys.WIDGET_PINNED_ALBUM]?.takeIf { it != Keys.UNSET },
                liveWallpaperHome = this[Keys.LIVE_WALLPAPER_HOME] ?: false,
                liveWallpaperLock = this[Keys.LIVE_WALLPAPER_LOCK] ?: false,
                nextTriggerAt = this[Keys.NEXT_TRIGGER_AT] ?: 0L,
                lastError = this[Keys.LAST_ERROR]?.takeIf { it.isNotEmpty() },
                hideSourcesSystemwide = this[Keys.HIDE_SOURCES] ?: false,
                authenticateHiddenSwitch = this[Keys.AUTHENTICATE_HIDDEN_SWITCH] ?: true,
                encryptHidden = this[Keys.ENCRYPT_HIDDEN] ?: false,
                debugDiagnostics = this[Keys.DEBUG_DIAGNOSTICS] ?: false,
                pendingUnlock = this[Keys.PENDING_UNLOCK] ?: false,
                animationFps = if (this[Keys.ANIMATION_FPS] == 60) 60 else 30,
                folderScanSeconds = this[Keys.FOLDER_SCAN_SECONDS]?.takeIf { it in listOf(0, 60, 300, 900) } ?: 60,
            )

        private object Keys {
            val ACTIVE_ALBUM = longPreferencesKey("active_album")
            val ROTATION_PAUSED = booleanPreferencesKey("rotation_paused")
            val CURRENT_BACKGROUND = longPreferencesKey("current_background")
            val CURRENT_CHANGED_AT = longPreferencesKey("current_changed_at")
            val UNLOCK_LAST_APPLIED_AT = longPreferencesKey("unlock_last_applied_at")
            val UNLOCK_SINCE_APPLY = intPreferencesKey("unlock_since_apply")
            val UNLOCK_APPLIED_TODAY = intPreferencesKey("unlock_applied_today")
            val UNLOCK_DAY = longPreferencesKey("unlock_day")
            val DOUBLE_TAP_ENABLED = booleanPreferencesKey("double_tap_enabled")
            val DOUBLE_TAP_MODE = stringPreferencesKey("double_tap_mode")
            val DOUBLE_TAP_ACTION = stringPreferencesKey("double_tap_action")
            val CROSSFADE = booleanPreferencesKey("crossfade")
            val CROSSFADE_DURATION = intPreferencesKey("crossfade_duration")
            val LOCK_DIM_DEFAULT = booleanPreferencesKey("lock_dim_default")
            val EXTERNAL_CONTROL = booleanPreferencesKey("external_control")
            val WIDGET_ICON_SOURCE = stringPreferencesKey("widget_icon_source")
            val WIDGET_ICON_ALPHA = intPreferencesKey("widget_icon_alpha")
            val WIDGET_BACKGROUND_ALPHA = intPreferencesKey("widget_background_alpha")
            val WIDGET_TAP_ACTION = stringPreferencesKey("widget_tap_action")
            val WIDGET_DOUBLE_TAP_ACTION = stringPreferencesKey("widget_double_tap_action")
            val WIDGET_PINNED_ALBUM = longPreferencesKey("widget_pinned_album")
            val WALLPAPER_SETUP_COMPLETED = booleanPreferencesKey("wallpaper_setup_completed")
            val LIVE_WALLPAPER_HOME = booleanPreferencesKey("live_wallpaper_home")
            val LIVE_WALLPAPER_LOCK = booleanPreferencesKey("live_wallpaper_lock")
            val NEXT_TRIGGER_AT = longPreferencesKey("next_trigger_at")
            val LAST_ERROR = stringPreferencesKey("last_error")
            val HIDE_SOURCES = booleanPreferencesKey("hide_sources_systemwide")
            val AUTHENTICATE_HIDDEN_SWITCH = booleanPreferencesKey("authenticate_hidden_switch")
            val ENCRYPT_HIDDEN = booleanPreferencesKey("encrypt_hidden")
            val PENDING_UNLOCK = booleanPreferencesKey("pending_unlock")
            val ANIMATION_FPS = intPreferencesKey("animation_fps")
            val FOLDER_SCAN_SECONDS = intPreferencesKey("folder_scan_seconds")
            val DEBUG_DIAGNOSTICS = booleanPreferencesKey("debug_diagnostics")

            const val UNSET = -1L
        }
    }
