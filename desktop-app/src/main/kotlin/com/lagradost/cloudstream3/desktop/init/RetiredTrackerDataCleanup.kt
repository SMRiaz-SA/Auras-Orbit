package com.lagradost.cloudstream3.desktop.init

import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore

/** Removes credentials and preferences left by the retired MAL, AniList, and Simkl integrations. */
internal object RetiredTrackerDataCleanup {
    private const val CLEANUP_KEY = "retired_external_tracker_data_cleaned_v1"
    private val retiredProviders = setOf("anilist", "mal", "simkl")

    internal fun isRetiredTrackerKey(key: String): Boolean =
        retiredProviders.any { provider ->
            key.startsWith("auth_tokens_${provider}_") ||
                key.startsWith("tracker_credentials_v1_auth_tokens_${provider}_") ||
                key == "tracker_client_id_$provider" ||
                key == "tracker_auto_sync_v1_$provider" ||
                key == "tracker_sync_health_v1_$provider" ||
                key.endsWith("/tracker_auto_sync_v1_$provider") ||
                key.endsWith("/tracker_sync_health_v1_$provider")
        }

    fun run() {
        try {
            if (DesktopDataStore.getKey<Boolean>(CLEANUP_KEY) == true) return

            val removedKeys = DesktopDataStore.getAllKeysWithPrefix("").filter(::isRetiredTrackerKey).toSet()
            DesktopDataStore.setKeys(mapOf(CLEANUP_KEY to true), removedKeys)
        } catch (error: Exception) {
            AppLogger.e("Could not remove saved data for retired external trackers", error)
        }
    }
}
