package com.lagradost.cloudstream3.ui.explore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.observe
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.MainActivity.Companion.bookmarksUpdatedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Provider-backed catalog browsing with responsive phone and TV layouts. */
class AurasCatalogsFragment : Fragment() {
    private val homeViewModel: HomeViewModel by activityViewModels()
    private var browseState by mutableStateOf(AurasBrowseState())
    private var sourcePickerVisible by mutableStateOf(false)
    private var watchlistState by mutableStateOf(AurasWatchlistLoadState())
    private var watchlistLoadJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AurasMobileTheme {
                AurasCatalogsScreen(
                    isTv = isLayout(TV),
                    state = browseState,
                    watchlistState = watchlistState,
                    sourcePickerVisible = sourcePickerVisible,
                    onBack = { findNavController().navigateUp() },
                    onSourceClick = { sourcePickerVisible = true },
                    onSourceDismiss = { sourcePickerVisible = false },
                    onSourceSelect = ::selectSource,
                    onRetrySource = ::retrySource,
                    onConnectSource = { openDestination(R.id.navigation_settings_extensions) },
                    onHelpClick = { openDestination(R.id.navigation_auras_help) },
                    onOpenTitle = ::openTitle,
                    onResumeTitle = ::openResumeTitle,
                    onExpand = ::openCatalogList,
                    onRetryWatchlist = ::refreshWatchlists,
                    onViewContinueWatching = { openDestination(R.id.navigation_library) },
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeHomeData()
        loadSourceOptions()
        homeViewModel.reloadStored()
        refreshWatchlists()
        homeViewModel.loadAndCancel(
            DataStoreHelper.currentHomePage ?: APIRepository.noneApi.name,
            forceReload = false,
        )
    }

    override fun onStart() {
        super.onStart()
        afterPluginsLoadedEvent += ::refreshSourceOptions
        bookmarksUpdatedEvent += ::bookmarksUpdated
        loadSourceOptions()
    }

    override fun onStop() {
        afterPluginsLoadedEvent -= ::refreshSourceOptions
        bookmarksUpdatedEvent -= ::bookmarksUpdated
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshWatchlists()
    }

    override fun onDestroyView() {
        watchlistLoadJob?.cancel()
        watchlistLoadJob = null
        super.onDestroyView()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun refreshSourceOptions(forceReload: Boolean) {
        activity?.runOnUiThread {
            if (isAdded) loadSourceOptions()
        }
    }

    private fun observeHomeData() {
        homeViewModel.page.observe(viewLifecycleOwner) { resource ->
            browseState = when (resource) {
                is Resource.Success -> browseState.copy(
                    shelves = resource.value.values.map { expandable ->
                        expandable.list.let { page ->
                            AurasShelf(
                                name = page.name,
                                items = page.list,
                                hasNext = expandable.hasNext,
                                isLoadingMore = expandable.isLoadingMore,
                                pageError = expandable.pageError,
                            )
                        }
                    },
                    loading = false,
                    error = null,
                )

                is Resource.Loading -> browseState.copy(loading = true, error = null)
                is Resource.Failure -> browseState.copy(
                    loading = false,
                    error = resource.errorString,
                )
            }
        }
        homeViewModel.apiName.observe(viewLifecycleOwner) { name ->
            browseState = browseState.copy(sourceName = name)
        }
        homeViewModel.resumeWatching.observe(viewLifecycleOwner) { items ->
            browseState = browseState.copy(resumeItems = items.orEmpty())
        }
    }

    private fun loadSourceOptions() {
        val context = context ?: return
        val sources = context
            .filterProviderByPreferredMedia()
            .map { AurasSourceOption(it.name, it.name) }
            .toMutableList()
        if (sources.isNotEmpty()) {
            sources.add(0, AurasSourceOption(APIRepository.randomApi.name, context.getString(R.string.auras_surprise_me)))
        }
        browseState = browseState.copy(
            sources = sources,
            sourceName = homeViewModel.apiName.value ?: DataStoreHelper.currentHomePage,
        )
    }

    private fun selectSource(option: AurasSourceOption) {
        sourcePickerVisible = false
        browseState = browseState.copy(
            sourceName = option.key,
            shelves = emptyList(),
            loading = true,
            error = null,
        )
        homeViewModel.loadAndCancel(option.key, forceReload = true, fromUI = true)
    }

    private fun retrySource() {
        val sourceName = browseState.sourceName ?: homeViewModel.apiName.value
            ?: DataStoreHelper.currentHomePage ?: return
        browseState = browseState.copy(loading = true, error = null)
        homeViewModel.loadAndCancel(sourceName, forceReload = true)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun bookmarksUpdated(unused: Boolean) = refreshWatchlists()

    private fun refreshWatchlists() {
        if (!isAdded || view == null) return
        val account = DataStoreHelper.currentAccount
        val accountGeneration = DataStoreHelper.selectedAccountNumberFlow.value
        watchlistLoadJob?.cancel()
        watchlistState = watchlistState.copy(items = emptyList(), loading = true, error = null)
        watchlistLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    val dataById = DataStoreHelper.getAllBookmarkedData()
                        .mapNotNull { data -> data.id?.let { it to data } }
                        .toMap()
                    DataStoreHelper.getAllWatchStateIds().orEmpty().mapNotNull { id ->
                        val status = DataStoreHelper.getResultWatchState(id)
                        val data = dataById[id]
                        if (status == WatchType.NONE || data == null) null
                        else AurasLocalWatchlistEntry(status, data, data.latestUpdatedTime)
                    }.sortedByDescending { it.latestUpdatedTime }
                }
                if (account != DataStoreHelper.currentAccount ||
                    accountGeneration != DataStoreHelper.selectedAccountNumberFlow.value
                ) {
                    refreshWatchlists()
                    return@launch
                }
                watchlistState = AurasWatchlistLoadState(items = items, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (account == DataStoreHelper.currentAccount &&
                    accountGeneration == DataStoreHelper.selectedAccountNumberFlow.value
                ) {
                    watchlistState = AurasWatchlistLoadState(
                        loading = false,
                        error = error.localizedMessage,
                    )
                } else {
                    refreshWatchlists()
                }
            }
        }
    }

    private fun openTitle(item: SearchResponse, index: Int) {
        openItem(item, index, SEARCH_ACTION_LOAD)
    }

    private fun openResumeTitle(item: SearchResponse, index: Int) {
        openItem(item, index, SEARCH_ACTION_PLAY_FILE)
    }

    private fun openItem(item: SearchResponse, index: Int, action: Int) {
        val view = view ?: return
        SearchHelper.handleSearchClickCallback(
            SearchClickCallback(action, view, index, item)
        )
    }

    private fun openDestination(destination: Int) {
        if (findNavController().currentDestination?.id != destination) {
            findNavController().navigate(destination)
        }
    }


    private fun openCatalogList(shelfName: String) {
        val args = Bundle().apply {
            putString(AURAS_CATALOG_ARGUMENT_SHELF, shelfName)
            putString(AURAS_CATALOG_ARGUMENT_SOURCE, browseState.sourceName)
        }
        findNavController().navigate(R.id.navigation_auras_catalog_list, args)
    }
}

@Composable
private fun AurasCatalogsScreen(
    isTv: Boolean,
    state: AurasBrowseState,
    watchlistState: AurasWatchlistLoadState,
    sourcePickerVisible: Boolean,
    onBack: () -> Unit,
    onSourceClick: () -> Unit,
    onSourceDismiss: () -> Unit,
    onSourceSelect: (AurasSourceOption) -> Unit,
    onRetrySource: () -> Unit,
    onConnectSource: () -> Unit,
    onHelpClick: () -> Unit,
    onOpenTitle: (SearchResponse, Int) -> Unit,
    onResumeTitle: (SearchResponse, Int) -> Unit,
    onExpand: (String) -> Unit,
    onRetryWatchlist: () -> Unit,
    onViewContinueWatching: () -> Unit,
) {
    var selectedCatalogFilter by remember { mutableStateOf(AurasCatalogFilter.ALL) }
    var selectedWatchStatus by remember { mutableStateOf(WatchType.WATCHING) }
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val filteredShelves = state.shelves.map { shelf ->
        shelf.copy(
            items = filterAurasCatalogItems(
                items = shelf.items,
                filter = selectedCatalogFilter,
                currentYear = currentYear,
                metadataOf = { it.toAurasCatalogMetadata() },
            ),
        )
    }
    val visibleProviderItems = filteredShelves.sumOf { it.items.size }
    val watchStatusOptions = WatchType.entries.filter { it != WatchType.NONE }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(AurasPalette.Canvas),
    ) {
        val wide = isTv || maxWidth >= 720.dp
        val horizontalPadding = if (wide) 42.dp else 20.dp
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 1120.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = if (wide) 28.dp else 16.dp,
                bottom = 34.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (wide) 25.dp else 20.dp),
        ) {
            item(key = "catalog-back") {
                AurasQuietButton(stringResource(R.string.auras_catalogs_back), onBack)
            }
            item(key = "catalog-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    AurasBrandBar(onHelpClick = onHelpClick)
                    AurasPageTitle(
                        title = stringResource(R.string.auras_catalogs_title),
                        subtitle = stringResource(R.string.auras_catalogs_subtitle),
                    )
                }
            }
            state.resumeItems.firstOrNull()?.let { latestResume ->
                item(key = "catalog-resume-hero") {
                    val progress = (latestResume as? DataStoreHelper.ResumeWatchingResult)?.watchPos
                        ?.takeIf { it.duration > 0L }
                        ?.let { (it.position.toFloat() / it.duration.toFloat()).coerceIn(0f, 1f) }
                    AurasResumeHero(
                        item = latestResume,
                        progress = progress,
                        isTv = isTv,
                        onResume = { onResumeTitle(latestResume, 0) },
                    )
                }
            }
            if (state.resumeItems.size > 1) {
                item(key = "catalog-resume-more") {
                    val progressByUrl = state.resumeItems.mapNotNull { item ->
                        val watchPos = (item as? DataStoreHelper.ResumeWatchingResult)?.watchPos
                            ?: return@mapNotNull null
                        if (watchPos.duration <= 0L) return@mapNotNull null
                        item.url to (watchPos.position.toFloat() / watchPos.duration.toFloat()).coerceIn(0f, 1f)
                    }.distinctBy { it.first }.toMap()
                    AurasPosterRail(
                        title = stringResource(R.string.auras_explore_resume_more_title),
                        items = state.resumeItems.drop(1),
                        onOpen = { item, index -> onResumeTitle(item, index + 1) },
                        progressByUrl = progressByUrl,
                        actionLabel = stringResource(R.string.auras_explore_resume_see_all),
                        onAction = onViewContinueWatching,
                        posterWidth = if (isTv) 132.dp else 98.dp,
                    )
                }
            }
            item(key = "catalog-source") {
                AurasSourceRow(
                    sourceName = state.sourceName,
                    hasSources = state.sources.isNotEmpty(),
                    isTv = isTv,
                    onClick = onSourceClick,
                )
            }
            item(key = "catalog-filters") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AurasSectionHeading(stringResource(R.string.auras_catalog_filters_title))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        items(AurasCatalogFilter.entries, key = { it.name }) { filter ->
                            AurasSelectionChip(
                                label = stringResource(filter.labelResource()),
                                selected = selectedCatalogFilter == filter,
                                onClick = { selectedCatalogFilter = filter },
                            )
                        }
                    }
                }
            }
            item(key = "watchlists-heading") {
                AurasSectionHeading(stringResource(R.string.auras_watchlists_title))
            }
            item(key = "watchlists-status-filters") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(watchStatusOptions, key = { it.name }) { status ->
                            val count = if (watchlistState.loading) "…"
                            else watchlistState.items.count { it.status == status }.toString()
                        AurasSelectionChip(
                                label = "${stringResource(status.stringRes)} · $count",
                            selected = selectedWatchStatus == status,
                            onClick = { selectedWatchStatus = status },
                        )
                    }
                }
            }
            item(key = "watchlists-selected-status") {
                when {
                    watchlistState.loading -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(color = AurasPalette.Accent)
                    }

                    watchlistState.error != null -> Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(
                            text = stringResource(R.string.auras_watchlists_load_error),
                            color = AurasPalette.Ember,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        )
                        AurasQuietButton(stringResource(R.string.auras_watchlists_retry), onRetryWatchlist)
                    }

                    else -> {
                        val selectedItems = filterAurasWatchlistItems(
                            items = watchlistState.items,
                            status = selectedWatchStatus,
                            statusOf = { it.status },
                        )
                        if (selectedItems.isEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.auras_watchlists_empty,
                                    stringResource(selectedWatchStatus.stringRes),
                                ),
                                color = AurasPalette.Muted,
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            AurasWatchlistGrid(
                                items = selectedItems.map { it.item },
                                isTv = isTv,
                                onOpen = onOpenTitle,
                            )
                        }
                    }
                }
            }
            item(key = "provider-catalogs-heading") {
                AurasSectionHeading(stringResource(R.string.auras_provider_catalogs_title))
            }
            if (state.error != null) {
                item(key = "provider-catalogs-error") {
                    AurasEmptyPanel(
                        title = stringResource(
                            if (state.shelves.isEmpty()) R.string.auras_catalogs_unreachable_title
                            else R.string.auras_catalogs_refresh_error_title
                        ),
                        body = state.error,
                        button = stringResource(R.string.auras_catalog_retry),
                        onClick = onRetrySource,
                    )
                }
            } else if (state.sources.isEmpty() && state.shelves.isEmpty() && !state.loading) {
                item(key = "provider-catalogs-empty") {
                    AurasEmptyPanel(
                        title = stringResource(R.string.auras_catalogs_empty_title),
                        body = stringResource(R.string.auras_catalogs_empty_body),
                        button = stringResource(R.string.auras_explore_connect_source),
                        onClick = onConnectSource,
                    )
                }
            } else if (state.loading && state.shelves.isEmpty()) {
                item(key = "provider-catalogs-loading") { AurasLoadingPanelForCatalogs() }
            } else if (state.loading) {
                item(key = "provider-catalogs-refreshing") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = AurasPalette.Accent,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.auras_catalogs_refreshing),
                            color = AurasPalette.Muted,
                        )
                    }
                }
            }
            if (visibleProviderItems == 0 && state.shelves.isNotEmpty() && !state.loading && state.error == null) {
                item(key = "provider-catalogs-no-filter-matches") {
                    Text(
                        text = stringResource(R.string.auras_catalog_filter_empty),
                        color = AurasPalette.Muted,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(
                items = filteredShelves.filter {
                    it.items.isNotEmpty() || it.hasNext || it.pageError != null
                },
                key = { "catalog-${it.name}" },
            ) { shelf ->
                if (shelf.items.isEmpty() && selectedCatalogFilter != AurasCatalogFilter.ALL && shelf.hasNext) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        AurasSectionHeading(
                            title = shelf.name,
                            actionLabel = stringResource(
                                when {
                                    shelf.isLoadingMore -> R.string.auras_catalog_loading_more
                                    shelf.pageError != null -> R.string.auras_catalog_retry
                                    else -> R.string.auras_catalog_more
                                },
                            ),
                            onAction = if (shelf.isLoadingMore) null else ({ onExpand(shelf.name) }),
                        )
                        Text(
                            text = stringResource(R.string.auras_catalog_filter_no_matches_loaded),
                            color = AurasPalette.Muted,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    AurasCatalogShelf(
                        shelf = shelf,
                        onOpen = onOpenTitle,
                        onExpand = if (shelf.hasNext || shelf.items.size > AURAS_POSTER_RAIL_LIMIT) ({ onExpand(shelf.name) }) else null,
                        posterWidth = if (isTv) 132.dp else 98.dp,
                    )
                }
            }
        }
        AurasSourcePicker(
            visible = sourcePickerVisible,
            selectedKey = state.sourceName,
            options = state.sources,
            onDismiss = onSourceDismiss,
            onSelect = onSourceSelect,
            onConnectSource = onConnectSource,
        )
    }
}

