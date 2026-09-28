package com.lagradost.cloudstream3.desktop.ui.screens.home

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import java.util.Locale

private val hiddenHomeCatalogTerms = listOf(
    "korean",
    "k-drama",
    "k drama",
    "kdrama",
)

/**
 * Returns true for catalog/row labels that should not appear on the default Home feed.
 * This affects Home presentation only; provider search and direct navigation remain available.
 */
fun isHiddenHomeCatalogTitle(title: String): Boolean {
    val normalized = title.trim().lowercase(Locale.ROOT)
    return hiddenHomeCatalogTerms.any(normalized::contains)
}

/** Returns only real, enabled home catalogs; providers without a home page expose a placeholder. */
internal fun homeCatalogPages(
    providers: List<MainAPI>,
    disabledCatalogs: Map<String, Set<String>>,
): List<Pair<MainAPI, MainPageData>> = providers.flatMap { provider ->
    if (!provider.hasMainPage) return@flatMap emptyList()

    val disabledForProvider = disabledCatalogs[provider.name].orEmpty()
    provider.mainPage
        .filter { it.name.isNotBlank() && it.name !in disabledForProvider }
        .filterNot { isHiddenHomeCatalogTitle(it.name) }
        .map { provider to it }
}
