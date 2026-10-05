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
    @ColumnInfo(defaultValue = "1") val rotationEnabled: Boolean = true,
    val intervalSeconds: Int? = null,
    // Legacy columns from schema 10; retained so existing databases migrate without rebuilding album foreign keys.
    @ColumnInfo(defaultValue = "'CROSSFADE'") val intervalTransition: String = "CROSSFADE",
    @ColumnInfo(defaultValue = "800") val intervalTransitionMs: Int = 800,
    @ColumnInfo(defaultValue = "'OFF'") val slideMode: String = "OFF",
    @ColumnInfo(defaultValue = "10.0") val slideSpeedPxPerSecond: Float = 10f,
    @ColumnInfo(defaultValue = "1") val crossfadeEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "800") val crossfadeDurationMs: Int = 800,
)
