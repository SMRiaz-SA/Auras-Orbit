package com.auras.orbit.providers

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.auras.orbit.downloads.DownloadProviderHookToken
import com.auras.orbit.player.SubtitleFormat
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject

/**
 * Narrow bridge for Orbit's committed CS3 MainAPI registrations. Calls run only on the
 * ProviderOperationCoordinator workers, after the runtime/profile gate has supplied a snapshot.
 * This class does not open links, construct playback state, or execute extractors itself.
 */
internal class Cs3MediaProviderAdapter(
    private val pluginId: String,
    private val profileId: String,
    private val registrationIndex: Int,
    private val providerId: String,
    fallbackName: String,
    private val api: MainAPI,
    private val metadataKey: Cs3ProviderMetadata.Key,
    suppliedMetadata: Cs3ProviderMetadata.Snapshot?,
    private val pluginArchiveSha256: String,
    private val operationCoordinator: ProviderOperationCoordinator? = null,
    private val stillCurrent: () -> Boolean,
) : MediaProvider {
    private val metadata = suppliedMetadata?.takeIf { it.key == metadataKey }
    private val name = metadata?.name ?: fallbackName
    private val pageMethod = hasOverride("getMainPage", Int::class.javaPrimitiveType!!,
        MainPageRequest::class.java, Continuation::class.java)
    private val searchPageMethod = hasOverride("search", String::class.java,
        Int::class.javaPrimitiveType!!, Continuation::class.java)
    private val searchMethod = hasOverride("search", String::class.java, Continuation::class.java)
    private val quickSearchMethod = hasOverride("quickSearch", String::class.java, Continuation::class.java)
    private val loadMethod = hasOverride("load", String::class.java, Continuation::class.java)
    private val loadLinksMethod = hasOverride(
        "loadLinks", String::class.java, Boolean::class.javaPrimitiveType!!,
        kotlin.Function1::class.java, kotlin.Function1::class.java,
        Continuation::class.java,
    )
    private val videoInterceptorMethod = hasOverride("getVideoInterceptor", ExtractorLink::class.java)
    private val extractorVerifierJobMethod = hasOverride(
        "extractorVerifierJob", String::class.java, Continuation::class.java,
    )
    @Volatile private var canarySearchResultLimit: Int? = null
    private data class ActionMetadata(
        val video: ResultEpisode,
        val syncData: HashMap<String, String>,
        val ownerStableId: String,
    )
    private val actionMetadata = object : LinkedHashMap<String, ActionMetadata>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ActionMetadata>?): Boolean =
            size > MediaItem.MAX_EPISODES * 4
    }
    private val descriptor = ProviderDescriptor(
        providerId, name, metadata?.hasMainPage == true && pageMethod,
        searchPageMethod || searchMethod, metadata?.hasQuickSearch == true && quickSearchMethod,
        loadMethod && supportsDetails(metadata?.supportedTypes ?: emptySet()),
        loadLinksMethod, // Orbit may attempt its strictly filtered direct-media handoff.
        loadLinksMethod,
        loadLinksMethod, // Downloads still require a per-candidate direct-file classification.
    )

    override fun descriptor(): ProviderDescriptor = descriptor
    override fun owningPluginId(): String = pluginId
    override fun profileScopeId(): String = profileId
    override fun linkResolutionTimeoutMs(): Long =
        api.loadLinksTimeoutMs ?: MediaProvider.DEFAULT_LINK_RESOLUTION_TIMEOUT_MS
    override fun isRegistrationCurrent(): Boolean = try { stillCurrent() } catch (_: Throwable) { false }

    /** Limits only this fresh canary adapter's host-side result conversion. */
    fun limitSearchResultsForCanary(maxResults: Int) {
        require(maxResults in 1..MAX_CANARY_SEARCH_RESULTS)
        canarySearchResultLimit = maxResults
    }

    override fun loadHome(request: HomeRequest): List<HomeSection> {
        checkCurrent()
        if (!descriptor.homeFeed) unsupported("Home feed")
        if (request.cursor.isNotBlank() && request.cursor != request.page.toString()) {
            unsupported("CS3 Home pagination cursor")
        }
        @Suppress("UNCHECKED_CAST")
        val pages = api.mainPage as? List<MainPageData>
            ?: throw UnsupportedOperationException("CS3 provider returned an unsupported MainPageData collection")
        if (pages.size > MAX_HOME_PAGES) {
            throw IllegalStateException("CS3 Home page descriptions exceed emergency ceiling; no partial catalogue returned")
        }
        val results = ArrayList<HomeSection>()
        for ((index, page) in pages.withIndex()) {
            checkCurrent()
            if (page.name.isBlank()) continue
            val response: HomePageResponse? = awaitSuspend {
                api.getMainPage(
                    request.page,
                    MainPageRequest(page.name, page.data, page.horizontalImages),
                )
            }
            if (response == null) continue
            checkCurrent()
            for ((shelfIndex, shelf) in response.items.asSequence().withIndex()) {
                if (results.size >= MAX_HOME_SHELVES) {
                    throw IllegalStateException("CS3 Home shelves exceed emergency ceiling; no partial catalogue returned")
                }
                if (shelf.list.size > MAX_HOME_ITEMS_PER_SHELF) {
                    throw IllegalStateException("CS3 Home shelf exceeds emergency item ceiling; paging required")
                }
                val items = shelf.list.asSequence()
                    .mapNotNull { toMediaItem(it) }
                    .filter { request.mediaKinds.isEmpty() || request.mediaKinds.contains(it.kind) }
                    .toList()
                results.add(
                    HomeSection("$providerId:home:$index:$shelfIndex",
                        shelf.name.ifBlank { page.name }, items,
                        if (response.hasNext) (request.page + 1).toString() else ""),
                )
            }
        }
        checkCurrent()
        return Collections.unmodifiableList(results)
    }

    override fun search(request: SearchRequest): List<MediaItem> {
        checkCurrent()
        if (!descriptor.titleSearch) unsupported("Title search")
        if (searchPageMethod) {
            val response: SearchResponseList? = awaitSuspend {
                api.search(request.query, request.page)
            }
            checkCurrent()
            val items = response?.items ?: return emptyList()
            val limit = canarySearchResultLimit
            if (limit == null && items.size > MAX_SEARCH_CONVERSION) {
                throw IllegalStateException("CS3 search exceeds emergency ceiling; no partial results returned")
            }
            return items.asSequence().take(limit ?: MAX_SEARCH_CONVERSION)
                .mapNotNull(::toMediaItem).toList()
        }

        // The pinned CS3 API also has a legacy non-paginated search(String) shape. Do not
        // invoke the paginated base method for providers which implement only that overload.
        if (!searchMethod) unsupported("Title search")
        if (request.page != 1) unsupported("CS3 non-paginated search pages after page 1")
        val response: List<SearchResponse>? = awaitSuspend {
            api.search(request.query)
        }
        checkCurrent()
        val items = response ?: return emptyList()
        val limit = canarySearchResultLimit
        if (limit == null && items.size > MAX_SEARCH_CONVERSION) {
            throw IllegalStateException("CS3 search exceeds emergency ceiling; no partial results returned")
        }
        return items.asSequence().take(limit ?: MAX_SEARCH_CONVERSION)
            .mapNotNull(::toMediaItem).toList()
    }

    override fun quickSearch(request: SearchRequest): List<MediaItem> {
        checkCurrent()
        if (!descriptor.quickSearch) unsupported("Quick search")
        if (request.page != 1) unsupported("Quick search pages after page 1")
        val response: List<SearchResponse>? = awaitSuspend { api.quickSearch(request.query) }
        checkCurrent()
        if (response != null && response.size > MAX_QUICK_SEARCH_CONVERSION) {
            throw IllegalStateException("CS3 quick search exceeds emergency ceiling; no partial results returned")
        }
        return response?.asSequence()?.mapNotNull(::toMediaItem)?.toList() ?: emptyList()
    }

    /** A title can be reused without another MainAPI.load only while its exact
     * plugin registration and all required action metadata remain available. */
    override fun isLoadedDetailsContextCurrent(details: MediaItem): Boolean {
        if (!isRegistrationCurrent() || details.detailUrl.isBlank() || !descriptor.details) return false
        return synchronized(actionMetadata) {
            when (details.kind) {
                MediaKind.MOVIE -> (details.providerData.isBlank()
                    || actionMetadata[details.providerData]?.ownerStableId == details.stableId)
                    && details.episodes.all { episode ->
                        episode.providerData.isBlank()
                            || actionMetadata[episode.providerData]?.ownerStableId == details.stableId
                    }
                MediaKind.SERIES -> details.episodes.all { episode ->
                    episode.providerData.isBlank()
                        || actionMetadata[episode.providerData]?.ownerStableId == details.stableId
                }
                // Live streams are dynamic; re-fetch their details on every open.
                else -> false
            }
        }
    }

    override fun loadDetails(providerItemId: String): MediaItem {
        checkCurrent()
        if (!descriptor.details) unsupported("Movie, series, and live details")
        val loaded = awaitSuspend { api.load(providerItemId) }
            ?: throw IllegalStateException("CS3 provider returned no details response")
        checkCurrent()
        if ((loaded is LiveStreamLoadResponse) != (loaded.type == TvType.Live)) {
            unsupported("CS3 details response crossed the live media boundary")
        }
        val episodes = when (loaded) {
            is TvSeriesLoadResponse -> toEpisodeItems(stableId(providerItemId), loaded.episodes, "")
            is AnimeLoadResponse -> toAnimeEpisodeItems(stableId(providerItemId), loaded)
            is MovieLoadResponse -> emptyList()
            is LiveStreamLoadResponse -> emptyList()
            else -> unsupported("Unsupported CS3 details response type")
        }
        // CloudStream providers sometimes fill SearchResponse/LoadResponse.type with a broad
        // category that disagrees with the concrete response they return. The concrete load
        // subtype is authoritative for movies, live streams, and TV series. AnimeLoadResponse
        // also carries explicit movie/live categories; episode groups distinguish the remaining
        // anime series cases.
        val kind = when (loaded) {
            is MovieLoadResponse -> MediaKind.MOVIE
            is LiveStreamLoadResponse -> MediaKind.LIVE
            is TvSeriesLoadResponse -> MediaKind.SERIES
            is AnimeLoadResponse -> {
                val declaredKind = toMediaKind(loaded.type)
                when {
                    declaredKind == MediaKind.MOVIE || declaredKind == MediaKind.LIVE -> declaredKind
                    episodes.isNotEmpty() -> MediaKind.SERIES
                    else -> declaredKind
                }
            }
            else -> unsupported("Unsupported CS3 details response type")
        }
        if (kind == MediaKind.UNKNOWN) unsupported("Unknown CS3 details media kind")
        val detailUrl = loaded.url.ifBlank { providerItemId }
        val tags = loaded.tags.orEmpty().asSequence().take(200)
            .filter { it.isNotBlank() }.take(50).toList()
        val durationMinutes = loaded.duration?.takeIf { it > 0 } ?: 0
        val score = loaded.score?.toString().orEmpty()
        val cast = loaded.actors.orEmpty().asSequence().take(120).mapNotNull { actorData ->
            if (actorData == null) return@mapNotNull null
            val actor = actorData.actor ?: return@mapNotNull null
            val actorName = actor.name.trim()
            if (actorName.isEmpty()) return@mapNotNull null
            val role = actorData.roleString?.trim().orEmpty().ifEmpty {
                when {
                    actorData.role === ActorRole.Main -> "Main"
                    actorData.role === ActorRole.Supporting -> "Supporting"
                    actorData.role === ActorRole.Background -> "Background"
                    else -> ""
                }
            }
            MediaItem.CastMember(actorName, role, actor.image)
        }.take(30).toList()
        val currentStableId = stableId(providerItemId)
        val recommendations = loaded.recommendations.orEmpty().asSequence()
            .take(80).mapNotNull(::toMediaItem)
            .filter { it.stableId != currentStableId }
            .distinctBy { it.stableId }
            .take(20)
            .toList()
        val movieData = (loaded as? MovieLoadResponse)?.dataUrl.orEmpty()
        val liveData = (loaded as? LiveStreamLoadResponse)?.dataUrl.orEmpty()
        val episodeResponse = loaded as? EpisodeResponse
        val rawSeasonNames = episodeResponse?.seasonNames.orEmpty()
            .asSequence().take(400).toList()
        val seasonNames = rawSeasonNames.map { season ->
            MediaItem.SeasonInfo(season.season, season.name, season.displaySeason)
        }
        val nextAiring = episodeResponse?.nextAiring?.let { next ->
            MediaItem.NextAiringInfo(next.season, next.episode, next.unixTime)
        }
        val syncData = HashMap<String, String>()
        for ((key, value) in loaded.syncData.orEmpty().entries.asSequence().take(128)) {
            if (key.isNotBlank() && key.length <= 160 && value.length <= 4096) {
                syncData[key] = value
            }
        }
        val parentId = currentStableId.hashCode()
        synchronized(actionMetadata) {
            if (movieData.isNotEmpty()) {
                actionMetadata[movieData] = ActionMetadata(ResultEpisode(loaded.name, null,
                    loaded.posterUrl, 0, null, null, movieData, name, parentId, 0, 0,
                    durationMinutes.toLong() * 60_000L, loaded.score, loaded.plot, null,
                    loaded.type, parentId, VideoWatchState.None, runTime = loaded.duration),
                    syncData, currentStableId)
            }
            val originals = when (loaded) {
                is TvSeriesLoadResponse -> loaded.episodes.asSequence()
                    .take(MediaItem.MAX_EPISODES * 4).toList()
                is AnimeLoadResponse -> loaded.episodes.values.asSequence()
                    .flatMap { it.asSequence() }.take(MediaItem.MAX_EPISODES * 4).toList()
                else -> emptyList()
            }
            // Build one first-match lookup instead of scanning up to 2,000 original
            // provider episodes for each of 500 display episodes (1,000,000 comparisons).
            // Keep the first duplicate, matching the previous firstOrNull semantics.
            val originalIndex = indexOriginalEpisodes(originals)
            episodes.forEachIndexed { index, episode ->
                val original = originalIndex[EpisodeMatchKey(
                    episode.providerData, episode.season, episode.number)]
                val season = rawSeasonNames.firstOrNull { it.season == episode.season }
                actionMetadata[episode.providerData] = ActionMetadata(ResultEpisode(loaded.name,
                    original?.name, original?.posterUrl ?: loaded.posterUrl,
                    original?.episode ?: index + 1, original?.season, season?.displaySeason ?: original?.season,
                    episode.providerData, name, episode.stableId.hashCode(), index, 0,
                    // CloudStream's episode UI treats runTime as minutes and multiplies
                    // by 60 before formatting, despite the API KDoc saying seconds.
                    (original?.runTime ?: 0).toLong() * 60_000L, original?.score,
                    original?.description, null, loaded.type, parentId, VideoWatchState.None,
                    airDate = original?.date, runTime = original?.runTime, seasonData = season),
                    syncData, currentStableId)
            }
        }
        return MediaItem(currentStableId, loaded.name, kind, detailUrl,
            loaded.posterUrl, loaded.year?.toString(), loaded.plot,
            if (kind == MediaKind.LIVE) liveData else movieData,
            tags, durationMinutes, loaded.contentRating, loaded.backgroundPosterUrl,
            loaded.logoUrl, loaded.posterHeaders, score, cast, recommendations, episodes,
            episodeResponse?.showStatus, seasonNames, nextAiring)
    }

    /** Executes only MainAPI.loadLinks and captures callback metadata; no link is opened. */
    override fun resolveLinks(providerItemId: String, isCasting: Boolean): ProviderLinkResult =
        resolveLinks(providerItemId, isCasting, null)

    /** Publishes bounded snapshots while CS3 emits links and subtitles. */
    override fun resolveLinks(providerItemId: String, isCasting: Boolean,
                              progressListener: MediaProvider.LinkProgressListener?): ProviderLinkResult {
        checkCurrent()
        if (!descriptor.links) unsupported("Link metadata resolution")
        val links = Collections.synchronizedList(ArrayList<ProviderLinkCandidate>())
        val subtitles = Collections.synchronizedList(ArrayList<ProviderSubtitleCandidate>())
        val rawLinks = Collections.synchronizedList(ArrayList<ExtractorLink>())
        val rawSubtitles = Collections.synchronizedList(ArrayList<SubtitleData>())
        val linkIds = AtomicInteger()
        val exceededBudget = AtomicBoolean(false)
        val rejectedSubtitleCallbacks = AtomicInteger()
        val progressChanges = AtomicInteger()
        val lastProgressPublicationMs = AtomicLong(0L)
        fun countRejectedSubtitleCallback(): Boolean {
            while (true) {
                val current = rejectedSubtitleCallbacks.get()
                if (current >= ProviderLinkResult.MAX_SUBTITLES) return false
                if (rejectedSubtitleCallbacks.compareAndSet(current, current + 1)) return true
            }
        }
        fun snapshotResult(): ProviderLinkResult {
            val linkSnapshot = synchronized(links) { ArrayList(links) }
            val subtitleSnapshot = synchronized(subtitles) { ArrayList(subtitles) }
            val originalLinks = synchronized(rawLinks) { ArrayList(rawLinks) }
            val originalSubtitles = synchronized(rawSubtitles) { ArrayList(rawSubtitles) }
            val metadata = synchronized(actionMetadata) { actionMetadata[providerItemId] }
            val actionContext = metadata?.let { Cs3ActionContext(pluginId, profileId, it.video,
                LinkLoadingResult(Collections.unmodifiableList(originalLinks),
                    Collections.unmodifiableList(originalSubtitles), HashMap(it.syncData))) }
            return ProviderLinkResult(false, linkSnapshot, subtitleSnapshot, "", actionContext,
                rejectedSubtitleCallbacks.get())
        }
        fun publishProgress(firstValidLink: Boolean = false) {
            val listener = progressListener ?: return
            val changes = progressChanges.incrementAndGet()
            val now = System.nanoTime() / 1_000_000L
            val previousPublication = lastProgressPublicationMs.get()
            val shouldPublish = firstValidLink || changes == 1 || changes % 32 == 0 ||
                    now - previousPublication >= 100L
            if (!shouldPublish) return
            if (firstValidLink) lastProgressPublicationMs.set(now)
            else if (!lastProgressPublicationMs.compareAndSet(previousPublication, now)) return
            try { listener.onProgress(snapshotResult()) }
            catch (_: RuntimeException) {
                // A playback observer must not interrupt provider callbacks.
            }
        }
        val returned = awaitSuspend {
            api.loadLinks(
                providerItemId,
                isCasting,
                { subtitle: SubtitleFile ->
                    checkCurrent()
                    // Both raw action metadata and player candidates share the same
                    // validated, bounded admission. Do not copy raw plugin headers
                    // into a HashMap before validating their cardinality and length.
                    val subtitleUrl: String? = subtitle.url
                    var shouldPublishRejectedCount = false
                    val candidate = if (subtitleUrl == null || subtitleUrl.length > 4096
                        || subtitleUrl.isBlank()) {
                        shouldPublishRejectedCount = countRejectedSubtitleCallback()
                        null
                    } else synchronized(subtitles) {
                        if (subtitles.size >= ProviderLinkResult.MAX_SUBTITLES) {
                            exceededBudget.set(true)
                            shouldPublishRejectedCount = countRejectedSubtitleCallback()
                            null
                        } else {
                            try {
                                ProviderSubtitleCandidate(
                                    "$providerId:subtitle:${subtitles.size}", subtitle.lang,
                                    subtitleUrl, subtitle.headers, subtitle.mimeType,
                                ).also { subtitles.add(it) }
                            } catch (_: RuntimeException) {
                                // Bad optional subtitles must not poison a valid video link.
                                shouldPublishRejectedCount = countRejectedSubtitleCallback()
                                null
                            }
                        }
                    }
                    if (candidate != null) synchronized(rawSubtitles) {
                        if (rawSubtitles.size < ProviderLinkResult.MAX_SUBTITLES) {
                            rawSubtitles.add(SubtitleData(subtitle.lang, "", candidate.uri,
                                SubtitleOrigin.URL,
                                SubtitleFormat.mimeType(candidate.uri, candidate.mimeType).orEmpty(),
                                candidate.headers, subtitle.lang))
                        }
                    }
                    if (candidate != null || shouldPublishRejectedCount) publishProgress()
                },
                { link: ExtractorLink ->
                    checkCurrent()
                    synchronized(rawLinks) {
                        if (rawLinks.size < ProviderLinkResult.MAX_LINKS) rawLinks.add(link)
                        else exceededBudget.set(true)
                    }
                    if (link.url.isNotBlank()) {
                        val index = linkIds.getAndIncrement()
                        if (index >= ProviderLinkResult.MAX_LINKS) exceededBudget.set(true)
                        if (link.audioTracks.size > ProviderLinkCandidate.MAX_AUDIO_TRACKS) {
                            exceededBudget.set(true)
                        }
                        val audioTracks = link.audioTracks
                            .take(ProviderLinkCandidate.MAX_AUDIO_TRACKS)
                            .mapIndexedNotNull { audioIndex, audio ->
                                try {
                                    ProviderLinkCandidate.AudioTrackCandidate(
                                        "audio-track-$audioIndex", audio.url, audio.headers,
                                    )
                                } catch (_: RuntimeException) {
                                    // A malformed optional audio file must not discard its video link.
                                    null
                                }
                            }
                        val playbackHooks = if (operationCoordinator != null && (videoInterceptorMethod
                            || (extractorVerifierJobMethod && link.extractorData != null))) {
                            Cs3PlaybackHooks(pluginId, profileId, providerId, registrationIndex,
                                name, pluginArchiveSha256, api,
                                link.takeIf { videoInterceptorMethod },
                                link.extractorData.takeIf { extractorVerifierJobMethod }, stillCurrent,
                                operationCoordinator)
                        } else null
                        val candidate = try { ProviderLinkCandidate(
                            "$providerId:link:$index", link.name, link.source, link.url,
                            link.referer, link.quality, linkTypeName(link.type),
                            link.extractorData, link.headers, audioTracks, playbackHooks,
                        ) } catch (_: RuntimeException) { null }
                        var firstAccepted = false
                        val accepted = synchronized(links) {
                            if (candidate != null && links.size < ProviderLinkResult.MAX_LINKS) {
                                links.add(candidate)
                                firstAccepted = links.size == 1
                                true
                            } else {
                                if (candidate != null) exceededBudget.set(true)
                                false
                            }
                        }
                        if (accepted) publishProgress(firstAccepted)
                    }
                },
            )
        }
        checkCurrent()
        if (exceededBudget.get()) {
            throw IllegalStateException("CS3 links, subtitles or audio tracks exceed the emergency "
                + "materialization ceiling; refusing to return incomplete callback results")
        }
        val linkSnapshot = synchronized(links) { ArrayList(links) }
        val subtitleSnapshot = synchronized(subtitles) { ArrayList(subtitles) }
        val originalLinks = synchronized(rawLinks) { ArrayList(rawLinks) }
        val originalSubtitles = synchronized(rawSubtitles) { ArrayList(rawSubtitles) }
        val metadata = synchronized(actionMetadata) { actionMetadata[providerItemId] }
        val actionContext = metadata?.let { Cs3ActionContext(pluginId, profileId, it.video,
            LinkLoadingResult(Collections.unmodifiableList(originalLinks),
                Collections.unmodifiableList(originalSubtitles), HashMap(it.syncData))) }
        val diagnostic = when {
            returned != true -> "CS3 loadLinks returned false"
            linkSnapshot.isEmpty() -> "CS3 loadLinks completed without a link callback"
            else -> ""
        }
        return ProviderLinkResult(returned == true && (linkSnapshot.isNotEmpty()
            || actionContext?.result?.links?.isNotEmpty() == true),
            linkSnapshot, subtitleSnapshot, diagnostic, actionContext,
            rejectedSubtitleCallbacks.get())
    }

    private fun toMediaItem(response: SearchResponse): MediaItem? {
        checkCurrent()
        if (response.name.isBlank() || response.url.isBlank()) return null
        // Live has separate detail/playback actions from movie and series; do not infer across
        // this boundary when the CS3 response class and declared type disagree.
        if ((response is LiveSearchResponse) != (response.type == TvType.Live)) return null
        val embeddedKind = (response as? MovieSearchResponse)?.let {
            embeddedProviderMediaKind(it.url)
        }
        if (embeddedKind == MediaKind.LIVE) return null
        val kind = toMediaKind(response, embeddedKind)
        if ((response is LiveSearchResponse) != (kind == MediaKind.LIVE)) return null
        val year = when (response) {
            is MovieSearchResponse -> response.year
            is TvSeriesSearchResponse -> response.year
            is AnimeSearchResponse -> response.year
            else -> null
        }
        return MediaItem(stableId(response.url), response.name, kind,
            response.url, response.posterUrl, year?.toString(), "", "",
            emptyList(), 0, "", "", "", emptyMap(), response.score?.toString().orEmpty(),
            emptyList())
    }

    private fun stableId(itemUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateLengthPrefixed(digest, providerId)
        updateLengthPrefixed(digest, canonicalizeUrlForIdentity(itemUrl))
        val hash = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val prefix = providerId.take(183)
        return "$prefix::sha256:$hash"
    }

    /**
     * Remove only recognized volatile credentials and tracking fields from an HTTP URL's identity.
     * Content identifiers and path bytes remain untouched. If parsing a parameter is ambiguous,
     * retain the original URL so identity normalization always fails closed.
     */
    private fun canonicalizeUrlForIdentity(itemUrl: String): String {
        val uri = try {
            URI(itemUrl)
        } catch (_: URISyntaxException) {
            return itemUrl
        }
        val scheme = uri.scheme ?: return itemUrl
        if (!scheme.equals("http", ignoreCase = true)
            && !scheme.equals("https", ignoreCase = true)) return itemUrl
        if (uri.rawAuthority == null) return itemUrl
        val rawQuery = uri.rawQuery ?: return itemUrl
        val retained = ArrayList<Pair<String, String>>()
        for (rawParameter in rawQuery.split('&')) {
            val separator = rawParameter.indexOf('=')
            val rawName = if (separator < 0) rawParameter else rawParameter.substring(0, separator)
            val name = decodeQueryName(rawName) ?: return itemUrl
            if (!isVolatileIdentityParameter(name)) retained.add(name to rawParameter)
        }
        if (retained.isEmpty()) return rebuildUrl(uri, null)

        // Treat a query as a map only when each name is unique (including case variants).
        // Duplicate names retain their original order because servers can interpret them as lists.
        val foldedNames = retained.map { it.first.lowercase(Locale.ROOT) }
        val ordered = if (foldedNames.toSet().size == foldedNames.size && foldedNames.none { it.isEmpty() }) {
            retained.sortedBy { it.first.lowercase(Locale.ROOT) }
        } else {
            retained
        }
        return rebuildUrl(uri, ordered.joinToString("&") { it.second })
    }

    private fun rebuildUrl(uri: URI, rawQuery: String?): String = buildString {
        append(uri.scheme).append("://").append(uri.rawAuthority)
        append(uri.rawPath.orEmpty())
        if (rawQuery != null) append('?').append(rawQuery)
        uri.rawFragment?.let { append('#').append(it) }
    }

    private fun decodeQueryName(rawName: String): String? = try {
        // URLDecoder normally treats '+' as a space; URI query names use it literally.
        URLDecoder.decode(rawName.replace("+", "%2B"), Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun isVolatileIdentityParameter(rawName: String): Boolean {
        val name = rawName.lowercase(Locale.ROOT)
        return name.startsWith("utm_") || name in VOLATILE_IDENTITY_PARAMETERS
    }

    private fun updateLengthPrefixed(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private fun toMediaKind(type: TvType?): MediaKind = when {
        type == TvType.Movie || type == TvType.AnimeMovie -> MediaKind.MOVIE
        type == TvType.Live -> MediaKind.LIVE
        type == TvType.TvSeries || type == TvType.Cartoon || type == TvType.Anime ||
            type == TvType.OVA || type == TvType.AsianDrama -> MediaKind.SERIES
        else -> MediaKind.UNKNOWN
    }

    private fun toMediaKind(
        response: SearchResponse,
        embeddedKind: MediaKind?,
    ): MediaKind = when (response) {
        is LiveSearchResponse -> MediaKind.LIVE
        is TvSeriesSearchResponse -> MediaKind.SERIES
        // Some compatible CS3 plugins (including the pinned StreamPlay release) wrap both
        // movie and TV results in MovieSearchResponse, while preserving the original media
        // discriminator in their structured URL payload. Trust only recognized explicit
        // movie/series values there; live values are rejected at the action boundary.
        is MovieSearchResponse -> {
            val declaredKind = toMediaKind(response.type)
            when {
                // A declared series type is already an unambiguous CS3 classification.
                declaredKind == MediaKind.SERIES -> MediaKind.SERIES
                // Recover only the observed MovieSearchResponse -> series mismatch.
                embeddedKind == MediaKind.SERIES -> MediaKind.SERIES
                declaredKind != MediaKind.UNKNOWN -> declaredKind
                embeddedKind != null -> embeddedKind
                else -> MediaKind.MOVIE
            }
        }
        else -> toMediaKind(response.type)
    }

    private fun embeddedProviderMediaKind(providerData: String): MediaKind? {
        if (providerData.length !in 2..MAX_PROVIDER_TYPE_DATA_LENGTH
            || !hasSafeJsonNesting(providerData)) return null
        val payload = try {
            JSONObject(providerData)
        } catch (_: JSONException) {
            return null
        }
        // Prefer the more explicit aliases. Unknown values are skipped, so they cannot mask
        // a later recognized media type; if recognized aliases conflict, this order wins.
        return sequenceOf("mediaType", "media_type", "type")
            .mapNotNull { key ->
                if (!payload.has(key) || payload.isNull(key)) null
                else payload.optString(key).trim().lowercase(Locale.ROOT)
            }
            .mapNotNull(::mediaKindFromProviderType)
            .firstOrNull()
    }

    private fun mediaKindFromProviderType(mediaType: String): MediaKind? =
        when (mediaType.replace('-', '_').replace(' ', '_')) {
            "movie", "film", "anime_movie", "animemovie" -> MediaKind.MOVIE
            "tv", "series", "show", "tv_series", "tvseries", "anime", "cartoon",
            "ova", "asian_drama", "asiandrama" -> MediaKind.SERIES
            "live" -> MediaKind.LIVE
            else -> null
        }

    private fun hasSafeJsonNesting(value: String): Boolean {
        var depth = 0
        var insideString = false
        var escaped = false
        for (character in value) {
            if (insideString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> insideString = false
                }
                continue
            }
            when (character) {
                '"' -> insideString = true
                '{', '[' -> {
                    depth++
                    if (depth > MAX_PROVIDER_TYPE_JSON_DEPTH) return false
                }
                '}', ']' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0 && !insideString
    }

    private fun supportsDetails(types: Set<TvType>): Boolean {
        if (types.isEmpty()) return false
        // CloudStream's `Others` and `Torrent` can be advertised catalog categories;
        // they do not determine the concrete response returned by `load()`. loadDetails()
        // still checks the actual LoadResponse class and media type before conversion.
        return types.all {
            it == TvType.Others || it == TvType.Torrent || it == TvType.Movie || it == TvType.AnimeMovie || it == TvType.TvSeries
                || it == TvType.Cartoon || it == TvType.Anime || it == TvType.OVA
                || it == TvType.AsianDrama || it == TvType.Live
        }
    }

    /** Creates a fresh verifier-only owner for a matching persisted registration token. */
    fun createVerifierOnlyHooks(extractorData: String): Cs3PlaybackHooks? {
        val coordinator = operationCoordinator ?: return null
        if (!extractorVerifierJobMethod
            || extractorData.length > DownloadProviderHookToken.MAX_FIELD_LENGTH
            || !stillCurrent()) return null
        val hooks = Cs3PlaybackHooks(pluginId, profileId, providerId, registrationIndex,
            name, pluginArchiveSha256, api, null, extractorData, stillCurrent, coordinator)
        return try {
            hooks.activate(coordinator)
            if (!stillCurrent()) {
                hooks.close()
                null
            } else hooks
        } catch (_: Throwable) {
            hooks.close()
            null
        }
    }

    private data class EpisodeMatchKey(val data: String?, val season: Int, val number: Int)

    /** Bounded first-match index over the original CS3 episode data, never an identity merge. */
    private fun indexOriginalEpisodes(originals: List<Episode>): Map<EpisodeMatchKey, Episode> {
        val indexed = HashMap<EpisodeMatchKey, Episode>(originals.size)
        for (original in originals) {
            val key = EpisodeMatchKey(original.data, original.season ?: -1,
                original.episode ?: -1)
            indexed.putIfAbsent(key, original)
        }
        return indexed
    }

    private fun toAnimeEpisodeItems(parentId: String, response: AnimeLoadResponse): List<MediaItem.EpisodeItem> {
        val result = ArrayList<MediaItem.EpisodeItem>()
        // Fail explicitly rather than silently dropping an anime episode group.
        val totalEpisodes = response.episodes.values.sumOf { it.size.toLong() }
        if (totalEpisodes > MediaItem.MAX_EPISODES) {
            throw IllegalStateException("CS3 anime contains more episodes than Orbit can currently materialize")
        }
        // Bound the provider-owned map traversal before constructing converter state.
        val groups = response.episodes.entries.asSequence().take(MAX_ANIME_GROUPS).map { (status, episodes) ->
            val variant = when (status) {
                DubStatus.Dubbed -> "Dubbed"
                DubStatus.Subbed -> "Subbed"
                else -> "Original"
            }
            AnimeEpisodeGroup(variant, episodes)
        }.toList()
        var scanned = 0
        while (result.size < MediaItem.MAX_EPISODES && scanned < MAX_EPISODE_SCAN
                && groups.any { it.hasNext() }) {
            checkCurrent()
            var progressed = false
            for (group in groups) {
                if (result.size >= MediaItem.MAX_EPISODES || scanned >= MAX_EPISODE_SCAN) break
                if (!group.hasNext()) continue
                // Round-robin even if an entry is malformed. Draining all bad entries
                // from one variant can otherwise starve a later playable variant.
                val index = group.nextIndex
                val episode = group.next()
                scanned++
                progressed = true
                val normalized = toEpisodeItem(parentId, episode, group.variant, index)
                if (normalized != null) result.add(normalized)
            }
            if (!progressed) break
        }
        return Collections.unmodifiableList(result)
    }

    private fun toEpisodeItems(
        parentId: String,
        episodes: List<Episode>,
        variant: String,
    ): List<MediaItem.EpisodeItem> {
        if (episodes.isEmpty()) return emptyList()
        // Never silently produce a partial show when a provider exceeds the
        // current materialization ceiling. A later lazy episode repository can
        // lift this emergency limit without losing provider ordering or data.
        if (episodes.size > MediaItem.MAX_EPISODES) {
            throw IllegalStateException("CS3 series contains more episodes than Orbit can currently materialize")
        }
        val result = ArrayList<MediaItem.EpisodeItem>()
        for ((index, episode) in episodes.asSequence().take(MAX_EPISODE_SCAN).withIndex()) {
            checkCurrent()
            if (result.size >= MediaItem.MAX_EPISODES) break
            val normalized = toEpisodeItem(parentId, episode, variant, index)
            if (normalized != null) result.add(normalized)
        }
        return Collections.unmodifiableList(result)
    }

    private fun toEpisodeItem(
        parentId: String,
        episode: Episode?,
        variant: String,
        fallbackOrder: Int,
    ): MediaItem.EpisodeItem? {
        if (episode == null) return null
        val data = episode.data.orEmpty()
        // Reject oversized payloads instead of handing the provider a truncated value.
        if (data.length > MediaItem.MAX_EPISODE_DATA_CHARS) return null
        val season = episode.season ?: -1
        val number = episode.episode ?: -1
        // Match CloudStream's shipped EpisodeAdapter convention: runTime is in minutes.
        // Orbit's media model stores seconds, so normalize at this boundary.
        val runtimeSeconds = episode.runTime?.takeIf { it > 0 }?.toLong()
            ?.times(60L)?.takeIf { it <= 172_800L }?.toInt() ?: 0
        return MediaItem.EpisodeItem(
            stableEpisodeId(parentId, episode, variant, fallbackOrder),
            episode.name.orEmpty(), season, number,
            episode.posterUrl.orEmpty(), episode.description.orEmpty(), runtimeSeconds,
            episode.score?.toString().orEmpty(), episode.date ?: 0L, variant, data,
        )
    }

    /** Episode IDs expose only a digest; provider URLs and Episode.data stay in memory as payload. */
    private fun stableEpisodeId(
        parentStableId: String,
        episode: Episode,
        variant: String,
        fallbackOrder: Int,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateLengthPrefixed(digest, providerId)
        updateLengthPrefixed(digest, parentStableId)
        updateLengthPrefixed(digest, variant)
        val season = episode.season
        val number = episode.episode
        if (number != null) {
            updateLengthPrefixed(digest, "numbered")
            updateLengthPrefixed(digest, season?.toString() ?: "season-missing")
            updateLengthPrefixed(digest, number.toString())
            if (season == null) {
                // Episode numbers are commonly reused across unsorted/null-season lists.
                updateLengthPrefixed(digest, "order-without-season")
                updateLengthPrefixed(digest, fallbackOrder.toString())
            }
        } else {
            updateLengthPrefixed(digest, "fallback")
            updateLengthPrefixed(digest, season?.toString().orEmpty())
            updateLengthPrefixed(digest, episode.name.orEmpty())
            updateLengthPrefixed(digest, episode.date?.toString().orEmpty())
            updateLengthPrefixed(digest, episode.posterUrl.orEmpty())
            updateLengthPrefixed(digest, episode.description.orEmpty())
            updateLengthPrefixed(digest, episode.runTime?.toString().orEmpty())
            updateLengthPrefixed(digest, episode.score?.toString().orEmpty())
            updateLengthPrefixed(digest, fallbackOrder.toString())
        }
        val hash = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "cs3-episode:sha256:$hash"
    }

    private class AnimeEpisodeGroup(
        val variant: String,
        private val episodes: List<Episode>,
    ) {
        var nextIndex: Int = 0
            private set

        fun hasNext(): Boolean = nextIndex < episodes.size

        fun next(): Episode? {
            if (!hasNext()) return null
            return episodes[nextIndex++]
        }
    }

    private fun linkTypeName(type: ExtractorLinkType?): String = when (type) {
        ExtractorLinkType.VIDEO -> "VIDEO"
        ExtractorLinkType.M3U8 -> "M3U8"
        ExtractorLinkType.DASH -> "DASH"
        ExtractorLinkType.TORRENT -> "TORRENT"
        ExtractorLinkType.MAGNET -> "MAGNET"
        else -> "UNKNOWN"
    }

    private fun hasOverride(name: String, vararg parameters: Class<*>): Boolean = try {
        api.javaClass.getMethod(name, *parameters).declaringClass != MainAPI::class.java
    } catch (_: ReflectiveOperationException) {
        false
    }

    private fun checkCurrent() {
        if (!stillCurrent()) throw CancellationException(
            "CS3 provider request belongs to a profile or plugin snapshot that is no longer active",
        )
    }

    private fun unsupported(capability: String): Nothing = throw UnsupportedOperationException(
        "$capability is not supported by this CS3 provider bridge",
    )

    private fun <T> awaitSuspend(block: suspend () -> T): T = runBlocking { block() }

    private companion object {
        // Emergency materialisation fuses; visible output is paged independently.
        // These are not CloudStream API feature caps and cannot silently truncate.
        const val MAX_HOME_PAGES = 1024
        const val MAX_HOME_SHELVES = 2048
        const val MAX_HOME_ITEMS_PER_SHELF = 25_000
        const val MAX_SEARCH_CONVERSION = 25_000
        const val MAX_QUICK_SEARCH_CONVERSION = 25_000
        const val MAX_CANARY_SEARCH_RESULTS = 200
        const val MAX_PROVIDER_TYPE_DATA_LENGTH = 32_768
        const val MAX_PROVIDER_TYPE_JSON_DEPTH = 16
        // A malformed provider can return unlimited invalid episodes. Bound attempts,
        // not only the number successfully converted into actionable episodes.
        const val MAX_EPISODE_SCAN = MediaItem.MAX_EPISODES * 4
        const val MAX_ANIME_GROUPS = 16

        /** Explicit volatile credential, signature, expiry, and advertising keys only. */
        val VOLATILE_IDENTITY_PARAMETERS = setOf(
            "access_token", "auth", "authorization", "auth_token", "authtoken",
            "token", "jwt", "signature", "sig", "sign", "hmac", "nonce",
            "policy", "key-pair-id", "awsaccesskeyid", "googleaccessid",
            "exp", "expires", "expire", "expires_at", "expires_in", "expiry", "expiration",
            "hdnea", "__gda__",
            "x-amz-algorithm", "x-amz-credential", "x-amz-date", "x-amz-expires",
            "x-amz-signedheaders", "x-amz-signature", "x-amz-security-token",
            "x-goog-algorithm", "x-goog-credential", "x-goog-date", "x-goog-expires",
            "x-goog-signedheaders", "x-goog-signature",
            "x-oss-credential", "x-oss-date", "x-oss-expires", "x-oss-signature",
            "fbclid", "gclid", "dclid", "msclkid", "yclid", "ttclid", "igshid",
            "mc_cid", "mc_eid", "_ga", "_gl",
        )
    }
}
