package dev.backgrounded.ui.albumsettings

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.backgrounded.R
import dev.backgrounded.core.security.DeviceCredentialGate
import dev.backgrounded.domain.model.RotationOrder
import dev.backgrounded.domain.model.ScheduleType
import dev.backgrounded.domain.model.WallpaperSurface
import dev.backgrounded.ui.components.SectionTitle
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumSettingsScreen(onBack: () -> Unit) {
    val viewModel: AlbumSettingsViewModel = hiltViewModel()
    val album by viewModel.album.collectAsStateWithLifecycle()
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    val current = album ?: return
    var pickerSurface by remember { mutableStateOf<WallpaperSurface?>(null) }

    val context = LocalContext.current
    val pendingHideLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) viewModel.setHidden(true)
        }

    var name by remember(current.id) { mutableStateOf(current.name) }
    var scheduleType by remember(current.id) { mutableStateOf(current.scheduleType) }
    var intervalMinutes by remember(current.id) {
        mutableStateOf(current.intervalMinutes ?: DEFAULT_INTERVAL_MINUTES)
    }
    var showTimePicker by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(current.scheduleType, current.intervalMinutes) {
        scheduleType = current.scheduleType
        current.intervalMinutes?.let { intervalMinutes = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.album_settings)) },
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
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.album_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { viewModel.rename(name) }) {
                Text(stringResource(R.string.rename))
            }

            Text(text = stringResource(R.string.rotation_order), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = current.rotationOrder == RotationOrder.SEQUENTIAL,
                    onClick = { viewModel.setOrder(RotationOrder.SEQUENTIAL) },
                    label = { Text(stringResource(R.string.order_sequential)) },
                )
                FilterChip(
                    selected = current.rotationOrder == RotationOrder.SHUFFLE,
                    onClick = { viewModel.setOrder(RotationOrder.SHUFFLE) },
                    label = { Text(stringResource(R.string.order_shuffle)) },
                )
            }

            Text(text = stringResource(R.string.schedule), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScheduleType.entries.forEach { type ->
                    FilterChip(
                        selected = scheduleType == type,
                        onClick = {
                            scheduleType = type
                            viewModel.setSchedule(
                                type,
                                if (type == ScheduleType.INTERVAL) intervalMinutes else null,
                                current.fixedTimes,
                            )
                        },
                        label = { Text(stringResource(scheduleTypeLabel(type))) },
                    )
                }
            }
            if (scheduleType == ScheduleType.INTERVAL) {
                Text(
                    text = stringResource(R.string.interval_minutes) + ": " + intervalMinutes,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = intervalMinutes.toFloat(),
                    onValueChange = {
                        intervalMinutes = it.toInt().coerceAtLeast(MIN_INTERVAL_MINUTES)
                    },
                    onValueChangeFinished = {
                        viewModel.setSchedule(ScheduleType.INTERVAL, intervalMinutes, current.fixedTimes)
                    },
                    valueRange = MIN_INTERVAL_MINUTES.toFloat()..MAX_INTERVAL_MINUTES.toFloat(),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    INTERVAL_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = intervalMinutes == preset,
                            onClick = {
                                intervalMinutes = preset
                                viewModel.setSchedule(ScheduleType.INTERVAL, preset, current.fixedTimes)
                            },
                            label = { Text(preset.toString()) },
                        )
                    }
                }
            }
            if (scheduleType == ScheduleType.FIXED_TIMES) {
                current.fixedTimes.forEach { time ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = time.toString(), modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.removeFixedTime(time) }) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
                TextButton(onClick = { showTimePicker = true }) {
                    Text(stringResource(R.string.add_time))
                }
            }

            Text(
                text = stringResource(R.string.unlock_change),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = current.unlockPolicy.enabled,
                    onCheckedChange = { enabled ->
                        viewModel.setUnlockPolicy(
                            enabled,
                            current.unlockPolicy.minMinutes,
                            current.unlockPolicy.everyN,
                            current.unlockPolicy.maxPerDay,
                        )
                    },
                )
                Text(
                    text = stringResource(R.string.unlock_change),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (current.unlockPolicy.enabled) {
                NumberField(
                    label = stringResource(R.string.unlock_min_minutes),
                    value = current.unlockPolicy.minMinutes,
                    onChange = { value ->
                        viewModel.setUnlockPolicy(
                            true,
                            value,
                            current.unlockPolicy.everyN,
                            current.unlockPolicy.maxPerDay,
                        )
                    },
                )
                NumberField(
                    label = stringResource(R.string.unlock_every_n),
                    value = current.unlockPolicy.everyN,
                    onChange = { value ->
                        viewModel.setUnlockPolicy(
                            true,
                            current.unlockPolicy.minMinutes,
                            value,
                            current.unlockPolicy.maxPerDay,
                        )
                    },
                )
                NumberField(
                    label = stringResource(R.string.unlock_max_day),
                    value = current.unlockPolicy.maxPerDay,
                    onChange = { value ->
                        viewModel.setUnlockPolicy(
                            true,
                            current.unlockPolicy.minMinutes,
                            current.unlockPolicy.everyN,
                            value,
                        )
                    },
                )
            }

            SectionTitle(stringResource(R.string.fixed_images))
            FixedAssetRow(
                label = stringResource(R.string.fixed_home_image),
                assetName =
                    assets.firstOrNull { it.id == current.fixedHomeAssetId }?.displayName
                        ?: stringResource(R.string.none),
                onChoose = { pickerSurface = WallpaperSurface.HOME },
            )
            FixedAssetRow(
                label = stringResource(R.string.fixed_lock_image),
                assetName =
                    assets.firstOrNull { it.id == current.fixedLockAssetId }?.displayName
                        ?: stringResource(R.string.none),
                onChoose = { pickerSurface = WallpaperSurface.LOCK },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = current.isHidden,
                    onCheckedChange = { checked ->
                        if (!checked) {
                            viewModel.setHidden(false)
                        } else {
                            val intent =
                                DeviceCredentialGate.confirmIntent(
                                    context,
                                    context.getString(R.string.hidden_albums),
                                )
                            if (intent != null) {
                                pendingHideLauncher.launch(intent)
                            } else {
                                viewModel.setHidden(true)
                            }
                        }
                    },
                )
                Text(text = stringResource(R.string.hidden_label), modifier = Modifier.padding(start = 8.dp))
            }

            Button(onClick = { showDeleteDialog = true }) {
                Text(stringResource(R.string.delete_album))
            }
        }
    }

    if (showTimePicker) {
        AddTimeDialog(
            onDismiss = { showTimePicker = false },
            onConfirm = { time ->
                viewModel.addFixedTime(time)
                showTimePicker = false
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_album)) },
            text = { Text(stringResource(R.string.delete_album_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.delete(onBack)
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val picker = pickerSurface
    if (picker != null) {
        AlertDialog(
            onDismissRequest = { pickerSurface = null },
            title = {
                Text(
                    stringResource(
                        if (picker == WallpaperSurface.HOME) {
                            R.string.fixed_home_image
                        } else {
                            R.string.fixed_lock_image
                        },
                    ),
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = {
                            viewModel.setFixedAsset(picker, null)
                            pickerSurface = null
                        },
                    ) {
                        Text(stringResource(R.string.none))
                    }
                    assets.forEach { asset ->
                        TextButton(
                            onClick = {
                                viewModel.setFixedAsset(picker, asset.id)
                                pickerSurface = null
                            },
                        ) {
                            Text(asset.displayName)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickerSurface = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun FixedAssetRow(
    label: String,
    assetName: String,
    onChoose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, modifier = Modifier.weight(1f))
        Text(text = assetName, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onChoose) {
            Text(stringResource(R.string.choose))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTimeDialog(
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = 8, initialMinute = 0, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                Text(stringResource(R.string.add_time))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        text = { TimePicker(state = state) },
    )
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.isEmpty()) {
                text = input
                onChange(0)
            } else {
                input.toIntOrNull()?.let { parsed ->
                    text = input
                    onChange(parsed)
                }
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun scheduleTypeLabel(type: ScheduleType): Int =
    when (type) {
        ScheduleType.NONE -> R.string.schedule_none
        ScheduleType.INTERVAL -> R.string.schedule_interval
        ScheduleType.FIXED_TIMES -> R.string.schedule_fixed
    }

private const val DEFAULT_INTERVAL_MINUTES = 60
private const val MIN_INTERVAL_MINUTES = 1
private const val MAX_INTERVAL_MINUTES = 240
private val INTERVAL_PRESETS = listOf(1, 5, 15, 30, 60)
