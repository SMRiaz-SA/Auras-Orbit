package com.lagradost.cloudstream3.syncproviders

import kotlinx.coroutines.CancellationException

/** Result of making a saved tracker account usable for one operation. */
sealed interface TrackerAuthResult {
    data class Ready(val account: AuthData) : TrackerAuthResult
    data object Missing : TrackerAuthResult
    data object ReauthorizationRequired : TrackerAuthResult
}

/** Shared token-refresh path for library, edits, and playback sync. */
object TrackerAccountAccess {
    suspend fun current(api: SyncAPI, saved: AuthData?): TrackerAuthResult {
        val profileId = com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId
        saved ?: return TrackerAuthResult.Missing
        if (!saved.token.isAccessTokenExpired()) return TrackerAuthResult.Ready(saved)
        if (saved.token.isRefreshTokenExpired()) return TrackerAuthResult.ReauthorizationRequired

        val refreshed = try {
            api.refreshToken(saved.token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
            ?: return TrackerAuthResult.ReauthorizationRequired
        val accounts = AccountManager.accounts(api.idPrefix)
        if (com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId != profileId || accounts.none { it.user.id == saved.user.id }) {
            return TrackerAuthResult.Missing
        }
        AccountManager.updateAccounts(
            api.idPrefix,
            accounts.map { account ->
                if (account.user.id == saved.user.id) account.copy(token = refreshed) else account
            }.toTypedArray(),
            expectedProfileId = profileId,
        )
        return TrackerAuthResult.Ready(saved.copy(token = refreshed))
    }
}
