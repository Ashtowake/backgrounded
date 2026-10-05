package dev.backgrounded.ui.album

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.backgrounded.R
import dev.backgrounded.data.importer.SafFolders
import dev.backgrounded.domain.model.BackgroundPair
import dev.backgrounded.domain.model.DisplayTarget
import dev.backgrounded.domain.model.WallpaperSurface
import kotlinx.coroutines.launch

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
    val hideSourcesEnabled by viewModel.hideSourcesEnabled.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val fileError by viewModel.fileError.collectAsStateWithLifecycle()
    val pairSources by viewModel.pairSources.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val configuration = LocalConfiguration.current
    val stackPreviews = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val displayTarget = viewModel.currentDisplay()
    val displayAspect = viewModel.displayAspect(displayTarget)
    val thumbnailVersion by viewModel.thumbnailVersion.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var slotRequest by remember { mutableStateOf<SlotRequest?>(null) }
    val gridState = rememberLazyGridState()
    val dragScope = rememberCoroutineScope()
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var orderedIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    LaunchedEffect(pairs) {
        if (draggingId == null) orderedIds = pairs.map { it.id }
    }
    val displayedPairs =
        if (orderedIds.isEmpty() && draggingId == null) {
            pairs
        } else {
            orderedIds.mapNotNull { id -> pairs.firstOrNull { it.id == id } }
        }
    var showFolderChoices by remember { mutableStateOf(false) }
    var folderAction by remember { mutableStateOf<FolderAction?>(null) }
    var pendingSelectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showSourceChoice by remember { mutableStateOf(false) }
    var showExistingPairs by remember { mutableStateOf(false) }

    val imagePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK_COUNT),
        ) { uris ->
            if (uris.isNotEmpty() && album?.isHidden == true && hideSourcesEnabled) {
                pendingSelectedUris = uris
                showSourceChoice = true
            } else {
                viewModel.addImages(uris)
            }
        }

    val folderPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                when (folderAction) {
                    FolderAction.IMPORT -> {
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                        viewModel.addImages(SafFolders.listImages(context, uri))
                    }
                    FolderAction.LINK -> viewModel.linkFolder(uri)
                    FolderAction.MOVE -> viewModel.moveSelectedFromFolder(pendingSelectedUris, uri)
                    null -> Unit
                }
            }
            folderAction = null
            pendingSelectedUris = emptyList()
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
    LaunchedEffect(fileError) {
        val error = fileError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.clearFileError()
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
                    IconButton(
                        onClick = {
                            viewModel.loadPairSources()
                            showExistingPairs = true
                        },
                        modifier = Modifier.semantics { contentDescription = "Add existing pair" },
                    ) { PairIcon() }
                    IconButton(
                        onClick = { showFolderChoices = true },
                        modifier = Modifier.semantics { contentDescription = "Import or link folder" },
                    ) { FolderIcon() }
                    IconButton(onClick = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_images)) }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.album_settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
                val columns = if (maxWidth < 600.dp) GridCells.Fixed(2) else GridCells.Adaptive(240.dp)
                LazyVerticalGrid(
                    columns = columns,
                    contentPadding = PaddingValues(bottom = 16.dp),
                    state = gridState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(displayedPairs, key = { _, pair -> pair.id }) { index, pair ->
                        PairCell(
                            pair = pair,
                            number = index + 1,
                            dragging = draggingId == pair.id,
                            dragOffset = if (draggingId == pair.id) dragOffset else Offset.Zero,
                            modifier = Modifier.animateItem(),
                            onDragStart = {
                                draggingId = pair.id
                                dragOffset = Offset.Zero
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { delta ->
                                dragOffset += delta
                                val current = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingId }
                                if (current != null) {
                                    val center =
                                        Offset(
                                            current.offset.x + current.size.width / 2f + dragOffset.x,
                                            current.offset.y + current.size.height / 2f + dragOffset.y,
                                        )
                                    val target =
                                        gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                            val inColumn =
                                                center.x >= item.offset.x &&
                                                    center.x <= item.offset.x + item.size.width
                                            val inRow =
                                                center.y >= item.offset.y &&
                                                    center.y <= item.offset.y + item.size.height
                                            inColumn && inRow
                                        }?.key as? Long
                                    if (target != null && target != pair.id) {
                                        val targetItem =
                                            gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == target }
                                        val from = orderedIds.indexOf(pair.id)
                                        val to = orderedIds.indexOf(target)
                                        if (from >= 0 && to >= 0 && targetItem != null) {
                                            dragOffset -=
                                                Offset(
                                                    (targetItem.offset.x - current.offset.x).toFloat(),
                                                    (targetItem.offset.y - current.offset.y).toFloat(),
                                                )
                                            orderedIds = orderedIds.toMutableList().apply { add(to, removeAt(from)) }
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                    val viewportHeight = gridState.layoutInfo.viewportEndOffset
                                    if (center.y > viewportHeight - DRAG_EDGE_PX) {
                                        dragScope.launch { gridState.scrollBy(DRAG_SCROLL_PX) }
                                    } else if (center.y < DRAG_EDGE_PX) {
                                        dragScope.launch { gridState.scrollBy(-DRAG_SCROLL_PX) }
                                    }
                                }
                            },
                            onDragEnd = {
                                viewModel.reorder(orderedIds)
                                draggingId = null
                                dragOffset = Offset.Zero
                            },
                            onDragCancel = {
                                draggingId = null
                                dragOffset = Offset.Zero
                                orderedIds = pairs.map { it.id }
                            },
                            displayTarget = displayTarget,
                            displayAspect = displayAspect,
                            stackPreviews = stackPreviews,
                            thumbnailVersion = thumbnailVersion,
                            viewModel = viewModel,
                            isCover = pair.id == album?.coverPairId,
                            onOpen = { onEditPair(pair.id) },
                            onApply = { viewModel.applyNow(pair.id) },
                            onSetCover = { viewModel.setCover(pair.id) },
                            onSetHomeImage = { slotRequest = SlotRequest(pair.id, WallpaperSurface.HOME) },
                            onSetLockImage = { slotRequest = SlotRequest(pair.id, WallpaperSurface.LOCK) },
                            onDelete = { viewModel.deletePair(pair.id) },
                        )
                    }
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
    if (showFolderChoices) {
        AlertDialog(
            onDismissRequest = { showFolderChoices = false },
            title = { Text("Add folder") },
            text = { Text("Import copies photos once. Link checks for new photos at each wallpaper change.") },
            confirmButton = {
                TextButton(onClick = {
                    showFolderChoices = false
                    folderAction = FolderAction.LINK
                    folderPicker.launch(null)
                }) { Text("Link folder") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFolderChoices = false
                    folderAction = FolderAction.IMPORT
                    folderPicker.launch(null)
                }) { Text("Import folder") }
            },
        )
    }
    if (showExistingPairs) {
        AlertDialog(
            onDismissRequest = { showExistingPairs = false },
            title = { Text("Add existing pair") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (pairSources.isEmpty()) Text("No pairs in other available albums")
                    pairSources.forEach { source ->
                        TextButton(onClick = {
                            showExistingPairs = false
                            viewModel.addExistingPair(source.pair)
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PairThumbnails(
                                    pair = source.pair,
                                    displayTarget = displayTarget,
                                    displayAspect = displayAspect,
                                    stackPreviews = stackPreviews,
                                    thumbnailVersion = thumbnailVersion,
                                    previewImage = viewModel::preview,
                                    modifier = Modifier.width(76.dp),
                                )
                                Text(
                                    "${source.albumName} · ${source.pair.home.displayName}",
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showExistingPairs = false }) { Text("Cancel") } },
        )
    }
    if (showSourceChoice) {
        AlertDialog(
            onDismissRequest = {
                showSourceChoice = false
                pendingSelectedUris = emptyList()
            },
            title = { Text("Hide source photos") },
            text = {
                Text(
                    "Choose a source folder or use full access to move exact local matches. " +
                        "Other apps or cloud services may retain copies.",
                )
            },
            confirmButton = {
                Column {
                    if (Environment.isExternalStorageManager()) {
                        TextButton(onClick = {
                            showSourceChoice = false
                            viewModel.moveSelectedWithFullAccess(pendingSelectedUris)
                            pendingSelectedUris = emptyList()
                        }) { Text("Use full access and move") }
                    }
                    TextButton(onClick = {
                        showSourceChoice = false
                        folderAction = FolderAction.MOVE
                        folderPicker.launch(null)
                    }) { Text("Choose source folder and move") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSourceChoice = false
                    viewModel.addImages(pendingSelectedUris)
                    pendingSelectedUris = emptyList()
                }) { Text("Keep original visible") }
            },
        )
    }
}

private enum class FolderAction { IMPORT, LINK, MOVE }

@Composable
private fun PairIcon() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(24.dp)) {
        drawRect(
            color,
            topLeft = Offset(size.width * 0.08f, size.height * 0.13f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.6f, size.height * 0.6f),
            style = Stroke(width = size.width * 0.07f),
        )
        drawRect(
            color,
            topLeft = Offset(size.width * 0.24f, size.height * 0.29f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.6f, size.height * 0.6f),
            style = Stroke(width = size.width * 0.07f),
        )
    }
}

