package dev.backgrounded.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
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
import dev.backgrounded.domain.render.GestureTransform
import dev.backgrounded.ui.components.SectionTitle
import kotlin.math.hypot
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(onBack: () -> Unit) {
    val viewModel: EditorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.orientation) { viewModel.refreshDisplayTargets() }
    val pair = state.pair ?: return
    val expandedKey = state.expandedKey
    val keys = orderedKeys(state)
    val pagerState = rememberPagerState(pageCount = { keys.size })

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
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (maxWidth >= 840.dp) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        keys.forEach { key ->
                            PreviewPage(
                                state = state,
                                key = key,
                                onSelect = viewModel::expand,
                                onSizeChanged = viewModel::setViewport,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        PreviewPage(
                            state = state,
                            key = keys[page],
                            onSelect = viewModel::expand,
                            onSizeChanged = viewModel::setViewport,
                        )
                    }
                }
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
    modifier: Modifier = Modifier,
) {
    val aspect = aspectOf(state, key.display)
    Column(
        modifier =
            modifier
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
    var showControls by remember(key) { mutableStateOf(false) }
    var controlSection by remember(key) { mutableStateOf(ControlSection.PLACEMENT) }
    var dockOffset by remember(key) { mutableStateOf(0f) }
    var angleSnap by remember { mutableStateOf(true) }
    var showGrid by remember { mutableStateOf(false) }
    val angleSnapState = rememberUpdatedState(angleSnap)

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
                            val minFingerDistance = MIN_FINGER_DISTANCE.toPx()
                            var lastTapAt = 0L
                            awaitEachGesture {
                                val firstDown = awaitFirstDown(requireUnconsumed = false)
                                val primaryId = firstDown.id
                                val primaryDownPosition = firstDown.position
                                val downAt = System.currentTimeMillis()
                                var primaryMoved = false
                                var startPair: GestureTransform.FingerPair? = null
                                var previousPair: GestureTransform.FingerPair? = null
                                var singlePointerPosition: Offset? = null

                                fun fingerPair(
                                    vector: Offset,
                                    centroid: Offset,
                                ): GestureTransform.FingerPair =
                                    GestureTransform.FingerPair(
                                        centroidX = centroid.x,
                                        centroidY = centroid.y,
                                        vectorX = vector.x,
                                        vectorY = vector.y,
                                    )

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    event.changes.firstOrNull { it.id == primaryId }?.let { primary ->
                                        if ((primary.position - primaryDownPosition).getDistance() >
                                            viewConfiguration.touchSlop
                                        ) {
                                            primaryMoved = true
                                        }
                                    }
                                    if (pressed.isEmpty()) {
                                        val endedAt = System.currentTimeMillis()
                                        viewModel.endGesture(angleSnapState.value)
                                        if (!primaryMoved && endedAt - downAt <= TAP_MAX_MILLIS) {
                                            val isDoubleTap =
                                                endedAt - lastTapAt <= DOUBLE_TAP_WINDOW_MILLIS
                                            lastTapAt = if (isDoubleTap) 0L else endedAt
                                            if (isDoubleTap) viewModel.align()
                                        }
                                        break
                                    } else if (pressed.size >= 2) {
                                        singlePointerPosition = null
                                        val first = pressed[0].position
                                        val second = pressed[1].position
                                        val vector = second - first
                                        val centroid = (first + second) / 2f
                                        val length = vector.getDistance()
                                        if (length >= minFingerDistance) {
                                            val currentPair = fingerPair(vector, centroid)
                                            val start = startPair
                                            if (start == null) {
                                                startPair = currentPair
                                                if (!adjustBackdrop) viewModel.beginGesture()
                                            } else if (adjustBackdrop) {
                                                val previous = previousPair
                                                if (previous != null) {
                                                    viewModel.transformBackdrop(
                                                        length / hypot(previous.vectorX, previous.vectorY),
                                                        (centroid.x - previous.centroidX) / size.width,
                                                        (centroid.y - previous.centroidY) / size.height,
                                                    )
                                                }
                                            } else {
                                                viewModel.updateGesture(
                                                    start = start,
                                                    current = currentPair,
                                                    frameWidth = size.width.toFloat(),
                                                    frameHeight = size.height.toFloat(),
                                                )
                                            }
                                            previousPair = currentPair
                                        } else {
                                            startPair = null
                                            previousPair = null
                                        }
                                    } else {
                                        val position = pressed.first().position
                                        val previousPosition = singlePointerPosition
                                        singlePointerPosition = position
                                        if (previousPosition != null &&
                                            size.width > 0 &&
                                            size.height > 0
                                        ) {
                                            val deltaX = (position.x - previousPosition.x) / size.width
                                            val deltaY = (position.y - previousPosition.y) / size.height
                                            if (adjustBackdrop) {
                                                viewModel.transformBackdrop(1f, deltaX, deltaY)
                                            } else {
                                                viewModel.panBy(deltaX, deltaY)
                                            }
                                        }
                                        startPair = null
                                        previousPair = null
                                    }
                                }
                            }
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
                if (showGrid) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val color = Color.White.copy(alpha = 0.35f)
                        val stroke = 1.5.dp.toPx()
                        for (fraction in listOf(1f / 3f, 2f / 3f)) {
                            drawLine(
                                color = color,
                                start = Offset(size.width * fraction, 0f),
                                end = Offset(size.width * fraction, size.height),
                                strokeWidth = stroke,
                            )
                            drawLine(
                                color = color,
                                start = Offset(0f, size.height * fraction),
                                end = Offset(size.width, size.height * fraction),
                                strokeWidth = stroke,
                            )
                        }
                    }
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

        if (showControls) {
            Surface(
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .padding(8.dp)
                        .offset { IntOffset(0, dockOffset.roundToInt()) }
                        .widthIn(max = 380.dp)
                        .fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                tonalElevation = 2.dp,
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "━━",
                            modifier =
                                Modifier.weight(1f).pointerInput(key) {
                                    detectDragGestures { change, drag ->
                                        dockOffset = (dockOffset + drag.y).coerceIn(-800.dp.toPx(), 0f)
                                        change.consume()
                                    }
                                },
                        )
                        OutlinedButton(onClick = viewModel::syncToCounterpart) {
                            Text(stringResource(R.string.sync))
                        }
                        TextButton(onClick = viewModel::reset) { Text(stringResource(R.string.reset)) }
                        IconButton(onClick = { showControls = false }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = angleSnap, onCheckedChange = { angleSnap = it })
                        Text(
                            stringResource(R.string.angle_snap),
                            modifier = Modifier.padding(start = 4.dp, end = 12.dp),
                        )
                        Switch(checked = showGrid, onCheckedChange = { showGrid = it })
                        Text(stringResource(R.string.grid), modifier = Modifier.padding(start = 4.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ControlSection.entries.filter {
                            it != ControlSection.SCROLL || key.surface == WallpaperSurface.HOME
                        }.forEach { section ->
                            TextButton(
                                onClick = { controlSection = section },
                                colors =
                                    androidx.compose.material3.ButtonDefaults.textButtonColors(
                                        containerColor =
                                            if (controlSection == section) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                Color.Transparent
                                            },
                                    ),
                            ) { Text(section.label) }
                        }
                    }
                    Column(modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        FramingControls(
                            section = controlSection,
                            framing = framing,
                            sourceWidth = asset.width,
                            sourceHeight = asset.height,
                            viewportAspect = aspect,
                            dimForLock = asset.dimForLock,
                            surface = key.surface,
                            scrollPreviews =
                                ScrollPreviews(
                                    state.scrollStartPreview,
                                    state.scrollEndPreview,
                                    aspectOf(state, key.display),
                                ),
                            adjustBackdrop = adjustBackdrop,
                            onAdjustBackdrop = { adjustBackdrop = it },
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }
}

private enum class ControlSection(val label: String) {
    PLACEMENT("Placement"),
    SCROLL("Scroll"),
    EFFECTS("Effects"),
}

@Composable
private fun FramingControls(
    section: ControlSection,
    framing: Framing,
    sourceWidth: Int,
    sourceHeight: Int,
    viewportAspect: Float,
    dimForLock: Boolean,
    surface: WallpaperSurface,
    scrollPreviews: ScrollPreviews,
    adjustBackdrop: Boolean,
    onAdjustBackdrop: (Boolean) -> Unit,
    viewModel: EditorViewModel,
) {
    if (section == ControlSection.PLACEMENT) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(FitMode.FILL, FitMode.FIT, FitMode.STRETCH).forEach { mode ->
                FilterChip(
                    selected = framing.fitMode == mode,
                    onClick = { viewModel.setFitMode(mode) },
                    label = { Text(stringResource(fitModeLabel(mode))) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = framing.mirrorX,
                onClick = { viewModel.setMirrorX(!framing.mirrorX) },
                label = { Text("Mirror horizontally") },
            )
            FilterChip(
                selected = framing.mirrorY,
                onClick = { viewModel.setMirrorY(!framing.mirrorY) },
                label = { Text("Mirror vertically") },
            )
        }

        if (framing.fitMode == FitMode.STRETCH) {
            val sourceAspect =
                sourceWidth.toFloat() * framing.crop.width /
                    (sourceHeight.toFloat() * framing.crop.height).coerceAtLeast(1f)
            val aspect = if (framing.rotationDegrees % 180 == 0) sourceAspect else 1f / sourceAspect
            val naturalX = minOf(1f, aspect / viewportAspect)
            val naturalY = minOf(1f, viewportAspect / aspect)
            Text(
                text = stringResource(R.string.stretch_x) + ": " + (framing.stretchX * naturalX * 100).toInt() + "%",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = framing.stretchX * naturalX,
                onValueChange = { viewModel.setStretchX(it / naturalX) },
                valueRange = (STRETCH_MIN * naturalX)..(STRETCH_MAX * naturalX),
            )
            Text(
                text = stringResource(R.string.stretch_y) + ": " + (framing.stretchY * naturalY * 100).toInt() + "%",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = framing.stretchY * naturalY,
                onValueChange = { viewModel.setStretchY(it / naturalY) },
                valueRange = (STRETCH_MIN * naturalY)..(STRETCH_MAX * naturalY),
            )
        }

        Text(stringResource(R.string.fit_background), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(BackdropType.NONE, BackdropType.BLUR, BackdropType.COLOR).forEach { type ->
                FilterChip(
                    selected = framing.backdrop == type,
                    onClick = { viewModel.setBackdropType(type) },
                    label = {
                        Text(
                            when (type) {
                                BackdropType.NONE -> stringResource(R.string.none)
                                BackdropType.BLUR -> stringResource(R.string.backdrop_blur)
                                BackdropType.COLOR -> stringResource(R.string.backdrop_color)
                            },
                        )
                    },
                )
            }
        }
        if (framing.backdrop != BackdropType.NONE) {
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
    }
    if (section == ControlSection.SCROLL && surface == WallpaperSurface.HOME) {
        ScrollSettings(
            framing = framing,
            previews = scrollPreviews,
            viewModel = viewModel,
        )
    }

    if (section == ControlSection.EFFECTS) {
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

        if (surface == WallpaperSurface.LOCK) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(checked = dimForLock, onCheckedChange = viewModel::setDimForLock)
                Text(text = stringResource(R.string.dim_lock))
            }
        }
    }
}

