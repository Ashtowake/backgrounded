package dev.backgrounded.ui.albumsettings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.backgrounded.R
import dev.backgrounded.domain.model.RotationOrder
import dev.backgrounded.domain.model.ScheduleType
import dev.backgrounded.domain.model.SlideMode
import dev.backgrounded.domain.model.WallpaperSurface
import dev.backgrounded.ui.components.SectionTitle
import java.time.LocalTime
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumSettingsScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val viewModel: AlbumSettingsViewModel = hiltViewModel()
    val album by viewModel.album.collectAsStateWithLifecycle()
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    val deleteError by viewModel.deleteError.collectAsStateWithLifecycle()
    val current = album ?: return
    var pickerSurface by remember { mutableStateOf<WallpaperSurface?>(null) }
    val phonePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val surface = pickerSurface
            if (uri != null && surface != null) viewModel.pickFixedAsset(surface, uri)
            pickerSurface = null
        }

    var name by remember(current.id) { mutableStateOf(current.name) }
    var scheduleType by remember(current.id) { mutableStateOf(current.scheduleType) }
    var intervalSeconds by remember(current.id) {
        mutableStateOf(current.intervalSeconds ?: current.intervalMinutes?.times(60) ?: DEFAULT_INTERVAL_SECONDS)
    }
    var crossfadeDuration by remember(current.id, current.crossfadeDurationMs) {
        mutableStateOf(current.crossfadeDurationMs.toFloat())
    }
    var slideSpeed by remember(current.id) { mutableStateOf(current.slideSpeedPxPerSecond) }
    var showIntervalPicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(current.scheduleType, current.intervalSeconds, current.slideSpeedPxPerSecond) {
        scheduleType = current.scheduleType
        (current.intervalSeconds ?: current.intervalMinutes?.times(60))?.let { intervalSeconds = it }
        slideSpeed = current.slideSpeedPxPerSecond
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = current.rotationEnabled, onCheckedChange = viewModel::setRotationEnabled)
                Text("Include in Next album", modifier = Modifier.padding(start = 8.dp))
            }
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = current.crossfadeEnabled,
                    onCheckedChange = { viewModel.setCrossfade(it, current.crossfadeDurationMs) },
                )
                Text(stringResource(R.string.crossfade), modifier = Modifier.padding(start = 8.dp))
            }
            if (current.crossfadeEnabled) {
                Text("${stringResource(R.string.crossfade_duration)}: ${crossfadeDuration.toInt()} ms")
                Slider(
                    value = crossfadeDuration,
                    onValueChange = { crossfadeDuration = it },
                    onValueChangeFinished = { viewModel.setCrossfade(true, crossfadeDuration.toInt()) },
                    valueRange = 100f..3000f,
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
                                if (type == ScheduleType.INTERVAL) intervalSeconds else null,
                                current.fixedTimes,
                            )
                        },
                        label = { Text(stringResource(scheduleTypeLabel(type))) },
                    )
                }
            }
            if (scheduleType == ScheduleType.INTERVAL) {
                Button(onClick = { showIntervalPicker = true }) {
                    Text(
                        "Interval: %02d:%02d:%02d".format(
                            intervalSeconds / 3600,
                            intervalSeconds / 60 % 60,
                            intervalSeconds % 60,
                        ),
                    )
                }
                Text("Slide animation", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SlideMode.entries.forEach { mode ->
                        FilterChip(
                            selected = current.slideMode == mode,
                            onClick = {
                                viewModel.setSlideOptions(mode, slideSpeed)
                            },
                            label = { Text(slideLabel(mode)) },
                        )
                    }
                }
                if (current.slideMode != SlideMode.OFF) {
                    Text("Slide speed: %.1f px/s".format(slideSpeed))
                    Slider(
                        value =
                            ((slideSpeed - MIN_SLIDE_SPEED) / SLIDE_SPEED_RANGE).coerceIn(0f, 1f)
                                .pow(1f / 3f),
                        onValueChange = { slideSpeed = MIN_SLIDE_SPEED + SLIDE_SPEED_RANGE * it * it * it },
                        onValueChangeFinished = { viewModel.setSlideOptions(current.slideMode, slideSpeed) },
                        valueRange = 0f..1f,
                    )
                    Text("The image reverses at its travel limits.")
                }
                Text("Short intervals can be delayed while the wallpaper service is stopped.")
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
    if (showIntervalPicker) {
        IntervalPickerDialog(
            initialSeconds = intervalSeconds,
            onDismiss = { showIntervalPicker = false },
            onConfirm = { seconds ->
                intervalSeconds = seconds
                viewModel.setSchedule(ScheduleType.INTERVAL, seconds, current.fixedTimes)
                showIntervalPicker = false
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
                        viewModel.delete(onDeleted)
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
    if (deleteError) {
        AlertDialog(
            onDismissRequest = viewModel::clearDeleteError,
            title = { Text("Sources still managed") },
            text = { Text("Unhide this album and restore its moved source photos before deleting it.") },
            confirmButton = { TextButton(onClick = viewModel::clearDeleteError) { Text("OK") } },
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
                    TextButton(onClick = {
                        phonePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("Choose from phone") }
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

private const val DEFAULT_INTERVAL_SECONDS = 3600
private const val MIN_SLIDE_SPEED = 0.1f
private const val SLIDE_SPEED_RANGE = 119.9f

private fun slideLabel(mode: SlideMode): String =
    when (mode) {
        SlideMode.OFF -> "Off"
        SlideMode.LEFT_TO_RIGHT -> "Left to right"
        SlideMode.RIGHT_TO_LEFT -> "Right to left"
        SlideMode.DIAGONAL_UP_RIGHT -> "Bottom left to top right"
        SlideMode.ZOOM_IN -> "Zoom"
    }

@Composable
private fun IntervalPickerDialog(
    initialSeconds: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var hours by remember { mutableStateOf(initialSeconds / 3600) }
    var minutes by remember { mutableStateOf(initialSeconds / 60 % 60) }
    var seconds by remember { mutableStateOf(initialSeconds % 60) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Interval (hours : minutes : seconds)") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IntervalWheel(hours, 99, Modifier.weight(1f)) { hours = it }
                Text(":")
                IntervalWheel(minutes, 59, Modifier.weight(1f)) { minutes = it }
                Text(":")
                IntervalWheel(seconds, 59, Modifier.weight(1f)) { seconds = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm((hours * 3600 + minutes * 60 + seconds).coerceAtLeast(1)) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IntervalWheel(
    initial: Int,
    maximum: Int,
    modifier: Modifier,
    onValue: (Int) -> Unit,
) {
    AndroidView(
        factory = { context ->
            android.widget.NumberPicker(context).apply {
                minValue = 0
                maxValue = maximum
                value = initial.coerceIn(0, maximum)
                setFormatter { "%02d".format(it) }
                wrapSelectorWheel = true
                setOnValueChangedListener { _, _, new -> onValue(new) }
            }
        },
        modifier = modifier,
    )
}
