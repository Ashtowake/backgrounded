package dev.backgrounded.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "background_framings",
    primaryKeys = ["backgroundId", "target", "surface"],
    foreignKeys = [
        ForeignKey(
            entity = BackgroundEntity::class,
            parentColumns = ["id"],
            childColumns = ["backgroundId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("backgroundId")],
)
data class BackgroundFramingEntity(
    val backgroundId: Long,
    val target: String,
    val surface: String,
    val fitMode: String,
    val cropLeft: Float,
    val cropTop: Float,
    val cropWidth: Float,
    val cropHeight: Float,
    val zoom: Float,
    val panX: Float,
    val panY: Float,
    @ColumnInfo(defaultValue = "1.0") val stretchX: Float,
    @ColumnInfo(defaultValue = "1.0") val stretchY: Float,
    val rotationDegrees: Int,
    val backdrop: String,
    val blurIntensity: Int,
    val backdropZoom: Float,
    val backdropPanX: Float,
    val backdropPanY: Float,
    val backdropColor: Int,
    val scrollMode: String,
    val scrollAmountPercent: Int,
    val scrollPages: Int,
    val scrollStartFraction: Float,
    val scrollSpanFraction: Float,
    @ColumnInfo(defaultValue = "0") val gyroParallax: Boolean,
    @ColumnInfo(defaultValue = "50") val gyroIntensity: Int,
    @ColumnInfo(defaultValue = "0") val mirrorX: Boolean = false,
    @ColumnInfo(defaultValue = "0") val mirrorY: Boolean = false,
)
