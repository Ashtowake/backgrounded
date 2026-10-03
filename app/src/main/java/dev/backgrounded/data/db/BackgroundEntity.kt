package dev.backgrounded.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "backgrounds",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("albumId"), Index("sha256")],
)
data class BackgroundEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val albumId: Long,
    val sourceType: String,
    val storageRef: String,
    val displayName: String,
    val sha256: String?,
    val width: Int,
    val height: Int,
    val dimForLock: Boolean,
    val sortIndex: Int,
    val addedAt: Long,
)
