package dev.backgrounded.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "encrypted_assets",
    foreignKeys = [
        ForeignKey(
            entity = BackgroundEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EncryptedAssetEntity(
    @PrimaryKey val assetId: Long,
    val formatVersion: Int,
    val wrappedKey: ByteArray,
    val keyNonce: ByteArray,
)
