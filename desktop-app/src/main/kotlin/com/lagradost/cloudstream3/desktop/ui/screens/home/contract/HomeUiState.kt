package com.lagradost.cloudstream3.desktop.ui.screens.home.contract

import androidx.compose.runtime.Immutable
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.desktop.DesktopErrorReporter
import com.lagradost.cloudstream3.desktop.repo.HeroMeta
import com.lagradost.cloudstream3.desktop.ui.base.UiState
import com.lagradost.cloudstream3.desktop.ui.screens.details.UpcomingEpisode
import com.lagradost.cloudstream3.desktop.ui.screens.home.HomeDiscoverySectionState
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopCustomList
import com.lagradost.common.storage.DesktopCustomListItem
import com.lagradost.common.storage.WatchHistory

@Immutable
data class HomeCategoryUiState(
    val isLoading: Boolean = false,
    val response: com.lagradost.cloudstream3.HomePageResponse? = null,
    val error: String? = null,
)

@Immutable
data class HomeUiState(
    val providers: List<MainAPI> = emptyList(),
    val activeProviderApis: List<MainAPI> = emptyList(),

    val errorSnapshot: String = DesktopErrorReporter.getSnapshot(),
    val historyList: List<WatchHistory> = emptyList(),
    val mergedPluginIcons: Map<String, String> = emptyMap(),
    val heroMetaMap: Map<String, HeroMeta> = emptyMap(),
    val bookmarks: Map<String, DesktopBookmark> = emptyMap(),
    val customLists: List<DesktopCustomList> = emptyList(),
    val customListItems: List<DesktopCustomListItem> = emptyList(),
    val disabledCatalogs: Map<String, Set<String>> = emptyMap(),
    val showHomeManagement: Boolean = false,
    val categories: Map<String, HomeCategoryUiState> = emptyMap(),
    val recentDiscovery: HomeDiscoverySectionState = HomeDiscoverySectionState(),
    val popularDiscovery: HomeDiscoverySectionState = HomeDiscoverySectionState(),
    val followedSeriesCount: Int = 0,
    val upcomingEpisodes: List<UpcomingEpisode> = emptyList(),
    val upcomingIsRefreshing: Boolean = false,
    val upcomingRefreshError: String? = null,
    val refreshEpoch: Long = 0L,
) : UiState {
    val activeProviders: List<String>
        get() = activeProviderApis.map { com.lagradost.cloudstream3.desktop.repo.ActiveProviderRepository.getProviderKey(it) }
}
