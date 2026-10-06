package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.ui.components.CategoryRowWithHeader
import com.lagradost.cloudstream3.desktop.ui.components.PosterCard
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import com.lagradost.cloudstream3.desktop.ui.screens.CategoryGridCache
import com.lagradost.cloudstream3.desktop.ui.screens.CategoryGridPageSource
import com.lagradost.cloudstream3.desktop.ui.screens.categoryGridItemKey
import com.lagradost.cloudstream3.desktop.ui.screens.home.contract.HomeUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.home.contract.HomeUiState
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopCustomList
import com.lagradost.common.storage.WatchHistory

@Composable
fun HomeLibraryRow(
    bookmarks: List<DesktopBookmark>,
    providersByName: Map<String, MainAPI>,
    onViewAll: () -> Unit,
    onBrowse: () -> Unit,
    onOpenBookmark: (DesktopBookmark, Boolean) -> Unit,
) {
    val sortedBookmarks = remember(bookmarks) { bookmarks.sortedByDescending(DesktopBookmark::dateAdded) }
    CategoryRowWithHeader(
        title = "My Library",
        itemCount = sortedBookmarks.size.coerceAtLeast(1),
        onViewAll = onViewAll,
        rowContentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        headerPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
    ) {
        if (sortedBookmarks.isEmpty()) {
            item(key = "home-library-empty") {
                Card(
                    modifier = Modifier.width(280.dp).height(172.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("Save titles to find them here", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Add a movie or series to your library from its details page.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onBrowse) { Text("Browse titles") }
                    }
                }
            }
        } else {
            items(sortedBookmarks.take(30), key = { "${it.apiName}:${it.url}" }) { bookmark ->
                val response = remember(bookmark) { BookmarkSearchResponse(bookmark) }
                PosterCard(
                    item = response,
                    provider = providersByName[bookmark.apiName],
                    onClick = { onOpenBookmark(bookmark, false) },
                    onPlayClick = { onOpenBookmark(bookmark, true) },
                )
            }
        }
    }
}

@Composable
private fun HomePinnedCustomListRows(
    lists: List<DesktopCustomList>,
    memberships: List<com.lagradost.common.storage.DesktopCustomListItem>,
    bookmarks: List<DesktopBookmark>,
    providersByName: Map<String, MainAPI>,
    onViewAll: () -> Unit,
    onOpenBookmark: (DesktopBookmark, Boolean) -> Unit,
) {
    val bookmarksById = remember(bookmarks) { bookmarks.associateBy(DesktopBookmark::id) }
    val shelves = remember(lists, memberships, bookmarksById) {
        lists.asSequence()
            .filter(DesktopCustomList::showOnHome)
            .mapNotNull { list ->
                val entries = memberships.asSequence()
                    .filter { it.listId == list.id }
                    .mapNotNull { bookmarksById[it.bookmarkId] }
                    .toList()
                entries.takeIf { it.isNotEmpty() }?.let { list to it }
            }
            .toList()
    }

    shelves.forEach { (list, entries) ->
        CategoryRowWithHeader(
            title = list.name,
            itemCount = entries.size,
            onViewAll = onViewAll,
            rowContentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            headerPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
        ) {
            items(entries.take(30), key = DesktopBookmark::id) { bookmark ->
                val response = remember(bookmark) { BookmarkSearchResponse(bookmark) }
                PosterCard(
                    item = response,
                    provider = providersByName[bookmark.apiName],
                    onClick = { onOpenBookmark(bookmark, false) },
                    onPlayClick = { onOpenBookmark(bookmark, true) },
                )
            }
        }
    }
}