@Composable
private fun FolderIcon() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(24.dp)) {
        val path =
            Path().apply {
                moveTo(size.width * 0.08f, size.height * 0.2f)
                lineTo(size.width * 0.4f, size.height * 0.2f)
                lineTo(size.width * 0.5f, size.height * 0.34f)
                lineTo(size.width * 0.92f, size.height * 0.34f)
                lineTo(size.width * 0.92f, size.height * 0.82f)
                lineTo(size.width * 0.08f, size.height * 0.82f)
                close()
            }
        drawPath(path, color, style = Stroke(width = size.width * 0.07f))
    }
}

@Composable
private fun PairCell(
    pair: BackgroundPair,
    number: Int,
    dragging: Boolean,
    dragOffset: Offset,
    modifier: Modifier,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    displayTarget: DisplayTarget,
    displayAspect: Float,
    stackPreviews: Boolean,
    thumbnailVersion: Int,
    viewModel: AlbumViewModel,
    isCover: Boolean,
    onOpen: () -> Unit,
    onApply: () -> Unit,
    onSetCover: () -> Unit,
    onSetHomeImage: () -> Unit,
    onSetLockImage: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dragScale by animateFloatAsState(
        targetValue = if (dragging) 1.06f else 1f,
        animationSpec = spring(),
        label = "Pair drag scale",
    )
    Card(
        onClick = onOpen,
        modifier =
            modifier.fillMaxWidth()
                .zIndex(if (dragging) 1f else 0f)
                .graphicsLayer {
                    translationX = dragOffset.x
                    translationY = dragOffset.y
                    scaleX = dragScale
                    scaleY = dragScale
                    shadowElevation = if (dragging) 18.dp.toPx() else 0f
                }
                .pointerInput(pair.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                        onDrag = { change, delta ->
                            change.consume()
                            onDrag(delta)
                        },
                    )
                },
    ) {
        WallpaperPairContent(
            pair = pair,
            number = number,
            displayTarget = displayTarget,
            displayAspect = displayAspect,
            stackPreviews = stackPreviews,
            thumbnailVersion = thumbnailVersion,
            previewImage = viewModel::preview,
        ) {
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

private data class SlotRequest(
    val pairId: Long,
    val surface: WallpaperSurface,
)

private const val MAX_PICK_COUNT = 50
private const val DRAG_EDGE_PX = 120f
private const val DRAG_SCROLL_PX = 35f
