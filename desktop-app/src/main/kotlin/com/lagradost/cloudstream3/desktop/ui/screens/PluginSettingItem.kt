package com.lagradost.cloudstream3.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lagradost.common.storage.PluginSettingSchema
import java.io.File

@Composable
fun PluginSettingItem(
    schema: PluginSettingSchema,
    currentValue: Any?,
    pluginName: String,
    jarFile: File?,
    onValueChanged: (Any?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        val options = schema.options
        val settingAction = schema.action
        if (settingAction != null) {
            val febboxConnected = schema.controlType == "FebboxLogin" &&
                currentValue?.toString()?.startsWith("ui=", ignoreCase = false) == true
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = getSettingTitle(schema),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val summary = if (febboxConnected) {
                    "Connected to Febbox. Sign in again to use a different account."
                } else {
                    getSettingSummary(schema)
                }
                summary?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                Button(
                    onClick = { settingAction.invoke() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (febboxConnected) "Reconnect Febbox" else getSettingTitle(schema))
                }
            }
        } else if (schema.type != "StringSet" && options != null && options.isNotEmpty()) {
            val currentValueStr = currentValue?.toString() ?: schema.defaultValue?.toString() ?: options.values.firstOrNull() ?: ""
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = getSettingTitle(schema),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val desc = getSettingSummary(schema).orEmpty()
                if (desc.isNotEmpty()) {
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                var expanded by remember(schema.key) { mutableStateOf(false) }
                val selectedLabel = options.entries.firstOrNull { it.value == currentValueStr }?.key ?: currentValueStr
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedLabel, modifier = Modifier.weight(1f))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        options.forEach { (label, value) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onValueChanged(value)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        } else {
            val isBooleanLike = schema.type == "Boolean" ||
                schema.defaultValue is Boolean ||
                schema.defaultValue == "true" || schema.defaultValue == "false" ||
                currentValue is Boolean ||
                currentValue == "true" || currentValue == "false" ||
                schema.controlType == "SwitchPreferenceCompat" || schema.controlType == "CheckBoxPreference"

            if (isBooleanLike) {
                val defaultVal = schema.defaultValue
                val isChecked = when (currentValue) {
                    is Boolean -> currentValue
                    is String -> currentValue.equals("true", ignoreCase = true)
                    else -> when (defaultVal) {
                        is Boolean -> defaultVal
                        is String -> defaultVal.equals("true", ignoreCase = true)
                        else -> false
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(
                            text = getSettingTitle(schema),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val desc = getSettingSummary(schema).orEmpty()
                        if (desc.isNotEmpty()) {
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = isChecked,
                        onCheckedChange = { newValue ->
                            val finalValue: Any = if (schema.type == "Boolean" || schema.defaultValue is Boolean || currentValue is Boolean) newValue else newValue.toString()
                            onValueChanged(finalValue)
                        },
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = getSettingTitle(schema),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val desc = getSettingSummary(schema).orEmpty()
                    if (desc.isNotEmpty()) {
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }

                    when (schema.type) {
                        "Int", "Long", "Float" -> {
                            val minimumNumber = schema.minimumNumber
                            val maximumNumber = schema.maximumNumber
                            var numericDraft by remember(schema.key) {
                                mutableStateOf(currentValue?.toString() ?: schema.defaultValue?.toString() ?: "")
                            }
                            val parsedNumber = numericDraft.toDoubleOrNull()
                            val outsideBounds = parsedNumber != null &&
                                (
                                    (minimumNumber != null && parsedNumber < minimumNumber) ||
                                        (maximumNumber != null && parsedNumber > maximumNumber)
                                    )
                            OutlinedTextField(
                                value = numericDraft,
                                onValueChange = { newValue ->
                                    numericDraft = newValue
                                    val parsed = when (schema.type) {
                                        "Int" -> newValue.toIntOrNull()
                                        "Long" -> newValue.toLongOrNull()
                                        "Float" -> newValue.toFloatOrNull()
                                        else -> newValue
                                    }
                                    val numericValue = (parsed as? Number)?.toDouble()
                                    val withinBounds = numericValue != null &&
                                        (minimumNumber == null || numericValue >= minimumNumber) &&
                                        (maximumNumber == null || numericValue <= maximumNumber)
                                    if (parsed != null && (minimumNumber == null && maximumNumber == null || withinBounds)) {
                                        onValueChanged(parsed)
                                    }
                                },
                                isError = outsideBounds,
                                supportingText = if (minimumNumber != null || maximumNumber != null) {
                                    {
                                        val range = when {
                                            minimumNumber != null && maximumNumber != null ->
                                                "Enter ${minimumNumber.toInt()}–${maximumNumber.toInt()}"
                                            minimumNumber != null -> "Enter at least ${minimumNumber.toInt()}"
                                            else -> "Enter no more than ${maximumNumber?.toInt()}"
                                        }
                                        Text(range)
                                    }
                                } else {
                                    null
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        "StringSet" -> {
                            val usesPluginProviders = schema.controlType == "ProviderSelection" ||
                                schema.controlType == "DisabledProviderSelection"
                            val isProviderSelection = usesPluginProviders ||
                                schema.controlType == "MultiSelectListPreference" ||
                                !schema.options.isNullOrEmpty()

                            // Provider sets use an explicit adapter control type. Never infer a
                            // provider list just because a key happens to contain "source".
                            val memorySources = remember(pluginName, jarFile, usesPluginProviders, schema.controlType) {
                                // DisabledProviderSelection is a plugin-internal source list, not the
                                // plugin's outer MainAPI providers. It must use declared source IDs.
                                if (!usesPluginProviders || schema.controlType == "DisabledProviderSelection") return@remember emptyList()
                                val jarName = jarFile?.name
                                val jarBaseName = jarFile?.nameWithoutExtension
                                val jarPath = jarFile?.absolutePath

                                val apis = com.lagradost.cloudstream3.APIHolder.apis
                                    .filter { api ->
                                        val src = api.sourcePlugin
                                        src == pluginName || src == jarName || src == jarBaseName || src == jarPath
                                    }
                                    .map { it.name }
                                    .ifEmpty {
                                        synchronized(com.lagradost.cloudstream3.APIHolder.allProviders) {
                                            com.lagradost.cloudstream3.APIHolder.allProviders
                                                .filter { api ->
                                                    val src = api.sourcePlugin
                                                    src == pluginName || src == jarName || src == jarBaseName || src == jarPath
                                                }
                                                .map { it.name }
                                        }
                                    }
                                    .sorted()
                                apis
                            }

                            val defaultSet = (schema.defaultValue as? Set<*>)?.map { it.toString() }?.toSet()
                                ?: (schema.defaultValue as? List<*>)?.map { it.toString() }?.toSet()
                                ?: emptySet()
                            val currentSet = (currentValue as? Set<*>)?.map { it.toString() }?.toSet()
                                ?: (currentValue as? List<*>)?.map { it.toString() }?.toSet()
                            val resolvedSet = currentSet ?: defaultSet

                            val declaredOptions = schema.options?.entries?.map { it.key to it.value }.orEmpty()
                            val optionsList = declaredOptions.ifEmpty {
                                (
                                    memorySources.takeIf { it.isNotEmpty() }
                                        ?: (defaultSet + (currentSet ?: emptySet())).toList().sorted()
                                    )
                                    .map { it to it }
                            }

                            if (schema.controlType == "DisabledProviderSelection" && optionsList.isEmpty()) {
                                Text(
                                    text = getSettingSummary(schema) ?: "This extension did not expose its internal source list.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (isProviderSelection && optionsList.isNotEmpty()) {
                                val isDisablingKey = schema.controlType == "DisabledProviderSelection"
                                val initialSelection = currentSet ?: defaultSet

                                var searchQuery by remember { mutableStateOf("") }
                                val filteredOptions = if (searchQuery.isBlank()) {
                                    optionsList
                                } else {
                                    optionsList.filter { it.first.contains(searchQuery, ignoreCase = true) }
                                }

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    // Search bar if more than 6 providers
                                    if (optionsList.size > 6) {
                                        OutlinedTextField(
                                            value = searchQuery,
                                            onValueChange = { searchQuery = it },
                                            placeholder = { Text("Filter ${optionsList.size} options...", style = MaterialTheme.typography.bodySmall) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Search,
                                                    contentDescription = "Search",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                            ),
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                if (isDisablingKey) {
                                                    onValueChanged(initialSelection - optionsList.map { it.second }.toSet())
                                                } else {
                                                    onValueChanged(initialSelection + optionsList.map { it.second })
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) { Text(if (usesPluginProviders) "Enable All" else "Select All") }
                                        OutlinedButton(
                                            onClick = {
                                                if (isDisablingKey) {
                                                    onValueChanged(initialSelection + optionsList.map { it.second })
                                                } else {
                                                    onValueChanged(initialSelection - optionsList.map { it.second }.toSet())
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) { Text(if (usesPluginProviders) "Disable All" else "Clear Selection") }
                                    }

                                    filteredOptions.forEach { option ->
                                        val isEnabled = if (isDisablingKey) {
                                            !resolvedSet.contains(option.second)
                                        } else {
                                            initialSelection.contains(option.second)
                                        }

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = option.first,
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Switch(
                                                checked = isEnabled,
                                                onCheckedChange = { checked ->
                                                    val newSet = initialSelection.toMutableSet()
                                                    if (isDisablingKey) {
                                                        if (checked) newSet.remove(option.second) else newSet.add(option.second)
                                                    } else {
                                                        if (checked) newSet.add(option.second) else newSet.remove(option.second)
                                                    }
                                                    onValueChanged(newSet)
                                                },
                                            )
                                        }
                                    }
                                }
                            } else {
                                val stringSetText = (currentSet ?: defaultSet).sorted().joinToString(", ")
                                OutlinedTextField(
                                    value = stringSetText,
                                    onValueChange = { newValue ->
                                        onValueChanged(
                                            newValue.split(',', '\n')
                                                .map(String::trim)
                                                .filter(String::isNotEmpty)
                                                .toSet(),
                                        )
                                    },
                                    label = { Text("Values (comma or line separated)") },
                                    minLines = 2,
                                    maxLines = 4,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        else -> {
                            // String / Sensitive key handling with password reveal
                            val isSensitive = schema.key.lowercase().let { k ->
                                k.contains("token") || k.contains("password") || k.contains("secret") ||
                                    k.contains("auth") || k.contains("api_key") || k.contains("apikey")
                            }
                            var showPassword by remember { mutableStateOf(false) }

                            OutlinedTextField(
                                value = currentValue?.toString() ?: schema.defaultValue?.toString() ?: "",
                                onValueChange = { newValue ->
                                    onValueChanged(newValue)
                                },
                                visualTransformation = if (isSensitive && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
                                trailingIcon = if (isSensitive) {
                                    {
                                        IconButton(onClick = { showPassword = !showPassword }) {
                                            Icon(
                                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = if (showPassword) "Hide" else "Show",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
