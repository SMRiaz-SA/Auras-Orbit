package com.lagradost.cloudstream3.desktop.ui.screens.search.contract

import androidx.compose.runtime.Immutable
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.ui.base.UiState
import com.lagradost.cloudstream3.desktop.ui.screens.person.model.TmdbPersonCandidate

enum class SearchMode {
    TITLES,
    PEOPLE,
    ;

    val showsTitles: Boolean
        get() = this == TITLES

    val showsPeople: Boolean
        get() = this == PEOPLE
}

internal data class SearchQueryRouting(
    val normalizedQuery: String,
    val showsTitles: Boolean,
    val showsPeople: Boolean,
    val canSearchTitles: Boolean,
    val canSearchPeople: Boolean,
    val fetchTitleSuggestions: Boolean,
    val showTitleHistory: Boolean,
)

internal fun SearchMode.routeQuery(
    query: String,
    titleSuggestionsEnabled: Boolean = true,
): SearchQueryRouting {
    val normalizedQuery = query.trim()
    val hasQuery = normalizedQuery.isNotEmpty()
    val hasPeopleSearchLength = normalizedQuery.length >= 2

    return SearchQueryRouting(
        normalizedQuery = normalizedQuery,
        showsTitles = showsTitles,
        showsPeople = showsPeople,
        canSearchTitles = showsTitles && hasQuery,
        canSearchPeople = showsPeople && hasPeopleSearchLength,
        fetchTitleSuggestions = showsTitles && titleSuggestionsEnabled && hasPeopleSearchLength,
        showTitleHistory = showsTitles && !hasQuery,
    )
}

@Immutable
data class SearchProviderPagination(
    val nextPage: Int = 2,
    val canPaginate: Boolean = true,
    val isLoadingMore: Boolean = false,
)

@Immutable
data class SearchUiState(
    val searchQuery: String = "",
    val searchMode: SearchMode = SearchMode.TITLES,
    val searchResultsGrouped: Map<String, Pair<com.lagradost.cloudstream3.MainAPI, List<SearchResponse>>>? = null,
    val peopleResults: List<TmdbPersonCandidate> = emptyList(),
    val peopleSearchFailed: Boolean = false,
    val isLoadingSearch: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canPaginate: Boolean = true,
    val isGlobalSearchEnabled: Boolean = false,
    val selectedProviderName: String? = null,
    val selectedProviderSource: String? = null,
    val selectedCategories: Set<TvType> = emptySet(),
    val pluginIcons: Map<String, String> = emptyMap(),
    val providers: List<com.lagradost.cloudstream3.MainAPI> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val searchSuggestions: List<com.lagradost.cloudstream3.desktop.ui.screens.search.SearchSuggestionItem> = emptyList(),
    val showSuggestions: Boolean = false,
    val isLoadingSuggestions: Boolean = false,
    val awaitingSearchSubmission: Boolean = false,
    val providerTypeFilter: Set<TvType> = emptySet(),
    val providerPagination: Map<String, SearchProviderPagination> = emptyMap(),
    val failedProviderKeys: Set<String> = emptySet(),
) : UiState
