package dev.backgrounded

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import dagger.hilt.android.AndroidEntryPoint
import dev.backgrounded.core.image.ThumbnailCache
import dev.backgrounded.ui.AppRoot
import dev.backgrounded.ui.LocalThumbnailCache
import dev.backgrounded.ui.theme.BackgroundedTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var thumbnailCache: ThumbnailCache

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BackgroundedTheme {
                CompositionLocalProvider(LocalThumbnailCache provides thumbnailCache) {
                    AppRoot()
                }
            }
        }
    }
}
