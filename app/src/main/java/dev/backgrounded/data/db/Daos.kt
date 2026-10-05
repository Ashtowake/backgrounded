package dev.backgrounded.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY sortIndex, id")
    fun observeAll(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :id")
    fun observe(id: Long): Flow<AlbumEntity?>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun get(id: Long): AlbumEntity?

    @Insert
    suspend fun insert(entity: AlbumEntity): Long

    @Update
    suspend fun update(entity: AlbumEntity)

    @Delete
    suspend fun delete(entity: AlbumEntity)

    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "UPDATE albums SET lastAppliedBackgroundId = :backgroundId, lastChangedAt = :changedAt, " +
            "shuffleRemainingCsv = :shuffleBag WHERE id = :albumId",
    )
    suspend fun updateRotationState(
        albumId: Long,
        backgroundId: Long?,
        changedAt: Long,
        shuffleBag: String?,
    )

    @Query("SELECT COALESCE(MAX(sortIndex), -1) + 1 FROM albums")
    suspend fun nextSortIndex(): Int

    @Query("DELETE FROM albums")
    suspend fun clear()

    @Query("UPDATE albums SET sortIndex = :sortIndex WHERE id = :id")
    suspend fun updateSortIndex(
        id: Long,
        sortIndex: Int,
    )
}

@Dao
interface BackgroundDao {
    @Query("SELECT * FROM backgrounds ORDER BY albumId, sortIndex, id")
    suspend fun listAll(): List<BackgroundEntity>

    @Query("SELECT * FROM backgrounds WHERE albumId = :albumId ORDER BY sortIndex, id")
    fun observeForAlbum(albumId: Long): Flow<List<BackgroundEntity>>

    @Query("SELECT * FROM backgrounds WHERE id = :id")
    fun observe(id: Long): Flow<BackgroundEntity?>

    @Query("SELECT * FROM backgrounds WHERE id = :id")
    suspend fun get(id: Long): BackgroundEntity?

    @Query("SELECT * FROM backgrounds WHERE id IN (:ids)")
    fun observeForIds(ids: List<Long>): Flow<List<BackgroundEntity>>

    @Query("SELECT * FROM backgrounds WHERE id IN (:ids)")
    suspend fun listForIds(ids: List<Long>): List<BackgroundEntity>

    @Query("SELECT storageRef FROM backgrounds")
    suspend fun allStorageRefs(): List<String>

    @Query("SELECT * FROM backgrounds WHERE albumId = :albumId ORDER BY sortIndex, id")
    suspend fun listForAlbum(albumId: Long): List<BackgroundEntity>

    @Query("SELECT COUNT(*) FROM backgrounds WHERE albumId = :albumId")
    suspend fun countForAlbum(albumId: Long): Int

    @Query("SELECT * FROM backgrounds WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findByHash(sha256: String): BackgroundEntity?

    @Insert
    suspend fun insert(entity: BackgroundEntity): Long

    @Update
    suspend fun update(entity: BackgroundEntity)

    @Delete
    suspend fun delete(entity: BackgroundEntity)

    @Query("SELECT COALESCE(MAX(sortIndex), -1) + 1 FROM backgrounds WHERE albumId = :albumId")
    suspend fun nextSortIndex(albumId: Long): Int

    @Query("DELETE FROM backgrounds")
    suspend fun clear()

    @Query("UPDATE backgrounds SET sortIndex = :sortIndex WHERE id = :id")
    suspend fun updateSortIndex(
        id: Long,
        sortIndex: Int,
    )
}

@Dao
interface BackgroundPairDao {
    @Query("SELECT * FROM background_pairs WHERE albumId = :albumId ORDER BY sortIndex, id")
    fun observeForAlbum(albumId: Long): Flow<List<BackgroundPairEntity>>

    @Query("SELECT * FROM background_pairs WHERE id = :id")
    fun observe(id: Long): Flow<BackgroundPairEntity?>

    @Query("SELECT * FROM background_pairs WHERE id = :id")
    suspend fun get(id: Long): BackgroundPairEntity?

    @Query("SELECT * FROM background_pairs WHERE albumId = :albumId ORDER BY sortIndex, id")
    suspend fun listForAlbum(albumId: Long): List<BackgroundPairEntity>

    @Query("SELECT COUNT(*) FROM background_pairs WHERE homeBackgroundId = :id OR lockBackgroundId = :id")
    suspend fun countForBackground(id: Long): Int

    @Insert
    suspend fun insert(entity: BackgroundPairEntity): Long

    @Update
    suspend fun update(entity: BackgroundPairEntity)

