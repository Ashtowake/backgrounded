package dev.backgrounded.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "scan_documents", primaryKeys = ["folderId", "documentId"])
data class ScanDocumentEntity(
    val folderId: Long,
    val documentId: String,
    val size: Long,
    val modifiedAt: Long,
    val status: String,
)

@Entity(tableName = "operation_journal", indices = [Index("assetId")])
data class OperationJournalEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val stage: String,
    val assetId: Long?,
    val sourceRef: String,
    val destinationRef: String,
    val sha256: String?,
    val wrappedKey: ByteArray?,
    val keyNonce: ByteArray?,
    val createdAt: Long,
)

@Entity(tableName = "playback_commit")
data class PlaybackCommitEntity(
    @PrimaryKey val id: Int = 1,
    val albumId: Long?,
    val pairId: Long?,
    val changedAt: Long,
    val paused: Boolean,
    val unlockCommit: String? = null,
)
