package dev.backgrounded.wallpaper

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.backgrounded.core.di.ApplicationScope
import dev.backgrounded.core.display.DisplayRepository
import dev.backgrounded.data.datastore.Settings
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.gesture.DoubleTapDetector
import dev.backgrounded.domain.model.BackgroundPair
import dev.backgrounded.domain.model.CurrentWallpaper
import dev.backgrounded.domain.model.DisplayTarget
import dev.backgrounded.domain.model.DoubleTapMode
import dev.backgrounded.domain.model.Framing
import dev.backgrounded.domain.model.GestureAction
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.model.WallpaperSurface
import dev.backgrounded.domain.render.BackgroundRenderer
import dev.backgrounded.domain.render.BitmapLoader
import dev.backgrounded.domain.render.ScrollGeometry
import dev.backgrounded.domain.state.WallpaperBus
import dev.backgrounded.domain.unlock.UnlockPolicyEvaluator
import dev.backgrounded.domain.usecase.ApplyNextBackground
import dev.backgrounded.domain.usecase.ApplyPreviousBackground
import dev.backgrounded.domain.usecase.NextAlbum
import dev.backgrounded.domain.usecase.TogglePause
import dev.backgrounded.schedule.ChangeScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min

@AndroidEntryPoint
class BackgroundedWallpaperService : WallpaperService() {
    @Inject
    lateinit var wallpaperBus: WallpaperBus

    @Inject
    lateinit var albumRepository: AlbumRepository

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var bitmapLoader: BitmapLoader

    @Inject
    lateinit var backgroundRenderer: BackgroundRenderer

    @Inject
    lateinit var displayRepository: DisplayRepository

    @Inject
    lateinit var changeScheduler: ChangeScheduler

    @Inject
    lateinit var applyNextBackground: ApplyNextBackground

    @Inject
    lateinit var applyPreviousBackground: ApplyPreviousBackground

    @Inject
    lateinit var nextAlbum: NextAlbum

    @Inject
    lateinit var togglePause: TogglePause

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    private val mainHandler = Handler(Looper.getMainLooper())
    private val engines = mutableSetOf<BackgroundedEngine>()
    private val tapDetector = DoubleTapDetector()
    private val unlockMutex = Mutex()
    private var receiverRegistered = false

    @Volatile
    private var settingsCache: Settings = Settings.DEFAULTS

    @Volatile
    private var pairCache: BackgroundPair? = null

    private val keyguardManager: KeyguardManager? by lazy {
        getSystemService(KeyguardManager::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            val settings = settingsStore.settings.first()
            wallpaperBus.set(
                CurrentWallpaper(
                    pairId = settings.currentPairId,
                    albumId = settings.activeAlbumId,
                    changedAt = settings.currentChangedAt,
                ),
            )
        }
        applicationScope.launch {
            wallpaperBus.state.collectLatest { wallpaper ->
                val pairId = wallpaper.pairId
                if (pairId == null) {
                    pairCache = null
                    mainHandler.post { engines.forEach { engine -> engine.invalidate() } }
                    return@collectLatest
                }
                albumRepository.observeResolvedPair(pairId).collect { pair ->
                    pairCache = pair
                    mainHandler.post { engines.forEach { engine -> engine.invalidate() } }
                }
            }
        }
        applicationScope.launch {
            settingsStore.settings.collect { settings ->
                settingsCache = settings
                mainHandler.post { engines.forEach { engine -> engine.onSettingsChanged(settings) } }
            }
        }
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        applicationScope.launch { changeScheduler.rearm() }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(unlockReceiver)
            receiverRegistered = false
        }
        engines.clear()
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine {
        val engine = BackgroundedEngine()
        engines.add(engine)
        return engine
    }