@Composable
fun HomeDiscoveryRows(
    recent: HomeDiscoverySectionState,
    popular: HomeDiscoverySectionState,
    history: List<WatchHistory>,
    bookmarks: List<DesktopBookmark>,
    providersByName: Map<String, MainAPI>,
    onRetry: (HomeDiscoveryKind) -> Unit,
    onViewAll: (String, List<HomeDiscoveryItem>, List<HomeDiscoveryPageSource>) -> Unit,
    onOpenItem: (HomeDiscoveryItem, Boolean) -> Unit,
) {
    HomeDiscoveryRow(
        title = "Recently Added / Updated",
        state = recent,
        providersByName = providersByName,
        onRetry = { onRetry(HomeDiscoveryKind.RECENT) },
        onViewAll = { onViewAll("Recently Added / Updated", recent.items, recent.pageSources) },
        onOpenItem = onOpenItem,
    )

    val recommendations = remember(popular.items, recent.items, history, bookmarks) {
        recommendHomeItems(
            candidates = mergeHomeDiscoveryItems(recent.items + popular.items),
            seedTitles = history.map(WatchHistory::showName) + bookmarks.map(DesktopBookmark::name),
        )
    }
    if (recommendations.isNotEmpty()) {
        HomeDiscoveryRow(
            title = "Recommended for You",
            state = HomeDiscoverySectionState(hasLoaded = true, items = recommendations),
            providersByName = providersByName,
            onRetry = {},
            onViewAll = { onViewAll("Recommended for You", recommendations, emptyList()) },
            onOpenItem = onOpenItem,
        )
    }

    HomeDiscoveryRow(
        title = "Trending / Popular",
        state = popular,
        providersByName = providersByName,
        onRetry = { onRetry(HomeDiscoveryKind.POPULAR) },
        onViewAll = { onViewAll("Trending / Popular", popular.items, popular.pageSources) },
        onOpenItem = onOpenItem,
    )
}

