package dev.backgrounded.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.backgrounded.R
import dev.backgrounded.domain.model.BackdropType
import dev.backgrounded.domain.model.BackgroundPair
import dev.backgrounded.domain.model.DisplayTarget
import dev.backgrounded.domain.model.FitMode
import dev.backgrounded.domain.model.Framing
import dev.backgrounded.domain.model.FramingKey
import dev.backgrounded.domain.model.ScrollMode
import dev.backgrounded.domain.model.WallpaperSurface
import dev.backgrounded.ui.components.SectionTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(onBack: () -> Unit) {
    val viewModel: EditorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pair = state.pair ?: return
    val expandedKey = state.expandedKey

    if (expandedKey == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.edit)) },
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
            val keys = orderedKeys(state)
            val pagerState = rememberPagerState(pageCount = { keys.size })
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            ) { page ->
                val key = keys[page]
                PreviewPage(
                    state = state,
                    key = key,
                    onSelect = viewModel::expand,
                    onSizeChanged = viewModel::setViewport,
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            R.string.preview_label,
                            stringResource(displayTargetLabel(expandedKey.display)),
                            stringResource(surfaceLabel(expandedKey.surface)),
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = viewModel::collapse) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save(viewModel::collapse) }) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        FramingEditor(
            state = state,
            pair = pair,
            viewModel = viewModel,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        )
    }
}

