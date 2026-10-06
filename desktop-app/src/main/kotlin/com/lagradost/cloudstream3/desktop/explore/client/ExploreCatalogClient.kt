package com.lagradost.cloudstream3.desktop.explore.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.explore.models.ExploreItem
import com.lagradost.cloudstream3.desktop.stremio.StremioTransport
import com.lagradost.common.net.readBoundedBytes

object ExploreCatalogClient {
    private val mapper = jacksonObjectMapper()

    suspend fun fetchCatalogItems(
        baseUrl: String,
        type: String,
        catalogId: String,
        genre: String? = null,
        search: String? = null,
        skip: Int = 0,
        manifestUrl: String? = null,
        addonName: String = "",
        extraArgs: Map<String, String> = emptyMap(),
    ): List<ExploreItem> {
        // Keep manifest configuration and serialize declared extras using Stremio's
        // single stringified extraArgs route segment.
        val url = StremioTransport.buildCatalogUrl(
            manifestOrBaseUrl = manifestUrl?.takeIf { it.isNotBlank() } ?: baseUrl,
            type = type,
            catalogId = catalogId,
            search = search,
            genre = genre,
            skip = skip,
            extraArgs = extraArgs,
        )

        val response = app.get(url, timeout = 10_000L, cacheTime = 60 * 6)
        val responseText = response.body.byteStream().use { stream ->
            stream.readBoundedBytes(MAX_CATALOG_RESPONSE_BYTES).toString(Charsets.UTF_8)
        }
        val root = mapper.readTree(responseText)
        val metas = root["metas"]
            ?: throw IllegalStateException("The add-on returned a catalog response without a metas list.")

        if (!metas.isArray) {
            throw IllegalStateException("The add-on returned an invalid catalog metas list.")
        }

        val items = mutableListOf<ExploreItem>()
        for (node in metas) {
            val id = node["id"]?.asText() ?: continue
            val itemType = node["type"]?.asText() ?: type
            val name = node["name"]?.asText() ?: continue
            val poster = node["poster"]?.asText()?.takeIf { it.isNotBlank() }
            val background = node["background"]?.asText()?.takeIf { it.isNotBlank() }
            val logo = node["logo"]?.asText()?.takeIf { it.isNotBlank() }
            val releaseInfo = (node["releaseInfo"]?.asText() ?: node["year"]?.asText())?.takeIf { it.isNotBlank() }
            val description = node["description"]?.asText()?.takeIf { it.isNotBlank() }

            val scoreStr = node["imdbRating"]?.asText()
            val rating = scoreStr?.toDoubleOrNull() ?: node["imdbRating"]?.asDouble()

            val genresList = mutableListOf<String>()
            val genreNode = node["genres"] ?: node["genre"]
            if (genreNode != null && genreNode.isArray) {
                for (g in genreNode) {
                    genresList.add(g.asText())
                }
            }

            items.add(
                ExploreItem(
                    id = id,
                    type = itemType,
                    name = name,
                    posterUrl = poster,
                    backgroundUrl = background,
                    logoUrl = logo,
                    releaseYear = releaseInfo,
                    description = description,
                    rating = rating,
                    genres = genresList,
                    sourceAddonName = addonName,
                    sourceManifestUrl = manifestUrl?.takeIf { it.isNotBlank() }
                        ?: StremioTransport.normalizeManifestUrl(baseUrl),
                ),
            )
        }
        return items
    }

    private const val MAX_CATALOG_RESPONSE_BYTES = 8 * 1024 * 1024
}
