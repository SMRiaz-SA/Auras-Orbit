package com.auras.orbit.providers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainAPIKt
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Covers provider-neutral search and details metadata mapping without loading a plugin or URL. */
@RunWith(AndroidJUnit4::class)
class Cs3ProviderMetadataMappingInstrumentationTest {
    @Test
    fun searchMapsYearsAndEverySupportedMediaKind() {
        val api = FixtureApi(setOf(
            TvType.Movie, TvType.AnimeMovie, TvType.TvSeries, TvType.Cartoon,
            TvType.Anime, TvType.OVA, TvType.AsianDrama, TvType.Live,
        ))
        val anime = MainAPIKt.newAnimeSearchResponse(
            api, "Anime", "https://provider.fixture.invalid/anime", TvType.Anime, false,
        ) { response: AnimeSearchResponse -> response.year = 2022 }
        val animeMovie = MainAPIKt.newAnimeSearchResponse(
            api, "Anime movie", "https://provider.fixture.invalid/anime-movie", TvType.AnimeMovie, false,
        ) { response: AnimeSearchResponse -> response.year = 2021 }
        api.searchResponses = listOf<SearchResponse>(
            movieSearch("Movie", TvType.Movie, 2024),
            animeMovie,
            seriesSearch("TV series", TvType.TvSeries, 2023),
            seriesSearch("Cartoon", TvType.Cartoon, 2020),
            anime,
            seriesSearch("OVA", TvType.OVA, 2019),
            seriesSearch("Asian drama", TvType.AsianDrama, 2018),
            LiveSearchResponse(
                "Live", "https://provider.fixture.invalid/live", "Fixture", TvType.Live,
                null, null, null, null, null, null,
            ),
        )

        val items = provider(api).search(SearchRequest("fixture", SearchType.ALL, 1))
            .associateBy { it.title }

        assertEquals(MediaKind.MOVIE, items.getValue("Movie").kind)
        assertEquals("2024", items.getValue("Movie").year)
        assertEquals(MediaKind.MOVIE, items.getValue("Anime movie").kind)
        assertEquals("2021", items.getValue("Anime movie").year)
        listOf("TV series", "Cartoon", "Anime", "OVA", "Asian drama").forEach { title ->
            assertEquals("$title maps to SERIES", MediaKind.SERIES, items.getValue(title).kind)
        }
        assertEquals("2023", items.getValue("TV series").year)
        assertEquals("2022", items.getValue("Anime").year)
        assertEquals("2020", items.getValue("Cartoon").year)
        assertEquals("2019", items.getValue("OVA").year)
        assertEquals("2018", items.getValue("Asian drama").year)
        assertEquals(MediaKind.LIVE, items.getValue("Live").kind)
        assertTrue("LiveSearchResponse does not provide a year", items.getValue("Live").year.isEmpty())
    }

    @Test
    fun tvSeriesSearchResponseRemainsASeriesWhenItsTvTypeIsMovie() {
        val response = seriesSearch("VisionQuest", TvType.Movie, 2025)
        val api = FixtureApi(setOf(TvType.Movie, TvType.TvSeries)).apply {
            searchResponses = listOf(response)
        }

        val listed = provider(api).search(SearchRequest("VisionQuest", SearchType.ALL, 1)).single()

        assertEquals(MediaKind.SERIES, listed.kind)
    }

    @Test
    fun movieSearchResponseUsesExplicitEmbeddedTvTypeWhenProviderMislabelsItsResponseClass() {
        val response = movieSearch(
            "VisionQuest", TvType.Movie, 2025, """{"id":12345,"type":"tv"}""",
        )
        val api = FixtureApi(setOf(TvType.Movie, TvType.TvSeries)).apply {
            searchResponses = listOf(response)
        }

        val listed = provider(api).search(SearchRequest("VisionQuest", SearchType.ALL, 1)).single()

        assertEquals(MediaKind.SERIES, listed.kind)
    }

    @Test
    fun movieSearchResponseKeepsMovieTypeFromEmbeddedProviderData() {
        val response = movieSearch(
            "A movie", TvType.Movie, 2025, """{"id":98765,"type":"movie"}""",
        )
        val api = FixtureApi(setOf(TvType.Movie, TvType.TvSeries)).apply {
            searchResponses = listOf(response)
        }

        val listed = provider(api).search(SearchRequest("A movie", SearchType.ALL, 1)).single()

        assertEquals(MediaKind.MOVIE, listed.kind)
    }

