package com.lagradost.cloudstream3.ui.search

internal enum class SearchResultScope {
    TITLES,
    PEOPLE;

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

internal fun SearchResultScope.routeQuery(
    query: String,
    titleSuggestionsEnabled: Boolean,
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
