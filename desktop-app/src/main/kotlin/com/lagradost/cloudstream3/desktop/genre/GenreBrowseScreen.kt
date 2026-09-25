package com.lagradost.cloudstream3.desktop.genre

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.rememberLazyListState
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import kotlinx.coroutines.launch

@Composable
fun GenreBrowseScreen(
    viewModel: GenreBrowseViewModel,
    onBack: () -> Unit,
    onNavigate: (Config) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()
    val genreListState = rememberLazyListState()
    val topicListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.effectFlow.collect { effect ->
            when (effect) {
                is GenreBrowseUiEffect.OpenDetails -> onNavigate(
                    Config.Details(
                        providerName = effect.providerName,
                        url = effect.url,
                        preloadedName = effect.title,
                        preloadedPoster = effect.posterUrl,
                    )
                )
            }
        }
    }

    LaunchedEffect(state.mediaType, state.selectedGenre, state.selectedTopic, state.sort) {
        gridState.scrollToItem(0)
    }
    LaunchedEffect(state.mediaType, state.selectedProvider) {
        genreListState.scrollToItem(0)
        topicListState.scrollToItem(0)
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val total = gridState.layoutInfo.totalItemsCount
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 6
        }
    }
    val canScrollGenresLeft by remember {
        derivedStateOf {
            genreListState.firstVisibleItemIndex > 0 || genreListState.firstVisibleItemScrollOffset > 0
        }
    }
    val canScrollGenresRight by remember {
        derivedStateOf {
            val lastVisible = genreListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible < genreListState.layoutInfo.totalItemsCount - 1
        }
    }
    val canScrollTopicsLeft by remember {
        derivedStateOf {
            topicListState.firstVisibleItemIndex > 0 || topicListState.firstVisibleItemScrollOffset > 0
        }
    }
    val canScrollTopicsRight by remember {
        derivedStateOf {
            val lastVisible = topicListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible < topicListState.layoutInfo.totalItemsCount - 1
        }
    }
    LaunchedEffect(shouldLoadMore, state.canLoadMore, state.isLoadingResults, state.isLoadingMore, state.error) {
        if (shouldLoadMore && state.canLoadMore && !state.isLoadingResults && !state.isLoadingMore && state.error == null) {
            viewModel.onEvent(GenreBrowseUiEvent.LoadMore)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Explore")
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Genre Browser", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Explore movies and series by genre and topic.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Provider", style = MaterialTheme.typography.labelLarge)
                    ProviderPicker(
                        providers = state.providers,
                        selectedProvider = state.selectedProvider,
                        onSelect = { viewModel.onEvent(GenreBrowseUiEvent.SelectProvider(it)) },
                    )
                    TextButton(onClick = { viewModel.onEvent(GenreBrowseUiEvent.RefreshProviders) }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Refresh", fontSize = 11.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "TMDB catalogue · StreamPlay details & playback",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GenreBrowseMediaType.entries.forEach { mediaType ->
                        FilterChip(
                            selected = state.mediaType == mediaType,
                            onClick = { viewModel.onEvent(GenreBrowseUiEvent.SelectMediaType(mediaType)) },
                            label = { Text(mediaType.label) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (mediaType == GenreBrowseMediaType.Movies) Icons.Default.Movie else Icons.Default.Tv,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }

                if (state.selectedProvider != null) {
                    if (state.isLoadingGenres) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Loading genres…", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (state.genres.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        genreListState.animateScrollToItem((genreListState.firstVisibleItemIndex - 4).coerceAtLeast(0))
                                    }
                                },
                                enabled = canScrollGenresLeft,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Scroll genres left")
                            }
                            LazyRow(
                                modifier = Modifier.weight(1f),
                                state = genreListState,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                item(key = "all-genres") {
                                    FilterChip(
                                        selected = state.selectedGenre == null,
                                        onClick = { viewModel.onEvent(GenreBrowseUiEvent.SelectGenre(null)) },
                                        label = { Text("All") },
                                    )
                                }
                                items(state.genres, key = { it.id }) { genre ->
                                    FilterChip(
                                        selected = state.selectedGenre?.id == genre.id,
                                        onClick = { viewModel.onEvent(GenreBrowseUiEvent.SelectGenre(genre)) },
                                        label = { Text(genre.name, maxLines = 1, softWrap = false) },
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val nextIndex = (genreListState.firstVisibleItemIndex + 4)
                                            .coerceAtMost((genreListState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                                        genreListState.animateScrollToItem(nextIndex)
                                    }
                                },
                                enabled = canScrollGenresRight,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.ArrowBack,
                                    modifier = Modifier.rotate(180f),
                                    contentDescription = "Scroll genres right",
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Topics", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        topicListState.animateScrollToItem((topicListState.firstVisibleItemIndex - 4).coerceAtLeast(0))
                                    }
                                },
                                enabled = canScrollTopicsLeft,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Scroll topics left")
                            }
                            LazyRow(
                                modifier = Modifier.weight(1f),
                                state = topicListState,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                item(key = "all-topics") {
                                    FilterChip(
                                        selected = state.selectedTopic == null,
                                        onClick = { viewModel.onEvent(GenreBrowseUiEvent.SelectTopic(null)) },
                                        label = { Text("All") },
                                    )
                                }
                                items(GenreBrowseTopic.entries, key = { it.name }) { topic ->
                                    FilterChip(
                                        selected = state.selectedTopic == topic,
                                        onClick = {
                                            val nextTopic = if (state.selectedTopic == topic) null else topic
                                            viewModel.onEvent(GenreBrowseUiEvent.SelectTopic(nextTopic))
                                        },
                                        label = { Text(topic.label, maxLines = 1, softWrap = false) },
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val nextIndex = (topicListState.firstVisibleItemIndex + 4)
                                            .coerceAtMost((topicListState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                                        topicListState.animateScrollToItem(nextIndex)
                                    }
                                },
                                enabled = canScrollTopicsRight,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.ArrowBack,
                                    modifier = Modifier.rotate(180f),
                                    contentDescription = "Scroll topics right",
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.selectedProvider != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val resultSummary = when {
                    state.isLoadingResults && state.results.isEmpty() -> "Loading titles…"
                    state.error != null && state.results.isEmpty() -> "Results unavailable"
                    state.results.isEmpty() -> "No titles"
                    else -> "${state.results.size} titles · page ${state.page} of ${state.totalPages.coerceAtLeast(state.page)}"
                }
                Text(resultSummary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("Sort", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SortPicker(state.sort) { viewModel.onEvent(GenreBrowseUiEvent.SelectSort(it)) }
            }
        }

        when {
            state.providers.isEmpty() -> UnsupportedProviderState(Modifier.weight(1f).fillMaxWidth()) { onNavigate(Config.Search) }
            state.selectedProvider == null -> UnsupportedProviderState(Modifier.weight(1f).fillMaxWidth()) { onNavigate(Config.Search) }
            state.error != null && state.results.isEmpty() -> FailureState(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                message = state.error!!,
                onRetry = { viewModel.onEvent(GenreBrowseUiEvent.Retry) },
            )
            (state.isLoadingGenres || state.isLoadingResults) && state.results.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.results.isEmpty() -> MessageState("No titles were returned for this selection.", Modifier.weight(1f).fillMaxWidth())
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 158.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    state = gridState,
                    contentPadding = PaddingValues(bottom = 28.dp),
                ) {
                    items(state.results, key = { "${it.mediaType.tmdbPath}:${it.id}" }) { result ->
                        GenreResultCard(result = result) {
                            viewModel.onEvent(GenreBrowseUiEvent.OpenResult(result))
                        }
                    }
                    if (state.isLoadingMore) {
                        item(key = "load-more-progress") {
                            Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }

        if (state.error != null && state.results.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { viewModel.onEvent(GenreBrowseUiEvent.Retry) }) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun ProviderPicker(
    providers: List<com.lagradost.cloudstream3.MainAPI>,
    selectedProvider: com.lagradost.cloudstream3.MainAPI?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = providers.isNotEmpty()) {
            Text(selectedProvider?.name ?: "Select StreamPlay", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.ExpandMore, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { provider ->
                val sourceName = provider.sourcePlugin
                    ?.substringAfterLast('/')
                    ?.substringAfterLast('\\')
                    ?.removeSuffix(".cs3")
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(provider.name)
                            if (!sourceName.isNullOrBlank()) {
                                Text(sourceName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    onClick = {
                        onSelect("${provider.sourcePlugin.orEmpty()}::${provider.name}")
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SortPicker(
    sort: GenreBrowseSort,
    onSelect: (GenreBrowseSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(sort.label, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.ExpandMore, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GenreBrowseSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun GenreResultCard(result: GenreBrowseResult, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(0.68f).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (result.posterUrl != null) {
                    AsyncImage(
                        model = result.posterUrl,
                        contentDescription = result.title,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text("No poster", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp)) {
                Text(result.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.year?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    result.rating?.takeIf { it > 0.0 }?.let {
                        Text("★ ${"%.1f".format(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun UnsupportedProviderState(modifier: Modifier = Modifier, onChooseProvider: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Genre browsing needs a loaded StreamPlay TMDB provider.", style = MaterialTheme.typography.titleMedium)
            Text(
                "Choose StreamPlay as your active provider. Other providers are not supported by this catalog yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onChooseProvider) { Text("Open Search") }
        }
    }
}

@Composable
private fun FailureState(modifier: Modifier = Modifier, message: String, onRetry: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun MessageState(message: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