@Composable
fun HomeDashboardRows(
    uiState: HomeUiState,
    viewModel: DesktopHomeViewModel,
    showContinueWatching: Boolean,
    sectionOrder: List<HomeFeedSectionKey> = HomeFeedSectionKey.defaultOrder,
    disabledSections: Set<HomeFeedSectionKey> = emptySet(),
    renderContinueWatching: Boolean = true,
    onNavigate: (Config) -> Unit,
) {
    val safeArea = com.lagradost.cloudstream3.desktop.ui.LocalSafeArea.current
    val layoutDirection = LocalLayoutDirection.current
    val safeStart = safeArea.calculateStartPadding(layoutDirection)
    val safeEnd = safeArea.calculateEndPadding(layoutDirection)
    val providersByName = remember(uiState.providers, uiState.activeProviderApis) {
        (APIHolder.allProviders.toList() + uiState.providers + uiState.activeProviderApis)
            .associateBy(MainAPI::name)
    }
    val bookmarks = remember(uiState.bookmarks) { uiState.bookmarks.values.sortedByDescending(DesktopBookmark::dateAdded) }
    val recommendations = remember(
        uiState.popularDiscovery.items,
        uiState.recentDiscovery.items,
        uiState.historyList,
        uiState.bookmarks,
    ) {
        recommendHomeItems(
            candidates = mergeHomeDiscoveryItems(uiState.recentDiscovery.items + uiState.popularDiscovery.items),
            seedTitles = uiState.historyList.map(WatchHistory::showName) + bookmarks.map(DesktopBookmark::name),
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        val openBookmark: (DesktopBookmark, Boolean) -> Unit = { bookmark, autoPlay ->
            if (bookmark.apiName == com.lagradost.cloudstream3.desktop.ui.GlobalMediaLauncher.NETWORK_STREAM_API_NAME) {
                com.lagradost.cloudstream3.desktop.ui.GlobalMediaLauncher.playNetworkStreamBookmark(bookmark)
            } else {
                onNavigate(
                    Config.Details(
                        bookmark.apiName,
                        bookmark.url,
                        bookmark.name,
                        bookmark.posterUrl,
                        null,
                        autoPlay,
                    ),
                )
            }
        }

        val onViewDiscovery: (String, List<HomeDiscoveryItem>, List<HomeDiscoveryPageSource>) -> Unit = { title, homeItems, homeSources ->
            val primaryProviderName = homeItems.firstOrNull()?.providerName
            val primaryProvider = primaryProviderName?.let { providersByName[it] ?: APIHolder.getApiFromNameNull(it) }
            if (primaryProvider != null) {
                val itemProviders = buildMap {
                    homeItems.forEach { homeItem ->
                        val itemProvider = providersByName[homeItem.providerName]
                            ?: APIHolder.getApiFromNameNull(homeItem.providerName)
                            ?: return@forEach
                        put(categoryGridItemKey(homeItem.providerName, homeItem.response.url), itemProvider)
                        put(categoryGridItemKey(homeItem.response.apiName, homeItem.response.url), itemProvider)
                    }
                }
                val pageSources = homeSources.map { source ->
                    CategoryGridPageSource(
                        provider = source.provider,
                        request = source.request,
                        sectionName = source.sectionName,
                        nextPage = source.nextPage,
                        hasNext = source.hasNext,
                    )
                }
                CategoryGridCache.putMergedHomeCategory(
                    providerName = primaryProvider.name,
                    title = title,
                    items = homeItems.map(HomeDiscoveryItem::response),
                    itemProviders = itemProviders,
                    sources = pageSources,
                )
                onNavigate(Config.CategoryGrid(primaryProvider.name, title))
            }
        }
        val onOpenDiscoveryItem: (HomeDiscoveryItem, Boolean) -> Unit = { homeItem, autoPlay ->
            onNavigate(
                Config.Details(
                    homeItem.providerName,
                    homeItem.response.url,
                    homeItem.response.name,
                    homeItem.response.posterUrl,
                    null,
                    autoPlay,
                ),
            )
        }

        sectionOrder.forEach { section ->
            if (section in disabledSections) return@forEach
            when (section) {
                HomeFeedSectionKey.CONTINUE_WATCHING -> if (renderContinueWatching && showContinueWatching && uiState.historyList.isNotEmpty()) {
                    HomeContinueWatchingRow(uiState, viewModel, onNavigate)
                }
                HomeFeedSectionKey.UPCOMING_EPISODES -> HomeUpcomingEpisodesRow(
                    followedSeriesCount = uiState.followedSeriesCount,
                    episodes = uiState.upcomingEpisodes,
                    isRefreshing = uiState.upcomingIsRefreshing,
                    refreshError = uiState.upcomingRefreshError,
                    onRefresh = { viewModel.onEvent(HomeUiEvent.OnRefreshUpcoming) },
                    onOpenEpisode = { episode ->
                        onNavigate(
                            Config.Details(
                                providerName = episode.providerName,
                                url = episode.showUrl,
                                preloadedName = episode.showName,
                                preloadedPoster = episode.posterUrl,
                                autoPlay = false,
                                targetSeason = episode.seasonNumber,
                                targetEpisode = episode.episodeNumber,
                            ),
                        )
                    },
                )
                HomeFeedSectionKey.LIBRARY -> Box(Modifier.fillMaxWidth().padding(start = safeStart, end = safeEnd)) {
                    HomeLibraryRow(
                        bookmarks = bookmarks,
                        providersByName = providersByName,
                        onViewAll = { onNavigate(Config.Library) },
                        onBrowse = { onNavigate(Config.Explore) },
                        onOpenBookmark = openBookmark,
                    )
                }
                HomeFeedSectionKey.CUSTOM_LISTS -> Box(Modifier.fillMaxWidth().padding(start = safeStart, end = safeEnd)) {
                    HomePinnedCustomListRows(
                        lists = uiState.customLists,
                        memberships = uiState.customListItems,
                        bookmarks = bookmarks,
                        providersByName = providersByName,
                        onViewAll = { onNavigate(Config.Library) },
                        onOpenBookmark = openBookmark,
                    )
                }
                HomeFeedSectionKey.RECENTLY_UPDATED -> Box(Modifier.fillMaxWidth().padding(start = safeStart, end = safeEnd)) {
                    HomeDiscoveryRow(
                        title = "Recently Added / Updated",
                        state = uiState.recentDiscovery,
                        providersByName = providersByName,
                        onRetry = { viewModel.onEvent(HomeUiEvent.OnRetryDiscovery(HomeDiscoveryKind.RECENT)) },
                        onViewAll = { onViewDiscovery("Recently Added / Updated", uiState.recentDiscovery.items, uiState.recentDiscovery.pageSources) },
                        onOpenItem = onOpenDiscoveryItem,
                    )
                }
                HomeFeedSectionKey.RECOMMENDED -> if (recommendations.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(start = safeStart, end = safeEnd)) {
                        HomeDiscoveryRow(
                            title = "Recommended for You",
                            state = HomeDiscoverySectionState(hasLoaded = true, items = recommendations),
                            providersByName = providersByName,
                            onRetry = {},
                            onViewAll = { onViewDiscovery("Recommended for You", recommendations, emptyList()) },
                            onOpenItem = onOpenDiscoveryItem,
                        )
                    }
                }
                HomeFeedSectionKey.TRENDING -> Box(Modifier.fillMaxWidth().padding(start = safeStart, end = safeEnd)) {
                    HomeDiscoveryRow(
                        title = "Trending / Popular",
                        state = uiState.popularDiscovery,
                        providersByName = providersByName,
                        onRetry = { viewModel.onEvent(HomeUiEvent.OnRetryDiscovery(HomeDiscoveryKind.POPULAR)) },
                        onViewAll = { onViewDiscovery("Trending / Popular", uiState.popularDiscovery.items, uiState.popularDiscovery.pageSources) },
                        onOpenItem = onOpenDiscoveryItem,
                    )
                }
            }
        }
    }
}

