package com.lagradost.cloudstream3.desktop.ui.screens.tracker

import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.TrackerAccountAccess
import com.lagradost.cloudstream3.syncproviders.TrackerAuthResult
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TrackerLibraryViewModel : BaseMviViewModel<TrackerLibraryUiState, TrackerLibraryUiEvent, Nothing>(
    initialState = TrackerLibraryUiState(),
) {
    private val providers = listOf(AccountManager.malApi, AccountManager.aniListApi, AccountManager.simklApi)
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            AccountManager.accountsFlow.collectLatest { refreshProviderOptions() }
        }
    }

    override fun handleEvent(event: TrackerLibraryUiEvent) {
        when (event) {
            is TrackerLibraryUiEvent.SelectProvider -> {
                updateState { copy(selectedProvider = event.idPrefix, searchResults = emptyList(), error = null) }
                refresh()
            }
            is TrackerLibraryUiEvent.SelectStatus -> updateState { copy(selectedStatus = event.status).rebuildVisible() }
            is TrackerLibraryUiEvent.SearchQueryChanged -> updateState { copy(searchQuery = event.value, message = null).rebuildVisible() }
            is TrackerLibraryUiEvent.Search -> search(event.query)
            TrackerLibraryUiEvent.Refresh -> refresh()
            is TrackerLibraryUiEvent.OpenEditor -> openEditor(event.entry)
            TrackerLibraryUiEvent.CloseEditor -> updateState { copy(editor = null) }
            is TrackerLibraryUiEvent.SetEditorStatus -> updateEditor { copy(status = event.status) }
            is TrackerLibraryUiEvent.SetEditorScore -> updateEditor { copy(score = event.value) }
            is TrackerLibraryUiEvent.SetEditorProgress -> updateEditor { copy(progress = event.value) }
            is TrackerLibraryUiEvent.SetEditorEpisodes -> updateEditor { copy(exactEpisodes = event.value) }
            TrackerLibraryUiEvent.SaveEditor -> saveEditor()
            TrackerLibraryUiEvent.ClearMessage -> updateState { copy(message = null, error = null) }
        }
    }

    fun updateSorting(sorting: ListSorting) {
        updateState { copy(sorting = sorting).rebuildVisible() }
    }

    private fun refreshProviderOptions() {
        val previousSelected = uiState.value.selectedProvider
        val options = providers.mapNotNull { api ->
            AccountManager.accounts(api.idPrefix).firstOrNull()?.let { account ->
                TrackerProviderOption(
                    idPrefix = api.idPrefix,
                    name = api.name,
                    accountName = account.user.name ?: "Connected account",
                    capabilities = api.supportedMediaTypes,
                    supportsExactEpisodes = api.supportsExactEpisodeProgress,
                    supportsCountProgress = api.supportsCountBasedProgress,
                )
            }
        }
        val selected = uiState.value.selectedProvider?.takeIf { id -> options.any { it.idPrefix == id } }
            ?: options.firstOrNull()?.idPrefix
        updateState {
            copy(
                providers = options,
                selectedProvider = selected,
                entries = if (selected == null) emptyList() else entries,
            )
        }
        val selectedApiName = providers.firstOrNull { it.idPrefix == selected }?.name
        if (selected != null && (uiState.value.entries.isEmpty() || previousSelected != selected || uiState.value.entries.any { it.item.apiName != selectedApiName })) {
            refresh()
        }
    }

    private fun selectedApi(): SyncAPI? = providers.firstOrNull { it.idPrefix == uiState.value.selectedProvider }

    private suspend fun currentAccount(api: SyncAPI): AuthData? {
        val saved = AccountManager.accounts(api.idPrefix).firstOrNull() ?: return null
        return when (val result = TrackerAccountAccess.current(api, saved)) {
            is TrackerAuthResult.Ready -> result.account
            TrackerAuthResult.Missing -> null
            TrackerAuthResult.ReauthorizationRequired -> throw IllegalStateException("${api.name} sign-in expired; reconnect it in Settings")
        }
    }

    private fun refresh() {
        val api = selectedApi() ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(loading = true, error = null, message = null) }
            try {
                val metadata = api.library(currentAccount(api)) ?: error("${api.name} did not return a library")
                val entries = metadata.allLibraryLists.flatMap { list ->
                    val label = (list.name as? UiText.PlainText)?.value ?: list.name.toString()
                    list.items.map { TrackerLibraryEntry(it, label) }
                }
                val filters = metadata.allLibraryLists.mapNotNull { list ->
                    (list.name as? UiText.PlainText)?.value
                }
                updateState {
                    copy(
                        entries = entries,
                        statusFilters = filters,
                        selectedStatus = selectedStatus?.takeIf(filters::contains),
                        loading = false,
                        error = null,
                    ).rebuildVisible()
                }
            } catch (error: Exception) {
                updateState { copy(loading = false, error = error.message ?: "Could not load ${api.name} library") }
            }
        }
    }

    private fun search(query: String) {
        val api = selectedApi() ?: return
        if (query.isBlank()) {
            updateState { copy(searchResults = emptyList(), error = null) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(searching = true, error = null, searchQuery = query) }
            try {
                val results = api.search(currentAccount(api), query).orEmpty()
                updateState { copy(searching = false, searchResults = results, error = null) }
            } catch (error: Exception) {
                updateState { copy(searching = false, error = error.message ?: "Search failed") }
            }
        }
    }

    private fun openEditor(entry: TrackerLibraryEntry) {
        val api = selectedApi() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(error = null, message = null) }
            try {
                val auth = currentAccount(api)
                val current = api.status(auth, entry.item.syncId) ?: SyncAPI.SyncStatus(
                    status = SyncWatchType.NONE,
                    score = null,
                    watchedEpisodes = null,
                    mediaType = entry.item.mediaType,
                )
                val exact = if (api.supportsExactEpisodeProgress) {
                    api.watchedEpisodeSelection(auth, entry.item.syncId).orEmpty()
                } else {
                    emptySet()
                }
                updateState {
                    copy(
                        editor = TrackerEditorState(
                            entry = entry,
                            status = current.status,
                            score = current.score?.toDouble(10)?.let { "%.1f".format(java.util.Locale.ROOT, it) }.orEmpty(),
                            progress = current.watchedEpisodes?.toString().orEmpty(),
                            exactEpisodes = TrackerLibraryModel.formatEpisodeSelection(exact),
                            exactEpisodesEnabled = api.supportsExactEpisodeProgress && entry.item.mediaType != SyncAPI.SyncMediaType.MOVIE,
                        ),
                    )
                }
            } catch (error: Exception) {
                updateState { copy(error = error.message ?: "Could not read the current tracker status") }
            }
        }
    }

    private fun saveEditor() {
        val editor = uiState.value.editor ?: return
        val api = selectedApi() ?: return
        if (editor.saving) return
        val score = editor.score.trim().takeIf(String::isNotBlank)?.toDoubleOrNull()
        val progress = editor.progress.trim().takeIf(String::isNotBlank)?.toIntOrNull()
        if (editor.score.isNotBlank() && (score == null || score !in 0.0..10.0)) {
            updateState { copy(error = "Score must be between 0 and 10") }
            return
        }
        if (editor.progress.isNotBlank() && (progress == null || progress < 0)) {
            updateState { copy(error = "Progress must be a non-negative episode count") }
            return
        }
        if (progress != null && editor.entry.item.episodesTotal != null && progress > editor.entry.item.episodesTotal) {
            updateState { copy(error = "Progress cannot exceed the provider's known episode total") }
            return
        }
        val exact = if (editor.exactEpisodesEnabled) {
            TrackerLibraryModel.parseEpisodeSelection(editor.exactEpisodes).getOrElse {
                updateState { copy(error = "Episodes must use values such as 1, 2 or 1x3, separated by commas") }
                return
            }
        } else {
            null
        }
        val status = SyncAPI.SyncStatus(
            status = editor.status,
            score = score?.let(com.lagradost.cloudstream3.Score::from10),
            watchedEpisodes = if (exact != null) exact.size else progress,
            watchedEpisodeSelection = exact,
            mediaType = editor.entry.item.mediaType ?: api.supportedMediaTypes.singleOrNull(),
        )
        updateState { copy(editor = editor.copy(saving = true), error = null, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ok = api.updateStatus(currentAccount(api), editor.entry.item.syncId, status)
                if (!ok) error("${api.name} rejected this change; the displayed value was not updated")
                updateState { copy(editor = null, message = "Saved ${editor.entry.item.name} to ${api.name}") }
                refresh()
            } catch (error: Exception) {
                updateState { copy(editor = editor.copy(saving = false), error = error.message ?: "Could not save the tracker change") }
            }
        }
    }

    private fun updateEditor(reducer: TrackerEditorState.() -> TrackerEditorState) {
        updateState { copy(editor = editor?.reducer()) }
    }

    private fun TrackerLibraryUiState.rebuildVisible(): TrackerLibraryUiState = copy(
        filteredEntries = TrackerLibraryModel.filterAndSort(entries, searchQuery, selectedStatus, sorting),
    )
}
