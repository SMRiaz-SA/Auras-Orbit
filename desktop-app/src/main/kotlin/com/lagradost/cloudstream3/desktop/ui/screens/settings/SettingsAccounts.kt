package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.SettingsUiEvent
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthAPI
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.TrackerClientConfig
import com.lagradost.cloudstream3.syncproviders.TrackerSyncHealth
import com.lagradost.cloudstream3.syncproviders.TrackerSyncOutcome
import com.lagradost.cloudstream3.syncproviders.TrackerSyncPreferences
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.desktop.sync.OAuthLocalServer
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

@Composable
fun SettingsAccounts(viewModel: SettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var configuringTracker by remember { mutableStateOf<SyncAPI?>(null) }
    var pendingTrackerLogin by remember { mutableStateOf<PendingTrackerLogin?>(null) }
    var trackerLoginBusy by remember { mutableStateOf(false) }
    var trackerLoginMessage by remember { mutableStateOf<String?>(null) }
    var trackerAccountMessage by remember { mutableStateOf<String?>(null) }
    var trackerRetryBusy by remember { mutableStateOf(false) }
    val cachedAccounts by AccountManager.accountsFlow.collectAsState()
    val syncHealth by TrackerSyncHealth.states.collectAsState()

    fun startTrackerLogin(api: SyncAPI) {
        trackerLoginMessage = null
        trackerAccountMessage = null
        scope.launch {
            trackerLoginBusy = true
            try {
                if (api is SimklApi) {
                    val verifier = AuthAPI.generateCodeVerifier()
                    val state = AuthAPI.generateCodeVerifier()
                    var payload: String? = null
                    val callback = OAuthLocalServer.authenticate(
                        authorizationUrl = { redirectUri ->
                            val page = api.loginRequest(redirectUri, state, verifier)
                                ?: error("Set a Simkl client ID first")
                            payload = page.payload
                            page.url
                        },
                        expectedState = state,
                        expectedIssuer = "https://simkl.com",
                    ) ?: error("Sign-in was cancelled or timed out")
                    val token = api.login(callback, payload) ?: error("Simkl sign-in did not return a token")
                    val user = api.user(token) ?: error("Could not verify the Simkl account")
                    AccountManager.updateAccounts(api.idPrefix, arrayOf(AuthData(user, token)))
                } else {
                    val page = api.loginRequest() ?: error("Set a ${api.name} client ID first")
                    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        error("Opening a browser is not supported on this system")
                    }
                    Desktop.getDesktop().browse(URI(page.url))
                    pendingTrackerLogin = PendingTrackerLogin(api, page.payload, page.url)
                }
            } catch (error: Exception) {
                trackerLoginMessage = error.message ?: "Sign-in failed"
            } finally {
                trackerLoginBusy = false
            }
        }
    }

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
                        scope.launch(Dispatchers.IO) {
                            if (isCreatingNew) {
                                com.lagradost.cloudstream3.desktop.profile.ProfileManager.createProfile(name, colorIndex, customAvatar, pin, isKids)
                            } else if (editingProfile != null) {
                                com.lagradost.cloudstream3.desktop.profile.ProfileManager.updateProfile(
                                    editingProfile!!.copy(
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

            SettingsGroupCard(title = "Trackers & Integrations") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Optional: Auras Orbit keeps your watch history and playback progress locally. Connect a tracker only if you want to sync your list and supported playback progress with that service. Credentials are stored separately for each profile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    listOf(AccountManager.malApi, AccountManager.aniListApi, AccountManager.simklApi).forEach { api ->
                        val account = cachedAccounts[api.idPrefix]?.firstOrNull()
                        val clientIdSet = trackerClientId(api).isNotBlank()
                        val expired = account?.token?.isAccessTokenExpired() == true &&
                            account.token.isRefreshTokenExpired()
                        var automaticSyncEnabled by remember(api.idPrefix) {
                            mutableStateOf(TrackerSyncPreferences.isAutomaticSyncEnabled(api.idPrefix))
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(api.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = account?.user?.name?.let { "Connected as $it" }
                                                ?: if (clientIdSet) "Client ID configured" else "Set up a client ID to connect",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (account != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { configuringTracker = api }) { Text("Configure") }
                                        if (account == null) {
                                            Button(
                                                enabled = clientIdSet && !trackerLoginBusy,
                                                onClick = { startTrackerLogin(api) },
                                            ) {
                                                Text(if (trackerLoginBusy) "Signing in…" else "Connect")
                                            }
                                        } else {
                                            Button(
                                                enabled = clientIdSet && !trackerLoginBusy,
                                                onClick = { startTrackerLogin(api) },
                                            ) { Text(if (expired) "Reauthorize" else "Reconnect") }
                                            OutlinedButton(
                                                onClick = {
                                                    trackerAccountMessage = null
                                                    scope.launch(Dispatchers.IO) {
                                                        val revokeRequested = try {
                                                            api.invalidateToken(account.token)
                                                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                            throw cancelled
                                                        } catch (_: Exception) {
                                                            false
                                                        }
                                                        try {
                                                            AccountManager.updateAccounts(api.idPrefix, emptyArray())
                                                            trackerAccountMessage = when {
                                                                api is SimklApi && revokeRequested ->
                                                                    "Local credentials cleared. A Simkl revoke request was sent; Simkl intentionally does not confirm whether a grant was active."
                                                                api is SimklApi ->
                                                                    "Local credentials cleared, but Simkl could not be reached to request revocation. Revoke this app in Simkl Connected Apps settings."
                                                                else ->
                                                                    "Local credentials cleared. Remote access remains active until revoked in ${api.name} account settings."
                                                            }
                                                        } catch (_: Exception) {
                                                            trackerLoginMessage = "Could not clear the saved tracker credentials."
                                                        }
                                                    }
                                                },
                                            ) { Text("Remove account") }
                                        }
                                    }
                                }
                                if (account != null) {
                                    Text(
                                        if (expired) {
                                            "This saved session has expired. Reauthorize to replace it; cancelling or failing leaves the existing account record unchanged."
                                        } else if (api is SimklApi) {
                                            "Disconnect requests remote token revocation and clears the protected local credentials. Simkl does not confirm whether a grant was active."
                                        } else {
                                            "This clears the protected local credentials; revoke the app separately in ${api.name} account settings to cancel remote access."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Automatic playback sync", style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "Send watched items only after the local watched threshold; local history is never imported from a tracker.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Switch(
                                            checked = automaticSyncEnabled,
                                            onCheckedChange = { enabled ->
                                                automaticSyncEnabled = enabled
                                                TrackerSyncPreferences.setAutomaticSyncEnabled(api.idPrefix, enabled)
                                            },
                                        )
                                    }
                                    syncHealth[api.idPrefix]?.let { health ->
                                        val healthColor = if (health.outcome == TrackerSyncOutcome.SYNCED) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        Text(
                                            "Last sync: ${health.outcome.name.lowercase().replace('_', ' ')} — ${health.detail}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = healthColor,
                                        )
                                        if (health.outcome == TrackerSyncOutcome.RETRYABLE || health.outcome == TrackerSyncOutcome.PROVIDER_REJECTED) {
                                            TextButton(
                                                enabled = !trackerRetryBusy,
                                                onClick = {
                                                    scope.launch {
                                                        trackerRetryBusy = true
                                                        try {
                                                            com.lagradost.cloudstream3.desktop.ui.screens.player.TrackerPlaybackSyncCoordinator.retryLast()
                                                        } finally {
                                                            trackerRetryBusy = false
                                                        }
                                                    }
                                                },
                                            ) { Text(if (trackerRetryBusy) "Retrying…" else "Retry last playback sync") }
                                        }
                                    }
                                }
                                if (trackerLoginBusy && api.idPrefix == "simkl") {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text("Finish sign-in in the browser window…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    trackerLoginMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    trackerAccountMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
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

    configuringTracker?.let { api ->
        TrackerClientIdDialog(
            api = api,
            currentValue = trackerClientId(api),
            onDismiss = { configuringTracker = null },
            onSave = { value ->
                saveTrackerClientId(api, value)
                configuringTracker = null
            },
        )
    }
    pendingTrackerLogin?.let { pending ->
        TrackerRedirectDialog(
            api = pending.api,
            busy = trackerLoginBusy,
            errorMessage = trackerLoginMessage,
            onOpenSignIn = {
                runCatching { Desktop.getDesktop().browse(URI(pending.authorizationUrl)) }
                    .onFailure { trackerLoginMessage = "Could not open the browser. Check your system browser and retry." }
            },
            onDismiss = { pendingTrackerLogin = null },
            onSubmit = { redirect ->
                trackerLoginMessage = null
                scope.launch {
                    trackerLoginBusy = true
                    try {
                        val token = pending.api.login(redirect, pending.payload)
                            ?: error("The service did not accept this sign-in response")
                        val user = pending.api.user(token)
                            ?: error("The service did not return a valid account")
                        AccountManager.updateAccounts(pending.api.idPrefix, arrayOf(AuthData(user, token)))
                        pendingTrackerLogin = null
                    } catch (_: Exception) {
                        trackerLoginMessage = "Sign-in could not be completed. Check the pasted redirect URL and client ID, then try again."
                    } finally {
                        trackerLoginBusy = false
                    }
                }
            },
        )
    }
}

private data class PendingTrackerLogin(val api: SyncAPI, val payload: String?, val authorizationUrl: String)

@Composable
private fun TrackerClientIdDialog(
    api: SyncAPI,
    currentValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(api.idPrefix) { mutableStateOf(currentValue) }
    CloudstreamAlertDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = { Text("Configure ${api.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Create an OAuth client for this app, then paste its public client ID below. No client secret is needed.")
                Text(
                    when (api.idPrefix) {
                        "anilist" -> "For AniList, set the app redirect URL to https://anilist.co/api/v2/oauth/pin."
                        "simkl" -> "For Simkl AUTH V2, choose Mobile, desktop & browser apps and register http://127.0.0.1/oauth/callback without a port. A free local port is selected for each sign-in."
                        else -> "For MyAnimeList, register a redirect URL in its developer settings. After approval, copy the full returned address-bar URL back into Auras Orbit."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Client ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(api.createAccountUrl.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    api.createAccountUrl?.let { runCatching { Desktop.getDesktop().browse(URI(it)) } }
                }) { Text("Open developer settings") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun TrackerRedirectDialog(
    api: SyncAPI,
    busy: Boolean,
    errorMessage: String?,
    onOpenSignIn: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var redirect by remember(api.idPrefix) { mutableStateOf("") }
    CloudstreamAlertDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = { Text("Finish ${api.name} sign-in") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (api.idPrefix == "anilist") {
                        "Sign in in the browser. Paste the full redirected URL (or the access token from its address bar) below."
                    } else {
                        "Sign in in the browser, then copy the full redirected URL containing the authorization code and state. Paste it below."
                    },
                )
                OutlinedTextField(
                    value = redirect,
                    onValueChange = { redirect = it },
                    label = { Text("Redirect URL${if (api.idPrefix == "anilist") " or token" else ""}") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (!errorMessage.isNullOrBlank()) {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onOpenSignIn, enabled = !busy) { Text("Open sign-in page") }
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && redirect.isNotBlank(), onClick = { onSubmit(redirect.trim()) }) {
                Text(if (busy) "Checking…" else "Verify and connect")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun trackerClientId(api: SyncAPI): String = when (api.idPrefix) {
    "mal" -> TrackerClientConfig.malClientId()
    "anilist" -> TrackerClientConfig.aniListClientId()
    "simkl" -> TrackerClientConfig.simklClientId()
    else -> ""
}

private fun saveTrackerClientId(api: SyncAPI, value: String) {
    when (api.idPrefix) {
        "mal" -> TrackerClientConfig.setMalClientId(value)
        "anilist" -> TrackerClientConfig.setAniListClientId(value)
        "simkl" -> TrackerClientConfig.setSimklClientId(value)
    }
}
