package com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.screens.home.HomeContinueWatchingPosition
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsDropdownItem
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsGroupCard
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsSubScreen
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsToggleItem
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.theme.HeroBannerStyle

@Composable
fun SettingsHomeFeedScreen(onNavigateToSubScreen: (SettingsSubScreen) -> Unit = {}) {
    val heroEnabled by AppearanceConfig.heroEnabled.collectAsState()
    val showContinueWatching by AppearanceConfig.showContinueWatching.collectAsState()
    val heroAutoSlideDelaySeconds by AppearanceConfig.heroAutoSlideDelaySeconds.collectAsState()
    val heroBannerStyle by AppearanceConfig.heroBannerStyle.collectAsState()
    val sectionOrder by AppearanceConfig.homeFeedSectionOrder.collectAsState()
    val disabledSections by AppearanceConfig.homeFeedDisabledSections.collectAsState()
    val continueWatchingPosition by AppearanceConfig.homeContinueWatchingPosition.collectAsState()

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsGroupCard(title = "Continue Watching Feed") {
            SettingsToggleItem(
                label = "Show Continue Watching",
                subtitle = "Display in-progress movies and series in the Home feed",
                checked = showContinueWatching,
                onCheckedChange = { AppearanceConfig.setShowContinueWatching(it) },
            )
            if (showContinueWatching) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingsDropdownItem(
                    label = "Continue Watching position",
                    subtitle = "Place this shelf above or below the Hero Spotlight",
                    options = HomeContinueWatchingPosition.entries.map { it to it.displayName },
                    currentValue = continueWatchingPosition,
                    onSelectionChanged = AppearanceConfig::setHomeContinueWatchingPosition,
                )
            }
        }

        SettingsGroupCard(title = "Home Feed Sections") {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Arrange Home shelves",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = AppearanceConfig::resetHomeFeedSections) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                    Text("Reset")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                sectionOrder.forEachIndexed { index, key ->
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(key.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(key.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = { AppearanceConfig.moveHomeFeedSection(index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move ${key.displayName} up")
                        }
                        IconButton(
                            onClick = { AppearanceConfig.moveHomeFeedSection(index, index + 1) },
                            enabled = index < sectionOrder.lastIndex,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move ${key.displayName} down")
                        }
                        Switch(
                            checked = key !in disabledSections,
                            onCheckedChange = { AppearanceConfig.toggleHomeFeedSection(key, it) },
                        )
                    }
                    if (index < sectionOrder.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    }
                }
            }
        }

        SettingsGroupCard(title = "Hero Spotlight Carousel") {
            SettingsToggleItem(
                label = "Enable Hero Slider",
                subtitle = "Display featured trending media spotlight banner at the top of Home",
                checked = heroEnabled,
                onCheckedChange = { AppearanceConfig.setHeroEnabled(it) },
            )

            if (heroEnabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingsDropdownItem(
                    label = "Hero Banner Layout Style",
                    subtitle = "Choose between cinema peeking edges, fullscreen banner, and filmstrip",
                    options = listOf(
                        HeroBannerStyle.CINEMA_PEEKING to "Cinema (Peeking Rails)",
                        HeroBannerStyle.FULLSCREEN_IMMERSIVE to "Fullscreen Immersive",
                        HeroBannerStyle.THUMBNAIL_STRIP to "Thumbnail Filmstrip",
                    ),
                    currentValue = heroBannerStyle,
                    onSelectionChanged = { AppearanceConfig.setHeroBannerStyle(it) },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingsDropdownItem(
                    label = "Hero Auto-Slide Delay",
                    subtitle = "How long each spotlight item stays visible before transitioning",
                    options = listOf(
                        0 to "Off (Manual Only)",
                        4 to "4 seconds",
                        6 to "6 seconds",
                        8 to "8 seconds",
                        12 to "12 seconds",
                    ),
                    currentValue = heroAutoSlideDelaySeconds,
                    onSelectionChanged = { AppearanceConfig.setHeroAutoSlideDelaySeconds(it) },
                )
            }
        }
    }
}
