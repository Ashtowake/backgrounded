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
import dev.backgrounded.domain.model.ScheduleType
import dev.backgrounded.domain.model.SlideMode
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.model.WallpaperSurface
import dev.backgrounded.domain.render.BackgroundRenderer
import dev.backgrounded.domain.render.BitmapLoader
import dev.backgrounded.domain.render.ScrollGeometry
import dev.backgrounded.domain.render.SlideMotion
import dev.backgrounded.domain.state.WallpaperBus
import dev.backgrounded.domain.unlock.UnlockPolicyEvaluator
import dev.backgrounded.domain.usecase.ApplyNextBackground
import dev.backgrounded.domain.usecase.ApplyPreviousBackground
import dev.backgrounded.domain.usecase.NextAlbum
import dev.backgrounded.domain.usecase.TogglePause
import dev.backgrounded.schedule.ChangeScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

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
                mainHandler.post { engines.forEach { engine -> engine.onSettingsChanged() } }
            }
        }
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
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
                GestureAction.NEXT_ALBUM -> {
                    if (nextAlbum(Trigger.GESTURE) == dev.backgrounded.domain.usecase.NextAlbumResult.AUTH_REQUIRED) {
                        runCatching {
                            startActivity(
                                dev.backgrounded.core.security.HiddenSwitchAuthActivity.intent(
                                    this@BackgroundedWallpaperService,
                                    Trigger.GESTURE,
                                ),
                            )
                        }
                    }
                }
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
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> {
                        mainHandler.post { engines.forEach { it.invalidate() } }
                        applicationScope.launch { handleUnlock() }
                    }
                    Intent.ACTION_SCREEN_OFF -> mainHandler.post { engines.forEach { it.invalidate() } }
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
        private var lastMotionKey: Triple<Int, Int, Int>? = null
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
        private var rendering = false
        private var visible = false
        private val slideTick =
            object : Runnable {
                override fun run() {
                    if (!visible || layer?.slide?.mode?.let { it != SlideMode.OFF } != true) return
                    draw()
                    mainHandler.postDelayed(this, slideFrameDelay())
                }
            }

        init {
            setOffsetNotificationsEnabled(true)
            applyTouchMode()
        }

        fun onSettingsChanged() {
            applyTouchMode()
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
            if (!isPreview) applicationScope.launch { settingsStore.completeWallpaperSetup() }
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
            val newTarget = displayRepository.targetForSurface(width, height)
            if (displayTarget != newTarget || layerKey?.width != width || layerKey?.height != height) {
                releaseLayers()
            }
            displayTarget = newTarget
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
                lastTranslation = -1
                lastMotionKey = null
                draw()
                dirty = true
                loadIfNeeded()
            }
            updateSlideTicker()
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

        @Suppress("CyclomaticComplexMethod")
        private fun loadIfNeeded() {
            if (!dirty || surfaceWidth <= 0 || surfaceHeight <= 0) return
            if (rendering) return
            rendering = true
            dirty = false
            val width = surfaceWidth
            val height = surfaceHeight
            val target = displayTarget
            applicationScope.launch {
                val pair = pairCache
                val wallpaper = wallpaperBus.state.value
                val album = pair?.albumId?.let { albumRepository.getAlbum(it) }
                val surface = resolveSurface()
                val asset = pair?.imageFor(surface)
                val framing = asset?.framingFor(target, surface)
                val scroll =
                    framing?.let { ScrollGeometry.scrollFor(it, width) }
                        ?: ScrollGeometry.Scroll(0, 0f, 1f)
                val decodeWidth = (width + scroll.slackPixels).coerceAtMost(DECODE_MAX_DIMENSION)
                val decodeHeight = height.coerceAtMost(DECODE_MAX_DIMENSION)
                val source = asset?.let { bitmapLoader.decode(it, decodeWidth, decodeHeight) }
                val rotated = prepareRotated(source, framing)
                mainHandler.post {
                    rendering = false
                    if (renderIsStale(width, height, target, surface)) {
                        recyclePrepared(source, rotated)
                        dirty = true
                        loadIfNeeded()
                        return@post
                    }
                    if (asset == null || framing == null || source == null) {
                        recyclePrepared(source, rotated)
                        return@post
                    }
                    val dim = asset.dimForLock && surface == WallpaperSurface.LOCK
                    val scrolls = surface == WallpaperSurface.HOME
                    val key = LayerKey(asset.storageRef, framing, scrolls, dim, width, height)
                    val slideMode =
                        if (album?.scheduleType == ScheduleType.INTERVAL) album.slideMode else SlideMode.OFF
                    val slideSpeed = album?.slideSpeedPxPerSecond ?: DEFAULT_SLIDE_SPEED
                    val crossfadeEnabled = album?.crossfadeEnabled ?: true
                    val crossfadeDurationMs = album?.crossfadeDurationMs ?: 800
                    if (key == layerKey) {
                        recyclePrepared(source, rotated)
                        layer?.apply {
                            slide = SlideSettings(slideMode, slideSpeed, wallpaper.changedAt)
                            this.crossfadeDurationMs = crossfadeDurationMs
                        }
                        if (!crossfadeEnabled) finishFade()
                        lastTranslation = -1
                        lastMotionKey = null
                        updateSlideTicker()
                        draw()
                        return@post
                    }
                    layerKey = key
                    val newLayer =
                        Layer(
                            source = source,
                            rotated = rotated ?: source,
                            framing = framing,
                            scroll = scroll,
                            scrolls = scrolls,
                            dim = dim,
                            slide = SlideSettings(slideMode, slideSpeed, wallpaper.changedAt),
                        )
                    newLayer.crossfadeDurationMs = crossfadeDurationMs
                    val old = layer
                    lastTranslation = -1
                    lastFadeStep = -1
                    lastMotionKey = null
                    if (crossfadeEnabled && old != null && visible) {
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
                    updateSlideTicker()
                    draw()
                    updateGyroSubscription()
                    if (dirty) loadIfNeeded()
                }
            }
        }

        private fun prepareRotated(
            source: Bitmap?,
            framing: Framing?,
        ): Bitmap? {
            if (source == null || framing == null) return null
            return backgroundRenderer.rotated(source, framing.rotationDegrees).also {
                backgroundRenderer.prepareBackdrop(it, framing)
            }
        }

        private fun recyclePrepared(
            source: Bitmap?,
            rotated: Bitmap?,
        ) {
            if (rotated !== source) rotated?.recycle()
            source?.recycle()
        }

        private fun renderIsStale(
            width: Int,
            height: Int,
            target: DisplayTarget,
            surface: WallpaperSurface,
        ): Boolean {
            if (dirty || width != surfaceWidth || height != surfaceHeight) return true
            return target != displayTarget || surface != resolveSurface()
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
            val interrupted = fadeAnimator
            fadeAnimator = null
            interrupted?.cancel()
            fade = 0f
            val animator = ValueAnimator.ofFloat(0f, 1f)
            fadeAnimator = animator
            animator.apply {
                duration = (layer?.crossfadeDurationMs ?: 800).toLong().coerceIn(MIN_FADE_MILLIS, MAX_FADE_MILLIS)
                addUpdateListener { animator ->
                    fade = animator.animatedValue as Float
                    draw()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (fadeAnimator !== animation) {
                                return
                            }
                            fadeAnimator = null
                            previousLayer?.recycle()
                            previousLayer = null
                            fade = 1f
                            lastFadeStep = -1
                            draw()
                        }
                    },
                )
                start()
            }
        }

        @Suppress("CyclomaticComplexMethod", "ComplexCondition")
        private fun draw() {
            val current = layer
            if (current == null || !visible) return
            val now = System.currentTimeMillis()
            val motion = motionFor(current, now)
            val motionKey =
                motion?.let {
                    Triple(
                        (it.offsetX * 2f).roundToInt(),
                        (it.offsetY * 2f).roundToInt(),
                        (it.zoom * 1000f).roundToInt(),
                    )
                }
            val translation = ScrollGeometry.translationPixels(current.scroll, xOffset, current.scrolls)
            val fading = previousLayer != null && fade < 1f
            val fadeStep = if (fading) (fade * FADE_STEPS).toInt() else FADE_COMPLETE
            val gyroKeyX = if (current.framing.gyroParallax) (gyroX * GYRO_KEY_STEPS).toInt() else 0
            val gyroKeyY = if (current.framing.gyroParallax) (gyroY * GYRO_KEY_STEPS).toInt() else 0
            if (translation == lastTranslation &&
                fadeStep == lastFadeStep &&
                motionKey == lastMotionKey &&
                gyroKeyX == lastGyroKeyX &&
                gyroKeyY == lastGyroKeyY
            ) {
                return
            }
            val holder = surfaceHolder
            val surface = holder?.surface
            if (holder == null || surface == null) return
            val hardwareCanvas = runCatching { surface.lockHardwareCanvas() }.getOrNull()
            val canvas = hardwareCanvas ?: runCatching { holder.lockCanvas() }.getOrNull()
            if (canvas == null) return
            lastTranslation = translation
            lastFadeStep = fadeStep
            lastMotionKey = motionKey
            lastGyroKeyX = gyroKeyX
            lastGyroKeyY = gyroKeyY
            try {
                canvas.drawColor(Color.BLACK)
                val previous = previousLayer
                if (previous != null && fade < 1f) {
                    drawLayer(canvas, previous, 1f, motionFor(previous, now))
                    drawLayer(canvas, current, fade, motion)
                } else {
                    drawLayer(canvas, current, 1f, motion)
                }
            } finally {
                if (hardwareCanvas != null) {
                    runCatching { surface.unlockCanvasAndPost(hardwareCanvas) }
                } else {
                    runCatching { holder.unlockCanvasAndPost(canvas) }
                }
            }
        }

        private fun finishFade() {
            fadeAnimator?.cancel()
            fadeAnimator = null
            previousLayer?.recycle()
            previousLayer = null
            fade = 1f
            lastFadeStep = -1
        }

        private fun motionFor(
            layer: Layer,
            now: Long,
        ): SlideMotion.Position? =
            SlideMotion.position(
                mode = layer.slide.mode,
                speedPxPerSecond = layer.slide.speedPxPerSecond,
                elapsedMillis = now - layer.slide.startedAtMillis,
                width = surfaceWidth,
                height = surfaceHeight,
            )

        private fun slideFrameDelay(): Long =
            (500f / (layer?.slide?.speedPxPerSecond ?: DEFAULT_SLIDE_SPEED)).toLong().coerceIn(16L, 1_000L)

        private fun updateSlideTicker() {
            mainHandler.removeCallbacks(slideTick)
            if (visible && layer?.slide?.mode?.let { it != SlideMode.OFF } == true) {
                mainHandler.postDelayed(slideTick, slideFrameDelay())
            }
        }

        private fun drawLayer(
            canvas: Canvas,
            layer: Layer,
            alpha: Float,
            slide: SlideMotion.Position?,
        ) {
            if (alpha <= 0f || surfaceWidth <= 0 || surfaceHeight <= 0) return
            if (alpha < 1f) {
                canvas.saveLayerAlpha(
                    0f,
                    0f,
                    surfaceWidth.toFloat(),
                    surfaceHeight.toFloat(),
                    (alpha * 255).toInt().coerceIn(0, 255),
                )
            }
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
                        alpha = 255,
                        allowScroll = layer.scrolls,
                        gyroShiftX = if (parallax) gyroX * maxShift else 0f,
                        gyroShiftY = if (parallax) gyroY * maxShift else 0f,
                        gyroMargin = if (parallax) maxShift else 0f,
                        slide = slide,
                    ),
            )
            if (alpha < 1f) canvas.restore()
        }

        private fun releaseLayers() {
            mainHandler.removeCallbacks(slideTick)
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
            lastMotionKey = null
        }
    }

    private class Layer(
        val source: Bitmap,
        val rotated: Bitmap,
        val framing: Framing,
        val scroll: ScrollGeometry.Scroll,
        val scrolls: Boolean,
        val dim: Boolean,
        var slide: SlideSettings,
    ) {
        var crossfadeDurationMs: Int = 800

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

    private data class SlideSettings(
        val mode: SlideMode,
        val speedPxPerSecond: Float,
        val startedAtMillis: Long,
    )

    private companion object {
        const val MIN_FADE_MILLIS = 100L
        const val DEFAULT_SLIDE_SPEED = 10f
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
