package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.TrackerAccountAccess
import com.lagradost.cloudstream3.syncproviders.TrackerAuthResult
import com.lagradost.cloudstream3.ui.SyncWatchType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class TrackerDetailsStatus(
    val providerName: String,
    val accountName: String,
    val status: SyncWatchType? = null,
    val score: Double? = null,
    val progress: Int? = null,
    val total: Int? = null,
    val note: String? = null,
)

@Composable
fun TrackerDetailsSection(
    data: LoadResponse,
    onNavigate: (Config) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accounts by AccountManager.accountsFlow.collectAsState()
    var rows by remember(data.url) { mutableStateOf<List<TrackerDetailsStatus>>(emptyList()) }
    var loading by remember(data.url) { mutableStateOf(false) }

    LaunchedEffect(data.url, accounts) {
        val trackers = listOf(AccountManager.malApi, AccountManager.aniListApi, AccountManager.simklApi)
            .mapNotNull { api -> accounts[api.idPrefix]?.firstOrNull()?.let { api to it } }
        if (trackers.isEmpty()) {
            rows = emptyList()
            return@LaunchedEffect
        }
        loading = true
        rows = withContext(Dispatchers.IO) {
            trackers.map { (api, saved) -> loadTrackerStatus(api, saved, data) }
        }
        loading = false
    }

    if (accounts.values.none { it.isNotEmpty() }) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tracker status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Each service is read independently. Remote values do not change local watch history.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (loading) {
                Text("Reading connected trackers…", style = MaterialTheme.typography.bodySmall)
            } else {
                rows.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(row.providerName, fontWeight = FontWeight.SemiBold)
                            Text("Account: ${row.accountName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                row.note ?: listOfNotNull(
                                    row.status?.label(),
                                    row.score?.let { "Score %.1f/10".format(java.util.Locale.ROOT, it) },
                                    row.progress?.let { "Progress $it/${row.total ?: "?"}" },
                                ).joinToString(" · ").ifBlank { "No list entry" },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (row.note == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = { onNavigate(Config.TrackerLibrary) }) {
                            Text("Edit")
                        }
                    }
                }
            }
        }
    }
}

private suspend fun loadTrackerStatus(
    api: SyncAPI,
    saved: AuthData,
    data: LoadResponse,
): TrackerDetailsStatus {
    val base = TrackerDetailsStatus(api.name, saved.user.name ?: "Connected account")
    val auth = when (val result = TrackerAccountAccess.current(api, saved)) {
        is TrackerAuthResult.Ready -> result.account
        TrackerAuthResult.Missing -> return base.copy(note = "Sign in required")
        TrackerAuthResult.ReauthorizationRequired -> return base.copy(note = "Reauthorization required in Settings")
    }
    val exactId = data.syncData[api.idPrefix]?.takeIf(String::isNotBlank)
    val candidates = if (exactId != null) {
        listOf(exactId)
    } else {
        api.search(auth, data.name).orEmpty()
            .filter { result ->
                val sameType = when (data.type) {
                    TvType.Anime, TvType.AnimeMovie -> result.mediaType == null || result.mediaType == SyncAPI.SyncMediaType.ANIME
                    TvType.Movie -> result.mediaType == null || result.mediaType == SyncAPI.SyncMediaType.MOVIE
                    TvType.TvSeries -> result.mediaType == null || result.mediaType == SyncAPI.SyncMediaType.SHOW
                    else -> true
                }
                sameType && (result.name.equals(data.name, ignoreCase = true) || result.alternativeNames.any { it.equals(data.name, ignoreCase = true) })
            }
            .distinctBy { it.syncId }
            .map { it.syncId }
    }
    val id = candidates.singleOrNull() ?: return base.copy(note = "No unambiguous ${api.name} title match")
    return try {
        val status = api.status(auth, id) ?: return base.copy(note = "Could not read status")
        base.copy(
            status = status.status,
            score = status.score?.toDouble(10),
            progress = status.watchedEpisodes,
            total = status.maxEpisodes,
        )
    } catch (_: Exception) {
        base.copy(note = "Tracker status unavailable")
    }
}

private fun SyncWatchType.label(): String = when (this) {
    SyncWatchType.NONE -> "Not on list"
    SyncWatchType.WATCHING -> "Watching"
    SyncWatchType.COMPLETED -> "Completed"
    SyncWatchType.ON_HOLD -> "On Hold"
    SyncWatchType.DROPPED -> "Dropped"
    SyncWatchType.PLAN_TO_WATCH -> "Plan to Watch"
}
