package dev.backgrounded.ui.album

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.backgrounded.core.display.DisplayRepository
import dev.backgrounded.core.image.ComposedPreviewCache
import dev.backgrounded.core.image.ThumbnailCache
import dev.backgrounded.core.security.EncryptedImageStore
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.importer.ImageImporter
import dev.backgrounded.data.importer.ImportedImage
import dev.backgrounded.data.importer.LinkedFolderScanner
import dev.backgrounded.data.importer.LinkedImage
import dev.backgrounded.data.importer.ManagedFolderStore
import dev.backgrounded.data.importer.ManagedSourceMover
import dev.backgrounded.data.importer.SafFolders
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.domain.model.Album
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.BackgroundPair
import dev.backgrounded.domain.model.DisplayTarget
import dev.backgrounded.domain.model.SourceType
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.model.WallpaperSurface
import dev.backgrounded.domain.usecase.ApplyPair
import dev.backgrounded.ui.nav.AlbumRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PairSource(val albumName: String, val pair: BackgroundPair)

@HiltViewModel
@Suppress("LongParameterList")
class AlbumViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        savedStateHandle: SavedStateHandle,
        private val albumRepository: AlbumRepository,
        private val imageImporter: ImageImporter,
        private val thumbnailCache: ThumbnailCache,
        private val previewCache: ComposedPreviewCache,
        private val displayRepository: DisplayRepository,
        private val settingsStore: SettingsStore,
        private val applyPair: ApplyPair,
        private val linkedFolderScanner: LinkedFolderScanner,
        private val managedFolderStore: ManagedFolderStore,
        private val managedSourceMover: ManagedSourceMover,
        private val encryptedImages: EncryptedImageStore,
    ) : ViewModel() {
        private val albumId: Long = savedStateHandle.toRoute<AlbumRoute>().albumId

        val album: StateFlow<Album?> =
            albumRepository.observeAlbum(albumId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

        val hideSourcesEnabled: StateFlow<Boolean> =
            settingsStore.settings.map { it.hideSourcesSystemwide }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

        val pairs: StateFlow<List<BackgroundPair>> =
            albumRepository.observeResolvedPairs(albumId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        private val mutableMessage = MutableStateFlow<Message?>(null)
        val message: StateFlow<Message?> = mutableMessage.asStateFlow()
        private val mutableFileError = MutableStateFlow<String?>(null)
        val fileError: StateFlow<String?> = mutableFileError.asStateFlow()
        private val mutablePairSources = MutableStateFlow<List<PairSource>>(emptyList())
        val pairSources: StateFlow<List<PairSource>> = mutablePairSources.asStateFlow()

        fun loadPairSources() {
            viewModelScope.launch {
                val targetHidden = albumRepository.getAlbum(albumId)?.isHidden == true
                mutablePairSources.value =
                    albumRepository.observeAlbums().first()
                        .filter { it.id != albumId && (targetHidden || !it.isHidden) }
                        .flatMap { sourceAlbum ->
                            albumRepository.pairsFor(sourceAlbum.id).map { pair ->
                                PairSource(sourceAlbum.name, pair)
                            }
                        }
            }
        }

        fun addExistingPair(pair: BackgroundPair) {
            viewModelScope.launch {
                val targetHidden = albumRepository.getAlbum(albumId)?.isHidden == true
                if (albumRepository.getAlbum(pair.albumId)?.isHidden == true && !targetHidden) return@launch
                val home = prepareCopy(pair.home) ?: return@launch
                val lock =
                    if (pair.home.id == pair.lock.id) {
                        home
                    } else {
                        prepareCopy(pair.lock) ?: run {
                            albumRepository.discardUnreferencedImport(home.storageRef)
                            return@launch
                        }
                    }
                val newId =
                    albumRepository.copyPair(albumId, pair, home, lock) ?: run {
                        albumRepository.discardUnreferencedImport(home.storageRef)
                        if (lock.storageRef != home.storageRef) {
                            albumRepository.discardUnreferencedImport(lock.storageRef)
                        }
                        return@launch
                    }
                if (targetHidden && settingsStore.settings.first().encryptHidden) {
                    val copied = albumRepository.getPair(newId) ?: return@launch
                    val ids = listOf(copied.home.id, copied.lock.id).distinct()
                    if (!ids.all { encryptedImages.encryptAsset(it) }) {
                        albumRepository.deletePair(newId)
                        mutableFileError.value = "Could not encrypt copied pair"
                        return@launch
                    }
                }
                thumbnailCache.evictAll()
            }
        }

        private suspend fun prepareCopy(source: Background): Background? {
            val input =
                when (source.sourceType) {
                    SourceType.IMPORT -> runCatching { java.io.File(source.storageRef).inputStream() }.getOrNull()
                    SourceType.SAF_LINK ->
                        runCatching {
                            context.contentResolver.openInputStream(Uri.parse(source.storageRef))
                        }.getOrNull()
                    SourceType.ENCRYPTED_IMPORT -> encryptedImages.openAsset(source.id)
                } ?: run {
                    mutableFileError.value =
                        if (source.sourceType == SourceType.ENCRYPTED_IMPORT) {
                            "Unlock hidden images before copying this pair"
                        } else {
                            "Could not read an image in this pair"
                        }
                    return null
                }
            val copied = imageImporter.importStream(input, source.displayName) ?: return null
            return source.copy(
                id = 0,
                albumId = albumId,
                sourceType = SourceType.IMPORT,
                storageRef = copied.filePath,
                sha256 = copied.sha256,
            )
        }

        fun currentDisplay(): DisplayTarget = displayRepository.activeTarget()

        fun displayAspect(target: DisplayTarget): Float {
            val info = displayRepository.targets().firstOrNull { it.target == target }
            return if (info != null && info.width > 0 && info.height > 0) {
                info.width.toFloat() / info.height
            } else {
                if (target == DisplayTarget.COVER) 0.46f else 0.96f
            }
        }

        fun preview(
            background: Background,
            surface: WallpaperSurface,
            target: DisplayTarget,
            aspect: Float,
        ): StateFlow<Bitmap?> = previewCache.observe(background, surface, target, aspect)

        fun addImages(uris: List<Uri>) {
            if (uris.isEmpty()) return
            viewModelScope.launch {
                val dimDefault = settingsStore.settings.first().lockDimDefault
                var added = 0
                var skipped = 0
                uris.forEach { uri ->
                    val imported = imageImporter.import(uri)
                    if (imported == null) {
                        skipped++
                    } else {
                        val pairId = albumRepository.addPair(albumId, imported.toAsset(dimDefault))
                        if (album.value?.isHidden == true && settingsStore.settings.first().encryptHidden) {
                            albumRepository.getPair(pairId)?.home?.id?.let { encryptedImages.encryptAsset(it) }
                        }
                        added++
                    }
                }
                mutableMessage.value = Message(added, skipped)
            }
        }

        @Suppress("TooGenericExceptionCaught") // Document providers may throw implementation-specific failures.
        fun importFolder(treeUri: Uri) {
            viewModelScope.launch {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val dim = settingsStore.settings.first().lockDimDefault
                        var added = 0
                        var skipped = 0
                        SafFolders.forEachImage(context, treeUri) { document ->
                            val imported = imageImporter.import(document.uri)
                            if (imported == null) {
                                skipped++
                            } else {
                                val pairId = albumRepository.addPair(albumId, imported.toAsset(dim))
                                if (album.value?.isHidden == true && settingsStore.settings.first().encryptHidden) {
                                    albumRepository.getPair(pairId)?.home?.id?.let { encryptedImages.encryptAsset(it) }
                                }
                                added++
                            }
                            mutableMessage.value = Message(added, skipped)
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    mutableFileError.value = "Folder import stopped: ${failure.message}"
                }
            }
        }

        fun addLinkedImages(uris: List<Uri>) {
            if (uris.isEmpty()) return
            viewModelScope.launch {
                val dimDefault = settingsStore.settings.first().lockDimDefault
                var added = 0
                var skipped = 0
                uris.forEach { uri ->
                    val linked = imageImporter.link(uri)
                    if (linked == null) {
                        skipped++
                    } else {
                        val pairId = albumRepository.addPair(albumId, linked.toAsset(dimDefault))
                        if (album.value?.isHidden == true && settingsStore.settings.first().encryptHidden) {
                            albumRepository.getPair(pairId)?.home?.id?.let { encryptedImages.encryptAsset(it) }
                        }
                        added++
                    }
                }
                mutableMessage.value = Message(added, skipped)
            }
        }

        fun linkFolder(uri: Uri) {
            if (!managedFolderStore.grant(uri)) {
                mutableMessage.value = Message(0, 1)
                return
            }
            viewModelScope.launch {
                val added = linkedFolderScanner.link(albumId, uri)
                mutableMessage.value = Message(added, 0)
            }
        }

        fun moveSelectedFromFolder(
            uris: List<Uri>,
            treeUri: Uri,
        ) {
            if (!managedFolderStore.grant(treeUri)) {
                mutableMessage.value = Message(0, uris.size)
                return
            }
            viewModelScope.launch {
                val candidates = SafFolders.listImages(context, treeUri)
                val dim = settingsStore.settings.first().lockDimDefault
                var added = 0
                var skipped = 0
                uris.forEach { selected ->
                    val picked = imageImporter.import(selected)
                    val source =
                        candidates.firstOrNull { candidate ->
                            picked != null &&
                                runCatching {
                                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                                    context.contentResolver.openInputStream(candidate)?.use { input ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            val read = input.read(buffer)
                                            if (read < 0) break
                                            digest.update(buffer, 0, read)
                                        }
                                        digest.digest().joinToString("") { "%02x".format(it) }
                                    } == picked.sha256
                                }.getOrDefault(false)
                        }
                    val linked = source?.let { imageImporter.link(it) }
                    if (linked == null) {
                        skipped++
                    } else {
                        val pairId = albumRepository.addPair(albumId, linked.toAsset(dim))
                        val assetId = albumRepository.getPair(pairId)?.home?.id
                        if (assetId != null && managedSourceMover.move(assetId, treeUri, source)) {
                            if (settingsStore.settings.first().encryptHidden) encryptedImages.encryptAsset(assetId)
                            added++
                        } else {
                            if (albumRepository.deletePair(pairId)) {
                                skipped++
                            } else {
                                added++
                                mutableFileError.value = "Source could not be removed; the private copy was retained"
                            }
                        }
                    }
                }
                mutableMessage.value = Message(added, skipped)
            }
        }

        fun moveSelectedWithFullAccess(uris: List<Uri>) {
            viewModelScope.launch {
                val dim = settingsStore.settings.first().lockDimDefault
                var added = 0
                var skipped = 0
                uris.forEach { selected ->
                    val imported = imageImporter.import(selected)
                    if (imported == null) {
                        skipped++
                        return@forEach
                    }
                    val pairId = albumRepository.addPair(albumId, imported.toAsset(dim))
                    val assetId = albumRepository.getPair(pairId)?.home?.id
                    if (assetId != null && managedSourceMover.moveAssetFromFullAccess(assetId)) {
                        if (settingsStore.settings.first().encryptHidden) encryptedImages.encryptAsset(assetId)
                        added++
                    } else if (albumRepository.deletePair(pairId)) {
                        skipped++
                    } else {
                        added++
                        mutableFileError.value = "Source could not be removed; the private copy was retained"
                    }
                }
                mutableMessage.value = Message(added, skipped)
            }
        }

        fun setSlotImage(
            pairId: Long,
            surface: WallpaperSurface,
            uri: Uri,
        ) {
            viewModelScope.launch {
                val dimDefault = settingsStore.settings.first().lockDimDefault
                val imported = imageImporter.import(uri) ?: return@launch
                if (!albumRepository.setPairImage(pairId, surface, imported.toAsset(dimDefault))) {
                    albumRepository.discardUnreferencedImport(imported.filePath)
                    mutableFileError.value = "Restore the moved source before replacing this image"
                    return@launch
                }
                encryptSlotIfNeeded(pairId, surface)
                thumbnailCache.evictAll()
            }
        }

        fun setSlotLinkedImage(
            pairId: Long,
            surface: WallpaperSurface,
            uri: Uri,
        ) {
            viewModelScope.launch {
                val dimDefault = settingsStore.settings.first().lockDimDefault
                val linked = imageImporter.link(uri) ?: return@launch
                if (!albumRepository.setPairImage(pairId, surface, linked.toAsset(dimDefault))) {
                    mutableFileError.value = "Restore the moved source before replacing this image"
                    return@launch
                }
                encryptSlotIfNeeded(pairId, surface)
                thumbnailCache.evictAll()
            }
        }

        private suspend fun encryptSlotIfNeeded(
            pairId: Long,
            surface: WallpaperSurface,
        ) {
            if (album.value?.isHidden != true || !settingsStore.settings.first().encryptHidden) return
            val pair = albumRepository.getPair(pairId) ?: return
            encryptedImages.encryptAsset(if (surface == WallpaperSurface.HOME) pair.home.id else pair.lock.id)
        }

        fun applyNow(pairId: Long) {
            viewModelScope.launch { applyPair(pairId, Trigger.MANUAL) }
        }

        fun setCover(pairId: Long) {
            viewModelScope.launch { albumRepository.setCover(albumId, pairId) }
        }

        fun deletePair(pairId: Long) {
            viewModelScope.launch {
                if (!albumRepository.deletePair(pairId)) {
                    mutableFileError.value = "Restore moved source photos before deleting this pair"
                    return@launch
                }
                thumbnailCache.evictAll()
            }
        }

        fun clearFileError() {
            mutableFileError.value = null
        }

        fun move(
            pairId: Long,
            delta: Int,
        ) {
            val current = pairs.value
            val index = current.indexOfFirst { it.id == pairId }
            val target = index + delta
            if (index < 0 || target !in current.indices) return
            val reordered = current.toMutableList().apply { add(target, removeAt(index)) }
            viewModelScope.launch { albumRepository.reorderPairs(reordered.map { it.id }) }
        }

        fun moveTo(
            pairId: Long,
            targetId: Long,
        ) {
            val ordered = pairs.value.toMutableList()
            val from = ordered.indexOfFirst { it.id == pairId }
            if (from < 0 || pairId == targetId) return
            val moved = ordered.removeAt(from)
            val to = ordered.indexOfFirst { it.id == targetId }
            if (to < 0) return
            ordered.add(to, moved)
            viewModelScope.launch { albumRepository.reorderPairs(ordered.map { it.id }) }
        }

        fun reorder(orderedIds: List<Long>) {
            if (orderedIds.toSet() != pairs.value.map { it.id }.toSet()) return
            viewModelScope.launch { albumRepository.reorderPairs(orderedIds) }
        }

        fun clearMessage() {
            mutableMessage.value = null
        }

        private fun ImportedImage.toAsset(dimDefault: Boolean): Background =
            asset(
                spec =
                    AssetSpec(
                        sourceType = SourceType.IMPORT,
                        storageRef = filePath,
                        displayName = displayName,
                        sha256 = sha256,
                        width = width,
                        height = height,
                        rotationDegrees = orientationDegrees,
                    ),
                dimDefault = dimDefault,
            )

        private fun LinkedImage.toAsset(dimDefault: Boolean): Background =
            asset(
                spec =
                    AssetSpec(
                        sourceType = SourceType.SAF_LINK,
                        storageRef = uri,
                        displayName = displayName,
                        sha256 = null,
                        width = width,
                        height = height,
                        rotationDegrees = orientationDegrees,
                    ),
                dimDefault = dimDefault,
            )

        private fun asset(
            spec: AssetSpec,
            dimDefault: Boolean,
        ): Background {
            val framings =
                Background.defaultFramings().mapValues { (_, framing) ->
                    framing.copy(rotationDegrees = spec.rotationDegrees)
                }
            return Background(
                id = 0,
                albumId = albumId,
                sourceType = spec.sourceType,
                storageRef = spec.storageRef,
                displayName = spec.displayName,
                sha256 = spec.sha256,
                width = spec.width,
                height = spec.height,
                dimForLock = dimDefault,
                sortIndex = 0,
                addedAt = System.currentTimeMillis(),
                framings = framings,
            )
        }

        private data class AssetSpec(
            val sourceType: SourceType,
            val storageRef: String,
            val displayName: String,
            val sha256: String?,
            val width: Int,
            val height: Int,
            val rotationDegrees: Int,
        )

        data class Message(
            val added: Int,
            val skipped: Int,
        )

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val PREVIEW_SOURCE_SIZE = 512
            const val PREVIEW_HEIGHT = 256
        }
    }
