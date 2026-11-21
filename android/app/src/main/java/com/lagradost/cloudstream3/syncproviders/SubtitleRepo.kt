package com.lagradost.cloudstream3.syncproviders

import androidx.annotation.WorkerThread
import com.lagradost.cloudstream3.APIHolder
import com.auras.orbit.player.SubtitleAuthenticationException
import com.auras.orbit.player.SubtitleProfileBinding
import com.auras.orbit.player.SubtitleSearchStatus
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleEntity
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleSearch
import com.lagradost.cloudstream3.subtitles.SubtitleResource
import kotlinx.coroutines.CancellationException

/** Profile-scoped safe abstraction of SubtitleAPI with the pinned rolling result caches. */
class SubtitleRepo(override val api: SubtitleAPI) : AuthRepo(api) {
    companion object {
        data class SavedSearchResponse(
            val unixTime: Long,
            val response: List<SubtitleEntity>,
            val query: SubtitleSearch,
            val idPrefix: String,
        )

        data class SavedResourceResponse(
            val unixTime: Long,
            val response: SubtitleResource,
            val query: SubtitleEntity,
        )

        private data class CachedSearch(val profileId: String?, val response: SavedSearchResponse)
        private data class CachedResource(
            val profileId: String?,
            val idPrefix: String,
            val response: SavedResourceResponse,
        )

        private val searchLock = Any()
        private val resourceLock = Any()
        private val searchCache = mutableListOf<CachedSearch>()
        private var searchCacheIndex = 0
        private val resourceCache = mutableListOf<CachedResource>()
        private var resourceCacheIndex = 0
        const val CACHE_SIZE = 20
    }

    @WorkerThread
    suspend fun resource(data: SubtitleEntity): Result<SubtitleResource> {
        val profileId = AccountManager.Companion.activeProfileId()
            ?: return Result.failure(IllegalStateException("Subtitle access requires an active Orbit profile."))
        return resourceForProfile(profileId, data)
    }

    /** Uses the profile captured by the initiating UI action throughout auth lookup and I/O. */
    @WorkerThread
    suspend fun resourceForProfile(profileId: String, data: SubtitleEntity): Result<SubtitleResource> {
        val providerId = idPrefix
        return providerResult(profileId, providerId) {
            requireProfileCurrent(profileId, providerId)
            val lookupTime = APIHolder.INSTANCE.unixTime
            val cached = synchronized(resourceLock) {
                resourceCache.firstOrNull { item ->
                    item.profileId == profileId && item.idPrefix == providerId &&
                        item.response.query == data && lookupTime - item.response.unixTime < 60 * 20
                }?.response?.response
            }
            if (cached != null) return@providerResult cached

            requireProfileCurrent(profileId, providerId)
            val auth = freshAuthForProfile(profileId)
            requireProfileCurrent(profileId, providerId)
            if (api.requiresLogin && auth == null) {
                throw SubtitleAuthenticationException("Sign in to ${api.name} again to search subtitles.")
            }
            val result = api.resource(auth, data)
            requireProfileCurrent(profileId, providerId)
            synchronized(resourceLock) {
                val entry = CachedResource(
                    profileId,
                    providerId,
                    SavedResourceResponse(APIHolder.INSTANCE.unixTime, result, data),
                )
                if (resourceCache.size > CACHE_SIZE) {
                    resourceCache[resourceCacheIndex] = entry
                    resourceCacheIndex = (resourceCacheIndex + 1) % CACHE_SIZE
                } else {
                    resourceCache.add(entry)
                }
            }
            result
        }
    }

    @WorkerThread
    suspend fun search(query: SubtitleSearch): Result<List<SubtitleEntity>> {
        val profileId = AccountManager.Companion.activeProfileId()
            ?: return Result.failure(IllegalStateException("Subtitle access requires an active Orbit profile."))
        return searchForProfile(profileId, query)
    }

    /** Uses the profile captured by the initiating UI action throughout auth lookup and I/O. */
    @WorkerThread
    suspend fun searchForProfile(profileId: String, query: SubtitleSearch): Result<List<SubtitleEntity>> {
        val providerId = idPrefix
        return providerResult(profileId, providerId) {
            requireProfileCurrent(profileId, providerId)
            val lookupTime = APIHolder.INSTANCE.unixTime
            val cached = synchronized(searchLock) {
                searchCache.firstOrNull { item ->
                    item.profileId == profileId && item.response.idPrefix == providerId &&
                        item.response.query == query && lookupTime - item.response.unixTime < 60 * 120
                }?.response?.response
            }
            if (cached != null) return@providerResult cached

            requireProfileCurrent(profileId, providerId)
            val auth = freshAuthForProfile(profileId)
            requireProfileCurrent(profileId, providerId)
            if (api.requiresLogin && auth == null) {
                throw SubtitleAuthenticationException("Sign in to ${api.name} again to search subtitles.")
            }
            val result = SubtitleSearchStatus.requireProviderResponse(
                api.search(auth, query), providerId,
            )
            requireProfileCurrent(profileId, providerId)
            if (result.isNotEmpty()) {
                synchronized(searchLock) {
                    val entry = CachedSearch(
                        profileId,
                        SavedSearchResponse(APIHolder.INSTANCE.unixTime, result, query, providerId),
                    )
                    if (searchCache.size > CACHE_SIZE) {
                        searchCache[searchCacheIndex] = entry
                        searchCacheIndex = (searchCacheIndex + 1) % CACHE_SIZE
                    } else {
                        searchCache.add(entry)
                    }
                }
            }
            result
        }
    }

    private suspend fun <T> providerResult(
        profileId: String?,
        providerId: String,
        action: suspend () -> T,
    ): Result<T> =
        try {
            requireProfileCurrent(profileId, providerId)
            val result = action()
            requireProfileCurrent(profileId, providerId)
            Result.success(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }

    private fun requireProfileCurrent(profileId: String?, providerId: String) {
        if (idPrefix != providerId || !SubtitleProfileBinding.isCurrent(
                profileId, AccountManager.Companion.activeProfileId()
            )
        ) {
            throw IllegalStateException("Orbit profile or subtitle provider changed during the request.")
        }
    }
}