    @Delete
    suspend fun delete(entity: BackgroundPairEntity)

    @Query("DELETE FROM background_pairs WHERE albumId = :albumId")
    suspend fun deleteForAlbum(albumId: Long)

    @Query("SELECT COALESCE(MAX(sortIndex), -1) + 1 FROM background_pairs WHERE albumId = :albumId")
    suspend fun nextSortIndex(albumId: Long): Int

    @Query("DELETE FROM background_pairs")
    suspend fun clear()

    @Query("UPDATE background_pairs SET sortIndex = :sortIndex WHERE id = :id")
    suspend fun updateSortIndex(
        id: Long,
        sortIndex: Int,
    )
}

@Dao
interface FramingDao {
    @Query("SELECT * FROM background_framings WHERE backgroundId = :backgroundId")
    fun observeForBackground(backgroundId: Long): Flow<List<BackgroundFramingEntity>>

    @Query("SELECT * FROM background_framings WHERE backgroundId = :backgroundId")
    suspend fun listForBackground(backgroundId: Long): List<BackgroundFramingEntity>

    @Query("SELECT * FROM background_framings WHERE backgroundId IN (:backgroundIds)")
    suspend fun listForBackgrounds(backgroundIds: List<Long>): List<BackgroundFramingEntity>

    @Query("SELECT * FROM background_framings WHERE backgroundId IN (:backgroundIds)")
    fun observeForBackgrounds(backgroundIds: List<Long>): Flow<List<BackgroundFramingEntity>>

    @Query(
        "SELECT f.* FROM background_framings f " +
            "INNER JOIN backgrounds b ON b.id = f.backgroundId WHERE b.albumId = :albumId",
    )
    fun observeForAlbum(albumId: Long): Flow<List<BackgroundFramingEntity>>

    @Upsert
    suspend fun upsertAll(entities: List<BackgroundFramingEntity>)

    @Query("DELETE FROM background_framings WHERE backgroundId = :backgroundId")
    suspend fun deleteForBackground(backgroundId: Long)

    @Query("DELETE FROM background_framings")
    suspend fun clear()
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Query("SELECT * FROM history ORDER BY appliedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE albumId = :albumId ORDER BY appliedAt DESC, id DESC LIMIT :limit")
    suspend fun recentForAlbum(
        albumId: Long,
        limit: Int,
    ): List<HistoryEntity>

    @Query("DELETE FROM history WHERE appliedAt < :before")
    suspend fun prune(before: Long)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface LinkedFolderDao {
    @Query("SELECT * FROM linked_folders WHERE albumId = :albumId ORDER BY id")
    suspend fun forAlbum(albumId: Long): List<LinkedFolderEntity>

    @Query("SELECT * FROM linked_folders WHERE albumId = :albumId ORDER BY id")
    fun observeForAlbum(albumId: Long): Flow<List<LinkedFolderEntity>>

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insert(entity: LinkedFolderEntity): Long

    @Query("UPDATE linked_folders SET lastScanAt = :at WHERE id = :id")
    suspend fun markScanned(
        id: Long,
        at: Long,
    )

    @Query("DELETE FROM linked_folders WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ManagedSourceDao {
    @Query("SELECT * FROM managed_sources WHERE assetId = :assetId")
    suspend fun get(assetId: Long): ManagedSourceEntity?

    @Query("SELECT m.* FROM managed_sources m INNER JOIN backgrounds b ON b.id = m.assetId WHERE b.albumId = :albumId")
    suspend fun forAlbum(albumId: Long): List<ManagedSourceEntity>

    @Query(
        "SELECT m.* FROM managed_sources m INNER JOIN backgrounds b ON b.id = m.assetId " +
            "INNER JOIN albums a ON a.id = b.albumId " +
            "WHERE m.moveState NOT IN ('MOVED', 'MOVED_IMPORT', 'MOVED_FILE') " +
            "OR a.isHidden = 0 " +
            "ORDER BY m.originalName",
    )
    fun observeUnresolved(): Flow<List<ManagedSourceEntity>>

    @Upsert
    suspend fun upsert(entity: ManagedSourceEntity)

    @Query("DELETE FROM managed_sources WHERE assetId = :assetId")
    suspend fun delete(assetId: Long)
}

@Dao
interface EncryptedAssetDao {
    @Query("SELECT * FROM encrypted_assets WHERE assetId = :assetId")
    suspend fun get(assetId: Long): EncryptedAssetEntity?

    @Upsert
    suspend fun upsert(entity: EncryptedAssetEntity)

    @Query("DELETE FROM encrypted_assets WHERE assetId = :assetId")
    suspend fun delete(assetId: Long)
}
