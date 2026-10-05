package dev.backgrounded.data.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.db.AlbumEntity
import dev.backgrounded.data.db.BackgroundEntity
import dev.backgrounded.data.db.BackgroundFramingEntity
import dev.backgrounded.data.db.BackgroundPairEntity
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.db.toEntity
import dev.backgrounded.domain.model.BackdropType
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.DisplayTarget
import dev.backgrounded.domain.model.DoubleTapMode
import dev.backgrounded.domain.model.FitMode
import dev.backgrounded.domain.model.Framing
import dev.backgrounded.domain.model.FramingKey
import dev.backgrounded.domain.model.GestureAction
import dev.backgrounded.domain.model.NormalizedCrop
import dev.backgrounded.domain.model.RotationOrder
import dev.backgrounded.domain.model.ScheduleType
import dev.backgrounded.domain.model.ScrollMode
import dev.backgrounded.domain.model.SourceType
import dev.backgrounded.domain.model.WallpaperSurface
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupFile(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val appVersion: String = "",
    val albums: List<AlbumBackup> = emptyList(),
    val settings: SettingsBackup? = null,
)

@Serializable
data class AlbumBackup(
    val name: String,
    val hidden: Boolean = false,
    val rotationOrder: String = RotationOrder.SEQUENTIAL.name,
    val scheduleType: String = ScheduleType.NONE.name,
    val intervalMinutes: Int? = null,
    val intervalSeconds: Int? = null,
    val slideMode: String = "OFF",
    val slideSpeedPxPerSecond: Float = 10f,
    val crossfadeEnabled: Boolean? = null,
    val crossfadeDurationMs: Int = 800,
    val rotationEnabled: Boolean = true,
    val fixedTimes: List<String> = emptyList(),
    val unlockEnabled: Boolean = false,
    val unlockMinMinutes: Int = 5,
    val unlockEveryN: Int = 1,
    val unlockMaxPerDay: Int = 20,
    val images: List<BackgroundBackup> = emptyList(),
    val pairs: List<PairBackup> = emptyList(),
    val coverPairIndex: Int? = null,
    val lastAppliedPairIndex: Int? = null,
    val shufflePairs: List<Int> = emptyList(),
    val fixedHomeIndex: Int? = null,
    val fixedLockIndex: Int? = null,
    val backgrounds: List<BackgroundBackup> = emptyList(),
    val coverIndex: Int? = null,
    val lastAppliedIndex: Int? = null,
    val shuffleRemaining: List<Int> = emptyList(),
)

@Serializable
data class PairBackup(
    val homeIndex: Int,
    val lockIndex: Int,
)

@Serializable
data class BackgroundBackup(
    val sourceType: String,
    val storageRef: String,
    val displayName: String,
    val sha256: String? = null,
    val width: Int,
    val height: Int,
    val dimForLock: Boolean = true,
    val framings: List<FramingBackup> = emptyList(),
    val fitMode: String? = null,
    val cropLeft: Float? = null,
    val cropTop: Float? = null,
    val cropWidth: Float? = null,
    val cropHeight: Float? = null,
    val zoom: Float? = null,
    val panX: Float? = null,
    val panY: Float? = null,
    val rotationDegrees: Int? = null,
    val backdrop: String? = null,
    val blurIntensity: Int? = null,
    val backdropZoom: Float? = null,
    val backdropPanX: Float? = null,
    val backdropPanY: Float? = null,
    val backdropColor: Int? = null,
    val parallaxAmount: Float? = null,
)

@Serializable
data class FramingBackup(
    val target: String,
    val surface: String? = null,
    val fitMode: String = FitMode.FILL.name,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropWidth: Float = 1f,
    val cropHeight: Float = 1f,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val stretchX: Float = 1f,
    val stretchY: Float = 1f,
    val rotationDegrees: Int = 0,
    val backdrop: String = BackdropType.BLUR.name,
    val blurIntensity: Int = Framing.DEFAULT_BLUR_INTENSITY,
    val backdropZoom: Float = 1f,
    val backdropPanX: Float = 0f,
    val backdropPanY: Float = 0f,
    val backdropColor: Int = 0xFF000000.toInt(),
    val scrollMode: String = ScrollMode.OFF.name,
    val scrollAmountPercent: Int = 0,
    val scrollPages: Int = 3,
    val scrollStartFraction: Float = 0f,
    val scrollSpanFraction: Float = 1f,
    val gyroParallax: Boolean = false,
    val gyroIntensity: Int = Framing.DEFAULT_GYRO_INTENSITY,
    val mirrorX: Boolean = false,
    val mirrorY: Boolean = false,
)

