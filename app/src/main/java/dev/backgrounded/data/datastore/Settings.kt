package dev.backgrounded.data.datastore

import dev.backgrounded.domain.model.DoubleTapMode
import dev.backgrounded.domain.model.GestureAction
import dev.backgrounded.domain.model.UnlockState

data class Settings(
    val activeAlbumId: Long?,
    val rotationPaused: Boolean,
    val currentPairId: Long?,
    val currentChangedAt: Long,
    val unlockState: UnlockState,
    val doubleTapEnabled: Boolean,
    val doubleTapMode: DoubleTapMode,
    val doubleTapAction: GestureAction,
    val lockDimDefault: Boolean,
    val externalControlEnabled: Boolean,
    val widgetIconSource: String,
    val widgetIconAlpha: Int,
    val widgetBackgroundAlpha: Int,
    val widgetTapAction: GestureAction,
    val widgetDoubleTapAction: GestureAction,
    val widgetPinnedAlbumId: Long?,
    val liveWallpaperHome: Boolean,
    val liveWallpaperLock: Boolean,
    val nextTriggerAt: Long,
    val lastError: String?,
    val hideSourcesSystemwide: Boolean,
    val authenticateHiddenSwitch: Boolean,
    val encryptHidden: Boolean,
    val debugDiagnostics: Boolean,
) {
    companion object {
        val DEFAULTS =
            Settings(
                activeAlbumId = null,
                rotationPaused = false,
                currentPairId = null,
                currentChangedAt = 0L,
                unlockState =
                    UnlockState(
                        lastAppliedAt = 0L,
                        unlocksSinceApply = 0,
                        appliedToday = 0,
                        dayEpochDay = 0L,
                    ),
                doubleTapEnabled = true,
                doubleTapMode = DoubleTapMode.BACKGROUND,
                doubleTapAction = GestureAction.NEXT,
                lockDimDefault = false,
                externalControlEnabled = false,
                widgetIconSource = "builtin:next",
                widgetIconAlpha = 255,
                widgetBackgroundAlpha = 0,
                widgetTapAction = GestureAction.NEXT,
                widgetDoubleTapAction = GestureAction.NEXT_ALBUM,
                widgetPinnedAlbumId = null,
                liveWallpaperHome = false,
                liveWallpaperLock = false,
                nextTriggerAt = 0L,
                lastError = null,
                hideSourcesSystemwide = false,
                authenticateHiddenSwitch = true,
                encryptHidden = false,
                debugDiagnostics = false,
            )
    }
}
