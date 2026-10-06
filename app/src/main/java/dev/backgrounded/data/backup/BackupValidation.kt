package dev.backgrounded.data.backup

import dev.backgrounded.domain.model.BackdropType
import dev.backgrounded.domain.model.FitMode
import dev.backgrounded.domain.model.RotationOrder
import dev.backgrounded.domain.model.ScheduleType
import dev.backgrounded.domain.model.ScrollMode
import dev.backgrounded.domain.model.SlideMode
import dev.backgrounded.domain.model.SourceType
import dev.backgrounded.domain.schedule.ScheduleCalculator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.time.LocalTime

object BackupValidation {
    const val MAX_BYTES = 32 * 1024 * 1024

    fun read(input: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_BYTES) { "Configuration exceeds 32 MiB" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun validate(
        text: String,
        backup: BackupFile,
    ) {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = Json.parseToJsonElement(text).jsonObject
        require("albums" in root) { "Missing albums" }
        val version =
            if ("schemaVersion" in root) {
                requireNotNull(root["schemaVersion"]?.jsonPrimitive?.intOrNull) { "Invalid configuration version" }
            } else {
                1
            }
        require(version in 1..8) { "Unsupported configuration version" }
        require(backup.albums.size <= 2000)
        require(backup.albums.sumOf { it.images.ifEmpty { it.backgrounds }.size.toLong() } <= 20000)
        require(backup.albums.sumOf { it.pairs.size.toLong() } <= 20000)
        backup.settings?.let { settings ->
            require(settings.animationFps in listOf(30, 60))
            require(settings.folderScanSeconds in listOf(0, 60, 300, 900))
            require(settings.widgetIconAlpha in 0..255 && settings.widgetBackgroundAlpha in 0..255)
            require(settings.doubleTapMode in dev.backgrounded.domain.model.DoubleTapMode.entries.map { it.name })
            listOf(settings.doubleTapAction, settings.widgetTapAction, settings.widgetDoubleTapAction).forEach {
                require(it in dev.backgrounded.domain.model.GestureAction.entries.map { action -> action.name })
            }
            listOfNotNull(settings.activeAlbumIndex, settings.widgetPinnedAlbumIndex).forEach {
                require(it in backup.albums.indices)
            }
        }
        backup.albums.forEach { album ->
            require(album.rotationOrder in RotationOrder.entries.map { it.name })
            require(album.scheduleType in ScheduleType.entries.map { it.name })
            require(album.slideMode in SlideMode.entries.map { it.name })
            require(album.slideSpeedPxPerSecond.isFinite() && album.slideSpeedPxPerSecond in 0.1f..120f)
            album.intervalSeconds?.let { require(it in 1..ScheduleCalculator.MAX_INTERVAL_SECONDS) }
            album.intervalMinutes?.let { require(it > 0 && it.toLong() * 60 <= 360000) }
            require(album.fixedTimes.size <= 1440)
            album.fixedTimes.forEach(LocalTime::parse)
            require(album.unlockMinMinutes >= 0 && album.unlockEveryN >= 1 && album.unlockMaxPerDay >= 0)
            val images = album.images.ifEmpty { album.backgrounds }
            require(album.pairs.size <= 20000)
            val pairIndices = if (album.pairs.isEmpty()) images.indices else album.pairs.indices
            listOfNotNull(album.coverPairIndex, album.lastAppliedPairIndex).forEach { require(it in pairIndices) }
            require(album.shufflePairs.all { it in pairIndices })
            listOfNotNull(album.coverIndex, album.lastAppliedIndex).forEach { require(it in images.indices) }
            require(album.shuffleRemaining.all { it in images.indices })
            require(album.crossfadeDurationMs in 100..3000)
            album.pairs.forEach { require(it.homeIndex in images.indices && it.lockIndex in images.indices) }
            listOfNotNull(album.fixedHomeIndex, album.fixedLockIndex).forEach { require(it in images.indices) }
            images.forEach(::validateImage)
        }
    }

    private fun validateImage(image: BackgroundBackup) {
        require(image.storageRef.isNotBlank() && '\u0000' !in image.storageRef) { "Invalid image reference" }
        image.sha256?.let { require(it.matches(SHA256)) { "Invalid image hash" } }
        if (image.sourceType == SourceType.SAF_LINK.name) {
            val uri = android.net.Uri.parse(image.storageRef)
            require(uri.scheme in setOf("content", "file")) { "Invalid linked image reference" }
            if (uri.scheme == "content") require(!uri.authority.isNullOrBlank())
        }
        require(image.sourceType in SourceType.entries.map { it.name })
        require(image.sourceType != SourceType.ENCRYPTED_IMPORT.name) {
            "Restore encrypted images before exporting a configuration"
        }
        require(image.width > 0 && image.height > 0)
        require(image.framings.size <= 4)
        require(image.framings.map { it.target to it.surface }.distinct().size == image.framings.size)
        require(
            listOfNotNull(
                image.zoom, image.panX, image.panY, image.cropLeft, image.cropTop,
                image.cropWidth, image.cropHeight, image.backdropZoom, image.backdropPanX,
                image.backdropPanY, image.parallaxAmount,
            ).all { it.isFinite() },
        )
        image.fitMode?.let { require(it in FitMode.entries.map { mode -> mode.name }) }
        image.backdrop?.let { require(it in BackdropType.entries.map { type -> type.name }) }
        image.zoom?.let { require(it in 0.2f..8f) }
        listOfNotNull(image.panX, image.panY).forEach { require(it in -1.5f..1.5f) }
        image.framings.forEach(::validateFraming)
    }

    private fun validateFraming(framing: FramingBackup) {
        require(framing.target in dev.backgrounded.domain.model.DisplayTarget.entries.map { it.name })
        framing.surface?.let {
            require(
                it in
                    dev.backgrounded.domain.model.WallpaperSurface.entries.map {
                            surface ->
                        surface.name
                    },
            )
        }
        require(framing.fitMode in FitMode.entries.map { it.name })
        require(framing.backdrop in BackdropType.entries.map { it.name })
        require(framing.scrollMode in ScrollMode.entries.map { it.name })
        val floats =
            listOf(
                framing.cropLeft, framing.cropTop, framing.cropWidth, framing.cropHeight,
                framing.zoom, framing.panX, framing.panY, framing.stretchX, framing.stretchY,
                framing.backdropZoom, framing.backdropPanX, framing.backdropPanY,
                framing.scrollStartFraction, framing.scrollSpanFraction,
            )
        require(floats.all { it.isFinite() })
        require(framing.zoom in 0.2f..8f && framing.stretchX in 0.1f..3f && framing.stretchY in 0.1f..3f)
        require(framing.panX in -1.5f..1.5f && framing.panY in -1.5f..1.5f)
        require(framing.cropLeft in 0f..1f && framing.cropTop in 0f..1f)
        require(framing.cropWidth > 0f && framing.cropWidth <= 1f)
        require(framing.cropHeight > 0f && framing.cropHeight <= 1f)
        require(framing.scrollAmountPercent in 0..200 && framing.scrollPages in 1..5)
        require(framing.blurIntensity in 0..100 && framing.gyroIntensity in 0..100)
        require(framing.backdropZoom in 0.1f..4f)
        require(framing.scrollStartFraction in 0f..1f && framing.scrollSpanFraction in 0.05f..1f)
    }

    private val SHA256 = Regex("[0-9a-fA-F]{64}")
}
