package dev.backgrounded.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "coverBackgroundId") val coverPairId: Long?,
    val fixedHomeAssetId: Long?,
    val fixedLockAssetId: Long?,
    val isHidden: Boolean,
    val rotationOrder: String,
    val scheduleType: String,
    val intervalMinutes: Int?,
    val fixedTimesCsv: String?,
    val unlockEnabled: Boolean,
    val unlockMinMinutes: Int,
    val unlockEveryN: Int,
    val unlockMaxPerDay: Int,
    @ColumnInfo(name = "lastAppliedBackgroundId") val lastAppliedPairId: Long?,
    val lastChangedAt: Long,
    val shuffleRemainingCsv: String?,
    val sortIndex: Int,
)
