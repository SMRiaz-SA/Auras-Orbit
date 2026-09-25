package com.lagradost.cloudstream3.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.desktop.ui.components.PosterCard
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.screens.home.HomeDiscoveryItem
import com.lagradost.cloudstream3.desktop.ui.screens.home.mergeHomeDiscoveryItems
import com.lagradost.runtime.executor.SafePluginInvoker
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@Composable
fun ComposeCategoryGridScreen(
    onNavigate: (Config) -> Unit,
    onBack: () -> Unit,
    provider: MainAPI,
    title: String,
    initialContent: CategoryGridContent,
) {
    var items by remember(provider.name, title, initialContent) {
        mutableStateOf(initialContent.items.distinctBy { "${it.apiName}\u0000${it.url}" })
    }
    var nextPage by remember(provider.name, title, initialContent) { mutableStateOf(2) }
    var mergedSources by remember(provider.name, title, initialContent) {
        mutableStateOf(initialContent.mergedSources)
    }
    var itemProviders by remember(provider.name, title, initialContent) {
        mutableStateOf(initialContent.itemProviders)
    }
    var canLoadMore by remember(provider.name, title, initialContent) {
        mutableStateOf(
            (initialContent.pageRequest != null && initialContent.hasNext) ||
                initialContent.mergedSources.any { it.hasNext },
        )
    }
    var isLoadingMore by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    fun loadMore() {
        val request = initialContent.pageRequest
        if (request == null && mergedSources.isEmpty()) return
        if (!canLoadMore || isLoadingMore) return
        scope.launch {
            isLoadingMore = true
            loadError = null
                if (request == null) {
                    val pendingSources = mergedSources.filter { it.hasNext }
                    val sourceResults = withContext(Dispatchers.IO) {
                        coroutineScope {
                            val semaphore = Semaphore(3)
                            pendingSources.map { source ->
                                async {
                                    val result = semaphore.withPermit {
                                        SafePluginInvoker.invoke(
                                            tag = "CategoryGrid:${source.provider.name}:$title",
                                            timeoutMs = SafePluginInvoker.TIMEOUT_LOAD_MS,
                                        ) { source.provider.getMainPage(source.nextPage, source.request) }
                                    }
                                    source to result
                                }
                            }.awaitAll()
                        }
                    }

                    val updatedSources = mergedSources.associateByTo(LinkedHashMap()) { source ->
                        "${source.provider.name}:${source.request.name}:${source.sectionName}"
                    }
                    val newItems = mutableListOf<Pair<MainAPI, com.lagradost.cloudstream3.SearchResponse>>()
                    val sourceErrors = mutableListOf<String>()
                    sourceResults.forEach { (source, result) ->
                        if (!result.isSuccess) {
                            val error = result.exceptionOrNull()
                            if (error is CancellationException) throw error
                            val message = error?.localizedMessage ?: "Could not load more items."
                            sourceErrors += "${source.provider.name}: $message"
                            updatedSources["${source.provider.name}:${source.request.name}:${source.sectionName}"] = source.copy(lastError = message)
                            return@forEach
                        }

                        val response = result.getOrNull()
                        val section = response?.items?.firstOrNull {
                            it.name.equals(source.sectionName, ignoreCase = true)
                        } ?: response?.items?.firstOrNull {
                            it.name.contains(source.sectionName, ignoreCase = true) ||
                                source.sectionName.contains(it.name, ignoreCase = true)
                        } ?: response?.items?.singleOrNull()

                        if (section == null || section.list.isEmpty()) {
                            updatedSources["${source.provider.name}:${source.request.name}:${source.sectionName}"] =
                                source.copy(hasNext = false, lastError = null)
                            return@forEach
                        }

                        newItems += section.list.map { source.provider to it }
                        updatedSources["${source.provider.name}:${source.request.name}:${source.sectionName}"] = source.copy(
                            nextPage = source.nextPage + 1,
                            hasNext = response?.hasNext == true,
                            lastError = null,
                        )
                    }

                    val homeCandidates = (items + newItems.map { it.second }).map { item ->
                        HomeDiscoveryItem(item.apiName, item)
                    }
                    items = mergeHomeDiscoveryItems(homeCandidates).map(HomeDiscoveryItem::response)
                    itemProviders = itemProviders + newItems.flatMap { (owner, item) ->
                        listOf(
                            com.lagradost.cloudstream3.desktop.ui.screens.categoryGridItemKey(owner.name, item.url) to owner,
                            com.lagradost.cloudstream3.desktop.ui.screens.categoryGridItemKey(item.apiName, item.url) to owner,
                        )
                    }
                    mergedSources = updatedSources.values.toList()
                    canLoadMore = mergedSources.any { it.hasNext }
                    loadError = sourceErrors.takeIf { it.isNotEmpty() }?.joinToString(" · ")
                    isLoadingMore = false
                    return@launch
                }

                val result = withContext(Dispatchers.IO) {
                    SafePluginInvoker.invoke(
                        tag = "CategoryGrid:${provider.name}:$title",
                        timeoutMs = SafePluginInvoker.TIMEOUT_LOAD_MS,
                    ) { provider.getMainPage(nextPage, request) }
                }

            if (!result.isSuccess) {
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                loadError = error?.localizedMessage ?: "Could not load more items."
                isLoadingMore = false
                return@launch
            }

            val response = result.getOrNull()
            val section = response?.items?.firstOrNull {
                it.name.equals(initialContent.sectionName ?: title, ignoreCase = true)
            } ?: response?.items?.singleOrNull()

            if (response == null || section == null) {
                canLoadMore = false
            } else {
                items = (items + section.list).distinctBy { it.url }
                nextPage += 1
                canLoadMore = response.hasNext && section.list.isNotEmpty()
            }
            isLoadingMore = false
        }
    }

    val gridScale by com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.gridScale.collectAsState()
    val hasLandscapeItems = remember(items) {
        items.any { it.type == com.lagradost.cloudstream3.TvType.Live || it.posterHeaders?.containsKey("landscape") == true }
    }
    val baseMinSize = when (gridScale) {
        "Compact" -> 150.dp
        "Large" -> 220.dp
        else -> 190.dp
    }
    val minSize = if (hasLandscapeItems) (baseMinSize * 1.45f) else baseMinSize

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        )

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = minSize),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(count = items.size, key = { "${items[it].apiName}:${items[it].url}" }) { index ->
                val item = items[index]
                val itemProvider = itemProviders[
                    com.lagradost.cloudstream3.desktop.ui.screens.categoryGridItemKey(item.apiName, item.url)
                ] ?: APIHolder.getApiFromNameNull(item.apiName) ?: provider
                PosterCard(
                    item = item,
                    provider = itemProvider,
                    aspectRatio = if (hasLandscapeItems) 16f / 9f else null,
                    onClick = {
                        onNavigate(Config.Details(itemProvider.name, item.url, item.name, item.posterUrl, null, false))
                    },
                    onPlayClick = {
                        onNavigate(Config.Details(itemProvider.name, item.url, item.name, item.posterUrl, null, true))
                    },
                )
            }
        }

        if (initialContent.pageRequest != null || initialContent.mergedSources.isNotEmpty()) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    isLoadingMore -> CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    loadError != null -> androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(loadError!!, color = MaterialTheme.colorScheme.error)
                        Button(onClick = ::loadMore) { Text("Retry") }
                    }
                    canLoadMore -> Button(onClick = ::loadMore) { Text("Load more") }
                    else -> Text("${items.size} items loaded", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
