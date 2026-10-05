package dev.backgrounded.ui.albums

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.backgrounded.R
import dev.backgrounded.core.security.DeviceCredentialGate
import dev.backgrounded.core.security.SystemAuthentication
import dev.backgrounded.domain.model.Album
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.rotation.AlbumSwitchMode
import dev.backgrounded.ui.components.BackgroundThumbnail
import dev.backgrounded.widget.PlaybackAction

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
    val cardHeight = 180.dp
    var selectionDismissed by remember(state.activeAlbumId) { mutableStateOf(false) }
    var pendingRename by remember { mutableStateOf<Album?>(null) }
    var renameText by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Album?>(null) }
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
            is CredentialAction.SwitchAlbum -> viewModel.advanceAlbumAuthorized(action.trigger, action.mode)
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(320.dp),
                contentPadding = PaddingValues(bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ActiveAlbumCard(
                        album =
                            state.visibleAlbums.firstOrNull { it.id == state.activeAlbumId }
                                ?: state.hiddenAlbums.firstOrNull { it.id == state.activeAlbumId },
                        paused = state.paused,
                        onPrevious = viewModel::previous,
                        onNext = viewModel::next,
                        onAlbumSwitch = { mode ->
                            viewModel.advanceAlbum(mode) {
                                runWithCredential(CredentialAction.SwitchAlbum(mode))
                            }
                        },
                        onRandomImage = viewModel::randomImage,
                        onTogglePause = viewModel::togglePause,
                        modifier = Modifier.height(cardHeight),
                    )
                }
                if (state.visibleAlbums.isEmpty() && state.hiddenAlbums.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
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
                        modifier = Modifier.height(cardHeight),
                        active = album.id == state.activeAlbumId,
                        onOpen = { onOpenAlbum(album.id) },
                        onSetActive = { viewModel.setActive(album.id) },
                        onHide = { pendingHide = album.id },
                        onRename = {
                            pendingRename = album
                            renameText = album.name
                        },
                        onDelete = { pendingDelete = album },
                    )
                }
                if (state.hiddenRevealed) {
                    items(state.hiddenAlbums, key = { it.id }) { album ->
                        AlbumRow(
                            album = album,
                            previews = albumPreviews[album.id].orEmpty(),
                            modifier = Modifier.height(cardHeight),
                            active = album.id == state.activeAlbumId,
                            onOpen = { onOpenAlbum(album.id) },
                            onSetActive = {
                                if (state.authenticateHiddenSwitch && !state.hiddenRevealed) {
                                    runWithCredential(CredentialAction.SelectAlbum(album.id))
                                } else {
                                    viewModel.setActive(album.id)
                                }
                            },
                            onHide = { viewModel.setHidden(album.id, false) },
                            onRename = {
                                pendingRename = album
                                renameText = album.name
                            },
                            onDelete = { pendingDelete = album },
                        )
                    }
                }
            }
        }
    }

    if (state.needsActiveSelection && !selectionDismissed) {
        AlertDialog(
            onDismissRequest = { selectionDismissed = true },
            title = { Text("Select new active album") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    val available = state.visibleAlbums + if (state.hiddenRevealed) state.hiddenAlbums else emptyList()
                    if (available.isEmpty()) Text("No available albums")
                    available.forEach { album ->
                        TextButton(onClick = {
                            if (album.isHidden && state.authenticateHiddenSwitch && !state.hiddenRevealed) {
                                runWithCredential(CredentialAction.SelectAlbum(album.id))
                            } else {
                                viewModel.setActive(album.id)
                            }
                        }) { Text(album.name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCreateDialog = true }) { Text(stringResource(R.string.new_album)) }
            },
            dismissButton = {
                Row {
                    if (state.hiddenAlbums.isNotEmpty() && !state.hiddenRevealed) {
                        TextButton(onClick = { runWithCredential(CredentialAction.Reveal) }) { Text("Authenticate") }
                    }
                    TextButton(onClick = { selectionDismissed = true }) { Text("Later") }
                }
            },
        )
    }
    pendingRename?.let { album ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("Rename album") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(album.id, renameText)
                    pendingRename = null
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    pendingDelete?.let { album ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_album)) },
            text = { Text(stringResource(R.string.delete_album_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(album.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
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
private fun ActiveAlbumCard(
    album: Album?,
    paused: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAlbumSwitch: (AlbumSwitchMode) -> Unit,
    onRandomImage: () -> Unit,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = album?.name ?: "No active album",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Row(
                modifier = Modifier.widthIn(max = 448.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PlaybackAction.entries.forEach { action ->
                    FilledTonalIconButton(
                        onClick = {
                            when (action) {
                                PlaybackAction.PREVIOUS_IMAGE -> onPrevious()
                                PlaybackAction.NEXT_IMAGE -> onNext()
                                PlaybackAction.RANDOM_IMAGE -> onRandomImage()
                                PlaybackAction.TOGGLE_PAUSE -> onTogglePause()
                                PlaybackAction.PREVIOUS_ALBUM -> onAlbumSwitch(AlbumSwitchMode.PREVIOUS)
                                PlaybackAction.NEXT_ALBUM -> onAlbumSwitch(AlbumSwitchMode.NEXT)
                                PlaybackAction.RANDOM_ALBUM -> onAlbumSwitch(AlbumSwitchMode.RANDOM)
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            painter = painterResource(action.icon(paused)),
                            contentDescription = action.label(paused),
                            modifier = Modifier.size(28.dp),
                        )
                    }
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
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        border =
            BorderStroke(
                1.dp,
                if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                },
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (active) "Active" else "",
                    modifier = Modifier.width(48.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.width(48.dp).height(56.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Switch(
                        checked = active,
                        onCheckedChange = { if (!active) onSetActive() },
                        modifier =
                            Modifier.graphicsLayer { rotationZ = -90f }
                                .semantics { contentDescription = "Activate ${album.name}" },
                    )
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                        menuOpen = false
                        onRename()
                    })
                    DropdownMenuItem(
                        text = { Text(stringResource(if (album.isHidden) R.string.unhide else R.string.hide)) },
                        onClick = {
                            menuOpen = false
                            onHide()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
            AlbumThumbnailStrip(previews, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun AlbumThumbnailStrip(
    previews: List<Background>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val thumbnailHeight = maxHeight
        val preferredWidth = thumbnailHeight * 0.75f
        val capacity = ((maxWidth + 4.dp) / (preferredWidth + 4.dp)).toInt().coerceAtLeast(1)
        val thumbnailWidth =
            if (previews.size >= capacity) {
                (maxWidth - 4.dp * (capacity - 1)) / capacity
            } else {
                minOf(preferredWidth, maxWidth)
            }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            previews.take(capacity).forEach { image ->
                BackgroundThumbnail(image, modifier = Modifier.width(thumbnailWidth).height(thumbnailHeight))
            }
        }
    }
}

private sealed interface CredentialAction {
    data object Reveal : CredentialAction

    data class Hide(val albumId: Long, val sourceFolder: Uri?, val useFullAccess: Boolean = false) : CredentialAction

    data class Move(val albumId: Long, val sourceFolder: Uri?, val useFullAccess: Boolean = false) : CredentialAction

    data class SwitchAlbum(val mode: AlbumSwitchMode, val trigger: Trigger = Trigger.MANUAL) : CredentialAction

    data class SelectAlbum(val albumId: Long) : CredentialAction
}
