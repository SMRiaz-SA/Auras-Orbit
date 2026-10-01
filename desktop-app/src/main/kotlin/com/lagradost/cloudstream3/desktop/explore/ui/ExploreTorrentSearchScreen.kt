package com.lagradost.cloudstream3.desktop.explore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.explore.models.TorrentSearchResult
import com.lagradost.cloudstream3.desktop.explore.viewmodel.ExploreUiEvent
import com.lagradost.cloudstream3.desktop.explore.viewmodel.ExploreViewModel
import com.lagradost.cloudstream3.desktop.ui.GlobalMediaLauncher
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme

@Composable
fun ExploreTorrentSearchScreen(
    viewModel: ExploreViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val theme = LocalDesktopTheme.current

    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 1080.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Explore")
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Torrent Search", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = theme.TextPrimary)
                    Text("Search an online index; Orbit handles playback.", fontSize = 12.sp, color = theme.TextMuted)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.torrentSearchQuery,
                    onValueChange = { viewModel.onEvent(ExploreUiEvent.UpdateTorrentSearchQuery(it)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Movie or show title") },
                )
                Button(
                    onClick = { viewModel.onEvent(ExploreUiEvent.SearchTorrents(state.torrentSearchQuery)) },
                    enabled = !state.isSearchingTorrents && state.torrentSearchQuery.isNotBlank(),
                    modifier = Modifier.height(54.dp),
                ) {
                    Text("Search")
                }
            }

            if (state.isSearchingTorrents) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("Searching…", color = theme.TextMuted, fontSize = 13.sp)
                }
            } else if (state.torrentSearchResults.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.torrentSearchResults, key = { it.infoHash?.lowercase() ?: it.magnetLink }) { result ->
                        TorrentSearchResultCard(result = result, onPlay = {
                            GlobalMediaLauncher.playStreamUrl(result.magnetLink, displayName = result.name)
                        })
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                ) {
                    Text(
                        state.torrentSearchMessage ?: "Search titles to find available magnets.",
                        modifier = Modifier.padding(20.dp),
                        color = theme.TextMuted,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun TorrentSearchResultCard(
    result: TorrentSearchResult,
    onPlay: () -> Unit,
) {
    val theme = LocalDesktopTheme.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(result.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(result.humanSize ?: formatSize(result.sizeBytes))
                        append(" · Seeds: ")
                        append(result.seeders?.toString() ?: "?")
                        append(" · Peers: ")
                        append(result.leechers?.toString() ?: "?")
                        if (result.isVerified) append(" · Verified")
                    },
                    color = theme.TextMuted,
                    fontSize = 11.sp,
                )
            }
            TextButton(onClick = onPlay) { Text("Play") }
        }
    }
}

private fun formatSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "Size unknown"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = sizeBytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return "%.1f %s".format(value, units[unit])
}
