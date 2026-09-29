package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ShortcutEntry(
    val keys: List<String>,
    val description: String,
    val context: String,
    val icon: ImageVector? = null,
)

data class ShortcutCategory(
    val title: String,
    val icon: ImageVector,
    val shortcuts: List<ShortcutEntry>,
)

@Composable
fun SettingsShortcutsScreen() {
    var searchQuery by remember { mutableStateOf("") }

    val categories = remember {
        listOf(
            ShortcutCategory(
                title = "Core System & Quick Actions",
                icon = Icons.Default.Bolt,
                shortcuts = listOf(
                    ShortcutEntry(listOf("Ctrl", "Shift", "C"), "Toggle Clean Mode", "Global", Icons.Default.AutoAwesome),
                    ShortcutEntry(listOf("F11"), "Toggle borderless fullscreen", "Global", Icons.Default.Fullscreen),
                    ShortcutEntry(listOf("Ctrl", "+"), "Zoom in by 10 percent", "Global", Icons.Default.ZoomIn),
                    ShortcutEntry(listOf("Ctrl", "-"), "Zoom out by 10 percent", "Global", Icons.Default.ZoomOut),
                    ShortcutEntry(listOf("Ctrl", "0"), "Reset UI zoom to 100 percent", "Global", Icons.Default.Refresh),
                    ShortcutEntry(listOf("F5"), "Refresh the current screen or feeds", "Global", Icons.Default.Refresh),
                    ShortcutEntry(listOf("Ctrl", "R"), "Refresh the current screen or feeds", "Global", Icons.Default.Sync),
                    ShortcutEntry(listOf("Ctrl", "O"), "Open a local video file", "Global", Icons.Default.FolderOpen),
                    ShortcutEntry(listOf("Ctrl", "U"), "Open the network stream dialog", "Global", Icons.Default.Link),
                    ShortcutEntry(listOf("F12"), "Toggle Developer Studio when developer mode is enabled", "Global", Icons.Default.Code),
                    ShortcutEntry(listOf("Esc"), "Close a supported overlay or exit fullscreen", "Global", Icons.Default.Close),
                    ShortcutEntry(listOf("Alt", "←"), "Leave the active Settings sub-screen", "Settings", Icons.Default.ArrowBack),
                    ShortcutEntry(listOf("F1"), "Open Help & Manual", "App", Icons.Default.Keyboard),
                ),
            ),
            ShortcutCategory(
                title = "Video Player & Playback Engine",
                icon = Icons.Default.PlayCircle,
                shortcuts = listOf(
                    ShortcutEntry(listOf("Space", "K"), "Play or pause", "Player", Icons.Default.PlayArrow),
                    ShortcutEntry(listOf("←", "→"), "Seek backward or forward 10 seconds", "Player", Icons.Default.FastForward),
                    ShortcutEntry(listOf("Shift", "←"), "Seek backward 2 seconds", "Player", Icons.Default.FastRewind),
                    ShortcutEntry(listOf("Shift", "→"), "Seek forward 2 seconds", "Player", Icons.Default.FastForward),
                    ShortcutEntry(listOf("↑", "↓"), "Increase or decrease volume by 5 percent", "Player", Icons.Default.VolumeUp),
                    ShortcutEntry(listOf("M"), "Mute or unmute", "Player", Icons.Default.VolumeOff),
                    ShortcutEntry(listOf("F"), "Toggle fullscreen", "Player", Icons.Default.Fullscreen),
                    ShortcutEntry(listOf("A"), "Toggle audio mode", "Player", Icons.Default.GraphicEq),
                    ShortcutEntry(listOf("P"), "Toggle picture-in-picture", "Player", Icons.Default.PictureInPicture),
                    ShortcutEntry(listOf("N"), "Play the next episode", "Player", Icons.Default.SkipNext),
                    ShortcutEntry(listOf("0–9"), "Seek to 0–90 percent of the video", "Player", Icons.Default.FastForward),
                    ShortcutEntry(listOf("Page Up", "Page Down"), "Previous or next chapter when chapters are available", "Player", Icons.Default.List),
                    ShortcutEntry(listOf("+", "]"), "Increase playback speed by 0.25x", "Player", Icons.Default.Speed),
                    ShortcutEntry(listOf("-", "["), "Decrease playback speed by 0.25x", "Player", Icons.Default.Speed),
                    ShortcutEntry(listOf("Backspace"), "Reset playback speed to 1.0x", "Player", Icons.Default.RestartAlt),
                    ShortcutEntry(listOf("C"), "Cycle subtitle tracks", "Player", Icons.Default.Subtitles),
                    ShortcutEntry(listOf("V"), "Show or hide subtitles", "Player", Icons.Default.Subtitles),
                    ShortcutEntry(listOf("Z", "X"), "Adjust subtitle delay", "Player", Icons.Default.Subtitles),
                    ShortcutEntry(listOf("Shift+S", "Ctrl+S"), "Capture a screenshot", "Player", Icons.Default.CameraAlt),
                    ShortcutEntry(listOf("S"), "Use the visible skip action when available", "Player", Icons.Default.FastForward),
                    ShortcutEntry(listOf("Shift+D", "Shift+I"), "Toggle playback statistics", "Player", Icons.Default.Analytics),
                    ShortcutEntry(listOf("H", "F1", "?"), "Open the player shortcut panel", "Player", Icons.Default.Keyboard),
                    ShortcutEntry(listOf("Esc"), "Close player panels", "Player", Icons.Default.Close),
                ),
            ),
        )
    }

    val filteredCategories = remember(searchQuery, categories) {
        if (searchQuery.isBlank()) {
            categories
        } else {
            val q = searchQuery.trim().lowercase()
            categories.mapNotNull { cat ->
                val matching = cat.shortcuts.filter {
                    it.description.lowercase().contains(q) ||
                        it.context.lowercase().contains(q) ||
                        it.keys.any { k -> k.lowercase().contains(q) }
                }
                if (matching.isNotEmpty()) cat.copy(shortcuts = matching) else null
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Hero Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Keyboard Shortcuts & Controls",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Reference for the global app commands and the embedded player's current keyboard commands.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Search Filter Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Filter shortcuts (e.g. clean mode, seek, volume, zoom, screenshot)...") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
        )

        // Shortcuts List
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            filteredCategories.forEach { category ->
                item(key = category.title) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = category.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = category.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "(${category.shortcuts.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                items(category.shortcuts, key = { "${category.title}_${it.description}" }) { entry ->
                    ShortcutRowCard(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun ShortcutRowCard(entry: ShortcutEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (entry.icon != null) {
                    Icon(
                        imageVector = entry.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp),
                    )
                }

                Column {
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = entry.context,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Keycap Badge Row
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entry.keys.forEachIndexed { index, key ->
                    KeycapBadge(key = key)
                    if (index < entry.keys.size - 1) {
                        Text(
                            text = "+",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun KeycapBadge(key: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = 2.dp,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 12.sp,
        )
    }
}
