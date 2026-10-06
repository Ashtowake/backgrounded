package dev.backgrounded.shortcuts

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.backgrounded.MainActivity
import dev.backgrounded.core.security.HiddenSwitchAuthActivity
import dev.backgrounded.core.security.HiddenSwitchOperation
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.rotation.RotationResult
import dev.backgrounded.domain.usecase.ApplyNextBackground
import dev.backgrounded.domain.usecase.ApplyPreviousBackground
import dev.backgrounded.domain.usecase.HiddenAlbumSwitchPolicy
import dev.backgrounded.domain.usecase.NextAlbum
import dev.backgrounded.domain.usecase.TogglePause
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ActionTrampolineActivity : ComponentActivity() {
    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var applyNextBackground: ApplyNextBackground

    @Inject
    lateinit var applyPreviousBackground: ApplyPreviousBackground

    @Inject
    lateinit var nextAlbum: NextAlbum

    @Inject
    lateinit var hiddenSwitchPolicy: HiddenAlbumSwitchPolicy

    @Inject
    lateinit var togglePause: TogglePause

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            dispatch(intent)
            finish()
        }
    }

    private suspend fun dispatch(intent: Intent?) {
        when (intent?.action) {
            ACTION_NEXT -> applyNextBackground(Trigger.EXTERNAL)
            ACTION_PREVIOUS -> applyPreviousBackground(Trigger.EXTERNAL)
            ACTION_NEXT_ALBUM -> {
                val selection = nextAlbum(Trigger.EXTERNAL)
                if (selection is RotationResult.AuthenticationRequired) {
                    startActivity(HiddenSwitchAuthActivity.intent(this, Trigger.EXTERNAL, albumId = selection.albumId))
                }
            }
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_OPEN_APP ->
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            ACTION_SET_ALBUM -> {
                val albumId = intent.getLongExtra(EXTRA_ALBUM_ID, -1L)
                if (albumId > 0) {
                    val activeId = settingsStore.settings.first().activeAlbumId
                    if (activeId != albumId && hiddenSwitchPolicy.requiresAuthentication(albumId)) {
                        startActivity(
                            HiddenSwitchAuthActivity.intent(
                                this,
                                Trigger.EXTERNAL,
                                albumId,
                                HiddenSwitchOperation.APPLY_NEXT,
                            ),
                        )
                    } else {
                        applyNextBackground(Trigger.EXTERNAL, albumIdOverride = albumId)
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_NEXT = "dev.backgrounded.action.NEXT"
        const val ACTION_PREVIOUS = "dev.backgrounded.action.PREVIOUS"
        const val ACTION_NEXT_ALBUM = "dev.backgrounded.action.NEXT_ALBUM"
        const val ACTION_TOGGLE_PAUSE = "dev.backgrounded.action.TOGGLE_PAUSE"
        const val ACTION_OPEN_APP = "dev.backgrounded.action.OPEN_APP"
        const val ACTION_SET_ALBUM = "dev.backgrounded.action.SET_ALBUM"
        const val EXTRA_ALBUM_ID = "dev.backgrounded.extra.ALBUM_ID"
    }
}