@Composable
private fun PreviewPage(
    state: EditorUiState,
    key: FramingKey,
    onSelect: (FramingKey) -> Unit,
    onSizeChanged: (width: Int, height: Int) -> Unit,
) {
    val aspect = aspectOf(state, key.display)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text =
                stringResource(
                    R.string.preview_label,
                    stringResource(displayTargetLabel(key.display)),
                    stringResource(surfaceLabel(key.surface)),
                ),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clickable { onSelect(key) },
            contentAlignment = Alignment.Center,
        ) {
            val cardHeight = minOf(maxHeight * 0.8f, maxWidth / aspect)
            Box(
                modifier =
                    Modifier
                        .width(cardHeight * aspect)
                        .height(cardHeight)
                        .background(Color.Black)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        .onSizeChanged { onSizeChanged(it.width, it.height) },
            ) {
                val preview = state.previews[key]?.asImageBitmap()
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FramingEditor(
    state: EditorUiState,
    pair: BackgroundPair,
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val key = state.expandedKey ?: return
    val asset = pair.imageFor(key.surface)
    val framing = asset.framingFor(key.display, key.surface)
    val aspect = aspectOf(state, key.display)
    var adjustBackdrop by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val canvasWidth = minOf(maxWidth, maxHeight * aspect * 0.96f)
            Box(
                modifier =
                    Modifier
                        .width(canvasWidth)
                        .height(canvasWidth / aspect)
                        .background(Color.Black)
                        .onSizeChanged { viewModel.setViewport(it.width, it.height) }
                        .pointerInput(key, adjustBackdrop) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (size.width <= 0 || size.height <= 0) {
                                    return@detectTransformGestures
                                }
                                val deltaX = pan.x / size.width
                                val deltaY = pan.y / size.height
                                if (adjustBackdrop) {
                                    viewModel.transformBackdrop(zoom, deltaX, deltaY)
                                } else {
                                    viewModel.transform(zoom, deltaX, deltaY)
                                }
                            }
                        }
                        .pointerInput(key) {
                            detectTapGestures(onDoubleTap = { viewModel.alignAndFill() })
                        },
            ) {
                val preview = state.previews[key]?.asImageBitmap()
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (!showControls) {
            Button(
                onClick = { showControls = true },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
            ) {
                Text(stringResource(R.string.adjust))
            }
        }
    }

    if (showControls) {
        ModalBottomSheet(
            onDismissRequest = { showControls = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditScope.entries.forEach { scope ->
                        FilterChip(
                            selected = state.scope == scope,
                            onClick = { viewModel.setScope(scope) },
                            label = { Text(stringResource(scopeLabel(scope))) },
                        )
                    }
                }
                FramingControls(
                    framing = framing,
                    dimForLock = asset.dimForLock,
                    surface = key.surface,
                    scrollPreview = state.scrollPreview,
                    adjustBackdrop = adjustBackdrop,
                    onAdjustBackdrop = { adjustBackdrop = it },
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun FramingControls(
    framing: Framing,
    dimForLock: Boolean,
    surface: WallpaperSurface,
    scrollPreview: Float,
    adjustBackdrop: Boolean,
    onAdjustBackdrop: (Boolean) -> Unit,
    viewModel: EditorViewModel,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FitMode.entries.forEach { mode ->
            FilterChip(
                selected = framing.fitMode == mode,
                onClick = { viewModel.setFitMode(mode) },
                label = { Text(stringResource(fitModeLabel(mode))) },
            )
        }
    }

    if (framing.fitMode == FitMode.STRETCH) {
        Text(
            text = stringResource(R.string.stretch_x) + ": " + (framing.stretchX * 100).toInt() + "%",
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = framing.stretchX,
            onValueChange = viewModel::setStretchX,
            valueRange = STRETCH_MIN..STRETCH_MAX,
        )
        Text(
            text = stringResource(R.string.stretch_y) + ": " + (framing.stretchY * 100).toInt() + "%",
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = framing.stretchY,
            onValueChange = viewModel::setStretchY,
            valueRange = STRETCH_MIN..STRETCH_MAX,
        )
    }

    if (framing.fitMode == FitMode.BACKGROUND_FILL) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = framing.backdrop == BackdropType.BLUR,
                onClick = { viewModel.setBackdropType(BackdropType.BLUR) },
                label = { Text(stringResource(R.string.backdrop_blur)) },
            )
            FilterChip(
                selected = framing.backdrop == BackdropType.COLOR,
                onClick = { viewModel.setBackdropType(BackdropType.COLOR) },
                label = { Text(stringResource(R.string.backdrop_color)) },
            )
        }
        if (framing.backdrop == BackdropType.BLUR) {
            Text(
                text = stringResource(R.string.blur_intensity),
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = framing.blurIntensity.toFloat(),
                onValueChange = { viewModel.setBlurIntensity(it.toInt()) },
                valueRange = 0f..100f,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(checked = adjustBackdrop, onCheckedChange = onAdjustBackdrop)
                Text(text = stringResource(R.string.adjust_backdrop))
            }
        } else {
            ColorPalette(
                selected = framing.backdropColor,
                onSelect = viewModel::setBackdropColor,
            )
        }
    }

    if (surface == WallpaperSurface.HOME) {
        SectionTitle(stringResource(R.string.scroll_behavior))
        ScrollControls(
            framing = framing,
            scrollPreview = scrollPreview,
            viewModel = viewModel,
        )
    }

    SectionTitle(stringResource(R.string.parallax))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(
            checked = framing.gyroParallax,
            onCheckedChange = viewModel::setGyroParallax,
        )
        Text(text = stringResource(R.string.parallax))
    }
    if (framing.gyroParallax) {
        Text(
            text = stringResource(R.string.parallax_intensity) + ": " + framing.gyroIntensity + "%",
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = framing.gyroIntensity.toFloat(),
            onValueChange = { viewModel.setGyroIntensity(it.toInt()) },
            valueRange = 0f..100f,
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = viewModel::rotateQuarterTurn) {
            Text(stringResource(R.string.rotate))
        }
        Switch(checked = dimForLock, onCheckedChange = viewModel::setDimForLock)
        Text(text = stringResource(R.string.dim_lock))
    }

    TextButton(onClick = viewModel::reset) {
        Text(stringResource(R.string.reset))
    }
}

