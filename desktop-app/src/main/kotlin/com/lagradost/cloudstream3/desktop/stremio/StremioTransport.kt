package com.lagradost.cloudstream3.desktop.stremio

import java.net.URLEncoder

object StremioTransport {

    fun normalizeManifestUrl(inputUrl: String): String {
        var url = inputUrl.trim()
        if (url.startsWith("stremio://", ignoreCase = true)) {
            url = "https://" + url.substring(10)
        }
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "https://$url"
        }
        if (!url.contains("/manifest.json", ignoreCase = true)) {
            val queryIndex = url.indexOf('?')
            url = if (queryIndex >= 0) {
                val base = url.substring(0, queryIndex).trimEnd('/')
                val query = url.substring(queryIndex)
                "$base/manifest.json$query"
            } else {
                url.trimEnd('/') + "/manifest.json"
            }
        }
        return url
    }

    fun getBaseUrl(manifestUrl: String): String {
        val clean = manifestUrl.substringBefore("?")
        return clean.replace(Regex("/manifest\\.json$", RegexOption.IGNORE_CASE), "").removeSuffix("/")
    }

    fun getQueryParams(manifestUrl: String): String {
        val query = manifestUrl.substringAfter("?", "")
        return if (query.isNotBlank()) "?$query" else ""
    }

    fun buildSubtitleUrl(manifestUrl: String, type: String, id: String): String {
        val baseUrl = getBaseUrl(manifestUrl)
        val query = getQueryParams(manifestUrl)
        val encodedId = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
        return "$baseUrl/subtitles/$type/$encodedId.json$query"
    }

    fun buildMetadataUrl(manifestUrl: String, type: String, id: String): String {
        val baseUrl = getBaseUrl(manifestUrl)
        val query = getQueryParams(manifestUrl)
        val encodedId = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
        return "$baseUrl/meta/$type/$encodedId.json$query"
    }

    fun buildStreamUrl(manifestUrl: String, type: String, id: String): String {
        val baseUrl = getBaseUrl(manifestUrl)
        val query = getQueryParams(manifestUrl)
        val encodedId = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
        return "$baseUrl/stream/$type/$encodedId.json$query"
    }

    /**
     * Build a Stremio catalog route with optional extras and manifest configuration.
     * Catalog extras are path segments, not URL query parameters.
     */
    fun buildCatalogUrl(
        manifestOrBaseUrl: String,
        type: String,
        catalogId: String,
        genre: String? = null,
        skip: Int = 0,
    ): String {
        val baseUrl = getBaseUrl(manifestOrBaseUrl)
        val query = getQueryParams(manifestOrBaseUrl)
        val encodedType = encodePathSegment(type)
        val encodedCatalogId = encodePathSegment(catalogId)
        val extras = buildList {
            if (!genre.isNullOrBlank() && !genre.equals("All", ignoreCase = true)) {
                add("genre=${encodePathSegment(genre.trim())}")
            }
            if (skip > 0) add("skip=$skip")
        }
        val extraPath = extras.joinToString(separator = "/", prefix = "/")
            .takeIf { extras.isNotEmpty() }
            ?: ""
        return "$baseUrl/catalog/$encodedType/$encodedCatalogId$extraPath.json$query"
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
