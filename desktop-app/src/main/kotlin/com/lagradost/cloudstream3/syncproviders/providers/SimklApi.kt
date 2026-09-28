package com.lagradost.cloudstream3.syncproviders.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.syncproviders.AuthAPI
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthLoginPage
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.syncproviders.TrackerClientConfig
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Simkl AUTH V2 client and watchlist adapter. OAuth uses public-client PKCE for desktop apps. */
class SimklApi : SyncAPI() {
    override val name = "Simkl"
    override val idPrefix = "simkl"
    override val redirectUrlIdentifier = "simkl"
    override val hasOAuth2 = true
    override var requireLibraryRefresh = true
    override val mainUrl = API_URL
    override val createAccountUrl = "https://simkl.com/settings/developer/"
    override val syncIdName = SyncIdName.Simkl
    override val supportedMediaTypes = setOf(
        SyncAPI.SyncMediaType.ANIME,
        SyncAPI.SyncMediaType.SHOW,
        SyncAPI.SyncMediaType.MOVIE,
    )
    override val supportsExactEpisodeProgress = true
    override val supportsWatchedEpisodeEvents = true

    private val clientId: String get() = TrackerClientConfig.simklClientId()
    private val appVersion: String
        get() = System.getProperty("cloudstream.version")?.takeIf(String::isNotBlank) ?: "0.2.0.00"

    /** The desktop flow supplies its ephemeral loopback URI, state, and PKCE verifier. */
    fun loginRequest(redirectUri: String, state: String, codeVerifier: String): AuthLoginPage? {
        val configuredClientId = clientId.takeIf(String::isNotBlank) ?: return null
        val challenge = AuthAPI.generateS256CodeChallenge(codeVerifier)
        val params = linkedMapOf(
            "client_id" to configuredClientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to "media:read media:write",
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        ).entries.joinToString("&") { (key, value) ->
            "${key.formEncode()}=${value.formEncode()}"
        }
        val payload = OAuthPayload(state, codeVerifier, redirectUri).toJson()
        return AuthLoginPage("https://simkl.com/oauth2/authorize?$params", payload)
    }

    // The standard zero-argument request cannot be used because a loopback callback must be reserved first.
    override fun loginRequest(): AuthLoginPage? = null

    override suspend fun login(redirectUrl: String, payload: String?): AuthToken? {
        val pending = tryParseJson<OAuthPayload>(payload) ?: throw ErrorLoadingException("Missing Simkl sign-in state")
        val response = AuthAPI.splitRedirectUrl(redirectUrl)
        if (response["state"] != pending.state || response["iss"] != ISSUER) {
            throw ErrorLoadingException("Simkl sign-in could not be verified")
        }
        val code = response["code"] ?: throw ErrorLoadingException("Simkl did not return an authorization code")
        val configuredClientId = clientId.takeIf(String::isNotBlank)
            ?: throw ErrorLoadingException("Set a Simkl client ID in Tracker settings first")

        val tokenJson = app.post(
            "$API_URL/oauth2/token",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded", "User-Agent" to userAgent()),
            data = mapOf(
                "grant_type" to "authorization_code",
                "client_id" to configuredClientId,
                "code" to code,
                "redirect_uri" to pending.redirectUri,
                "code_verifier" to pending.codeVerifier,
            ),
            cacheTime = 0,
        ).text
        val token = tryParseJson<TokenResponse>(tokenJson)
            ?: throw ErrorLoadingException("Simkl token exchange failed")
        if (token.scope?.split(' ')?.contains("media:write") != true) {
            throw ErrorLoadingException("Simkl granted read-only access; reconnect and approve library updates")
        }
        return token.toAuthToken()
    }

    override suspend fun refreshToken(token: AuthToken): AuthToken? {
        val refreshToken = token.refreshToken ?: return null
        val configuredClientId = clientId.takeIf(String::isNotBlank) ?: return null
        val body = app.post(
            "$API_URL/oauth2/token",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded", "User-Agent" to userAgent()),
            data = mapOf(
                "grant_type" to "refresh_token",
                "client_id" to configuredClientId,
                "refresh_token" to refreshToken,
            ),
            cacheTime = 0,
        ).text
        return tryParseJson<TokenResponse>(body)?.toAuthToken()
    }

