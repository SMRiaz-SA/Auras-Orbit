package com.lagradost.cloudstream3.desktop.search

import com.lagradost.cloudstream3.desktop.ui.screens.search.contract.SearchMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchModeTest {
    @Test
    fun titleAndPeopleModesNeverShowTheOtherResultType() {
        assertTrue(SearchMode.TITLES.showsTitles)
        assertFalse(SearchMode.TITLES.showsPeople)
        assertTrue(SearchMode.PEOPLE.showsPeople)
        assertFalse(SearchMode.PEOPLE.showsTitles)
    }

    @Test
    fun modesExposeOnlyTitlesAndPeople() {
        assertEquals(setOf(SearchMode.TITLES, SearchMode.PEOPLE), SearchMode.entries.toSet())
    }
}
