package com.lagradost.cloudstream3.syncproviders

import com.lagradost.common.storage.DesktopDataStore

/** Profile-scoped opt-in controls for automatic playback writes. */
object TrackerSyncPreferences {
    private const val KEY_PREFIX = "tracker_auto_sync_v1_"

    fun isAutomaticSyncEnabled(idPrefix: String): Boolean =
        DesktopDataStore.getProfileKey<Boolean>(KEY_PREFIX + idPrefix) ?: true

    fun setAutomaticSyncEnabled(idPrefix: String, enabled: Boolean) {
        DesktopDataStore.setProfileKey(KEY_PREFIX + idPrefix, enabled)
    }
}