    @Test
    fun movieSearchResponseFallsBackToItsDeclaredSeriesTypeWithoutUsablePayloadType() {
        val api = FixtureApi(setOf(TvType.Movie, TvType.TvSeries)).apply {
            searchResponses = listOf(
                movieSearch("Declared series", TvType.TvSeries, 2025),
                movieSearch("Malformed data", TvType.Movie, 2025, """{"type":"tv"""),
                movieSearch(
                    "Deep data", TvType.Movie, 2025,
                    """{"type":"tv","nested":${"[".repeat(24)}0${"]".repeat(24)}}""",
                ),
                movieSearch(
                    "Oversized data", TvType.Movie, 2025,
                    """{"type":"tv","padding":"${"x".repeat(32_800)}"}""",
                ),
            )
        }

        val items = provider(api).search(SearchRequest("fixture", SearchType.ALL, 1))
            .associateBy { it.title }

        assertEquals(MediaKind.SERIES, items.getValue("Declared series").kind)
        assertEquals(MediaKind.MOVIE, items.getValue("Malformed data").kind)
        assertEquals(MediaKind.MOVIE, items.getValue("Deep data").kind)
        assertEquals(MediaKind.MOVIE, items.getValue("Oversized data").kind)
    }

    @Test
    fun movieSearchResponseSkipsUnknownAliasesAndUsesSpecificAliasPrecedence() {
        val api = FixtureApi(setOf(TvType.Movie, TvType.TvSeries)).apply {
            searchResponses = listOf(
                movieSearch(
                    "Fallback alias", TvType.Movie, 2025,
                    """{"mediaType":"video","type":"tv"}""",
                ),
                movieSearch(
                    "Specific alias", TvType.Movie, 2025,
                    """{"mediaType":"movie","type":"tv"}""",
                ),
            )
        }

        val items = provider(api).search(SearchRequest("fixture", SearchType.ALL, 1))
            .associateBy { it.title }

        assertEquals(MediaKind.SERIES, items.getValue("Fallback alias").kind)
        assertEquals(MediaKind.MOVIE, items.getValue("Specific alias").kind)
    }

    @Test
    fun liveSearchResponseWithMovieTypeIsRejectedAcrossTheLiveActionBoundary() {
        val api = FixtureApi(setOf(TvType.Movie, TvType.Live)).apply {
            searchResponses = listOf(
                LiveSearchResponse(
                    "Misclassified live", "https://provider.fixture.invalid/live-movie",
                    "Fixture", TvType.Movie, null, null, null, null, null, null,
                ),
            )
        }

        val listed = provider(api).search(SearchRequest("Misclassified live", SearchType.ALL, 1))

        assertTrue("live results with a movie type cannot enter movie/series details", listed.isEmpty())
    }

    @Test
    fun movieSearchResponseWithEmbeddedLiveTypeIsRejectedForMovieAndSeriesDeclarations() {
        val api = FixtureApi(setOf(TvType.Movie, TvType.TvSeries)).apply {
            searchResponses = listOf(
                movieSearch("Live declared movie", TvType.Movie, 2025,
                    """{"id":123,"type":"live"}"""),
                movieSearch("Live declared series", TvType.TvSeries, 2025,
                    """{"id":456,"type":"live"}"""),
            )
        }

        val listed = provider(api).search(SearchRequest("fixture", SearchType.ALL, 1))

        assertTrue("embedded live entries cannot enter movie/series actions", listed.isEmpty())
    }

