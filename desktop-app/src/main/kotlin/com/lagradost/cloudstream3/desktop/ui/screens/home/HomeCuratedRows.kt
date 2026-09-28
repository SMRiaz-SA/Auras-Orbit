package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
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

    if (showContinueWatching && uiState.historyList.isNotEmpty()) {
        HomeHistoryRow(
            historyList = uiState.historyList,
            providers = uiState.providers,
            onClearHistory = { viewModel.onEvent(HomeUiEvent.OnClearHistory) },
            onRemoveHistoryItem = { viewModel.onEvent(HomeUiEvent.OnRemoveHistoryItem(it)) },
            onViewAllClick = { onNavigate(Config.History) },
            onItemClick = { provider, history ->
                onNavigate(
                    Config.Details(
                        provider.name,
                        history.showUrl,
                        history.showName,
                        history.posterUrl,
                        null,
                        autoPlay = false,
                        targetSeason = history.season,
                        targetEpisodeId = history.episodeId,
                    ),
                )
            },
            onPlayClick = { provider, history ->
                onNavigate(
                    Config.Details(
                        provider.name,
                        history.showUrl,
                        history.showName,
                        history.posterUrl,
                        null,
                        autoPlay = true,
                        targetSeason = history.season,
                        targetEpisodeId = history.episodeId,
                    ),
                )
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = safeStart, end = safeEnd),
    ) {
        HomeLibraryRow(
            bookmarks = bookmarks,
            providersByName = providersByName,
            onViewAll = { onNavigate(Config.Library) },
            onBrowse = { onNavigate(Config.Explore) },
            onOpenBookmark = { bookmark, autoPlay ->
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
            },
        )

        HomeDiscoveryRows(
            recent = uiState.recentDiscovery,
            popular = uiState.popularDiscovery,
            history = uiState.historyList,
            bookmarks = bookmarks,
            providersByName = providersByName,
            onRetry = { viewModel.onEvent(HomeUiEvent.OnRetryDiscovery(it)) },
            onViewAll = { title, homeItems, homeSources ->
                val primaryProviderName = homeItems.firstOrNull()?.providerName ?: return@HomeDiscoveryRows
                val primaryProvider = providersByName[primaryProviderName]
                    ?: APIHolder.getApiFromNameNull(primaryProviderName)
                    ?: return@HomeDiscoveryRows
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
            },
            onOpenItem = { homeItem, autoPlay ->
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
            },
        )
    }
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
