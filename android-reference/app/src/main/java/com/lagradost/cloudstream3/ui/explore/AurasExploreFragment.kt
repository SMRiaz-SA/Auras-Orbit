package com.lagradost.cloudstream3.ui.explore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.observe
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.util.Locale

/** Auras-owned mobile discovery screen backed by the existing provider and watch-progress model. */
class AurasExploreFragment : Fragment() {
    private val homeViewModel: HomeViewModel by activityViewModels()
    private var browseState by mutableStateOf(AurasBrowseState())
    private var sourcePickerVisible by mutableStateOf(false)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AurasMobileTheme {
                AurasExploreScreen(
                    state = browseState,
                    sourcePickerVisible = sourcePickerVisible,
                    onSourceClick = { sourcePickerVisible = true },
                    onSourceDismiss = { sourcePickerVisible = false },
                    onSourceSelect = { option -> selectSource(option) },
                    onOpenCatalogs = { openCatalogs() },
                    onOpenSearch = { openDestination(R.id.navigation_search) },
                    onConnectSource = { openDestination(R.id.navigation_settings_extensions) },
                    onHelpClick = { openDestination(R.id.navigation_auras_help) },
                    onOpenTitle = { item, index -> openItem(item, index, SEARCH_ACTION_LOAD) },
                    onResumeTitle = { item, index -> openItem(item, index, SEARCH_ACTION_PLAY_FILE) },
                    onExpand = { name -> homeViewModel.expand(name) },
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeHomeData()
        loadSourceOptions()
        homeViewModel.reloadStored()
        homeViewModel.loadAndCancel(
            DataStoreHelper.currentHomePage ?: APIRepository.noneApi.name,
            forceReload = false,
        )
    }

    override fun onResume() {
        super.onResume()
        homeViewModel.reloadStored()
    }

    private fun observeHomeData() {
        homeViewModel.page.observe(viewLifecycleOwner) { resource ->
            browseState = when (resource) {
                is Resource.Success -> browseState.copy(
                    shelves = resource.value.values.mapNotNull { expandable ->
                        expandable.list.let { page ->
                            if (page.list.isEmpty()) null else AurasShelf(
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
                    shelves = emptyList(),
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
        val sources = requireContext()
            .filterProviderByPreferredMedia()
            .map { AurasSourceOption(it.name, it.name) }
            .toMutableList()
        if (sources.isNotEmpty()) {
            sources.add(0, AurasSourceOption(APIRepository.randomApi.name, getString(R.string.auras_surprise_me)))
        }
        browseState = browseState.copy(
            sources = sources,
            sourceName = homeViewModel.apiName.value ?: DataStoreHelper.currentHomePage,
        )
    }

    private fun selectSource(option: AurasSourceOption) {
        sourcePickerVisible = false
        browseState = browseState.copy(sourceName = option.key, loading = true, error = null)
        homeViewModel.loadAndCancel(option.key, forceReload = true, fromUI = true)
    }

    private fun openItem(item: SearchResponse, index: Int, action: Int) {
        val view = view ?: return
        SearchHelper.handleSearchClickCallback(SearchClickCallback(action, view, index, item))
    }

    private fun openCatalogs() {
        val destination = if (isLayout(PHONE)) {
            R.id.navigation_auras_catalogs
        } else if (isLayout(TV)) {
            R.id.navigation_catalogs
        } else {
            R.id.navigation_auras_catalogs
        }
        openDestination(destination)
    }

    private fun openDestination(destination: Int) {
        if (findNavController().currentDestination?.id != destination) {
            findNavController().navigate(destination)
        }
    }
}

@Composable
private fun AurasExploreScreen(
    state: AurasBrowseState,
    sourcePickerVisible: Boolean,
    onSourceClick: () -> Unit,
    onSourceDismiss: () -> Unit,
    onSourceSelect: (AurasSourceOption) -> Unit,
    onOpenCatalogs: () -> Unit,
    onOpenSearch: () -> Unit,
    onConnectSource: () -> Unit,
    onHelpClick: () -> Unit,
    onOpenTitle: (SearchResponse, Int) -> Unit,
    onResumeTitle: (SearchResponse, Int) -> Unit,
    onExpand: (String) -> Unit,
) {
    val topTenShelf = state.shelves.firstOrNull { it.isExplicitTopTenShelf() }
    val discoveryShelf = topTenShelf ?: state.shelves.firstOrNull { it.isPopularShelf() }
    val remainingShelves = state.shelves.filterNot { it.name == discoveryShelf?.name }
    val resumeProgressByUrl = state.resumeItems.mapNotNull { item ->
        val watchPos = (item as? DataStoreHelper.ResumeWatchingResult)?.watchPos
            ?: return@mapNotNull null
        if (watchPos.duration <= 0L) return@mapNotNull null
        item.url to (watchPos.position.toFloat() / watchPos.duration.toFloat()).coerceIn(0f, 1f)
    }.distinctBy { it.first }.toMap()

    Box(
        Modifier
            .fillMaxSize()
            .background(AurasPalette.Canvas),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item {
                AurasBrandBar(
                    sourceName = state.sourceName,
                    sourceLabel = if (state.sources.isEmpty()) "Add source" else "Source",
                    onHelpClick = onHelpClick,
                    onSourceClick = {
                        if (state.sources.isEmpty()) onConnectSource() else onSourceClick()
                    },
                )
            }
            if (state.resumeItems.isNotEmpty()) {
                item {
                    AurasPosterRail(
                        title = "Pick up where you left off",
                        items = state.resumeItems,
                        onOpen = onResumeTitle,
                        progressByUrl = resumeProgressByUrl,
                    )
                }
            }
            if (state.loading) {
                item { AurasLoadingPanel() }
            } else if (state.shelves.isEmpty() && state.resumeItems.isEmpty()) {
                item {
                    AurasEmptyPanel(
                        title = if (state.error == null) "Your Orbit is waiting" else "We couldn't load this catalog",
                        body = state.error ?: "Connect a source to bring its collections and titles into Auras Orbit.",
                        button = if (state.sources.isEmpty()) "Connect a source" else "Choose a source",
                        onClick = if (state.sources.isEmpty()) onConnectSource else onSourceClick,
                    )
                }
            }
            discoveryShelf?.let { shelf ->
                item(key = "explore-discovery-${shelf.name}") {
                    if (shelf == topTenShelf) {
                        AurasTopTenRail(
                            title = "Top 10 on ${state.sourceName ?: "this source"}",
                            items = shelf.items,
                            onOpen = onOpenTitle,
                            onLoadMore = if (shelf.hasNext) ({ onExpand(shelf.name) }) else null,
                            typeLabelOverride = mediaTypeLabelForShelf(shelf.name),
                        )
                    } else {
                        AurasPosterRail(
                            title = shelf.name,
                            items = shelf.items.take(10),
                            onOpen = onOpenTitle,
                        )
                    }
                }
            }
            remainingShelves.take(4).forEach { shelf ->
                item(key = "explore-${shelf.name}") {
                    AurasCatalogShelf(
                        shelf = shelf,
                        onOpen = onOpenTitle,
                        onExpand = if (shelf.hasNext) ({ onExpand(shelf.name) }) else null,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AurasQuietButton("All catalogs", onOpenCatalogs)
                    AurasQuietButton("Search Orbit", onOpenSearch)
                }
            }
        }
        AurasSourcePicker(
            visible = sourcePickerVisible,
            selectedKey = state.sourceName,
            options = state.sources,
            onDismiss = onSourceDismiss,
            onSelect = onSourceSelect,
        )
    }
}

private fun AurasShelf.isExplicitTopTenShelf(): Boolean {
    val normalized = name.lowercase(Locale.ROOT)
    return Regex("\\btop(?:\\s*10|\\s*ten)\\b|\\btop10\\b").containsMatchIn(normalized)
}

private fun AurasShelf.isPopularShelf(): Boolean {
    val normalized = name.lowercase(Locale.ROOT)
    return listOf("popular", "trending", "most viewed", "most watched")
        .any(normalized::contains)
}

@Composable
private fun AurasLoadingPanel() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(
            Modifier
                .fillMaxWidth(0.42f)
                .height(20.dp)
                .background(AurasPalette.SurfaceRaised, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
        )
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) { index ->
                Spacer(
                    Modifier
                        .width(if (index == 1) 144.dp else 132.dp)
                        .height(192.dp)
                        .background(AurasPalette.Surface, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                )
            }
        }
    }
}
