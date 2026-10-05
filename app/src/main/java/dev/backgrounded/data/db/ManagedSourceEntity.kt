package dev.backgrounded.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "managed_sources",
    foreignKeys = [
        ForeignKey(
            entity = BackgroundEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("treeUri")],
)
data class ManagedSourceEntity(
    @PrimaryKey val assetId: Long,
    val treeUri: String,
    val documentId: String,
    val originalName: String,
    val sha256: String,
    val moveState: String,
)