@Serializable
data class SettingsBackup(
    val activeAlbumIndex: Int? = null,
    val rotationPaused: Boolean = false,
    val authenticateHiddenSwitch: Boolean = true,
    val doubleTapEnabled: Boolean = true,
    val doubleTapMode: String = DoubleTapMode.BACKGROUND.name,
    val doubleTapAction: String = GestureAction.NEXT.name,
    // Legacy global value, used only when importing older backups.
    val crossfadeEnabled: Boolean? = null,
    val externalControlEnabled: Boolean = false,
    val widgetIconSource: String = "builtin:next",
    val widgetIconAlpha: Int = 255,
    val widgetBackgroundAlpha: Int = 0,
    val widgetTapAction: String = GestureAction.NEXT.name,
    val widgetDoubleTapAction: String = GestureAction.NEXT_ALBUM.name,
    val widgetPinnedAlbumIndex: Int? = null,
)

private const val CURRENT_SCHEMA = 7

@Singleton
class BackupManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: BackgroundedDatabase,
        private val settingsStore: SettingsStore,
    ) {
        private val json =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        suspend fun exportJson(): String {
            val albums = database.albumDao().observeAll().first()
            val backup =
                BackupFile(
                    appVersion = appVersion(),
                    albums = albums.map { album -> album.toBackup() },
                    settings =
                        settingsStore.settings.first().let { settings ->
                            SettingsBackup(
                                activeAlbumIndex =
                                    albums.indexOfFirst { it.id == settings.activeAlbumId }
                                        .takeIf { it >= 0 },
                                rotationPaused = settings.rotationPaused,
                                authenticateHiddenSwitch = settings.authenticateHiddenSwitch,
                                doubleTapEnabled = settings.doubleTapEnabled,
                                doubleTapMode = settings.doubleTapMode.name,
                                doubleTapAction = settings.doubleTapAction.name,
                                externalControlEnabled = settings.externalControlEnabled,
                                widgetIconSource = settings.widgetIconSource,
                                widgetIconAlpha = settings.widgetIconAlpha,
                                widgetBackgroundAlpha = settings.widgetBackgroundAlpha,
                                widgetTapAction = settings.widgetTapAction.name,
                                widgetDoubleTapAction = settings.widgetDoubleTapAction.name,
                                widgetPinnedAlbumIndex =
                                    albums.indexOfFirst { it.id == settings.widgetPinnedAlbumId }
                                        .takeIf { it >= 0 },
                            )
                        },
                )
            return json.encodeToString(backup)
        }

        @Suppress("LongMethod")
        suspend fun importJson(text: String): Boolean =
            runCatching {
                val backup = json.decodeFromString<BackupFile>(text)
                database.albumDao().clear()
                database.pairDao().clear()
                database.backgroundDao().clear()
                database.framingDao().clear()
                database.historyDao().clear()
                val importedAlbumIds = mutableListOf<Long>()
                backup.albums.forEachIndexed { albumIndex, albumBackup ->
                    val albumId =
                        database.albumDao().insert(
                            AlbumEntity(
                                name = albumBackup.name,
                                coverPairId = null,
                                fixedHomeAssetId = null,
                                fixedLockAssetId = null,
                                isHidden = albumBackup.hidden,
                                rotationOrder = albumBackup.rotationOrder,
                                scheduleType = albumBackup.scheduleType,
                                intervalMinutes = albumBackup.intervalMinutes,
                                intervalSeconds = albumBackup.intervalSeconds ?: albumBackup.intervalMinutes?.times(60),
                                slideMode = albumBackup.slideMode,
                                slideSpeedPxPerSecond = albumBackup.slideSpeedPxPerSecond,
                                crossfadeEnabled =
                                    albumBackup.crossfadeEnabled
                                        ?: backup.settings?.crossfadeEnabled ?: true,
                                crossfadeDurationMs = albumBackup.crossfadeDurationMs.coerceIn(100, 3000),
                                rotationEnabled = albumBackup.rotationEnabled,
                                fixedTimesCsv = albumBackup.fixedTimes.joinToString(separator = ","),
                                unlockEnabled = albumBackup.unlockEnabled,
                                unlockMinMinutes = albumBackup.unlockMinMinutes,
                                unlockEveryN = albumBackup.unlockEveryN,
                                unlockMaxPerDay = albumBackup.unlockMaxPerDay,
                                lastAppliedPairId = null,
                                lastChangedAt = 0L,
                                shuffleRemainingCsv = null,
                                sortIndex = albumIndex,
                            ),
                        )
                    importedAlbumIds += albumId

                    val images = albumBackup.images.ifEmpty { albumBackup.backgrounds }
                    val imageIds =
                        images.mapIndexed { index, image ->
                            val id = database.backgroundDao().insert(image.toEntity(albumId, index))
                            database.framingDao().upsertAll(image.toFramings(id))
                            id
                        }
                    val pairBackups = albumBackup.pairs.ifEmpty { images.indices.map { PairBackup(it, it) } }
                    val pairIds =
                        pairBackups.mapIndexed { index, pair ->
                            database.pairDao().insert(
                                BackgroundPairEntity(
                                    albumId = albumId,
                                    homeBackgroundId = imageIds[pair.homeIndex],
                                    lockBackgroundId = imageIds[pair.lockIndex],
                                    sortIndex = index,
                                    addedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                    val cover = (albumBackup.coverPairIndex ?: albumBackup.coverIndex)?.let { pairIds.getOrNull(it) }
                    val last =
                        (albumBackup.lastAppliedPairIndex ?: albumBackup.lastAppliedIndex)
                            ?.let { pairIds.getOrNull(it) }
                    val shuffle =
                        albumBackup.shufflePairs.ifEmpty { albumBackup.shuffleRemaining }
                            .mapNotNull { pairIds.getOrNull(it) }
                    val current = database.albumDao().get(albumId) ?: return@forEachIndexed
                    database.albumDao().update(
                        current.copy(
                            coverPairId = cover ?: current.coverPairId,
                            lastAppliedPairId = last ?: current.lastAppliedPairId,
                            shuffleRemainingCsv = shuffle.joinToString(separator = ","),
                            fixedHomeAssetId =
                                albumBackup.fixedHomeIndex?.let { imageIds.getOrNull(it) }
                                    ?: current.fixedHomeAssetId,
                            fixedLockAssetId =
                                albumBackup.fixedLockIndex?.let { imageIds.getOrNull(it) }
                                    ?: current.fixedLockAssetId,
                        ),
                    )
                }
                backup.settings?.let { settings ->
                    settingsStore.setRotationPaused(settings.rotationPaused)
                    settingsStore.setAuthenticateHiddenSwitch(settings.authenticateHiddenSwitch)
                    settingsStore.setDoubleTap(
                        settings.doubleTapEnabled,
                        DoubleTapMode.from(settings.doubleTapMode),
                        GestureAction.from(settings.doubleTapAction),
                    )
                    settingsStore.setExternalControl(settings.externalControlEnabled)
                    settingsStore.setWidgetConfig(
                        iconSource = settings.widgetIconSource,
                        iconAlpha = settings.widgetIconAlpha,
                        backgroundAlpha = settings.widgetBackgroundAlpha,
                        tapAction = GestureAction.from(settings.widgetTapAction),
                        doubleTapAction = GestureAction.from(settings.widgetDoubleTapAction),
                        pinnedAlbumId = settings.widgetPinnedAlbumIndex?.let { importedAlbumIds.getOrNull(it) },
                    )
                    settingsStore.setActiveAlbum(settings.activeAlbumIndex?.let { importedAlbumIds.getOrNull(it) })
                    settingsStore.setCurrent(null, 0L)
                }
                true
            }.getOrDefault(false)

        private suspend fun AlbumEntity.toBackup(): AlbumBackup {
            val pairs = database.pairDao().listForAlbum(id)
            val fixedIds = listOfNotNull(fixedHomeAssetId, fixedLockAssetId)
            val assetIds =
                (pairs.flatMap { listOf(it.homeBackgroundId, it.lockBackgroundId) } + fixedIds)
                    .distinct()
            val assets =
                assetIds.mapNotNull { database.backgroundDao().get(it) }
                    .sortedWith(compareBy({ it.sortIndex }, { it.id }))
            val framings = database.framingDao().listForBackgrounds(assetIds).groupBy { it.backgroundId }
            val assetIndex = assets.withIndex().associate { (index, asset) -> asset.id to index }
            val pairIndex = pairs.withIndex().associate { (index, pair) -> pair.id to index }
            return AlbumBackup(
                name = name,
                hidden = isHidden,
                rotationOrder = rotationOrder,
                scheduleType = scheduleType,
                intervalMinutes = intervalMinutes,
                intervalSeconds = intervalSeconds,
                slideMode = slideMode,
                slideSpeedPxPerSecond = slideSpeedPxPerSecond,
                crossfadeEnabled = crossfadeEnabled,
                crossfadeDurationMs = crossfadeDurationMs,
                rotationEnabled = rotationEnabled,
                fixedTimes =
                    fixedTimesCsv.orEmpty()
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                unlockEnabled = unlockEnabled,
                unlockMinMinutes = unlockMinMinutes,
                unlockEveryN = unlockEveryN,
                unlockMaxPerDay = unlockMaxPerDay,
                images = assets.map { asset -> asset.toBackup(framings[asset.id].orEmpty()) },
                pairs =
                    pairs.mapNotNull { pair ->
                        val home = assetIndex[pair.homeBackgroundId] ?: return@mapNotNull null
                        val lock = assetIndex[pair.lockBackgroundId] ?: return@mapNotNull null
                        PairBackup(home, lock)
                    },
                coverPairIndex = coverPairId?.let { pairIndex[it] },
                lastAppliedPairIndex = lastAppliedPairId?.let { pairIndex[it] },
                shufflePairs =
                    shuffleRemainingCsv.orEmpty()
                        .split(',')
                        .mapNotNull { it.trim().toLongOrNull()?.let { id -> pairIndex[id] } },
                fixedHomeIndex = fixedHomeAssetId?.let { assetIndex[it] },
                fixedLockIndex = fixedLockAssetId?.let { assetIndex[it] },
            )
        }

        private fun BackgroundEntity.toBackup(framings: List<BackgroundFramingEntity>): BackgroundBackup =
            BackgroundBackup(
                sourceType = sourceType,
                storageRef = storageRef,
                displayName = displayName,
                sha256 = sha256,
                width = width,
                height = height,
                dimForLock = dimForLock,
                framings =
                    framings.map { framing ->
                        FramingBackup(
                            target = framing.target,
                            surface = framing.surface,
                            fitMode = framing.fitMode,
                            cropLeft = framing.cropLeft,
                            cropTop = framing.cropTop,
                            cropWidth = framing.cropWidth,
                            cropHeight = framing.cropHeight,
                            zoom = framing.zoom,
                            panX = framing.panX,
                            panY = framing.panY,
                            stretchX = framing.stretchX,
                            stretchY = framing.stretchY,
                            rotationDegrees = framing.rotationDegrees,
                            backdrop = framing.backdrop,
                            blurIntensity = framing.blurIntensity,
                            backdropZoom = framing.backdropZoom,
                            backdropPanX = framing.backdropPanX,
                            backdropPanY = framing.backdropPanY,
                            backdropColor = framing.backdropColor,
                            scrollMode = framing.scrollMode,
                            scrollAmountPercent = framing.scrollAmountPercent,
                            scrollPages = framing.scrollPages,
                            scrollStartFraction = framing.scrollStartFraction,
                            scrollSpanFraction = framing.scrollSpanFraction,
                            gyroParallax = framing.gyroParallax,
                            gyroIntensity = framing.gyroIntensity,
                            mirrorX = framing.mirrorX,
                            mirrorY = framing.mirrorY,
                        )
                    },
            )

        private fun BackgroundBackup.toEntity(
            albumId: Long,
            sortIndex: Int,
        ): BackgroundEntity =
            Background(
                id = 0,
                albumId = albumId,
                sourceType = SourceType.from(sourceType),
                storageRef = storageRef,
                displayName = displayName,
                sha256 = sha256,
                width = width,
                height = height,
                dimForLock = dimForLock,
                sortIndex = sortIndex,
                addedAt = System.currentTimeMillis(),
                framings = emptyMap(),
            ).toEntity()

        private fun BackgroundBackup.toFramings(backgroundId: Long): List<BackgroundFramingEntity> {
            if (framings.isNotEmpty()) {
                return framings.flatMap { framing ->
                    val display = DisplayTarget.from(framing.target)
                    val surfaces =
                        framing.surface?.let { surface -> listOf(WallpaperSurface.from(surface)) }
                            ?: WallpaperSurface.entries
                    surfaces.map { surface ->
                        framing.toModel().toEntity(FramingKey(display, surface), backgroundId)
                    }
                }
            }
            val legacy = legacyFraming()
            return Background.keys().map { key -> legacy.toEntity(key, backgroundId) }
        }

        private fun BackgroundBackup.legacyFraming(): Framing {
            val parallaxPercent = ((parallaxAmount ?: 0f) * 100f).toInt()
            return Framing(
                fitMode = FitMode.from(fitMode),
                crop =
                    NormalizedCrop(
                        left = cropLeft ?: 0f,
                        top = cropTop ?: 0f,
                        width = cropWidth ?: 1f,
                        height = cropHeight ?: 1f,
                    ),
                zoom = zoom ?: 1f,
                panX = panX ?: 0f,
                panY = panY ?: 0f,
                stretchX = Framing.DEFAULT_STRETCH,
                stretchY = Framing.DEFAULT_STRETCH,
                rotationDegrees = rotationDegrees ?: 0,
                backdrop = BackdropType.from(backdrop),
                blurIntensity = blurIntensity ?: Framing.DEFAULT_BLUR_INTENSITY,
                backdropZoom = backdropZoom ?: 1f,
                backdropPanX = backdropPanX ?: 0f,
                backdropPanY = backdropPanY ?: 0f,
                backdropColor = backdropColor ?: 0xFF000000.toInt(),
                scrollMode = if (parallaxPercent > 0) ScrollMode.AMOUNT else ScrollMode.OFF,
                scrollAmountPercent = parallaxPercent,
                scrollPages = 3,
                scrollStartFraction = 0f,
                scrollSpanFraction = 1f,
                gyroParallax = false,
                gyroIntensity = Framing.DEFAULT_GYRO_INTENSITY,
            )
        }

        private fun FramingBackup.toModel(): Framing =
            Framing(
                fitMode = FitMode.from(fitMode),
                crop = NormalizedCrop(cropLeft, cropTop, cropWidth, cropHeight),
                zoom = zoom,
                panX = panX,
                panY = panY,
                stretchX = stretchX,
                stretchY = stretchY,
                rotationDegrees = rotationDegrees,
                backdrop = BackdropType.from(backdrop),
                blurIntensity = blurIntensity,
                backdropZoom = backdropZoom,
                backdropPanX = backdropPanX,
                backdropPanY = backdropPanY,
                backdropColor = backdropColor,
                scrollMode = ScrollMode.from(scrollMode),
                scrollAmountPercent = scrollAmountPercent,
                scrollPages = scrollPages,
                scrollStartFraction = scrollStartFraction,
                scrollSpanFraction = scrollSpanFraction,
                gyroParallax = gyroParallax,
                gyroIntensity = gyroIntensity,
                mirrorX = mirrorX,
                mirrorY = mirrorY,
            )

        private fun appVersion(): String =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            }.getOrDefault("")
    }