    @Test
    fun detailsMapCommonMetadataForMovieSeriesAnimeAndLive() {
        val related = movieSearch("Related", TvType.Movie, 2020)

        val movie = MovieLoadResponse(
            "Movie details", "https://provider.fixture.invalid/movie", "Fixture", TvType.Movie,
            "movie-data", null, null, null, null, emptyList(), null,
            emptyList<com.lagradost.cloudstream3.TrailerData>(),
            listOf(related), emptyList(), false, linkedMapOf(), null, null, null, null,
            "https://provider.fixture.invalid/movie",
        ).apply { setDetailsMetadata(this, 2024) }
        val movieItem = provider(FixtureApi(setOf(TvType.Movie), movie))
            .loadDetails(movie.url)
        assertCommonDetails(movieItem, MediaKind.MOVIE, "movie-data", "2024", relatedTitle = "Related")

        listOf(TvType.TvSeries, TvType.Cartoon, TvType.OVA, TvType.AsianDrama)
            .forEachIndexed { index, type ->
                val url = "https://provider.fixture.invalid/series-${type.name}"
                val series = TvSeriesLoadResponse(
                    "Series ${type.name}", url, "Fixture", type, emptyList(),
                ).apply { setDetailsMetadata(this, 2023 - index) }
                val seriesItem = provider(FixtureApi(setOf(type), series)).loadDetails(series.url)
                assertCommonDetails(seriesItem, MediaKind.SERIES, "", (2023 - index).toString())
            }

        val anime = AnimeLoadResponse(
            "Anime details", "https://provider.fixture.invalid/anime-details", "Fixture", TvType.Anime,
        ).apply { setDetailsMetadata(this, 2022) }
        val animeItem = provider(FixtureApi(setOf(TvType.Anime), anime))
            .loadDetails(anime.url)
        assertCommonDetails(animeItem, MediaKind.SERIES, "", "2022")

        val animeMovie = AnimeLoadResponse(
            "Anime movie details", "https://provider.fixture.invalid/anime-movie-details",
            "Fixture", TvType.AnimeMovie,
        ).apply { setDetailsMetadata(this, 2021) }
        val animeMovieItem = provider(FixtureApi(setOf(TvType.AnimeMovie), animeMovie))
            .loadDetails(animeMovie.url)
        assertCommonDetails(animeMovieItem, MediaKind.MOVIE, "", "2021")

        val live = LiveStreamLoadResponse(
            "Live details", "https://provider.fixture.invalid/live-details", "Fixture",
            "live-data", null, null, null, TvType.Live, null, emptyList(), null,
            emptyList<com.lagradost.cloudstream3.TrailerData>(), listOf(related), emptyList(),
            false, linkedMapOf(), null,
            null, null, null, "https://provider.fixture.invalid/live-details",
        ).apply { setDetailsMetadata(this, 2025) }
        val liveItem = provider(FixtureApi(setOf(TvType.Live), live))
            .loadDetails(live.url)
        assertCommonDetails(liveItem, MediaKind.LIVE, "live-data", "2025", relatedTitle = "Related")
    }

    @Test
    fun tvSeriesLoadResponseWithEpisodesKeepsItsMetadataWhenItsTvTypeIsMovie() {
        val url = "https://provider.fixture.invalid/visionquest"
        val response = TvSeriesLoadResponse(
            "VisionQuest", url, "Fixture", TvType.Movie,
            listOf(Episode("episode-data", "Episode 1", 1, 1,
                null, null as Int?, "Fixture episode", null)),
        ).apply { setDetailsMetadata(this, 2025) }

        val details = provider(FixtureApi(setOf(TvType.Movie, TvType.TvSeries), response))
            .loadDetails(url)

        assertEquals(MediaKind.SERIES, details.kind)
        assertEquals("2025", details.year)
        assertEquals("Fixture overview", details.overview)
        assertEquals(1, details.episodes.size)
        assertEquals("Episode 1", details.episodes.single().title)
    }

    @Test
    fun tvSeriesLoadResponseProvidesMutableCloudStreamTrailerAndSyncCollections() {
        val response = TvSeriesLoadResponse(
            "Series", "https://provider.fixture.invalid/series", "Fixture",
            TvType.TvSeries, emptyList(),
        )

        assertNotNull(response.trailers)
        response.trailers.add(TrailerData("https://provider.fixture.invalid/trailer", null, false))
        assertEquals(1, response.trailers.size)
        assertNotNull(response.syncData)
        response.syncData["fixture"] = "ready"
        assertEquals("ready", response.syncData["fixture"])
    }

    @Test
    fun nonLiveLoadResponseCannotCrossIntoTheLiveDetailsBoundary() {
        val url = "https://provider.fixture.invalid/misdeclared-live"
        val response = TvSeriesLoadResponse(
            "Not live", url, "Fixture", TvType.Live, emptyList(),
        )

        val failure = runCatching {
            provider(FixtureApi(setOf(TvType.TvSeries, TvType.Live), response)).loadDetails(url)
        }.exceptionOrNull()

        assertTrue("series load response with live type must be rejected",
            failure is UnsupportedOperationException)
    }

    @Test
    fun animeMovieWithEpisodesRemainsAMovieAndCanBeCached() {
        val api = FixtureApi(setOf(TvType.AnimeMovie))
        val url = "https://provider.fixture.invalid/anime-feature"
        api.searchResponses = listOf(
            MainAPIKt.newAnimeSearchResponse(api, "Anime feature", url, TvType.AnimeMovie, false) { },
        )
        api.loadedResponse = AnimeLoadResponse(
            "Anime feature", url, "Fixture", TvType.AnimeMovie,
        ).apply {
            episodes[DubStatus.Subbed] = listOf(
                Episode("anime-feature-data", "Feature", 1, 1,
                    null, null as Int?, "Feature audio", null),
            )
            setDetailsMetadata(this, 2021)
        }

        val provider = provider(api)
        val seed = provider.search(SearchRequest("Anime feature", SearchType.ALL, 1)).single()
        val details = provider.loadDetails(seed.detailUrl)

        assertEquals(MediaKind.MOVIE, seed.kind)
        assertEquals(MediaKind.MOVIE, details.kind)
        assertEquals(1, details.episodes.size)
        assertTrue("movie episode actions should belong to the live provider registration",
            provider.isLoadedDetailsContextCurrent(details))
        val cache = ProviderDetailsMemoryCache()
        assertTrue(cache.put(provider, "metadata-fixture-profile", seed, details, 100L))
        val cached = cache.get(provider, "metadata-fixture-profile", seed, 101L)
        assertTrue("the movie detail result should remain reusable", cached != null)
        assertEquals(MediaKind.MOVIE, cached?.kind)
    }

