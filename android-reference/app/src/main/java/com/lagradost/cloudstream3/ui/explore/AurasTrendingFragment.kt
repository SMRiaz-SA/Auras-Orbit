package com.lagradost.cloudstream3.ui.explore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

internal const val AURAS_TRENDING_ARGUMENT_SHELF = "auras_trending_shelf"
internal const val AURAS_TRENDING_ARGUMENT_SOURCE = "auras_trending_source"

/** Full, provider-ordered Trending list opened from the ten-title Explore preview. */
class AurasTrendingFragment : Fragment() {
    private val homeViewModel: HomeViewModel by activityViewModels()
    private var browseState by mutableStateOf(AurasBrowseState())
    private val moreLoads = CopyOnWriteArraySet<Job>()

    private val shelfName: String?
        get() = arguments?.getString(AURAS_TRENDING_ARGUMENT_SHELF)

    private val expectedSourceName: String?
        get() = arguments?.getString(AURAS_TRENDING_ARGUMENT_SOURCE)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AurasMobileTheme {
                AurasTrendingListScreen(
                    state = browseState,
                    shelfName = shelfName,
                    expectedSourceName = expectedSourceName,
                    onBack = { findNavController().navigateUp() },
                    onOpenTitle = ::openTitle,
                    onLoadMore = ::loadMore,
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
                        expandable.list.list.takeIf { it.isNotEmpty() }?.let {
                            AurasShelf(
                                name = expandable.list.name,
                                items = it,
                                hasNext = expandable.hasNext,
                                isLoadingMore = expandable.isLoadingMore,
                                pageError = expandable.pageError,
                            )
                        }
                    },
                    loading = false,
                    error = null,
                )

                is Resource.Loading -> browseState.copy(
                    shelves = emptyList(),
                    loading = true,
                    error = null,
                )

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

    private fun loadMore() {
        val name = shelfName ?: return
        val job = viewLifecycleOwner.lifecycleScope.launch {
            homeViewModel.expandAndReturn(name)
        }
        moreLoads.add(job)
        job.invokeOnCompletion { moreLoads.remove(job) }
    }

    private fun openTitle(item: SearchResponse, index: Int) {
        val view = view ?: return
        SearchHelper.handleSearchClickCallback(
            SearchClickCallback(SEARCH_ACTION_LOAD, view, index, item)
        )
    }

    override fun onStop() {
        moreLoads.toList().forEach { it.cancel() }
        moreLoads.clear()
        super.onStop()
    }
}

@Composable
private fun AurasTrendingListScreen(
    state: AurasBrowseState,
    shelfName: String?,
    expectedSourceName: String?,
    onBack: () -> Unit,
    onOpenTitle: (SearchResponse, Int) -> Unit,
    onLoadMore: () -> Unit,
) {
    val sourceChanged = expectedSourceName != null && state.sourceName != null &&
        expectedSourceName != state.sourceName
    val shelf = if (sourceChanged) null else state.shelves.firstOrNull { it.name == shelfName }
    val sourceName = state.sourceName ?: expectedSourceName.orEmpty()
    val listState = rememberLazyListState()
    val lastAutoRequestedSize = remember(shelfName) { mutableIntStateOf(-1) }

    LaunchedEffect(
        listState,
        shelfName,
        shelf?.items?.size,
        shelf?.hasNext,
        shelf?.isLoadingMore,
        shelf?.pageError,
    ) {
        val current = shelf ?: return@LaunchedEffect
        if (!current.hasNext || current.isLoadingMore || current.pageError != null) {
            return@LaunchedEffect
        }

        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible >= totalItems - 4
        }.first { nearEnd -> nearEnd }

        if (lastAutoRequestedSize.intValue != current.items.size) {
            lastAutoRequestedSize.intValue = current.items.size
            onLoadMore()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AurasPalette.Canvas),
    ) {
        Surface(
            onClick = onBack,
            color = AurasPalette.SurfaceRaised,
            contentColor = AurasPalette.Text,
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = stringResource(R.string.auras_trending_back),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "trending-title") {
                AurasPageTitle(
                    title = when {
                        shelfName?.contains("trending", ignoreCase = true) == true ->
                            stringResource(R.string.auras_trending_title)
                        else -> shelfName ?: stringResource(R.string.auras_trending_title)
                    },
                    subtitle = stringResource(
                        R.string.auras_collection_full_subtitle,
                        sourceName.ifBlank { stringResource(R.string.auras_trending_default_source) },
                    ),
                )
            }

            when {
                state.loading && shelf == null -> item(key = "trending-loading") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(color = AurasPalette.Accent)
                    }
                }

                shelf != null -> {
                    itemsIndexed(
                        items = shelf.items,
                        key = { _, item -> item.id?.toString() ?: "${item.apiName}:${item.url}" },
                    ) { index, item ->
                        AurasRankedTrendingRow(
                            item = item,
                            rank = index + 1,
                            onOpen = { onOpenTitle(item, index) },
                        )
                    }

                    when {
                        shelf.pageError != null -> item(key = "trending-error") {
                            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text(
                                    text = stringResource(
                                        R.string.auras_catalog_page_error,
                                        shelf.pageError,
                                    ),
                                    color = AurasPalette.Ember,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                AurasQuietButton(
                                    label = stringResource(R.string.auras_catalog_retry),
                                    onClick = onLoadMore,
                                )
                            }
                        }

                        shelf.isLoadingMore -> item(key = "trending-loading-more") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = AurasPalette.Accent,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.size(10.dp))
                                Text(
                                    text = stringResource(R.string.auras_trending_loading),
                                    color = AurasPalette.Muted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        shelf.hasNext -> item(key = "trending-more") {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.auras_trending_scroll_hint),
                                    color = AurasPalette.Muted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (lastAutoRequestedSize.intValue == shelf.items.size) {
                                    AurasQuietButton(
                                        label = stringResource(R.string.auras_trending_continue_loading),
                                        onClick = onLoadMore,
                                    )
                                }
                            }
                        }

                        else -> item(key = "trending-end") {
                            Text(
                                text = stringResource(R.string.auras_trending_end),
                                color = AurasPalette.Muted,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                            )
                        }
                    }
                }

                else -> item(key = "trending-empty") {
                    AurasEmptyPanel(
                        title = stringResource(
                            if (sourceChanged) R.string.auras_trending_source_changed_title
                            else R.string.auras_collection_empty_title
                        ),
                        body = state.error ?: if (sourceChanged) {
                            stringResource(R.string.auras_trending_source_changed_body)
                        } else {
                            stringResource(R.string.auras_collection_empty_body, shelfName.orEmpty())
                        },
                        button = stringResource(R.string.auras_trending_back),
                        onClick = onBack,
                    )
                }
            }
        }
    }
}
