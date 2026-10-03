package dev.backgrounded.data.db

import dev.backgrounded.domain.model.Album
import dev.backgrounded.domain.model.BackdropType
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.BackgroundPair
import dev.backgrounded.domain.model.DisplayTarget
import dev.backgrounded.domain.model.FitMode
import dev.backgrounded.domain.model.Framing
import dev.backgrounded.domain.model.FramingKey
import dev.backgrounded.domain.model.HistoryEntry
import dev.backgrounded.domain.model.NormalizedCrop
import dev.backgrounded.domain.model.RotationOrder
import dev.backgrounded.domain.model.ScheduleType
import dev.backgrounded.domain.model.ScrollMode
import dev.backgrounded.domain.model.SourceType
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.model.UnlockPolicy
import dev.backgrounded.domain.model.WallpaperSurface
import java.time.LocalTime

fun AlbumEntity.toModel(): Album =
    Album(
        id = id,
        name = name,
        coverPairId = coverPairId,
        fixedHomeAssetId = fixedHomeAssetId,
        fixedLockAssetId = fixedLockAssetId,
        isHidden = isHidden,
        rotationOrder = RotationOrder.from(rotationOrder),
        scheduleType = ScheduleType.from(scheduleType),
        intervalMinutes = intervalMinutes,
        fixedTimes = fixedTimesCsv.toLocalTimes(),
        unlockPolicy =
            UnlockPolicy(
                enabled = unlockEnabled,
                minMinutes = unlockMinMinutes,
                everyN = unlockEveryN,
                maxPerDay = unlockMaxPerDay,
            ),
        lastAppliedPairId = lastAppliedPairId,
        lastChangedAt = lastChangedAt,
        shuffleRemaining = shuffleRemainingCsv.toIdList(),
        sortIndex = sortIndex,
    )

fun Album.toEntity(): AlbumEntity =
    AlbumEntity(
        id = id,
        name = name,
        coverPairId = coverPairId,
        fixedHomeAssetId = fixedHomeAssetId,
        fixedLockAssetId = fixedLockAssetId,
        isHidden = isHidden,
        rotationOrder = rotationOrder.name,
        scheduleType = scheduleType.name,
        intervalMinutes = intervalMinutes,
        fixedTimesCsv = fixedTimes.joinToString(separator = ",") { it.toString() },
        unlockEnabled = unlockPolicy.enabled,
        unlockMinMinutes = unlockPolicy.minMinutes,
        unlockEveryN = unlockPolicy.everyN,
        unlockMaxPerDay = unlockPolicy.maxPerDay,
        lastAppliedPairId = lastAppliedPairId,
        lastChangedAt = lastChangedAt,
        shuffleRemainingCsv = shuffleRemaining.joinToString(separator = ","),
        sortIndex = sortIndex,
    )

fun BackgroundPairEntity.toModel(images: Map<Long, Background>): BackgroundPair? {
    val home = images[homeBackgroundId] ?: return null
    val lock = images[lockBackgroundId] ?: return null
    return BackgroundPair(
        id = id,
        albumId = albumId,
        home = home,
        lock = lock,
        sortIndex = sortIndex,
        addedAt = addedAt,
    )
}

fun BackgroundEntity.toModel(framings: List<BackgroundFramingEntity>): Background {
    val byKey =
        framings.associateBy {
            FramingKey(DisplayTarget.from(it.target), WallpaperSurface.from(it.surface))
        }
    return Background(
        id = id,
        albumId = albumId,
        sourceType = SourceType.from(sourceType),
        storageRef = storageRef,
        displayName = displayName,
        sha256 = sha256,
        width = width,
        height = height,
        dimForLock = dimForLock,
        sortIndex = sortIndex,
        addedAt = addedAt,
        framings =
            Background.keys().associateWith { key ->
                byKey[key]?.toModel() ?: Framing.DEFAULT
            },
    )
}

fun Background.toEntity(): BackgroundEntity =
    BackgroundEntity(
        id = id,
        albumId = albumId,
        sourceType = sourceType.name,
        storageRef = storageRef,
        displayName = displayName,
        sha256 = sha256,
        width = width,
        height = height,
        dimForLock = dimForLock,
        sortIndex = sortIndex,
        addedAt = addedAt,
    )

fun BackgroundFramingEntity.toModel(): Framing =
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
    )

fun Framing.toEntity(
    key: FramingKey,
    backgroundId: Long,
): BackgroundFramingEntity =
    BackgroundFramingEntity(
        backgroundId = backgroundId,
        target = key.display.name,
        surface = key.surface.name,
        fitMode = fitMode.name,
        cropLeft = crop.left,
        cropTop = crop.top,
        cropWidth = crop.width,
        cropHeight = crop.height,
        zoom = zoom,
        panX = panX,
        panY = panY,
        stretchX = stretchX,
        stretchY = stretchY,
        rotationDegrees = rotationDegrees,
        backdrop = backdrop.name,
        blurIntensity = blurIntensity,
        backdropZoom = backdropZoom,
        backdropPanX = backdropPanX,
        backdropPanY = backdropPanY,
        backdropColor = backdropColor,
        scrollMode = scrollMode.name,
        scrollAmountPercent = scrollAmountPercent,
        scrollPages = scrollPages,
        scrollStartFraction = scrollStartFraction,
        scrollSpanFraction = scrollSpanFraction,
        gyroParallax = gyroParallax,
        gyroIntensity = gyroIntensity,
    )

fun Background.framingEntities(): List<BackgroundFramingEntity> =
    framings.entries.map { (key, framing) -> framing.toEntity(key, id) }

fun HistoryEntity.toModel(): HistoryEntry =
    HistoryEntry(
        id = id,
        pairId = pairId,
        albumId = albumId,
        appliedAt = appliedAt,
        trigger = Trigger.entries.firstOrNull { it.name == trigger } ?: Trigger.MANUAL,
    )

private fun String?.toLocalTimes(): List<LocalTime> =
    this
        ?.split(',')
        ?.mapNotNull { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }
        .orEmpty()

private fun String?.toIdList(): List<Long> =
    this
        ?.split(',')
        ?.mapNotNull { it.trim().toLongOrNull() }
        .orEmpty()
