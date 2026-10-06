package com.lagradost.cloudstream3.desktop.ui.screens.details

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.desktop.core.preference.PreferenceKeys
import com.lagradost.cloudstream3.desktop.di.AppContainerHolder
import com.lagradost.cloudstream3.desktop.domain.bookmarks.interactor.GetBookmarks
import com.lagradost.cloudstream3.desktop.domain.bookmarks.repository.BookmarksRepository
import com.lagradost.cloudstream3.desktop.domain.customlists.repository.CustomListsRepository
import com.lagradost.cloudstream3.desktop.domain.history.interactor.GetWatchHistory
import com.lagradost.cloudstream3.desktop.domain.history.interactor.RemoveWatchHistory
import com.lagradost.cloudstream3.desktop.domain.history.interactor.UpsertWatchHistory
import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiState
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.EpisodeWatchMark
import com.lagradost.common.storage.FollowedShow
import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class DetailsViewModel(
    val provider: MainAPI,
    val url: String,
    val preloadedName: String? = null,
    val preloadedPoster: String? = null,
    val preloadedBg: String? = null,
    val initialSeason: Int? = null,
    val targetEpisodeId: String? = null,
    val targetEpisode: Int? = null,
    val playNextEpisode: Boolean = false,
    cachedResponse: LoadResponse? = DetailsCache.get(url),
    cachedUiState: DetailsUiState? = EnrichedDetailsCache.get(url),
    private val getWatchHistory: GetWatchHistory = AppContainerHolder.container.getWatchHistory,
    private val upsertWatchHistory: UpsertWatchHistory = AppContainerHolder.container.upsertWatchHistory,
    private val removeWatchHistory: RemoveWatchHistory = AppContainerHolder.container.removeWatchHistory,
    private val getBookmarks: GetBookmarks = AppContainerHolder.container.getBookmarks,
    private val bookmarksRepository: BookmarksRepository = AppContainerHolder.container.bookmarksRepository,
    private val customListsRepository: CustomListsRepository = AppContainerHolder.container.customListsRepository,
) : BaseMviViewModel<DetailsUiState, DetailsUiEvent, DetailsUiEffect>(
    initialState = cachedUiState?.copy(
        fetchFailed = false,
        error = null,
        selectedSeason = initialSeason ?: cachedUiState.selectedSeason,
    ) ?: DetailsUiState(
        preloadedName = preloadedName,
        response = cachedResponse,
        selectedSeason = initialSeason,
        enrichedLogoUrl = cachedResponse?.logoUrl,
        enrichedBackdropUrl = cachedResponse?.backgroundPosterUrl,
        isLoading = cachedResponse == null,
        fakeData = if (cachedResponse == null) {
            @Suppress("DEPRECATION_ERROR", "DEPRECATION")
            MovieLoadResponse(
                name = preloadedName ?: "",
                url = url,
                apiName = provider.name,
                type = TvType.Movie,
                dataUrl = url,
                posterUrl = preloadedPoster,
            ).apply {
                this.backgroundPosterUrl = preloadedBg
            }
        } else {
            null
        },
    ),
) {

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val autoPlay = DesktopDataStore.getKey<Boolean>(com.lagradost.cloudstream3.desktop.player.PlayerConfig.PREF_AUTO_PLAY) ?: true
            val isStacked = DesktopDataStore.getKey<Boolean>(PreferenceKeys.PREF_EPISODES_STACKED_VIEW) ?: false
            val viewMode = DesktopDataStore.getKey<Int>(PreferenceKeys.PREF_EPISODES_VIEW_MODE) ?: if (isStacked) 1 else 0
            updateState {
                copy(
                    autoPlayEnabled = autoPlay,
                    isEpisodesStackedView = isStacked,
                    episodeViewMode = viewMode,
                )
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            getWatchHistory.subscribeAll().collect {
                val currentDataUrl = uiState.value.response?.url ?: url
                val currentParentId = DesktopDataStore.watchHistoryId(provider.name, currentDataUrl)
                val fallbackParentId = DesktopDataStore.watchHistoryId(provider.name, url)

                val historyMap = (
                    getWatchHistory.awaitByParent(currentParentId) +
                        getWatchHistory.awaitByParent(fallbackParentId)
                    )
                    .distinctBy { it.episodeId }
                    .associateBy { it.episodeId ?: "" }
                val latestSeason = historyMap.values.maxByOrNull { it.updateTime }?.season
                updateState {
                    copy(
                        watchHistory = historyMap,
                        selectedSeason = selectedSeason ?: latestSeason,
                    )
                }
            }
        }
        viewModelScope.launch {
            getBookmarks.subscribeAll().collect { bookmarks ->
                updateState { copy(bookmarks = bookmarks) }
            }
        }
        viewModelScope.launch {
            customListsRepository.subscribeActive().collect { snapshot ->
                updateState {
                    copy(
                        customLists = snapshot.lists,
                        customListItems = snapshot.items,
                    )
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.flow.combine(
                DesktopDataStore.episodeTrackingUpdates,
                ProfileManager.activeProfile,
            ) { _, profile -> profile.id }
                .distinctUntilChanged()
                .collect { refreshEpisodeTrackingState(uiState.value.response?.url ?: url) }
        }
    }

    override fun handleEvent(event: DetailsUiEvent) {
        when (event) {
            is DetailsUiEvent.OnLoad -> load()
            is DetailsUiEvent.OnRetry -> retry()
            is DetailsUiEvent.OnOpenLinksPanel -> openLinksPanel(event.data)
            is DetailsUiEvent.OnCloseLinksPanel -> closeLinksPanel()
            is DetailsUiEvent.OnRequestAutoPlay -> handleAutoPlay()
            is DetailsUiEvent.OnMarkAutoPlayHandled -> updateState { copy(hasAutoPlayed = true) }
            is DetailsUiEvent.OnPlayEpisode -> handlePlayEpisode(event.ep)
            is DetailsUiEvent.OnDownloadEpisode -> handleDownloadEpisode(event.ep)
            is DetailsUiEvent.OnToggleEpisodeWatched -> handleToggleEpisodeWatched(event.ep, event.isWatched)
            is DetailsUiEvent.OnRemoveEpisodeWatched -> handleRemoveEpisodeWatched(event.ep)
            is DetailsUiEvent.OnToggleSeasonWatched -> handleToggleSeasonWatched(event.episodes, event.isWatched)
            DetailsUiEvent.OnToggleScheduleFollow -> toggleScheduleFollow()
            is DetailsUiEvent.OnToggleEpisodesStackedView -> handleToggleEpisodesStackedView(event.isStacked)
            is DetailsUiEvent.OnSetEpisodeViewMode -> handleSetEpisodeViewMode(event.viewMode)
            is DetailsUiEvent.OnRefresh -> refresh()
            is DetailsUiEvent.OnAddBookmark -> {
                val profileId = com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId
                viewModelScope.launch(Dispatchers.IO) {
                    bookmarksRepository.addBookmark(event.bookmark, profileId)
                }
            }
            is DetailsUiEvent.OnRemoveBookmark -> {
                val profileId = com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId
                viewModelScope.launch(Dispatchers.IO) {
                    bookmarksRepository.removeBookmark(event.id, profileId)
                }
            }
            is DetailsUiEvent.OnSetBookmarkInCustomList -> {
                val profileId = ProfileManager.activeProfileId
                viewModelScope.launch(Dispatchers.IO) {
                    customListsRepository.setBookmark(event.listId, event.bookmarkId, event.included, profileId)
                }
            }
            is DetailsUiEvent.OnSelectSeason -> selectSeason(event.season)
            is DetailsUiEvent.OnShowPlaybackError -> updateState { copy(playbackError = event.message) }
            is DetailsUiEvent.OnDismissPlaybackError -> updateState { copy(playbackError = null) }
            is DetailsUiEvent.OnSelectTrailer -> updateState { copy(activeTrailer = event.trailer) }
            is DetailsUiEvent.OnSetPendingExternalUrl -> updateState { copy(pendingExternalUrl = event.url) }
        }
    }

    private fun load() {
        if (uiState.value.isInitialized) return
        updateState { copy(isInitialized = true) }
        loadDetails()
    }

    private fun loadDetails() {
        viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(fetchFailed = false, isLoading = true, error = null) }

            if (uiState.value.response == null && uiState.value.fakeData == null) {
                val fake = provider.newMovieLoadResponse(
                    name = preloadedName ?: "",
                    url = url,
                    type = TvType.Movie,
                    dataUrl = url,
                ) {
                    this.posterUrl = preloadedPoster
                    this.backgroundPosterUrl = preloadedBg
                }
                updateState { copy(fakeData = fake) }
            }

            GetEnrichedDetailsUseCase(provider, url, preloadedName, preloadedPoster, preloadedBg).collect { update ->
                when (update) {
                    is EnrichmentUpdate.RawData -> {
                        val rawTmdbId = update.response.syncData["tmdb"]?.toIntOrNull()
                        val isSeries = update.response is TvSeriesLoadResponse || update.response is AnimeLoadResponse
                        val latestHistorySeason = uiState.value.watchHistory.values.maxByOrNull { it.updateTime }?.season
                        val availableSeasons = when (val resp = update.response) {
                            is TvSeriesLoadResponse -> resp.episodes.mapNotNull { it.season }.distinct().sorted()
                            is AnimeLoadResponse -> resp.episodes.values.flatten().mapNotNull { it.season }.distinct().sorted()
                            else -> emptyList()
                        }.filter { it > 0 }
                        val firstAvailableSeason = availableSeasons.firstOrNull() ?: 1
                        val queuedTargetSeason = if (playNextEpisode && !targetEpisodeId.isNullOrBlank()) {
                            DetailsWatchCoordinator.determineAutoPlayTarget(
                                provider = provider,
                                resp = update.response,
                                watchHistory = uiState.value.watchHistory,
                                targetEpisodeId = targetEpisodeId,
                                targetSeason = initialSeason,
                                targetEpisode = targetEpisode,
                                playNextEpisode = true,
                            )?.season
                        } else {
                            null
                        }
                        val resolvedSeason = if (isSeries) {
                            (
                                queuedTargetSeason?.takeIf { it in availableSeasons }
                                    ?: uiState.value.selectedSeason?.takeIf { it in availableSeasons }
                                    ?: initialSeason?.takeIf { it in availableSeasons }
                                    ?: latestHistorySeason?.takeIf { it in availableSeasons }
                                    ?: firstAvailableSeason
                                )
                        } else {
                            null
                        }

                        updateState {
                            copy(
                                response = update.response,
                                isLoading = false,
                                fakeData = null,
                                enrichedLogoUrl = update.response.logoUrl,
                                enrichedBackdropUrl = update.response.backgroundPosterUrl,
                                isEnriching = true,
                                enrichmentPhase = com.lagradost.cloudstream3.desktop.ui.screens.details.contract.EnrichmentPhase.InProgress,
                                tmdbId = rawTmdbId ?: tmdbId,
                                selectedSeason = resolvedSeason,
                            )
                        }
                        refreshEpisodeTrackingState(update.response.url)
                        val targetSeason = resolvedSeason ?: uiState.value.selectedSeason
                        val currentTmdb = rawTmdbId ?: uiState.value.tmdbId
                        if (targetSeason != null && targetSeason > 0 && currentTmdb != null) {
                            loadSeasonCredits(targetSeason)
                        }
                    }
                    is EnrichmentUpdate.LogoLoaded -> {
                        updateState {
                            val newState = copy(enrichedLogoUrl = update.url)
                            EnrichedDetailsCache.put(url, newState)
                            newState.response?.url?.let { rUrl -> if (rUrl != url) EnrichedDetailsCache.put(rUrl, newState) }
                            newState
                        }
                    }
                    is EnrichmentUpdate.BackdropLoaded -> {
                        updateState {
                            val newState = copy(enrichedBackdropUrl = update.url)
                            EnrichedDetailsCache.put(url, newState)
                            newState.response?.url?.let { rUrl -> if (rUrl != url) EnrichedDetailsCache.put(rUrl, newState) }
                            newState
                        }
                    }
                    is EnrichmentUpdate.ScreenshotsLoaded -> {
                        updateState { copy(screenshots = update.urls) }
                    }
                    is EnrichmentUpdate.ExtractedColor -> {
                        // Ignored, color extraction removed
                    }
                    is EnrichmentUpdate.ActorsLoaded -> {
                        updateState {
                            val currentActors = enrichedActors
                            val currentHasDualCast = currentActors?.any { it.voiceActor != null } == true
                            val updateHasDualCast = update.actors.any { it.voiceActor != null }
                            val resolvedActors = if (currentHasDualCast && !updateHasDualCast) {
                                currentActors
                            } else {
                                update.actors
                            }
                            val newState = copy(enrichedActors = resolvedActors)
                            EnrichedDetailsCache.put(url, newState)
                            newState.response?.url?.let { rUrl -> if (rUrl != url) EnrichedDetailsCache.put(rUrl, newState) }
                            newState
                        }
                    }
                    is EnrichmentUpdate.TrailersLoaded -> {
                        updateState {
                            val language = com.lagradost.cloudstream3.desktop.metadata.MetadataConfig.tmdbLanguage.value
                            val maxTrailers = com.lagradost.cloudstream3.desktop.metadata.MetadataConfig.maxTrailers.value.coerceIn(3, 50)
                            val mergedTrailers = com.lagradost.cloudstream3.desktop.ui.screens.details.contract.TrailerUtils
                                .mergeAndRank(enrichedTrailers + update.trailers, language, maxTrailers)
                            copy(
                                enrichedTrailers = mergedTrailers,
                                enrichedTrailerUrl = mergedTrailers.firstOrNull()?.url,
                            )
                        }
                    }
                    is EnrichmentUpdate.ReviewsLoaded -> {
                        updateState { copy(enrichedReviews = update.reviews) }
                    }
                    is EnrichmentUpdate.EpisodeThumbnailsEnriched -> {
                        updateState { copy(episodeThumbnailVersion = episodeThumbnailVersion + 1) }
                    }
                    is EnrichmentUpdate.RatingsLoaded -> {
                        updateState {
                            copy(
                                enrichedImdbRating = update.imdb ?: enrichedImdbRating,
                                enrichedTmdbRating = update.tmdb ?: enrichedTmdbRating,
                                enrichedAniListRating = update.anilist ?: enrichedAniListRating,
                            )
                        }
                    }
                    is EnrichmentUpdate.MetadataLoaded -> {
                        updateState {
                            val mergedProdCompanies = if (update.productionCompanies != null) {
                                val current = enrichedProductionCompanies.toMutableList()
                                update.productionCompanies.forEach { newComp ->
                                    val existingIdx = current.indexOfFirst { it.name.trim().equals(newComp.name.trim(), ignoreCase = true) }
                                    if (existingIdx >= 0) {
                                        val existing = current[existingIdx]
                                        if (existing.logoUrl.isNullOrBlank() && !newComp.logoUrl.isNullOrBlank()) {
                                            current[existingIdx] = newComp
                                        }
                                    } else {
                                        current.add(newComp)
                                    }
                                }
                                current
                            } else {
                                enrichedProductionCompanies
                            }

                            val mergedNetCompanies = if (update.networkCompanies != null) {
                                val current = enrichedNetworksList.toMutableList()
                                update.networkCompanies.forEach { newComp ->
                                    val existingIdx = current.indexOfFirst { it.name.trim().equals(newComp.name.trim(), ignoreCase = true) }
                                    if (existingIdx >= 0) {
                                        val existing = current[existingIdx]
                                        if (existing.logoUrl.isNullOrBlank() && !newComp.logoUrl.isNullOrBlank()) {
                                            current[existingIdx] = newComp
                                        }
                                    } else {
                                        current.add(newComp)
                                    }
                                }
                                current
                            } else {
                                enrichedNetworksList
                            }

                            val newState = copy(
                                enrichedTagline = update.tagline ?: enrichedTagline,
                                enrichedStatus = update.status ?: enrichedStatus,
                                enrichedStudios = if (update.studios.isNotEmpty()) update.studios else enrichedStudios,
                                enrichedProductionCompanies = mergedProdCompanies,
                                enrichedNetworksList = mergedNetCompanies,
                                enrichedCollectionName = update.collName ?: enrichedCollectionName,
                                enrichedCollectionBackdrop = update.collBg ?: enrichedCollectionBackdrop,
                                enrichedSeasonsCount = update.seasons ?: enrichedSeasonsCount,
                                enrichedEpisodesCount = update.episodes ?: enrichedEpisodesCount,
                                enrichedSeasonsMetadata = if (!update.seasonsMetadata.isNullOrEmpty()) update.seasonsMetadata else enrichedSeasonsMetadata,
                                enrichedOriginalLanguage = update.lang ?: enrichedOriginalLanguage,
                                enrichedReleaseDate = update.relDate ?: enrichedReleaseDate,
                                enrichedCountry = update.country ?: enrichedCountry,
                                enrichedCollectionItems = if (update.collItems.isNotEmpty()) update.collItems else enrichedCollectionItems,
                                enrichedBudget = update.budget ?: enrichedBudget,
                                enrichedRevenue = update.revenue ?: enrichedRevenue,
                                enrichedNetworks = if (!update.networks.isNullOrEmpty()) update.networks else enrichedNetworks,
                                enrichedYear = update.year ?: enrichedYear,
                                enrichedDuration = update.duration ?: enrichedDuration,
                                enrichedTags = update.tags ?: enrichedTags,
                                enrichedActors = if (enrichedActors?.any { it.voiceActor != null } == true && update.actors?.none { it.voiceActor != null } == true) {
                                    enrichedActors
                                } else {
                                    update.actors ?: enrichedActors
                                },
                            )
                            EnrichedDetailsCache.put(url, newState)
                            newState.response?.url?.let { rUrl -> if (rUrl != url) EnrichedDetailsCache.put(rUrl, newState) }
                            newState
                        }
                    }
                    is EnrichmentUpdate.FullyEnriched -> {
                        updateState {
                            val resolvedTmdbId = tmdbId ?: response?.syncData?.get("tmdb")?.toIntOrNull()
                            val newState = copy(
                                isEnriching = false,
                                enrichmentPhase = com.lagradost.cloudstream3.desktop.ui.screens.details.contract.EnrichmentPhase.Complete,
                                tmdbId = resolvedTmdbId ?: tmdbId,
                            )
                            EnrichedDetailsCache.put(url, newState)
                            newState.response?.url?.let {
                                if (it != url) EnrichedDetailsCache.put(it, newState)
                            }
                            newState
                        }
                        val isSeries = uiState.value.response?.let { it is TvSeriesLoadResponse || it is AnimeLoadResponse } ?: false
                        val currentAvailableSeasons = when (val resp = uiState.value.response) {
                            is TvSeriesLoadResponse -> resp.episodes.mapNotNull { it.season }.distinct().sorted()
                            is AnimeLoadResponse -> resp.episodes.values.flatten().mapNotNull { it.season }.distinct().sorted()
                            else -> emptyList()
                        }.filter { it > 0 }
                        val currentSeason = if (isSeries) {
                            (
                                uiState.value.selectedSeason?.takeIf { it in currentAvailableSeasons }
                                    ?: currentAvailableSeasons.firstOrNull()
                                    ?: 1
                                )
                        } else {
                            null
                        }
                        if (currentSeason != null && currentSeason > 0) {
                            if (uiState.value.selectedSeason != currentSeason) {
                                updateState { copy(selectedSeason = currentSeason) }
                            }
                            loadSeasonCredits(currentSeason)
                        }
                    }
                    is EnrichmentUpdate.Error -> {
                        AppLogger.e("DetailsViewModel", "Error loading details: ${update.message}")
                        updateState {
                            copy(
                                fetchFailed = true,
                                isLoading = false,
                                error = update.message,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun selectSeason(season: Int?) {
        updateState { copy(selectedSeason = season) }
        if (season != null && season > 0) {
            loadSeasonCredits(season)
        }
    }

    private fun loadSeasonCredits(season: Int) {
        val state = uiState.value
        if (season <= 0) return
        if (state.seasonCredits.containsKey(season)) return
        val tmdbId = state.tmdbId ?: state.response?.syncData?.get("tmdb")?.toIntOrNull() ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val credits = TmdbEnrichmentService.fetchSeasonCredits(tmdbId, season)
            if (credits.isNotEmpty()) {
                updateState {
                    copy(
                        seasonCredits = seasonCredits + (season to credits),
                    )
                }
            }
        }
    }

    private fun handleAutoPlay() {
        if (uiState.value.hasAutoPlayed) return
        updateState { copy(hasAutoPlayed = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val resp = uiState.value.response ?: return@launch
            val targetEp = DetailsWatchCoordinator.determineAutoPlayTarget(
                provider = provider,
                resp = resp,
                watchHistory = uiState.value.watchHistory,
                targetEpisodeId = targetEpisodeId,
                targetSeason = initialSeason,
                targetEpisode = targetEpisode,
                playNextEpisode = playNextEpisode,
            )
            if (targetEp != null) {
                val patchedData = DetailsWatchCoordinator.patchEpisodeData(targetEp, resp)
                val history = DetailsWatchCoordinator.buildWatchHistory(provider.name, targetEp, resp)
                handlePlayRequest(Triple(provider, patchedData, history), forceAutoPlay = true)
            }
        }
    }

    private fun handlePlayEpisode(ep: Episode) {
        viewModelScope.launch(Dispatchers.IO) {
            val data = uiState.value.response ?: return@launch
            val patchedData = DetailsWatchCoordinator.patchEpisodeData(ep, data)
            val history = DetailsWatchCoordinator.buildWatchHistory(provider.name, ep, data)
            handlePlayRequest(Triple(provider, patchedData, history))
        }
    }

    private fun handleDownloadEpisode(ep: Episode) {
        val data = uiState.value.response ?: return
        val patchedData = DetailsWatchCoordinator.patchEpisodeData(ep, data)
        val isMovie = data is MovieLoadResponse
        val history = WatchHistory(
            parentId = data.url,
            showName = data.name,
            showUrl = data.url,
            apiName = provider.name,
            posterUrl = ep.posterUrl ?: data.posterUrl,
            episodeThumbnailUrl = ep.posterUrl ?: data.posterUrl,
            screenshotUrl = null,
            episode = if (isMovie) null else ep.episode,
            season = if (isMovie) null else ep.season,
            episodeId = ep.data,
            position = 0L,
            duration = 0L,
            updateTime = System.currentTimeMillis(),
            episodeName = if (isMovie) null else ep.name,
            episodeDescription = ep.description ?: data.plot,
        )
        openLinksPanel(Triple(provider, patchedData, history))
    }

    private fun handleRemoveEpisodeWatched(ep: com.lagradost.cloudstream3.Episode) {
        val data = uiState.value.response ?: return
        val profileId = ProfileManager.activeProfileId
        val updatedMarks = uiState.value.episodeWatchMarks.filterValues { !ep.matchesWatchMark(it) }
        updateState { copy(episodeWatchMarks = updatedMarks) }
        viewModelScope.launch(Dispatchers.IO) {
            DesktopDataStore.setEpisodeWatchMarks(episodeWatchMarksForRemoval(listOf(ep), data, profileId), watched = false)
        }
    }

    private fun handleToggleEpisodeWatched(ep: Episode, isWatched: Boolean) {
        val data = uiState.value.response ?: return
        val profileId = ProfileManager.activeProfileId
        val mark = makeEpisodeWatchMark(ep, data, profileId)
        val updatedMarks = uiState.value.episodeWatchMarks.toMutableMap()
        updatedMarks.entries.removeAll { ep.matchesWatchMark(it.value) }
        if (isWatched) updatedMarks[mark.episodeKey] = mark
        updateState { copy(episodeWatchMarks = updatedMarks) }

        viewModelScope.launch(Dispatchers.IO) {
            val marks = if (isWatched) listOf(mark) else episodeWatchMarksForRemoval(listOf(ep), data, profileId)
            DesktopDataStore.setEpisodeWatchMarks(marks, watched = isWatched)
            if (isWatched) DetailsWatchCoordinator.queueNextEpisode(provider.name, data, ep, profileId)
        }
    }

    private fun handleToggleSeasonWatched(episodes: List<Episode>, isWatched: Boolean) {
        val data = uiState.value.response ?: return
        val profileId = ProfileManager.activeProfileId
        val marks = episodes.map { makeEpisodeWatchMark(it, data, profileId) }
        val updatedMarks = uiState.value.episodeWatchMarks.toMutableMap()
        episodes.forEach { ep -> updatedMarks.entries.removeAll { ep.matchesWatchMark(it.value) } }
        if (isWatched) marks.forEach { updatedMarks[it.episodeKey] = it }
        updateState { copy(episodeWatchMarks = updatedMarks) }
        viewModelScope.launch(Dispatchers.IO) {
            val marksToPersist = if (isWatched) marks else episodeWatchMarksForRemoval(episodes, data, profileId)
            DesktopDataStore.setEpisodeWatchMarks(marksToPersist, watched = isWatched)
            if (isWatched) {
                episodes.lastOrNull()?.let {
                    DetailsWatchCoordinator.queueNextEpisode(provider.name, data, it, profileId)
                }
            }
        }
    }

    private fun episodeWatchMarksForRemoval(episodes: List<Episode>, data: LoadResponse, profileId: Int): List<EpisodeWatchMark> {
        val showUrls = setOf(data.url, url)
        val storedMarks = showUrls.flatMap { showUrl ->
            DesktopDataStore.getEpisodeWatchMarks(profileId, provider.name, showUrl)
        }
        val marksToRemove = episodes.flatMap { episode ->
            val matchingMarks = storedMarks.filter(episode::matchesWatchMark)
            matchingMarks.ifEmpty { listOf(makeEpisodeWatchMark(episode, data, profileId)) }
        }
        return marksToRemove.distinctBy { it.showUrl to it.episodeKey }
    }

    private fun makeEpisodeWatchMark(ep: Episode, data: LoadResponse, profileId: Int): EpisodeWatchMark {
        val season = ep.season ?: 1
        val episodeNumber = ep.episode ?: 0
        val key = if (ep.episode != null) "s$season:e$episodeNumber" else "data:${ep.data}"
        return EpisodeWatchMark(
            profileId = profileId,
            providerName = provider.name,
            showUrl = data.url,
            episodeKey = key,
            episodeId = ep.data,
            showName = data.name,
            seasonNumber = season,
            episodeNumber = episodeNumber,
        )
    }

    private fun refreshEpisodeTrackingState(showUrl: String) {
        val profileId = ProfileManager.activeProfileId
        val currentUrl = uiState.value.response?.url ?: showUrl
        val marks = (
            DesktopDataStore.getEpisodeWatchMarks(profileId, provider.name, currentUrl) +
                DesktopDataStore.getEpisodeWatchMarks(profileId, provider.name, url)
            )
            .distinctBy { it.episodeKey }
            .associateBy { it.episodeKey }
        val follows = DesktopDataStore.getFollowedShows(profileId)
            .any { it.providerName == provider.name && (it.showUrl == currentUrl || it.showUrl == url) }
        updateState {
            if (ProfileManager.activeProfileId != profileId || (response?.url ?: showUrl) != currentUrl) {
                this
            } else {
                copy(episodeWatchMarks = marks, isFollowingSchedule = follows)
            }
        }
    }

    private fun toggleScheduleFollow() {
        val data = uiState.value.response ?: return
        if (data !is TvSeriesLoadResponse && data !is AnimeLoadResponse) return
        val profileId = ProfileManager.activeProfileId
        val existing = DesktopDataStore.getFollowedShows(profileId)
            .firstOrNull { it.providerName == provider.name && (it.showUrl == data.url || it.showUrl == url) }
        if (existing != null) {
            updateState { copy(isFollowingSchedule = false) }
            viewModelScope.launch(Dispatchers.IO) {
                DesktopDataStore.unfollowShow(profileId, existing.providerName, existing.showUrl)
            }
            return
        }

        updateState { copy(isFollowingSchedule = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val tmdbId = uiState.value.tmdbId
                ?: data.syncData["tmdb"]?.toIntOrNull()
                ?: try {
                    TmdbEpisodeCalendarRepository.resolveTmdbId(data)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            val followed = FollowedShow(
                profileId = profileId,
                providerName = provider.name,
                showUrl = data.url,
                showName = data.name,
                posterUrl = data.posterUrl,
                tmdbId = tmdbId,
                lastRefreshError = if (tmdbId == null) "No confident TMDB match was found" else null,
            )
            DesktopDataStore.followShow(followed)
            if (tmdbId != null) {
                try {
                    TmdbEpisodeCalendarRepository.refreshShow(followed)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    DesktopDataStore.updateFollowedShowRefresh(
                        profileId = profileId,
                        providerName = provider.name,
                        showUrl = data.url,
                        refreshedAt = System.currentTimeMillis(),
                        error = failure.localizedMessage ?: "Could not load episode dates",
                    )
                }
            }
        }
    }

    private fun handleToggleEpisodesStackedView(isStacked: Boolean) {
        val viewMode = if (isStacked) 1 else 0
        updateState { copy(isEpisodesStackedView = isStacked, episodeViewMode = viewMode) }
        viewModelScope.launch(Dispatchers.IO) {
            DesktopDataStore.setKey(PreferenceKeys.PREF_EPISODES_STACKED_VIEW, isStacked)
            DesktopDataStore.setKey(PreferenceKeys.PREF_EPISODES_VIEW_MODE, viewMode)
        }
    }

    private fun handleSetEpisodeViewMode(viewMode: Int) {
        val isStacked = viewMode != 0
        updateState { copy(episodeViewMode = viewMode, isEpisodesStackedView = isStacked) }
        viewModelScope.launch(Dispatchers.IO) {
            DesktopDataStore.setKey(PreferenceKeys.PREF_EPISODES_VIEW_MODE, viewMode)
            DesktopDataStore.setKey(PreferenceKeys.PREF_EPISODES_STACKED_VIEW, isStacked)
        }
    }

    private fun handlePlayRequest(data: Triple<MainAPI, String, WatchHistory>, forceAutoPlay: Boolean? = null) {
        val isTorrent = com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine.isTorrentProvider(data.first)
        val isP2pOn = com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine.isP2pEnabled
        val shouldAutoPlay = (forceAutoPlay ?: uiState.value.autoPlayEnabled) && (!isTorrent || isP2pOn)
        if (shouldAutoPlay) {
            val linkHistory = data.third
            val epTitle = buildString {
                append(linkHistory.showName)
                if (linkHistory.season != null && linkHistory.episode != null) {
                    append(" - S${linkHistory.season}E${linkHistory.episode}")
                } else if (linkHistory.episode != null) {
                    append(" - E${linkHistory.episode}")
                }
            }
            val response = uiState.value.response
            val isLive = response?.type == TvType.Live
            val resumeMs = if (isLive) 0L else com.lagradost.player.impl.PlayerLinkHandler.resumeStartSeconds(linkHistory.position, linkHistory.duration) * 1000L

            val currentSeason = linkHistory.season ?: uiState.value.selectedSeason
            val seasonCast = if (currentSeason != null && currentSeason > 0) {
                uiState.value.seasonCredits[currentSeason]
            } else {
                null
            }
            val effectiveActors = seasonCast ?: uiState.value.enrichedActors ?: response?.actors

            sendEffect(
                DetailsUiEffect.NavigateToPlayer(
                    com.lagradost.cloudstream3.desktop.ui.VideoLaunchData(
                        links = emptyList(),
                        initialIndex = 0,
                        title = epTitle,
                        subtitles = emptyList(),
                        startPositionMs = resumeMs,
                        history = linkHistory,
                        loadResponse = response,
                        enrichedLogoUrl = uiState.value.enrichedLogoUrl,
                        enrichedBackdropUrl = uiState.value.enrichedBackdropUrl,
                        enrichedActors = effectiveActors,
                    ),
                ),
            )
        } else {
            handleEvent(DetailsUiEvent.OnOpenLinksPanel(data))
        }
    }

    private fun retry() {
        updateState { copy(fetchFailed = false, isLoading = true) }
        DetailsCache.remove(url)
        loadDetails()
    }

    private fun refresh() {
        DetailsCache.remove(url)
        uiState.value.response?.url?.let { DetailsCache.remove(it) }
        EnrichedDetailsCache.remove(url)
        uiState.value.response?.url?.let { EnrichedDetailsCache.remove(it) }
        val titleToEvict = uiState.value.response?.name ?: uiState.value.preloadedName
        com.lagradost.cloudstream3.desktop.metadata.MetadataPipeline.clearCache(titleToEvict)
        com.lagradost.cloudstream3.desktop.ui.components.AppToastManager.showInfo("Refreshing details...")
        updateState {
            copy(
                isInitialized = true,
                isLoading = true,
                fetchFailed = false,
                error = null,
                response = null,
                fakeData = null,
                isEnriching = false,
                enrichmentPhase = com.lagradost.cloudstream3.desktop.ui.screens.details.contract.EnrichmentPhase.Idle,
                enrichedLogoUrl = null,
                enrichedBackdropUrl = null,
                enrichedTagline = null,
                enrichedStatus = null,
                enrichedStudios = emptyList(),
                enrichedProductionCompanies = emptyList(),
                enrichedNetworksList = emptyList(),
                enrichedCollectionName = null,
                enrichedCollectionBackdrop = null,
                enrichedSeasonsCount = null,
                enrichedEpisodesCount = null,
                enrichedSeasonsMetadata = emptyList(),
                enrichedOriginalLanguage = null,
                enrichedReleaseDate = null,
                enrichedCountry = null,
                enrichedCollectionItems = emptyList(),
                enrichedBudget = null,
                enrichedRevenue = null,
                enrichedNetworks = emptyList(),
                enrichedYear = null,
                enrichedDuration = null,
                enrichedTags = null,
                enrichedActors = null,
                enrichedImdbRating = null,
                enrichedTmdbRating = null,
                enrichedAniListRating = null,
                enrichedReviews = emptyList(),
                enrichedTrailers = emptyList(),
                enrichedTrailerUrl = null,
                screenshots = null,
                tmdbId = null,
                seasonCredits = emptyMap(),
                episodeThumbnailVersion = 0,
            )
        }
        loadDetails()
    }

    private fun openLinksPanel(data: Triple<MainAPI, String, WatchHistory>) {
        updateState { copy(activeLinkData = data, isPanelOpen = true) }
    }

    private fun closeLinksPanel() {
        updateState { copy(isPanelOpen = false) }
    }
}
