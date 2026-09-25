package com.lagradost.cloudstream3.desktop.ui.screens.home

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
