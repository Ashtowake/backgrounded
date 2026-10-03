package dev.backgrounded.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "history",
    indices = [Index("albumId"), Index("appliedAt")],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "backgroundId") val pairId: Long,
    val albumId: Long,
    val appliedAt: Long,
    val trigger: String,
)
