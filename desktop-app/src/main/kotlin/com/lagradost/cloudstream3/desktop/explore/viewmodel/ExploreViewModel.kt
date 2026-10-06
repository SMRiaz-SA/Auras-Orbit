package com.lagradost.cloudstream3.desktop.explore.viewmodel

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.desktop.explore.client.ExploreCatalogClient
import com.lagradost.cloudstream3.desktop.explore.client.ExploreCatalogDiscoverer
import com.lagradost.cloudstream3.desktop.explore.client.TorrentSearchClient
import com.lagradost.cloudstream3.desktop.explore.models.ExploreItem
import com.lagradost.cloudstream3.desktop.explore.models.ManifestCatalogDescriptor
import com.lagradost.cloudstream3.desktop.explore.models.ProviderMatch
import com.lagradost.cloudstream3.desktop.explore.models.TorrentSearchResult
import com.lagradost.cloudstream3.desktop.metadata.stremio.StremioAddonClient
import com.lagradost.cloudstream3.desktop.metadata.stremio.StremioAddonClient.StremioVideo
import com.lagradost.cloudstream3.desktop.stremio.ManagedStremioAddon
import com.lagradost.cloudstream3.desktop.stremio.StremioAddonManager
import com.lagradost.cloudstream3.desktop.ui.badges.CardTitleSanitizer
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.base.UiEffect
import com.lagradost.cloudstream3.desktop.ui.base.UiEvent
import com.lagradost.cloudstream3.desktop.ui.base.UiState
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.common.logging.AppLogger
import com.lagradost.runtime.executor.SafePluginInvoker
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]")
private val WHITESPACE_REGEX = Regex("\\s+")
private val STOP_WORDS = setOf("the", "a", "an", "and", "of", "in", "to", "for", "with", "on", "at", "by", "from", "season", "episode")

val EXPLORE_YEAR_OPTIONS = listOf(
    "All Years",
    "2026",
    "2025",
    "2024",
    "2023",
    "2022",
    "2021",
    "2020",
    "2010s",
    "2000s",
    "1990s & Older",
)

@androidx.compose.runtime.Immutable
data class ExploreUiState(
    val isInitializing: Boolean = true,
    val allCatalogs: List<ManifestCatalogDescriptor> = emptyList(),
    val availableTypes: List<String> = emptyList(),
    val selectedType: String = "movie",
    val filteredCatalogs: List<ManifestCatalogDescriptor> = emptyList(),
    val selectedCatalog: ManifestCatalogDescriptor? = null,
    val selectedGenre: String = "All",
    val selectedExtraArgs: Map<String, String> = emptyMap(),
    val selectedYear: String = "All Years",
    val searchQuery: String = "",
    val rawItems: List<ExploreItem> = emptyList(),
    val catalogItemsFetched: Int = 0,
    val displayItems: List<ExploreItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val catalogDiscoveryError: String? = null,
    val catalogDiscoveryWarning: String? = null,
    val catalogLoadError: String? = null,
    val selectedItemForMatch: ExploreItem? = null,
    val providerMatches: List<ProviderMatch> = emptyList(),
    val isSearchingProviders: Boolean = false,
    val stremioItem: ExploreItem? = null,
    val stremioVideos: List<StremioVideo> = emptyList(),
    val selectedStremioVideo: StremioVideo? = null,
    val stremioStreams: List<ExtractorLink> = emptyList(),
    val isLoadingStremioStreams: Boolean = false,
    val stremioStreamMessage: String? = null,
    val torrentSearchQuery: String = "",
    val torrentSearchResults: List<TorrentSearchResult> = emptyList(),
    val isSearchingTorrents: Boolean = false,
    val torrentSearchMessage: String? = null,
) : UiState

sealed interface ExploreUiEvent : UiEvent {
    data class SelectType(val type: String) : ExploreUiEvent
    data class SelectCatalog(val catalog: ManifestCatalogDescriptor) : ExploreUiEvent
    data class SelectGenre(val genre: String) : ExploreUiEvent
    data class ApplyCatalogExtras(val values: Map<String, String>) : ExploreUiEvent
    data class SelectYear(val year: String) : ExploreUiEvent
    data class UpdateSearchQuery(val query: String) : ExploreUiEvent
    data object ClearSearchQuery : ExploreUiEvent
    data class OpenProviderPicker(val item: ExploreItem) : ExploreUiEvent
    data class OpenStremioStreams(val item: ExploreItem) : ExploreUiEvent
    data class SelectStremioVideo(val video: StremioVideo) : ExploreUiEvent
    data object BackToStremioVideos : ExploreUiEvent
    data object CloseStremioStreams : ExploreUiEvent
    data class UpdateTorrentSearchQuery(val query: String) : ExploreUiEvent
    data class SearchTorrents(val query: String) : ExploreUiEvent
    data object CloseProviderPicker : ExploreUiEvent
    data class SelectProviderMatch(val match: ProviderMatch) : ExploreUiEvent
    data object LoadMore : ExploreUiEvent
    data object RefreshCatalogs : ExploreUiEvent
    data object RetryCatalogLoad : ExploreUiEvent
}