@Composable
fun HomeContinueWatchingRow(
    uiState: HomeUiState,
    viewModel: DesktopHomeViewModel,
    onNavigate: (Config) -> Unit,
) {
    val openHistory: (MainAPI, WatchHistory, Boolean) -> Unit = { provider, history, autoPlay ->
        onNavigate(
            Config.Details(
                providerName = provider.name,
                url = history.showUrl,
                preloadedName = history.showName,
                preloadedPoster = history.posterUrl,
                autoPlay = autoPlay,
                targetSeason = history.season,
                targetEpisodeId = history.episodeId,
                targetEpisode = history.episode,
                playNextEpisode = autoPlay && history.duration == 0L && history.position == 0L,
            ),
        )
    }

    HomeHistoryRow(
        historyList = uiState.historyList,
        providers = uiState.providers,
        onClearHistory = { viewModel.onEvent(HomeUiEvent.OnClearHistory) },
        onRemoveHistoryItem = { viewModel.onEvent(HomeUiEvent.OnRemoveHistoryItem(it)) },
        onViewAllClick = { onNavigate(Config.History) },
        onItemClick = { provider, history -> openHistory(provider, history, false) },
        onPlayClick = { provider, history -> openHistory(provider, history, true) },
        onPrimaryItemClick = { provider, history -> openHistory(provider, history, true) },
    )
}

@Composable
private fun HomeDiscoveryRow(
    title: String,
    state: HomeDiscoverySectionState,
    providersByName: Map<String, MainAPI>,
    onRetry: () -> Unit,
    onViewAll: () -> Unit,
    onOpenItem: (HomeDiscoveryItem, Boolean) -> Unit,
) {
    if (!state.isLoading && state.items.isEmpty() && state.failedSourceCount == 0) return

    val hasItems = state.items.isNotEmpty()
    CategoryRowWithHeader(
        title = title,
        itemCount = state.items.size.coerceAtLeast(1),
        onViewAll = onViewAll.takeIf { hasItems },
        trailingHeaderExtra = if (hasItems && state.failedSourceCount > 0) {
            {
                TextButton(onClick = onRetry) { Text("Retry ${state.failedSourceCount}") }
            }
        } else {
            null
        },
        rowContentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        headerPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
    ) {
        if (hasItems) {
            items(state.items.take(30), key = { "${it.providerName}:${it.response.url}" }) { homeItem ->
                PosterCard(
                    item = homeItem.response,
                    provider = providersByName[homeItem.providerName],
                    onClick = { onOpenItem(homeItem, false) },
                    onPlayClick = { onOpenItem(homeItem, true) },
                )
            }
        } else {
            item(key = "$title-loading-or-error") {
                Card(
                    modifier = Modifier.width(280.dp).height(112.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.isLoading) CircularProgressIndicator(modifier = Modifier.width(24.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (state.isLoading) "Loading catalogs…" else "Could not load this shelf",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!state.isLoading) {
                                TextButton(onClick = onRetry) { Text("Retry") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class BookmarkSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = null,
    override var posterUrl: String? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var score: Score? = null,
) : SearchResponse {
    constructor(bookmark: DesktopBookmark) : this(
        name = bookmark.name,
        url = bookmark.url,
        apiName = bookmark.apiName,
        posterUrl = bookmark.posterUrl,
    )
}
