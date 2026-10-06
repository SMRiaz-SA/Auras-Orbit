package com.lagradost.cloudstream3.desktop.explore.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.explore.models.ManifestCatalogDescriptor
import com.lagradost.cloudstream3.desktop.stremio.ManagedStremioAddon
import com.lagradost.cloudstream3.desktop.stremio.StremioTransport
import com.lagradost.common.net.readBoundedBytes
import java.util.LinkedHashMap

object ExploreCatalogDiscoverer {
    private const val MAX_MANIFEST_CACHE_ENTRIES = 32
    private val mapper = jacksonObjectMapper()
    private val manifestCache = LinkedHashMap<String, List<ManifestCatalogDescriptor>>(16, 0.75f, true)
    private val manifestCacheLock = Any()

    suspend fun getCatalogsForAddon(addon: ManagedStremioAddon): List<ManifestCatalogDescriptor> {
        val cached = synchronized(manifestCacheLock) { manifestCache[addon.manifestUrl] }
        if (cached != null) return cached

        val response = app.get(addon.manifestUrl, timeout = 6000L)
        val responseText = response.body.byteStream().use { stream ->
            stream.readBoundedBytes(MAX_MANIFEST_RESPONSE_BYTES).toString(Charsets.UTF_8)
        }
        val root = mapper.readTree(responseText)
        val catalogsNode = root["catalogs"]
        val list = mutableListOf<ManifestCatalogDescriptor>()

        if (catalogsNode != null && catalogsNode.isArray) {
            for (cat in catalogsNode) {
                val type = cat["type"]?.asText() ?: continue
                val id = cat["id"]?.asText() ?: continue
                val name = cat["name"]?.asText() ?: "$type - $id"

                val genres = mutableListOf<String>()
                var supportsSearch = false
                val extras = mutableListOf<com.lagradost.cloudstream3.desktop.stremio.StremioCatalogExtra>()

                val extraNode = cat["extra"]
                if (extraNode != null && extraNode.isArray) {
                    for (ex in extraNode) {
                        val exName = ex["name"]?.asText()
                        if (!exName.isNullOrBlank()) {
                            extras += com.lagradost.cloudstream3.desktop.stremio.StremioCatalogExtra(
                                name = exName,
                                options = ex["options"]?.mapNotNull { option -> option.asText(null) } ?: emptyList(),
                                isRequired = ex["isRequired"]?.asBoolean() ?: false,
                            )
                        }
                        if (exName.equals("genre", ignoreCase = true)) {
                            val opts = ex["options"]
                            if (opts != null && opts.isArray) {
                                for (opt in opts) {
                                    genres.add(opt.asText())
                                }
                            }
                        } else if (exName.equals("search", ignoreCase = true)) {
                            supportsSearch = true
                        }
                    }
                }

                list.add(
                    ManifestCatalogDescriptor(
                        addonName = addon.name,
                        addonBaseUrl = StremioTransport.getBaseUrl(addon.manifestUrl),
                        type = type,
                        id = id,
                        name = name,
                        genres = genres,
                        supportsSearch = supportsSearch,
                        extras = extras,
                        addonManifestUrl = addon.manifestUrl,
                    ),
                )
            }
        }

        synchronized(manifestCacheLock) {
            manifestCache[addon.manifestUrl] = list
            while (manifestCache.size > MAX_MANIFEST_CACHE_ENTRIES) {
                manifestCache.remove(manifestCache.keys.first())
            }
        }
        return list
    }

    fun clearCache() {
        synchronized(manifestCacheLock) { manifestCache.clear() }
    }

    private const val MAX_MANIFEST_RESPONSE_BYTES = 2 * 1024 * 1024
}
