package dev.backgrounded.domain.rotation

import androidx.room.withTransaction
import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.data.db.BackgroundedDatabase
import dev.backgrounded.data.db.HistoryEntity
import dev.backgrounded.data.db.PlaybackCommitEntity
import dev.backgrounded.domain.model.CurrentWallpaper
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.state.WallpaperBus
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RotationResult {
    data object Applied : RotationResult

    data object Unavailable : RotationResult

    data class AuthenticationRequired(val albumId: Long) : RotationResult

    data object Locked : RotationResult

    data object Busy : RotationResult
}

@Singleton
class RotationCoordinator
    @Inject
    constructor(
        private val db: BackgroundedDatabase,
        private val settingsStore: SettingsStore,
        private val bus: WallpaperBus,
        @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    ) {
        private val mutex = Mutex()
        private val waiting = AtomicInteger()
        private val owner = ThreadLocal<Boolean>()
        private val automaticPending = java.util.concurrent.atomic.AtomicBoolean()
        private var historyChecked = false

        suspend fun <T> run(
            busy: T,
            automatic: Boolean = false,
            operation: suspend () -> T,
        ): T {
            if (owner.get() == true) return operation()
            if (automatic && !automaticPending.compareAndSet(false, true)) return busy
            if (waiting.incrementAndGet() > 32) {
                waiting.decrementAndGet()
                if (automatic) automaticPending.set(false)
                if (!automatic) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            context,
                            "Wallpaper busy",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                settingsStore.setLastError("Wallpaper busy")
                return busy
            }
            try {
                return mutex.withLock {
                    withContext(owner.asContextElement(true)) {
                        recover()
                        val result = operation()
                        if (result == RotationResult.Locked) {
                            settingsStore.setLastError("Hidden images are locked. Authenticate to resume.")
                        } else if (result == RotationResult.Unavailable && !automatic) {
                            settingsStore.setLastError("No usable wallpaper pair")
                        }
                        result
                    }
                }
            } finally {
                if (automatic) automaticPending.set(false)
                waiting.decrementAndGet()
            }
        }

        suspend fun recover() {
            db.hardeningDao().operations().filter { it.kind == "CONFIG" }.forEach { operation ->
                val backup =
                    kotlinx.serialization.json.Json.decodeFromString<dev.backgrounded.data.backup.SettingsBackup?>(
                        operation.sourceRef,
                    )
                val ids = operation.destinationRef.split(",").mapNotNull { it.toLongOrNull() }
                settingsStore.restoreConfiguration(backup, ids)
                db.hardeningDao().finishOperation(operation.id)
            }
            if (!historyChecked) {
                if (db.historyDao().count() > 10000) db.historyDao().trim()
                historyChecked = true
            }
            val pending = db.hardeningDao().pendingPlayback() ?: return
            val unlock =
                pending.unlockCommit?.split(",")?.let {
                    dev.backgrounded.domain.model.UnlockState(
                        it[0].toLong(),
                        it[1].toInt(),
                        it[2].toInt(),
                        it[3].toLong(),
                    )
                }
            settingsStore.commitPlayback(pending.albumId, pending.pairId, pending.changedAt, pending.paused, unlock)
            db.hardeningDao().finishPlayback()
            bus.set(CurrentWallpaper(pending.pairId, pending.albumId, pending.changedAt))
        }

        suspend fun commit(
            albumId: Long,
            pairId: Long,
            changedAt: Long,
            shuffle: List<Long>,
            trigger: Trigger,
        ) {
            val settings = settingsStore.settings.first()
            val unlock =
                if (trigger == Trigger.UNLOCK || settings.pendingUnlock) {
                    dev.backgrounded.domain.unlock.UnlockPolicyEvaluator.stateAfterApply(
                        settings.unlockState,
                        changedAt,
                        java.time.LocalDate.now().toEpochDay(),
                    )
                } else {
                    null
                }
            db.withTransaction {
                require(db.pairDao().get(pairId)?.albumId == albumId)
                db.albumDao().updateRotationState(albumId, pairId, changedAt, shuffle.joinToString(","))
                db.historyDao().insert(
                    HistoryEntity(
                        pairId = pairId,
                        albumId = albumId,
                        appliedAt = changedAt,
                        trigger = trigger.name,
                    ),
                )
                if (db.historyDao().count() % 100 == 0) db.historyDao().trim()
                db.hardeningDao().recordPlayback(
                    PlaybackCommitEntity(
                        albumId = albumId,
                        pairId = pairId,
                        changedAt = changedAt,
                        paused = settings.rotationPaused,
                        unlockCommit =
                            unlock?.let {
                                listOf(
                                    it.lastAppliedAt,
                                    it.unlocksSinceApply.toLong(),
                                    it.appliedToday.toLong(),
                                    it.dayEpochDay,
                                ).joinToString(",")
                            },
                    ),
                )
            }
            recover()
        }

        suspend fun pause(): Boolean =
            run(false) {
                val settings = settingsStore.settings.first()
                val paused = !settings.rotationPaused
                db.hardeningDao().recordPlayback(
                    PlaybackCommitEntity(
                        albumId = settings.activeAlbumId,
                        pairId = settings.currentPairId,
                        changedAt = settings.currentChangedAt,
                        paused = paused,
                    ),
                )
                recover()
                paused
            }
    }
