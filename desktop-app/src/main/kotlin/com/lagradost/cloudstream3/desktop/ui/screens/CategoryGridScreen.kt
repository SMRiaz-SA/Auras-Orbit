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
import com.lagradost.cloudstream3.desktop.ui.components.PosterCard
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.runtime.executor.SafePluginInvoker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ComposeCategoryGridScreen(
    onNavigate: (Config) -> Unit,
    onBack: () -> Unit,
    provider: MainAPI,
    title: String,
    initialContent: CategoryGridContent,
) {
    var items by remember(provider.name, title, initialContent) { mutableStateOf(initialContent.items.distinctBy { it.url }) }
    var nextPage by remember(provider.name, title, initialContent) { mutableStateOf(2) }
    var canLoadMore by remember(provider.name, title, initialContent) {
        mutableStateOf(initialContent.pageRequest != null && initialContent.hasNext)
    }
    var isLoadingMore by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    fun loadMore() {
        val request = initialContent.pageRequest ?: return
        if (!canLoadMore || isLoadingMore) return
        scope.launch {
            isLoadingMore = true
            loadError = null
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
            items(count = items.size, key = { items[it].url }) { index ->
                val item = items[index]
                PosterCard(
                    item = item,
                    provider = provider,
                    aspectRatio = if (hasLandscapeItems) 16f / 9f else null,
                    onClick = {
                        onNavigate(Config.Details(provider.name, item.url, item.name, item.posterUrl, null, false))
                    },
                    onPlayClick = {
                        onNavigate(Config.Details(provider.name, item.url, item.name, item.posterUrl, null, true))
                    },
                )
            }
        }

        if (initialContent.pageRequest != null) {
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
