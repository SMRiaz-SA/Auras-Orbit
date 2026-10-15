package com.lagradost.cloudstream3.desktop.search

import com.lagradost.cloudstream3.desktop.ui.screens.search.contract.SearchMode
import com.lagradost.cloudstream3.desktop.ui.screens.search.contract.routeQuery
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SearchQueryRoutingMatrixTest {
    @TestFactory
    fun routesFiftyQueriesIndependentlyForTitlesAndPeople(): List<DynamicTest> =
        searchQueries().flatMapIndexed { index, query ->
            SearchMode.entries.map { mode ->
                DynamicTest.dynamicTest("$mode query case $index") {
                    val routing = mode.routeQuery(query, titleSuggestionsEnabled = true)
                    val normalizedQuery = query.trim()

                    assertEquals(normalizedQuery, routing.normalizedQuery)
                    assertEquals(mode.showsTitles, routing.showsTitles)
                    assertEquals(mode.showsPeople, routing.showsPeople)
                    assertEquals(mode.showsTitles && normalizedQuery.isNotEmpty(), routing.canSearchTitles)
                    assertEquals(mode.showsPeople && normalizedQuery.length >= 2, routing.canSearchPeople)
                    assertEquals(mode.showsTitles && normalizedQuery.length >= 2, routing.fetchTitleSuggestions)
                    assertEquals(mode.showsTitles && normalizedQuery.isEmpty(), routing.showTitleHistory)
                    assertFalse(mode.routeQuery(query, titleSuggestionsEnabled = false).fetchTitleSuggestions)
                    assertFalse(routing.showsTitles && routing.showsPeople)
                }
            }
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
