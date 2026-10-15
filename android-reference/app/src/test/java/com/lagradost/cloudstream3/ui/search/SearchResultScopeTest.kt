package com.lagradost.cloudstream3.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultScopeTest {
    @Test
    fun titleAndPeopleScopesNeverShowTheOtherResultType() {
        assertTrue(SearchResultScope.TITLES.showsTitles)
        assertFalse(SearchResultScope.TITLES.showsPeople)
        assertTrue(SearchResultScope.PEOPLE.showsPeople)
        assertFalse(SearchResultScope.PEOPLE.showsTitles)
    }

    @Test
    fun scopesExposeOnlyTitlesAndPeople() {
        assertEquals(setOf(SearchResultScope.TITLES, SearchResultScope.PEOPLE), SearchResultScope.entries.toSet())
    }
}