sealed interface ExploreUiEffect : UiEffect {
    data class OpenDetails(val providerName: String, val url: String, val title: String) : ExploreUiEffect
}

private data class CatalogDiscoveryOutcome(
    val catalogs: List<ManifestCatalogDescriptor>,
    val error: String? = null,
)

private data class CachedCatalogItems(
    val items: List<ExploreItem>,
    val fetchedCount: Int,
)

class ExploreViewModel : BaseMviViewModel<ExploreUiState, ExploreUiEvent, ExploreUiEffect>(ExploreUiState()) {
    private val TAG = "ExploreViewModel"

    private var refreshJob: Job? = null
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var providerSearchJob: Job? = null
    private var stremioStreamJob: Job? = null
    private var torrentSearchJob: Job? = null
    private var remoteSearchJob: Job? = null
    private val catalogRequestGeneration = AtomicLong(0L)
    private val catalogDiscoveryGeneration = AtomicLong(0L)
    private val providerSearchGeneration = AtomicLong(0L)
    private val stremioStreamGeneration = AtomicLong(0L)
    private val torrentSearchGeneration = AtomicLong(0L)
    private val searchSemaphore = Semaphore(8)

    companion object {
        private const val MAX_CACHE_ENTRIES = 30
    }

    private val catalogItemsCache: MutableMap<String, CachedCatalogItems> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedCatalogItems>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedCatalogItems>?): Boolean {
                return size > MAX_CACHE_ENTRIES
            }
        },
    )

    init {
        viewModelScope.launch {
            StremioAddonManager.addons.collect {
                ExploreCatalogDiscoverer.clearCache()
                catalogItemsCache.clear()
                refreshCatalogs()
            }
        }
    }

    override fun handleEvent(event: ExploreUiEvent) {
        when (event) {
            is ExploreUiEvent.SelectType -> selectType(event.type)
            is ExploreUiEvent.SelectCatalog -> selectCatalog(event.catalog)
            is ExploreUiEvent.SelectGenre -> selectGenre(event.genre)
            is ExploreUiEvent.ApplyCatalogExtras -> applyCatalogExtras(event.values)
            is ExploreUiEvent.SelectYear -> selectYear(event.year)
            is ExploreUiEvent.UpdateSearchQuery -> updateSearchQuery(event.query)
            is ExploreUiEvent.ClearSearchQuery -> updateSearchQuery("")
            is ExploreUiEvent.OpenProviderPicker -> openProviderPicker(event.item)
            is ExploreUiEvent.OpenStremioStreams -> openStremioStreams(event.item)
            is ExploreUiEvent.SelectStremioVideo -> selectStremioVideo(event.video)
            is ExploreUiEvent.BackToStremioVideos -> backToStremioVideos()
            is ExploreUiEvent.CloseStremioStreams -> closeStremioStreams()
            is ExploreUiEvent.UpdateTorrentSearchQuery -> updateState { copy(torrentSearchQuery = event.query) }
            is ExploreUiEvent.SearchTorrents -> searchTorrents(event.query)
            is ExploreUiEvent.CloseProviderPicker -> closeProviderPicker()
            is ExploreUiEvent.SelectProviderMatch -> selectProviderMatch(event.match)
            is ExploreUiEvent.LoadMore -> loadMore()
            is ExploreUiEvent.RefreshCatalogs -> {
                ExploreCatalogDiscoverer.clearCache()
                catalogItemsCache.clear()
                refreshCatalogs()
            }
            is ExploreUiEvent.RetryCatalogLoad -> retryCatalogLoad()
        }
    }

    private fun selectProviderMatch(match: ProviderMatch) {
        closeProviderPicker()
        sendEffect(
            ExploreUiEffect.OpenDetails(
                providerName = match.providerName,
                url = match.searchResponse.url,
                title = match.displayTitle,
            ),
        )
    }

    private fun refreshCatalogs() {
        val discoveryGeneration = catalogDiscoveryGeneration.incrementAndGet()
        refreshJob?.cancel()
        remoteSearchJob?.cancel()
        remoteSearchJob = null
        invalidateCatalogRequests()
        updateState {
            copy(
                isInitializing = true,
                allCatalogs = emptyList(),
                availableTypes = emptyList(),
                filteredCatalogs = emptyList(),
                selectedCatalog = null,
                selectedExtraArgs = emptyMap(),
                rawItems = emptyList(),
                catalogItemsFetched = 0,
                displayItems = emptyList(),
                isLoading = false,
                isLoadingMore = false,
                canLoadMore = false,
                catalogDiscoveryError = null,
                catalogDiscoveryWarning = null,
                catalogLoadError = null,
            )
        }
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val enabledAddons: List<ManagedStremioAddon> = StremioAddonManager.addons.value.filter { it.enabled }
            val outcomes = coroutineScope {
                enabledAddons.map { addon ->
                    async {
                        try {
                            CatalogDiscoveryOutcome(
                                catalogs = ExploreCatalogDiscoverer.getCatalogsForAddon(addon),
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Failed to discover catalogs for ${addon.name}: ${e.message}")
                            CatalogDiscoveryOutcome(catalogs = emptyList(), error = e.message ?: "Request failed")
                        }
                    }
                }.awaitAll()
            }
            val discovered = outcomes.flatMap { it.catalogs }
            val failedAddons = outcomes.filter { it.error != null }
            val discoveryError = if (discovered.isEmpty() && failedAddons.isNotEmpty()) {
                "Couldn't reach enabled add-on catalogs. Check your connection and retry."
            } else {
                null
            }
            val discoveryWarning = if (discovered.isNotEmpty() && failedAddons.isNotEmpty()) {
                "Some enabled add-ons couldn't be reached. Showing catalogs from the add-ons that responded."
            } else {
                null
            }

            if (catalogDiscoveryGeneration.get() != discoveryGeneration) return@launch

            val types = discovered.map { it.type.lowercase() }.distinct().sortedBy {
                when (it) {
                    "movie" -> 0
                    "series" -> 1
                    "anime" -> 2
                    else -> 3
                }
            }

            val currentType = uiState.value.selectedType
            val targetType = if (types.contains(currentType)) currentType else types.firstOrNull() ?: "movie"

            val forType = discovered.filter { it.type.equals(targetType, ignoreCase = true) }
            val nextCat = forType.firstOrNull()

            if (discovered.isEmpty()) {
                updateState {
                    if (catalogDiscoveryGeneration.get() == discoveryGeneration) {
                        copy(
                            isInitializing = false,
                            allCatalogs = emptyList(),
                            availableTypes = emptyList(),
                            filteredCatalogs = emptyList(),
                            selectedCatalog = null,
                            selectedExtraArgs = emptyMap(),
                            rawItems = emptyList(),
                            catalogItemsFetched = 0,
                            displayItems = emptyList(),
                            isLoading = false,
                            isLoadingMore = false,
                            canLoadMore = false,
                            catalogDiscoveryError = discoveryError,
                            catalogDiscoveryWarning = null,
                            catalogLoadError = null,
                        )
                    } else {
                        this
                    }
                }
            } else {
                updateState {
                    if (catalogDiscoveryGeneration.get() == discoveryGeneration) {
                        copy(
                            isInitializing = false,
                            allCatalogs = discovered,
                            availableTypes = types,
                            selectedType = targetType,
                            filteredCatalogs = forType,
                            selectedCatalog = nextCat,
                            selectedGenre = "All",
                            selectedExtraArgs = emptyMap(),
                            selectedYear = "All Years",
                            searchQuery = "",
                            rawItems = emptyList(),
                            catalogItemsFetched = 0,
                            displayItems = emptyList(),
                            isLoading = false,
                            isLoadingMore = false,
                            canLoadMore = true,
                            catalogDiscoveryError = null,
                            catalogDiscoveryWarning = discoveryWarning,
                            catalogLoadError = null,
                        )
                    } else {
                        this
                    }
                }
                if (catalogDiscoveryGeneration.get() != discoveryGeneration) return@launch
                loadCurrentCatalog()
            }
        }
    }

    private fun selectType(type: String) {
        if (uiState.value.selectedType == type) return
        cancelPendingRemoteSearch()

        val forType = uiState.value.allCatalogs.filter { it.type.equals(type, ignoreCase = true) }
        val nextCat = forType.firstOrNull()

        updateState {
            copy(
                selectedType = type,
                filteredCatalogs = forType,
                selectedCatalog = nextCat,
                selectedGenre = "All",
                selectedExtraArgs = emptyMap(),
                selectedYear = "All Years",
                searchQuery = "",
            )
        }

        loadCurrentCatalog()
    }

    private fun selectCatalog(catalog: ManifestCatalogDescriptor) {
        if (uiState.value.selectedCatalog?.id == catalog.id &&
            uiState.value.selectedCatalog?.addonManifestUrl == catalog.addonManifestUrl
        ) {
            return
        }
        cancelPendingRemoteSearch()

        updateState {
            copy(
                selectedCatalog = catalog,
                selectedGenre = "All",
                selectedExtraArgs = emptyMap(),
                selectedYear = "All Years",
                searchQuery = "",
            )
        }

        loadCurrentCatalog()
    }

    private fun selectGenre(genre: String) {
        if (uiState.value.selectedGenre == genre) return
        cancelPendingRemoteSearch()

        updateState { copy(selectedGenre = genre) }
        loadCurrentCatalog()
    }

    private fun selectYear(year: String) {
        if (uiState.value.selectedYear == year) return
        updateState {
            copy(
                selectedYear = year,
                displayItems = applyFilters(rawItems, searchQuery, year),
            )
        }
    }

    private fun updateSearchQuery(query: String) {
        val supportsRemoteSearch = uiState.value.selectedCatalog?.supportsSearch == true
        if (supportsRemoteSearch) {
            invalidateCatalogRequests()
            updateState { copy(isLoadingMore = false, catalogLoadError = null) }
        }
        updateState {
            copy(
                searchQuery = query,
                displayItems = applyFilters(rawItems, query, selectedYear),
            )
        }
        remoteSearchJob?.cancel()
        if (supportsRemoteSearch) {
            remoteSearchJob = viewModelScope.launch {
                delay(350)
                loadCurrentCatalog()
            }
        }
    }

    private fun applyCatalogExtras(values: Map<String, String>) {
        cancelPendingRemoteSearch()
        updateState {
            copy(
                selectedExtraArgs = values.filterValues { it.isNotBlank() },
                rawItems = emptyList(),
                catalogItemsFetched = 0,
                displayItems = emptyList(),
            )
        }
        loadCurrentCatalog()
    }

    internal fun applyFilters(items: List<ExploreItem>, query: String, year: String): List<ExploreItem> {
        val q = query.trim().lowercase(Locale.US)
        return items.filter { item ->
            val matchesQuery = if (q.isBlank()) {
                true
            } else {
                item.name.lowercase(Locale.US).contains(q) ||
                    item.genres.any { it.lowercase(Locale.US).contains(q) } ||
                    item.releaseYear?.contains(q) == true
            }

            val itemYearInt = item.releaseYear?.toIntOrNull() ?: 0
            val matchesYear = when (year) {
                "All Years" -> true
                "2026" -> item.releaseYear == "2026"
                "2025" -> item.releaseYear == "2025"
                "2024" -> item.releaseYear == "2024"
                "2023" -> item.releaseYear == "2023"
                "2022" -> item.releaseYear == "2022"
                "2021" -> item.releaseYear == "2021"
                "2020" -> item.releaseYear == "2020"
                "2010s" -> itemYearInt in 2010..2019
                "2000s" -> itemYearInt in 2000..2009
                "1990s & Older" -> itemYearInt in 1..1999
                else -> item.releaseYear == year
            }

            matchesQuery && matchesYear
        }
    }

    private fun loadCurrentCatalog(skip: Int = 0) {
        val cat = uiState.value.selectedCatalog ?: return
        val requestGeneration = invalidateCatalogRequests()
        val state = uiState.value
        val genreArg = if (state.selectedGenre.equals("All", ignoreCase = true)) null else state.selectedGenre
        val queryArg = state.searchQuery.takeIf { cat.supportsSearch && it.isNotBlank() }
        val requestExtras = state.selectedExtraArgs.filterKeys { key ->
            !key.equals("genre", ignoreCase = true) && !key.equals("search", ignoreCase = true) && !key.equals("skip", ignoreCase = true)
        }
        val missingRequired = cat.extras.filter { it.isRequired }.firstOrNull { extra ->
            when (extra.name.lowercase(Locale.US)) {
                "genre" -> genreArg.isNullOrBlank()
                "search" -> queryArg.isNullOrBlank()
                "skip" -> false
                else -> requestExtras[extra.name].isNullOrBlank()
            }
        }
        if (missingRequired != null) {
            updateCatalogState(requestGeneration) {
                copy(
                    isInitializing = false,
                    isLoading = false,
                    isLoadingMore = false,
                    rawItems = emptyList(),
                    catalogItemsFetched = 0,
                    displayItems = emptyList(),
                    canLoadMore = false,
                    catalogLoadError = null,
                )
            }
            return
        }
        val cacheKey = listOf(
            cat.addonManifestUrl,
            cat.type,
            cat.id,
            genreArg.orEmpty(),
            queryArg.orEmpty(),
            requestExtras.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" },
            skip.toString(),
        ).joinToString("_")

        // Instant display if already in memory
        if (skip == 0) {
            val cached = catalogItemsCache[cacheKey]
            if (cached != null && cached.items.isNotEmpty()) {
                val filtered = applyFilters(cached.items, uiState.value.searchQuery, uiState.value.selectedYear)
                updateCatalogState(requestGeneration) {
                    copy(
                        isInitializing = false,
                        rawItems = cached.items,
                        catalogItemsFetched = cached.fetchedCount,
                        displayItems = filtered,
                        isLoading = false,
                        isLoadingMore = false,
                        canLoadMore = cached.fetchedCount >= 20,
                        catalogLoadError = null,
                    )
                }
                return
            }
        }

        updateCatalogState(requestGeneration) {
            copy(
                isInitializing = false,
                isLoading = true,
                isLoadingMore = false,
                rawItems = emptyList(),
                catalogItemsFetched = 0,
                displayItems = emptyList(),
                canLoadMore = true,
                catalogLoadError = null,
            )
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val fetched = ExploreCatalogClient.fetchCatalogItems(
                    baseUrl = cat.addonBaseUrl,
                    type = cat.type,
                    catalogId = cat.id,
                    genre = genreArg,
                    search = queryArg,
                    skip = skip,
                    manifestUrl = cat.addonManifestUrl,
                    addonName = cat.addonName,
                    extraArgs = requestExtras,
                )

                if (catalogRequestGeneration.get() != requestGeneration) return@launch

                val distinctFetched = fetched.distinctBy { it.id }
                if (distinctFetched.isNotEmpty()) {
                    catalogItemsCache[cacheKey] = CachedCatalogItems(distinctFetched, fetched.size)
                }

                val newRaw = if (skip == 0) distinctFetched else (uiState.value.rawItems + distinctFetched).distinctBy { it.id }
                val filtered = applyFilters(newRaw, uiState.value.searchQuery, uiState.value.selectedYear)

                updateCatalogState(requestGeneration) {
                    copy(
                        isInitializing = false,
                        rawItems = newRaw,
                        catalogItemsFetched = skip + fetched.size,
                        displayItems = filtered,
                        isLoading = false,
                        canLoadMore = fetched.size >= 20,
                        catalogLoadError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed loading catalog ${cat.name}: ${e.message}")
                if (catalogRequestGeneration.get() != requestGeneration) return@launch
                updateCatalogState(requestGeneration) {
                    copy(
                        isLoading = false,
                        isInitializing = false,
                        isLoadingMore = false,
                        rawItems = emptyList(),
                        catalogItemsFetched = 0,
                        displayItems = emptyList(),
                        canLoadMore = false,
                        catalogLoadError = "Could not load ${cat.name}. Check the add-on connection and retry.",
                    )
                }
            }
        }
    }

    private fun loadMore() {
        val state = uiState.value
        if (state.isLoading || state.isLoadingMore || !state.canLoadMore || state.selectedCatalog == null) return
        if (loadMoreJob?.isActive == true) return
        if (state.rawItems.isEmpty()) return

        val cat = state.selectedCatalog
        val genreArg = if (state.selectedGenre.equals("All", ignoreCase = true)) null else state.selectedGenre
        val queryArg = state.searchQuery.takeIf { state.selectedCatalog.supportsSearch && it.isNotBlank() }
        val requestExtras = state.selectedExtraArgs.filterKeys { key ->
            !key.equals("genre", ignoreCase = true) && !key.equals("search", ignoreCase = true) && !key.equals("skip", ignoreCase = true)
        }
        val skip = state.catalogItemsFetched
        val requestGeneration = catalogRequestGeneration.get()

        updateCatalogState(requestGeneration) { copy(isLoadingMore = true, catalogLoadError = null) }
        loadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val fetched = ExploreCatalogClient.fetchCatalogItems(
                    baseUrl = cat.addonBaseUrl,
                    type = cat.type,
                    catalogId = cat.id,
                    genre = genreArg,
                    search = queryArg,
                    skip = skip,
                    manifestUrl = cat.addonManifestUrl,
                    addonName = cat.addonName,
                    extraArgs = requestExtras,
                )

                if (catalogRequestGeneration.get() != requestGeneration) return@launch

                if (fetched.isEmpty()) {
                    updateCatalogState(requestGeneration) {
                        copy(isLoadingMore = false, canLoadMore = false, catalogLoadError = null)
                    }
                } else {
                    val distinctFetched = fetched.distinctBy { it.id }
                    val newRaw = (uiState.value.rawItems + distinctFetched).distinctBy { it.id }
                    val filtered = applyFilters(newRaw, uiState.value.searchQuery, uiState.value.selectedYear)
                    updateCatalogState(requestGeneration) {
                        copy(
                            rawItems = newRaw,
                            catalogItemsFetched = skip + fetched.size,
                            displayItems = filtered,
                            isLoadingMore = false,
                            canLoadMore = fetched.size >= 20,
                            catalogLoadError = null,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed loading more items for ${cat.name}: ${e.message}")
                if (catalogRequestGeneration.get() != requestGeneration) return@launch
                updateCatalogState(requestGeneration) {
                    copy(
                        isLoadingMore = false,
                        catalogLoadError = "Could not load more titles from ${cat.name}. Retry the request.",
                    )
                }
            }
        }
    }

    private fun retryCatalogLoad() {
        val state = uiState.value
        if (state.rawItems.isNotEmpty() && state.canLoadMore) {
            loadMore()
        } else {
            loadCurrentCatalog()
        }
    }

    private fun invalidateCatalogRequests(): Long {
        val generation = catalogRequestGeneration.incrementAndGet()
        loadJob?.cancel()
        loadMoreJob?.cancel()
        loadJob = null
        loadMoreJob = null
        return generation
    }

    private fun updateCatalogState(
        generation: Long,
        reducer: ExploreUiState.() -> ExploreUiState,
    ) {
        if (catalogRequestGeneration.get() != generation) return
        updateState {
            if (catalogRequestGeneration.get() == generation) reducer() else this
        }
    }

    private fun cancelPendingRemoteSearch() {
        remoteSearchJob?.cancel()
        remoteSearchJob = null
    }

    internal fun isTitleRelevant(targetTitle: String, candidateTitle: String): Boolean {
        val targetNorm = NON_ALPHANUMERIC_REGEX.replace(
            CardTitleSanitizer.sanitize(targetTitle).displayTitle.lowercase(Locale.US),
            " ",
        ).trim()
        val candidateNorm = NON_ALPHANUMERIC_REGEX.replace(
            CardTitleSanitizer.sanitize(candidateTitle).displayTitle.lowercase(Locale.US),
            " ",
        ).trim()

        if (targetNorm.isBlank() || candidateNorm.isBlank()) return false
        if (targetNorm == candidateNorm) return true
        if (candidateNorm.contains(targetNorm) || targetNorm.contains(candidateNorm)) return true

        val targetWords = targetNorm.split(WHITESPACE_REGEX).filter { it.length > 1 && it !in STOP_WORDS }

        if (targetWords.isNotEmpty() && targetWords.all { candidateNorm.contains(it) }) {
            return true
        }

        return false
    }

    private fun openProviderPicker(item: ExploreItem) {
        val generation = providerSearchGeneration.incrementAndGet()
        providerSearchJob?.cancel()
        updateState {
            copy(
                selectedItemForMatch = item,
                providerMatches = emptyList(),
                isSearchingProviders = true,
            )
        }

        providerSearchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Asynchronously enrich metadata (clear logo, backdrop, etc.)
                launch {
                    try {
                        val meta = com.lagradost.cloudstream3.desktop.metadata.stremio.StremioAddonClient.getMeta(item.id, item.type)
                        if (meta != null) {
                            val current = uiState.value.selectedItemForMatch ?: item
                            val enriched = current.copy(
                                logoUrl = meta.logo?.takeIf { it.isNotBlank() } ?: current.logoUrl,
                                backgroundUrl = (meta.background?.replace("t/p/original//", "t/p/original/"))?.takeIf { it.isNotBlank() } ?: current.backgroundUrl,
                                posterUrl = meta.poster?.takeIf { it.isNotBlank() } ?: current.posterUrl,
                                description = meta.description?.takeIf { it.isNotBlank() } ?: current.description,
                                rating = meta.imdbRating?.toDoubleOrNull() ?: current.rating,
                                releaseYear = meta.releaseInfo?.takeIf { it.isNotBlank() } ?: current.releaseYear,
                                genres = if (meta.genres.isNullOrEmpty()) current.genres else meta.genres,
                            )
                            updateProviderSearchState(generation, item) {
                                copy(selectedItemForMatch = enriched)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { }
                }

                // Uses the single-source-of-truth real content providers
                val activeProviders: List<MainAPI> = com.lagradost.cloudstream3.desktop.repo.ActiveProviderRepository.allRealProviders.value
                    .ifEmpty { APIHolder.allProviders.filter { com.lagradost.cloudstream3.desktop.repo.ActiveProviderRepository.isRealContentProvider(it) } }
                if (activeProviders.isEmpty()) {
                    updateProviderSearchState(generation, item) { copy(isSearchingProviders = false) }
                    return@launch
                }

                val cleanTitle = CardTitleSanitizer.sanitize(item.name).displayTitle
                val searchTitle = cleanTitle.ifBlank { item.name }
                val aggregatedMatches = CopyOnWriteArrayList<ProviderMatch>()

                val jobs = activeProviders.map { provider ->
                    launch {
                        searchSemaphore.withPermit {
                            try {
                                if (generation != providerSearchGeneration.get()) return@withPermit
                                val res = SafePluginInvoker.invokeOrNull(
                                    tag = "Explore:Search:${provider.name}",
                                    timeoutMs = SafePluginInvoker.TIMEOUT_SEARCH_MS,
                                ) {
                                    provider.search(searchTitle, 1)
                                }

                                val searchItems = res?.items
                                if (generation == providerSearchGeneration.get() && !searchItems.isNullOrEmpty()) {
                                    val validMatches = mutableListOf<ProviderMatch>()
                                    for (searchRes in searchItems) {
                                        // Strictly filter for title relevance to discard random search noise
                                        if (isTitleRelevant(searchTitle, searchRes.name)) {
                                            val meta = CardTitleSanitizer.sanitize(searchRes.name)
                                            validMatches.add(
                                                ProviderMatch(
                                                    providerName = provider.name,
                                                    searchResponse = searchRes,
                                                    displayTitle = meta.displayTitle,
                                                    qualityText = meta.qualityText,
                                                    hasSub = meta.hasSub,
                                                    hasDub = meta.hasDub,
                                                ),
                                            )
                                        }
                                    }
                                    if (validMatches.isNotEmpty()) {
                                        aggregatedMatches.addAll(validMatches)
                                        updateProviderSearchState(generation, item) {
                                            copy(providerMatches = aggregatedMatches.toList())
                                        }
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // Ignored per provider failure
                            }
                        }
                    }
                }

                jobs.joinAll()
                updateProviderSearchState(generation, item) {
                    copy(
                        providerMatches = aggregatedMatches.toList(),
                        isSearchingProviders = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Provider resolution failed: ${e.message}")
                updateProviderSearchState(generation, item) { copy(isSearchingProviders = false) }
            }
        }
    }

    private fun updateProviderSearchState(
        generation: Long,
        item: ExploreItem,
        reducer: ExploreUiState.() -> ExploreUiState,
    ) {
        if (providerSearchGeneration.get() != generation) return
        updateState {
            val selected = selectedItemForMatch
            if (providerSearchGeneration.get() == generation && selected != null &&
                selected.id == item.id &&
                selected.type == item.type &&
                selected.sourceManifestUrl == item.sourceManifestUrl
            ) {
                reducer()
            } else {
                this
            }
        }
    }

    private fun closeProviderPicker() {
        providerSearchGeneration.incrementAndGet()
        providerSearchJob?.cancel()
        updateState {
            copy(
                selectedItemForMatch = null,
                providerMatches = emptyList(),
                isSearchingProviders = false,
            )
        }
    }

    private fun openStremioStreams(item: ExploreItem) {
        val generation = stremioStreamGeneration.incrementAndGet()
        providerSearchGeneration.incrementAndGet()
        providerSearchJob?.cancel()
        stremioStreamJob?.cancel()
        updateState {
            copy(
                selectedItemForMatch = null,
                providerMatches = emptyList(),
                isSearchingProviders = false,
                stremioItem = item,
                stremioVideos = emptyList(),
                selectedStremioVideo = null,
                stremioStreams = emptyList(),
                isLoadingStremioStreams = true,
                stremioStreamMessage = if (item.type.equals("series", ignoreCase = true)) {
                    "Loading series video IDs from enabled metadata add-ons…"
                } else {
                    "Searching enabled Stremio stream add-ons…"
                },
            )
        }

        stremioStreamJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val mediaType = item.type.lowercase(Locale.US)
                val needsVideoIds = mediaType == "series"
                val shouldCheckMetadataVideos = needsVideoIds || mediaType !in setOf("movie", "tv")
                if (shouldCheckMetadataVideos) {
                    val metadataCandidates = StremioAddonClient.getMetaCandidates(
                        id = item.id,
                        type = item.type,
                        preferredManifestUrl = item.sourceManifestUrl,
                    )
                    val metaWithVideos = metadataCandidates.firstOrNull { candidate ->
                        candidate.videos.orEmpty().any { !it.id.isNullOrBlank() }
                    }
                    val meta = metaWithVideos ?: metadataCandidates.firstOrNull()
                    val videos = metaWithVideos?.videos.orEmpty()
                        .filter { !it.id.isNullOrBlank() }
                        .sortedWith(compareBy<StremioVideo> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })
                    if (videos.isNotEmpty()) {
                        updateStremioState(generation, item) {
                            copy(
                                stremioVideos = videos,
                                isLoadingStremioStreams = false,
                                stremioStreamMessage = "Choose a video to query compatible stream add-ons.",
                            )
                        }
                    } else if (needsVideoIds) {
                        updateStremioState(generation, item) {
                            copy(
                                isLoadingStremioStreams = false,
                                stremioStreamMessage = if (meta?.videos.isNullOrEmpty()) {
                                    "No enabled metadata add-on returned video IDs for this series. The app will not guess a season or episode ID."
                                } else {
                                    "The metadata add-on returned episodes without protocol video IDs, so no stream request was sent."
                                },
                            )
                        }
                    } else {
                        queryStremioStreams(item, video = null, generation = generation)
                    }
                } else {
                    queryStremioStreams(item, video = null, generation = generation)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Stremio stream lookup failed for ${item.type}:${item.id}: ${e.message}")
                updateStremioState(generation, item) {
                    copy(
                        isLoadingStremioStreams = false,
                        stremioStreamMessage = "The enabled Stremio add-ons could not be queried. Check the add-on connection and try again.",
                    )
                }
            }
        }
    }

    private fun searchTorrents(rawQuery: String) {
        val query = rawQuery.trim()
        val generation = torrentSearchGeneration.incrementAndGet()
        torrentSearchJob?.cancel()
        torrentSearchJob = null
        if (query.isEmpty()) {
            updateState {
                copy(
                    torrentSearchQuery = rawQuery,
                    torrentSearchResults = emptyList(),
                    isSearchingTorrents = false,
                    torrentSearchMessage = "Enter a title to search.",
                )
            }
            return
        }

        torrentSearchJob = viewModelScope.launch(Dispatchers.IO) {
            updateState {
                if (generation == torrentSearchGeneration.get()) {
                    copy(
                        torrentSearchQuery = query,
                        torrentSearchResults = emptyList(),
                        isSearchingTorrents = true,
                        torrentSearchMessage = null,
                    )
                } else {
                    this
                }
            }
            try {
                val results = TorrentSearchClient.search(query)
                updateTorrentSearchState(generation, query) {
                    copy(
                        torrentSearchResults = results,
                        isSearchingTorrents = false,
                        torrentSearchMessage = if (results.isEmpty()) "No playable magnet results found." else null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Torrent search failed for '$query': ${e.message}")
                updateTorrentSearchState(generation, query) {
                    copy(
                        torrentSearchResults = emptyList(),
                        isSearchingTorrents = false,
                        torrentSearchMessage = "Search failed: ${e.message ?: "service unavailable"}",
                    )
                }
            }
        }
    }

    private fun selectStremioVideo(video: StremioVideo) {
        val item = uiState.value.stremioItem ?: return
        if (video.id.isNullOrBlank()) return
        val generation = stremioStreamGeneration.incrementAndGet()
        stremioStreamJob?.cancel()
        updateState {
            copy(
                selectedStremioVideo = video,
                stremioStreams = emptyList(),
                isLoadingStremioStreams = true,
                stremioStreamMessage = "Searching enabled Stremio stream add-ons…",
            )
        }
        stremioStreamJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                queryStremioStreams(item, video, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLogger.w(TAG, "Stremio stream lookup failed for ${item.type}:${item.id}: ${failure.message}")
                updateStremioState(generation, item) {
                    copy(
                        isLoadingStremioStreams = false,
                        stremioStreamMessage = "The enabled Stremio add-ons could not be queried. Check the add-on connection and try again.",
                    )
                }
            }
        }
    }

    private suspend fun queryStremioStreams(item: ExploreItem, video: StremioVideo?, generation: Long) {
        val requestId = video?.id ?: item.id
        val streams = StremioAddonManager.searchStreamsById(item.type, requestId)
        updateStremioState(generation, item) {
            copy(
                stremioStreams = streams,
                isLoadingStremioStreams = false,
                stremioStreamMessage = if (streams.isEmpty()) {
                    "No playable streams were returned by enabled add-ons that declare support for this exact media ID."
                } else {
                    null
                },
            )
        }
    }

    private fun updateStremioState(
        generation: Long,
        item: ExploreItem,
        reducer: ExploreUiState.() -> ExploreUiState,
    ) {
        if (stremioStreamGeneration.get() != generation) return
        updateState {
            val current = stremioItem
            if (stremioStreamGeneration.get() == generation && current != null &&
                current.id == item.id &&
                current.type == item.type &&
                current.sourceManifestUrl == item.sourceManifestUrl
            ) {
                reducer()
            } else {
                this
            }
        }
    }

    private fun updateTorrentSearchState(
        generation: Long,
        query: String,
        reducer: ExploreUiState.() -> ExploreUiState,
    ) {
        if (torrentSearchGeneration.get() != generation) return
        updateState {
            if (torrentSearchGeneration.get() == generation && torrentSearchQuery == query) reducer() else this
        }
    }

    private fun backToStremioVideos() {
        stremioStreamGeneration.incrementAndGet()
        stremioStreamJob?.cancel()
        updateState {
            copy(
                selectedStremioVideo = null,
                stremioStreams = emptyList(),
                isLoadingStremioStreams = false,
                stremioStreamMessage = "Choose an episode to query compatible stream add-ons.",
            )
        }
    }

    private fun closeStremioStreams() {
        stremioStreamGeneration.incrementAndGet()
        stremioStreamJob?.cancel()
        updateState {
            copy(
                stremioItem = null,
                stremioVideos = emptyList(),
                selectedStremioVideo = null,
                stremioStreams = emptyList(),
                isLoadingStremioStreams = false,
                stremioStreamMessage = null,
            )
        }
    }

    fun formatTypeTitle(type: String): String {
        return when (type.lowercase(Locale.US)) {
            "movie" -> "Movies"
            "series" -> "Series"
            "anime" -> "Anime"
            else -> type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }
    }
}