@Composable
private fun ScrollControls(
    framing: Framing,
    scrollPreview: Float,
    viewModel: EditorViewModel,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScrollMode.entries.forEach { mode ->
            FilterChip(
                selected = framing.scrollMode == mode,
                onClick = { viewModel.setScrollMode(mode) },
                label = { Text(stringResource(scrollModeLabel(mode))) },
            )
        }
    }
    when (framing.scrollMode) {
        ScrollMode.OFF -> Unit
        ScrollMode.AMOUNT -> AmountSlider(framing.scrollAmountPercent, viewModel::setScrollAmount)
        ScrollMode.PAGES -> {
            Text(
                text = stringResource(R.string.scroll_pages_count),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (pages in 2..5) {
                    FilterChip(
                        selected = framing.scrollPages == pages,
                        onClick = { viewModel.setScrollPages(pages) },
                        label = { Text(pages.toString()) },
                    )
                }
            }
        }

        ScrollMode.CUSTOM -> {
            AmountSlider(framing.scrollAmountPercent, viewModel::setScrollAmount)
            FractionSlider(
                label = stringResource(R.string.scroll_start),
                value = framing.scrollStartFraction,
                onChange = viewModel::setScrollStart,
            )
            FractionSlider(
                label = stringResource(R.string.scroll_span),
                value = framing.scrollSpanFraction,
                onChange = viewModel::setScrollSpan,
            )
        }
    }
    if (framing.scrollMode != ScrollMode.OFF) {
        FractionSlider(
            label = stringResource(R.string.scroll_preview),
            value = scrollPreview,
            onChange = viewModel::setScrollPreview,
        )
    }
}

@Composable
private fun AmountSlider(
    percent: Int,
    onChange: (Int) -> Unit,
) {
    Text(
        text = stringResource(R.string.scroll_amount_percent) + ": " + percent + "%",
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(
        value = percent.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        valueRange = 0f..200f,
    )
}

@Composable
private fun FractionSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
) {
    Text(
        text = label + ": " + (value * 100).toInt() + "%",
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = 0f..1f,
    )
}

@Composable
private fun ColorPalette(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PALETTE.forEach { color ->
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .background(Color(color), CircleShape)
                        .border(
                            width = if (color == selected) 3.dp else 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        )
                        .clickable { onSelect(color) },
            )
        }
    }
}

private fun orderedKeys(state: EditorUiState): List<FramingKey> {
    val displays =
        listOf(DisplayTarget.COVER, DisplayTarget.INNER)
            .filter { display -> state.targets.any { it.target == display } }
            .ifEmpty { listOf(DisplayTarget.INNER) }
    return listOf(WallpaperSurface.LOCK, WallpaperSurface.HOME).flatMap { surface ->
        displays.map { display -> FramingKey(display, surface) }
    }
}

private fun aspectOf(
    state: EditorUiState,
    display: DisplayTarget,
): Float {
    val info = state.targets.firstOrNull { it.target == display }
    if (info == null || info.width <= 0 || info.height <= 0) return 0.462f
    return info.width.toFloat() / info.height
}

private fun scopeLabel(scope: EditScope): Int =
    when (scope) {
        EditScope.HOME -> R.string.surface_home
        EditScope.LOCK -> R.string.surface_lock
        EditScope.BOTH -> R.string.surface_both
    }

private fun surfaceLabel(surface: WallpaperSurface): Int =
    when (surface) {
        WallpaperSurface.HOME -> R.string.surface_home
        WallpaperSurface.LOCK -> R.string.surface_lock
    }

private fun displayTargetLabel(target: DisplayTarget): Int =
    when (target) {
        DisplayTarget.COVER -> R.string.display_front
        DisplayTarget.INNER -> R.string.display_inner
    }

private fun fitModeLabel(mode: FitMode): Int =
    when (mode) {
        FitMode.FILL -> R.string.fit_fill
        FitMode.FIT -> R.string.fit_fit
        FitMode.BACKGROUND_FILL -> R.string.fit_background
        FitMode.STRETCH -> R.string.fit_stretch
    }

private fun scrollModeLabel(mode: ScrollMode): Int =
    when (mode) {
        ScrollMode.OFF -> R.string.scroll_off
        ScrollMode.AMOUNT -> R.string.scroll_amount
        ScrollMode.PAGES -> R.string.scroll_pages
        ScrollMode.CUSTOM -> R.string.scroll_custom
    }

private val PALETTE =
    listOf(
        0xFF000000.toInt(),
        0xFF1B1B1F.toInt(),
        0xFF381E72.toInt(),
        0xFF6750A4.toInt(),
        0xFF2E5E4E.toInt(),
        0xFF7A4E2D.toInt(),
        0xFF8C1D18.toInt(),
        0xFFB3261E.toInt(),
    )

private const val STRETCH_MIN = 0.1f
private const val STRETCH_MAX = 3f
