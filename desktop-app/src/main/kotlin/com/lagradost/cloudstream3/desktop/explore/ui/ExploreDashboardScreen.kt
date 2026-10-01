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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.explore.viewmodel.ExploreViewModel
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.navigation.Config

@Composable
fun ExploreDashboardScreen(
    viewModel: ExploreViewModel,
    onNavigate: (Config) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val theme = LocalDesktopTheme.current

    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 30.dp),
        contentAlignment = androidx.compose.ui.Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 1180.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Explore", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = theme.TextPrimary)
                Text(
                    "Find something to watch across your catalogs, providers, and torrent index.",
                    fontSize = 14.sp,
                    color = theme.TextMuted,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Discover", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = theme.TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ExploreDestinationCard(
                        title = "Catalogs",
                        description = "Browse title shelves from your enabled add-ons.",
                        icon = Icons.Default.GridView,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(Config.ExploreCatalogs) },
                    )
                    ExploreDestinationCard(
                        title = "Torrent Search",
                        description = "Find a magnet and play it with Orbit.",
                        icon = Icons.Default.Link,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(Config.TorrentSearch) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ExploreDestinationCard(
                        title = "Genres",
                        description = "Browse titles by genre and topic.",
                        icon = Icons.Default.Category,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(Config.GenreBrowse) },
                    )
                    ExploreDestinationCard(
                        title = "Providers",
                        description = "Browse the providers available in Orbit.",
                        icon = Icons.Default.Extension,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(Config.ProviderBrowse) },
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Add-on catalogs", fontWeight = FontWeight.SemiBold, color = theme.TextPrimary)
                        Text(
                            text = when {
                                state.isInitializing -> "Checking enabled add-ons…"
                                state.allCatalogs.isEmpty() -> "No catalog shelves are available. The other Explore destinations still work."
                                else -> "${state.allCatalogs.size} catalog shelves available from enabled add-ons."
                            },
                            fontSize = 12.sp,
                            color = theme.TextMuted,
                        )
                    }
                    Spacer(Modifier.size(16.dp))
                    Button(onClick = { onNavigate(Config.ExploreCatalogs) }) {
                        Text("Open Catalogs")
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun ExploreDestinationCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val theme = LocalDesktopTheme.current
    Surface(
        onClick = onClick,
        modifier = modifier.height(148.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = theme.TextPrimary)
                Text(description, fontSize = 12.sp, color = theme.TextMuted)
            }
        }
    }
}
