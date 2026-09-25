package com.lagradost.cloudstream3.desktop.ui.screens.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeCatalogFiltersTest {
    @Test
    fun `hides korean home catalog labels`() {
        assertTrue(isHiddenHomeCatalogTitle("Korean"))
        assertTrue(isHiddenHomeCatalogTitle("K-Dramas"))
        assertTrue(isHiddenHomeCatalogTitle("K drama classics"))
        assertTrue(isHiddenHomeCatalogTitle("KDrama Trending"))
    }

    @Test
    fun `keeps unrelated home catalog labels`() {
        assertFalse(isHiddenHomeCatalogTitle("Popular Movies"))
        assertFalse(isHiddenHomeCatalogTitle("Japanese Anime"))
        assertFalse(isHiddenHomeCatalogTitle("International Series"))
    }
}
