package com.lagradost.cloudstream3.ui.explore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

internal const val AURAS_CATALOG_ARGUMENT_SHELF = "auras_catalog_shelf"
internal const val AURAS_CATALOG_ARGUMENT_SOURCE = "auras_catalog_source"

/** Full provider-ordered catalog opened from a shelf's More action. */
class AurasCatalogListFragment : Fragment() {
    private val homeViewModel: HomeViewModel by activityViewModels()
    private var browseState by mutableStateOf(AurasBrowseState())
    private val pageLoads = CopyOnWriteArraySet<Job>()

    private val shelfName: String
        get() = arguments?.getString(AURAS_CATALOG_ARGUMENT_SHELF).orEmpty()

    private val expectedSourceName: String?
        get() = arguments?.getString(AURAS_CATALOG_ARGUMENT_SOURCE)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AurasMobileTheme {
                AurasCatalogListScreen(
                    state = browseState,
                    shelfName = shelfName,
                    expectedSourceName = expectedSourceName,
                    onBack = { findNavController().navigateUp() },
                    onOpenTitle = ::openTitle,
                    onLoadMore = ::loadMore,
                    onRetrySource = ::reloadSource,
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.page.observe(viewLifecycleOwner) { resource ->
            browseState = when (resource) {
                is Resource.Success -> browseState.copy(
                    shelves = resource.value.values.mapNotNull { expandable ->
                        expandable.list.takeIf { it.name == shelfName }?.let { page ->
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
                is Resource.Failure -> browseState.copy(loading = false, error = resource.errorString)
            }
        }
        homeViewModel.apiName.observe(viewLifecycleOwner) { name ->
            browseState = browseState.copy(sourceName = name)
        }
        val loadedShelves = (homeViewModel.page.value as? Resource.Success)?.value
        if (expectedSourceName != null &&
            (homeViewModel.apiName.value != expectedSourceName || loadedShelves?.containsKey(shelfName) != true)
        ) {
            homeViewModel.loadAndCancel(expectedSourceName, forceReload = true)
        }
    }

    private fun loadMore() {
        val job = viewLifecycleOwner.lifecycleScope.launch {
            homeViewModel.expandAndReturn(shelfName)
        }
        pageLoads.add(job)
        job.invokeOnCompletion { pageLoads.remove(job) }
    }

    private fun reloadSource() {
        expectedSourceName?.let { homeViewModel.loadAndCancel(it, forceReload = true) }
    }

    private fun openTitle(item: SearchResponse, index: Int) {
        val view = view ?: return
        SearchHelper.handleSearchClickCallback(
            SearchClickCallback(SEARCH_ACTION_LOAD, view, index, item)
        )
    }

    override fun onStop() {
        pageLoads.toList().forEach { it.cancel() }
        pageLoads.clear()
        super.onStop()
    }
}

@Composable
private fun AurasCatalogListScreen(
    state: AurasBrowseState,
    shelfName: String,
    expectedSourceName: String?,
    onBack: () -> Unit,
    onOpenTitle: (SearchResponse, Int) -> Unit,
    onLoadMore: () -> Unit,
    onRetrySource: () -> Unit,
) {
    val sourceChanged = expectedSourceName != null && state.sourceName != null &&
        expectedSourceName != state.sourceName
    val shelf = if (sourceChanged) null else state.shelves.firstOrNull { it.name == shelfName }
    val sourceName = state.sourceName ?: expectedSourceName.orEmpty()
    val listState = rememberLazyListState()
    val lastAutoRequestedSize = remember(shelfName, expectedSourceName) { mutableIntStateOf(-1) }

    LaunchedEffect(listState, shelfName, shelf?.items?.size, shelf?.hasNext, shelf?.isLoadingMore, shelf?.pageError) {
        val current = shelf ?: return@LaunchedEffect
        if (!current.hasNext || current.isLoadingMore || current.pageError != null) return@LaunchedEffect

        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible >= totalItems - 5
        }.first { it }

        if (lastAutoRequestedSize.intValue != current.items.size) {
            lastAutoRequestedSize.intValue = current.items.size
            onLoadMore()
        }
    }

    Column(Modifier.fillMaxSize().background(AurasPalette.Canvas)) {
        Surface(
            onClick = onBack,
            color = AurasPalette.SurfaceRaised,
            contentColor = AurasPalette.Text,
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(stringResource(R.string.auras_catalog_list_back), style = MaterialTheme.typography.labelMedium)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "catalog-list-title") {
                AurasPageTitle(
                    title = shelfName,
                    subtitle = stringResource(R.string.auras_catalog_list_subtitle, sourceName),
                )
            }

            when {
                state.loading && shelf == null -> item(key = "catalog-list-loading") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = AurasPalette.Accent)
                    }
                }

                shelf != null -> {
                    itemsIndexed(
                        items = shelf.items,
                        key = { _, item -> item.id?.toString() ?: "${item.apiName}:${item.url}" },
                    ) { index, item ->
                        AurasCatalogListRow(
                            item = item,
                            onClick = { onOpenTitle(item, index) },
                        )
                    }

                    when {
                        shelf.pageError != null -> item(key = "catalog-list-error") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    stringResource(R.string.auras_catalog_page_error, shelf.pageError),
                                    color = AurasPalette.Ember,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                AurasQuietButton(stringResource(R.string.auras_catalog_retry), onLoadMore)
                            }
                        }

                        shelf.isLoadingMore -> item(key = "catalog-list-loading-more") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(color = AurasPalette.Accent, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.auras_catalog_loading_more), color = AurasPalette.Muted)
                            }
                        }

                        shelf.hasNext -> item(key = "catalog-list-continue") {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(stringResource(R.string.auras_catalog_scroll_hint), color = AurasPalette.Muted)
                                if (lastAutoRequestedSize.intValue == shelf.items.size) {
                                    AurasQuietButton(stringResource(R.string.auras_catalog_load_more), onLoadMore)
                                }
                            }
                        }

                        else -> item(key = "catalog-list-end") {
                            Text(
                                stringResource(R.string.auras_catalog_list_end),
                                color = AurasPalette.Muted,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            )
                        }
                    }
                }

                else -> item(key = "catalog-list-empty") {
                    AurasEmptyPanel(
                        title = stringResource(
                            if (sourceChanged) R.string.auras_catalog_changed_title
                            else R.string.auras_catalog_unavailable_title
                        ),
                        body = state.error ?: if (sourceChanged) {
                            stringResource(R.string.auras_catalog_changed_body)
                        } else {
                            stringResource(R.string.auras_catalog_unavailable_body)
                        },
                        button = stringResource(
                            if (sourceChanged) R.string.auras_catalog_list_back_to_catalogs
                            else R.string.auras_catalog_retry
                        ),
                        onClick = if (sourceChanged) onBack else onRetrySource,
                    )
                }
            }
        }
    }
}

@Composable
private fun AurasCatalogListRow(item: SearchResponse, onClick: () -> Unit) {
    val year = when (item) {
        is AnimeSearchResponse -> item.year
        is MovieSearchResponse -> item.year
        is TvSeriesSearchResponse -> item.year
        else -> null
    }
    val score = item.score?.toStringNull(minScore = 0.1, maxScore = 10, decimals = 1)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = AurasPalette.Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, AurasPalette.Stroke),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(19.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(69.dp)
                    .aspectRatio(com.lagradost.cloudstream3.ui.PORTRAIT_POSTER_ASPECT_RATIO)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(11.dp)),
            ) {
                AurasArtwork(
                    url = item.posterUrl,
                    headers = item.posterHeaders,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    item.name,
                    color = AurasPalette.Text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (year != null || score != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        year?.let {
                            Text(
                                text = it.toString(),
                                color = AurasPalette.Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (year != null && score != null) {
                            Text("·", color = AurasPalette.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        score?.let {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text("★", color = AurasPalette.Accent, style = MaterialTheme.typography.bodySmall)
                                Text(it, color = AurasPalette.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
