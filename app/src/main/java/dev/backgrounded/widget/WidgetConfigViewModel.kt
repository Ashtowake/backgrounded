package dev.backgrounded.widget

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.importer.ImageImporter
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Album
import dev.backgrounded.domain.model.GestureAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WidgetConfigUiState(
    val iconSource: String = "builtin:next",
    val iconAlpha: Int = 255,
    val backgroundAlpha: Int = 0,
    val tapAction: GestureAction = GestureAction.NEXT,
    val doubleTapAction: GestureAction = GestureAction.NEXT_ALBUM,
    val pinnedAlbumId: Long? = null,
    val albums: List<Album> = emptyList(),
)

@HiltViewModel
class WidgetConfigViewModel
    @Inject
    constructor(
        @ApplicationContext private val applicationContext: Context,
        private val settingsStore: SettingsStore,
        private val albumRepository: AlbumRepository,
        private val imageImporter: ImageImporter,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(WidgetConfigUiState())
        val state: StateFlow<WidgetConfigUiState> = mutableState.asStateFlow()

        init {
            viewModelScope.launch {
                val settings = settingsStore.settings.first()
                mutableState.update {
                    it.copy(
                        iconSource = settings.widgetIconSource,
                        iconAlpha = settings.widgetIconAlpha,
                        backgroundAlpha = settings.widgetBackgroundAlpha,
                        tapAction = settings.widgetTapAction,
                        doubleTapAction = settings.widgetDoubleTapAction,
                        pinnedAlbumId = settings.widgetPinnedAlbumId,
                    )
                }
            }
            viewModelScope.launch {
                albumRepository.observeAlbums().collect { albums ->
                    mutableState.update { it.copy(albums = albums) }
                }
            }
        }

        fun setIconSource(source: String) = mutableState.update { it.copy(iconSource = source) }

        fun setIconAlpha(alpha: Int) = mutableState.update { it.copy(iconAlpha = alpha) }

        fun setBackgroundAlpha(alpha: Int) = mutableState.update { it.copy(backgroundAlpha = alpha) }

        fun setTapAction(action: GestureAction) = mutableState.update { it.copy(tapAction = action) }

        fun setDoubleTapAction(action: GestureAction) = mutableState.update { it.copy(doubleTapAction = action) }

        fun setPinnedAlbum(albumId: Long?) = mutableState.update { it.copy(pinnedAlbumId = albumId) }

        fun importCustomIcon(uri: Uri) {
            viewModelScope.launch {
                val imported = imageImporter.import(uri) ?: return@launch
                mutableState.update { it.copy(iconSource = "file:${imported.filePath}") }
            }
        }

        fun save(onSaved: () -> Unit) {
            val current = mutableState.value
            viewModelScope.launch {
                settingsStore.setWidgetConfig(
                    iconSource = current.iconSource,
                    iconAlpha = current.iconAlpha,
                    backgroundAlpha = current.backgroundAlpha,
                    tapAction = current.tapAction,
                    doubleTapAction = current.doubleTapAction,
                    pinnedAlbumId = current.pinnedAlbumId,
                )
                WallpaperWidget.updateAll(applicationContext)
                onSaved()
            }
        }
    }
