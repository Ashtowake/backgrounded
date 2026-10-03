package dev.backgrounded.ui.album

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.backgrounded.R
import dev.backgrounded.data.importer.SafFolders
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.BackgroundPair
import dev.backgrounded.domain.model.WallpaperSurface
import dev.backgrounded.ui.components.BackgroundThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    onBack: () -> Unit,
    onEditPair: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel: AlbumViewModel = hiltViewModel()
    val album by viewModel.album.collectAsStateWithLifecycle()
    val pairs by viewModel.pairs.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var slotRequest by remember { mutableStateOf<SlotRequest?>(null) }

    val imagePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK_COUNT),
        ) { uris -> viewModel.addImages(uris) }

    val folderPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                viewModel.addLinkedImages(SafFolders.listImages(context, uri))
            }
        }

    val slotPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            val request = slotRequest
            slotRequest = null
            if (uri != null && request != null) {
                viewModel.setSlotImage(request.pairId, request.surface, uri)
            }
        }

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            context.getString(R.string.images_imported, current.added, current.skipped),
        )
        viewModel.clearMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(album?.name ?: stringResource(R.string.album)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.album_settings))
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_folder)) },
                            onClick = {
                                menuOpen = false
                                folderPicker.launch(null)
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_images))
            }
        },
    ) { padding ->
        if (pairs.isEmpty()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = stringResource(R.string.no_images))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pairs, key = { it.id }) { pair ->
                    PairCell(
                        pair = pair,
                        isCover = pair.id == album?.coverPairId,
                        onOpen = { onEditPair(pair.id) },
                        onApply = { viewModel.applyNow(pair.id) },
                        onSetCover = { viewModel.setCover(pair.id) },
                        onSetHomeImage = { slotRequest = SlotRequest(pair.id, WallpaperSurface.HOME) },
                        onSetLockImage = { slotRequest = SlotRequest(pair.id, WallpaperSurface.LOCK) },
                        onMoveUp = { viewModel.move(pair.id, -1) },
                        onMoveDown = { viewModel.move(pair.id, 1) },
                        onDelete = { viewModel.deletePair(pair.id) },
                    )
                }
            }
        }
    }

    val request = slotRequest
    if (request != null) {
        LaunchedEffect(request) {
            slotPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}

@Composable
private fun PairCell(
    pair: BackgroundPair,
    isCover: Boolean,
    onOpen: () -> Unit,
    onApply: () -> Unit,
    onSetCover: () -> Unit,
    onSetHomeImage: () -> Unit,
    onSetLockImage: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                SlotThumbnail(
                    background = pair.home,
                    surface = WallpaperSurface.HOME,
                    modifier = Modifier.weight(1f),
                )
                SlotThumbnail(
                    background = pair.lock,
                    surface = WallpaperSurface.LOCK,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pair.home.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                if (isCover) {
                    Text(
                        text = stringResource(R.string.cover_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.change_now)) },
                        onClick = {
                            menuOpen = false
                            onApply()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.set_home_image)) },
                        onClick = {
                            menuOpen = false
                            onSetHomeImage()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.set_lock_image)) },
                        onClick = {
                            menuOpen = false
                            onSetLockImage()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.set_cover)) },
                        onClick = {
                            menuOpen = false
                            onSetCover()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_up)) },
                        onClick = {
                            menuOpen = false
                            onMoveUp()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_down)) },
                        onClick = {
                            menuOpen = false
                            onMoveDown()
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
        }
    }
}

@Composable
private fun SlotThumbnail(
    background: Background,
    surface: WallpaperSurface,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.aspectRatio(CELL_ASPECT_RATIO)) {
        BackgroundThumbnail(
            background = background,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text =
                stringResource(
                    if (surface == WallpaperSurface.HOME) R.string.surface_home else R.string.surface_lock,
                ),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
        )
    }
}

private data class SlotRequest(
    val pairId: Long,
    val surface: WallpaperSurface,
)

private const val GRID_COLUMNS = 2
private const val MAX_PICK_COUNT = 50
private const val CELL_ASPECT_RATIO = 0.62f