    override suspend fun invalidateToken(token: AuthToken): Boolean {
        val configuredClientId = clientId.takeIf(String::isNotBlank) ?: return false
        val grantToken = token.refreshToken ?: token.accessToken ?: return false
        return try {
            app.post(
                "$API_URL/oauth2/revoke",
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "User-Agent" to userAgent(),
                ),
                data = mapOf("client_id" to configuredClientId, "token" to grantToken),
                cacheTime = 0,
            )
            // RFC 7009 deliberately returns the same 200 response for known and unknown tokens.
            // This only reports that the remote revocation request completed.
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun user(token: AuthToken?): AuthUser? {
        val response = request("/users/settings", token = token)
        val settings = tryParseJson<AccountSettings>(response)
            ?: throw ErrorLoadingException("Unable to fetch Simkl account data")
        return AuthUser(name = settings.user.name, id = settings.account.id, profilePicture = settings.user.avatar)
    }

    override suspend fun search(auth: AuthData?, query: String): List<SyncAPI.SyncSearchResult>? {
        val response = request("/search/anime", auth?.token, mapOf("q" to query, "extended" to "full"))
        return tryParseJson<List<SearchResult>>(response)?.mapNotNull { entry ->
            val media = entry.anime ?: entry.show ?: entry.movie ?: return@mapNotNull null
            val id = media.ids?.simkl ?: return@mapNotNull null
            val mediaType = when {
                entry.movie != null || entry.type == "movie" -> SyncAPI.SyncMediaType.MOVIE
                entry.show != null || entry.type == "show" -> SyncAPI.SyncMediaType.SHOW
                else -> SyncAPI.SyncMediaType.ANIME
            }
            SyncAPI.SyncSearchResult(
                name = media.title ?: return@mapNotNull null,
                apiName = name,
                syncId = id.toString(),
                url = "$WEB_URL/${mediaType.webPath}/$id",
                posterUrl = media.poster?.let(::posterUrl),
                type = when (mediaType) {
                    SyncAPI.SyncMediaType.ANIME -> TvType.Anime
                    SyncAPI.SyncMediaType.SHOW -> TvType.TvSeries
                    SyncAPI.SyncMediaType.MOVIE -> TvType.Movie
                },
                mediaType = mediaType,
                year = media.year,
            )
        }
    }

    override suspend fun load(auth: AuthData?, id: String): SyncAPI.SyncResult? {
        val numericId = idFrom(id)
            ?: throw ErrorLoadingException("Invalid Simkl ID")
        val response = request("/anime/$numericId", auth?.token, mapOf("extended" to "full"))
        val media = tryParseJson<SimklMedia>(response) ?: return null
        return SyncAPI.SyncResult(
            id = numericId.toString(),
            totalEpisodes = media.totalEpisodes,
            title = media.title,
            posterUrl = media.poster?.let(::posterUrl),
            startDate = media.year?.let { runCatching { Instant.parse("$it-01-01T00:00:00Z").epochSecond }.getOrNull() },
        )
    }

    override fun urlToId(url: String): String? =
        idFrom(url)?.toString()

    private fun idFrom(value: String): Int? =
        value.toIntOrNull() ?: Regex("/(\\d+)(?:/|$)").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

    override suspend fun status(auth: AuthData?, id: String): SyncAPI.AbstractSyncStatus? {
        val numericId = idFrom(id) ?: return null
        // The editor needs membership plus status, score, and progress fields. The IDs-only
        // response used for delta removals intentionally omits those values.
        val response = request("/sync/all-items", auth?.token)
        val library = tryParseJson<AllItemsResponse>(response) ?: return null
        val match = library.anime.firstOrNull { it.simklId() == numericId }
            ?.let { SyncAPI.SyncMediaType.ANIME to it }
            ?: library.shows.firstOrNull { it.simklId() == numericId }
                ?.let { SyncAPI.SyncMediaType.SHOW to it }
            ?: library.movies.firstOrNull { it.simklId() == numericId }
                ?.let { SyncAPI.SyncMediaType.MOVIE to it }
        return if (match == null) {
            SimklSyncStatus(SyncWatchType.NONE, null, null, null, null, null, mediaType = null)
        } else {
            val (mediaType, entry) = match
            SimklSyncStatus(
                status = statusFromSimkl(entry.status),
                score = Score.from10(entry.userRating),
                watchedEpisodes = entry.watchedEpisodesCount,
                oldStatus = entry.status,
                oldScore = entry.userRating,
                oldEpisodes = entry.watchedEpisodesCount,
                mediaType = mediaType,
            )
        }
    }

    override suspend fun updateStatus(auth: AuthData?, id: String, newStatus: AbstractSyncStatus): Boolean {
        val token = auth?.token ?: return false
        val numericId = idFrom(id) ?: return false
        val previous = newStatus as? SimklSyncStatus
        val mediaType = previous?.mediaType ?: newStatus.mediaType ?: return false
        if (previous?.mediaType != null && previous.mediaType != mediaType) return false
        val anime = mediaType == SyncAPI.SyncMediaType.ANIME
        val exactSelection = newStatus.watchedEpisodeSelection
        if (mediaType == SyncAPI.SyncMediaType.MOVIE && exactSelection != null) return false

        // Exact selections are reconciled against the user's episode-level state from Simkl's
        // targeted watched lookup. Count-only callers retain the documented contiguous-count
        // compatibility mapping. Resolve and validate before any write to avoid partial updates.
        val progressPlan = if (newStatus.status != SyncWatchType.NONE && exactSelection != null) {
            if (!selectionMatchesCount(exactSelection, newStatus.watchedEpisodes)) return false
            val catalogPath = if (anime) "/anime/episodes/$numericId" else "/tv/episodes/$numericId"
            val catalog = tryParseJson<List<SimklCatalogEpisode>>(request(catalogPath, token = null)) ?: return false
            if (!SimklEpisodeProgress.selectionIsInCatalog(exactSelection, catalog, anime)) return false
            val current = currentWatchedEpisodes(numericId, mediaType, token, previous?.oldEpisodes) ?: return false
            SimklEpisodeProgress.exactPlan(current, exactSelection, anime) ?: return false
        } else if (newStatus.status != SyncWatchType.NONE && mediaType != SyncAPI.SyncMediaType.MOVIE) {
            val targetCount = newStatus.watchedEpisodes
            val oldCount = when {
                previous == null -> 0
                previous.oldEpisodes != null -> previous.oldEpisodes
                previous.oldStatus == null -> 0
                else -> return false
            }
            if (targetCount != null && targetCount != oldCount) {
                val catalogPath = if (anime) "/anime/episodes/$numericId" else "/tv/episodes/$numericId"
                val catalog = tryParseJson<List<SimklCatalogEpisode>>(request(catalogPath, token = null)) ?: return false
                SimklEpisodeProgress.plan(catalog, oldCount, targetCount, anime) ?: return false
            } else {
                null
            }
        } else {
            null
        }

        if (newStatus.status != SyncWatchType.NONE && progressPlan != null) {
            if (progressPlan.remove.isNotEmpty()) {
                val response = postJsonResponse(
                    "/sync/history/remove",
                    token,
                    episodeHistoryBody(numericId, progressPlan.remove, anime),
                )
                if (response == null || !writeWasAccepted(response)) return false
            }
            if (progressPlan.add.isNotEmpty()) {
                val response = postJsonResponse(
                    "/sync/history",
                    token,
                    episodeHistoryBody(numericId, progressPlan.add, anime),
                )
                if (response == null || !writeWasAccepted(response)) return false
            }
        }

        val statusOk = if (newStatus.status == SyncWatchType.NONE) {
            val response = postJsonResponse(
                "/sync/history/remove",
                token,
                mapOf(itemBucket(mediaType) to listOf(mapOf("ids" to mapOf("simkl" to numericId)))),
            )
            response?.let { tryParseJson<RemoveFromListResponse>(it) }
                ?.let { it.notFound.movies.isEmpty() && it.notFound.shows.isEmpty() && it.notFound.anime.isEmpty() } == true
        } else {
            val target = when (newStatus.status) {
                SyncWatchType.WATCHING -> "watching"
                SyncWatchType.COMPLETED -> "completed"
                SyncWatchType.ON_HOLD -> "hold"
                SyncWatchType.DROPPED -> "dropped"
                SyncWatchType.PLAN_TO_WATCH -> "plantowatch"
                SyncWatchType.NONE -> return false
            }
            val body = mapOf(itemBucket(mediaType) to listOf(mapOf("ids" to mapOf("simkl" to numericId), "to" to target)))
            val response = postJsonResponse("/sync/add-to-list", token, body)
            val result = response?.let { tryParseJson<AddToListResponse>(it) }
            result != null && result.added.itemsFor(mediaType).isNotEmpty() && result.notFound.isEmpty()
        }
        if (!statusOk) return false

        // Removing a title means clearing the account's relationship with it; do not re-add it
        // immediately through a separate ratings write.
        if (newStatus.status == SyncWatchType.NONE) {
            requireLibraryRefresh = true
            return true
        }

        val score = newStatus.score?.toInt(10)
        val oldScore = previous?.oldScore
        if (score != null && score != oldScore) {
            val scoreBucket = if (mediaType == SyncAPI.SyncMediaType.ANIME) "anime" else itemBucket(mediaType)
            val body = mapOf(scoreBucket to listOf(mapOf("ids" to mapOf("simkl" to numericId), "rating" to score)))
            val response = postJsonResponse("/sync/ratings", token, body)
            if (response == null || !writeWasAccepted(response)) return false
        }
        requireLibraryRefresh = true
        return true
    }

    override suspend fun recordWatchedEpisodes(
        auth: AuthData?,
        media: SyncAPI.SyncMediaIdentity,
        episodes: List<SyncAPI.WatchedEpisodeEvent>,
    ): Boolean {
        val token = auth?.token ?: return false
        val body = SimklEpisodeProgress.historyEventBody(media, episodes) ?: return false
        val response = postJsonResponse("/sync/history", token, body) ?: return false
        val result = tryParseJson<AddHistoryResponse>(response) ?: return false
        // Simkl returns added.episodes=0 for an already-recorded (idempotent) episode.
        val accepted = result.added?.episodes != null && result.notFound.isEmpty()
        if (accepted) requireLibraryRefresh = true
        return accepted
    }

    override suspend fun watchedEpisodeSelection(
        auth: AuthData?,
        id: String,
    ): Set<SyncAPI.SyncEpisode>? {
        val numericId = idFrom(id) ?: return null
        val current = status(auth, numericId.toString()) ?: return null
        val mediaType = current.mediaType ?: return null
        return currentWatchedEpisodes(numericId, mediaType, auth?.token ?: return null, current.watchedEpisodes)
    }

    private fun episodeHistoryBody(
        simklId: Int,
        episodes: List<SimklEpisodeRef>,
        anime: Boolean,
    ): Map<String, Any> = SimklEpisodeProgress.historyBody(simklId, episodes, anime)

    private suspend fun currentWatchedEpisodes(
        simklId: Int,
        mediaType: SyncAPI.SyncMediaType,
        token: AuthToken,
        previousCount: Int?,
    ): Set<SyncAPI.SyncEpisode>? {
        if (mediaType == SyncAPI.SyncMediaType.MOVIE) return null
        val anime = mediaType == SyncAPI.SyncMediaType.ANIME
        val requestBody = listOf(
            mapOf(
                "ids" to mapOf("simkl" to simklId),
                "type" to if (anime) "anime" else "show",
            ),
        )
        val response = postJsonResponse(
            "/sync/watched",
            token,
            requestBody,
            extra = mapOf("extended" to "episodes,specials"),
        ) ?: return null
        val lookup = tryParseJson<List<WatchedLookupEntry>>(response)?.singleOrNull() ?: return null
        if (lookup.simkl != simklId) return null
        when (lookup.result) {
            false, "false" -> return if ((lookup.episodesWatched ?: previousCount ?: 0) == 0) emptySet() else null
            true, "true" -> Unit
            else -> return null
        }

        val seasons = lookup.seasons ?: return if ((lookup.episodesWatched ?: previousCount ?: 0) == 0) emptySet() else null
        val watched = seasons.asSequence()
            .filter { !anime || it.number == 1 }
            .flatMap { season ->
                season.episodes.orEmpty().asSequence()
                    .filter { it.watched == true }
                    .map { episode ->
                        SyncAPI.SyncEpisode(season = if (anime) null else season.number, number = episode.number)
                    }
            }
            .toSet()
        val reportedCount = lookup.episodesWatched ?: previousCount
        if (reportedCount != null && reportedCount != watched.size) return null
        return watched
    }

    private fun selectionMatchesCount(selection: Set<SyncAPI.SyncEpisode>, count: Int?): Boolean =
        count == null || count == selection.size

    private fun itemBucket(mediaType: SyncAPI.SyncMediaType): String =
        if (mediaType == SyncAPI.SyncMediaType.MOVIE) "movies" else "shows"

    private val SyncAPI.SyncMediaType.webPath: String
        get() = when (this) {
            SyncAPI.SyncMediaType.ANIME -> "anime"
            SyncAPI.SyncMediaType.SHOW -> "tv"
            SyncAPI.SyncMediaType.MOVIE -> "movies"
        }

    override suspend fun library(auth: AuthData?): SyncAPI.LibraryMetadata? {
        val account = auth ?: return null
        val currentActivities = tryParseJson<ActivitiesResponse>(request("/sync/activities", account.token)) ?: return null
        val cacheKey = "$LIBRARY_CACHE_KEY${account.user.id}"
        val cached = DesktopDataStore.getKey<SimklLibraryCache>(cacheKey)
        val allItems = when {
            cached == null -> fetchAllItems(account.token) ?: return null
            currentActivities == cached.activities -> cached.items
            else -> {
                val since = cached.activities.all
                val changes = if (since.isNullOrBlank()) {
                    fetchAllItems(account.token)
                } else {
                    fetchAllItems(account.token, dateFrom = since)
                }
                changes ?: return null
                var merged = mergeItems(cached.items, changes)
                if (haveRemovalsChanged(cached.activities, currentActivities)) {
                    val currentIds = fetchAllItems(account.token, idsOnly = true) ?: return null
                    val liveIds = currentIds.ids().toSet()
                    merged = mergeItems(merged, AllItemsResponse(), liveIds)
                }
                merged
            }
        }
        DesktopDataStore.setKey(cacheKey, SimklLibraryCache(currentActivities, allItems))
        val rows = listOf(
            "Watching" to SyncWatchType.WATCHING,
            "Completed" to SyncWatchType.COMPLETED,
            "On Hold" to SyncWatchType.ON_HOLD,
            "Dropped" to SyncWatchType.DROPPED,
            "Plan to Watch" to SyncWatchType.PLAN_TO_WATCH,
        )
        val entries = buildList {
            allItems.anime.forEach { add(it to SyncAPI.SyncMediaType.ANIME) }
            allItems.shows.forEach { add(it to SyncAPI.SyncMediaType.SHOW) }
            allItems.movies.forEach { add(it to SyncAPI.SyncMediaType.MOVIE) }
        }
        return SyncAPI.LibraryMetadata(
            allLibraryLists = rows.map { (label, status) ->
                val items = entries.filter { statusFromSimkl(it.first.status) == status }
                    .mapNotNull { (entry, mediaType) -> entry.toLibraryItem(mediaType) }
                SyncAPI.LibraryList(UiText.PlainText(label), items)
            },
            supportedListSorting = ListSorting.entries.toSet(),
        )
    }

    private suspend fun fetchAllItems(
        token: AuthToken,
        dateFrom: String? = null,
        idsOnly: Boolean = false,
    ): AllItemsResponse? {
        val parameters = buildMap {
            if (dateFrom != null) put("date_from", dateFrom)
            if (idsOnly) put("extended", "simkl_ids_only")
        }
        return tryParseJson<AllItemsResponse>(request("/sync/all-items", token, parameters))
    }

    /** Keep one canonical row per Simkl ID while applying a timestamp-bounded delta. */
    private fun mergeItems(
        base: AllItemsResponse,
        changes: AllItemsResponse,
        keepIds: Set<Int>? = null,
    ): AllItemsResponse {
        val merged = linkedMapOf<Int, Pair<Int, LibraryEntry>>()
        fun add(bucket: Int, entries: List<LibraryEntry>) {
            entries.forEach { entry -> entry.simklId()?.let { merged[it] = bucket to entry } }
        }
        add(BUCKET_SHOWS, base.shows)
        add(BUCKET_ANIME, base.anime)
        add(BUCKET_MOVIES, base.movies)
        add(BUCKET_SHOWS, changes.shows)
        add(BUCKET_ANIME, changes.anime)
        add(BUCKET_MOVIES, changes.movies)
        if (keepIds != null) merged.keys.retainAll(keepIds)
        return AllItemsResponse(
            shows = merged.values.filter { it.first == BUCKET_SHOWS }.map { it.second },
            anime = merged.values.filter { it.first == BUCKET_ANIME }.map { it.second },
            movies = merged.values.filter { it.first == BUCKET_MOVIES }.map { it.second },
        )
    }

    private fun LibraryEntry.simklId(): Int? =
        (show ?: movie)?.ids?.simkl ?: ids?.simkl

    private fun AllItemsResponse.ids(): List<Int> =
        (shows + anime + movies).mapNotNull { it.simklId() }

    private fun haveRemovalsChanged(previous: ActivitiesResponse, current: ActivitiesResponse): Boolean =
        previous.tvShows?.removedFromList != current.tvShows?.removedFromList ||
            previous.anime?.removedFromList != current.anime?.removedFromList ||
            previous.movies?.removedFromList != current.movies?.removedFromList

    private suspend fun request(path: String, token: AuthToken?, extra: Map<String, String> = emptyMap()): String {
        val id = clientId.takeIf(String::isNotBlank) ?: throw ErrorLoadingException("Set a Simkl client ID in Tracker settings first")
        val params = mapOf(
            "client_id" to id,
            "app-name" to APP_NAME,
            "app-version" to appVersion,
        ) + extra
        return app.get(
            "$API_URL$path",
            params = params,
            headers = requestHeaders(token),
            cacheTime = 0,
        ).text
    }

    private suspend fun postJsonResponse(
        path: String,
        token: AuthToken,
        body: Any,
        extra: Map<String, String> = emptyMap(),
    ): String? {
        val id = clientId.takeIf(String::isNotBlank) ?: return null
        return try {
            app.post(
                "$API_URL$path",
                params = mapOf("client_id" to id, "app-name" to APP_NAME, "app-version" to appVersion) + extra,
                headers = requestHeaders(token) + ("Content-Type" to "application/json"),
                json = body.toJson(),
                cacheTime = 0,
            ).text
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun writeWasAccepted(response: String): Boolean =
        tryParseJson<WriteResponse>(response)?.notFound?.isEmpty() == true

    private fun requestHeaders(token: AuthToken?): Map<String, String> = buildMap {
        put("User-Agent", userAgent())
        token?.accessToken?.let { put("Authorization", "Bearer $it") }
    }

    private fun userAgent(): String = "Auras-Orbit/$appVersion"

    private fun posterUrl(path: String): String = "https://wsrv.nl/?url=https://simkl.in/posters/${path}_m.webp"

    private fun statusFromSimkl(status: String?): SyncWatchType = when (status?.lowercase()) {
        "watching" -> SyncWatchType.WATCHING
        "completed" -> SyncWatchType.COMPLETED
        "hold", "paused" -> SyncWatchType.ON_HOLD
        "dropped" -> SyncWatchType.DROPPED
        "plantowatch", "planning" -> SyncWatchType.PLAN_TO_WATCH
        else -> SyncWatchType.NONE
    }

    private fun LibraryEntry.toLibraryItem(mediaType: SyncAPI.SyncMediaType): SyncAPI.LibraryItem? {
        val media = show ?: movie ?: return null
        val simklId = media.ids?.simkl ?: return null
        return SyncAPI.LibraryItem(
            name = media.title ?: return null,
            url = "$WEB_URL/${mediaType.webPath}/$simklId",
            syncId = simklId.toString(),
            episodesCompleted = watchedEpisodesCount,
            episodesTotal = totalEpisodesCount,
            personalRating = Score.from10(userRating),
            lastUpdatedUnixTime = timestamp(lastWatchedAt),
            apiName = name,
            type = when (mediaType) {
                SyncAPI.SyncMediaType.ANIME -> TvType.Anime
                SyncAPI.SyncMediaType.SHOW -> TvType.TvSeries
                SyncAPI.SyncMediaType.MOVIE -> TvType.Movie
            },
            posterUrl = media.poster?.let(::posterUrl),
            posterHeaders = null,
            quality = null,
            releaseDate = media.year?.let { java.util.GregorianCalendar(it, 0, 1).time },
            id = simklId,
            mediaType = mediaType,
        )
    }

    private fun timestamp(value: String?): Long? = value?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }

    private data class OAuthPayload(
        @JsonProperty("state") val state: String,
        @JsonProperty("codeVerifier") val codeVerifier: String,
        @JsonProperty("redirectUri") val redirectUri: String,
    )

    private data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String? = null,
        @JsonProperty("refresh_token") val refreshToken: String? = null,
        @JsonProperty("expires_in") val expiresIn: Long? = null,
        @JsonProperty("scope") val scope: String? = null,
    ) {
        fun toAuthToken(): AuthToken {
            val access = accessToken ?: throw ErrorLoadingException("Simkl did not return an access token")
            return AuthToken(
                accessToken = access,
                refreshToken = refreshToken,
                accessTokenLifetime = expiresIn?.let { APIHolder.unixTime + it },
                refreshTokenLifetime = if (refreshToken != null) APIHolder.unixTime + REFRESH_TOKEN_LIFETIME else null,
            )
        }
    }

    private data class AccountSettings(
        @JsonProperty("user") val user: SimklUser,
        @JsonProperty("account") val account: SimklAccount,
    )

    private data class SimklUser(
        @JsonProperty("name") val name: String,
        @JsonProperty("avatar") val avatar: String? = null,
    )

    private data class SimklAccount(@JsonProperty("id") val id: Int)

    private data class AllItemsResponse(
        @JsonProperty("anime") val anime: List<LibraryEntry> = emptyList(),
        @JsonProperty("shows") val shows: List<LibraryEntry> = emptyList(),
        @JsonProperty("movies") val movies: List<LibraryEntry> = emptyList(),
    )

    private data class ActivitiesResponse(
        @JsonProperty("all") val all: String? = null,
        @JsonProperty("tv_shows") val tvShows: ActivityBlock? = null,
        @JsonProperty("anime") val anime: ActivityBlock? = null,
        @JsonProperty("movies") val movies: ActivityBlock? = null,
    )

    private data class ActivityBlock(
        @JsonProperty("removed_from_list") val removedFromList: String? = null,
    )

    private data class SimklLibraryCache(
        @JsonProperty("activities") val activities: ActivitiesResponse,
        @JsonProperty("items") val items: AllItemsResponse,
    )

    private data class AddToListResponse(
        @JsonProperty("added") val added: AddedItems = AddedItems(),
        @JsonProperty("not_found") val notFound: MissingItems = MissingItems(),
    )

    private data class AddedItems(
        @JsonProperty("movies") val movies: List<Any> = emptyList(),
        @JsonProperty("shows") val shows: List<Any> = emptyList(),
        @JsonProperty("anime") val anime: List<Any> = emptyList(),
    ) {
        fun itemsFor(mediaType: SyncAPI.SyncMediaType): List<Any> = when (mediaType) {
            SyncAPI.SyncMediaType.MOVIE -> movies
            SyncAPI.SyncMediaType.ANIME, SyncAPI.SyncMediaType.SHOW -> shows + anime
        }
    }

    private data class RemoveFromListResponse(
        @JsonProperty("not_found") val notFound: MissingItems = MissingItems(),
    )

    private data class WriteResponse(
        @JsonProperty("not_found") val notFound: MissingItems? = null,
    )

    private data class MissingItems(
        @JsonProperty("movies") val movies: List<Any> = emptyList(),
        @JsonProperty("shows") val shows: List<Any> = emptyList(),
        @JsonProperty("anime") val anime: List<Any> = emptyList(),
    ) {
        fun isEmpty(): Boolean = movies.isEmpty() && shows.isEmpty() && anime.isEmpty()
    }

    private data class LibraryEntry(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("user_rating") val userRating: Int? = null,
        @JsonProperty("watched_episodes_count") val watchedEpisodesCount: Int? = null,
        @JsonProperty("total_episodes_count") val totalEpisodesCount: Int? = null,
        @JsonProperty("last_watched_at") val lastWatchedAt: String? = null,
        @JsonProperty("show") val show: SimklMedia? = null,
        @JsonProperty("movie") val movie: SimklMedia? = null,
        @JsonProperty("ids") val ids: SimklIds? = null,
    )

    private data class AddHistoryResponse(
        @JsonProperty("added") val added: AddedHistory? = null,
        @JsonProperty("not_found") val notFound: MissingItems = MissingItems(),
    )

    private data class AddedHistory(
        @JsonProperty("episodes") val episodes: Int? = null,
    )

    private data class WatchedLookupEntry(
        @JsonProperty("result") val result: Any? = null,
        @JsonProperty("simkl") val simkl: Int? = null,
        @JsonProperty("episodes_watched") val episodesWatched: Int? = null,
        @JsonProperty("seasons") val seasons: List<WatchedLookupSeason>? = null,
    )

    private data class WatchedLookupSeason(
        @JsonProperty("number") val number: Int,
        @JsonProperty("episodes") val episodes: List<WatchedLookupEpisode>? = null,
    )

    private data class WatchedLookupEpisode(
        @JsonProperty("number") val number: Int,
        @JsonProperty("watched") val watched: Boolean? = null,
    )

    private data class SearchResult(
        @JsonProperty("anime") val anime: SimklMedia? = null,
        @JsonProperty("show") val show: SimklMedia? = null,
        @JsonProperty("movie") val movie: SimklMedia? = null,
        @JsonProperty("type") val type: String? = null,
    )

    private data class SimklMedia(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
        @JsonProperty("ids") val ids: SimklIds? = null,
    )

    private data class SimklIds(@JsonProperty("simkl") val simkl: Int? = null)

    private data class SimklSyncStatus(
        override var status: SyncWatchType,
        override var score: Score?,
        override var watchedEpisodes: Int?,
        val oldStatus: String?,
        val oldScore: Int?,
        val oldEpisodes: Int?,
        override var mediaType: SyncAPI.SyncMediaType?,
        override var isFavorite: Boolean? = null,
        override var maxEpisodes: Int? = null,
    ) : SyncAPI.AbstractSyncStatus()

    companion object {
        private const val API_URL = "https://api.simkl.com"
        private const val WEB_URL = "https://simkl.com"
        private const val ISSUER = "https://simkl.com"
        private const val APP_NAME = "auras-orbit"
        private const val LIBRARY_CACHE_KEY = "tracker_simkl_library_"
        private const val BUCKET_SHOWS = 0
        private const val BUCKET_ANIME = 1
        private const val BUCKET_MOVIES = 2
        private const val REFRESH_TOKEN_LIFETIME = 15_552_000L

        private fun String.formEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)
    }
}
