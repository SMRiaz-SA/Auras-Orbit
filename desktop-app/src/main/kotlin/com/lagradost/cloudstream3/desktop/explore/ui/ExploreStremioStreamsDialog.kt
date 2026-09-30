package com.lagradost.cloudstream3.desktop.explore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.explore.models.ExploreItem
import com.lagradost.cloudstream3.desktop.metadata.stremio.StremioAddonClient.StremioVideo
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.utils.ExtractorLink

@Composable
fun ExploreStremioStreamsDialog(
    item: ExploreItem?,
    videos: List<StremioVideo>,
    selectedVideo: StremioVideo?,
    streams: List<ExtractorLink>,
    isLoading: Boolean,
    message: String?,
    onSelectVideo: (StremioVideo) -> Unit,
    onBackToVideos: () -> Unit,
    onDismiss: () -> Unit,
    onPlay: (ExploreItem, StremioVideo?, ExtractorLink) -> Unit,
) {
    if (item == null) return
    val theme = LocalDesktopTheme.current
    val selectingVideo = videos.isNotEmpty() && selectedVideo == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(item.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    text = when {
                        selectingVideo -> if (item.type.equals("series", ignoreCase = true)) "Choose an episode" else "Choose a video"
                        selectedVideo != null -> selectedVideo.title ?: "Available Stremio streams"
                        else -> "Available Stremio streams"
                    },
                    color = theme.TextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text("Checking enabled add-ons…", color = theme.TextMuted, fontSize = 13.sp)
                    }
                } else if (selectingVideo) {
                    LazyColumn(modifier = Modifier.height(300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(videos) { video ->
                            val episodeText = buildString {
                                if (video.season != null) append("S${video.season} ")
                                if (video.episode != null) append("E${video.episode} · ")
                                append(video.title?.takeIf { it.isNotBlank() } ?: "Episode")
                            }
                            TextButton(
                                onClick = { onSelectVideo(video) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(episodeText, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else if (streams.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.height(320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(streams) { link ->
                            TextButton(
                                onClick = { onPlay(item, selectedVideo, link) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(link.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = "${link.source} · ${link.quality}p · ${link.type}",
                                        color = theme.TextMuted,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(message.orEmpty(), color = theme.TextMuted, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Row {
                if (selectedVideo != null && videos.isNotEmpty()) {
                    TextButton(onClick = onBackToVideos) { Text("Episodes") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