    private fun dispatchGestureAction() {
        if (!settingsCache.doubleTapEnabled) return
        applicationScope.launch {
            when (settingsCache.doubleTapAction) {
                GestureAction.NEXT -> applyNextBackground(Trigger.GESTURE)
                GestureAction.PREVIOUS -> applyPreviousBackground(Trigger.GESTURE)
                GestureAction.NEXT_ALBUM -> nextAlbum(Trigger.GESTURE)
                GestureAction.TOGGLE_PAUSE -> togglePause()
                GestureAction.OPEN_APP ->
                    startActivity(
                        Intent(Intent.ACTION_MAIN)
                            .setClassName(packageName, "dev.backgrounded.MainActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
            }
        }
    }

    private val unlockReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    applicationScope.launch { handleUnlock() }
                }
            }
        }

    private suspend fun handleUnlock() =
        unlockMutex.withLock {
            val settings = settingsStore.settings.first()
            val albumId = settings.activeAlbumId ?: return@withLock
            val album = albumRepository.getAlbum(albumId) ?: return@withLock
            val now = System.currentTimeMillis()
            val epochDay = LocalDate.now().toEpochDay()
            val shouldChange =
                UnlockPolicyEvaluator.shouldChange(
                    policy = album.unlockPolicy,
                    state = settings.unlockState,
                    nowMillis = now,
                    epochDay = epochDay,
                )
            val afterUnlock = UnlockPolicyEvaluator.stateAfterUnlock(settings.unlockState, epochDay)
            settingsStore.setUnlockState(afterUnlock)
            if (shouldChange && applyNextBackground(Trigger.UNLOCK)) {
                settingsStore.setUnlockState(UnlockPolicyEvaluator.stateAfterApply(afterUnlock, now, epochDay))
            }
        }

    inner class BackgroundedEngine : Engine() {
        private val gestureDetector =
            GestureDetector(
                this@BackgroundedWallpaperService,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(event: MotionEvent): Boolean {
                        dispatchGestureAction()
                        return true
                    }
                },
            )
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var displayTarget = DisplayTarget.INNER
        private var xOffset = 0.5f
        private var layer: Layer? = null
        private var previousLayer: Layer? = null
        private var layerKey: LayerKey? = null
        private var fade = 1f
        private var lastTranslation = -1
        private var lastFadeStep = -1
        private var lastGyroKeyX = 0
        private var lastGyroKeyY = 0
        private var gyroRegistered = false
        private var gyroX = 0f
        private var gyroY = 0f
        private var smoothX = 0f
        private var smoothY = 0f
        private val sensorManager = getSystemService(SensorManager::class.java)
        private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        private val gyroListener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    smoothX += GYRO_FILTER_ALPHA * (event.values[0] - smoothX)
                    smoothY += GYRO_FILTER_ALPHA * (event.values[1] - smoothY)
                    val normalizedX = (-smoothX / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                    val normalizedY =
                        ((smoothY - SensorManager.GRAVITY_EARTH) / SensorManager.GRAVITY_EARTH)
                            .coerceIn(-1f, 1f)
                    if (abs(normalizedX - gyroX) > GYRO_MIN_DELTA || abs(normalizedY - gyroY) > GYRO_MIN_DELTA) {
                        gyroX = normalizedX
                        gyroY = normalizedY
                        draw()
                    }
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) = Unit
            }
        private var fadeAnimator: ValueAnimator? = null
        private var dirty = true
        private var renderJob: Job? = null
        private var visible = false

        init {
            setOffsetNotificationsEnabled(true)
            applyTouchMode()
        }

        fun onSettingsChanged(settings: Settings) {
            applyTouchMode()
            if (!settings.crossfadeEnabled) {
                fadeAnimator?.cancel()
                previousLayer?.recycle()
                previousLayer = null
                fade = 1f
            }
            updateGyroSubscription()
        }

        private fun updateGyroSubscription() {
            val manager = sensorManager ?: return
            val sensor = accelerometer ?: return
            val enabled = visible && layer?.framing?.gyroParallax == true
            if (enabled && !gyroRegistered) {
                gyroRegistered = manager.registerListener(gyroListener, sensor, SensorManager.SENSOR_DELAY_UI)
            } else if (!enabled && gyroRegistered) {
                manager.unregisterListener(gyroListener)
                gyroRegistered = false
                gyroX = 0f
                gyroY = 0f
            }
        }

        fun invalidate() {
            dirty = true
            loadIfNeeded()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            loadIfNeeded()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            if (!isPreview) displayRepository.rememberSurfaceSize(width, height)
            displayTarget = displayRepository.targetForSurface(width, height)
            dirty = true
            loadIfNeeded()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            releaseLayers()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                dirty = true
                loadIfNeeded()
            }
            updateGyroSubscription()
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            this.xOffset = xOffset
            draw()
        }

        override fun onZoomChanged(zoom: Float) {
            draw()
        }

        override fun onWallpaperFlagsChanged(which: Int) {
            dirty = true
            loadIfNeeded()
        }

        override fun onCommand(
            action: String,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean,
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP &&
                settingsCache.doubleTapEnabled &&
                settingsCache.doubleTapMode == DoubleTapMode.BACKGROUND
            ) {
                if (tapDetector.onTap(SystemClock.elapsedRealtime())) dispatchGestureAction()
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (settingsCache.doubleTapEnabled && settingsCache.doubleTapMode == DoubleTapMode.ANYWHERE) {
                gestureDetector.onTouchEvent(event)
            }
        }

        override fun onDestroy() {
            releaseLayers()
            super.onDestroy()
        }

        private fun applyTouchMode() {
            setTouchEventsEnabled(
                settingsCache.doubleTapEnabled && settingsCache.doubleTapMode == DoubleTapMode.ANYWHERE,
            )
        }

        private fun loadIfNeeded() {
            if (!dirty || surfaceWidth <= 0 || surfaceHeight <= 0) return
            if (renderJob?.isActive == true) return
            dirty = false
            val width = surfaceWidth
            val height = surfaceHeight
            val target = displayTarget
            renderJob =
                applicationScope.launch {
                    val pair = pairCache
                    val surface = resolveSurface()
                    val asset = pair?.imageFor(surface)
                    val framing = asset?.framingFor(target, surface)
                    val scroll =
                        framing?.let { ScrollGeometry.scrollFor(it, width) }
                            ?: ScrollGeometry.Scroll(0, 0f, 1f)
                    val decodeWidth = (width + scroll.slackPixels).coerceAtMost(DECODE_MAX_DIMENSION)
                    val decodeHeight = height.coerceAtMost(DECODE_MAX_DIMENSION)
                    val source = asset?.let { bitmapLoader.decode(it, decodeWidth, decodeHeight) }
                    mainHandler.post {
                        if (asset == null || framing == null || source == null) {
                            dirty = false
                            return@post
                        }
                        val dim = asset.dimForLock && surface == WallpaperSurface.LOCK
                        val scrolls = surface == WallpaperSurface.HOME
                        val key = LayerKey(asset.storageRef, framing, scrolls, dim, width, height)
                        if (key == layerKey) {
                            source.recycle()
                            dirty = false
                            return@post
                        }
                        layerKey = key
                        val rotated = backgroundRenderer.rotated(source, framing.rotationDegrees)
                        val newLayer =
                            Layer(
                                source = source,
                                rotated = rotated,
                                framing = framing,
                                scroll = scroll,
                                scrolls = scrolls,
                                dim = dim,
                            )
                        val old = layer
                        lastTranslation = -1
                        lastFadeStep = -1
                        if (settingsCache.crossfadeEnabled && old != null && visible) {
                            previousLayer?.recycle()
                            previousLayer = old
                            layer = newLayer
                            startFade()
                        } else {
                            old?.recycle()
                            previousLayer?.recycle()
                            previousLayer = null
                            layer = newLayer
                            fade = 1f
                        }
                        draw()
                        updateGyroSubscription()
                    }
                }
        }

        private fun resolveSurface(): WallpaperSurface {
            val locked = keyguardManager?.isKeyguardLocked == true
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    runCatching { getWallpaperFlags() }.getOrDefault(0)
                } else {
                    0
                }
            val hasSystem = flags and WallpaperManager.FLAG_SYSTEM != 0
            val hasLock = flags and WallpaperManager.FLAG_LOCK != 0
            return when {
                hasLock && !hasSystem -> WallpaperSurface.LOCK
                !hasLock && hasSystem -> WallpaperSurface.HOME
                else -> if (locked) WallpaperSurface.LOCK else WallpaperSurface.HOME
            }
        }

        private fun startFade() {
            fadeAnimator?.cancel()
            fade = 0f
            fadeAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = settingsCache.crossfadeDurationMs.toLong().coerceIn(MIN_FADE_MILLIS, MAX_FADE_MILLIS)
                    addUpdateListener { animator ->
                        fade = animator.animatedValue as Float
                        draw()
                    }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                previousLayer?.recycle()
                                previousLayer = null
                                fade = 1f
                                lastFadeStep = FADE_COMPLETE
                                draw()
                            }
                        },
                    )
                    start()
                }
        }

        private fun draw() {
            val current = layer
            if (current == null || !visible) return
            val translation = ScrollGeometry.translationPixels(current.scroll, xOffset, current.scrolls)
            val fading = previousLayer != null && fade < 1f
            val fadeStep = if (fading) (fade * FADE_STEPS).toInt() else FADE_COMPLETE
            val gyroKeyX = if (current.framing.gyroParallax) (gyroX * GYRO_KEY_STEPS).toInt() else 0
            val gyroKeyY = if (current.framing.gyroParallax) (gyroY * GYRO_KEY_STEPS).toInt() else 0
            if (translation == lastTranslation &&
                fadeStep == lastFadeStep &&
                gyroKeyX == lastGyroKeyX &&
                gyroKeyY == lastGyroKeyY
            ) {
                return
            }
            lastTranslation = translation
            lastFadeStep = fadeStep
            lastGyroKeyX = gyroKeyX
            lastGyroKeyY = gyroKeyY

            val holder = surfaceHolder
            val surface = holder?.surface
            if (holder == null || surface == null) return
            val hardwareCanvas = runCatching { surface.lockHardwareCanvas() }.getOrNull()
            val canvas = hardwareCanvas ?: runCatching { holder.lockCanvas() }.getOrNull()
            if (canvas == null) return
            try {
                canvas.drawColor(Color.BLACK)
                val previous = previousLayer
                if (previous != null && fade < 1f) {
                    drawLayer(canvas, previous, 1f - fade)
                    drawLayer(canvas, current, fade)
                } else {
                    drawLayer(canvas, current, 1f)
                }
            } finally {
                if (hardwareCanvas != null) {
                    runCatching { surface.unlockCanvasAndPost(hardwareCanvas) }
                } else {
                    runCatching { holder.unlockCanvasAndPost(canvas) }
                }
            }
        }

        private fun drawLayer(
            canvas: Canvas,
            layer: Layer,
            alpha: Float,
        ) {
            if (alpha <= 0f || surfaceWidth <= 0 || surfaceHeight <= 0) return
            val intensity = layer.framing.gyroIntensity.coerceIn(0, 100) / 100f
            val maxShift = intensity * GYRO_MAX_FRACTION * min(surfaceWidth, surfaceHeight)
            val parallax = layer.framing.gyroParallax
            backgroundRenderer.draw(
                canvas = canvas,
                rotatedSource = layer.rotated,
                viewport = BackgroundRenderer.Viewport(surfaceWidth, surfaceHeight),
                frame =
                    BackgroundRenderer.Frame(
                        framing = layer.framing,
                        scroll = layer.scroll,
                        scrollOffset = xOffset,
                        dim = layer.dim,
                        alpha = (alpha * 255).toInt().coerceIn(0, 255),
                        allowScroll = layer.scrolls,
                        gyroShiftX = if (parallax) gyroX * maxShift else 0f,
                        gyroShiftY = if (parallax) gyroY * maxShift else 0f,
                        gyroMargin = if (parallax) maxShift else 0f,
                    ),
            )
        }

        private fun releaseLayers() {
            fadeAnimator?.cancel()
            fadeAnimator = null
            if (gyroRegistered) {
                sensorManager?.unregisterListener(gyroListener)
                gyroRegistered = false
            }
            layerKey = null
            layer?.recycle()
            layer = null
            previousLayer?.recycle()
            previousLayer = null
        }
    }

    private class Layer(
        val source: Bitmap,
        val rotated: Bitmap,
        val framing: Framing,
        val scroll: ScrollGeometry.Scroll,
        val scrolls: Boolean,
        val dim: Boolean,
    ) {
        fun recycle() {
            if (rotated !== source) rotated.recycle()
            source.recycle()
        }
    }

    private data class LayerKey(
        val reference: String,
        val framing: Framing,
        val scrolls: Boolean,
        val dim: Boolean,
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val MIN_FADE_MILLIS = 100L
        const val MAX_FADE_MILLIS = 3000L
        const val FADE_STEPS = 60f
        const val FADE_COMPLETE = -1
        const val DECODE_MAX_DIMENSION = 2560
        const val GYRO_FILTER_ALPHA = 0.15f
        const val GYRO_MIN_DELTA = 0.003f
        const val GYRO_KEY_STEPS = 64f
        const val GYRO_MAX_FRACTION = 0.2f
    }
}