private data class ScrollPreviews(
    val start: Bitmap?,
    val end: Bitmap?,
    val aspect: Float,
)

@Composable
private fun ScrollSettings(
    framing: Framing,
    previews: ScrollPreviews,
    viewModel: EditorViewModel,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(
            checked = framing.scrollMode != ScrollMode.OFF,
            onCheckedChange = viewModel::setScrollEnabled,
        )
        Text(
            text = stringResource(R.string.scroll_behavior),
            style = MaterialTheme.typography.labelLarge,
        )
    }
    if (framing.scrollMode != ScrollMode.OFF) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageEdge(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.page_first),
                preview = previews.start,
                aspect = previews.aspect,
                value = framing.scrollStartFraction,
                onChange = viewModel::setScrollLeft,
            )
            PageEdge(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.page_last),
                preview = previews.end,
                aspect = previews.aspect,
                value = (framing.scrollStartFraction + framing.scrollSpanFraction).coerceIn(0f, 1f),
                onChange = viewModel::setScrollRight,
            )
        }
    }
}

@Composable
private fun PageEdge(
    label: String,
    preview: Bitmap?,
    aspect: Float,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Box(
            modifier = Modifier.fillMaxWidth().height(PAGE_EDGE_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            val image = preview?.asImageBitmap()
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(PAGE_EDGE_HEIGHT).aspectRatio(aspect),
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..1f,
        )
    }
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
private const val DOUBLE_TAP_WINDOW_MILLIS = 300L
private const val TAP_MAX_MILLIS = 300L
private val MIN_FINGER_DISTANCE = 28.dp
private val PAGE_EDGE_HEIGHT = 110.dp
