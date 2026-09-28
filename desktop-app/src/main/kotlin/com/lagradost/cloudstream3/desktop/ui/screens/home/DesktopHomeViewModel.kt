package com.lagradost.cloudstream3.desktop.ui.screens.home

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.DesktopErrorReporter
import com.lagradost.cloudstream3.desktop.core.preference.PreferenceKeys
import com.lagradost.cloudstream3.desktop.di.AppContainerHolder
import com.lagradost.cloudstream3.desktop.domain.bookmarks.interactor.GetBookmarks
import com.lagradost.cloudstream3.desktop.domain.hero.repository.HeroRepository
import com.lagradost.cloudstream3.desktop.domain.hero.repository.HeroRepository.HeroUpdate
import com.lagradost.cloudstream3.desktop.domain.history.interactor.GetContinueWatching
import com.lagradost.cloudstream3.desktop.domain.history.interactor.RemoveWatchHistory
import com.lagradost.cloudstream3.desktop.domain.providers.repository.ActiveProviderRepository
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.home.contract.HomeCategoryUiState
import com.lagradost.cloudstream3.desktop.ui.screens.home.contract.HomeUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.home.contract.HomeUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.home.contract.HomeUiState
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.runtime.executor.SafePluginInvoker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Returns true only for real, user-facing content providers:
 * - Excludes built-in MetaProviders (Trakt, TMDB, CrossTMDB)
 * - Excludes "NONE"
 */
fun MainAPI.isRealProvider(): Boolean = com.lagradost.cloudstream3.desktop.repo.ActiveProviderRepository.isRealContentProvider(this)

