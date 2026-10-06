package dev.backgrounded.core.security

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.rotation.AlbumSwitchMode
import dev.backgrounded.domain.usecase.ApplyNextBackground
import dev.backgrounded.domain.usecase.ApplyPreviousBackground
import dev.backgrounded.domain.usecase.NextAlbum
import dev.backgrounded.ui.theme.BackgroundedTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HiddenSwitchOperation {
    NEXT_ALBUM,
    PREVIOUS_ALBUM,
    RANDOM_ALBUM,
    APPLY_NEXT,
    APPLY_PREVIOUS,
    SELECT,
    REVEAL,
}

/** Authenticates a widget, shortcut, or wallpaper gesture without opening the main app. */
@AndroidEntryPoint
class HiddenSwitchAuthActivity : ComponentActivity() {
    @Inject lateinit var pinVault: PinVault

    @Inject lateinit var nextAlbum: NextAlbum

    @Inject lateinit var applyNextBackground: ApplyNextBackground

    @Inject lateinit var applyPreviousBackground: ApplyPreviousBackground

    @Inject lateinit var settingsStore: SettingsStore

    private var showPin by mutableStateOf(false)
    private var pin by mutableStateOf("")
    private var confirmation by mutableStateOf("")
    private var allowRecovery by mutableStateOf(false)
    private var error by mutableStateOf(false)
    private var started = false
    private var recoverySetup = false

    private val credentialLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            lifecycleScope.launch {
                if (result.resultCode == RESULT_OK) {
                    val ready =
                        if (recoverySetup) pinVault.setupAsync(pin, true) else pinVault.recover()
                    if (ready) execute() else error = true
                }
                recoverySetup = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BackgroundedTheme {
                if (showPin) PinDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (started) return
        started = true
        if ((pinVault.biometricConfigured() || !pinVault.configured()) &&
            SystemAuthentication.available(this) && pinVault.prepareSystemKey()
        ) {
            SystemAuthentication.authenticate(this, "Hidden album") { authenticated ->
                if (authenticated && pinVault.unlockWithSystem()) {
                    execute()
                } else if (authenticated && pinVault.configured()) {
                    showPin = true
                } else {
                    finish()
                }
            }
        } else {
            showPin = true
        }
    }

    private fun execute() {
        val trigger =
            runCatching { Trigger.valueOf(intent.getStringExtra(EXTRA_TRIGGER).orEmpty()) }
                .getOrDefault(Trigger.EXTERNAL)
        val operation =
            runCatching { HiddenSwitchOperation.valueOf(intent.getStringExtra(EXTRA_OPERATION).orEmpty()) }
                .getOrDefault(HiddenSwitchOperation.NEXT_ALBUM)
        val albumId = intent.getLongExtra(EXTRA_ALBUM_ID, -1L).takeIf { it > 0L }
        lifecycleScope.launch {
            try {
                when (operation) {
                    HiddenSwitchOperation.REVEAL -> setResult(RESULT_OK)
                    HiddenSwitchOperation.NEXT_ALBUM ->
                        nextAlbum(
                            trigger,
                            authenticated = true,
                            targetAlbumId = albumId,
                        )
                    HiddenSwitchOperation.PREVIOUS_ALBUM ->
                        nextAlbum(
                            trigger,
                            authenticated = true,
                            mode = AlbumSwitchMode.PREVIOUS,
                            targetAlbumId = albumId,
                        )
                    HiddenSwitchOperation.RANDOM_ALBUM ->
                        nextAlbum(trigger, authenticated = true, mode = AlbumSwitchMode.RANDOM, targetAlbumId = albumId)
                    HiddenSwitchOperation.APPLY_NEXT -> {
                        if (albumId != null) applyNextBackground(trigger, albumIdOverride = albumId)
                    }
                    HiddenSwitchOperation.APPLY_PREVIOUS -> {
                        if (albumId != null) {
                            applyPreviousBackground(trigger, albumIdOverride = albumId)
                        }
                    }
                    HiddenSwitchOperation.SELECT -> {
                        if (albumId != null) applyNextBackground(trigger, albumIdOverride = albumId)
                    }
                }
            } finally {
                finish()
            }
        }
    }

    @Composable
    private fun PinDialog() {
        val configured = pinVault.configured()
        AlertDialog(
            onDismissRequest = ::finish,
            title = { Text(if (configured) "Enter hidden-album PIN" else "Set hidden-album PIN") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            pin = it.filter(Char::isDigit).take(32)
                            error = false
                        },
                        label = { Text("PIN (at least 6 digits)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions =
                            androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                            ),
                    )
                    if (!configured) {
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = { confirmation = it.filter(Char::isDigit).take(32) },
                            label = { Text("Confirm PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions =
                                androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = KeyboardType.NumberPassword,
                                ),
                        )
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            Checkbox(checked = allowRecovery, onCheckedChange = { allowRecovery = it })
                            Text("Allow device-credential recovery")
                        }
                    }
                    if (error) Text("PIN incorrect or temporarily locked")
                    if (configured && pinVault.recoveryEnabled()) {
                        TextButton(onClick = {
                            DeviceCredentialGate.confirmIntent(this@HiddenSwitchAuthActivity, "Recover hidden albums")
                                ?.let(credentialLauncher::launch)
                        }) { Text("Use device credential") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    lifecycleScope.launch {
                        if (configured) {
                            if (pinVault.unlockAsync(pin)) execute() else error = true
                        } else if (PinVault.validPin(pin) && pin == confirmation) {
                            if (allowRecovery) {
                                val intent =
                                    DeviceCredentialGate.confirmIntent(
                                        this@HiddenSwitchAuthActivity,
                                        "Enable PIN recovery",
                                    )
                                if (intent != null) {
                                    recoverySetup = true
                                    credentialLauncher.launch(intent)
                                } else {
                                    error = true
                                }
                            } else if (pinVault.setupAsync(pin, false)) {
                                execute()
                            } else {
                                error = true
                            }
                        } else {
                            error = true
                        }
                    }
                }) { Text(if (configured) "Unlock" else "Set PIN") }
            },
            dismissButton = { TextButton(onClick = ::finish) { Text("Cancel") } },
        )
    }

    companion object {
        private const val EXTRA_ALBUM_ID = "dev.backgrounded.extra.HIDDEN_SWITCH_ALBUM_ID"
        private const val EXTRA_TRIGGER = "dev.backgrounded.extra.HIDDEN_SWITCH_TRIGGER"
        private const val EXTRA_OPERATION = "dev.backgrounded.extra.HIDDEN_SWITCH_OPERATION"

        fun intent(
            context: Context,
            trigger: Trigger,
            albumId: Long? = null,
            operation: HiddenSwitchOperation = HiddenSwitchOperation.NEXT_ALBUM,
        ): Intent =
            Intent(context, HiddenSwitchAuthActivity::class.java)
                .putExtra(EXTRA_ALBUM_ID, albumId ?: -1L)
                .putExtra(EXTRA_TRIGGER, trigger.name)
                .putExtra(EXTRA_OPERATION, operation.name)
                .addFlags(if (operation == HiddenSwitchOperation.REVEAL) 0 else Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
