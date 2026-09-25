package com.lagradost.cloudstream3.desktop.genre

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.desktop.di.AppContainerHolder
import com.lagradost.cloudstream3.desktop.domain.providers.repository.ActiveProviderRepository
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.base.UiEffect
import com.lagradost.cloudstream3.desktop.ui.base.UiEvent
import com.lagradost.cloudstream3.desktop.ui.base.UiState
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.compose.runtime.Immutable
data class GenreBrowseUiState(
    val providers: List<MainAPI> = emptyList(),
    val selectedProvider: MainAPI? = null,
    val mediaType: GenreBrowseMediaType = GenreBrowseMediaType.Movies,
    val genres: List<GenreBrowseOption> = emptyList(),
    val selectedGenre: GenreBrowseOption? = null,
    val selectedTopic: GenreBrowseTopic? = null,
    val sort: GenreBrowseSort = GenreBrowseSort.Popularity,
    val results: List<GenreBrowseResult> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 0,
    val isLoadingGenres: Boolean = false,
    val isLoadingResults: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
) : UiState {
    val canLoadMore: Boolean get() = page > 0 && page < totalPages
}

sealed interface GenreBrowseUiEvent : UiEvent {
    data class SelectProvider(val providerKey: String) : GenreBrowseUiEvent
    data class SelectMediaType(val mediaType: GenreBrowseMediaType) : GenreBrowseUiEvent
    data class SelectGenre(val genre: GenreBrowseOption?) : GenreBrowseUiEvent
    data class SelectTopic(val topic: GenreBrowseTopic?) : GenreBrowseUiEvent
    data class SelectSort(val sort: GenreBrowseSort) : GenreBrowseUiEvent
    data object LoadMore : GenreBrowseUiEvent
    data object RefreshProviders : GenreBrowseUiEvent
    data object Retry : GenreBrowseUiEvent
    data class OpenResult(val result: GenreBrowseResult) : GenreBrowseUiEvent
}

sealed interface GenreBrowseUiEffect : UiEffect {
    data class OpenDetails(
        val providerName: String,
        val url: String,
        val title: String,
        val posterUrl: String?,
    ) : GenreBrowseUiEffect
}

