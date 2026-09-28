package com.lagradost.cloudstream3.desktop.ui.screens.home

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `home catalogs skip providers without a home page and blank placeholders`() {
        val noHomePage = object : MainAPI() {
            override var name = "Search only"
            override var mainUrl = "https://search-only.invalid"
            override val hasMainPage = false
            override val mainPage: List<MainPageData>
                get() = error("Providers without a home page must not expose their placeholder catalog")
        }
        val withHomePage = object : MainAPI() {
            override var name = "Catalog provider"
            override var mainUrl = "https://catalog.invalid"
            override val hasMainPage = true
            override val mainPage = listOf(
                MainPageData("", "", false),
                MainPageData("Popular Movies", "popular", false),
                MainPageData("Latest Series", "latest", false),
            )
        }

        val pages = homeCatalogPages(
            listOf(noHomePage, withHomePage),
            mapOf("Catalog provider" to setOf("Latest Series")),
        )

        assertEquals(listOf("Popular Movies"), pages.map { it.second.name })
        assertEquals(listOf("Catalog provider"), pages.map { it.first.name })
    }
}
