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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.DataStoreHelper

/** Provider-backed catalog browsing with an Auras-owned phone layout. */
class AurasCatalogsFragment : Fragment() {
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
                AurasCatalogsScreen(
                    state = browseState,
                    sourcePickerVisible = sourcePickerVisible,
                    onBack = { findNavController().navigateUp() },
                    onSourceClick = { sourcePickerVisible = true },
                    onSourceDismiss = { sourcePickerVisible = false },
                    onSourceSelect = ::selectSource,
                    onConnectSource = { openDestination(R.id.navigation_settings_extensions) },
                    onHelpClick = { openDestination(R.id.navigation_auras_help) },
                    onOpenTitle = ::openTitle,
                    onExpand = ::openCatalogList,
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

    override fun onStart() {
        super.onStart()
        afterPluginsLoadedEvent += ::refreshSourceOptions
        loadSourceOptions()
    }

    override fun onStop() {
        afterPluginsLoadedEvent -= ::refreshSourceOptions
        super.onStop()
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
                    shelves = emptyList(),
                    loading = false,
                    error = resource.errorString,
                )
            }
        }
        homeViewModel.apiName.observe(viewLifecycleOwner) { name ->
            browseState = browseState.copy(sourceName = name)
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

    private fun openTitle(item: SearchResponse, index: Int) {
        val view = view ?: return
        SearchHelper.handleSearchClickCallback(
            SearchClickCallback(SEARCH_ACTION_LOAD, view, index, item)
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
    state: AurasBrowseState,
    sourcePickerVisible: Boolean,
    onBack: () -> Unit,
    onSourceClick: () -> Unit,
    onSourceDismiss: () -> Unit,
    onSourceSelect: (AurasSourceOption) -> Unit,
    onConnectSource: () -> Unit,
    onHelpClick: () -> Unit,
    onOpenTitle: (SearchResponse, Int) -> Unit,
    onExpand: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(AurasPalette.Canvas),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(23.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AurasBrandBar(onHelpClick = onHelpClick)
                    AurasSourceRow(
                        sourceName = state.sourceName,
                        hasSources = state.sources.isNotEmpty(),
                        onClick = onSourceClick,
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AurasPageTitle(
                        title = stringResource(R.string.auras_catalogs_title),
                        subtitle = stringResource(R.string.auras_catalogs_subtitle),
                    )
                    AurasQuietButton(stringResource(R.string.auras_catalogs_back), onBack)
                }
            }
            if (state.error != null && state.shelves.isEmpty()) {
                item {
                    AurasEmptyPanel(
                        title = stringResource(R.string.auras_catalogs_unreachable_title),
                        body = state.error,
                        button = stringResource(R.string.auras_explore_choose_source),
                        onClick = onSourceClick,
                    )
                }
            } else if (state.sources.isEmpty() && state.shelves.isEmpty() && !state.loading) {
                item {
                    AurasEmptyPanel(
                        title = stringResource(R.string.auras_catalogs_empty_title),
                        body = stringResource(R.string.auras_catalogs_empty_body),
                        button = stringResource(R.string.auras_explore_connect_source),
                        onClick = onConnectSource,
                    )
                }
            } else if (state.loading && state.shelves.isEmpty()) {
                item { AurasLoadingPanelForCatalogs() }
            }
            state.shelves.forEach { shelf ->
                item(key = "catalog-${shelf.name}") {
                    AurasCatalogShelf(
                        shelf = shelf,
                        onOpen = onOpenTitle,
                        onExpand = if (shelf.hasNext) ({ onExpand(shelf.name) }) else null,
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
