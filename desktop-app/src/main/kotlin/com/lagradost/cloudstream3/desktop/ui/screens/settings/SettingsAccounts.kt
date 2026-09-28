package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsAccounts(viewModel: SettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var containerCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }

    CompositionLocalProvider(
        LocalSettingsScrollState provides scrollState,
        LocalScrollContainerCoordinates provides containerCoordinates,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { containerCoordinates = it }
                .verticalScroll(scrollState)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            val profiles by com.lagradost.cloudstream3.desktop.profile.ProfileManager.profiles.collectAsState()
            val activeProfile by com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfile.collectAsState()
            val isPickerOnStartup by com.lagradost.cloudstream3.desktop.profile.ProfileManager.isPickerOnStartup.collectAsState()
            var editingProfile by remember { mutableStateOf<com.lagradost.cloudstream3.desktop.profile.Profile?>(null) }
            var isCreatingNew by remember { mutableStateOf(false) }

            if (editingProfile != null || isCreatingNew) {
                com.lagradost.cloudstream3.desktop.ui.screens.profile.ProfileEditDialog(
                    profile = editingProfile,
                    canDelete = profiles.size > 1,
                    onDismiss = {
                        editingProfile = null
                        isCreatingNew = false
                    },
                    onSave = { name, colorIndex, customAvatar, pin, isKids ->
                        // The dialog calls onDismiss immediately after onSave. Capture its
                        // mode and target before launching so the background coroutine does
                        // not observe the cleared Compose state and silently skip the save.
                        val shouldCreateProfile = isCreatingNew
                        val profileToEdit = editingProfile
                        scope.launch(Dispatchers.IO) {
                            if (shouldCreateProfile) {
                                com.lagradost.cloudstream3.desktop.profile.ProfileManager.createProfile(name, colorIndex, customAvatar, pin, isKids)
                            } else if (profileToEdit != null) {
                                com.lagradost.cloudstream3.desktop.profile.ProfileManager.updateProfile(
                                    profileToEdit.copy(
                                        name = name,
                                        avatarColorIndex = colorIndex,
                                        customAvatarPath = customAvatar,
                                        pinCode = pin,
                                        isKids = isKids,
                                    ),
                                )
                            }
                        }
                    },
                    onDelete = {
                        scope.launch(Dispatchers.IO) {
                            editingProfile?.let { com.lagradost.cloudstream3.desktop.profile.ProfileManager.deleteProfile(it.id) }
                        }
                    },
                )
            }

            SettingsGroupCard(title = "User Profiles") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Profile List Items
                    profiles.forEach { profile ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                com.lagradost.cloudstream3.desktop.profile.ProfileAvatar(
                                    profile = profile,
                                    size = 36.dp,
                                    shape = RoundedCornerShape(8.dp),
                                    fontSize = 16.sp,
                                )

                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = profile.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = if (profile.id == activeProfile.id) FontWeight.Bold else FontWeight.Medium,
                                            color = Color.White,
                                        )
                                        if (profile.id == activeProfile.id) {
                                            Surface(
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                            ) {
                                                Text(
                                                    "Active",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                    val details = buildList {
                                        if (profile.isKids) add("Kids Profile")
                                        if (profile.hasPin) add("PIN Protected")
                                    }.joinToString(" • ")
                                    if (details.isNotEmpty()) {
                                        Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (profile.id != activeProfile.id) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                com.lagradost.cloudstream3.desktop.profile.ProfileManager.switchProfile(profile.id)
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        modifier = Modifier.height(32.dp),
                                    ) {
                                        Text("Switch", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                IconButton(
                                    onClick = { editingProfile = profile },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(androidx.compose.material.icons.Icons.Default.Edit, contentDescription = "Edit Profile", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    // Actions & Preferences
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Button(onClick = { isCreatingNew = true }) {
                            Text("+ Create New Profile")
                        }

                        val autoSignIn by com.lagradost.cloudstream3.desktop.profile.ProfileManager.autoSignIn.collectAsState()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Auto sign-in on launch", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = autoSignIn,
                                onCheckedChange = { checked ->
                                    scope.launch(Dispatchers.IO) {
                                        com.lagradost.cloudstream3.desktop.profile.ProfileManager.setAutoSignIn(checked)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            SettingsGroupCard(title = "Discord Rich Presence") {
                MviSettingsToggle(
                    key = DesktopDataStore.PREF_DISCORD_RPC_ENABLED,
                    label = "Enable Discord Rich Presence",
                    subtitle = "Display your current playback and browsing status on your Discord profile.",
                    uiState = uiState,
                    onEvent = viewModel::onEvent,
                    defaultValue = false,
                )

                val discordRpcEnabled = uiState.booleanSettings[DesktopDataStore.PREF_DISCORD_RPC_ENABLED] ?: (DesktopDataStore.getKey<Boolean>(DesktopDataStore.PREF_DISCORD_RPC_ENABLED) ?: false)

                if (discordRpcEnabled) {
                    MviSettingsToggle(
                        key = DesktopDataStore.PREF_DISCORD_RPC_SHOW_TITLE,
                        label = "Show Media & Episode Titles",
                        subtitle = "Display the specific movie, series name, and episode number.",
                        uiState = uiState,
                        onEvent = viewModel::onEvent,
                        defaultValue = true,
                    )

                    MviSettingsToggle(
                        key = DesktopDataStore.PREF_DISCORD_RPC_SHOW_PROGRESS,
                        label = "Show Playback Progress Bar",
                        subtitle = "Display a live countdown progress bar on Discord while playing video.",
                        uiState = uiState,
                        onEvent = viewModel::onEvent,
                        defaultValue = true,
                    )

                    MviSettingsToggle(
                        key = DesktopDataStore.PREF_DISCORD_RPC_SHOW_BROWSING,
                        label = "Show Browsing Activity",
                        subtitle = "Display when browsing menus and catalogs when video is not playing.",
                        uiState = uiState,
                        onEvent = viewModel::onEvent,
                        defaultValue = true,
                    )
                }
            }
        }
    }
}