@Composable
private fun AurasWatchlistGrid(
    items: List<SearchResponse>,
    isTv: Boolean,
    onOpen: (SearchResponse, Int) -> Unit,
) {
    val minimumPosterWidth = if (isTv) 132.dp else 98.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minimumPosterWidth),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = if (isTv) 540.dp else 380.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        gridItemsIndexed(
            items = items,
            key = { _, item -> item.id?.let { "${item.apiName}:$it" } ?: "${item.apiName}:${item.url}" },
        ) { index, item ->
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                AurasPosterCard(
                    item = item,
                    width = maxWidth,
                    onClick = { onOpen(item, index) },
                )
            }
        }
    }
}

private fun AurasCatalogFilter.labelResource(): Int = when (this) {
    AurasCatalogFilter.ALL -> R.string.auras_catalog_filter_all
    AurasCatalogFilter.MOVIES -> R.string.auras_catalog_filter_movies
    AurasCatalogFilter.SERIES -> R.string.auras_catalog_filter_series
    AurasCatalogFilter.ANIME -> R.string.auras_catalog_filter_anime
    AurasCatalogFilter.NEW -> R.string.auras_catalog_filter_new
}

@Composable
private fun AurasLoadingPanelForCatalogs() {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        repeat(3) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.layout.Spacer(
                    Modifier
                        .fillMaxWidth(0.42f)
                        .padding(vertical = 8.dp)
                        .background(AurasPalette.SurfaceRaised, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                )
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    items(3) {
                        androidx.compose.foundation.layout.Spacer(
                            Modifier
                                .size(width = 132.dp, height = 192.dp)
                                .background(AurasPalette.Surface, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                        )
                    }
                }
            }
        }
    }
}
