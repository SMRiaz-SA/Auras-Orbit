package com.lagradost.cloudstream3.desktop.explore.models

import com.lagradost.cloudstream3.SearchResponse

data class ExploreItem(
    val id: String,
    val type: String,
    val name: String,
    val posterUrl: String?,
    val backgroundUrl: String? = null,
    val logoUrl: String? = null,
    val releaseYear: String? = null,
    val description: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
)

data class ManifestCatalogDescriptor(
    val addonName: String,
    val addonBaseUrl: String,
    val type: String,
    val id: String,
    val name: String,
    val genres: List<String> = emptyList(),
    val supportsSearch: Boolean = false,
    /**
     * The normalized manifest URL, including any configuration query parameters.
     * Configured Stremio addons require those parameters on catalog requests too.
     */
    val addonManifestUrl: String = "",
)

data class ProviderMatch(
    val providerName: String,
    val searchResponse: SearchResponse,
    val displayTitle: String,
    val qualityText: String? = null,
    val hasSub: Boolean = false,
    val hasDub: Boolean = false,
)
