package com.lagradost.cloudstream3.desktop.ui.screens.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.metadata.MetadataMatch
import com.lagradost.cloudstream3.desktop.metadata.MetadataPipeline
import com.lagradost.cloudstream3.desktop.ui.GlobalMediaLauncher
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog
import kotlinx.coroutines.launch

@Composable
fun GlobalNetworkStreamMetadataDialog() {
    val history = GlobalMediaLauncher.networkStreamMetadataTarget ?: return
    var title by remember(history.parentId) { mutableStateOf(history.showName) }
    val savedMetadata = remember(history.parentId) { GlobalMediaLauncher.getNetworkStreamMetadata(history) }
    var selectedType by remember(history.parentId) {
        mutableStateOf(savedMetadata?.mediaType?.let { name -> TvType.entries.firstOrNull { it.name == name } } ?: TvType.Movie)
    }
    var isTypeMenuExpanded by remember(history.parentId) { mutableStateOf(false) }
    var isSearching by remember(history.parentId) { mutableStateOf(false) }
    var hasSearched by remember(history.parentId) { mutableStateOf(false) }
    var matches by remember(history.parentId) { mutableStateOf<List<MetadataMatch>>(emptyList()) }
    var selectedMatch by remember(history.parentId) { mutableStateOf<MetadataMatch?>(null) }
    val scope = rememberCoroutineScope()

    fun search() {
        if (title.isBlank() || isSearching) return
        scope.launch {
            isSearching = true
            hasSearched = true
            selectedMatch = null
            matches = emptyList()
            try {
                matches = MetadataPipeline.findNetworkStreamMatches(title.trim(), selectedType)
            } catch (_: Exception) {
                matches = emptyList()
            } finally {
                isSearching = false
            }
        }
    }

    fun saveSelected(addToLibrary: Boolean) {
        val match = selectedMatch ?: return
        GlobalMediaLauncher.saveNetworkStreamMetadata(history, match, selectedType.name)
        if (addToLibrary) {
            GlobalMediaLauncher.addNetworkStreamToLibrary(
                history = history,
                name = match.matchedTitle,
                posterUrl = match.posterUrl ?: history.posterUrl,
            )
        }
        GlobalMediaLauncher.networkStreamMetadataTarget = null
    }

    CloudstreamAlertDialog(
        show = true,
        onDismissRequest = { GlobalMediaLauncher.networkStreamMetadataTarget = null },
        title = { Text("Find stream metadata") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Search by title, review the matches, then save metadata or add the stream to Library.")
                savedMetadata?.let { saved ->
                    Text(
                        "Current match: ${saved.matchedTitle}${saved.matchedYear?.let { " ($it)" } ?: ""} • ${saved.providerId.uppercase()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Title") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        OutlinedButton(onClick = { isTypeMenuExpanded = true }) {
                            Text(selectedType.displayName())
                        }
                        DropdownMenu(
                            expanded = isTypeMenuExpanded,
                            onDismissRequest = { isTypeMenuExpanded = false },
                        ) {
                            listOf(TvType.Movie, TvType.TvSeries, TvType.Anime).forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.displayName()) },
                                    onClick = {
                                        selectedType = type
                                        isTypeMenuExpanded = false
                                        matches = emptyList()
                                        selectedMatch = null
                                        hasSearched = false
                                    },
                                )
                            }
                        }
                    }
                    Button(onClick = ::search, enabled = title.isNotBlank() && !isSearching) {
                        Text("Search")
                    }
                    if (isSearching) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }

                if (matches.isEmpty() && !isSearching && title.isNotBlank()) {
                    Text(
                        if (hasSearched) "No matches found. Try another title or media type." else "Search for a title to see metadata matches.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (matches.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(matches, key = { it.matchKey() }) { match ->
                            val isSelected = selectedMatch?.matchKey() == match.matchKey()
                            Surface(
                                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                    .clickable { selectedMatch = match },
                                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (!match.posterUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = match.posterUrl,
                                            contentDescription = null,
                                            modifier = Modifier.size(width = 40.dp, height = 58.dp).clip(MaterialTheme.shapes.small),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(match.matchedTitle, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                        Text(
                                            listOfNotNull(match.providerId.uppercase(), match.matchedYear?.toString()).joinToString(" • "),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { saveSelected(addToLibrary = false) }, enabled = selectedMatch != null) {
                    Text("Save metadata")
                }
                TextButton(onClick = { saveSelected(addToLibrary = true) }, enabled = selectedMatch != null) {
                    Text("Add to Library")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { GlobalMediaLauncher.networkStreamMetadataTarget = null }) { Text("Cancel") }
        },
    )
}

private fun TvType.displayName(): String = when (this) {
    TvType.Movie -> "Movie"
    TvType.TvSeries -> "TV series"
    TvType.Anime -> "Anime"
    else -> name
}

private fun MetadataMatch.matchKey(): String =
    "$providerId:${tmdbId ?: imdbId ?: anilistId ?: matchedTitle.lowercase()}:$matchedYear"
