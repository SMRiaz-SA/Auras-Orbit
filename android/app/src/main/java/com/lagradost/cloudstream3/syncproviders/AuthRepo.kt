package com.lagradost.cloudstream3.syncproviders

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import com.auras.orbit.extensions.OrbitCs3HostServices
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.CancellationException

/** General-purpose repository for an API that only needs authentication state. */
class PlainAuthRepo(api: AuthAPI) : AuthRepo(api)

/** Shared authentication state and token refresh behavior for sync and subtitle providers. */
abstract class AuthRepo(open val api: AuthAPI) {
    fun isValidRedirectUrl(url: String): Boolean = try {
        api.isValidRedirectUrl(url)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    val idPrefix get() = api.idPrefix
    val name get() = api.name
    val icon get() = api.icon
    val requiresLogin get() = api.requiresLogin
    val createAccountUrl get() = api.createAccountUrl
    val hasOAuth2 get() = api.hasOAuth2
    val hasPin get() = api.hasPin
    val hasInApp get() = api.hasInApp
    val inAppLoginRequirement get() = api.inAppLoginRequirement
    val isAvailable get() = !api.requiresLogin || authUser() != null

    companion object {
        private data class PendingOAuthLogin(
            val profileId: String,
            val idPrefix: String,
            val payload: String?,
            val createdAtElapsedMs: Long,
        )

        private data class PendingPinLogin(
            val profileId: String,
            val idPrefix: String,
            val expiresAtElapsedMs: Long,
            var inFlight: Boolean = false,
        )

        private val pendingOAuthLogins: MutableMap<String, PendingOAuthLogin> = mutableMapOf()
        private val pendingPinLogins: MutableMap<AuthPinData, MutableList<PendingPinLogin>> = mutableMapOf()
        private const val OAUTH_PENDING_TTL_MS = 10 * 60 * 1000L
        private const val PIN_PENDING_TTL_MS = 10 * 60 * 1000L
        private const val MAX_PENDING_OAUTH_LOGINS = 32
        private const val MAX_PENDING_PIN_LOGINS = 32
    }

    @Throws
    protected suspend fun freshAuth(): AuthData? = freshAuthForProfile(activeProfileId())

    /** Uses the profile snapshot owned by the enclosing provider operation. */
    @Throws
    protected suspend fun freshAuthForProfile(profileId: String?): AuthData? {
        val providerId = idPrefix
        if (!isProfileCurrent(profileId) || profileId == null || providerId == "NONE") return null
        val data = authDataForProfile(profileId, providerId) ?: return null
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) return null
        if (data.token.isAccessTokenExpired()) {
            val newToken = api.refreshToken(data.token) ?: return null
            if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) return null
            val newAuth = AuthData(user = data.user, token = newToken)
            if (!refreshUserForProfile(profileId, providerId, newAuth)) return null
            if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) return null
            return newAuth
        }
        return data.takeIf { isProfileCurrent(profileId) && isProviderCurrent(providerId) }
    }

    @Throws
    fun openOAuth2Page(): Boolean {
        return openOAuth2PageForProfile(activeProfileId())
    }

    /** Starts OAuth only when the caller's captured profile is still the active profile. */
    @Throws
    fun openOAuth2PageForProfile(expectedProfileId: String?): Boolean {
        val profileId = expectedProfileId ?: return false
        if (!isProfileCurrent(profileId)) return false
        val providerId = idPrefix
        if (providerId == "NONE") return false
        val page = api.loginRequest() ?: return false
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) return false
        val oauthRequest = addOrBindOAuthState(page.url, AuthAPI.generateCodeVerifier()) ?: return false
        val (browserUrl, state) = oauthRequest
        val createdAtElapsedMs = SystemClock.elapsedRealtime()
        synchronized(pendingOAuthLogins) {
            purgeExpiredOAuthLogins(createdAtElapsedMs)
            if (pendingOAuthLogins.size >= MAX_PENDING_OAUTH_LOGINS || pendingOAuthLogins.containsKey(state)) {
                return false
            }
            pendingOAuthLogins[state] = PendingOAuthLogin(
                profileId, providerId, page.payload, createdAtElapsedMs
            )
        }
        val context = OrbitCs3HostServices.get().applicationContext
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (error: Throwable) {
            synchronized(pendingOAuthLogins) { pendingOAuthLogins.remove(state) }
            throw error
        }
    }

    fun openOAuth2PageWithToast() {
        try {
            if (!openOAuth2Page()) showAuthFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            showAuthFailure()
        }
    }

    private fun showAuthFailure() {
        Toast.makeText(
            OrbitCs3HostServices.get().applicationContext,
            "Unable to authenticate with ${api.name}",
            Toast.LENGTH_SHORT,
        ).show()
    }

    suspend fun logout(from: AuthUser) {
        logoutForProfile(activeProfileId(), from)
    }

    /** Removes only from the profile captured by the UI action; never retargets a later profile. */
    suspend fun logoutForProfile(expectedProfileId: String?, from: AuthUser) {
        val profileId = expectedProfileId ?: return
        if (idPrefix == "NONE" || !isProfileCurrent(profileId)) return
        val currentAccounts = AccountManager.Companion.accountsForProfile(profileId, idPrefix)
        if (!isProfileCurrent(profileId)) return
        val removed = currentAccounts.filter { it.user.id == from.id }
        val remaining = currentAccounts.filter { it.user.id != from.id }
        if (remaining.size < currentAccounts.size) {
            val selectedId = AccountManager.Companion.accountIdForProfile(profileId, idPrefix)
            val newSelectedId = if (remaining.isEmpty()) {
                AccountManager.NONE_ID
            } else if (selectedId == from.id) {
                remaining.firstOrNull()?.user?.id ?: AccountManager.NONE_ID
            } else {
                selectedId
            }
            if (!AccountManager.Companion.updateAccountStateForProfile(
                    profileId, idPrefix, remaining.toTypedArray(), newSelectedId
                )) return
        }
        for (oldAccount in removed) {
            try {
                api.invalidateToken(oldAccount.token)
            } catch (_: NotImplementedError) {
                // Providers that do not support revocation keep their remote token active.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Logout still completes when a remote revocation request fails.
            }
        }
    }

    fun refreshUser(newAuth: AuthData) {
        val profileId = activeProfileId() ?: return
        val providerId = idPrefix
        refreshUserForProfile(profileId, providerId, newAuth)
    }

    private fun refreshUserForProfile(
        profileId: String,
        providerId: String,
        newAuth: AuthData,
    ): Boolean {
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId) || providerId == "NONE") return false
        val currentAccounts = AccountManager.Companion.accountsForProfile(profileId, providerId)
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) return false
        val newAccounts = currentAccounts.map { account ->
            if (account.user.id == newAuth.user.id) newAuth else account
        }.toTypedArray()
        return AccountManager.Companion.updateAccountsForProfile(profileId, providerId, newAccounts)
    }

    fun authData(): AuthData? = authDataForProfile(activeProfileId(), idPrefix)

    private fun authDataForProfile(profileId: String?, providerId: String): AuthData? =
        if (profileId == null || providerId == "NONE" || !isProfileCurrent(profileId) || !isProviderCurrent(providerId)) null
        else AccountManager.Companion.selectedAccountForProfile(profileId, providerId)

    fun authToken(): AuthToken? = authData()?.token

    fun authUser(): AuthUser? = authData()?.user

    val accounts: Array<AuthData>
        get() {
            val profileId = activeProfileId()
            val providerId = idPrefix
            return if (profileId == null || providerId == "NONE") emptyArray()
            else AccountManager.Companion.accountsForProfile(profileId, providerId)
        }

    var accountId: Int
        get() {
            val profileId = activeProfileId()
            val providerId = idPrefix
            return if (profileId == null || providerId == "NONE") AccountManager.NONE_ID
            else AccountManager.Companion.accountIdForProfile(profileId, providerId)
        }
        set(value) {
            AccountManager.Companion.updateAccountsId(idPrefix, value)
        }

    @Throws
    suspend fun pinRequest(): AuthPinData? {
        return pinRequestForProfile(activeProfileId())
    }

    /** Requests a device code only for the profile captured by the UI action. */
    @Throws
    suspend fun pinRequestForProfile(expectedProfileId: String?): AuthPinData? {
        val profileId = expectedProfileId ?: return null
        if (!isProfileCurrent(profileId)) return null
        val providerId = idPrefix
        if (providerId == "NONE") return null
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) return null
        val request = api.pinRequest() ?: return null
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId) || request.expiresIn <= 0) return null

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val providerExpiry = nowElapsedMs + request.expiresIn.toLong() * 1000L
        val expiresAtElapsedMs = minOf(providerExpiry, nowElapsedMs + PIN_PENDING_TTL_MS)
        synchronized(pendingPinLogins) {
            purgeExpiredPinLogins(nowElapsedMs)
            if (pendingPinLogins.values.sumOf { it.size } >= MAX_PENDING_PIN_LOGINS) return null
            val pendingForRequest = pendingPinLogins[request] ?: mutableListOf()
            pendingForRequest.add(PendingPinLogin(profileId, providerId, expiresAtElapsedMs))
            pendingPinLogins[request] = pendingForRequest
        }
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) {
            synchronized(pendingPinLogins) { pendingPinLogins.remove(request) }
            return null
        }
        return request
    }

    @Throws
    private suspend fun setupLogin(profileId: String, providerId: String, token: AuthToken): Boolean {
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) return false
        val user = api.user(token) ?: return false
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) return false
        val newAccount = AuthData(token = token, user = user)
        val currentAccounts = AccountManager.Companion.accountsForProfile(profileId, providerId)
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) return false
        if (currentAccounts.any { it.user.id == newAccount.user.id }) {
            throw ErrorLoadingException("Already logged into this account")
        }
        if (!AccountManager.Companion.addAccountForProfile(profileId, providerId, newAccount)) {
            if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) return false
            throw ErrorLoadingException("Already logged into this account")
        }
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) return false
        if (this is SyncRepo) requireLibraryRefresh = true
        return true
    }

    @Throws
    suspend fun login(form: AuthLoginResponse): Boolean {
        val profileId = activeProfileId() ?: return false
        val providerId = idPrefix
        if (providerId == "NONE" || !isProfileCurrent(profileId)) return false
        val token = api.login(form) ?: return false
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) return false
        return setupLogin(profileId, providerId, token)
    }

    @Throws
    suspend fun login(payload: AuthPinData): Boolean {
        val profileId = activeProfileId() ?: return false
        val providerId = idPrefix
        if (providerId == "NONE" || !isProfileCurrent(profileId)) return false
        val pending = acquirePinLogin(profileId, providerId, payload) ?: return false
        val token = try {
            api.login(payload)
        } catch (cancelled: CancellationException) {
            finishPinLogin(
                payload,
                pending,
                consume = !isProfileCurrent(profileId) || !isProviderCurrent(providerId),
            )
            throw cancelled
        } catch (error: Throwable) {
            finishPinLogin(
                payload,
                pending,
                consume = !isProfileCurrent(profileId) || !isProviderCurrent(providerId),
            )
            throw error
        }
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) {
            finishPinLogin(payload, pending, consume = true)
            return false
        }
        if (token == null) {
            finishPinLogin(payload, pending, consume = false)
            return false
        }
        if (!finishPinLogin(payload, pending, consume = true)) return false
        return setupLogin(profileId, providerId, token)
    }

    @Throws
    suspend fun login(redirectUrl: String): Boolean {
        val profileId = activeProfileId() ?: return false
        val providerId = idPrefix
        if (providerId == "NONE" || !isValidRedirectUrl(redirectUrl)) return false
        val redirectParameters = try {
            AuthAPI.splitRedirectUrl(redirectUrl)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) return false
        val pending = consumeOAuthLogin(profileId, providerId, redirectParameters["state"]) ?: return false
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) return false
        val token = api.login(redirectUrl, pending.payload) ?: return false
        if (!isProfileCurrent(profileId) || !isProviderCurrent(providerId)) return false
        return setupLogin(profileId, providerId, token)
    }

    private fun addOrBindOAuthState(url: String, generatedState: String): Pair<String, String>? {
        return try {
            val existingParameters = AuthAPI.splitRedirectUrl(url)
            val existingState = existingParameters.entries.firstOrNull {
                it.key.equals("state", ignoreCase = true)
            }
            if (existingState != null) {
                // Pinned providers such as MAL and Simkl own state and verify it against their
                // original payload; bind that exact state without changing the payload contract.
                if (existingState.key != "state" || existingState.value.isEmpty()) null
                else url to existingState.value
            } else {
                Uri.parse(url).buildUpon().appendQueryParameter("state", generatedState).build().toString() to
                    generatedState
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun consumeOAuthLogin(
        profileId: String,
        providerId: String,
        callbackState: String?,
    ): PendingOAuthLogin? = synchronized(pendingOAuthLogins) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        purgeExpiredOAuthLogins(nowElapsedMs)
        if (callbackState.isNullOrEmpty()) return@synchronized null
        val pending = pendingOAuthLogins.remove(callbackState) ?: return@synchronized null
        if (pending.profileId != profileId || pending.idPrefix != providerId) return@synchronized null
        pending
    }

    private fun purgeExpiredOAuthLogins(nowElapsedMs: Long) {
        pendingOAuthLogins.entries.removeAll { (_, pending) ->
            nowElapsedMs - pending.createdAtElapsedMs >= OAUTH_PENDING_TTL_MS
        }
    }

    private fun acquirePinLogin(
        profileId: String,
        providerId: String,
        request: AuthPinData,
    ): PendingPinLogin? = synchronized(pendingPinLogins) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        purgeExpiredPinLogins(nowElapsedMs)
        val matches = pendingPinLogins[request] ?: return@synchronized null
        // Equal provider payloads do not identify which outstanding flow completed; consume and
        // reject all of them instead of choosing one profile implicitly.
        if (matches.size != 1) {
            pendingPinLogins.remove(request)
            return@synchronized null
        }
        val pending = matches.single()
        if (pending.expiresAtElapsedMs <= nowElapsedMs ||
            pending.profileId != profileId || pending.idPrefix != providerId) {
            pendingPinLogins.remove(request)
            return@synchronized null
        }
        if (pending.inFlight) return@synchronized null
        pending.inFlight = true
        pending
    }

    private fun finishPinLogin(
        request: AuthPinData,
        pending: PendingPinLogin,
        consume: Boolean,
    ): Boolean = synchronized(pendingPinLogins) {
        val matches = pendingPinLogins[request] ?: return@synchronized false
        if (matches.size != 1 || matches.single() !== pending) {
            pendingPinLogins.remove(request)
            return@synchronized false
        }
        if (pending.expiresAtElapsedMs <= SystemClock.elapsedRealtime()) {
            pendingPinLogins.remove(request)
            return@synchronized false
        }
        if (consume) {
            pendingPinLogins.remove(request)
        } else {
            pending.inFlight = false
        }
        true
    }

    private fun purgeExpiredPinLogins(nowElapsedMs: Long) {
        pendingPinLogins.entries.forEach { entry ->
            entry.setValue(entry.value.filter { it.expiresAtElapsedMs > nowElapsedMs }.toMutableList())
        }
        pendingPinLogins.entries.removeAll { (_, pending) -> pending.isEmpty() }
    }

    private fun activeProfileId(): String? = AccountManager.Companion.activeProfileId()

    private fun isProfileCurrent(profileId: String?): Boolean =
        AccountManager.Companion.isActiveProfile(profileId)

    private fun isProviderCurrent(providerId: String): Boolean = idPrefix == providerId
}