class GenreBrowseViewModel(
    private val activeProviderRepository: ActiveProviderRepository =
        AppContainerHolder.container.activeProviderRepository,
) : BaseMviViewModel<GenreBrowseUiState, GenreBrowseUiEvent, GenreBrowseUiEffect>(
    initialState = GenreBrowseUiState(),
) {
    private var genreJob: Job? = null
    private var resultsJob: Job? = null

    init {
        refreshProviderChoices()
        viewModelScope.launch {
            activeProviderRepository.allRealProviders.collectLatest {
                refreshProviderChoices()
            }
        }

        viewModelScope.launch {
            activeProviderRepository.currentSelectedProvider.collectLatest { provider ->
                if (provider != null && supportsGenreBrowser(provider)) {
                    refreshProviderChoices(provider)
                    if (uiState.value.selectedProvider?.let(::providerKey) != providerKey(provider)) {
                        selectProvider(provider)
                    }
                }
            }
        }
    }

    override fun handleEvent(event: GenreBrowseUiEvent) {
        when (event) {
            is GenreBrowseUiEvent.SelectProvider -> {
                uiState.value.providers.firstOrNull { providerKey(it) == event.providerKey }?.let(::selectProvider)
            }
            is GenreBrowseUiEvent.SelectMediaType -> selectMediaType(event.mediaType)
            is GenreBrowseUiEvent.SelectGenre -> {
                if (event.genre?.id != uiState.value.selectedGenre?.id) {
                    updateState { copy(selectedGenre = event.genre) }
                    loadPage(1)
                }
            }
            is GenreBrowseUiEvent.SelectTopic -> {
                if (event.topic != uiState.value.selectedTopic) {
                    updateState { copy(selectedTopic = event.topic) }
                    loadPage(1)
                }
            }
            is GenreBrowseUiEvent.SelectSort -> {
                if (event.sort != uiState.value.sort) {
                    updateState { copy(sort = event.sort) }
                    loadPage(1)
                }
            }
            GenreBrowseUiEvent.LoadMore -> {
                val state = uiState.value
                if (!state.isLoadingResults && !state.isLoadingMore && state.canLoadMore) {
                    loadPage(state.page + 1)
                }
            }
            GenreBrowseUiEvent.RefreshProviders -> refreshProviderChoices()
            GenreBrowseUiEvent.Retry -> {
                val state = uiState.value
                if (state.genres.isEmpty()) loadGenres(state.mediaType) else loadPage(1)
            }
            is GenreBrowseUiEvent.OpenResult -> {
                val provider = uiState.value.selectedProvider ?: return
                sendEffect(
                    GenreBrowseUiEffect.OpenDetails(
                        providerName = provider.name,
                        url = event.result.pluginLoadUrl,
                        title = event.result.title,
                        posterUrl = event.result.posterUrl,
                    )
                )
            }
        }
    }

    private fun selectProvider(provider: MainAPI) {
        genreJob?.cancel()
        resultsJob?.cancel()
        updateState {
            copy(
                selectedProvider = provider,
                genres = emptyList(),
                selectedGenre = null,
                selectedTopic = null,
                results = emptyList(),
                page = 0,
                totalPages = 0,
                isLoadingGenres = true,
                isLoadingResults = false,
                isLoadingMore = false,
                error = null,
            )
        }
        loadGenres(uiState.value.mediaType)
    }

    private fun selectMediaType(mediaType: GenreBrowseMediaType) {
        if (mediaType == uiState.value.mediaType) return
        genreJob?.cancel()
        resultsJob?.cancel()
        updateState {
            copy(
                mediaType = mediaType,
                genres = emptyList(),
                selectedGenre = null,
                selectedTopic = null,
                results = emptyList(),
                page = 0,
                totalPages = 0,
                isLoadingGenres = selectedProvider != null,
                isLoadingResults = false,
                isLoadingMore = false,
                error = null,
            )
        }
        if (uiState.value.selectedProvider != null) loadGenres(mediaType)
    }

    private fun loadGenres(mediaType: GenreBrowseMediaType) {
        genreJob?.cancel()
        val providerKeyAtRequest = uiState.value.selectedProvider?.let(::providerKey) ?: return
        genreJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val genres = GenreBrowseClient.fetchGenres(mediaType)
                withContext(Dispatchers.Main.immediate) {
                    if (uiState.value.mediaType != mediaType ||
                        uiState.value.selectedProvider?.let(::providerKey) != providerKeyAtRequest
                    ) return@withContext
                    updateState { copy(genres = genres, isLoadingGenres = false, error = null) }
                    loadPage(1)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLogger.e(TAG, "Failed to load $mediaType genres: ${e.message}")
                withContext(Dispatchers.Main.immediate) {
                    if (uiState.value.mediaType != mediaType ||
                        uiState.value.selectedProvider?.let(::providerKey) != providerKeyAtRequest
                    ) return@withContext
                    updateState { copy(isLoadingGenres = false, isLoadingResults = false, error = "Could not load genres from TMDB.") }
                }
            }
        }
    }

    private fun loadPage(page: Int) {
        val state = uiState.value
        if (state.selectedProvider == null) return
        resultsJob?.cancel()
        updateState {
            copy(
                isLoadingResults = page == 1,
                isLoadingMore = page > 1,
                results = if (page == 1) emptyList() else results,
                error = null,
            )
        }

        val providerKeyAtRequest = providerKey(state.selectedProvider)
        val mediaTypeAtRequest = state.mediaType
        val genreIdAtRequest = state.selectedGenre?.id
        val topicAtRequest = state.selectedTopic
        val sortAtRequest = state.sort
        resultsJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val resultPage = GenreBrowseClient.fetchPage(
                    mediaTypeAtRequest,
                    genreIdAtRequest,
                    topicAtRequest,
                    sortAtRequest,
                    page,
                )
                withContext(Dispatchers.Main.immediate) {
                    if (providerKey(uiState.value.selectedProvider ?: return@withContext) != providerKeyAtRequest ||
                        uiState.value.mediaType != mediaTypeAtRequest ||
                        uiState.value.selectedGenre?.id != genreIdAtRequest ||
                        uiState.value.selectedTopic != topicAtRequest ||
                        uiState.value.sort != sortAtRequest
                    ) return@withContext

                    updateState {
                        copy(
                            results = if (page == 1) resultPage.results
                            else (results + resultPage.results).distinctBy { "${it.mediaType.tmdbPath}:${it.id}" },
                            page = resultPage.page,
                            totalPages = resultPage.totalPages,
                            isLoadingResults = false,
                            isLoadingMore = false,
                            error = null,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLogger.e(TAG, "Failed to load StreamPlay genre page $page: ${e.message}")
                withContext(Dispatchers.Main.immediate) {
                    if (uiState.value.selectedProvider?.let(::providerKey) != providerKeyAtRequest ||
                        uiState.value.mediaType != mediaTypeAtRequest ||
                        uiState.value.selectedGenre?.id != genreIdAtRequest ||
                        uiState.value.selectedTopic != topicAtRequest ||
                        uiState.value.sort != sortAtRequest
                    ) return@withContext
                    val message = e.message?.takeIf { it.startsWith("No TMDB keywords are available") }
                        ?: "Could not load titles from TMDB. Check your connection and try again."
                    updateState {
                        copy(
                            isLoadingResults = false,
                            isLoadingMore = false,
                            error = message,
                        )
                    }
                }
            }
        }
    }

    private fun supportsGenreBrowser(api: MainAPI): Boolean {
        val identity = "${api.name} ${api.javaClass.simpleName} ${api.sourcePlugin.orEmpty()}"
        return identity.contains("streamplay", ignoreCase = true) &&
            !identity.contains("anime", ignoreCase = true)
    }

    private fun refreshProviderChoices(preferred: MainAPI? = activeProviderRepository.currentSelectedProvider.value) {
        val loaded = synchronized(APIHolder.allProviders) { APIHolder.allProviders.toList() }
        val supported = (loaded + listOfNotNull(preferred?.takeIf(::supportsGenreBrowser)))
            .filter(::supportsGenreBrowser)
            .distinctBy(::providerKey)
            .sortedWith(compareBy<MainAPI> { if (it.name.equals("StreamPlay", ignoreCase = true)) 0 else 1 }.thenBy { it.name })

        updateState { copy(providers = supported) }
        val selected = preferred?.takeIf(::supportsGenreBrowser) ?: supported.firstOrNull()
        if (selected != null && uiState.value.selectedProvider?.let(::providerKey) != providerKey(selected)) {
            selectProvider(selected)
        }
    }

    private fun providerKey(api: MainAPI): String = "${api.sourcePlugin.orEmpty()}::${api.name}"

    private companion object {
        const val TAG = "GenreBrowse"
    }
}
