package dev.backgrounded.data.repository

import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.db.HistoryEntity
import dev.backgrounded.data.db.toModel
import dev.backgrounded.domain.model.HistoryEntry
import dev.backgrounded.domain.model.Trigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepository
    @Inject
    constructor(private val db: BackgroundedDatabase) {
        fun observeRecent(limit: Int = 50): Flow<List<HistoryEntry>> =
            db.historyDao().observeRecent(limit).map { entries -> entries.map { it.toModel() } }

        suspend fun record(
            pairId: Long,
            albumId: Long,
            appliedAt: Long,
            trigger: Trigger,
        ) {
            db.historyDao().insert(
                HistoryEntity(
                    pairId = pairId,
                    albumId = albumId,
                    appliedAt = appliedAt,
                    trigger = trigger.name,
                ),
            )
        }

        suspend fun recentForAlbum(
            albumId: Long,
            limit: Int = 10,
        ): List<HistoryEntry> = db.historyDao().recentForAlbum(albumId, limit).map { it.toModel() }

        suspend fun prune(olderThan: Long) = db.historyDao().prune(olderThan)
    }
