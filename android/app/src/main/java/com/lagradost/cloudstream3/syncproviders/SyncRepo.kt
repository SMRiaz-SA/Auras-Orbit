package com.lagradost.cloudstream3.syncproviders

import kotlinx.coroutines.CancellationException

/** Stateless safe abstraction of SyncAPI with profile-scoped authorization. */
class SyncRepo(override val api: SyncAPI) : AuthRepo(api) {
    val syncIdName: SyncIdName? get() = api.syncIdName

    var requireLibraryRefresh: Boolean
        get() = api.requireLibraryRefresh
        set(value) {
            api.requireLibraryRefresh = value
        }

    suspend fun updateStatus(
        id: String,
        newStatus: SyncAPI.AbstractSyncStatus,
    ): Result<Boolean> = providerResult { profileId ->
        val auth = freshAuthForProfile(profileId)
        requireCurrentProfile(profileId)
        val updated = api.updateStatus(auth ?: return@providerResult false, id, newStatus)
        requireCurrentProfile(profileId)
        requireLibraryRefresh = true
        updated
    }

    suspend fun status(id: String): Result<SyncAPI.AbstractSyncStatus?> = providerResult { profileId ->
        val auth = freshAuthForProfile(profileId)
        requireCurrentProfile(profileId)
        val result = api.status(auth, id)
        requireCurrentProfile(profileId)
        result
    }

    suspend fun load(id: String): Result<SyncAPI.SyncResult?> = providerResult { profileId ->
        val auth = freshAuthForProfile(profileId)
        requireCurrentProfile(profileId)
        val result = api.load(auth, id)
        requireCurrentProfile(profileId)
        result
    }

    suspend fun library(): Result<SyncAPI.LibraryMetadata?> = providerResult { profileId ->
        val auth = freshAuthForProfile(profileId)
        requireCurrentProfile(profileId)
        val result = api.library(auth)
        requireCurrentProfile(profileId)
        result
    }

    private suspend fun <T> providerResult(action: suspend (String?) -> T): Result<T> {
        val profileId = AccountManager.Companion.activeProfileId()
        return try {
            Result.success(action(profileId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun requireCurrentProfile(profileId: String?) {
        if (!AccountManager.Companion.isActiveProfile(profileId)) {
            throw IllegalStateException("Orbit profile changed while the sync provider request was running.")
        }
    }
}
