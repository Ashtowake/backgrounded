package dev.backgrounded.widget

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.backgrounded.R
import dev.backgrounded.ui.album.WallpaperPairContent

@Composable
fun GalleryPickerScreen(
    viewModel: GalleryPickerViewModel,
    onDismiss: () -> Unit,
    onAuthenticate: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val albumPreviews by viewModel.albumPreviews.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val applying by viewModel.applying.collectAsStateWithLifecycle()
    val target = viewModel.activeTarget()
    val aspect = viewModel.aspect(target)
    val stack = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.selectedAlbum?.id) { gridState.scrollToItem(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { if (state.selectedAlbum != null) viewModel.up() else onDismiss() }
        BoxWithConstraints(
            Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } }.padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier =
                    Modifier.width(maxWidth.coerceAtMost(880.dp)).height(maxHeight * 0.82f)
                        .pointerInput(Unit) { detectTapGestures {} },
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.selectedAlbum?.name ?: "Albums",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        val locked = state.encryptionEnabled && viewModel.needsUnlock()
                        if (state.albums.any { it.isHidden } && (!state.showHidden || locked)) {
                            IconButton(onClick = onAuthenticate) {
                                Icon(
                                    painterResource(R.drawable.ic_gallery_eye),
                                    contentDescription = "Show hidden albums",
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(160.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.selectedAlbum != null) {
                            item(key = "up") {
                                Card(
                                    onClick = viewModel::up,
                                    modifier = Modifier.semantics { contentDescription = "Up to albums" },
                                    colors = galleryCardColors(),
                                ) {
                                    GalleryPlaceholder(aspect, stack, R.drawable.ic_gallery_up)
                                    GalleryCaption(".. / Albums")
                                }
                            }
                            itemsIndexed(state.visiblePairs, key = { _, pair -> pair.id }) { index, pair ->
                                Card(
                                    onClick = { if (!applying) onSelect(pair.id) },
                                    colors = galleryCardColors(),
                                ) {
                                    WallpaperPairContent(
                                        pair,
                                        index + 1,
                                        target,
                                        aspect,
                                        stack,
                                        viewModel::preview,
                                    )
                                }
                            }
                            if (state.visiblePairs.isEmpty()) {
                                item(key = "empty") { Text("No images", modifier = Modifier.padding(16.dp)) }
                            }
                        } else {
                            items(state.visibleAlbums, key = { it.id }) { album ->
                                Card(onClick = { viewModel.openAlbum(album.id) }, colors = galleryCardColors()) {
                                    val preview = albumPreviews[album.id]
                                    if (preview != null) {
                                        WallpaperPairContent(
                                            preview,
                                            1,
                                            target,
                                            aspect,
                                            stack,
                                            viewModel::preview,
                                            title = album.name,
                                        )
                                    } else {
                                        GalleryPlaceholder(aspect, stack, R.drawable.ic_gallery_folder)
                                        GalleryCaption(album.name)
                                    }
                                }
                            }
                            if (state.visibleAlbums.isEmpty()) {
                                item(key = "empty") { Text("No albums", modifier = Modifier.padding(16.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun galleryCardColors() =
    CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.75f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    )

@Composable
private fun GalleryPlaceholder(
    aspect: Float,
    stack: Boolean,
    icon: Int,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val previewHeight = if (stack) maxWidth / aspect * 2 + 2.dp else (maxWidth - 2.dp) / 2 / aspect
        Box(Modifier.fillMaxWidth().height(previewHeight), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun GalleryCaption(title: String) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
