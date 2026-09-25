package com.lagradost.cloudstream3.syncproviders.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.ShowStatus
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/** MyAnimeList v2 tracker. Client IDs are configured by the user in Accounts settings. */
class MalApi : SyncAPI() {
    override val name = "MyAnimeList"
    override val idPrefix = "mal"
    override val mainUrl = MAL_URL
    override val createAccountUrl = "$MAL_URL/apiconfig"
    override val hasOAuth2 = true
    override val redirectUrlIdentifier = "mallogin"
    override val syncIdName = SyncIdName.MyAnimeList
    override val supportedWatchTypes = setOf(
        SyncWatchType.NONE,
        SyncWatchType.WATCHING,
        SyncWatchType.COMPLETED,
        SyncWatchType.ON_HOLD,
        SyncWatchType.DROPPED,
        SyncWatchType.PLAN_TO_WATCH,
    )

    private val clientId: String get() = TrackerClientConfig.malClientId()

    override fun loginRequest(): AuthLoginPage? {
        val id = clientId.takeIf(String::isNotBlank) ?: return null
        val verifier = AuthAPI.generateCodeVerifier()
        val state = AuthAPI.generateCodeVerifier()
        // MAL defaults to the PKCE plain challenge method.
        val url = "$MAL_URL/v1/oauth2/authorize?response_type=code" +
            "&client_id=${id.formEncode()}&code_challenge=$verifier&state=$state"
        return AuthLoginPage(url, OAuthPayload(state, verifier).toJson())
    }

    override suspend fun login(redirectUrl: String, payload: String?): AuthToken? {
        val pending = tryParseJson<OAuthPayload>(payload) ?: return null
        val params = AuthAPI.splitRedirectUrl(redirectUrl)
        if (params["state"] != pending.state) return null
        val code = params["code"]?.takeIf(String::isNotBlank) ?: return null
        val id = clientId.takeIf(String::isNotBlank) ?: return null
        val body = app.post(
            "$MAL_URL/v1/oauth2/token",
            data = mapOf(
                "client_id" to id,
                "code" to code,
                "code_verifier" to pending.codeVerifier,
                "grant_type" to "authorization_code",
            ),
            cacheTime = 0,
        ).text
        return tryParseJson<TokenResponse>(body)?.toAuthToken()
    }

    override suspend fun refreshToken(token: AuthToken): AuthToken? {
        val refresh = token.refreshToken ?: return null
        val id = clientId.takeIf(String::isNotBlank) ?: return null
        val body = app.post(
            "$MAL_URL/v1/oauth2/token",
            data = mapOf(
                "client_id" to id,
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
            ),
            cacheTime = 0,
        ).text
        return tryParseJson<TokenResponse>(body)?.toAuthToken()
    }

    override suspend fun user(token: AuthToken?): AuthUser? {
        val response = app.get("$API_URL/users/@me", headers = token?.authHeader() ?: return null, cacheTime = 0).text
        val user = tryParseJson<MalUser>(response) ?: return null
        return AuthUser(id = user.id, name = user.name, profilePicture = user.picture)
    }

    override suspend fun search(auth: AuthData?, query: String): List<SyncAPI.SyncSearchResult>? {
        val response = app.get(
            "$API_URL/anime?q=${query.formEncode()}&limit=25&nsfw=1&fields=start_date,media_type",
            headers = auth?.token?.authHeader() ?: return null,
            cacheTime = 0,
        ).text
        val results = tryParseJson<SearchResponse>(response) ?: return null
        return results.data.mapNotNull { it.node.toSearchResult() }
    }

    override suspend fun load(auth: AuthData?, id: String): SyncAPI.SyncResult? {
        val numericId = parseId(id) ?: return null
        val fields = "id,title,main_picture,start_date,end_date,synopsis,mean,media_type,status,num_episodes,average_episode_duration,genres"
        val response = app.get(
            "$API_URL/anime/$numericId?fields=$fields",
            headers = auth?.token?.authHeader() ?: return null,
            cacheTime = 0,
        ).text
        val anime = tryParseJson<MalAnime>(response) ?: return null
        return SyncAPI.SyncResult(
            id = numericId.toString(),
            totalEpisodes = anime.numEpisodes,
            title = anime.title,
            publicScore = Score.from10(anime.mean),
            duration = anime.averageEpisodeDuration,
            synopsis = anime.synopsis,
            airStatus = when (anime.status) {
                "finished_airing" -> ShowStatus.Completed
                "currently_airing" -> ShowStatus.Ongoing
                else -> null
            },
            genres = anime.genres?.map { it.name },
            startDate = anime.startDate.toEpochSeconds(),
            endDate = anime.endDate.toEpochSeconds(),
        )
    }

