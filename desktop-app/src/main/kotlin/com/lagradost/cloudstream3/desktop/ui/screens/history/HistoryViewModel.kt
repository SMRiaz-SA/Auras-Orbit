package com.lagradost.cloudstream3.desktop.ui.screens.history

import com.lagradost.cloudstream3.desktop.data.history.WatchHistoryRepositoryImpl
import com.lagradost.cloudstream3.desktop.domain.history.interactor.GetContinueWatching
import com.lagradost.cloudstream3.desktop.domain.history.interactor.RemoveWatchHistory
import com.lagradost.cloudstream3.desktop.domain.history.repository.WatchHistoryRepository
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.history.contract.HistoryUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.history.contract.HistoryUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.history.contract.HistoryUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HistoryViewModel(
    watchHistoryRepo: WatchHistoryRepository = WatchHistoryRepositoryImpl(),
    private val removeWatchHistory: RemoveWatchHistory = RemoveWatchHistory(watchHistoryRepo),
) : BaseMviViewModel<HistoryUiState, HistoryUiEvent, HistoryUiEffect>(
    initialState = HistoryUiState(),
) {
    init {
        viewModelScope.launch {
            watchHistoryRepo.subscribeAll().collect { allHistory ->
                val continueWatching = GetContinueWatching.filterContinueWatching(allHistory)
                val networkStreams = allHistory.filter {
                    com.lagradost.cloudstream3.desktop.ui.GlobalMediaLauncher.isNetworkStreamHistory(it)
                }
                val list = (continueWatching + networkStreams)
                    .distinctBy { it.parentId to it.episodeId }
                    .sortedByDescending { it.updateTime }
                updateState { copy(historyList = list, isLoading = false) }
            }
        }
    }

    override fun handleEvent(event: HistoryUiEvent) {
        when (event) {
            is HistoryUiEvent.ClearAll -> {
                viewModelScope.launch(Dispatchers.IO) {
                    removeWatchHistory.clearAll()
                    sendEffect(HistoryUiEffect.ShowToast("Watch history cleared"))
                }
            }
            is HistoryUiEvent.RemoveItem -> {
                viewModelScope.launch(Dispatchers.IO) {
                    removeWatchHistory.awaitByParent(event.parentId)
                }
            }
        }
    }
}
