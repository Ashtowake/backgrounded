package dev.backgrounded.ui.albums

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import dev.backgrounded.core.wallpaper.LiveWallpaperController
import dev.backgrounded.domain.model.Album

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    onOpenAlbum: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel: AlbumsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var appliedHome by remember { mutableStateOf(false) }
    var appliedLock by remember { mutableStateOf(false) }

    var pendingAction by remember { mutableStateOf<CredentialAction?>(null) }

    val executeCredentialAction: (CredentialAction) -> Unit = { action ->
        when (action) {
            CredentialAction.Reveal -> viewModel.revealHidden()
            is CredentialAction.Hide -> viewModel.setHidden(action.albumId, true)
        }
    }

    val credentialLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                pendingAction?.let(executeCredentialAction)
            }
            pendingAction = null
        }

    val runWithCredential: (CredentialAction) -> Unit = { action ->
        val intent =
            DeviceCredentialGate.confirmIntent(
                context,
                context.getString(R.string.hidden_albums),
            )
        if (intent != null) {
            pendingAction = action
            credentialLauncher.launch(intent)
        } else {
            executeCredentialAction(action)
        }
    }

    LifecycleResumeEffect(Unit) {
        appliedHome = LiveWallpaperController.isApplied(context, lockScreen = false)
        appliedLock = LiveWallpaperController.isApplied(context, lockScreen = true)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
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
                    appliedHome = appliedHome,
                    appliedLock = appliedLock,
                    onApply = {
                        context.startActivity(LiveWallpaperController.applyIntent(context))
                    },
                )
            }
            item {
                ActiveAlbumCard(
                    album =
                        state.visibleAlbums.firstOrNull { it.id == state.activeAlbumId }
                            ?: state.visibleAlbums.firstOrNull(),
                    paused = state.paused,
                    onPrevious = viewModel::previous,
                    onNext = viewModel::next,
                    onNextAlbum = viewModel::advanceAlbum,
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
                    active = album.id == state.activeAlbumId,
                    onOpen = { onOpenAlbum(album.id) },
                    onSetActive = { viewModel.setActive(album.id) },
                    onHide = { runWithCredential(CredentialAction.Hide(album.id)) },
                )
            }
            if (state.hiddenAlbums.isNotEmpty()) {
                item {
                    SectionHeaderWithAction(
                        title = stringResource(R.string.hidden_albums),
                        actionLabel =
                            if (state.hiddenRevealed) {
                                stringResource(R.string.hide)
                            } else {
                                stringResource(R.string.show_hidden)
                            },
                        onAction = {
                            if (state.hiddenRevealed) {
                                viewModel.concealHidden()
                            } else {
                                runWithCredential(CredentialAction.Reveal)
                            }
                        },
                    )
                }
                if (state.hiddenRevealed) {
                    items(state.hiddenAlbums, key = { it.id }) { album ->
                        AlbumRow(
                            album = album,
                            active = album.id == state.activeAlbumId,
                            onOpen = { onOpenAlbum(album.id) },
                            onSetActive = { viewModel.setActive(album.id) },
                            onHide = { viewModel.setHidden(album.id, false) },
                        )
                    }
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
}

@Composable
private fun LiveWallpaperCard(
    appliedHome: Boolean,
    appliedLock: Boolean,
    onApply: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.live_wallpaper), style = MaterialTheme.typography.titleMedium)
            Text(
                text =
                    stringResource(R.string.home_screen) + ": " +
                        stringResource(if (appliedHome) R.string.applied else R.string.not_applied),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text =
                    stringResource(R.string.lock_screen) + ": " +
                        stringResource(if (appliedLock) R.string.applied else R.string.not_applied),
                style = MaterialTheme.typography.bodyMedium,
            )
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
    paused: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onNextAlbum: () -> Unit,
    onTogglePause: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.active_album) + ": " + (album?.name ?: "—"),
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
private fun SectionHeaderWithAction(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        Surface(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surface) {}
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun AlbumRow(
    album: Album,
    active: Boolean,
    onOpen: () -> Unit,
    onSetActive: () -> Unit,
    onHide: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = album.name, style = MaterialTheme.typography.titleMedium)
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

    data class Hide(val albumId: Long) : CredentialAction
}
