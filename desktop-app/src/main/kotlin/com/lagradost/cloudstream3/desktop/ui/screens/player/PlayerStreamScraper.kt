package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.desktop.player.QualityDataHelper
import com.lagradost.cloudstream3.desktop.player.ytdl.DesktopYtDlpBinary
import com.lagradost.cloudstream3.desktop.stremio.StremioAddonManager
import com.lagradost.cloudstream3.desktop.ui.VideoLaunchData
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.runtime.executor.SafePluginInvoker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlayerStreamScraper(
    private val scope: CoroutineScope,
    private val isEpisodeStillNext: (String) -> Boolean = { true },
    private val preScrapeTimeoutMs: Long = SafePluginInvoker.TIMEOUT_SCRAPE_MS,
) {
    private val preScrapeLock = Any()
    private var preScrapeGeneration = 0L
    private var activePreScrapeEpisodeId: String? = null

    @Volatile
    private var preScrapeJob: Job? = null

    val isPreScrapeActive: Boolean
        get() = synchronized(preScrapeLock) { preScrapeJob?.isActive == true }

    fun cancelPreScrape() {
        synchronized(preScrapeLock) {
            preScrapeGeneration++
            preScrapeJob?.cancel()
            preScrapeJob = null
            activePreScrapeEpisodeId = null
        }
    }

    fun preScrapeNextEpisode(
        nextEp: Episode,
        currentLaunchData: VideoLaunchData?,
    ) {
        val nextEpId = nextEp.data
        if (nextEpId.isBlank()) return
        cancelSupersededPreScrape(nextEpId)
        if (LinkCache.get(nextEpId) != null) return

        val currentData = currentLaunchData ?: return
        val apiName = currentData.history.apiName.takeIf { it.isNotBlank() } ?: currentData.loadResponse?.apiName
        val provider = apiName?.let { APIHolder.getApiFromNameNull(it) } ?: return

        preScrapeNextEpisode(nextEp, provider)
    }

    /** Test seam for fixture providers; production resolves the provider from the current launch data. */
    internal fun preScrapeNextEpisode(nextEp: Episode, provider: MainAPI) {
        val nextEpId = nextEp.data
        if (nextEpId.isBlank()) return
        cancelSupersededPreScrape(nextEpId)
        if (LinkCache.get(nextEpId) != null) return

        synchronized(preScrapeLock) {
            if (preScrapeJob?.isActive == true) {
                if (activePreScrapeEpisodeId == nextEpId) return
                preScrapeJob?.cancel()
            }

            val generation = ++preScrapeGeneration
            activePreScrapeEpisodeId = nextEpId
            preScrapeJob = scope.launch(Dispatchers.IO) {
                runPreScrape(provider, nextEp, generation)
            }
        }
    }

    private fun cancelSupersededPreScrape(requestedEpisodeId: String) {
        synchronized(preScrapeLock) {
            if (
                preScrapeJob?.isActive == true &&
                (activePreScrapeEpisodeId != requestedEpisodeId || LinkCache.get(requestedEpisodeId) != null)
            ) {
                preScrapeGeneration++
                preScrapeJob?.cancel()
                preScrapeJob = null
                activePreScrapeEpisodeId = null
            }
        }
    }

    private suspend fun runPreScrape(provider: MainAPI, nextEp: Episode, generation: Long) {
        val episodeId = nextEp.data
        val callbackLock = Any()
        val collectedLinks = mutableListOf<ExtractorLink>()
        val collectedSubs = mutableListOf<SubtitleFile>()
        var callbacksOpen = true

        fun isCurrentAttempt(): Boolean = synchronized(preScrapeLock) {
            preScrapeGeneration == generation && activePreScrapeEpisodeId == episodeId
        }

        val subtitleCallback = SafePluginInvoker.wrapCallback<SubtitleFile>("PreScrapeSubtitleCallback:${provider.name}") { sub ->
            synchronized(callbackLock) {
                if (callbacksOpen && isCurrentAttempt()) collectedSubs.add(sub)
            }
        }
        val linkCallback = SafePluginInvoker.wrapCallback<ExtractorLink>("PreScrapeLinkCallback:${provider.name}") { link ->
            synchronized(callbackLock) {
                if (callbacksOpen && isCurrentAttempt()) collectedLinks.add(link)
            }
        }

        try {
            AppLogger.i("PlayerStreamScraper", "Starting background pre-scrape for next episode: ${nextEp.name ?: episodeId}")
            val result = SafePluginInvoker.invoke(
                tag = "PlayerStreamScraper:${provider.name}:PreScrape",
                providerName = provider.name,
                timeoutMs = preScrapeTimeoutMs,
                penalizeOnTimeout = false,
            ) {
                provider.loadLinks(
                    data = episodeId,
                    isCasting = false,
                    subtitleCallback = subtitleCallback,
                    callback = linkCallback,
                )
            }

            val (links, subtitles) = synchronized(callbackLock) {
                callbacksOpen = false
                collectedLinks.toList() to collectedSubs.toList()
            }

            if (result.isFailure) {
                AppLogger.w("PlayerStreamScraper", "Background pre-scrape failed for $episodeId: ${result.exceptionOrNull()?.message}")
                return
            }
            if (links.isEmpty()) return

            val sortedLinks = QualityDataHelper.sortLinks(links)
            val cached = synchronized(preScrapeLock) {
                if (
                    preScrapeGeneration != generation ||
                    activePreScrapeEpisodeId != episodeId ||
                    !isEpisodeStillNext(episodeId) ||
                    LinkCache.get(episodeId) != null
                ) {
                    false
                } else {
                    LinkCache.set(episodeId, sortedLinks, subtitles)
                    true
                }
            }

            if (cached) {
                AppLogger.i("PlayerStreamScraper", "Background pre-scrape complete: cached ${sortedLinks.size} streams for ${nextEp.name ?: episodeId}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLogger.w("PlayerStreamScraper", "Background pre-scrape error: ${e.message}")
        } finally {
            synchronized(callbackLock) { callbacksOpen = false }
            synchronized(preScrapeLock) {
                if (preScrapeGeneration == generation && activePreScrapeEpisodeId == episodeId) {
                    preScrapeJob = null
                    activePreScrapeEpisodeId = null
                }
            }
        }
    }

    suspend fun executeScrape(
        provider: MainAPI,
        targetEpisodeId: String,
        autoPlay: Boolean,
        currentLaunchData: VideoLaunchData,
        targetEpisodeData: Episode?,
        onSubtitle: (SubtitleFile) -> Unit,
        onLink: (ExtractorLink) -> Unit,
        onSeekabilityResolved: (ExtractorLink) -> Unit,
    ): Result<Unit> {
        val sharedSubtitleCallback = SafePluginInvoker.wrapCallback("SubtitleCallback") { sub: SubtitleFile ->
            val cleanUrl = sub.url.trim()
            if (cleanUrl.isNotBlank()) {
                val cleanSub = sub.copy(url = cleanUrl, lang = sub.lang.trim())
                AppLogger.i("Plugin:${provider.name}", "Extracted subtitle: [${cleanSub.lang}] ${cleanSub.url}")
                onSubtitle(cleanSub)
            }
        }

        val sharedLinkCallback = SafePluginInvoker.wrapCallback("LinkCallback") { link: ExtractorLink ->
            AppLogger.i("Plugin:${provider.name}", "Extracted link: ${link.name} (quality=${link.quality}) -> ${link.url}")

            // Probe range seekability for non-HLS/DASH streams in background
            if (!link.isM3u8 && !link.isDash && link.type != ExtractorLinkType.M3U8 && link.type != ExtractorLinkType.DASH) {
                scope.launch(Dispatchers.IO) {
                    val seekability = QualityDataHelper.probeRangeSeekability(link)
                    if (seekability != QualityDataHelper.Seekability.UNKNOWN) {
                        onSeekabilityResolved(link)
                    }
                }
            }

            onLink(link)
        }

        // Concurrently query installed Stremio stream addons
        val epHistoryId = currentLaunchData.history.episodeId
        val loadResp = currentLaunchData.loadResponse
        val resolvedImdbId = when {
            targetEpisodeId.startsWith("tt", ignoreCase = true) -> targetEpisodeId.substringBefore(":")
            epHistoryId?.startsWith("tt", ignoreCase = true) == true -> epHistoryId.substringBefore(":")
            loadResp?.syncData?.get("imdb")?.startsWith("tt", ignoreCase = true) == true -> loadResp.syncData["imdb"]
            loadResp?.url?.startsWith("tt", ignoreCase = true) == true -> loadResp.url.substringBefore(":")
            else -> com.lagradost.cloudstream3.desktop.metadata.MetadataPipeline.getCachedImdbId(currentLaunchData.history.showName)
        }

        val epNumber = currentLaunchData.history.episode ?: targetEpisodeData?.episode
        val seasonNumber = currentLaunchData.history.season ?: targetEpisodeData?.season
        scope.launch(Dispatchers.IO) {
            StremioAddonManager.searchStreams(
                imdbId = resolvedImdbId,
                season = seasonNumber,
                episode = epNumber,
                title = currentLaunchData.history.showName,
                onLink = { link -> sharedLinkCallback(link) },
            )
        }

        // Route YouTube streams directly to yt-dlp resolver
        val isYoutubeTarget = DesktopYtDlpBinary.isYouTubeUrl(targetEpisodeId) ||
            (provider.name.equals("YouTube", ignoreCase = true) && targetEpisodeId.isNotBlank())
        if (isYoutubeTarget) {
            val ytUrl = if (targetEpisodeId.startsWith("http")) targetEpisodeId else "https://www.youtube.com/watch?v=$targetEpisodeId"
            AppLogger.i("PlayerStreamScraper", "Routing YouTube episode to yt-dlp resolver: $ytUrl")
            val isLiveStream = currentLaunchData.loadResponse?.type == com.lagradost.cloudstream3.TvType.Live ||
                currentLaunchData.loadResponse?.tags?.contains("Live") == true ||
                targetEpisodeData?.name?.contains("Live", ignoreCase = true) == true
            val ytQualities = if (isLiveStream) {
                listOf(
                    Pair(0, "Live (Auto)"),
                    Pair(com.lagradost.cloudstream3.utils.Qualities.P1080.value, "Live 1080p"),
                    Pair(com.lagradost.cloudstream3.utils.Qualities.P720.value, "Live 720p"),
                    Pair(com.lagradost.cloudstream3.utils.Qualities.P480.value, "Live 480p"),
                )
            } else {
                listOf(
                    Pair(0, "Auto"),
                    Pair(com.lagradost.cloudstream3.utils.Qualities.P1080.value, "1080p"),
                    Pair(com.lagradost.cloudstream3.utils.Qualities.P720.value, "720p"),
                    Pair(com.lagradost.cloudstream3.utils.Qualities.P480.value, "480p"),
                    Pair(com.lagradost.cloudstream3.utils.Qualities.P360.value, "360p"),
                )
            }
            ytQualities.forEach { (qVal, label) ->
                val qFrag = if (qVal > 0) "#q=$qVal" else "#q=auto"
                sharedLinkCallback(
                    newExtractorLink(
                        source = provider.name,
                        name = "${provider.name} ($label)",
                        url = "$ytUrl$qFrag",
                    ) {
                        this.extractorData = "yt-dlp"
                        this.quality = qVal
                    },
                )
            }
            return Result.success(Unit)
        }

        return SafePluginInvoker.invoke(
            tag = "PlayerStreamScraper:${provider.name}",
            providerName = provider.name,
            timeoutMs = SafePluginInvoker.TIMEOUT_SCRAPE_MS,
            penalizeOnTimeout = false,
        ) {
            AppLogger.i("Plugin:${provider.name}", "Scraping streams for episode: $targetEpisodeId (autoPlay=$autoPlay)")
            provider.loadLinks(
                data = targetEpisodeId,
                isCasting = false,
                subtitleCallback = sharedSubtitleCallback,
                callback = sharedLinkCallback,
            )
        }
    }

    suspend fun attemptSelfHealing(
        provider: MainAPI,
        targetEpisodeId: String,
        showUrl: String,
    ): Pair<String, MovieLoadResponse>? {
        val parentId = DesktopDataStore.watchHistoryId(provider.name, showUrl)
        DesktopDataStore.removeEpisodeWatched(parentId, targetEpisodeId)
        return try {
            val freshResp = SafePluginInvoker.invokeOrNull(
                tag = "SelfHealing:${provider.name}",
                timeoutMs = SafePluginInvoker.TIMEOUT_LOAD_MS,
            ) {
                provider.load(showUrl)
            }
            if (freshResp is MovieLoadResponse && freshResp.dataUrl.isNotBlank() && freshResp.dataUrl != targetEpisodeId) {
                AppLogger.i("Plugin:${provider.name}", "Self-healing resolved valid movie data payload. Retrying scraping...")
                freshResp.dataUrl to freshResp
            } else {
                null
            }
        } catch (healEx: Throwable) {
            AppLogger.e("Plugin:${provider.name}", "Self-healing re-fetch failed: ${healEx.message}")
            null
        }
    }
}