    override fun urlToId(url: String): String? = parseId(url)?.toString()

    override suspend fun status(auth: AuthData?, id: String): SyncAPI.AbstractSyncStatus? {
        val numericId = parseId(id) ?: return null
        val response = app.get(
            "$API_URL/anime/$numericId?fields=id,my_list_status",
            headers = auth?.token?.authHeader() ?: return null,
            cacheTime = 0,
        ).text
        val anime = tryParseJson<MalAnime>(response) ?: return null
        val listStatus = anime.myListStatus
        return SyncAPI.SyncStatus(
            status = watchType(listStatus?.status),
            score = Score.from10(listStatus?.score),
            watchedEpisodes = listStatus?.numEpisodesWatched,
        )
    }

    override suspend fun updateStatus(auth: AuthData?, id: String, newStatus: AbstractSyncStatus): Boolean {
        if (newStatus.watchedEpisodeSelection != null) return false
        val numericId = parseId(id) ?: return false
        val token = auth?.token ?: return false
        val fields = buildMap {
            when (newStatus.status) {
                SyncWatchType.NONE -> Unit
                SyncWatchType.WATCHING -> put("status", "watching")
                SyncWatchType.COMPLETED -> put("status", "completed")
                SyncWatchType.ON_HOLD -> put("status", "on_hold")
                SyncWatchType.DROPPED -> put("status", "dropped")
                SyncWatchType.PLAN_TO_WATCH -> put("status", "plan_to_watch")
            }
            newStatus.score?.let { put("score", it.toInt(10).toString()) }
            newStatus.watchedEpisodes?.let { put("num_watched_episodes", it.toString()) }
        }

        return try {
            if (newStatus.status == SyncWatchType.NONE) {
                app.delete("$API_URL/anime/$numericId/my_list_status", headers = token.authHeader(), cacheTime = 0)
            } else {
                app.put(
                    "$API_URL/anime/$numericId/my_list_status",
                    headers = token.authHeader(),
                    data = fields,
                    cacheTime = 0,
                )
            }
            requireLibraryRefresh = true
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun library(auth: AuthData?): SyncAPI.LibraryMetadata? {
        val token = auth?.token ?: return null
        val result = mutableListOf<MalListEntry>()
        var url: String? = "$API_URL/users/@me/animelist?fields=list_status,num_episodes,main_picture,start_date,synopsis&limit=100&nsfw=1"
        while (url != null) {
            val page = app.get(url, headers = token.authHeader(), cacheTime = 0).text
            val parsed = tryParseJson<MalLibraryPage>(page) ?: return null
            result += parsed.data
            url = parsed.paging?.next
        }

        val labels = linkedMapOf(
            "watching" to "Watching",
            "completed" to "Completed",
            "on_hold" to "On Hold",
            "dropped" to "Dropped",
            "plan_to_watch" to "Plan to Watch",
        )
        val lists = labels.map { (status, label) ->
            SyncAPI.LibraryList(
                UiText.PlainText(label),
                result.filter { it.listStatus?.status == status }.mapNotNull { it.toLibraryItem() },
            )
        }
        return SyncAPI.LibraryMetadata(lists, ListSorting.entries.toSet())
    }

    private fun MalAnime.toSearchResult(): SyncAPI.SyncSearchResult? {
        val animeId = id ?: return null
        return SyncAPI.SyncSearchResult(
            name = title ?: return null,
            apiName = name,
            syncId = animeId.toString(),
            url = "$MAL_URL/anime/$animeId",
            posterUrl = mainPicture?.large ?: mainPicture?.medium,
            type = TvType.Anime,
            year = startDate?.substringBefore('-')?.toIntOrNull(),
        )
    }

    private fun MalListEntry.toLibraryItem(): SyncAPI.LibraryItem? {
        val animeId = node.id ?: return null
        return SyncAPI.LibraryItem(
            name = node.title ?: return null,
            url = "$MAL_URL/anime/$animeId",
            syncId = animeId.toString(),
            episodesCompleted = listStatus?.numEpisodesWatched,
            episodesTotal = node.numEpisodes,
            personalRating = Score.from10(listStatus?.score),
            lastUpdatedUnixTime = listStatus?.updatedAt.toEpochSeconds(),
            apiName = name,
            type = TvType.Anime,
            posterUrl = node.mainPicture?.large ?: node.mainPicture?.medium,
            posterHeaders = null,
            quality = null,
            releaseDate = node.startDate.toEpochSeconds()?.let { java.util.Date(it * 1000) },
            plot = node.synopsis,
        )
    }

    private fun AuthToken.authHeader(): Map<String, String> =
        mapOf("Authorization" to "Bearer ${accessToken ?: ""}")

    private fun parseId(value: String): Int? =
        value.toIntOrNull() ?: Regex("/anime/(\\d+)").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun watchType(status: String?): SyncWatchType = when (status) {
        "watching" -> SyncWatchType.WATCHING
        "completed" -> SyncWatchType.COMPLETED
        "on_hold" -> SyncWatchType.ON_HOLD
        "dropped" -> SyncWatchType.DROPPED
        "plan_to_watch" -> SyncWatchType.PLAN_TO_WATCH
        else -> SyncWatchType.NONE
    }

    private fun String?.toEpochSeconds(): Long? {
        if (this.isNullOrBlank()) return null
        return runCatching {
            OffsetDateTime.parse(this).toEpochSecond()
        }.recoverCatching {
            val day = when (length) {
                4 -> LocalDate.of(toInt(), 1, 1)
                7 -> YearMonth.parse(this).atDay(1)
                else -> LocalDate.parse(this.take(10))
            }
            day.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        }.getOrNull()
    }

    private fun String.formEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)

    private data class OAuthPayload(val state: String, val codeVerifier: String)
    private data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String? = null,
        @JsonProperty("refresh_token") val refreshToken: String? = null,
        @JsonProperty("expires_in") val expiresIn: Long? = null,
    ) {
        fun toAuthToken(): AuthToken = AuthToken(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessTokenLifetime = expiresIn?.let { APIHolder.unixTime + it },
            refreshTokenLifetime = if (refreshToken != null) APIHolder.unixTime + REFRESH_TOKEN_LIFETIME else null,
        )
    }

