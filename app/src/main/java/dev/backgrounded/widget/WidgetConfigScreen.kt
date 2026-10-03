package dev.backgrounded.widget

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.backgrounded.R
import dev.backgrounded.domain.model.GestureAction

@Composable
fun WidgetConfigScreen(
    viewModel: WidgetConfigViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val iconPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri -> uri?.let(viewModel::importCustomIcon) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.widget_configure), style = MaterialTheme.typography.titleMedium)

        Text(text = stringResource(R.string.widget_icon), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "builtin:next" to R.string.icon_next,
                "builtin:previous" to R.string.icon_previous,
                "builtin:album" to R.string.icon_album,
                "builtin:pause" to R.string.icon_pause,
                "builtin:invisible" to R.string.icon_invisible,
            ).forEach { (source, label) ->
                FilterChip(
                    selected = state.iconSource == source,
                    onClick = { viewModel.setIconSource(source) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        FilterChip(
            selected = state.iconSource.startsWith("file:"),
            onClick = {
                iconPicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                    ),
                )
            },
            label = { Text(stringResource(R.string.icon_custom)) },
        )

        Text(text = stringResource(R.string.widget_icon_alpha), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = state.iconAlpha.toFloat(),
            onValueChange = { viewModel.setIconAlpha(it.toInt()) },
            valueRange = 0f..255f,
        )

        Text(text = stringResource(R.string.widget_background_alpha), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = state.backgroundAlpha.toFloat(),
            onValueChange = { viewModel.setBackgroundAlpha(it.toInt()) },
            valueRange = 0f..255f,
        )

        ActionSelector(
            title = stringResource(R.string.widget_tap_action),
            selected = state.tapAction,
            onSelect = viewModel::setTapAction,
        )
        ActionSelector(
            title = stringResource(R.string.widget_double_tap_action),
            selected = state.doubleTapAction,
            onSelect = viewModel::setDoubleTapAction,
        )

        Text(text = stringResource(R.string.widget_pinned_album), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.pinnedAlbumId == null,
                onClick = { viewModel.setPinnedAlbum(null) },
                label = { Text(stringResource(R.string.widget_active_album)) },
            )
            state.albums.forEach { album ->
                FilterChip(
                    selected = state.pinnedAlbumId == album.id,
                    onClick = { viewModel.setPinnedAlbum(album.id) },
                    label = { Text(album.name) },
                )
            }
        }

        Spacer(modifier = Modifier.width(1.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.save(onDone) }) {
                Text(stringResource(R.string.save))
            }
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun ActionSelector(
    title: String,
    selected: GestureAction,
    onSelect: (GestureAction) -> Unit,
) {
    Text(text = title, style = MaterialTheme.typography.labelLarge)
    val labels =
        mapOf(
            GestureAction.NEXT to R.string.action_next,
            GestureAction.PREVIOUS to R.string.action_previous,
            GestureAction.NEXT_ALBUM to R.string.action_next_album,
            GestureAction.TOGGLE_PAUSE to R.string.action_toggle_pause,
            GestureAction.OPEN_APP to R.string.action_open_app,
        )
    Column {
        labels.forEach { (action, label) ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected == action, onClick = { onSelect(action) })
                        .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == action, onClick = { onSelect(action) })
                Text(text = stringResource(label))
            }
        }
    }
}
