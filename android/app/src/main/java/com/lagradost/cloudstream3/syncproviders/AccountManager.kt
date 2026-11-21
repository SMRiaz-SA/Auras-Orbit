package com.lagradost.cloudstream3.syncproviders

import android.content.Context
import com.auras.orbit.OrbitApplication
import com.auras.orbit.extensions.OrbitCs3HostServices
import com.auras.orbit.profile.LocalProfileStore
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.Addic7ed
import com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesApi
import com.lagradost.cloudstream3.syncproviders.providers.SubDlApi
import com.lagradost.cloudstream3.syncproviders.providers.SubSourceApi

/** Profile-scoped provider account access; credentials never use CloudStream's legacy keys. */
class AccountManager private constructor() {
    companion object {
        const val NONE_ID = -1

        const val ACCOUNT_TOKEN = "auth_tokens"
        const val ACCOUNT_IDS = "auth_ids"
        const val APP_STRING = "cloudstreamapp"
        const val APP_STRING_REPO = "cloudstreamrepo"
        const val APP_STRING_PLAYER = "cloudstreamplayer"
        const val APP_STRING_SEARCH = "cloudstreamsearch"
        const val APP_STRING_RESUME_WATCHING = "cloudstreamcontinuewatching"
        const val APP_STRING_SHARE = "csshare"

        private val cacheLock = Any()
        private val aniListApi = AniListApi()
        private val cachedAccounts = HashMap<String, Array<AuthData>>()
        private val cachedAccountIds = HashMap<String, Int>()
        private val syncApis = arrayOf(SyncRepo(aniListApi))
        private val subtitleProviders = arrayOf(
            SubtitleRepo(OpenSubtitlesApi()),
            SubtitleRepo(Addic7ed()),
            SubtitleRepo(SubDlApi()),
            SubtitleRepo(SubSourceApi()),
        )
        private val authApis: Array<AuthRepo> = (syncApis.asList() + subtitleProviders.asList()).toTypedArray()
        private var cachedProfileId: String? = null

        fun getNONE_ID(): Int = NONE_ID

        fun getAniListApi(): AniListApi = aniListApi

        fun getSyncApis(): Array<SyncRepo> = syncApis.clone()

        fun getSubtitleProviders(): Array<SubtitleRepo> = subtitleProviders.clone()

        fun getAllApis(): Array<AuthRepo> = authApis.clone()

        fun getCachedAccounts(): MutableMap<String, Array<AuthData>> {
            synchronized(LocalProfileStore.getArchiveImportLock()) {
                ensureActiveProfileCacheLocked(activeProfileId())
                synchronized(cacheLock) {
                    val snapshot = HashMap<String, Array<AuthData>>(cachedAccounts.size)
                    cachedAccounts.forEach { (prefix, accounts) -> snapshot[prefix] = accounts.clone() }
                    return snapshot
                }
            }
        }

        fun setCachedAccounts(values: Map<String, Array<AuthData>>?) {
            synchronized(LocalProfileStore.getArchiveImportLock()) {
                val profileId = activeProfileId()
                synchronized(cacheLock) {
                    setCacheProfileLocked(profileId)
                    cachedAccounts.clear()
                    if (values != null) {
                        values.forEach { (prefix, accounts) -> cachedAccounts[prefix] = accounts.clone() }
                    }
                }
            }
        }

        fun getCachedAccountIds(): MutableMap<String, Int> {
            synchronized(LocalProfileStore.getArchiveImportLock()) {
                ensureActiveProfileCacheLocked(activeProfileId())
                synchronized(cacheLock) {
                    return HashMap(cachedAccountIds)
                }
            }
        }

        fun setCachedAccountIds(values: Map<String, Int>?) {
            synchronized(LocalProfileStore.getArchiveImportLock()) {
                val profileId = activeProfileId()
                synchronized(cacheLock) {
                    setCacheProfileLocked(profileId)
                    cachedAccountIds.clear()
                    if (values != null) cachedAccountIds.putAll(values)
                }
            }
        }

        fun accounts(prefix: String?): Array<AuthData> {
            val providerId = requireProvider(prefix)
            val profileId = activeProfileId() ?: return emptyArray()
            return accountsForProfile(profileId, providerId)
        }

        /** Reads only the requested profile and fails closed if it is no longer active. */
        fun accountsForProfile(profileId: String?, prefix: String?): Array<AuthData> {
            val (validProfileId, providerId) = requireProfileAndProvider(profileId, prefix)
            synchronized(LocalProfileStore.getArchiveImportLock()) {
                if (!isActiveProfile(validProfileId)) return emptyArray()
                val loaded = OrbitAuthCredentialStore.readAccounts(context(), validProfileId, providerId)
                synchronized(cacheLock) {
                    setCacheProfileLocked(validProfileId)
                    cachedAccounts[providerId] = loaded
                }
                return loaded.clone()
            }
        }

        fun updateAccounts(prefix: String?, accounts: Array<AuthData>?) {
            val providerId = requireProvider(prefix)
            val profileId = requireActiveProfileId()
            if (!updateAccountsForProfile(profileId, providerId, accounts)) {
                throw IllegalStateException("The active Orbit profile changed before provider credentials were saved.")
            }
        }

        fun updateAccountsForProfile(
            profileId: String?,
            prefix: String?,
            accounts: Array<AuthData>?,
        ): Boolean {
            val (validProfileId, providerId) = requireProfileAndProvider(profileId, prefix)
            synchronized(LocalProfileStore.getArchiveImportLock()) {
                if (!isActiveProfile(validProfileId)) return false
                val accountValues = accounts
                    ?: throw IllegalArgumentException("Accounts are required.")
                OrbitAuthCredentialStore.writeAccounts(context(), validProfileId, providerId, accountValues)
                synchronized(cacheLock) {
                    setCacheProfileLocked(validProfileId)
                    cachedAccounts[providerId] = accountValues.clone()
                }
                return true
            }
        }

        fun updateAccountsId(prefix: String?, id: Int) {
            val providerId = requireProvider(prefix)
            val profileId = requireActiveProfileId()
            if (!updateAccountsIdForProfile(profileId, providerId, id)) {
                throw IllegalStateException("The active Orbit profile changed before its selected account was saved.")
            }
        }

        fun updateAccountsIdForProfile(profileId: String?, prefix: String?, id: Int): Boolean {
            val (validProfileId, providerId) = requireProfileAndProvider(profileId, prefix)
            synchronized(LocalProfileStore.getArchiveImportLock()) {
                if (!isActiveProfile(validProfileId)) return false
                OrbitAuthCredentialStore.writeSelectedAccountId(context(), validProfileId, providerId, id)
                synchronized(cacheLock) {
                    setCacheProfileLocked(validProfileId)
                    cachedAccountIds[providerId] = id
                }
                return true
            }
        }

        /** Saves a changed account list and selected ID while the profile selection is locked. */
        fun updateAccountStateForProfile(
            profileId: String?,
            prefix: String?,
            accounts: Array<AuthData>?,
            selectedId: Int,
        ): Boolean {
            val (validProfileId, providerId) = requireProfileAndProvider(profileId, prefix)
            synchronized(LocalProfileStore.getArchiveImportLock()) {
                if (!isActiveProfile(validProfileId)) return false
                val accountValues = accounts
                    ?: throw IllegalArgumentException("Accounts are required.")
                OrbitAuthCredentialStore.writeAccounts(context(), validProfileId, providerId, accountValues)
                OrbitAuthCredentialStore.writeSelectedAccountId(context(), validProfileId, providerId, selectedId)
                synchronized(cacheLock) {
                    setCacheProfileLocked(validProfileId)
                    cachedAccounts[providerId] = accountValues.clone()
                    cachedAccountIds[providerId] = selectedId
                }
                return true
            }
        }

        /** Adds a login and selects it atomically with respect to profile switching. */
        fun addAccountForProfile(profileId: String?, prefix: String?, account: AuthData?): Boolean {
            val (validProfileId, providerId) = requireProfileAndProvider(profileId, prefix)
            val accountValue = account ?: throw IllegalArgumentException("Account is required.")
            synchronized(LocalProfileStore.getArchiveImportLock()) {
                if (!isActiveProfile(validProfileId)) return false
                val current = OrbitAuthCredentialStore.readAccounts(context(), validProfileId, providerId)
                for (existing in current) {
                    if (existing.user.id == accountValue.user.id) return false
                }
                val updated = java.util.Arrays.copyOf(current, current.size + 1)
                updated[current.size] = accountValue
                OrbitAuthCredentialStore.writeAccounts(context(), validProfileId, providerId, updated)
                OrbitAuthCredentialStore.writeSelectedAccountId(
                    context(), validProfileId, providerId, accountValue.user.id
                )
                synchronized(cacheLock) {
                    setCacheProfileLocked(validProfileId)
                    cachedAccounts[providerId] = updated
                    cachedAccountIds[providerId] = accountValue.user.id
                }
                return true
            }
        }

        fun selectedAccount(prefix: String?): AuthData? {
            val providerId = requireProvider(prefix)
            val profileId = activeProfileId() ?: return null
            return selectedAccountForProfile(profileId, providerId)
        }

        fun selectedAccountForProfile(profileId: String?, prefix: String?): AuthData? {
            val (validProfileId, providerId) = requireProfileAndProvider(profileId, prefix)
            synchronized(LocalProfileStore.getArchiveImportLock()) {
                if (!isActiveProfile(validProfileId)) return null
                val selectedId = OrbitAuthCredentialStore.readSelectedAccountId(
                    context(), validProfileId, providerId, NONE_ID
                )
                for (account in accountsForProfile(validProfileId, providerId)) {
                    if (account.user.id == selectedId) return account
                }
                return null
            }
        }

        fun accountId(prefix: String?): Int {
            val providerId = requireProvider(prefix)
            val profileId = activeProfileId() ?: return NONE_ID
            return accountIdForProfile(profileId, providerId)
        }

        fun accountIdForProfile(profileId: String?, prefix: String?): Int {
            val (validProfileId, providerId) = requireProfileAndProvider(profileId, prefix)
            synchronized(LocalProfileStore.getArchiveImportLock()) {
                if (!isActiveProfile(validProfileId)) return NONE_ID
                val id = OrbitAuthCredentialStore.readSelectedAccountId(
                    context(), validProfileId, providerId, NONE_ID
                )
                synchronized(cacheLock) {
                    setCacheProfileLocked(validProfileId)
                    cachedAccountIds[providerId] = id
                }
                return id
            }
        }

        /** Reloads selected IDs for all locally available provider APIs. */
        fun updateAccountIds() {
            synchronized(LocalProfileStore.getArchiveImportLock()) {
                val profileId = activeProfileId()
                synchronized(cacheLock) {
                    setCacheProfileLocked(profileId)
                    cachedAccountIds.clear()
                    if (profileId != null) {
                        for (repo in authApis) {
                            val providerId = repo.idPrefix
                            cachedAccountIds[providerId] = OrbitAuthCredentialStore.readSelectedAccountId(
                                context(), profileId, providerId, NONE_ID
                            )
                        }
                    }
                }
            }
        }

        /** Called while archiveImportLock is held; cacheLock is always acquired second. */
        private fun ensureActiveProfileCacheLocked(profileId: String?) {
            synchronized(cacheLock) {
                setCacheProfileLocked(profileId)
                if (profileId != null && !cachedAccounts.containsKey(aniListApi.idPrefix)) {
                    cachedAccounts[aniListApi.idPrefix] = OrbitAuthCredentialStore.readAccounts(
                        context(), profileId, aniListApi.idPrefix
                    )
                }
                if (profileId != null && !cachedAccountIds.containsKey(aniListApi.idPrefix)) {
                    cachedAccountIds[aniListApi.idPrefix] = OrbitAuthCredentialStore.readSelectedAccountId(
                        context(), profileId, aniListApi.idPrefix, NONE_ID
                    )
                }
            }
        }

        private fun setCacheProfileLocked(profileId: String?) {
            if (cachedProfileId != profileId) {
                cachedAccounts.clear()
                cachedAccountIds.clear()
                cachedProfileId = profileId
            }
        }

        fun activeProfileId(): String? {
            synchronized(LocalProfileStore.getArchiveImportLock()) {
                return try {
                    val applicationContext = context()
                    if (applicationContext !is OrbitApplication) null else applicationContext.activeProfileId
                } catch (_: RuntimeException) {
                    null
                }
            }
        }

        fun isActiveProfile(profileId: String?): Boolean = activeProfileId() == profileId

        private fun requireActiveProfileId(): String {
            val profileId = activeProfileId()
            if (profileId == null || profileId.trim().isEmpty()) {
                throw IllegalStateException("Provider credentials require an active Orbit profile.")
            }
            return profileId
        }

        private fun context(): Context {
            return OrbitCs3HostServices.get().applicationContext
        }

        private fun requireProvider(prefix: String?): String {
            if (prefix == null || prefix.trim().isEmpty() || prefix == "NONE") {
                throw IllegalArgumentException("A provider idPrefix is required.")
            }
            return prefix
        }

        private fun requireProfileAndProvider(profileId: String?, prefix: String?): Pair<String, String> {
            if (profileId == null || profileId.trim().isEmpty()) {
                throw IllegalArgumentException("A profile ID is required.")
            }
            return profileId to requireProvider(prefix)
        }
    }
}
