package com.lagradost.cloudstream3.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SearchResultScopeRoutingMatrixTest(
    private val scopeName: String,
    private val queryIndex: Int,
    private val query: String,
) {
    @Test
    fun routesOneQueryToExactlyOneResultType() {
        val scope = SearchResultScope.valueOf(scopeName)
        val routing = scope.routeQuery(query, titleSuggestionsEnabled = true)
        val normalizedQuery = query.trim()

        assertEquals("query case $queryIndex", normalizedQuery, routing.normalizedQuery)
        assertEquals("query case $queryIndex", scope.showsTitles, routing.showsTitles)
        assertEquals("query case $queryIndex", scope.showsPeople, routing.showsPeople)
        assertEquals("query case $queryIndex", scope.showsTitles && normalizedQuery.isNotEmpty(), routing.canSearchTitles)
        assertEquals("query case $queryIndex", scope.showsPeople && normalizedQuery.length >= 2, routing.canSearchPeople)
        assertEquals(
            "query case $queryIndex",
            scope.showsTitles && normalizedQuery.length >= 2,
            routing.fetchTitleSuggestions,
        )
        assertEquals("query case $queryIndex", scope.showsTitles && normalizedQuery.isEmpty(), routing.showTitleHistory)
        assertFalse(scope.routeQuery(query, titleSuggestionsEnabled = false).fetchTitleSuggestions)
        assertFalse(routing.showsTitles && routing.showsPeople)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, query case {1}")
        fun parameters(): Collection<Array<Any>> =
            searchQueries().flatMapIndexed { index, query ->
                SearchResultScope.entries.map { scope -> arrayOf<Any>(scope.name, index, query) }
            }

        private fun searchQueries(): List<String> =
            listOf(
                "",
                " ",
                "\t",
                "\n",
                "a",
                " A ",
                "ab",
                " ab ",
                "Brad Pitt",
                "Dune",
                "One Piece",
                "Björk",
                "O'Neal",
                "Jean-Luc",
                "東京",
                "中文",
                "😀",
                "a b",
                "a\nb",
                "  actor  ",
            ) + (0 until 30).map { "actor-$it" }
    }
}
