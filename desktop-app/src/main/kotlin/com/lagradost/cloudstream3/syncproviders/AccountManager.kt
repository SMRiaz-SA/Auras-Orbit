package com.lagradost.cloudstream3.syncproviders

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.MalApi
import com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesStremioApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.syncproviders.providers.SubDlApi
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AccountManager {
    companion object {
        @JvmStatic
        val openSubtitlesStremioApi = OpenSubtitlesStremioApi()

        @JvmStatic
        val subDlApi = SubDlApi()

        @JvmStatic
        val aniListApi = AniListApi()

        @JvmStatic
        val malApi = MalApi()

        @JvmStatic
        val simklApi = SimklApi()

        val subtitleProviders = listOf(openSubtitlesStremioApi, subDlApi)
        val allApis = listOf(openSubtitlesStremioApi, subDlApi, aniListApi, malApi, simklApi)

        var cachedAccounts: MutableMap<String, Array<AuthData>> = mutableMapOf()

        private val _accountsFlow = MutableStateFlow<Map<String, Array<AuthData>>>(emptyMap())
        val accountsFlow: StateFlow<Map<String, Array<AuthData>>> = _accountsFlow.asStateFlow()

        const val ACCOUNT_TOKEN = "auth_tokens"

        private val protectedTrackerPrefixes = setOf("anilist", "mal", "simkl")
        private val credentialMapper = jacksonObjectMapper()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun storageKey(prefix: String): String {
            val profileId = ProfileManager.activeProfileId
            val profileKey = if (profileId == 0) "default" else "profile_$profileId"
            return "${ACCOUNT_TOKEN}_${prefix}_$profileKey"
        }

        private fun protectedStorageKey(prefix: String): String = "tracker_credentials_v1_${storageKey(prefix)}"

        fun accounts(prefix: String): Array<AuthData> {
            require(prefix != "NONE")
            if (prefix !in protectedTrackerPrefixes) {
                return com.lagradost.common.storage.DesktopDataStore.getKey<Array<AuthData>>(storageKey(prefix)) ?: arrayOf()
            }

            val encrypted = com.lagradost.common.storage.DesktopDataStore.getKey<String>(protectedStorageKey(prefix))
            if (encrypted != null) {
                return try {
                    credentialMapper.readValue(TrackerTokenVault.unprotect(encrypted))
                } catch (_: Exception) {
                    AppLogger.e("Unable to unlock saved $prefix credentials for this Windows user.")
                    arrayOf()
                }
            }

            // Migrate an older plaintext entry only after DPAPI protection succeeds.
            val legacyKey = storageKey(prefix)
            val legacy = com.lagradost.common.storage.DesktopDataStore.getKey<Array<AuthData>>(legacyKey) ?: arrayOf()
            if (legacy.isEmpty()) return legacy
            try {
                val json = credentialMapper.writeValueAsString(legacy)
                com.lagradost.common.storage.DesktopDataStore.setKeys(
                    mapOf(protectedStorageKey(prefix) to TrackerTokenVault.protect(json)),
                    setOf(legacyKey),
                )
                return legacy
            } catch (_: Exception) {
                AppLogger.e("Could not protect legacy $prefix credentials; leaving the saved account untouched.")
                return arrayOf()
            }
        }

        fun updateAccounts(prefix: String, array: Array<AuthData>, expectedProfileId: Int = ProfileManager.activeProfileId) = synchronized(ProfileManager) {
            check(ProfileManager.activeProfileId == expectedProfileId) { "Profile changed while the account operation was running" }
            require(prefix != "NONE")
            if (prefix in protectedTrackerPrefixes) {
                val secureKey = protectedStorageKey(prefix)
                val legacyKey = storageKey(prefix)
                if (array.isEmpty()) {
                    com.lagradost.common.storage.DesktopDataStore.setKeys(emptyMap(), setOf(secureKey, legacyKey))
                } else {
                    val json = credentialMapper.writeValueAsString(array)
                    val protected = TrackerTokenVault.protect(json)
                    com.lagradost.common.storage.DesktopDataStore.setKeys(mapOf(secureKey to protected), setOf(legacyKey))
                }
            } else {
                com.lagradost.common.storage.DesktopDataStore.setKey(storageKey(prefix), array)
            }
            synchronized(cachedAccounts) {
                cachedAccounts[prefix] = array
                _accountsFlow.value = cachedAccounts.toMap()
            }
        }

        private fun reloadAccountsForActiveProfile() {
            val data = mutableMapOf<String, Array<AuthData>>()
            for (api in allApis) {
                data[api.idPrefix] = accounts(api.idPrefix)
            }
            synchronized(cachedAccounts) {
                cachedAccounts = data
                _accountsFlow.value = data.toMap()
            }
        }

        init {
            LoadResponse.malIdPrefix = malApi.idPrefix
            LoadResponse.aniListIdPrefix = aniListApi.idPrefix
            LoadResponse.simklIdPrefix = simklApi.idPrefix
            reloadAccountsForActiveProfile()
            scope.launch {
                ProfileManager.activeProfile.collect {
                    reloadAccountsForActiveProfile()
                }
            }
        }
    }
}
