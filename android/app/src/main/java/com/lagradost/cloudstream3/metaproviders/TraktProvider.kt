package com.lagradost.cloudstream3.metaproviders

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.auras.orbit.BuildConfig
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainActivityKt
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainAPIKt
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addImdbId
import com.lagradost.cloudstream3.addTrailer
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

open class TraktProvider : MainAPI() {
    init {
        setName("Trakt")
    }

    override fun getHasMainPage(): Boolean = true
    override fun getProviderType(): ProviderType = ProviderType.MetaProvider
    override fun getSupportedTypes(): Set<TvType> = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime,
    )

    private val traktApiUrl = "https://api.trakt.tv"
    private val traktClientId: String = BuildConfig.ORBIT_TRAKT_CLIENT_ID

    override fun getMainPage(): List<*> = MainAPIKt.mainPageOf(arrayOf(
        "$traktApiUrl/movies/trending" to "Trending Movies", //Most watched movies right now
        "$traktApiUrl/movies/popular" to "Popular Movies", //The most popular movies for all time
        "$traktApiUrl/shows/trending" to "Trending Shows", //Most watched Shows right now
        "$traktApiUrl/shows/popular" to "Popular Shows", //The most popular Shows for all time
    ))

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val apiResponse = getApi("${request.data}?extended=full,images&page=$page")
        val results = parseJson<List<MediaDetails>>(apiResponse).map { element ->
            element.toSearchResponse()
        }

        return newHomePageResponse(request.name, results)
    }

    private fun MediaDetails.toSearchResponse(): SearchResponse {
        val media = this.media ?: MediaSummary(
            title = this.title,
            year = this.year,
            ids = this.ids,
            rating = this.rating,
            images = this.images,
        )
        val loadDetails = this.media?.toMediaDetails(this) ?: this
        val mediaType = if (media.ids?.tvdb == null) TvType.Movie else TvType.TvSeries
        val poster = media.images?.poster?.firstOrNull()
        return if (mediaType == TvType.Movie) {
            newMovieSearchResponse(
                name = media.title ?: "",
                url = Data(
                    type = mediaType,
                    mediaDetails = loadDetails,
                ).toJson(),
                type = TvType.Movie,
            ) {
                score = Score.from10(media.rating)
                posterUrl = fixPath(poster)
            }
        } else {
            newTvSeriesSearchResponse(
                name = media.title ?: "",
                url = Data(
                    type = mediaType,
                    mediaDetails = loadDetails,
                ).toJson(),
                type = TvType.TvSeries,
            ) {
                score = Score.from10(media.rating)
                this.posterUrl = fixPath(poster)
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val apiResponse = getApi("$traktApiUrl/search/movie,show?extended=full,images&limit=20&page=$page&query=${query.encodeURLParameter()}")
        return newSearchResponseList(parseJson<List<MediaDetails>>(apiResponse).map { element ->
            element.toSearchResponse()
        })
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<Data>(url)
        val mediaDetails = data.mediaDetails?.let { wrapper ->
            wrapper.media?.toMediaDetails(wrapper) ?: wrapper
        }
        val moviesOrShows = if (data.type == TvType.Movie) "movies" else "shows"

        val posterUrl = fixPath(mediaDetails?.images?.poster?.firstOrNull())
        val backDropUrl = fixPath(mediaDetails?.images?.fanart?.firstOrNull())
        val logoUrl = fixPath(mediaDetails?.images?.logo?.firstOrNull())

        val resActor = getApi("$traktApiUrl/$moviesOrShows/${mediaDetails?.ids?.trakt}/people?extended=full,images")
        val actors = parseJson<People>(resActor).cast?.map {
            ActorData(
                Actor(
                    it.person?.name!!,
                    fixPath(it.person.images?.headshot?.firstOrNull())
                ),
                null,
                it.character,
                null,
            )
        }

        val resRelated = getApi("$traktApiUrl/$moviesOrShows/${mediaDetails?.ids?.trakt}/related?extended=full,images&limit=20")
        val relatedMedia = parseJson<List<MediaDetails>>(resRelated).map { it.toSearchResponse() }

        val isCartoon =
            mediaDetails?.genres?.contains("animation") == true || mediaDetails?.genres?.contains("anime") == true
        val isAnime =
            isCartoon && (mediaDetails.language == "zh" || mediaDetails.language == "ja")
        val isAsian = !isAnime && (mediaDetails?.language == "zh" || mediaDetails?.language == "ko")
        val isBollywood = mediaDetails?.country == "in"
        val uniqueUrl = data.mediaDetails?.ids?.trakt?.toJson() ?: data.toJson()

        if (data.type == TvType.Movie) {
            val linkData = LinkData(
                id = mediaDetails?.ids?.tmdb,
                traktId = mediaDetails?.ids?.trakt,
                traktSlug = mediaDetails?.ids?.slug,
                tmdbId = mediaDetails?.ids?.tmdb,
                imdbId = mediaDetails?.ids?.imdb.toString(),
                tvdbId = mediaDetails?.ids?.tvdb,
                tvrageId = mediaDetails?.ids?.tvrage,
                type = data.type.toString(),
                title = mediaDetails?.title,
                year = mediaDetails?.year,
                orgTitle = mediaDetails?.title,
                isAnime = isAnime,
                // jpTitle = later if needed as it requires another network request,
                airedDate = mediaDetails?.released
                    ?: mediaDetails?.firstAired,
                isAsian = isAsian,
                isBollywood = isBollywood,
            ).toJson()

            return newMovieLoadResponse(
                name = mediaDetails?.title!!,
                url = data.toJson(),
                data = linkData.toJson(),
                type = if (isAnime) TvType.AnimeMovie else TvType.Movie,
            ) {
                this.uniqueUrl = uniqueUrl
                this.name = mediaDetails.title
                this.type = if (isAnime) TvType.AnimeMovie else TvType.Movie
                this.posterUrl = posterUrl
                this.year = mediaDetails.year
                this.plot = mediaDetails.overview
                this.score = Score.from10(mediaDetails.rating)
                this.tags = mediaDetails.genres
                this.duration = mediaDetails.runtime
                this.recommendations = relatedMedia
                this.actors = actors
                this.comingSoon = isUpcoming(mediaDetails.released)
                //posterHeaders
                this.backgroundPosterUrl = backDropUrl
                this.logoUrl = logoUrl
                this.contentRating = mediaDetails.certification
                mediaDetails.trailer?.takeUnless { it.isBlank() }?.let { addTrailer(listOf(it)) }
                addImdbId(mediaDetails.ids?.imdb)
                addTmdbId(this, mediaDetails.ids?.tmdb.toString())
            }
        } else {
            val resSeasons =
                getApi("$traktApiUrl/shows/${mediaDetails?.ids?.trakt.toString()}/seasons?extended=full,images,episodes")
            val episodes = mutableListOf<Episode>()
            val seasons = parseJson<List<Seasons>>(resSeasons)
            var nextAir: NextAiring? = null

            seasons.forEach { season ->
                season.episodes?.map { episode ->
                    val linkData = LinkData(
                        id = mediaDetails?.ids?.tmdb,
                        traktId = mediaDetails?.ids?.trakt,
                        traktSlug = mediaDetails?.ids?.slug,
                        tmdbId = mediaDetails?.ids?.tmdb,
                        imdbId = mediaDetails?.ids?.imdb.toString(),
                        tvdbId = mediaDetails?.ids?.tvdb,
                        tvrageId = mediaDetails?.ids?.tvrage,
                        type = data.type.toString(),
                        season = episode.season,
                        episode = episode.number,
                        title = mediaDetails?.title,
                        year = mediaDetails?.year,
                        orgTitle = mediaDetails?.title,
                        isAnime = isAnime,
                        airedYear = mediaDetails?.year,
                        lastSeason = seasons.size,
                        epsTitle = episode.title,
                        //jpTitle = later if needed as it requires another network request,
                        date = episode.firstAired,
                        airedDate = episode.firstAired,
                        isAsian = isAsian,
                        isBollywood = isBollywood,
                        isCartoon = isCartoon
                    ).toJson()

                    episodes.add(
                        newEpisode(linkData.toJson()) {
                            this.name = episode.title
                            this.season = episode.season
                            this.episode = episode.number
                            this.description = episode.overview
                            // CloudStream's episode UI interprets Episode.runTime as minutes.
                            this.runTime = episode.runtime?.takeIf { it > 0 }
                            this.posterUrl = fixPath(episode.images?.screenshot?.firstOrNull())
                            this.score = Score.from10(episode.rating)

                            MainAPIKt.addDate(this, episode.firstAired, "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                            if (nextAir == null && this.date != null && this.date!! > APIHolder.INSTANCE.getUnixTimeMS() && this.season != 0) {
                                nextAir = NextAiring(
                                    this.episode!!,
                                    this.date!!.div(1000L),
                                    if (this.season == 1) null else this.season,
                                )
                            }
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(
                name = mediaDetails?.title!!,
                url = data.toJson(),
                type = if (isAnime) TvType.Anime else TvType.TvSeries,
                episodes = episodes
            ) {
                this.uniqueUrl = uniqueUrl
                this.name = mediaDetails.title
                this.type = if (isAnime) TvType.Anime else TvType.TvSeries
                this.episodes = episodes
                this.posterUrl = posterUrl
                this.year = mediaDetails.year
                this.plot = mediaDetails.overview
                this.showStatus = getStatus(mediaDetails.status)
                this.score = Score.from10(mediaDetails.rating)
                this.tags = mediaDetails.genres
                this.duration = mediaDetails.runtime
                this.recommendations = relatedMedia
                this.actors = actors
                this.comingSoon = isUpcoming(mediaDetails.released)
                //posterHeaders
                this.nextAiring = nextAir
                this.backgroundPosterUrl = backDropUrl
                this.logoUrl = logoUrl
                this.contentRating = mediaDetails.certification
                mediaDetails.trailer?.takeUnless { it.isBlank() }?.let { addTrailer(listOf(it)) }
                addImdbId(mediaDetails.ids?.imdb)
                addTmdbId(this, mediaDetails.ids?.tmdb.toString())
            }
        }
    }

    private suspend fun getApi(url: String): String {
        check(traktClientId.isNotBlank()) {
            "Trakt API client ID is not configured for this app."
        }
        return MainActivityKt.getApp().get(
            url = url,
            headers = mapOf(
                "Content-Type" to "application/json",
                "trakt-api-version" to "2",
                "trakt-api-key" to traktClientId,
            )
        ).text
    }

    private fun getStatus(t: String?): ShowStatus {
        return when (t) {
            "returning series" -> ShowStatus.Ongoing
            "continuing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    private fun fixPath(url: String?): String? {
        url ?: return null
        return "https://$url"
    }

    private inline fun <reified T> parseJson(value: String): T = MainAPIKt.getMapper().readValue(
        value,
        object : TypeReference<T>() {},
    )

    @OptIn(ExperimentalTime::class)
    private fun isUpcoming(dateString: String?): Boolean {
        val value = dateString?.trim()?.takeIf(String::isNotEmpty) ?: return false
        val timeZone = TimeZone.currentSystemDefault()

        return runCatching {
            val instant = runCatching { Instant.parse(value) }
                .recoverCatching { LocalDateTime.parse(value).toInstant(timeZone) }
                .getOrElse { LocalDate.parse(value).atStartOfDayIn(timeZone) }
            Clock.System.now() < instant
        }.getOrElse { false }
    }

    private fun addTmdbId(response: LoadResponse, id: String?) {
        try {
            val syncData = response.getSyncData() ?: return
            val prefix = LoadResponse.configuredSimklIdPrefix()
            val ids = linkedMapOf<String, String>()
            val existingIds = syncData[prefix]
            if (existingIds != null) {
                try {
                    val existing = MainAPIKt.getMapper().readTree(existingIds)
                    if (existing == null || !existing.isObject) {
                        throw IllegalArgumentException("Expected an ID object")
                    }
                    val fields = existing.fields()
                    while (fields.hasNext()) {
                        val field = fields.next()
                        val service = field.key
                        val value = field.value
                        if (service !in setOf("Simkl", "Imdb", "Tmdb", "AniList", "Mal") || !value.isTextual) {
                            ids.clear()
                            break
                        }
                        ids[service] = value.textValue()
                    }
                } catch (_: Exception) {
                    // The pinned parser treats malformed existing ID data as an empty map.
                }
            }
            ids["Tmdb"] = id.toString()
            syncData[prefix] = MainAPIKt.getMapper().writeValueAsString(ids)
        } catch (_: Exception) {
            // Match the pinned safe helper by leaving sync data unchanged on failure.
        }
    }

    @Serializable
    data class Data(
        @JsonProperty("type") @SerialName("type") val type: TvType? = null,
        @JsonProperty("mediaDetails") @SerialName("mediaDetails") val mediaDetails: MediaDetails? = null,
    )

    @Serializable
    data class MediaSummary(
        @JsonProperty("title") @SerialName("title") val title: String? = null,
        @JsonProperty("year") @SerialName("year") val year: Int? = null,
        @JsonProperty("ids") @SerialName("ids") val ids: Ids? = null,
        @JsonProperty("tagline") @SerialName("tagline") val tagline: String? = null,
        @JsonProperty("overview") @SerialName("overview") val overview: String? = null,
        @JsonProperty("released") @SerialName("released") val released: String? = null,
        @JsonProperty("runtime") @SerialName("runtime") val runtime: Int? = null,
        @JsonProperty("country") @SerialName("country") val country: String? = null,
        @JsonProperty("updatedAt") @SerialName("updatedAt") val updatedAt: String? = null,
        @JsonProperty("trailer") @SerialName("trailer") val trailer: String? = null,
        @JsonProperty("homepage") @SerialName("homepage") val homepage: String? = null,
        @JsonProperty("status") @SerialName("status") val status: String? = null,
        @JsonProperty("rating") @SerialName("rating") val rating: Double? = null,
        @JsonProperty("votes") @SerialName("votes") val votes: Long? = null,
        @JsonProperty("comment_count") @SerialName("comment_count") val commentCount: Long? = null,
        @JsonProperty("language") @SerialName("language") val language: String? = null,
        @JsonProperty("languages") @SerialName("languages") val languages: List<String>? = null,
        @JsonProperty("available_translations") @SerialName("available_translations") val availableTranslations: List<String>? = null,
        @JsonProperty("genres") @SerialName("genres") val genres: List<String>? = null,
        @JsonProperty("certification") @SerialName("certification") val certification: String? = null,
        @JsonProperty("aired_episodes") @SerialName("aired_episodes") val airedEpisodes: Int? = null,
        @JsonProperty("first_aired") @SerialName("first_aired") val firstAired: String? = null,
        @JsonProperty("airs") @SerialName("airs") val airs: Airs? = null,
        @JsonProperty("network") @SerialName("network") val network: String? = null,
        @JsonProperty("images") @SerialName("images") val images: Images? = null,
    )

    private fun MediaSummary.toMediaDetails(fallback: MediaDetails): MediaDetails = MediaDetails(
        title = title ?: fallback.title,
        year = year ?: fallback.year,
        ids = ids ?: fallback.ids,
        tagline = tagline ?: fallback.tagline,
        overview = overview ?: fallback.overview,
        released = released ?: fallback.released,
        runtime = runtime ?: fallback.runtime,
        country = country ?: fallback.country,
        updatedAt = updatedAt ?: fallback.updatedAt,
        trailer = trailer ?: fallback.trailer,
        homepage = homepage ?: fallback.homepage,
        status = status ?: fallback.status,
        rating = rating ?: fallback.rating,
        votes = votes ?: fallback.votes,
        commentCount = commentCount ?: fallback.commentCount,
        language = language ?: fallback.language,
        languages = languages ?: fallback.languages,
        availableTranslations = availableTranslations ?: fallback.availableTranslations,
        genres = genres ?: fallback.genres,
        certification = certification ?: fallback.certification,
        airedEpisodes = airedEpisodes ?: fallback.airedEpisodes,
        firstAired = firstAired ?: fallback.firstAired,
        airs = airs ?: fallback.airs,
        network = network ?: fallback.network,
        images = images ?: fallback.images,
    )

    @OptIn(ExperimentalSerializationApi::class) // JsonNames is an experimental annotation for now
    @Serializable
    data class MediaDetails(
        @JsonProperty("title") @SerialName("title") val title: String? = null,
        @JsonProperty("year") @SerialName("year") val year: Int? = null,
        @JsonProperty("ids") @SerialName("ids") val ids: Ids? = null,
        @JsonProperty("tagline") @SerialName("tagline") val tagline: String? = null,
        @JsonProperty("overview") @SerialName("overview") val overview: String? = null,
        @JsonProperty("released") @SerialName("released") val released: String? = null,
        @JsonProperty("runtime") @SerialName("runtime") val runtime: Int? = null,
        @JsonProperty("country") @SerialName("country") val country: String? = null,
        @JsonProperty("updatedAt") @SerialName("updatedAt") val updatedAt: String? = null,
        @JsonProperty("trailer") @SerialName("trailer") val trailer: String? = null,
        @JsonProperty("homepage") @SerialName("homepage") val homepage: String? = null,
        @JsonProperty("status") @SerialName("status") val status: String? = null,
        @JsonProperty("rating") @SerialName("rating") val rating: Double? = null,
        @JsonProperty("votes") @SerialName("votes") val votes: Long? = null,
        @JsonProperty("comment_count") @SerialName("comment_count") val commentCount: Long? = null,
        @JsonProperty("language") @SerialName("language") val language: String? = null,
        @JsonProperty("languages") @SerialName("languages") val languages: List<String>? = null,
        @JsonProperty("available_translations") @SerialName("available_translations") val availableTranslations: List<String>? = null,
        @JsonProperty("genres") @SerialName("genres") val genres: List<String>? = null,
        @JsonProperty("certification") @SerialName("certification") val certification: String? = null,
        @JsonProperty("aired_episodes") @SerialName("aired_episodes") val airedEpisodes: Int? = null,
        @JsonProperty("first_aired") @SerialName("first_aired") val firstAired: String? = null,
        @JsonProperty("airs") @SerialName("airs") val airs: Airs? = null,
        @JsonProperty("network") @SerialName("network") val network: String? = null,
        @JsonProperty("images") @SerialName("images") val images: Images? = null,
        @JsonProperty("media") @JsonAlias("movie", "show") @SerialName("media") @JsonNames("movie", "show") val media: MediaSummary? = null,
    )

    @Serializable
    data class Airs(
        @JsonProperty("day") @SerialName("day") val day: String? = null,
        @JsonProperty("time") @SerialName("time") val time: String? = null,
        @JsonProperty("timezone") @SerialName("timezone") val timezone: String? = null,
    )

    @Serializable
    data class Ids(
        @JsonProperty("trakt") @SerialName("trakt") val trakt: Int? = null,
        @JsonProperty("slug") @SerialName("slug") val slug: String? = null,
        @JsonProperty("tvdb") @SerialName("tvdb") val tvdb: Int? = null,
        @JsonProperty("imdb") @SerialName("imdb") val imdb: String? = null,
        @JsonProperty("tmdb") @SerialName("tmdb") val tmdb: Int? = null,
        @JsonProperty("tvrage") @SerialName("tvrage") val tvrage: String? = null,
    )

    @Serializable
    data class Images(
        @JsonProperty("poster") @SerialName("poster") val poster: List<String>? = null,
        @JsonProperty("fanart") @SerialName("fanart") val fanart: List<String>? = null,
        @JsonProperty("logo") @SerialName("logo") val logo: List<String>? = null,
        @JsonProperty("clearart") @SerialName("clearart") val clearArt: List<String>? = null,
        @JsonProperty("banner") @SerialName("banner") val banner: List<String>? = null,
        @JsonProperty("thumb") @SerialName("thumb") val thumb: List<String>? = null,
        @JsonProperty("screenshot") @SerialName("screenshot") val screenshot: List<String>? = null,
        @JsonProperty("headshot") @SerialName("headshot") val headshot: List<String>? = null,
    )

    @Serializable
    data class People(
        @JsonProperty("cast") @SerialName("cast") val cast: List<Cast>? = null,
    )

    @Serializable
    data class Cast(
        @JsonProperty("character") @SerialName("character") val character: String? = null,
        @JsonProperty("characters") @SerialName("characters") val characters: List<String>? = null,
        @JsonProperty("episode_count") @SerialName("episode_count") val episodeCount: Long? = null,
        @JsonProperty("person") @SerialName("person") val person: Person? = null,
        @JsonProperty("images") @SerialName("images") val images: Images? = null,
    )

    @Serializable
    data class Person(
        @JsonProperty("name") @SerialName("name") val name: String? = null,
        @JsonProperty("ids") @SerialName("ids") val ids: Ids? = null,
        @JsonProperty("images") @SerialName("images") val images: Images? = null,
    )

    @Serializable
    data class Seasons(
        @JsonProperty("aired_episodes") @SerialName("aired_episodes") val airedEpisodes: Int? = null,
        @JsonProperty("episode_count") @SerialName("episode_count") val episodeCount: Int? = null,
        @JsonProperty("episodes") @SerialName("episodes") val episodes: List<TraktEpisode>? = null,
        @JsonProperty("first_aired") @SerialName("first_aired") val firstAired: String? = null,
        @JsonProperty("ids") @SerialName("ids") val ids: Ids? = null,
        @JsonProperty("images") @SerialName("images") val images: Images? = null,
        @JsonProperty("network") @SerialName("network") val network: String? = null,
        @JsonProperty("number") @SerialName("number") val number: Int? = null,
        @JsonProperty("overview") @SerialName("overview") val overview: String? = null,
        @JsonProperty("rating") @SerialName("rating") val rating: Double? = null,
        @JsonProperty("title") @SerialName("title") val title: String? = null,
        @JsonProperty("updated_at") @SerialName("updated_at") val updatedAt: String? = null,
        @JsonProperty("votes") @SerialName("votes") val votes: Int? = null,
    )

    @Serializable
    data class TraktEpisode(
        @JsonProperty("available_translations") @SerialName("available_translations") val availableTranslations: List<String>? = null,
        @JsonProperty("comment_count") @SerialName("comment_count") val commentCount: Int? = null,
        @JsonProperty("episode_type") @SerialName("episode_type") val episodeType: String? = null,
        @JsonProperty("first_aired") @SerialName("first_aired") val firstAired: String? = null,
        @JsonProperty("ids") @SerialName("ids") val ids: Ids? = null,
        @JsonProperty("images") @SerialName("images") val images: Images? = null,
        @JsonProperty("number") @SerialName("number") val number: Int? = null,
        @JsonProperty("number_abs") @SerialName("number_abs") val numberAbs: Int? = null,
        @JsonProperty("overview") @SerialName("overview") val overview: String? = null,
        @JsonProperty("rating") @SerialName("rating") val rating: Double? = null,
        @JsonProperty("runtime") @SerialName("runtime") val runtime: Int? = null,
        @JsonProperty("season") @SerialName("season") val season: Int? = null,
        @JsonProperty("title") @SerialName("title") val title: String? = null,
        @JsonProperty("updated_at") @SerialName("updated_at") val updatedAt: String? = null,
        @JsonProperty("votes") @SerialName("votes") val votes: Int? = null,
    )

    @Serializable
    data class LinkData(
        @JsonProperty("id") @SerialName("id") val id: Int? = null,
        @JsonProperty("trakt_id") @SerialName("trakt_id") val traktId: Int? = null,
        @JsonProperty("trakt_slug") @SerialName("trakt_slug") val traktSlug: String? = null,
        @JsonProperty("tmdb_id") @SerialName("tmdb_id") val tmdbId: Int? = null,
        @JsonProperty("imdb_id") @SerialName("imdb_id") val imdbId: String? = null,
        @JsonProperty("tvdb_id") @SerialName("tvdb_id") val tvdbId: Int? = null,
        @JsonProperty("tvrage_id") @SerialName("tvrage_id") val tvrageId: String? = null,
        @JsonProperty("type") @SerialName("type") val type: String? = null,
        @JsonProperty("season") @SerialName("season") val season: Int? = null,
        @JsonProperty("episode") @SerialName("episode") val episode: Int? = null,
        @JsonProperty("ani_id") @SerialName("ani_id") val aniId: String? = null,
        @JsonProperty("anime_id") @SerialName("anime_id") val animeId: String? = null,
        @JsonProperty("title") @SerialName("title") val title: String? = null,
        @JsonProperty("year") @SerialName("year") val year: Int? = null,
        @JsonProperty("org_title") @SerialName("org_title") val orgTitle: String? = null,
        @JsonProperty("is_anime") @SerialName("is_anime") val isAnime: Boolean = false,
        @JsonProperty("aired_year") @SerialName("aired_year") val airedYear: Int? = null,
        @JsonProperty("last_season") @SerialName("last_season") val lastSeason: Int? = null,
        @JsonProperty("eps_title") @SerialName("eps_title") val epsTitle: String? = null,
        @JsonProperty("jp_title") @SerialName("jp_title") val jpTitle: String? = null,
        @JsonProperty("date") @SerialName("date") val date: String? = null,
        @JsonProperty("aired_date") @SerialName("aired_date") val airedDate: String? = null,
        @JsonProperty("is_asian") @SerialName("is_asian") val isAsian: Boolean = false,
        @JsonProperty("is_bollywood") @SerialName("is_bollywood") val isBollywood: Boolean = false,
        @JsonProperty("is_cartoon") @SerialName("is_cartoon") val isCartoon: Boolean = false,
    )
}
