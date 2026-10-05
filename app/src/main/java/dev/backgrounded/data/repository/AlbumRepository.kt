package dev.backgrounded.data.repository

import androidx.room.withTransaction
import dev.backgrounded.data.db.AlbumEntity
import dev.backgrounded.data.db.BackgroundEntity
import dev.backgrounded.data.db.BackgroundPairEntity
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.db.framingEntities
import dev.backgrounded.data.db.toEntity
import dev.backgrounded.data.db.toModel
import dev.backgrounded.data.importer.ImageStore
import dev.backgrounded.domain.model.Album
import dev.backgrounded.domain.model.Background
import dev.backgrounded.domain.model.BackgroundPair
import dev.backgrounded.domain.model.RotationOrder
import dev.backgrounded.domain.model.ScheduleType
import dev.backgrounded.domain.model.UnlockPolicy
import dev.backgrounded.domain.model.WallpaperSurface
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlbumRepository
    @Inject
    constructor(
        private val db: BackgroundedDatabase,
        private val imageStore: ImageStore,
    ) {
        fun observeAlbums(): Flow<List<Album>> =
            db.albumDao().observeAll().map {
                    albums ->
                albums.map { it.toModel() }
            }

        fun observeAlbum(id: Long): Flow<Album?> = db.albumDao().observe(id).map { it?.toModel() }

        fun observePairs(albumId: Long): Flow<List<BackgroundPair>> =
            combine(
                db.pairDao().observeForAlbum(albumId),
                db.backgroundDao().observeForAlbum(albumId),
                db.framingDao().observeForAlbum(albumId),
            ) { pairs, backgrounds, framings ->
                val images = framedImages(backgrounds, framings)
                pairs.mapNotNull { pair -> pair.toModel(images) }
            }

        fun observeAssets(albumId: Long): Flow<List<Background>> =
            combine(db.backgroundDao().observeForAlbum(albumId), db.framingDao().observeForAlbum(albumId)) {
                    backgrounds, framings ->
                framedImages(backgrounds, framings).values.toList()
            }

        fun observeResolvedPairs(albumId: Long): Flow<List<BackgroundPair>> =
            combine(observePairs(albumId), observeAlbum(albumId)) { pairs, album ->
                pairs.map { assignFixedAssets(it, album) }
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        fun observePair(id: Long): Flow<BackgroundPair?> =
            db.pairDao().observe(id).flatMapLatest { entity ->
                if (entity == null) {
                    flowOf(null)
                } else {
                    val ids = listOf(entity.homeBackgroundId, entity.lockBackgroundId)
                    combine(
                        db.backgroundDao().observeForIds(ids),
                        db.framingDao().observeForBackgrounds(ids),
                    ) { backgrounds, framings ->
                        entity.toModel(framedImages(backgrounds, framings))
                    }
                }
            }

        /** Pair with the album's fixed home/lock overrides applied when set. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun observeResolvedPair(pairId: Long): Flow<BackgroundPair?> =
            observePair(pairId).flatMapLatest { pair ->
                if (pair == null) {
                    flowOf(null)
                } else {
                    combine(observeAlbum(pair.albumId), flowOf(pair)) { album, current ->
                        assignFixedAssets(current, album)
                    }
                }
            }

        suspend fun resolvedPair(pairId: Long): BackgroundPair? {
            val pair = getPair(pairId) ?: return null
            return assignFixedAssets(pair, getAlbum(pair.albumId))
        }

        suspend fun setFixedAsset(
            albumId: Long,
            surface: WallpaperSurface,
            assetId: Long?,
        ) {
            val before = getAlbum(albumId)
            update(albumId) {
                when (surface) {
                    WallpaperSurface.HOME -> it.copy(fixedHomeAssetId = assetId)
                    WallpaperSurface.LOCK -> it.copy(fixedLockAssetId = assetId)
                }
            }
            val previous = if (surface == WallpaperSurface.HOME) before?.fixedHomeAssetId else before?.fixedLockAssetId
            if (previous != null && previous != assetId) deleteAssetIfUnreferenced(previous)
        }

        private suspend fun assignFixedAssets(
            pair: BackgroundPair,
            album: Album?,
        ): BackgroundPair {
            val home = album?.fixedHomeAssetId?.let { getAsset(it) } ?: pair.home
            val lock = album?.fixedLockAssetId?.let { getAsset(it) } ?: pair.lock
            return pair.copy(home = home, lock = lock)
        }

        suspend fun getAlbum(id: Long): Album? = db.albumDao().get(id)?.toModel()

        suspend fun getPair(id: Long): BackgroundPair? {
            val entity = db.pairDao().get(id) ?: return null
            val ids = listOf(entity.homeBackgroundId, entity.lockBackgroundId)
            val images =
                framedImages(
                    db.backgroundDao().listForIds(ids),
                    db.framingDao().listForBackgrounds(ids),
                )
            return entity.toModel(images)
        }

        suspend fun getAsset(id: Long): Background? {
            val entity = db.backgroundDao().get(id) ?: return null
            return entity.toModel(db.framingDao().listForBackground(entity.id))
        }

        suspend fun pairsFor(albumId: Long): List<BackgroundPair> {
            val entities = db.pairDao().listForAlbum(albumId)
            if (entities.isEmpty()) return emptyList()
            val backgroundIds = entities.flatMap { listOf(it.homeBackgroundId, it.lockBackgroundId) }.distinct()
            val images =
                framedImages(
                    db.backgroundDao().listForIds(backgroundIds),
                    db.framingDao().listForBackgrounds(backgroundIds),
                )
            return entities.mapNotNull { pair -> pair.toModel(images) }
        }

        suspend fun firstVisibleAlbumId(): Long? =
            observeAlbums().first().firstOrNull { !it.isHidden && it.rotationEnabled }?.id

        suspend fun createAlbum(name: String): Long {
            val entity =
                AlbumEntity(
                    name = name,
                    coverPairId = null,
                    fixedHomeAssetId = null,
                    fixedLockAssetId = null,
                    isHidden = false,
                    rotationOrder = RotationOrder.SEQUENTIAL.name,
                    scheduleType = ScheduleType.NONE.name,
                    intervalMinutes = null,
                    fixedTimesCsv = null,
                    unlockEnabled = false,
                    unlockMinMinutes = 5,
                    unlockEveryN = 1,
                    unlockMaxPerDay = 20,
                    lastAppliedPairId = null,
                    lastChangedAt = 0L,
                    shuffleRemainingCsv = null,
                    sortIndex = db.albumDao().nextSortIndex(),
                )
            return db.albumDao().insert(entity)
        }

        suspend fun renameAlbum(
            id: Long,
            name: String,
        ) = update(id) { it.copy(name = name) }

        suspend fun setHidden(
            id: Long,
            hidden: Boolean,
        ) = update(id) { it.copy(isHidden = hidden) }

        suspend fun setRotationOrder(
            id: Long,
            order: RotationOrder,
        ) = update(id) { it.copy(rotationOrder = order.name, shuffleRemainingCsv = null) }

        suspend fun setRotationEnabled(
            id: Long,
            enabled: Boolean,
        ) = update(id) { it.copy(rotationEnabled = enabled) }

        suspend fun setCrossfade(
            id: Long,
            enabled: Boolean,
            durationMs: Int,
        ) = update(id) {
            it.copy(crossfadeEnabled = enabled, crossfadeDurationMs = durationMs.coerceIn(100, 3000))
        }

        suspend fun setSlideOptions(
            id: Long,
            mode: dev.backgrounded.domain.model.SlideMode,
            speedPxPerSecond: Float,
        ) = update(id) {
            it.copy(
                slideMode = mode.name,
                slideSpeedPxPerSecond = speedPxPerSecond.coerceIn(0.1f, 120f),
            )
        }

        suspend fun setSchedule(
            id: Long,
            type: ScheduleType,
            intervalSeconds: Int?,
            fixedTimes: List<LocalTime>,
        ) = update(id) {
            it.copy(
                scheduleType = type.name,
                intervalMinutes = intervalSeconds?.div(60)?.takeIf { it > 0 },
                intervalSeconds = intervalSeconds,
                fixedTimesCsv = fixedTimes.joinToString(separator = ",") { time -> time.toString() },
            )
        }

        suspend fun setUnlockPolicy(
            id: Long,
            policy: UnlockPolicy,
        ) = update(id) {
            it.copy(
                unlockEnabled = policy.enabled,
                unlockMinMinutes = policy.minMinutes,
                unlockEveryN = policy.everyN,
                unlockMaxPerDay = policy.maxPerDay,
            )
        }

        suspend fun setCover(
            albumId: Long,
            pairId: Long?,
        ) = update(albumId) {
            it.copy(coverPairId = pairId)
        }

        suspend fun updateRotationState(
            albumId: Long,
            pairId: Long?,
            changedAt: Long,
            shuffleBag: List<Long>,
        ) = db.albumDao().updateRotationState(
            albumId = albumId,
            backgroundId = pairId,
            changedAt = changedAt,
            shuffleBag = shuffleBag.joinToString(separator = ","),
        )

        /** Inserts an image asset and creates a pair that uses it for both surfaces. */
        suspend fun addPair(
            albumId: Long,
            image: Background,
        ): Long {
            val assetId = insertAsset(albumId, image)
            val pairEntity =
                BackgroundPairEntity(
                    albumId = albumId,
                    homeBackgroundId = assetId,
                    lockBackgroundId = assetId,
                    sortIndex = db.pairDao().nextSortIndex(albumId),
                    addedAt = System.currentTimeMillis(),
                )
            val pairId = db.pairDao().insert(pairEntity)
            val album = db.albumDao().get(albumId)
            if (album != null && album.coverPairId == null) {
                db.albumDao().update(album.copy(coverPairId = pairId))
            }
            return pairId
        }

        suspend fun copyPair(
            targetAlbumId: Long,
            sourcePair: BackgroundPair,
            home: Background,
            lock: Background,
        ): Long? {
            val target = db.albumDao().get(targetAlbumId) ?: return null
            val source = db.albumDao().get(sourcePair.albumId) ?: return null
            if (source.isHidden && !target.isHidden) return null
            return db.withTransaction {
                val homeId = insertAsset(targetAlbumId, home)
                val lockId = if (sourcePair.home.id == sourcePair.lock.id) homeId else insertAsset(targetAlbumId, lock)
                val pairId =
                    db.pairDao().insert(
                        BackgroundPairEntity(
                            albumId = targetAlbumId,
                            homeBackgroundId = homeId,
                            lockBackgroundId = lockId,
                            sortIndex = db.pairDao().nextSortIndex(targetAlbumId),
                            addedAt = System.currentTimeMillis(),
                        ),
                    )
                if (target.coverPairId == null) db.albumDao().update(target.copy(coverPairId = pairId))
                pairId
            }
        }

        suspend fun addStandaloneAsset(
            albumId: Long,
            image: Background,
        ): Long = insertAsset(albumId, image)

        /** Replaces one slot of a pair with a newly inserted image asset. */
        suspend fun setPairImage(
            pairId: Long,
            surface: WallpaperSurface,
            image: Background,
        ): Boolean {
            val pair = db.pairDao().get(pairId) ?: return false
            val oldAssetId =
                when (surface) {
                    WallpaperSurface.HOME -> pair.homeBackgroundId
                    WallpaperSurface.LOCK -> pair.lockBackgroundId
                }
            if (db.managedSourceDao().get(oldAssetId) != null) return false
            val assetId = insertAsset(pair.albumId, image)
            db.pairDao().update(
                when (surface) {
                    WallpaperSurface.HOME -> pair.copy(homeBackgroundId = assetId)
                    WallpaperSurface.LOCK -> pair.copy(lockBackgroundId = assetId)
                },
            )
            deleteAssetIfUnreferenced(oldAssetId)
            return true
        }

        suspend fun updateAsset(background: Background) {
            db.backgroundDao().update(background.toEntity())
            db.framingDao().upsertAll(background.framingEntities())
        }

        suspend fun findAssetByHash(sha256: String): Background? {
            val entity = db.backgroundDao().findByHash(sha256) ?: return null
            return entity.toModel(db.framingDao().listForBackground(entity.id))
        }

        suspend fun deletePair(pairId: Long): Boolean {
            val pair = db.pairDao().get(pairId) ?: return false
            if (listOf(pair.homeBackgroundId, pair.lockBackgroundId).any {
                    db.managedSourceDao().get(it) != null
                }
            ) {
                return false
            }
            db.pairDao().delete(pair)
            deleteAssetIfUnreferenced(pair.homeBackgroundId)
            deleteAssetIfUnreferenced(pair.lockBackgroundId)
            val album = db.albumDao().get(pair.albumId)
            if (album?.coverPairId == pairId) {
                val next = db.pairDao().listForAlbum(pair.albumId).firstOrNull()
                db.albumDao().update(album.copy(coverPairId = next?.id))
            }
            return true
        }

        suspend fun deleteAlbum(id: Long): Boolean {
            if (db.managedSourceDao().forAlbum(id).isNotEmpty()) return false
            val refs = db.backgroundDao().listForAlbum(id).map { it.storageRef }.distinct()
            db.albumDao().deleteById(id)
            val referenced = db.backgroundDao().allStorageRefs().toSet()
            refs.forEach { ref -> imageStore.deleteIfUnreferenced(ref, referenced) }
            return true
        }

        suspend fun reorderPairs(orderedIds: List<Long>) {
            db.withTransaction {
                orderedIds.forEachIndexed { index, id -> db.pairDao().updateSortIndex(id, index) }
            }
        }

        suspend fun discardUnreferencedImport(storageRef: String) {
            imageStore.deleteIfUnreferenced(storageRef, db.backgroundDao().allStorageRefs().toSet())
        }

        private suspend fun insertAsset(
            albumId: Long,
            image: Background,
        ): Long {
            val entity =
                image.copy(
                    id = 0,
                    albumId = albumId,
                    sortIndex = db.backgroundDao().nextSortIndex(albumId),
                ).toEntity()
            val assetId = db.backgroundDao().insert(entity)
            db.framingDao().upsertAll(image.copy(id = assetId).framingEntities())
            return assetId
        }

        private suspend fun deleteAssetIfUnreferenced(assetId: Long) {
            if (db.pairDao().countForBackground(assetId) > 0) return
            if (db.managedSourceDao().get(assetId) != null) return
            val asset = db.backgroundDao().get(assetId) ?: return
            val album = db.albumDao().get(asset.albumId)
            if (album?.fixedHomeAssetId == assetId || album?.fixedLockAssetId == assetId) return
            db.backgroundDao().delete(asset)
            imageStore.deleteIfUnreferenced(
                storageRef = asset.storageRef,
                referenced = db.backgroundDao().allStorageRefs().toSet(),
            )
        }

        private fun framedImages(
            backgrounds: List<BackgroundEntity>,
            framings: List<dev.backgrounded.data.db.BackgroundFramingEntity>,
        ): Map<Long, Background> =
            backgrounds.associate { entity ->
                entity.id to entity.toModel(framings.filter { it.backgroundId == entity.id })
            }

        private suspend fun update(
            id: Long,
            transform: (AlbumEntity) -> AlbumEntity,
        ) {
            val entity = db.albumDao().get(id) ?: return
            db.albumDao().update(transform(entity))
        }
    }