class DesktopHomeViewModel(
    private val getContinueWatching: GetContinueWatching = AppContainerHolder.container.getContinueWatching,
    private val removeWatchHistory: RemoveWatchHistory = AppContainerHolder.container.removeWatchHistory,
    private val getBookmarks: GetBookmarks = AppContainerHolder.container.getBookmarks,
    private val activeProviderRepository: ActiveProviderRepository = AppContainerHolder.container.activeProviderRepository,
    private val heroRepository: HeroRepository = AppContainerHolder.container.heroRepository,
) : BaseMviViewModel<HomeUiState, HomeUiEvent, HomeUiEffect>(
    initialState = HomeUiState(),
) {
    private val categoryCache = java.util.concurrent.ConcurrentHashMap<String, com.lagradost.cloudstream3.HomePageResponse>()
    private val categoryMutex = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private data class CachedDiscoveryResult(
        val items: List<HomeDiscoveryItem>,
        val pageSources: List<HomeDiscoveryPageSource>,
    )

    private val discoveryCache = java.util.concurrent.ConcurrentHashMap<String, CachedDiscoveryResult>()
    private val discoveryReloadEpoch = MutableStateFlow(0L)
    private var discoverySignature: String? = null
    private var lastDiscoveryReloadEpoch = -1L
    private var discoverySources: Map<HomeDiscoveryKind, List<HomeDiscoverySource>> = emptyMap()
    private var discoveryKindJobs = mutableMapOf<HomeDiscoveryKind, kotlinx.coroutines.Job>()

    private data class HomeDiscoverySource(
        val kind: HomeDiscoveryKind,
        val provider: MainAPI,
        val pageData: MainPageData,
    ) {
        val cacheKey: String
            get() = "${kind.name}:${provider.name}:${provider.mainUrl}:${pageData.name}:${pageData.data}"
    }

    // Redundant StateFlow mappings have been permanently deleted in accordance with MVI best practices.
    // UI should collect `uiState` and read properties directly from the immutable snapshot.

    init {
        viewModelScope.launch {
            getBookmarks.subscribeAll().collect { bookmarks ->
                updateState { copy(bookmarks = bookmarks) }
            }
        }

        // Reactively observe providers from single source of truth
        viewModelScope.launch {
            activeProviderRepository.allRealProviders.collectLatest { realProviders ->
                updateState { copy(providers = realProviders) }
            }
        }

        viewModelScope.launch {
            activeProviderRepository.activeProviders.collectLatest { activeApis ->
                updateState {
                    copy(
                        activeProviderApis = activeApis,
                    )
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            uiState.map { it.activeProviders }.distinctUntilChanged().collect { names ->
                val disabledMap = names.associateWith { name ->
                    DesktopDataStore.getKey<Set<String>>(PreferenceKeys.disabledCatalogsKey(name)) ?: emptySet()
                }
                updateState { copy(disabledCatalogs = disabledMap) }
            }
        }

        viewModelScope.launch {
            combine(
                uiState.map { it.activeProviderApis to it.disabledCatalogs }.distinctUntilChanged(),
                discoveryReloadEpoch,
            ) { (providers, disabledCatalogs), reloadEpoch ->
                Triple(providers, disabledCatalogs, reloadEpoch)
            }.collectLatest { (providers, disabledCatalogs, reloadEpoch) ->
                configureDiscovery(providers, disabledCatalogs, reloadEpoch)
            }
        }

        viewModelScope.launch {
            DesktopRepositoryManager.syncGeneration.collect { syncGen ->
                if (syncGen > 0) {
                    reloadIcons()
                }
            }
        }

        viewModelScope.launch {
            getContinueWatching.subscribe().collect { newHistory ->
                updateState { copy(historyList = newHistory) }
                prefetchTopHistory(newHistory.take(3))
            }
        }

        reloadIcons()
    }

    override fun handleEvent(event: HomeUiEvent) {
        when (event) {
            is HomeUiEvent.OnToggleProviderActive -> {
                val current = uiState.value.activeProviders.toMutableList()
                if (event.isActive) {
                    if (!current.contains(event.providerName)) current.add(event.providerName)
                } else {
                    current.remove(event.providerName)
                }
                activeProviderRepository.setActiveProviders(current)
            }
            is HomeUiEvent.OnSetSingleProvider -> {
                activeProviderRepository.setActiveProviders(listOf(event.providerName))
            }
            is HomeUiEvent.OnMoveProvider -> {
                val current = uiState.value.activeProviders.toMutableList()
                if (event.fromIndex in current.indices && event.toIndex in current.indices) {
                    val item = current.removeAt(event.fromIndex)
                    current.add(event.toIndex, item)
                }
                activeProviderRepository.setActiveProviders(current)
            }
            is HomeUiEvent.OnClearHistory -> clearHistory()
            is HomeUiEvent.OnRemoveHistoryItem -> removeHistoryItem(event.parentId)
            is HomeUiEvent.OnPrefetchHeroItem -> prefetchHeroItem(event.provider, event.item)
            is HomeUiEvent.OnProviderRefresh -> reloadProvider()
            is HomeUiEvent.OnShowHomeManagement -> {
                updateState { copy(showHomeManagement = event.show) }
            }
            is HomeUiEvent.OnToggleCatalog -> {
                val currentDisabled = uiState.value.disabledCatalogs[event.providerName] ?: emptySet()
                val newDisabled = if (event.isEnabled) {
                    currentDisabled - event.catalogName
                } else {
                    currentDisabled + event.catalogName
                }
                updateState {
                    copy(disabledCatalogs = disabledCatalogs + (event.providerName to newDisabled))
                }
                viewModelScope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PreferenceKeys.disabledCatalogsKey(event.providerName), newDisabled)
                }
            }
            is HomeUiEvent.OnLoadCategory -> {
                loadCategory(event.provider, event.pageData)
            }
            is HomeUiEvent.OnRetryDiscovery -> retryDiscovery(event.kind)
        }
    }

    private suspend fun configureDiscovery(
        providers: List<MainAPI>,
        disabledCatalogs: Map<String, Set<String>>,
        reloadEpoch: Long,
    ) {
        val pagesByProvider = coroutineScope {
            providers.filter { it.hasMainPage }.map { provider ->
                async(Dispatchers.IO) {
                    val result = SafePluginInvoker.invoke(
                        tag = "HomeDiscovery:${provider.name}:mainPage",
                        timeoutMs = SafePluginInvoker.TIMEOUT_LOAD_MS,
                    ) { provider.mainPage }
                    val pages = if (result.isSuccess) {
                        result.getOrNull().orEmpty()
                    } else {
                        val failure = result.exceptionOrNull()
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        com.lagradost.common.logging.AppLogger.w(
                            "HomeDiscovery:${provider.name}",
                            "Could not inspect provider catalogs: ${failure?.message}",
                        )
                        emptyList()
                    }
                    provider to pages
                }
            }.awaitAll()
        }

        val sourcesByKind = HomeDiscoveryKind.entries.associateWith { kind ->
            pagesByProvider.mapNotNull { (provider, pages) ->
                val disabledForProvider = disabledCatalogs[provider.name].orEmpty()
                pages.firstOrNull { page ->
                    page.name !in disabledForProvider &&
                        !isHiddenHomeCatalogTitle(page.name) &&
                        classifyHomeDiscoveryCatalog(page.name) == kind
                }?.let { page -> HomeDiscoverySource(kind, provider, page) }
            }
        }
        val signature = sourcesByKind.values.flatten().joinToString("|") { it.cacheKey }
        if (signature == discoverySignature && reloadEpoch == lastDiscoveryReloadEpoch) return

        discoverySignature = signature
        lastDiscoveryReloadEpoch = reloadEpoch
        discoverySources = sourcesByKind
        discoveryCache.clear()
        discoveryKindJobs.values.forEach(kotlinx.coroutines.Job::cancel)
        discoveryKindJobs.clear()

        val recentSources = sourcesByKind[HomeDiscoveryKind.RECENT].orEmpty()
        val popularSources = sourcesByKind[HomeDiscoveryKind.POPULAR].orEmpty()
        updateState {
            copy(
                recentDiscovery = HomeDiscoverySectionState(
                    isLoading = recentSources.isNotEmpty(),
                    hasLoaded = recentSources.isEmpty(),
                    sourceCount = recentSources.size,
                ),
                popularDiscovery = HomeDiscoverySectionState(
                    isLoading = popularSources.isNotEmpty(),
                    hasLoaded = popularSources.isEmpty(),
                    sourceCount = popularSources.size,
                ),
            )
        }

        coroutineScope {
            HomeDiscoveryKind.entries.map { kind ->
                async { loadDiscoveryKind(kind, sourcesByKind[kind].orEmpty()) }
            }.awaitAll()
        }
    }

    private fun retryDiscovery(kind: HomeDiscoveryKind) {
        val current = if (kind == HomeDiscoveryKind.RECENT) {
            uiState.value.recentDiscovery
        } else {
            uiState.value.popularDiscovery
        }
        if (current.isLoading) return

        discoveryKindJobs[kind]?.cancel()
        val sources = discoverySources[kind].orEmpty()
        discoveryKindJobs[kind] = viewModelScope.launch {
            loadDiscoveryKind(kind, sources)
        }
    }

    private suspend fun loadDiscoveryKind(kind: HomeDiscoveryKind, sources: List<HomeDiscoverySource>) {
        setDiscoveryState(kind) { current ->
            current.copy(
                isLoading = sources.isNotEmpty(),
                hasLoaded = sources.isEmpty(),
                sourceCount = sources.size,
                failedSourceCount = 0,
                items = emptyList(),
                pageSources = emptyList(),
            )
        }
        if (sources.isEmpty()) return

        val semaphore = Semaphore(MAX_PARALLEL_DISCOVERY_LOADS)
        val sourceResults = coroutineScope {
            sources.map { source ->
                async(Dispatchers.IO) {
                    val cached = discoveryCache[source.cacheKey]
                    if (cached != null) return@async cached to false

                    val result = semaphore.withPermit {
                        SafePluginInvoker.invoke(
                            tag = "HomeDiscovery:${source.provider.name}:${source.pageData.name}",
                            timeoutMs = SafePluginInvoker.TIMEOUT_LOAD_MS,
                        ) {
                            source.provider.getMainPage(
                                1,
                                MainPageRequest(source.pageData.name, source.pageData.data, source.pageData.horizontalImages),
                            )
                        }
                    }
                    if (!result.isSuccess) {
                        val failure = result.exceptionOrNull()
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        com.lagradost.common.logging.AppLogger.w(
                            "HomeDiscovery:${source.provider.name}",
                            "Could not load ${source.pageData.name}: ${failure?.message}",
                        )
                        return@async CachedDiscoveryResult(emptyList(), emptyList()) to true
                    }

                    val response = result.getOrNull()
                    val visibleSections = response?.items.orEmpty()
                        .filter { section ->
                            val label = section.name.ifBlank { source.pageData.name }
                            !isHiddenHomeCatalogTitle(label)
                        }
                    val items = visibleSections.flatMap { section ->
                        section.list.map { HomeDiscoveryItem(source.provider.name, it) }
                    }
                    val pageSources = if (response?.hasNext == true) {
                        visibleSections.filter { it.list.isNotEmpty() }.map { section ->
                            HomeDiscoveryPageSource(
                                provider = source.provider,
                                request = MainPageRequest(
                                    source.pageData.name,
                                    source.pageData.data,
                                    source.pageData.horizontalImages,
                                ),
                                sectionName = section.name.ifBlank { source.pageData.name },
                            )
                        }
                    } else {
                        emptyList()
                    }
                    val loaded = CachedDiscoveryResult(items, pageSources)
                    discoveryCache[source.cacheKey] = loaded
                    loaded to false
                }
            }.awaitAll()
        }

        val merged = mergeHomeDiscoveryItems(sourceResults.flatMap { it.first.items })
        val pageSources = sourceResults.flatMap { it.first.pageSources }
        val failedCount = sourceResults.count { it.second }
        setDiscoveryState(kind) { current ->
            current.copy(
                isLoading = false,
                hasLoaded = true,
                items = merged,
                sourceCount = sources.size,
                failedSourceCount = failedCount,
                pageSources = pageSources,
            )
        }
    }

    private fun setDiscoveryState(
        kind: HomeDiscoveryKind,
        transform: (HomeDiscoverySectionState) -> HomeDiscoverySectionState,
    ) {
        updateState {
            when (kind) {
                HomeDiscoveryKind.RECENT -> copy(recentDiscovery = transform(recentDiscovery))
                HomeDiscoveryKind.POPULAR -> copy(popularDiscovery = transform(popularDiscovery))
            }
        }
    }

    private fun loadCategory(provider: MainAPI, pageData: MainPageData) {
        val cacheKey = "${provider.name}_${pageData.name}"
        val cachedResponse = categoryCache[cacheKey]
        val currentState = uiState.value.categories[cacheKey]

        if (cachedResponse != null && currentState?.response == cachedResponse) {
            return
        }

        if (cachedResponse != null) {
            updateState {
                copy(categories = categories + (cacheKey to HomeCategoryUiState(isLoading = false, response = cachedResponse, error = null)))
            }
            return
        }

        if (currentState?.isLoading == true) {
            return
        }

        updateState {
            copy(categories = categories + (cacheKey to HomeCategoryUiState(isLoading = true, response = null, error = null)))
        }

        viewModelScope.launch(Dispatchers.IO) {
            val mutex = categoryMutex.getOrPut(cacheKey) { kotlinx.coroutines.sync.Mutex() }
            mutex.withLock {
                val existing = categoryCache[cacheKey]
                if (existing != null) {
                    updateState {
                        copy(categories = categories + (cacheKey to HomeCategoryUiState(isLoading = false, response = existing, error = null)))
                    }
                    return@withLock
                }

                val request = MainPageRequest(pageData.name, pageData.data, pageData.horizontalImages)
                com.lagradost.common.logging.AppLogger.i("Plugin:${provider.name}", "Loading home category: '${pageData.name}'")
                val result = SafePluginInvoker.invoke(
                    tag = "HomeCategory:${provider.name}:${pageData.name.ifBlank { "Category" }}",
                    timeoutMs = SafePluginInvoker.TIMEOUT_LOAD_MS,
                ) {
                    provider.getMainPage(1, request)
                }

                if (result.isSuccess) {
                    val response = result.getOrNull()
                    if (response != null && response.items.isNotEmpty()) {
                        com.lagradost.common.logging.AppLogger.i("Plugin:${provider.name}", "Loaded ${response.items.size} items for category '${pageData.name}'")
                        categoryCache[cacheKey] = response
                        updateState {
                            copy(categories = categories + (cacheKey to HomeCategoryUiState(isLoading = false, response = response, error = null)))
                        }
                    } else {
                        updateState {
                            copy(categories = categories + (cacheKey to HomeCategoryUiState(isLoading = false, response = null, error = "No items found.")))
                        }
                    }
                } else {
                    val ex = result.exceptionOrNull()
                    if (ex is kotlinx.coroutines.CancellationException) {
                        throw ex
                    }
                    com.lagradost.common.logging.AppLogger.w("Plugin:${provider.name}", "Failed to load category '${pageData.name}': ${ex?.message}")
                    DesktopErrorReporter.report("getMainPage failed for ${provider.name} - ${pageData.name.ifBlank { "Unknown Category" }}", ex ?: Exception("Unknown error"))
                    val errorMsg = ex?.localizedMessage ?: "Connection error"
                    updateState {
                        copy(categories = categories + (cacheKey to HomeCategoryUiState(isLoading = false, response = null, error = errorMsg)))
                    }
                }
            }
        }
    }

    private fun prefetchTopHistory(topHistory: List<com.lagradost.common.storage.WatchHistory>) {
        if (topHistory.isEmpty()) return
        viewModelScope.launch {
            heroRepository.prefetchTopHistory(topHistory, uiState.value.providers)
        }
    }

    private fun prefetchHeroItem(provider: MainAPI?, item: SearchResponse) {
        viewModelScope.launch {
            heroRepository.prefetchHeroItem(provider, item)
                .collect { update ->
                    if (update is HeroUpdate.Meta) {
                        updateState {
                            copy(heroMetaMap = heroMetaMap + (update.url to update.meta))
                        }
                    }
                }
        }
    }

    private fun reloadIcons() {
        viewModelScope.launch(Dispatchers.IO) {
            val icons = DesktopRepositoryManager.remotePluginIcons.value
            updateState { copy(mergedPluginIcons = icons) }
        }
    }

    private fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            removeWatchHistory.clearAll()
        }
    }

    private fun removeHistoryItem(parentId: String) {
        updateState { copy(historyList = historyList.filterNot { it.parentId == parentId }) }
        viewModelScope.launch(Dispatchers.IO) {
            removeWatchHistory.awaitByParent(parentId)
        }
    }

    private fun reloadProvider() {
        categoryCache.clear()
        categoryMutex.clear()
        discoveryCache.clear()
        discoveryReloadEpoch.update { it + 1L }
        updateState {
            copy(
                categories = emptyMap(),
                refreshEpoch = refreshEpoch + 1L,
            )
        }
    }

    private companion object {
        const val MAX_PARALLEL_DISCOVERY_LOADS = 3
    }
}
