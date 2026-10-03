package dev.backgrounded.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.backgrounded.R
import dev.backgrounded.domain.model.DoubleTapMode
import dev.backgrounded.domain.model.GestureAction
import dev.backgrounded.ui.components.LabelValueRow
import dev.backgrounded.ui.components.SectionTitle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            uri?.let {
                viewModel.exportTo(it) { success ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(if (success) R.string.export_finished else R.string.import_failed),
                        )
                    }
                }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let {
                viewModel.importFrom(it) { success ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(if (success) R.string.import_finished else R.string.import_failed),
                        )
                    }
                }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = settings.rotationPaused, onCheckedChange = { viewModel.togglePause() })
                Text(
                    text = stringResource(R.string.pause),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            SectionTitle(stringResource(R.string.double_tap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.doubleTapEnabled,
                    onCheckedChange = viewModel::setDoubleTapEnabled,
                )
                Text(
                    text = stringResource(R.string.double_tap_enabled),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (settings.doubleTapEnabled) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = settings.doubleTapMode == DoubleTapMode.BACKGROUND,
                        onClick = { viewModel.setDoubleTapMode(DoubleTapMode.BACKGROUND) },
                        label = { Text(stringResource(R.string.mode_background)) },
                    )
                    FilterChip(
                        selected = settings.doubleTapMode == DoubleTapMode.ANYWHERE,
                        onClick = { viewModel.setDoubleTapMode(DoubleTapMode.ANYWHERE) },
                        label = { Text(stringResource(R.string.mode_anywhere)) },
                    )
                }
                Text(
                    text = stringResource(R.string.double_tap_action),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GestureAction.entries.forEach { action ->
                        FilterChip(
                            selected = settings.doubleTapAction == action,
                            onClick = { viewModel.setDoubleTapAction(action) },
                            label = { Text(stringResource(actionLabel(action))) },
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.crossfadeEnabled,
                    onCheckedChange = viewModel::setCrossfade,
                )
                Text(
                    text = stringResource(R.string.crossfade),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.lockDimDefault,
                    onCheckedChange = viewModel::setLockDimDefault,
                )
                Text(
                    text = stringResource(R.string.lock_dim_default),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Text(
                text =
                    stringResource(R.string.crossfade_duration) +
                        ": " + settings.crossfadeDurationMs + " ms",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = settings.crossfadeDurationMs.toFloat(),
                onValueChange = { viewModel.setCrossfadeDuration(it.toInt()) },
                valueRange = 100f..3000f,
            )

            SectionTitle(stringResource(R.string.history))
            TextButton(onClick = onOpenHistory) {
                Text(stringResource(R.string.history))
            }

            SectionTitle(stringResource(R.string.export_config))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch("backgrounded-backup.json") }) {
                    Text(stringResource(R.string.export_config))
                }
                Button(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                    Text(stringResource(R.string.import_config))
                }
            }

            SectionTitle(stringResource(R.string.external_control))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.externalControlEnabled,
                    onCheckedChange = viewModel::setExternalControl,
                )
                Text(
                    text = stringResource(R.string.external_control),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = stringResource(R.string.external_control_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle(stringResource(R.string.about))
            val version =
                remember {
                    runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull().orEmpty()
                }
            LabelValueRow(
                label = stringResource(R.string.version),
                value = version,
                modifier = Modifier.fillMaxWidth(),
            )
            LabelValueRow(
                label = stringResource(R.string.license),
                value = stringResource(R.string.license_value),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.permissions_none),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun actionLabel(action: GestureAction): Int =
    when (action) {
        GestureAction.NEXT -> R.string.action_next
        GestureAction.PREVIOUS -> R.string.action_previous
        GestureAction.NEXT_ALBUM -> R.string.action_next_album
        GestureAction.TOGGLE_PAUSE -> R.string.action_toggle_pause
        GestureAction.OPEN_APP -> R.string.action_open_app
    }
