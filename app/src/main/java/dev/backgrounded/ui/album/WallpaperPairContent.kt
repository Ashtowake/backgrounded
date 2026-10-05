package dev.backgrounded.ui.album

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.backgrounded.R
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.BackgroundPair
import dev.backgrounded.domain.model.DisplayTarget
import dev.backgrounded.domain.model.WallpaperSurface

@Composable
fun WallpaperPairContent(
    pair: BackgroundPair,
    number: Int,
    displayTarget: DisplayTarget,
    displayAspect: Float,
    stackPreviews: Boolean,
    thumbnailVersion: Int,
    previewImage: (Background, WallpaperSurface, DisplayTarget, Float) -> android.graphics.Bitmap?,
    title: String = "$number. ${pair.home.displayName}",
    actions: @Composable () -> Unit = {},
) {
    Column {
        PairThumbnails(
            pair,
            displayTarget,
            displayAspect,
            stackPreviews,
            thumbnailVersion,
            previewImage,
            Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
    }
}

@Composable
fun PairThumbnails(
    pair: BackgroundPair,
    displayTarget: DisplayTarget,
    displayAspect: Float,
    stackPreviews: Boolean,
    thumbnailVersion: Int,
    previewImage: (Background, WallpaperSurface, DisplayTarget, Float) -> android.graphics.Bitmap?,
    modifier: Modifier,
) {
    val thumbnails: @Composable (Modifier) -> Unit = { thumbnailModifier ->
        listOf(WallpaperSurface.LOCK, WallpaperSurface.HOME).forEach { surface ->
            SlotThumbnail(
                background = if (surface == WallpaperSurface.LOCK) pair.lock else pair.home,
                surface = surface,
                displayTarget = displayTarget,
                displayAspect = displayAspect,
                thumbnailVersion = thumbnailVersion,
                previewImage = previewImage,
                modifier = thumbnailModifier,
            )
        }
    }
    if (stackPreviews) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            thumbnails(Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            thumbnails(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SlotThumbnail(
    background: Background,
    surface: WallpaperSurface,
    displayTarget: DisplayTarget,
    displayAspect: Float,
    thumbnailVersion: Int,
    previewImage: (Background, WallpaperSurface, DisplayTarget, Float) -> android.graphics.Bitmap?,
    modifier: Modifier = Modifier,
) {
    val framing = background.framingFor(displayTarget, surface)
    val preview =
        remember(
            background.id,
            background.storageRef,
            framing,
            background.dimForLock,
            surface,
            displayTarget,
            displayAspect,
            thumbnailVersion,
        ) {
            previewImage(background, surface, displayTarget, displayAspect)
        }
    Box(modifier = modifier.aspectRatio(displayAspect)) {
        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = background.displayName,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
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
