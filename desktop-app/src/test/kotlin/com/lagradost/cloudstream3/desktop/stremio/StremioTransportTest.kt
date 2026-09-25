package com.lagradost.cloudstream3.desktop.stremio

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class StremioTransportTest {
    @Test
    fun catalogExtrasUseRouteSegmentsAndPreserveManifestConfiguration() {
        val url = StremioTransport.buildCatalogUrl(
            manifestOrBaseUrl = "https://catalog.example/manifest.json?token=abc%20123&profile=tv",
            type = "movie",
            catalogId = "top",
            genre = "Science Fiction",
            skip = 20,
        )

        assertEquals(
            "https://catalog.example/catalog/movie/top/genre=Science%20Fiction/skip=20.json?token=abc%20123&profile=tv",
            url,
        )
    }

    @Test
    fun baseUrlAcceptsCaseInsensitiveManifestSuffix() {
        assertEquals(
            "https://catalog.example",
            StremioTransport.getBaseUrl("https://catalog.example/MANIFEST.JSON?token=abc"),
        )
    }
}
