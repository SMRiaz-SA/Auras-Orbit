package com.lagradost.cloudstream3.desktop.ui.screens.tracker

import androidx.compose.runtime.Immutable
import com.lagradost.cloudstream3.desktop.ui.base.UiEvent
import com.lagradost.cloudstream3.desktop.ui.base.UiState
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.library.ListSorting

@Immutable
data class TrackerProviderOption(
    val idPrefix: String,
    val name: String,
    val accountName: String,
    val capabilities: Set<SyncAPI.SyncMediaType>,
    val supportsExactEpisodes: Boolean,
    val supportsCountProgress: Boolean,
)

@Immutable
data class TrackerEditorState(
    val entry: TrackerLibraryEntry,
    val status: SyncWatchType,
    val score: String,
    val progress: String,
    val exactEpisodes: String,
    val exactEpisodesEnabled: Boolean,
    val saving: Boolean = false,
)

@Immutable
data class TrackerLibraryUiState(
    val providers: List<TrackerProviderOption> = emptyList(),
    val selectedProvider: String? = null,
    val entries: List<TrackerLibraryEntry> = emptyList(),
    val statusFilters: List<String> = emptyList(),
    val selectedStatus: String? = null,
    val searchQuery: String = "",
    val sorting: ListSorting = ListSorting.AlphabeticalA,
    val filteredEntries: List<TrackerLibraryEntry> = emptyList(),
    val searchResults: List<SyncAPI.SyncSearchResult> = emptyList(),
    val loading: Boolean = false,
    val searching: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val editor: TrackerEditorState? = null,
) : UiState

sealed interface TrackerLibraryUiEvent : UiEvent {
    data class SelectProvider(val idPrefix: String) : TrackerLibraryUiEvent
    data class SelectStatus(val status: String?) : TrackerLibraryUiEvent
    data class SearchQueryChanged(val value: String) : TrackerLibraryUiEvent
    data class Search(val query: String) : TrackerLibraryUiEvent
    data object Refresh : TrackerLibraryUiEvent
    data class OpenEditor(val entry: TrackerLibraryEntry) : TrackerLibraryUiEvent
    data object CloseEditor : TrackerLibraryUiEvent
    data class SetEditorStatus(val status: SyncWatchType) : TrackerLibraryUiEvent
    data class SetEditorScore(val value: String) : TrackerLibraryUiEvent
    data class SetEditorProgress(val value: String) : TrackerLibraryUiEvent
    data class SetEditorEpisodes(val value: String) : TrackerLibraryUiEvent
    data object SaveEditor : TrackerLibraryUiEvent
    data object ClearMessage : TrackerLibraryUiEvent
}
