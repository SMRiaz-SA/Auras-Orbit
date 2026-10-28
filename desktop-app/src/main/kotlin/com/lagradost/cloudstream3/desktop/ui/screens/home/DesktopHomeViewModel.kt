package com.lagradost.cloudstream3.desktop.ui.screens.home

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.DesktopErrorReporter
import com.lagradost.cloudstream3.desktop.core.preference.PreferenceKeys
import com.lagradost.cloudstream3.desktop.di.AppContainerHolder
import com.lagradost.cloudstream3.desktop.domain.bookmarks.interactor.GetBookmarks
import com.lagradost.cloudstream3.desktop.domain.customlists.repository.CustomListsRepository
import com.lagradost.cloudstream3.desktop.domain.hero.repository.HeroRepository
import com.lagradost.cloudstream3.desktop.domain.hero.repository.HeroRepository.HeroUpdate
import com.lagradost.cloudstream3.desktop.domain.history.interactor.GetContinueWatching
import com.lagradost.cloudstream3.desktop.domain.history.interactor.RemoveWatchHistory
import com.lagradost.cloudstream3.desktop.domain.providers.repository.ActiveProviderRepository
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.details.TmdbEpisodeCalendarRepository
import com.lagradost.cloudstream3.desktop.ui.screens.home.contract.HomeCategoryUiState
import com.lagradost.cloudstream3.desktop.ui.screens.home.contract.HomeUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.home.contract.HomeUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.home.contract.HomeUiState
import com.lagradost.cloudstream3.desktop.utils.KeyedMutex
import com.lagradost.common.collections.BoundedLruCache
import com.lagradost.common.performance.PerformanceDiagnosticStage
import com.lagradost.common.performance.PerformanceDiagnosticStatus
import com.lagradost.common.performance.PerformanceDiagnostics
import com.lagradost.common.performance.normalizePerformanceSourceLabel
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.runtime.executor.SafePluginInvoker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    private val customListsRepository: CustomListsRepository = AppContainerHolder.container.customListsRepository,
    private val activeProviderRepository: ActiveProviderRepository = AppContainerHolder.container.activeProviderRepository,
    private val heroRepository: HeroRepository = AppContainerHolder.container.heroRepository,
) : BaseMviViewModel<HomeUiState, HomeUiEvent, HomeUiEffect>(
    initialState = HomeUiState(),
) {
    private val upcomingRefreshLock = Any()
    private var upcomingRefreshJob: Job? = null

    @Volatile
    private var upcomingRefreshGeneration = 0L
    private val disabledCatalogWriteLock = Any()
    private val disabledCatalogWriteJobs = mutableMapOf<String, Job>()
    private val categoryCache = BoundedLruCache<String, com.lagradost.cloudstream3.HomePageResponse>(64)
    private val categoryMutex = KeyedMutex<String>()
    private data class CachedDiscoveryResult(
        val items: List<HomeDiscoveryItem>,
        val pageSources: List<HomeDiscoveryPageSource>,
    )

    private val discoveryCache = BoundedLruCache<String, CachedDiscoveryResult>(128)
    private val discoveryReloadEpoch = MutableStateFlow(0L)
    private var discoverySignature: String? = null
    private var lastDiscoveryReloadEpoch = -1L

    @Volatile
    private var discoverySources: Map<HomeDiscoveryKind, List<HomeDiscoverySource>> = emptyMap()
    private val discoveryConfigurationGeneration = AtomicLong(0L)
    private val discoverySourceGenerations = ConcurrentHashMap<String, Long>()
    private val discoverySourceJobs = ConcurrentHashMap<String, Job>()
    private var discoveryProfileId: Int? = null
    private val discoverySemaphore = Semaphore(MAX_PARALLEL_DISCOVERY_LOADS)

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

        viewModelScope.launch {
            customListsRepository.subscribeActive().collect { snapshot ->
                updateState {
                    copy(customLists = snapshot.lists, customListItems = snapshot.items)
                }
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
                com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfile
                    .map { it.id }
                    .distinctUntilChanged(),
            ) { (providers, disabledCatalogs), reloadEpoch, profileId ->
                Triple(providers, disabledCatalogs, reloadEpoch to profileId)
            }.collectLatest { (providers, disabledCatalogs, reload) ->
                configureDiscovery(providers, disabledCatalogs, reload.first, reload.second)
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

        viewModelScope.launch(Dispatchers.IO) {
            com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfile
                .map { it.id }
                .distinctUntilChanged()
                .collectLatest { profileId ->
                    synchronized(upcomingRefreshLock) {
                        upcomingRefreshGeneration++
                        upcomingRefreshJob?.cancel()
                        upcomingRefreshJob = null
                    }
                    updateState {
                        if (com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId == profileId) {
                            copy(upcomingIsRefreshing = false)
                        } else {
                            this
                        }
                    }
                    updateUpcomingFromCache(profileId)
                    refreshUpcoming(profileId, force = false)
                }
        }
        viewModelScope.launch(Dispatchers.IO) {
            DesktopDataStore.episodeTrackingUpdates.collect {
                updateUpcomingFromCache(com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId)
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
            is HomeUiEvent.OnProviderRefresh -> {
                reloadProvider()
                refreshUpcoming(com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId, force = false)
            }
            is HomeUiEvent.OnRefreshUpcoming -> refreshUpcoming(
                com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId,
                force = true,
            )
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
                persistDisabledCatalogs(event.providerName, newDisabled)
            }
            is HomeUiEvent.OnLoadCategory -> {
                loadCategory(event.provider, event.pageData)
            }
            is HomeUiEvent.OnRetryDiscovery -> retryDiscovery(event.kind)
            is HomeUiEvent.OnRetryDiscoverySource -> retryDiscoverySource(event.kind, event.sourceKey)
        }
    }

    private suspend fun configureDiscovery(
        providers: List<MainAPI>,
        disabledCatalogs: Map<String, Set<String>>,
        reloadEpoch: Long,
        profileId: Int,
    ) {
        val configurationGeneration = discoveryConfigurationGeneration.incrementAndGet()
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

        if (configurationGeneration != discoveryConfigurationGeneration.get() ||
            com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId != profileId
        ) {
            return
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
        val profileChanged = discoveryProfileId != null && discoveryProfileId != profileId
        if (signature == discoverySignature && reloadEpoch == lastDiscoveryReloadEpoch && !profileChanged) return

        val forceRefresh = profileChanged || (lastDiscoveryReloadEpoch >= 0 && reloadEpoch != lastDiscoveryReloadEpoch)
        val activeSourceKeys = sourcesByKind.values.flatten().map { it.cacheKey }.toSet()
        val invalidatedKeys = discoverySourceJobs.keys.filter { it !in activeSourceKeys || profileChanged || forceRefresh }
        invalidatedKeys.forEach { key ->
            discoverySourceJobs.remove(key)?.cancel()
            discoverySourceGenerations[key] = (discoverySourceGenerations[key] ?: 0L) + 1L
        }

        discoverySignature = signature
        lastDiscoveryReloadEpoch = reloadEpoch
        discoveryProfileId = profileId
        discoverySources = sourcesByKind

        val recentSources = sourcesByKind[HomeDiscoveryKind.RECENT].orEmpty()
        val popularSources = sourcesByKind[HomeDiscoveryKind.POPULAR].orEmpty()
        updateState {
            copy(
                recentDiscovery = buildDiscoverySectionState(
                    HomeDiscoveryKind.RECENT,
                    recentSources,
                    recentDiscovery,
                    forceRefresh,
                ),
                popularDiscovery = buildDiscoverySectionState(
                    HomeDiscoveryKind.POPULAR,
                    popularSources,
                    popularDiscovery,
                    forceRefresh,
                ),
            )
        }

        HomeDiscoveryKind.entries.forEach { kind ->
            val current = if (kind == HomeDiscoveryKind.RECENT) uiState.value.recentDiscovery else uiState.value.popularDiscovery
            sourcesByKind[kind].orEmpty().forEach { source ->
                val currentSource = current.sources.firstOrNull { it.sourceKey == source.cacheKey }
                if (forceRefresh || currentSource?.hasLoaded != true) {
                    startDiscoverySource(source, profileId, forceRefresh)
                }
            }
        }
    }

    private fun retryDiscovery(kind: HomeDiscoveryKind) {
        val current = if (kind == HomeDiscoveryKind.RECENT) {
            uiState.value.recentDiscovery
        } else {
            uiState.value.popularDiscovery
        }
        current.failedSourceKeys().forEach { sourceKey ->
            retryDiscoverySource(kind, sourceKey)
        }
        if (current.sources.none { it.error != null } && current.sources.isEmpty()) {
            discoverySources[kind].orEmpty().forEach { source ->
                startDiscoverySource(
                    source,
                    com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId,
                    forceRefresh = true,
                )
            }
        }
    }

    private fun retryDiscoverySource(kind: HomeDiscoveryKind, sourceKey: String) {
        val source = discoverySources[kind].orEmpty().firstOrNull { it.cacheKey == sourceKey } ?: return
        startDiscoverySource(
            source,
            com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId,
            forceRefresh = true,
        )
    }

    private fun buildDiscoverySectionState(
        kind: HomeDiscoveryKind,
        sources: List<HomeDiscoverySource>,
        previous: HomeDiscoverySectionState,
        refreshing: Boolean,
    ): HomeDiscoverySectionState {
        val previousByKey = previous.sources.associateBy { it.sourceKey }
        val sourceStates = sources.map { source ->
            val prior = previousByKey[source.cacheKey]
            val cached = discoveryCache[source.cacheKey]
            when {
                prior != null && refreshing -> prior.copy(isLoading = true, error = null)
                prior != null -> prior
                cached != null -> HomeDiscoverySourceState(
                    sourceKey = source.cacheKey,
                    sourceName = source.provider.name,
                    hasLoaded = true,
                    items = cached.items,
                    pageSources = cached.pageSources,
                )
                else -> HomeDiscoverySourceState(
                    sourceKey = source.cacheKey,
                    sourceName = source.provider.name,
                    isLoading = true,
                )
            }
        }
        return aggregateHomeDiscoverySources(sourceStates, sources.size)
    }

    private fun startDiscoverySource(source: HomeDiscoverySource, profileId: Int, forceRefresh: Boolean) {
        val key = source.cacheKey
        val existingJob = discoverySourceJobs[key]
        if (existingJob?.isActive == true && !forceRefresh) return
        if (forceRefresh) existingJob?.cancel()
        val generation = (discoverySourceGenerations[key] ?: 0L) + 1L
        discoverySourceGenerations[key] = generation

        val previous = getDiscoverySection(source.kind).sources.firstOrNull { it.sourceKey == key }
        val cached = discoveryCache[key]
        val diagnosticStartedAt = System.nanoTime()
        val diagnosticSource = normalizePerformanceSourceLabel(source.provider.name) ?: "unknown"
        updateDiscoverySource(source.kind, key, profileId, generation) { current ->
            current.copy(
                sourceName = source.provider.name,
                isLoading = true,
                error = null,
                items = previous?.items ?: cached?.items.orEmpty(),
                pageSources = previous?.pageSources ?: cached?.pageSources.orEmpty(),
            )
        }

        discoverySourceJobs[key] = viewModelScope.launch(Dispatchers.IO) {
            loadHomeDiscoverySourcesProgressively(
                sources = listOf(source),
                load = ::fetchDiscoverySource,
                onResult = { loadedSource, result ->
                    val sourceKey = loadedSource.cacheKey
                    if (isCurrentDiscoverySource(sourceKey, profileId, generation)) {
                        val failure = result.exceptionOrNull()
                        if (failure != null) {
                            PerformanceDiagnostics.recordAsync(
                                PerformanceDiagnosticStage.SOURCE_LOOKUP,
                                diagnosticSource,
                                profileId = profileId.toString(),
                                status = if (failure is kotlinx.coroutines.CancellationException) {
                                    PerformanceDiagnosticStatus.CANCELLED
                                } else {
                                    PerformanceDiagnosticStatus.FAILURE
                                },
                                durationMs = ((System.nanoTime() - diagnosticStartedAt) / 1_000_000L).coerceAtLeast(0L),
                            )
                            com.lagradost.common.logging.AppLogger.w(
                                "HomeDiscovery:${loadedSource.provider.name}",
                                "Could not load ${loadedSource.pageData.name}: ${failure.message}",
                            )
                            val message = failure.message?.takeIf(String::isNotBlank) ?: "Request failed"
                            updateDiscoverySource(loadedSource.kind, sourceKey, profileId, generation) { current ->
                                current.copy(isLoading = false, hasLoaded = true, error = message)
                            }
                        } else {
                            PerformanceDiagnostics.recordAsync(
                                PerformanceDiagnosticStage.SOURCE_LOOKUP,
                                diagnosticSource,
                                profileId = profileId.toString(),
                                status = PerformanceDiagnosticStatus.SUCCESS,
                                durationMs = ((System.nanoTime() - diagnosticStartedAt) / 1_000_000L).coerceAtLeast(0L),
                            )
                            val loaded = result.getOrThrow()
                            if (isCurrentDiscoverySource(sourceKey, profileId, generation)) {
                                discoveryCache[sourceKey] = loaded
                            }
                            updateDiscoverySource(loadedSource.kind, sourceKey, profileId, generation) { current ->
                                current.copy(
                                    isLoading = false,
                                    hasLoaded = true,
                                    error = null,
                                    items = loaded.items,
                                    pageSources = loaded.pageSources,
                                )
                            }
                        }
                    }
                },
            )
        }
    }

    private suspend fun fetchDiscoverySource(source: HomeDiscoverySource): CachedDiscoveryResult {
        val result = discoverySemaphore.withPermit {
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
            throw failure ?: IllegalStateException("Provider returned no catalog response.")
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
        return CachedDiscoveryResult(items, pageSources)
    }

    private fun getDiscoverySection(kind: HomeDiscoveryKind): HomeDiscoverySectionState =
        if (kind == HomeDiscoveryKind.RECENT) uiState.value.recentDiscovery else uiState.value.popularDiscovery

    private fun isCurrentDiscoverySource(key: String, profileId: Int, generation: Long): Boolean =
        discoverySourceGenerations[key] == generation &&
            com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId == profileId &&
            discoverySources.values.flatten().any { it.cacheKey == key }

    private fun updateDiscoverySource(
        kind: HomeDiscoveryKind,
        key: String,
        profileId: Int,
        generation: Long,
        transform: (HomeDiscoverySourceState) -> HomeDiscoverySourceState,
    ) {
        if (!isCurrentDiscoverySource(key, profileId, generation)) return
        updateState {
            val current = if (kind == HomeDiscoveryKind.RECENT) recentDiscovery else popularDiscovery
            if (!isCurrentDiscoverySource(key, profileId, generation)) return@updateState this
            val sourceStates = current.sources.map { state ->
                if (state.sourceKey == key) transform(state) else state
            }
            val next = aggregateHomeDiscoverySources(sourceStates, current.sourceCount)
            when (kind) {
                HomeDiscoveryKind.RECENT -> copy(recentDiscovery = next)
                HomeDiscoveryKind.POPULAR -> copy(popularDiscovery = next)
            }
        }
    }

    private fun loadCategory(provider: MainAPI, pageData: MainPageData) {
        val cacheKey = homeCategoryStateKey(provider, pageData)
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
            categoryMutex.withLock(cacheKey) {
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

    private fun persistDisabledCatalogs(providerName: String, values: Set<String>) {
        synchronized(disabledCatalogWriteLock) {
            val previousWrite = disabledCatalogWriteJobs[providerName]
            disabledCatalogWriteJobs[providerName] = viewModelScope.launch(Dispatchers.IO) {
                previousWrite?.join()
                DesktopDataStore.setKey(PreferenceKeys.disabledCatalogsKey(providerName), values)
            }
        }
    }

    private fun reloadProvider() {
        categoryCache.clear()
        discoveryReloadEpoch.update { it + 1L }
        updateState {
            copy(
                categories = emptyMap(),
                refreshEpoch = refreshEpoch + 1L,
            )
        }
    }

    private fun updateUpcomingFromCache(profileId: Int) {
        val followed = DesktopDataStore.getFollowedShows(profileId)
        val firstError = followed.firstNotNullOfOrNull { it.lastRefreshError }
        val upcoming = TmdbEpisodeCalendarRepository.getUpcoming(profileId)
        updateState {
            if (com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId == profileId) {
                copy(
                    followedSeriesCount = followed.size,
                    upcomingEpisodes = upcoming,
                    upcomingRefreshError = firstError,
                )
            } else {
                this
            }
        }
    }

    private fun refreshUpcoming(profileId: Int, force: Boolean) {
        if (DesktopDataStore.getFollowedShows(profileId).isEmpty()) {
            synchronized(upcomingRefreshLock) {
                if (com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId != profileId) return
                upcomingRefreshJob?.cancel()
                upcomingRefreshJob = null
                upcomingRefreshGeneration++
            }
            updateState {
                if (com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId == profileId) {
                    copy(upcomingIsRefreshing = false)
                } else {
                    this
                }
            }
            updateUpcomingFromCache(profileId)
            return
        }

        synchronized(upcomingRefreshLock) {
            if (com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId != profileId) return
            if (!force && upcomingRefreshJob?.isActive == true) return
            upcomingRefreshJob?.cancel()
            val generation = ++upcomingRefreshGeneration
            updateState { copy(upcomingIsRefreshing = true) }
            upcomingRefreshJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val refreshError = TmdbEpisodeCalendarRepository.refreshProfile(profileId, force)
                    if (generation == upcomingRefreshGeneration &&
                        com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId == profileId
                    ) {
                        updateUpcomingFromCache(profileId)
                        updateState {
                            if (generation == upcomingRefreshGeneration &&
                                com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId == profileId
                            ) {
                                copy(
                                    upcomingIsRefreshing = false,
                                    upcomingRefreshError = refreshError ?: upcomingRefreshError,
                                )
                            } else {
                                this
                            }
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (generation == upcomingRefreshGeneration &&
                        com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId == profileId
                    ) {
                        updateUpcomingFromCache(profileId)
                        updateState {
                            if (generation == upcomingRefreshGeneration &&
                                com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId == profileId
                            ) {
                                copy(
                                    upcomingIsRefreshing = false,
                                    upcomingRefreshError = failure.localizedMessage ?: "Could not refresh episode dates",
                                )
                            } else {
                                this
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_PARALLEL_DISCOVERY_LOADS = 3
    }
}