    private data class MalUser(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("picture") val picture: String? = null,
    )

    private data class SearchResponse(@JsonProperty("data") val data: List<MalSearchRow> = emptyList())
    private data class MalSearchRow(@JsonProperty("node") val node: MalAnime)
    private data class MalLibraryPage(
        @JsonProperty("data") val data: List<MalListEntry> = emptyList(),
        @JsonProperty("paging") val paging: Paging? = null,
    )
    private data class Paging(@JsonProperty("next") val next: String? = null)
    private data class MalListEntry(
        @JsonProperty("node") val node: MalAnime,
        @JsonProperty("list_status") val listStatus: MalListStatus? = null,
    )
    private data class MalAnime(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("main_picture") val mainPicture: MainPicture? = null,
        @JsonProperty("num_episodes") val numEpisodes: Int? = null,
        @JsonProperty("my_list_status") val myListStatus: MalListStatus? = null,
        @JsonProperty("start_date") val startDate: String? = null,
        @JsonProperty("end_date") val endDate: String? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("mean") val mean: Double? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("average_episode_duration") val averageEpisodeDuration: Int? = null,
        @JsonProperty("genres") val genres: List<Genre>? = null,
    )
    private data class MainPicture(
        @JsonProperty("medium") val medium: String? = null,
        @JsonProperty("large") val large: String? = null,
    )
    private data class Genre(@JsonProperty("name") val name: String)
    private data class MalListStatus(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("score") val score: Int? = null,
        @JsonProperty("num_episodes_watched") val numEpisodesWatched: Int? = null,
        @JsonProperty("updated_at") val updatedAt: String? = null,
    )

    companion object {
        private const val MAL_URL = "https://myanimelist.net"
        private const val API_URL = "https://api.myanimelist.net/v2"
        private const val REFRESH_TOKEN_LIFETIME = 31_536_000L
    }
}
