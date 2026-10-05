package dev.backgrounded.ui.albums

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.backgrounded.R
import dev.backgrounded.core.security.DeviceCredentialGate
import dev.backgrounded.core.security.SystemAuthentication
import dev.backgrounded.core.wallpaper.LiveWallpaperController
import dev.backgrounded.domain.model.Album
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.ui.components.BackgroundThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    onOpenAlbum: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel: AlbumsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val albumPreviews by viewModel.albumPreviews.collectAsStateWithLifecycle()
    val operationError by viewModel.operationError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var pendingHide by remember { mutableStateOf<Long?>(null) }
    var pendingMove by remember { mutableStateOf<Long?>(null) }
    var pendingSourceAlbum by remember { mutableStateOf<Long?>(null) }
    var sourceMoveExisting by remember { mutableStateOf(false) }

    var pendingAction by remember { mutableStateOf<CredentialAction?>(null) }
    var pinAction by remember { mutableStateOf<CredentialAction?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var pinConfirmation by remember { mutableStateOf("") }
    var pinRecovery by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf(false) }

    val executeCredentialAction: (CredentialAction) -> Unit = { action ->
        when (action) {
            CredentialAction.Reveal -> viewModel.revealHidden()
            is CredentialAction.Hide ->
                viewModel.setHidden(action.albumId, true, action.sourceFolder, action.useFullAccess)
            is CredentialAction.Move ->
                viewModel.moveSources(action.albumId, action.sourceFolder, action.useFullAccess)
            is CredentialAction.NextAlbum -> viewModel.advanceAlbumAuthorized(action.trigger)
            is CredentialAction.SelectAlbum -> viewModel.setActive(action.albumId, authenticated = true)
        }
    }

    val credentialLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                if (pinAction != null && viewModel.pinRecoveryEnabled()) {
                    if (viewModel.recoverPin()) pinAction?.let(executeCredentialAction)
                    pinAction = null
                } else if (pinAction != null && pinRecovery && !viewModel.pinConfigured()) {
                    if (viewModel.setupPin(pinInput, true)) pinAction?.let(executeCredentialAction)
                    pinAction = null
                    pinInput = ""
                    pinConfirmation = ""
                } else {
                    pendingAction?.let(executeCredentialAction)
                }
            }
            pendingAction = null
        }

    val runWithCredential: (CredentialAction) -> Unit = { action ->
        val canUseSystem = viewModel.systemConfigured() || !viewModel.pinConfigured()
        if (canUseSystem && SystemAuthentication.available(context) && viewModel.prepareSystemKey()) {
            SystemAuthentication.authenticate(context, context.getString(R.string.hidden_albums)) { authenticated ->
                val vaultReady =
                    if (!authenticated) {
                        false
                    } else if (viewModel.systemConfigured() || !viewModel.pinConfigured()) {
                        viewModel.unlockWithSystem()
                    } else {
                        true
                    }
                if (authenticated && vaultReady) {
                    executeCredentialAction(action)
                } else if (authenticated) {
                    pinAction = action
                    pinError = false
                }
            }
        } else {
            pinAction = action
            pinError = false
        }
    }

    val sourceFolderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val id = pendingSourceAlbum
            if (uri != null && id != null) {
                if (sourceMoveExisting) {
                    runWithCredential(CredentialAction.Move(id, uri))
                } else {
                    runWithCredential(CredentialAction.Hide(id, uri))
                }
            }
            pendingSourceAlbum = null
            sourceMoveExisting = false
        }

    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { viewModel.concealHidden() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        modifier =
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { runWithCredential(CredentialAction.Reveal) },
                            ),
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_album))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LiveWallpaperCard(
                    onApply = {
                        context.startActivity(LiveWallpaperController.applyIntent(context))
                    },
                )
            }
            item {
                ActiveAlbumCard(
                    album =
                        state.visibleAlbums.firstOrNull { it.id == state.activeAlbumId }
                            ?: state.hiddenAlbums.firstOrNull { it.id == state.activeAlbumId && state.hiddenRevealed },
                    concealed = state.hiddenAlbums.any { it.id == state.activeAlbumId } && !state.hiddenRevealed,
                    paused = state.paused,
                    onPrevious = viewModel::previous,
                    onNext = viewModel::next,
                    onNextAlbum = {
                        viewModel.advanceAlbum {
                            runWithCredential(CredentialAction.NextAlbum(Trigger.MANUAL))
                        }
                    },
                    onTogglePause = viewModel::togglePause,
                )
            }
            if (state.visibleAlbums.isEmpty() && state.hiddenAlbums.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_albums),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(state.visibleAlbums, key = { it.id }) { album ->
                AlbumRow(
                    album = album,
                    previews = albumPreviews[album.id].orEmpty(),
                    active = album.id == state.activeAlbumId,
                    onOpen = { onOpenAlbum(album.id) },
                    onSetActive = { viewModel.setActive(album.id) },
                    onHide = { pendingHide = album.id },
                )
            }
            if (state.hiddenRevealed) {
                items(state.hiddenAlbums, key = { it.id }) { album ->
                    AlbumRow(
                        album = album,
                        previews = albumPreviews[album.id].orEmpty(),
                        active = album.id == state.activeAlbumId,
                        onOpen = { onOpenAlbum(album.id) },
                        onSetActive = {
                            if (state.authenticateHiddenSwitch) {
                                runWithCredential(CredentialAction.SelectAlbum(album.id))
                            } else {
                                viewModel.setActive(album.id)
                            }
                        },
                        onHide = { viewModel.setHidden(album.id, false) },
                        onMoveSources = if (state.hideSourcesSystemwide) ({ pendingMove = album.id }) else null,
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.new_album)) },
            text = {
                OutlinedTextField(
                    value = newAlbumName,
                    onValueChange = { newAlbumName = it },
                    label = { Text(stringResource(R.string.album_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createAlbum(newAlbumName)
                        newAlbumName = ""
                        showCreateDialog = false
                    },
                ) {
                    Text(stringResource(R.string.create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (pendingHide != null) {
        AlertDialog(
            onDismissRequest = { pendingHide = null },
            title = { Text(stringResource(R.string.hide)) },
            text = {
                Text(
                    "To show hidden albums, long-press Backgrounded on this screen and authenticate." +
                        if (state.hideSourcesSystemwide) {
                            " Choose a source folder or use all-files access. Only exact local matches " +
                                "will be moved; unmatched photos keep this album visible. " +
                                "Other apps or cloud services may retain copies."
                        } else {
                            ""
                        },
                )
            },
            confirmButton = {
                Row {
                    if (state.hideSourcesSystemwide && Environment.isExternalStorageManager()) {
                        TextButton(onClick = {
                            val id = pendingHide
                            pendingHide = null
                            if (id != null) runWithCredential(CredentialAction.Hide(id, null, true))
                        }) { Text("Use full access") }
                    }
                    TextButton(onClick = {
                        val id = pendingHide
                        pendingHide = null
                        if (id != null) {
                            if (state.hideSourcesSystemwide) {
                                pendingSourceAlbum = id
                                sourceMoveExisting = false
                                sourceFolderPicker.launch(null)
                            } else {
                                runWithCredential(CredentialAction.Hide(id, null))
                            }
                        }
                    }) { Text(if (state.hideSourcesSystemwide) "Choose folder" else stringResource(R.string.hide)) }
                }
            },
            dismissButton = { TextButton(onClick = { pendingHide = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (pendingMove != null) {
        AlertDialog(
            onDismissRequest = { pendingMove = null },
            title = { Text("Move original photos") },
            text = {
                Text(
                    "Choose the source folder or use all-files access. Exact local name and content matches " +
                        "will be verified before removal. Other apps or cloud services may retain copies.",
                )
            },
            confirmButton = {
                Row {
                    if (Environment.isExternalStorageManager()) {
                        TextButton(onClick = {
                            val id = pendingMove
                            pendingMove = null
                            if (id != null) runWithCredential(CredentialAction.Move(id, null, true))
                        }) { Text("Use full access") }
                    }
                    TextButton(onClick = {
                        pendingSourceAlbum = pendingMove
                        sourceMoveExisting = true
                        pendingMove = null
                        sourceFolderPicker.launch(null)
                    }) { Text("Choose folder") }
                }
            },
            dismissButton = { TextButton(onClick = { pendingMove = null }) { Text("Cancel") } },
        )
    }
    if (operationError != null) {
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("File operation failed") },
            text = { Text(operationError.orEmpty()) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("OK") } },
        )
    }
    if (pinAction != null) {
        val configured = viewModel.pinConfigured()
        AlertDialog(
            onDismissRequest = {
                pinAction = null
                pinInput = ""
                pinConfirmation = ""
            },
            title = { Text(if (configured) "Enter hidden-album PIN" else "Set hidden-album PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            pinInput = it.filter(Char::isDigit).take(32)
                            pinError = false
                        },
                        label = { Text("PIN (at least 6 digits)") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions =
                            androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                            ),
                    )
                    if (!configured) {
                        OutlinedTextField(
                            value = pinConfirmation,
                            onValueChange = { pinConfirmation = it.filter(Char::isDigit).take(32) },
                            label = { Text("Confirm PIN") },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            keyboardOptions =
                                androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                                ),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = pinRecovery, onCheckedChange = { pinRecovery = it })
                            Text("Allow device-credential recovery")
                        }
                    }
                    if (pinError) Text("PIN incorrect or temporarily locked", color = MaterialTheme.colorScheme.error)
                    if (configured && viewModel.pinRecoveryEnabled()) {
                        TextButton(onClick = {
                            DeviceCredentialGate.confirmIntent(
                                context,
                                "Recover hidden albums",
                            )?.let(credentialLauncher::launch)
                        }) { Text("Use device credential") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val action = pinAction ?: return@TextButton
                    if (configured) {
                        if (viewModel.unlockPin(pinInput)) {
                            executeCredentialAction(action)
                            pinAction = null
                            pinInput = ""
                        } else {
                            pinError = true
                        }
                    } else if (dev.backgrounded.core.security.PinVault.validPin(pinInput) &&
                        pinInput == pinConfirmation
                    ) {
                        if (pinRecovery) {
                            val intent = DeviceCredentialGate.confirmIntent(context, "Enable PIN recovery")
                            if (intent != null) credentialLauncher.launch(intent) else pinError = true
                        } else if (viewModel.setupPin(pinInput, false)) {
                            executeCredentialAction(action)
                            pinAction = null
                            pinInput = ""
                            pinConfirmation = ""
                        } else {
                            pinError = true
                        }
                    } else {
                        pinError = true
                    }
                }) { Text(if (configured) "Unlock" else "Set PIN") }
            },
            dismissButton = { TextButton(onClick = { pinAction = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LiveWallpaperCard(onApply: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.live_wallpaper), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onApply) {
                Text(stringResource(R.string.set_live_wallpaper))
            }
            Text(
                text = stringResource(R.string.picker_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActiveAlbumCard(
    album: Album?,
    concealed: Boolean,
    paused: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onNextAlbum: () -> Unit,
    onTogglePause: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text =
                    stringResource(R.string.active_album) + ": " +
                        if (concealed) stringResource(R.string.hidden_albums) else (album?.name ?: "—"),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onPrevious) { Text(stringResource(R.string.previous)) }
                TextButton(onClick = onNext) { Text(stringResource(R.string.next)) }
                TextButton(onClick = onNextAlbum) { Text(stringResource(R.string.action_next_album)) }
                TextButton(onClick = onTogglePause) {
                    Text(stringResource(if (paused) R.string.resume else R.string.pause))
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(
    album: Album,
    previews: List<Background>,
    active: Boolean,
    onOpen: () -> Unit,
    onSetActive: () -> Unit,
    onHide: () -> Unit,
    onMoveSources: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = album.name, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    previews.forEach { image ->
                        BackgroundThumbnail(image, modifier = Modifier.width(36.dp).height(48.dp))
                    }
                }
                if (active) {
                    Text(
                        text = stringResource(R.string.active_album),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.active_album)) },
                    onClick = {
                        menuOpen = false
                        onSetActive()
                    },
                )
                if (onMoveSources != null) {
                    DropdownMenuItem(
                        text = { Text("Move original photos") },
                        onClick = {
                            menuOpen = false
                            onMoveSources()
                        },
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(stringResource(if (album.isHidden) R.string.unhide else R.string.hide))
                    },
                    onClick = {
                        menuOpen = false
                        onHide()
                    },
                )
            }
        }
    }
}

private sealed interface CredentialAction {
    data object Reveal : CredentialAction

    data class Hide(val albumId: Long, val sourceFolder: Uri?, val useFullAccess: Boolean = false) : CredentialAction

    data class Move(val albumId: Long, val sourceFolder: Uri?, val useFullAccess: Boolean = false) : CredentialAction

    data class NextAlbum(val trigger: Trigger) : CredentialAction

    data class SelectAlbum(val albumId: Long) : CredentialAction
}
