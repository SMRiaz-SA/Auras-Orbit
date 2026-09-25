package com.lagradost.cloudstream3.desktop.providerbrowse

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.desktop.di.AppContainerHolder
import com.lagradost.cloudstream3.desktop.domain.providers.repository.ActiveProviderRepository
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.base.UiEffect
import com.lagradost.cloudstream3.desktop.ui.base.UiEvent
import com.lagradost.cloudstream3.desktop.ui.base.UiState
import com.lagradost.cloudstream3.desktop.ui.screens.CategoryGridCache
import com.lagradost.common.logging.AppLogger
import com.lagradost.runtime.executor.SafePluginInvoker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class ProviderBrowseCatalog(
    val provider: MainAPI,
    val page: MainPageData,
) {
    val key: String get() = "${provider.sourcePlugin.orEmpty()}::${provider.name}::${page.data}::${page.name}"
}

@androidx.compose.runtime.Immutable
data class ProviderBrowseUiState(
    val catalogs: List<ProviderBrowseCatalog> = emptyList(),
    val streamPlaySources: List<String> = emptyList(),
    val isLoadingSources: Boolean = true,
    val loadingCatalogKey: String? = null,
    val error: String? = null,
) : UiState

sealed interface ProviderBrowseUiEvent : UiEvent {
    data object Refresh : ProviderBrowseUiEvent
    data class OpenCatalog(val catalog: ProviderBrowseCatalog) : ProviderBrowseUiEvent
}

sealed interface ProviderBrowseUiEffect : UiEffect {
    data class OpenCatalogGrid(val providerName: String, val title: String) : ProviderBrowseUiEffect
}

class ProviderBrowseViewModel(
    private val activeProviderRepository: ActiveProviderRepository =
        AppContainerHolder.container.activeProviderRepository,
) : BaseMviViewModel<ProviderBrowseUiState, ProviderBrowseUiEvent, ProviderBrowseUiEffect>(
    initialState = ProviderBrowseUiState(),
) {
    private var sourceJob: Job? = null
    private var catalogJob: Job? = null

    init {
        refreshCatalogs()
        viewModelScope.launch {
            activeProviderRepository.allRealProviders.collectLatest { refreshCatalogs() }
        }
    }

    override fun handleEvent(event: ProviderBrowseUiEvent) {
        when (event) {
            ProviderBrowseUiEvent.Refresh -> refreshCatalogs()
            is ProviderBrowseUiEvent.OpenCatalog -> openCatalog(event.catalog)
        }
    }

    private fun refreshCatalogs() {
        val providers = synchronized(APIHolder.allProviders) { APIHolder.allProviders.toList() }
            .plus(activeProviderRepository.allRealProviders.value)
            .filter(::supportsStreamPlayCatalogs)
            .distinctBy { "${it.sourcePlugin.orEmpty()}::${it.name}::${it.mainUrl}" }

        sourceJob?.cancel()
        updateState {
            copy(
                streamPlaySources = providers.map { it.name }.distinct(),
                isLoadingSources = true,
                error = null,
            )
        }

        sourceJob = viewModelScope.launch(Dispatchers.IO) {
            val catalogs = buildList {
                providers.forEach { provider ->
                    val result = SafePluginInvoker.invoke(
                        tag = "ProviderBrowse:${provider.name}:mainPage",
                        timeoutMs = SafePluginInvoker.TIMEOUT_LOAD_MS,
                    ) { provider.mainPage }
                    if (result.isSuccess) {
                        result.getOrNull().orEmpty()
                            .filter { it.name.isNotBlank() }
                            .forEach { add(ProviderBrowseCatalog(provider, it)) }
                    } else {
                        val failure = result.exceptionOrNull()
                        if (failure is CancellationException) throw failure
                        AppLogger.w(TAG, "Could not read ${provider.name} provider catalogs: ${failure?.message}")
                    }
                }
            }.distinctBy(ProviderBrowseCatalog::key)

            updateState { copy(catalogs = catalogs, isLoadingSources = false) }
        }
    }

    private fun openCatalog(catalog: ProviderBrowseCatalog) {
        if (uiState.value.loadingCatalogKey != null) return
        catalogJob?.cancel()
        updateState { copy(loadingCatalogKey = catalog.key, error = null) }

        catalogJob = viewModelScope.launch(Dispatchers.IO) {
            val request = MainPageRequest(catalog.page.name, catalog.page.data, catalog.page.horizontalImages)
            val result = SafePluginInvoker.invoke(
                tag = "ProviderBrowse:${catalog.provider.name}:${catalog.page.name}",
                timeoutMs = SafePluginInvoker.TIMEOUT_LOAD_MS,
            ) { catalog.provider.getMainPage(1, request) }

            if (!result.isSuccess) {
                val failure = result.exceptionOrNull()
                if (failure is CancellationException) throw failure
                updateState {
                    copy(
                        loadingCatalogKey = null,
                        error = failure?.localizedMessage ?: "Could not load this provider catalog. Try again.",
                    )
                }
                return@launch
            }

            val response = result.getOrNull()
            val section = response?.items?.firstOrNull {
                it.name.equals(catalog.page.name, ignoreCase = true)
            } ?: response?.items?.firstOrNull {
                it.name.contains(catalog.page.name, ignoreCase = true) ||
                    catalog.page.name.contains(it.name, ignoreCase = true)
            } ?: response?.items?.singleOrNull()
            if (response == null || section == null || section.list.isEmpty()) {
                updateState {
                    copy(
                        loadingCatalogKey = null,
                        error = "StreamPlay returned no items for ${catalog.page.name}.",
                    )
                }
                return@launch
            }

            CategoryGridCache.putHomeCategory(
                providerName = catalog.provider.name,
                title = catalog.page.name,
                items = section.list,
                request = request,
                hasNext = response.hasNext,
                sectionName = section.name,
            )
            updateState { copy(loadingCatalogKey = null, error = null) }
            sendEffect(ProviderBrowseUiEffect.OpenCatalogGrid(catalog.provider.name, catalog.page.name))
        }
    }

    private companion object {
        const val TAG = "ProviderBrowse"
    }
}

internal fun supportsStreamPlayCatalogs(api: MainAPI): Boolean {
    val identity = "${api.name} ${api.javaClass.simpleName} ${api.sourcePlugin.orEmpty()}"
    return identity.contains("streamplay", ignoreCase = true) &&
        !identity.contains("anime", ignoreCase = true)
}
