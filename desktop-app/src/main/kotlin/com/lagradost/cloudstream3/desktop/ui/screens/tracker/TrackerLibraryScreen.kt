package com.lagradost.cloudstream3.desktop.ui.screens.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.library.ListSorting

@Composable
fun TrackerLibraryScreen(
    viewModel: TrackerLibraryViewModel,
    onNavigate: (Config) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var sortingMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.providers.size) {
        if (state.providers.isNotEmpty() && state.entries.isEmpty() && !state.loading) {
            viewModel.onEvent(TrackerLibraryUiEvent.Refresh)
        }
    }

    BoxWithTrackerInsets {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tracker Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Remote lists are informational until you explicitly save a selected title. Local watch history stays separate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.onEvent(TrackerLibraryUiEvent.Refresh) }, enabled = !state.loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh tracker library")
                }
            }

            Spacer(Modifier.height(12.dp))
            if (state.providers.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("No tracker account is connected", style = MaterialTheme.typography.titleMedium)
                        Text("Connect MyAnimeList, AniList, or Simkl in Settings before opening a remote library.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { onNavigate(Config.Settings) }) { Text("Open tracker settings") }
                    }
                }
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    OutlinedButton(onClick = { providerMenuExpanded = true }) {
                        val selected = state.providers.firstOrNull { it.idPrefix == state.selectedProvider }
                        Text(selected?.let { "${it.name} · ${it.accountName}" } ?: "Choose provider", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = providerMenuExpanded, onDismissRequest = { providerMenuExpanded = false }) {
                        state.providers.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text("${provider.name} · ${provider.accountName}") },
                                onClick = {
                                    providerMenuExpanded = false
                                    viewModel.onEvent(TrackerLibraryUiEvent.SelectProvider(provider.idPrefix))
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.onEvent(TrackerLibraryUiEvent.SearchQueryChanged(it)) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    singleLine = true,
                    placeholder = { Text("Search this tracker") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    colors = TextFieldDefaults.colors(unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                )
                Button(
                    onClick = { viewModel.onEvent(TrackerLibraryUiEvent.Search(state.searchQuery)) },
                    enabled = !state.searching && state.searchQuery.isNotBlank(),
                ) { Text("Search") }
            }

            state.providers.firstOrNull { it.idPrefix == state.selectedProvider }?.let { provider ->
                Text(
                    "Supports: ${provider.capabilities.joinToString { it.name.lowercase().replaceFirstChar(Char::uppercase) }}" +
                        ". " + when {
                            provider.supportsExactEpisodes -> "Exact episode selections are available for supported series."
                            provider.supportsCountProgress -> "Playback progress is count-only."
                            else -> "Playback progress is not exposed by this adapter."
                        },
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.loading || state.searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            state.error?.let { error ->
                Card(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { viewModel.onEvent(TrackerLibraryUiEvent.Refresh) }) { Text("Retry") }
                    }
                }
            }
            state.message?.let { message ->
                Text(message, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            if (state.searchResults.isNotEmpty()) {
                Text("Search matches", modifier = Modifier.padding(top = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium)
                LazyColumn(
                    modifier = Modifier.height(190.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.searchResults, key = { "${it.syncId}:${it.name}" }) { result ->
                        val entry = result.toTrackerEntry(state.selectedProvider.orEmpty())
                        SearchResultRow(result = result, onEdit = { viewModel.onEvent(TrackerLibraryUiEvent.OpenEditor(entry)) })
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = state.selectedStatus == null,
                        onClick = { viewModel.onEvent(TrackerLibraryUiEvent.SelectStatus(null)) },
                        label = { Text("All") },
                    )
                    state.statusFilters.forEach { status ->
                        FilterChip(
                            selected = state.selectedStatus == status,
                            onClick = { viewModel.onEvent(TrackerLibraryUiEvent.SelectStatus(status)) },
                            label = { Text(status) },
                        )
                    }
                }
                Box {
                    OutlinedButton(onClick = { sortingMenuExpanded = true }) { Text("Sort: ${state.sorting.name.lowercase().replace('_', ' ')}") }
                    DropdownMenu(expanded = sortingMenuExpanded, onDismissRequest = { sortingMenuExpanded = false }) {
                        ListSorting.entries.forEach { sorting ->
                            DropdownMenuItem(
                                text = { Text(sorting.name.lowercase().replace('_', ' ')) },
                                onClick = {
                                    sortingMenuExpanded = false
                                    viewModel.updateSorting(sorting)
                                },
                            )
                        }
                    }
                }
            }

            if (!state.loading && state.filteredEntries.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("No tracker titles match this view.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.filteredEntries, key = { "${it.item.apiName}:${it.item.syncId}" }) { entry ->
                        TrackerLibraryRow(entry, onEdit = { viewModel.onEvent(TrackerLibraryUiEvent.OpenEditor(entry)) })
                    }
                }
            }
        }
    }

    state.editor?.let { editor ->
        TrackerEditDialog(
            editor = editor,
            supportedStatuses = state.providers.firstOrNull { it.idPrefix == state.selectedProvider }
                ?.let { provider -> providersStatuses(provider.idPrefix) } ?: SyncWatchType.entries.toList(),
            onEvent = viewModel::onEvent,
        )
    }
}

@Composable
private fun BoxWithTrackerInsets(content: @Composable () -> Unit) = Box(modifier = Modifier.fillMaxSize()) { content() }

@Composable
private fun TrackerLibraryRow(entry: TrackerLibraryEntry, onEdit: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = entry.item.posterUrl,
                contentDescription = null,
                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.listName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                val progress = if (entry.item.episodesCompleted != null || entry.item.episodesTotal != null) {
                    "Progress ${entry.item.episodesCompleted ?: 0}/${entry.item.episodesTotal ?: "?"}"
                } else {
                    null
                }
                Text(
                    listOfNotNull(progress, entry.item.personalRating?.toDouble(10)?.let { "Score %.1f/10".format(java.util.Locale.ROOT, it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Edit")
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: SyncAPI.SyncSearchResult, onEdit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f), RoundedCornerShape(8.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(model = result.posterUrl, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
        Text(result.name, modifier = Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onEdit) { Text("Inspect / edit") }
    }
}

@Composable
private fun TrackerEditDialog(
    editor: TrackerEditorState,
    supportedStatuses: List<SyncWatchType>,
    onEvent: (TrackerLibraryUiEvent) -> Unit,
) {
    var statusMenuExpanded by remember(editor.entry.item.syncId) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!editor.saving) onEvent(TrackerLibraryUiEvent.CloseEditor) },
        title = { Text("Edit ${editor.entry.item.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Target: ${editor.entry.item.apiName} · ${editor.entry.item.syncId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { statusMenuExpanded = true }, enabled = !editor.saving) {
                        Text("Status: ${TrackerLibraryModel.statusLabel(editor.status)}")
                    }
                    DropdownMenu(expanded = statusMenuExpanded, onDismissRequest = { statusMenuExpanded = false }) {
                        supportedStatuses.forEach { status ->
                            DropdownMenuItem(
                                text = { Text(TrackerLibraryModel.statusLabel(status)) },
                                onClick = {
                                    statusMenuExpanded = false
                                    onEvent(TrackerLibraryUiEvent.SetEditorStatus(status))
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = editor.score,
                    onValueChange = { onEvent(TrackerLibraryUiEvent.SetEditorScore(it)) },
                    enabled = !editor.saving,
                    label = { Text("Score (0–10)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = editor.progress,
                    onValueChange = { onEvent(TrackerLibraryUiEvent.SetEditorProgress(it)) },
                    enabled = !editor.saving,
                    label = { Text("Watched episode count") },
                    singleLine = true,
                )
                if (editor.exactEpisodesEnabled) {
                    OutlinedTextField(
                        value = editor.exactEpisodes,
                        onValueChange = { onEvent(TrackerLibraryUiEvent.SetEditorEpisodes(it)) },
                        enabled = !editor.saving,
                        label = { Text("Exact episodes") },
                        supportingText = { Text("Use 1, 2 or season coordinates such as 1x3. Non-contiguous selections are preserved.") },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onEvent(TrackerLibraryUiEvent.SaveEditor) }, enabled = !editor.saving) { Text(if (editor.saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = { onEvent(TrackerLibraryUiEvent.CloseEditor) }, enabled = !editor.saving) { Text("Cancel") } },
    )
}

private fun providersStatuses(idPrefix: String): List<SyncWatchType> = when (idPrefix) {
    "mal", "anilist", "simkl" -> listOf(SyncWatchType.NONE, SyncWatchType.WATCHING, SyncWatchType.COMPLETED, SyncWatchType.ON_HOLD, SyncWatchType.DROPPED, SyncWatchType.PLAN_TO_WATCH)
    else -> SyncWatchType.entries.toList()
}

private fun SyncAPI.SyncSearchResult.toTrackerEntry(apiName: String): TrackerLibraryEntry = TrackerLibraryEntry(
    item = SyncAPI.LibraryItem(
        name = name,
        url = url,
        syncId = syncId,
        episodesCompleted = null,
        episodesTotal = null,
        personalRating = score,
        lastUpdatedUnixTime = null,
        apiName = apiName,
        type = type,
        posterUrl = posterUrl,
        posterHeaders = posterHeaders,
        quality = quality,
        releaseDate = year?.let { java.util.GregorianCalendar(it, 0, 1).time },
        id = id,
        score = score,
        mediaType = mediaType,
    ),
    listName = "Search result",
)
