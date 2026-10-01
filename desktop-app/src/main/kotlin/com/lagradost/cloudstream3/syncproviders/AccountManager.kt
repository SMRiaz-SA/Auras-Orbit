package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesStremioApi
import com.lagradost.cloudstream3.syncproviders.providers.SubDlApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AccountManager {
    companion object {
        // Android-built extensions such as StreamPlay resolve this getter while their providers
        // are constructed. The desktop AniList API is currently unauthenticated; SyncRepo keeps
        // account-only pages unavailable until desktop account sync is implemented.
        val aniListApi = AniListApi()

        @JvmStatic
        val openSubtitlesStremioApi = OpenSubtitlesStremioApi()

        @JvmStatic
        val subDlApi = SubDlApi()

        val subtitleProviders = listOf(openSubtitlesStremioApi, subDlApi)

        var cachedAccounts: MutableMap<String, Array<AuthData>> = mutableMapOf()

        private val _accountsFlow = MutableStateFlow<Map<String, Array<AuthData>>>(emptyMap())
        val accountsFlow: StateFlow<Map<String, Array<AuthData>>> = _accountsFlow.asStateFlow()

        const val ACCOUNT_TOKEN = "auth_tokens"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun storageKey(prefix: String): String {
            val profileId = ProfileManager.activeProfileId
            val profileKey = if (profileId == 0) "default" else "profile_$profileId"
            return "${ACCOUNT_TOKEN}_${prefix}_$profileKey"
        }

        fun accounts(prefix: String): Array<AuthData> {
            require(subtitleProviders.any { it.idPrefix == prefix })
            return com.lagradost.common.storage.DesktopDataStore.getKey<Array<AuthData>>(storageKey(prefix)) ?: arrayOf()
        }

        fun updateAccounts(prefix: String, array: Array<AuthData>, expectedProfileId: Int = ProfileManager.activeProfileId) = synchronized(ProfileManager) {
            check(ProfileManager.activeProfileId == expectedProfileId) { "Profile changed while the account operation was running" }
            require(subtitleProviders.any { it.idPrefix == prefix })
            com.lagradost.common.storage.DesktopDataStore.setKey(storageKey(prefix), array)
            synchronized(cachedAccounts) {
                cachedAccounts[prefix] = array
                _accountsFlow.value = cachedAccounts.toMap()
            }
        }

        private fun reloadAccountsForActiveProfile() {
            val data = mutableMapOf<String, Array<AuthData>>()
            for (api in subtitleProviders) {
                data[api.idPrefix] = accounts(api.idPrefix)
            }
            synchronized(cachedAccounts) {
                cachedAccounts = data
                _accountsFlow.value = data.toMap()
            }
        }

        init {
            reloadAccountsForActiveProfile()
            scope.launch {
                ProfileManager.activeProfile.collect {
                    reloadAccountsForActiveProfile()
                }
            }
        }
    }
}
