package dev.backgrounded.wallpaper

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
import android.view.Choreographer
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
import dev.backgrounded.domain.rotation.RotationResult
import dev.backgrounded.domain.state.WallpaperBus
import dev.backgrounded.domain.unlock.UnlockPolicyEvaluator
import dev.backgrounded.domain.usecase.ApplyNextBackground
import dev.backgrounded.domain.usecase.ApplyPreviousBackground
import dev.backgrounded.domain.usecase.NextAlbum
import dev.backgrounded.domain.usecase.TogglePause
import dev.backgrounded.schedule.ChangeScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engines = mutableSetOf<BackgroundedEngine>()
    private val tapDetector = DoubleTapDetector()
    private var receiverRegistered = false

    @Volatile
    private var settingsCache: Settings = Settings.DEFAULTS

    @Volatile
    private var pairCache: BackgroundPair? = null

    private val keyguardManager: KeyguardManager? by lazy {
        getSystemService(KeyguardManager::class.java)
    }

    @Inject lateinit var rotationCoordinator: dev.backgrounded.domain.rotation.RotationCoordinator

    @Inject lateinit var diagnostics: dev.backgrounded.core.diagnostics.LocalDiagnostics

    @Inject lateinit var pinVault: dev.backgrounded.core.security.PinVault

    @Inject lateinit var folderDiscovery: dev.backgrounded.data.importer.FolderDiscoveryController

    @Suppress("CyclomaticComplexMethod")
    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            rotationCoordinator.run(Unit) { rotationCoordinator.recover() }
            val settings = settingsStore.settings.first()
            wallpaperBus.set(
                CurrentWallpaper(
                    pairId = settings.currentPairId,
                    albumId = settings.activeAlbumId,
                    changedAt = settings.currentChangedAt,
                ),
            )
        }
        serviceScope.launch {
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
        serviceScope.launch {
            settingsStore.settings.collect { settings ->
                val previous = settingsCache
                settingsCache = settings
                if (previous.rotationPaused != settings.rotationPaused ||
                    previous.activeAlbumId != settings.activeAlbumId || previous.pendingUnlock != settings.pendingUnlock
                ) {
                    changeScheduler.rearm()
                }
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
        serviceScope.launch {
            changeScheduler.due.collectLatest { expected ->
                if (expected == Long.MIN_VALUE || !changeScheduler.hasVisibleEngine()) return@collectLatest
                val pending = settingsStore.settings.first().pendingUnlock
                val result =
                    applyNextBackground.execute(
                        if (pending) Trigger.UNLOCK else Trigger.TIMER,
                        expectedScheduleAt = expected,
                    )
                if (result != RotationResult.Applied) {
                    val settings = settingsStore.settings.first()
                    if (settings.nextTriggerAt == expected && changeScheduler.hasVisibleEngine()) {
                        val hidden = settings.activeAlbumId?.let { albumRepository.getAlbum(it)?.isHidden } == true
                        changeScheduler.failed(locked = hidden && settings.encryptHidden && !pinVault.unlocked())
                    }
                }
            }
        }
        serviceScope.launch {
            pinVault.unlockedState.collect { unlocked ->
                if (unlocked) {
                    changeScheduler.authenticated()
                    mainHandler.post { engines.forEach { it.invalidate() } }
                }
            }
        }
        serviceScope.launch { changeScheduler.rearm() }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(unlockReceiver)
            receiverRegistered = false
        }
        engines.toList().forEach { it.destroyResources() }
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        engines.clear()
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine {
        diagnostics.count(settingsCache.debugDiagnostics, "engine_created")
        val engine = BackgroundedEngine()
        engines.add(engine)
        return engine
    }

    private fun dispatchGestureAction() {
        if (!settingsCache.doubleTapEnabled) return
        serviceScope.launch {
            when (settingsCache.doubleTapAction) {
                GestureAction.NEXT -> applyNextBackground(Trigger.GESTURE)
                GestureAction.PREVIOUS -> applyPreviousBackground(Trigger.GESTURE)
                GestureAction.NEXT_ALBUM -> {
                    val selection = nextAlbum(Trigger.GESTURE)
                    if (selection is RotationResult.AuthenticationRequired) {
                        runCatching {
                            startActivity(
                                dev.backgrounded.core.security.HiddenSwitchAuthActivity.intent(
                                    this@BackgroundedWallpaperService,
                                    Trigger.GESTURE,
                                    albumId = selection.albumId,
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
                        val unlockedAt = System.currentTimeMillis()
                        mainHandler.post { engines.forEach { it.invalidate() } }
                        serviceScope.launch { handleUnlock(unlockedAt) }
                    }
                    Intent.ACTION_SCREEN_OFF -> mainHandler.post { engines.forEach { it.invalidate() } }
                }
            }
        }

    private suspend fun handleUnlock(unlockedAt: Long) =
        rotationCoordinator.run(Unit) {
            val settings = settingsStore.settings.first()
            val albumId = settings.activeAlbumId ?: return@run
            val album = albumRepository.getAlbum(albumId) ?: return@run
            if (!album.unlockPolicy.enabled) return@run
            val epochDay = LocalDate.now().toEpochDay()
            val latest =
                if (settings.currentChangedAt >= unlockedAt) {
                    albumRepository.latestAutomaticChange(albumId, settings.currentChangedAt)
                } else {
                    null
                }
            val decision =
                UnlockPolicyEvaluator.onUnlock(
                    policy = album.unlockPolicy,
                    state = settings.unlockState,
                    unlockedAt = unlockedAt,
                    epochDay = epochDay,
                    automaticChangedAt = latest,
                )
            settingsStore.setUnlockState(decision.state)
            if (decision.pending) {
                settingsStore.setPendingUnlock(true)
                changeScheduler.rearm()
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
        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var loadJob: Job? = null
        private var generation = 0L
        private var alive = true
        private var surfaceAvailable = false
        private var frameScheduled = false
        private var lastFrameNanos = 0L
        private var scheduledAtNanos = 0L
        private val frameCallback =
            Choreographer.FrameCallback { nanos ->
                frameScheduled = false
                if (alive && visible && surfaceAvailable) {
                    val now = SystemClock.elapsedRealtime()
                    if (previousLayer != null) {
                        fade = ((now - fadeStartedAt).toFloat() / fadeDuration).coerceIn(0f, 1f)
                        if (fade >= 1f) finishFade()
                    }
                    drawNow()
                    lastFrameNanos = nanos
                    val moving = layer?.slide?.mode?.let { it != SlideMode.OFF } == true
                    if (previousLayer != null || moving) {
                        scheduleFrame(if (previousLayer != null) 0L else slideFrameDelay())
                    }
                }
            }

        private fun scheduleFrame(delayMillis: Long = 0L) {
            if (!alive || !visible || !surfaceAvailable) return
            val now = System.nanoTime()
            val interval = 1_000_000_000L / settingsCache.animationFps
            val at = maxOf(now + delayMillis * 1_000_000L, lastFrameNanos + interval)
            if (frameScheduled && at >= scheduledAtNanos) return
            if (frameScheduled) Choreographer.getInstance().removeFrameCallback(frameCallback)
            frameScheduled = true
            scheduledAtNanos = at
            val delay = ((at - now).coerceAtLeast(0) + 999999) / 1_000_000L
            diagnostics.count(settingsCache.debugDiagnostics, "frame_posted")
            Choreographer.getInstance().postFrameCallbackDelayed(frameCallback, delay)
        }

        private fun draw() {
            scheduleFrame()
        }

        private fun stopFrames() {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            frameScheduled = false
        }

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
        private var fadeStartedAt = 0L
        private var fadeDuration = 800L
        private var dirty = true
        private var rendering = false
        private var visible = false

        init {
            setOffsetNotificationsEnabled(true)
            applyTouchMode()
        }

        fun onSettingsChanged() {
            updateSlideTicker()
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
            generation++
            dirty = true
            if (visible) loadIfNeeded()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceAvailable = true
            if (!isPreview) serviceScope.launch { settingsStore.completeWallpaperSetup() }
            loadIfNeeded()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceAvailable = true
            if (!isPreview && visible) changeScheduler.visible(this, true)
            if (!isPreview && visible) folderDiscovery.visible(this, true)
            generation++
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
            surfaceAvailable = false
            if (!isPreview) changeScheduler.visible(this, false)
            if (!isPreview) folderDiscovery.visible(this, false)
            generation++
            surfaceWidth = 0
            surfaceHeight = 0
            loadJob?.cancel()
            stopFrames()
            releaseLayers()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (!isPreview) changeScheduler.visible(this, visible)
            if (!isPreview) folderDiscovery.visible(this, visible)
            if (visible) {
                lastTranslation = -1
                lastMotionKey = null
                draw()
                dirty = true
                loadIfNeeded()
            }
            if (!visible) {
                generation++
                loadJob?.cancel()
                dirty = true
                finishFade()
                stopFrames()
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

        fun destroyResources() {
            if (!alive) return
            diagnostics.count(settingsCache.debugDiagnostics, "engine_destroyed")
            alive = false
            visible = false
            surfaceAvailable = false
            generation++
            engines.remove(this)
            if (!isPreview) changeScheduler.visible(this, false)
            if (!isPreview) folderDiscovery.visible(this, false)
            engineScope.cancel()
            stopFrames()
            surfaceWidth = 0
            surfaceHeight = 0
            releaseLayers()
        }

        override fun onDestroy() {
            destroyResources()
            super.onDestroy()
        }

        private fun applyTouchMode() {
            setTouchEventsEnabled(
                settingsCache.doubleTapEnabled && settingsCache.doubleTapMode == DoubleTapMode.ANYWHERE,
            )
        }

        @Suppress("CyclomaticComplexMethod", "LongMethod", "ComplexCondition", "TooGenericExceptionCaught")
        private fun loadIfNeeded() {
            if (!alive || !visible || !surfaceAvailable || !dirty || surfaceWidth <= 0 || surfaceHeight <= 0) return
            if (rendering) return
            rendering = true
            dirty = false
            val requestGeneration = generation
            val width = surfaceWidth
            val height = surfaceHeight
            val target = displayTarget
            val surface = resolveSurface()
            val pair = pairCache
            val wallpaper = wallpaperBus.state.value
            loadJob =
                engineScope.launch {
                    var source: Bitmap? = null
                    var rotated: Bitmap? = null
                    var installed = false
                    try {
                        val asset = pair?.imageFor(surface) ?: return@launch
                        if (pair.id != wallpaper.pairId) return@launch
                        if (asset.sourceType == dev.backgrounded.domain.model.SourceType.ENCRYPTED_IMPORT &&
                            !pinVault.unlocked()
                        ) {
                            settingsStore.setLastError("Hidden images are locked. Authenticate to resume.")
                            return@launch
                        }
                        val framing = asset.framingFor(target, surface)
                        val album = albumRepository.getAlbum(pair.albumId)
                        val dim = asset.dimForLock && surface == WallpaperSurface.LOCK
                        val scrolls = surface == WallpaperSurface.HOME
                        val key =
                            LayerKey(
                                asset.id, asset.sha256, asset.storageRef, framing, scrolls, dim,
                                target, surface, width, height,
                            )
                        val slideMode =
                            if (album?.scheduleType == ScheduleType.INTERVAL) album.slideMode else SlideMode.OFF
                        val speed = album?.slideSpeedPxPerSecond ?: DEFAULT_SLIDE_SPEED
                        val duration = album?.crossfadeDurationMs ?: 800
                        if (requestGeneration != generation || !alive || !visible) return@launch
                        if (key == layerKey) {
                            diagnostics.count(settingsCache.debugDiagnostics, "layer_reused")
                            layer?.apply {
                                slide = slide.copy(mode = slideMode, speedPxPerSecond = speed)
                                crossfadeDurationMs = duration
                            }
                            if (album?.crossfadeEnabled == false) finishFade()
                            updateSlideTicker()
                            updateGyroSubscription()
                            draw()
                            return@launch
                        }
                        val scroll = ScrollGeometry.scrollFor(framing, width)
                        diagnostics.count(settingsCache.debugDiagnostics, "layer_decode_requested")
                        source = bitmapLoader.decode(
                            asset, (width + scroll.slackPixels).coerceAtMost(DECODE_MAX_DIMENSION),
                            height.coerceAtMost(DECODE_MAX_DIMENSION),
                        ) ?: run {
                            settingsStore.setLastError("Image could not be decoded")
                            return@launch
                        }
                        finishFade()
                        var backdrop: Bitmap? = null
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Default) {
                            rotated = backgroundRenderer.rotated(requireNotNull(source), framing.rotationDegrees)
                            backdrop = backgroundRenderer.prepareBackdrop(requireNotNull(rotated), framing)
                        }
                        if (rotated !== source) {
                            source?.recycle()
                            source = rotated
                        }
                        if (requestGeneration != generation ||
                            renderIsStale(
                                width,
                                height,
                                target,
                                surface,
                            )
                        ) {
                            return@launch
                        }
                        val next =
                            Layer(
                                requireNotNull(source), requireNotNull(rotated), framing, scroll, scrolls, dim,
                                SlideSettings(slideMode, speed, SystemClock.elapsedRealtime()), backdrop,
                            )
                        next.crossfadeDurationMs = duration
                        val old = layer
                        layerKey = key
                        lastTranslation = -1
                        lastMotionKey = null
                        lastFadeStep = -1
                        finishFade()
                        layer = next
                        installed = true
                        if (album?.crossfadeEnabled != false && old != null && visible) {
                            previousLayer = old
                            startFade()
                        } else {
                            old?.recycle()
                            fade = 1f
                        }
                        updateSlideTicker()
                        updateGyroSubscription()
                        draw()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: OutOfMemoryError) {
                        settingsStore.setLastError("Image memory allocation failed: ${failure.message}")
                    } catch (failure: Exception) {
                        settingsStore.setLastError("Image could not be displayed: ${failure.message}")
                    } finally {
                        if (!installed) recyclePrepared(source, rotated)
                        rendering = false
                        if (alive && visible && dirty) loadIfNeeded()
                    }
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
            if (!alive || !visible || !surfaceAvailable) return true
            if (dirty ||
                width != surfaceWidth ||
                height != surfaceHeight
            ) {
                return true
            }
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
            fadeStartedAt = SystemClock.elapsedRealtime()
            fadeDuration = (layer?.crossfadeDurationMs ?: 800).toLong().coerceIn(MIN_FADE_MILLIS, MAX_FADE_MILLIS)
            fade = 0f
            draw()
        }

        @Suppress("CyclomaticComplexMethod", "ComplexCondition")
        private fun drawNow() {
            val current = layer
            if (current == null || !visible) return
            val now = SystemClock.elapsedRealtime()
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
            diagnostics.count(settingsCache.debugDiagnostics, "wallpaper_drawn")
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
            (500f / (layer?.slide?.speedPxPerSecond ?: DEFAULT_SLIDE_SPEED)).toLong()
                .coerceIn((1000L + settingsCache.animationFps - 1) / settingsCache.animationFps, 1_000L)

        private fun updateSlideTicker() {
            if (visible && layer?.slide?.mode?.let { it != SlideMode.OFF } == true) draw()
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
            canvas.drawColor(Color.BLACK)
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
                        preparedBackdrop = layer.backdrop,
                    ),
            )
            if (alpha < 1f) canvas.restore()
        }

        private fun releaseLayers() {
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

    @Suppress("LongParameterList")
    private class Layer(
        val source: Bitmap,
        val rotated: Bitmap,
        val framing: Framing,
        val scroll: ScrollGeometry.Scroll,
        val scrolls: Boolean,
        val dim: Boolean,
        var slide: SlideSettings,
        val backdrop: Bitmap?,
    ) {
        var crossfadeDurationMs: Int = 800

        fun recycle() {
            if (rotated !== source) rotated.recycle()
            source.recycle()
        }
    }

    private data class LayerKey(
        val assetId: Long,
        val revision: String?,
        val reference: String,
        val framing: Framing,
        val scrolls: Boolean,
        val dim: Boolean,
        val target: DisplayTarget,
        val surface: WallpaperSurface,
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
