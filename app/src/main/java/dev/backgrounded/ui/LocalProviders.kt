package dev.backgrounded.ui

import androidx.compose.runtime.compositionLocalOf
import dev.backgrounded.core.image.ThumbnailCache

val LocalThumbnailCache = compositionLocalOf<ThumbnailCache> { error("ThumbnailCache not provided") }
