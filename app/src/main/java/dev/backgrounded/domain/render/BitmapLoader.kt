package dev.backgrounded.domain.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

@Singleton
class BitmapLoader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        suspend fun decode(
            background: Background,
            targetWidth: Int,
            targetHeight: Int,
        ): Bitmap? =
            withContext(Dispatchers.IO) {
                val source = source(background.storageRef, background.sourceType) ?: return@withContext null
                runCatching {
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val width = info.size.width
                        val height = info.size.height
                        if (width > 0 && height > 0 && targetWidth > 0 && targetHeight > 0) {
                            val scale =
                                min(
                                    targetWidth.toFloat() / width,
                                    targetHeight.toFloat() / height,
                                )
                            if (scale < 1f) {
                                decoder.setTargetSize(
                                    max(1, (width * scale).toInt()),
                                    max(1, (height * scale).toInt()),
                                )
                            }
                        }
                    }
                }.getOrNull()
            }

        suspend fun decodeThumbnail(
            background: Background,
            size: Int,
        ): Bitmap? = decode(background, size, size)

        suspend fun exists(background: Background): Boolean =
            withContext(Dispatchers.IO) {
                when (background.sourceType) {
                    SourceType.IMPORT -> File(background.storageRef).isFile
                    SourceType.SAF_LINK ->
                        runCatching {
                            context.contentResolver.openAssetFileDescriptor(Uri.parse(background.storageRef), "r")
                                ?.use { true } ?: false
                        }.getOrDefault(false)
                }
            }

        private fun source(
            storageRef: String,
            sourceType: SourceType,
        ): ImageDecoder.Source? =
            when (sourceType) {
                SourceType.IMPORT -> File(storageRef).takeIf { it.isFile }?.let { ImageDecoder.createSource(it) }
                SourceType.SAF_LINK ->
                    runCatching {
                        ImageDecoder.createSource(context.contentResolver, Uri.parse(storageRef))
                    }.getOrNull()
            }
    }
