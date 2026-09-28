package com.lagradost.cloudstream3.syncproviders

import com.lagradost.common.storage.DesktopDataStore

/** Client IDs are public OAuth identifiers. User access/refresh tokens remain in the account store. */
object TrackerClientConfig {
    private const val ANILIST_KEY = "tracker_client_id_anilist"
    private const val SIMKL_KEY = "tracker_client_id_simkl"
    private const val MAL_KEY = "tracker_client_id_mal"

    fun aniListClientId(): String = configured(ANILIST_KEY, "ANILIST_CLIENT_ID", "anilist.clientId")
    fun simklClientId(): String = configured(SIMKL_KEY, "SIMKL_CLIENT_ID", "simkl.clientId")
    fun malClientId(): String = configured(MAL_KEY, "MAL_CLIENT_ID", "mal.clientId")

    fun setAniListClientId(value: String) = set(ANILIST_KEY, value)
    fun setSimklClientId(value: String) = set(SIMKL_KEY, value)
    fun setMalClientId(value: String) = set(MAL_KEY, value)

    private fun configured(key: String, environmentName: String, propertyName: String): String =
        DesktopDataStore.getKey<String>(key)?.trim()?.takeIf(String::isNotEmpty)
            ?: System.getProperty(propertyName)?.trim()?.takeIf(String::isNotEmpty)
            ?: System.getenv(environmentName)?.trim().orEmpty()

    private fun set(key: String, value: String) {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            DesktopDataStore.removeKey(key)
        } else {
            DesktopDataStore.setKey(key, normalized)
        }
    }
}
