package com.lagradost.cloudstream3.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamCustomDialog
import com.lagradost.cloudstream3.desktop.ui.screens.settings.PluginSettingsUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.settings.PluginSettingsViewModel
import com.lagradost.common.storage.PluginSettingsSchemaRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginSettingsDialog(
    pluginName: String,
    prefName: String,
    jarFile: java.io.File? = null,
    adapterNotice: String? = null,
    onDismiss: () -> Unit,
) {
    val schemaUpdates by PluginSettingsSchemaRegistry.schemaUpdates.collectAsState()

    val viewModel = remember(pluginName, prefName) { PluginSettingsViewModel() }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.dispose()
        }
    }

    LaunchedEffect(viewModel, pluginName, prefName) {
        viewModel.onEvent(PluginSettingsUiEvent.OnInit(pluginName, prefName))
    }

    LaunchedEffect(viewModel, schemaUpdates) {
        viewModel.onEvent(PluginSettingsUiEvent.OnSchemaUpdated(schemaUpdates))
    }

    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.settings
    val currentValues = uiState.currentValues
    val hasChanged = uiState.hasChanged
    val isSaving = uiState.isSaving
    val isLoading = uiState.isLoading

    LaunchedEffect(uiState.closeWhenSaved) {
        if (uiState.closeWhenSaved) onDismiss()
    }

    CloudstreamCustomDialog(
        show = true,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .width(750.dp)
            .fillMaxHeight(0.85f),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$pluginName Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Desktop settings exposed by this extension",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Scrollable Content
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "Loading settings schema...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                        contentPadding = PaddingValues(vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (!adapterNotice.isNullOrBlank()) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = adapterNotice,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(14.dp),
                                    )
                                }
                            }
                        }

                        uiState.saveError?.let { message ->
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = message,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(14.dp),
                                    )
                                }
                            }
                        }

                        if (hasChanged) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = "Changes are staged. Apply & Close saves them to this plugin’s preferences, then reloads the plugin.",
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(14.dp),
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }

                        if (settings.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "This extension has no AndroidX preference screen or desktop adapter that Auras Orbit can display.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            val grouped = settings.groupBy { it.category?.takeIf(String::isNotBlank) ?: "Settings" }
                            val sortedCategories = grouped.keys.sortedWith(
                                compareBy<String> { category -> grouped[category].orEmpty().minOfOrNull { it.order } ?: 0 }
                                    .thenBy { it },
                            )

                            sortedCategories.forEach { category ->
                                item {
                                    Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                                        Text(
                                            text = category,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            letterSpacing = 1.sp,
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), thickness = 2.dp)
                                    }
                                }

                                items(grouped[category].orEmpty().sortedWith(compareBy({ it.order }, { it.title ?: it.key })), key = { it.key }) { schema ->
                                    val fullKey = if (schema.isGlobal) schema.key else schema.pluginPrefName + schema.key
                                    val currentValue = currentValues[fullKey]
                                    com.lagradost.cloudstream3.desktop.ui.screens.PluginSettingItem(
                                        schema = schema,
                                        currentValue = currentValue,
                                        pluginName = pluginName,
                                        jarFile = jarFile,
                                        onValueChanged = { newValue ->
                                            viewModel.onEvent(PluginSettingsUiEvent.OnSettingChanged(schema, newValue))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Bottom Footer Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = {
                        if (hasChanged) {
                            viewModel.onEvent(PluginSettingsUiEvent.OnApply)
                        } else {
                            onDismiss()
                        }
                    },
                    enabled = !isSaving,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isSaving || hasChanged) "Apply & Close" else "Close", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// Helpers for Logical Categorization and Visual Polish
private fun getCategory(key: String): String {
    val lower = key.lowercase()
    return when {
        lower.contains("stremio") || lower.contains("addon") || lower.contains("catalog") -> "Stremio & External Catalogs"
        lower.contains("key") || lower.contains("token") || lower.contains("auth") || lower.contains("api") || lower.contains("password") || lower.contains("username") -> "Accounts & API Integrations"
        lower.contains("provider") || lower.contains("source") || lower.contains("channel") || lower.contains("extractor") || lower.endsWith("enable") || lower.contains("concurrency") || lower.startsWith("scrape") -> "Scrapers & Engines"
        else -> "General Configurations"
    }
}

private fun getCategoryPriority(key: String): Int {
    return when (getCategory(key)) {
        "General Configurations" -> 0
        "Accounts & API Integrations" -> 1
        "Stremio & External Catalogs" -> 2
        "Scrapers & Engines" -> 3
        else -> 4
    }
}

internal fun getFriendlyName(key: String): String {
    var clean = key
    if (clean.startsWith("Provider")) {
        clean = clean.removePrefix("Provider")
    }

    val friendly = clean.replace("_", " ")
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .trim()
        .split(" ")
        .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

    return friendly
        .replace(" Saved Links", " Links Cache")
        .replace(" Concurrency", " Simultaneous Connections")
}

internal fun getDescription(key: String): String {
    val lower = key.lowercase()
    val friendly = getFriendlyName(key)
    return when {
        lower.contains("concurrency") || lower.contains("threads") ->
            "Maximum simultaneous connection threads for network operations."
        lower.contains("timeout") ->
            "Connection timeout duration in seconds."
        lower.contains("download") && lower.contains("enable") ->
            "Prioritize direct file download links over streaming playback."
        lower.contains("token") || lower.contains("key") || lower.contains("auth") || lower.contains("password") || lower.contains("secret") ->
            "Configure authentication credentials/API key for $friendly."
        lower.contains("host") || lower.contains("domain") || lower.contains("server") ->
            "Configure the API or media server endpoint address."
        lower.contains("addon") || lower.contains("catalog") || lower.contains("stremio") ->
            "Configure external streaming catalog endpoints."
        lower.contains("sub") || lower.contains("caption") ->
            "Configure subtitle provider and language integration."
        lower.contains("quality") || lower.contains("resolution") ->
            "Preferred media streaming quality."
        lower.contains("disabled") ->
            "Toggle individual sub-scrapers and data sources for this plugin."
        lower.contains("enabled") || key.startsWith("Provider") ->
            "Enable or disable this data source."
        else -> "Adjust configuration setting for $friendly."
    }
}

internal fun getSettingTitle(schema: com.lagradost.common.storage.PluginSettingSchema): String =
    schema.title?.takeIf(String::isNotBlank) ?: getFriendlyName(schema.key)

internal fun getSettingSummary(schema: com.lagradost.common.storage.PluginSettingSchema): String? =
    schema.summary?.takeIf(String::isNotBlank)
