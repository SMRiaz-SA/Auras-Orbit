package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TrackerSyncOutcome {
    SYNCED,
    SKIPPED,
    SIGN_IN_REQUIRED,
    RETRYABLE,
    PROVIDER_REJECTED,
}

data class TrackerSyncHealthState(
    val outcome: TrackerSyncOutcome,
    val detail: String,
    val timestamp: Long,
)

/** Small, token-free status record used by settings and the tracker library. */
object TrackerSyncHealth {
    private const val KEY_PREFIX = "tracker_sync_health_v1_"
    private val _states = MutableStateFlow<Map<String, TrackerSyncHealthState>>(emptyMap())
    val states: StateFlow<Map<String, TrackerSyncHealthState>> = _states.asStateFlow()

    init {
        reload()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            ProfileManager.activeProfile.collect { reload() }
        }
    }

    fun state(idPrefix: String): TrackerSyncHealthState? = _states.value[idPrefix]

    fun record(idPrefix: String, outcome: TrackerSyncOutcome, detail: String) {
        val value = TrackerSyncHealthState(outcome, detail.take(240), System.currentTimeMillis())
        DesktopDataStore.setProfileKey(KEY_PREFIX + idPrefix, value)
        _states.value = _states.value + (idPrefix to value)
    }

    fun reload() {
        val values = listOf("mal", "anilist", "simkl").mapNotNull { idPrefix ->
            DesktopDataStore.getProfileKey<TrackerSyncHealthState>(KEY_PREFIX + idPrefix)?.let { idPrefix to it }
        }.toMap()
        _states.value = values
    }
}
