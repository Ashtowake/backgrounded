package dev.backgrounded.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.backgrounded.data.repository.AlbumRepository
import dev.backgrounded.data.repository.HistoryRepository
import dev.backgrounded.domain.model.HistoryEntry
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.usecase.ApplyPair
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryRow(
    val entry: HistoryEntry,
    val albumName: String,
)

@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        historyRepository: HistoryRepository,
        albumRepository: AlbumRepository,
        private val applyPair: ApplyPair,
    ) : ViewModel() {
        val rows: StateFlow<List<HistoryRow>> =
            combine(
                historyRepository.observeRecent(HISTORY_LIMIT),
                albumRepository.observeAlbums(),
            ) { history, albums ->
                val names = albums.associate { it.id to it.name }
                history.map { entry -> HistoryRow(entry, names[entry.albumId].orEmpty()) }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        fun apply(pairId: Long) {
            viewModelScope.launch { applyPair(pairId, Trigger.MANUAL) }
        }

        private companion object {
            const val HISTORY_LIMIT = 50
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