    private fun assertCommonDetails(
        item: MediaItem,
        expectedKind: MediaKind,
        expectedProviderData: String,
        expectedYear: String,
        relatedTitle: String? = null,
    ) {
        assertEquals(expectedKind, item.kind)
        assertEquals(expectedYear, item.year)
        assertEquals("https://images.fixture.invalid/poster.jpg", item.posterUrl)
        assertEquals("Fixture overview", item.overview)
        assertEquals(listOf("Drama", "Mystery"), item.tags)
        assertEquals(96, item.durationMinutes)
        assertEquals("PG-13", item.contentRating)
        assertEquals("https://images.fixture.invalid/background.jpg", item.backgroundPosterUrl)
        assertEquals("https://images.fixture.invalid/logo.png", item.logoUrl)
        assertEquals(mapOf("X-Poster" to "fixture"), item.posterHeaders)
        assertEquals("8.5", item.score)
        assertEquals(1, item.cast.size)
        assertEquals("Fixture Actor", item.cast.single().name)
        assertEquals("Lead", item.cast.single().role)
        assertEquals("", item.cast.single().imageUrl)
        assertEquals(expectedProviderData, item.providerData)
        if (relatedTitle != null) assertEquals(relatedTitle, item.relatedTitles.single().title)
    }

    private fun setDetailsMetadata(response: LoadResponse, year: Int) {
        response.posterUrl = "https://images.fixture.invalid/poster.jpg"
        response.year = year
        response.plot = "Fixture overview"
        response.score = Score.from10(8.5)
        response.tags = listOf("Drama", "Mystery")
        response.duration = 96
        response.actors = listOf(ActorData(Actor("Fixture Actor", null), null, "Lead", null))
        response.posterHeaders = mapOf("X-Poster" to "fixture")
        response.backgroundPosterUrl = "https://images.fixture.invalid/background.jpg"
        response.logoUrl = "https://images.fixture.invalid/logo.png"
        response.contentRating = "PG-13"
    }

    private fun provider(api: FixtureApi): Cs3MediaProviderAdapter {
        val key = Cs3ProviderMetadata.Key(
            profileId = "metadata-fixture-profile",
            pluginId = "metadata-fixture",
            profileGeneration = 1L,
            pluginGeneration = 1L,
            registrationIndex = 0,
            api = api,
        )
        return Cs3MediaProviderAdapter(
            pluginId = "metadata-fixture",
            profileId = "metadata-fixture-profile",
            registrationIndex = 0,
            providerId = "cs3:metadata-fixture:provider-0",
            fallbackName = api.name,
            api = api,
            metadataKey = key,
            suppliedMetadata = Cs3ProviderMetadata.Snapshot(
                key, api.name, false, false, api.supportedTypes,
            ),
            pluginArchiveSha256 = "0".repeat(64),
            stillCurrent = { true },
        )
    }

    private fun movieSearch(
        title: String,
        type: TvType,
        year: Int,
        providerUrl: String = "https://provider.fixture.invalid/${title.lowercase().replace(' ', '-')}",
    ) = MovieSearchResponse(
        title, providerUrl, "Fixture", type, null, year, null, null, null, null,
    )

    private fun seriesSearch(title: String, type: TvType, year: Int) = TvSeriesSearchResponse(
        title, "https://provider.fixture.invalid/${title.lowercase().replace(' ', '-')}",
        "Fixture", type, null, year, null, null, null, null, null,
    )

    private class FixtureApi(
        private val types: Set<TvType>,
        var loadedResponse: LoadResponse? = null,
    ) : MainAPI() {
        var searchResponses: List<SearchResponse> = emptyList()

        override fun getName(): String = "Metadata fixture"
        override fun getMainUrl(): String = "https://provider.fixture.invalid"
        override fun getSupportedTypes(): Set<TvType> = types
        override suspend fun search(query: String, page: Int): SearchResponseList =
            SearchResponseList(searchResponses, false)

        override suspend fun load(url: String): LoadResponse =
            loadedResponse ?: error("fixture received unexpected details request: $url")
    }
}
