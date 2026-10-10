package com.lagradost.cloudstream3.desktop.ui.screens.search

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.DesktopErrorReporter
import com.lagradost.cloudstream3.desktop.core.preference.PreferenceKeys
import com.lagradost.cloudstream3.desktop.di.AppContainerHolder
import com.lagradost.cloudstream3.desktop.domain.plugins.repository.PluginRepository
import com.lagradost.cloudstream3.desktop.domain.providers.repository.ActiveProviderRepository
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.details.TmdbEnrichmentService
import com.lagradost.cloudstream3.desktop.ui.screens.search.contract.SearchMode
import com.lagradost.cloudstream3.desktop.ui.screens.search.contract.SearchProviderPagination
import com.lagradost.cloudstream3.desktop.ui.screens.search.contract.SearchUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.search.contract.SearchUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.search.contract.SearchUiState
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.runtime.executor.SafePluginInvoker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicLong

private const val PREF_SEARCH_HISTORY = PreferenceKeys.PREF_SEARCH_HISTORY
private const val MAX_HISTORY_SIZE = 20

class SearchViewModel(
    private val activeProviderRepository: ActiveProviderRepository = AppContainerHolder.container.activeProviderRepository,
    private val pluginRepository: PluginRepository = AppContainerHolder.container.pluginRepository,
) : BaseMviViewModel<SearchUiState, SearchUiEvent, SearchUiEffect>(
    initialState = SearchUiState(),
) {

    private var searchJob: Job? = null
    private val loadMoreJobs = mutableMapOf<String, Job>()
    private var searchGeneration = 0L
    private var lastSearchedQuery: String = ""
    private var skipAutoSearchForQuery: String? = null
    private var suppressSuggestionsForQuery: String? = null
    private val searchSemaphore = kotlinx.coroutines.sync.Semaphore(8)
    private val searchHistoryGeneration = AtomicLong(0L)
    private val searchHistoryWriteLock = Any()
    private var searchHistoryWriteJob: Job? = null

    init {
        // Collect real providers reactively
        viewModelScope.launch {
            activeProviderRepository.allRealProviders.collectLatest { providers ->
                updateState { copy(providers = providers) }
            }
        }

        // Collect current selected provider reactively from the shared domain repository
        viewModelScope.launch {
            activeProviderRepository.currentSelectedProvider.collectLatest { provider ->
                updateState {
                    copy(
                        selectedProviderName = provider?.name,
                        selectedProviderSource = provider?.sourcePlugin,
                    )
                }
            }
        }

        // Load search history
        viewModelScope.launch(Dispatchers.IO) {
            val history = DesktopDataStore.getKey<List<String>>(PREF_SEARCH_HISTORY) ?: emptyList()
            updateState {
                if (searchHistoryGeneration.get() == 0L) copy(searchHistory = history) else this
            }
        }

        // Debounced search query (500ms to prevent Cloudflare HTTP 429 rate limits on typing)
        viewModelScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            uiState.map { it.searchQuery }
                .distinctUntilChanged()
                .debounce(500)
                .collectLatest { query ->
                    if (query.isBlank()) {
                        lastSearchedQuery = ""
                        updateState {
                            copy(
                                searchResultsGrouped = null,
                                peopleResults = emptyList(),
                                peopleSearchFailed = false,
                                isLoadingSearch = false,
                                isLoadingMore = false,
                                canPaginate = true,
                                providerPagination = emptyMap(),
                                failedProviderKeys = emptySet(),
                                awaitingSearchSubmission = false,
                                searchSuggestions = emptyList(),
                                showSuggestions = false,
                            )
                        }
                    } else if (query == skipAutoSearchForQuery) {
                        skipAutoSearchForQuery = null
                    } else if (query != lastSearchedQuery) {
                        search()
                    }
                }
        }

        // Debounced search suggestions
        viewModelScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            uiState.map { it.searchMode to it.searchQuery }
                .distinctUntilChanged()
                .debounce(200)
                .collectLatest { (searchMode, query) ->
                    if (searchMode == SearchMode.PEOPLE) {
                        updateState {
                            if (searchQuery == query) {
                                copy(searchSuggestions = emptyList(), showSuggestions = false, isLoadingSuggestions = false)
                            } else {
                                this
                            }
                        }
                        return@collectLatest
                    }
                    val trimmed = query.trim()
                    if (trimmed.length >= 2 && query != suppressSuggestionsForQuery) {
                        updateState {
                            if (searchQuery == query) {
                                copy(
                                    searchSuggestions = emptyList(),
                                    showSuggestions = false,
                                    isLoadingSuggestions = true,
                                )
                            } else {
                                this
                            }
                        }
                        try {
                            val suggestions = SearchSuggestionApi.getSuggestions(trimmed, uiState.value.searchHistory)
                            updateState {
                                if (searchQuery == query && query != suppressSuggestionsForQuery) {
                                    copy(searchSuggestions = suggestions, showSuggestions = suggestions.isNotEmpty())
                                } else {
                                    this
                                }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            updateState {
                                if (searchQuery == query) copy(searchSuggestions = emptyList(), showSuggestions = false) else this
                            }
                        } finally {
                            updateState {
                                if (searchQuery == query) copy(isLoadingSuggestions = false) else this
                            }
                        }
                    } else {
                        updateState {
                            if (searchQuery == query) {
                                copy(
                                    searchSuggestions = emptyList(),
                                    showSuggestions = false,
                                    isLoadingSuggestions = false,
                                )
                            } else {
                                this
                            }
                        }
                    }
                }
        }

        // Remote plugin icons
        viewModelScope.launch {
            pluginRepository.remotePluginIcons.collectLatest { icons ->
                updateState { copy(pluginIcons = icons) }
            }
        }
    }

    override fun handleEvent(event: SearchUiEvent) {
        when (event) {
            is SearchUiEvent.OnSearchQueryChange -> handleQueryChange(event.query)
            is SearchUiEvent.OnSelectSearchMode -> {
                if (event.mode == uiState.value.searchMode) return
                invalidateSearchWork()
                lastSearchedQuery = ""
                suppressSuggestionsForQuery = if (event.mode == SearchMode.PEOPLE) uiState.value.searchQuery else null
                updateState {
                    copy(
                        searchMode = event.mode,
                        searchResultsGrouped = null,
                        peopleResults = emptyList(),
                        peopleSearchFailed = false,
                        isLoadingSearch = false,
                        isLoadingMore = false,
                        canPaginate = true,
                        providerPagination = emptyMap(),
                        failedProviderKeys = emptySet(),
                        awaitingSearchSubmission = false,
                        searchSuggestions = emptyList(),
                        showSuggestions = false,
                        isLoadingSuggestions = false,
                    )
                }
                if (uiState.value.searchQuery.isNotBlank()) search(force = true)
            }
            is SearchUiEvent.OnSearch -> {
                skipAutoSearchForQuery = null
                suppressSuggestionsForQuery = uiState.value.searchQuery
                updateState {
                    copy(
                        showSuggestions = false,
                        isLoadingSuggestions = false,
                        awaitingSearchSubmission = false,
                    )
                }
                addToHistory(uiState.value.searchQuery)
                search(force = true)
            }
            is SearchUiEvent.OnClearSearch -> {
                invalidateSearchWork()
                lastSearchedQuery = ""
                skipAutoSearchForQuery = null
                suppressSuggestionsForQuery = null
                updateState {
                    copy(
                        searchQuery = "",
                        searchResultsGrouped = null,
                        peopleResults = emptyList(),
                        peopleSearchFailed = false,
                        isLoadingSearch = false,
                        isLoadingMore = false,
                        canPaginate = true,
                        providerPagination = emptyMap(),
                        failedProviderKeys = emptySet(),
                        awaitingSearchSubmission = false,
                        searchSuggestions = emptyList(),
                        showSuggestions = false,
                        isLoadingSuggestions = false,
                    )
                }
            }
            is SearchUiEvent.OnSelectSuggestion -> {
                if (event.submitSearch) {
                    skipAutoSearchForQuery = null
                    handleQueryChange(event.query)
                    suppressSuggestionsForQuery = event.query
                    updateState {
                        copy(
                            showSuggestions = false,
                            isLoadingSuggestions = false,
                            awaitingSearchSubmission = false,
                        )
                    }
                    addToHistory(event.query)
                    search(force = true)
                } else {
                    invalidateSearchWork()
                    lastSearchedQuery = ""
                    skipAutoSearchForQuery = event.query
                    suppressSuggestionsForQuery = event.query
                    updateState {
                        copy(
                            searchQuery = event.query,
                            searchResultsGrouped = null,
                            peopleResults = emptyList(),
                            peopleSearchFailed = false,
                            isLoadingSearch = false,
                            isLoadingMore = false,
                            canPaginate = true,
                            providerPagination = emptyMap(),
                            failedProviderKeys = emptySet(),
                            awaitingSearchSubmission = event.query.isNotBlank(),
                            searchSuggestions = emptyList(),
                            showSuggestions = false,
                            isLoadingSuggestions = false,
                        )
                    }
                }
            }
            is SearchUiEvent.OnDismissSuggestions -> {
                suppressSuggestionsForQuery = uiState.value.searchQuery
                updateState { copy(showSuggestions = false, isLoadingSuggestions = false) }
            }
            is SearchUiEvent.OnToggleGlobalSearch -> {
                suppressSuggestionsForQuery = uiState.value.searchQuery
                updateState {
                    copy(isGlobalSearchEnabled = event.enabled, showSuggestions = false, isLoadingSuggestions = false)
                }
                if (uiState.value.searchQuery.isNotBlank() && !uiState.value.awaitingSearchSubmission) {
                    search(force = true)
                }
            }
            is SearchUiEvent.OnProviderSelected -> {
                suppressSuggestionsForQuery = uiState.value.searchQuery
                updateState {
                    copy(
                        isGlobalSearchEnabled = false,
                        selectedProviderName = event.providerName,
                        selectedProviderSource = event.sourcePlugin,
                        showSuggestions = false,
                        isLoadingSuggestions = false,
                    )
                }
                activeProviderRepository.setSelectedProviderByName(event.providerName, event.sourcePlugin)
                if (uiState.value.searchQuery.isNotBlank() && !uiState.value.awaitingSearchSubmission) {
                    search(force = true)
                }
            }
            is SearchUiEvent.OnToggleCategory -> {
                val current = uiState.value.selectedCategories
                val updated = if (event.category in current) {
                    current - event.category
                } else {
                    current + event.category
                }
                updateState { copy(selectedCategories = updated) }
            }
            is SearchUiEvent.OnClearCategories -> updateState { copy(selectedCategories = emptySet()) }
            is SearchUiEvent.OnRemoveSearchHistoryItem -> {
                val updated = uiState.value.searchHistory.filter { it != event.query }
                searchHistoryGeneration.incrementAndGet()
                updateState { copy(searchHistory = updated) }
                persistSearchHistory(updated)
            }
            is SearchUiEvent.OnClearSearchHistory -> {
                searchHistoryGeneration.incrementAndGet()
                updateState { copy(searchHistory = emptyList()) }
                persistSearchHistory(emptyList())
            }
            is SearchUiEvent.OnLoadMore -> loadMore(event.providerKey)
            is SearchUiEvent.OnSetProviderTypeFilter -> updateState { copy(providerTypeFilter = event.types) }
        }
    }

    private fun handleQueryChange(query: String) {
        if (query == uiState.value.searchQuery) return

        if (skipAutoSearchForQuery != query) skipAutoSearchForQuery = null
        if (suppressSuggestionsForQuery != query) suppressSuggestionsForQuery = null
        invalidateSearchWork()
        lastSearchedQuery = ""
        updateState {
            copy(
                searchQuery = query,
                searchResultsGrouped = null,
                peopleResults = emptyList(),
                peopleSearchFailed = false,
                searchSuggestions = emptyList(),
                showSuggestions = false,
                isLoadingSuggestions = false,
                isLoadingSearch = query.isNotBlank(),
                isLoadingMore = false,
                canPaginate = true,
                providerPagination = emptyMap(),
                failedProviderKeys = emptySet(),
                awaitingSearchSubmission = false,
            )
        }
    }

    private fun invalidateSearchWork() {
        searchGeneration += 1
        searchJob?.cancel()
        searchJob = null
        loadMoreJobs.values.forEach { it.cancel() }
        loadMoreJobs.clear()
    }

    private var currentPage = 1

    private fun addToHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        val current = uiState.value.searchHistory.toMutableList()
        current.remove(trimmed) // Deduplicate
        current.add(0, trimmed) // Prepend
        val capped = current.take(MAX_HISTORY_SIZE)
        searchHistoryGeneration.incrementAndGet()
        updateState { copy(searchHistory = capped) }

        persistSearchHistory(capped)
    }

    private fun persistSearchHistory(history: List<String>) {
        synchronized(searchHistoryWriteLock) {
            val previousWrite = searchHistoryWriteJob
            searchHistoryWriteJob = viewModelScope.launch(Dispatchers.IO) {
                previousWrite?.join()
                DesktopDataStore.setKey(PREF_SEARCH_HISTORY, history)
            }
        }
    }

    private fun search(force: Boolean = false) {
        val searchState = uiState.value
        val query = searchState.searchQuery
        if (query.isBlank() || (!force && query == lastSearchedQuery)) return

        invalidateSearchWork()
        val generation = searchGeneration
        lastSearchedQuery = query
        currentPage = 1

        updateSearchState(generation, query) {
            copy(
                isLoadingSearch = true,
                isLoadingMore = false,
                canPaginate = true,
                searchResultsGrouped = null,
                peopleResults = emptyList(),
                peopleSearchFailed = false,
                providerPagination = emptyMap(),
                failedProviderKeys = emptySet(),
                awaitingSearchSubmission = false,
            )
        }

        searchJob = viewModelScope.launch {
            try {
                if (searchState.searchMode == SearchMode.PEOPLE) {
                    val people = TmdbEnrichmentService.searchPeople(query)
                    updateSearchState(generation, query) {
                        copy(peopleResults = people, peopleSearchFailed = false)
                    }
                } else {
                    val providers = searchState.providers

                    val activeProviders = if (searchState.isGlobalSearchEnabled) {
                        providers.filter { it.hasMainPage || it.supportedTypes.isNotEmpty() }
                    } else {
                        val selName = searchState.selectedProviderName
                        val selSource = searchState.selectedProviderSource
                        val active = providers.find {
                            (selName != null && (it.name == selName || it.name == selName.substringAfter("::"))) &&
                                (selSource == null || it.sourcePlugin == selSource)
                        } ?: providers.firstOrNull()
                        active?.let { listOf(it) } ?: emptyList()
                    }

                    val tempResults = java.util.concurrent.ConcurrentHashMap<String, Pair<com.lagradost.cloudstream3.MainAPI, List<SearchResponse>>>()

                    activeProviders.map { p ->
                        launch {
                            searchSemaphore.withPermit {
                                com.lagradost.common.logging.AppLogger.i("Plugin:${p.name}", "Searching query: '$query'")
                                val uniqueKey = "${p.name}::${p.sourcePlugin ?: ""}"
                                val result = SafePluginInvoker.invoke(
                                    tag = "Search:${p.name}",
                                    timeoutMs = SafePluginInvoker.TIMEOUT_SEARCH_MS,
                                ) {
                                    p.search(query, 1)
                                }
                                if (result.isFailure) {
                                    updateSearchState(generation, query) {
                                        copy(failedProviderKeys = failedProviderKeys + uniqueKey)
                                    }
                                }
                                val res = result.getOrNull()
                                if (res != null && res.items.isNotEmpty()) {
                                    com.lagradost.common.logging.AppLogger.i("Plugin:${p.name}", "Found ${res.items.size} results for '$query'")
                                    tempResults[uniqueKey] = Pair(p, res.items)
                                    updateSearchState(generation, query) {
                                        copy(
                                            searchResultsGrouped = tempResults.toMap(),
                                            providerPagination = providerPagination + (
                                                uniqueKey to SearchProviderPagination(canPaginate = res.hasNext)
                                                ),
                                            canPaginate = if (isGlobalSearchEnabled) canPaginate else res.hasNext,
                                        )
                                    }
                                } else {
                                    com.lagradost.common.logging.AppLogger.i("Plugin:${p.name}", "No results found for '$query'")
                                }
                            }
                        }
                    }.forEach { it.join() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation == searchGeneration) {
                    if (searchState.searchMode == SearchMode.PEOPLE) {
                        updateSearchState(generation, query) { copy(peopleSearchFailed = true) }
                    } else {
                        DesktopErrorReporter.report("Search failed", e)
                    }
                }
            } finally {
                updateSearchState(generation, query) { copy(isLoadingSearch = false) }
            }
        }
    }

    private fun updateSearchState(
        generation: Long,
        query: String,
        reducer: SearchUiState.() -> SearchUiState,
    ) {
        if (generation != searchGeneration) return
        updateState {
            if (generation == searchGeneration && searchQuery == query) reducer() else this
        }
    }

    private fun loadMore(providerKey: String?) {
        val state = uiState.value
        val query = state.searchQuery
        if (query.isBlank() || state.isLoadingSearch) return

        if (state.isGlobalSearchEnabled) {
            if (providerKey != null) loadMoreGlobalProvider(providerKey, state)
            return
        }
        if (providerKey != null || state.isLoadingMore || !state.canPaginate) return
        if (loadMoreJobs["\u0000single"]?.isActive == true) return

        val activeProvider = state.providers.find {
            (it.name == state.selectedProviderName || it.name == state.selectedProviderName?.substringAfter("::")) &&
                (state.selectedProviderSource == null || it.sourcePlugin == state.selectedProviderSource)
        } ?: state.providers.firstOrNull() ?: return
        val uniqueKey = "${activeProvider.name}::${activeProvider.sourcePlugin ?: ""}"
        val currentGrouped = state.searchResultsGrouped ?: return
        if (currentGrouped[uniqueKey] == null) return

        val nextPage = currentPage + 1
        val generation = searchGeneration
        updateSearchState(generation, query) { copy(isLoadingMore = true) }
        val job = viewModelScope.launch {
            try {
                com.lagradost.common.logging.AppLogger.i("Plugin:${activeProvider.name}", "Loading more search results (page $nextPage) for '$query'")
                val result = SafePluginInvoker.invoke(
                    tag = "SearchMore:${activeProvider.name}",
                    timeoutMs = SafePluginInvoker.TIMEOUT_SEARCH_MS,
                ) {
                    activeProvider.search(query, nextPage)
                }
                if (result.isFailure) {
                    updateSearchState(generation, query) {
                        copy(failedProviderKeys = failedProviderKeys + uniqueKey)
                    }
                }
                val res = result.getOrNull()

                if (res != null && res.items.isNotEmpty()) {
                    currentPage = nextPage
                    val latestItems = uiState.value.searchResultsGrouped?.get(uniqueKey)?.second ?: return@launch
                    val existingUrls = latestItems.map { it.url }.toSet()
                    val newUniqueItems = res.items.filter { it.url !in existingUrls }
                    if (newUniqueItems.isNotEmpty()) {
                        val updatedItems = latestItems + newUniqueItems
                        updateSearchState(generation, query) {
                            val latestGrouped = searchResultsGrouped ?: return@updateSearchState this
                            copy(
                                searchResultsGrouped = latestGrouped.toMutableMap().apply {
                                    put(uniqueKey, Pair(activeProvider, updatedItems))
                                },
                                canPaginate = res.hasNext,
                            )
                        }
                        com.lagradost.common.logging.AppLogger.i("Plugin:${activeProvider.name}", "Appended ${newUniqueItems.size} new items (total: ${updatedItems.size})")
                    } else {
                        updateSearchState(generation, query) { copy(canPaginate = res.hasNext) }
                    }
                } else {
                    updateSearchState(generation, query) { copy(canPaginate = false) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                com.lagradost.common.logging.AppLogger.e("Failed loading more search results: ${e.message}")
                updateSearchState(generation, query) {
                    copy(canPaginate = false, failedProviderKeys = failedProviderKeys + uniqueKey)
                }
            } finally {
                updateSearchState(generation, query) { copy(isLoadingMore = false) }
            }
        }
        loadMoreJobs["\u0000single"] = job
    }

    private fun loadMoreGlobalProvider(providerKey: String, state: SearchUiState) {
        val query = state.searchQuery
        val pageState = state.providerPagination[providerKey] ?: return
        if (!pageState.canPaginate || pageState.isLoadingMore) return
        val providerResults = state.searchResultsGrouped?.get(providerKey) ?: return
        val provider = providerResults.first
        val generation = searchGeneration
        val nextPage = pageState.nextPage

        updateSearchState(generation, query) {
            copy(providerPagination = providerPagination + (providerKey to pageState.copy(isLoadingMore = true)))
        }

        val job = viewModelScope.launch {
            try {
                val result = SafePluginInvoker.invoke(
                    tag = "SearchMore:${provider.name}",
                    timeoutMs = SafePluginInvoker.TIMEOUT_SEARCH_MS,
                ) {
                    provider.search(query, nextPage)
                }
                if (result.isFailure) {
                    updateSearchState(generation, query) {
                        copy(failedProviderKeys = failedProviderKeys + providerKey)
                    }
                }
                val res = result.getOrNull()

                val latestItems = uiState.value.searchResultsGrouped?.get(providerKey)?.second ?: return@launch
                val existingUrls = latestItems.map { it.url }.toSet()
                val newItems = res?.items.orEmpty().filter { it.url !in existingUrls }
                val hasMore = res?.let { it.hasNext && it.items.isNotEmpty() } == true
                updateSearchState(generation, query) {
                    val latestGrouped = searchResultsGrouped ?: return@updateSearchState this
                    val latestPair = latestGrouped[providerKey] ?: return@updateSearchState this
                    val page = providerPagination[providerKey] ?: pageState
                    val nextPagination = page.copy(
                        nextPage = if (hasMore) nextPage + 1 else nextPage,
                        canPaginate = hasMore,
                        isLoadingMore = false,
                    )
                    val updatedGrouped = if (newItems.isEmpty()) {
                        latestGrouped
                    } else {
                        latestGrouped.toMutableMap().apply {
                            put(providerKey, Pair(latestPair.first, latestPair.second + newItems))
                        }
                    }
                    copy(
                        searchResultsGrouped = updatedGrouped,
                        providerPagination = providerPagination + (providerKey to nextPagination),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                com.lagradost.common.logging.AppLogger.e("Failed loading more search results: ${e.message}")
                updateSearchState(generation, query) {
                    val page = providerPagination[providerKey] ?: pageState
                    copy(
                        providerPagination = providerPagination + (providerKey to page.copy(canPaginate = false)),
                        failedProviderKeys = failedProviderKeys + providerKey,
                    )
                }
            } finally {
                updateSearchState(generation, query) {
                    val page = providerPagination[providerKey]
                    if (page == null) {
                        this
                    } else {
                        copy(
                            providerPagination = providerPagination + (providerKey to page.copy(isLoadingMore = false)),
                        )
                    }
                }
            }
        }
        loadMoreJobs[providerKey] = job
    }
}
