package com.auras.orbit.player

import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleEntity
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleSearch
import com.lagradost.cloudstream3.subtitles.SubtitleResource
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SubtitleRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Runs the pinned CloudStream SubtitleRepo search/resource flow against Orbit's active profile. */
object SubtitleSearchGateway {
    private const val MAX_RESULTS_PER_PROVIDER = 30
    private const val MAX_RESULTS_TOTAL = 90

    data class SearchResult(
        val providerId: String,
        val providerName: String,
        val entity: SubtitleEntity,
    )

    interface Callback {
        fun onSearchComplete(
            results: List<SearchResult>,
            failedProviders: Int,
            authenticationRequiredProviders: Int,
            authenticationFailedProviders: Int,
        )
        fun onSubtitleLoaded(
            providerId: String,
            entity: SubtitleEntity,
            resources: List<SubtitleResource.SingleSubtitleResource>,
            failed: Boolean,
            authenticationFailed: Boolean,
        )
    }

    @JvmStatic
    fun search(profileId: String, query: SubtitleSearch, callback: Callback) {
        CoroutineScope(Dispatchers.IO).launch {
            val providerBatches = mutableListOf<List<SearchResult>>()
            var failures = 0
            var authenticationRequired = 0
            var authenticationFailed = 0
            if (!AccountManager.isActiveProfile(profileId)) {
                callback.onSearchComplete(emptyList(), 1, 0, 0)
                return@launch
            }

            for (repo in AccountManager.getSubtitleProviders()) {
                if (!AccountManager.isActiveProfile(profileId)) {
                    callback.onSearchComplete(emptyList(), failures + 1, authenticationRequired,
                        authenticationFailed)
                    return@launch
                }
                if (repo.requiresLogin) {
                    val hasAccount = AccountManager.selectedAccountForProfile(profileId, repo.idPrefix) != null
                    if (!AccountManager.isActiveProfile(profileId)) {
                        callback.onSearchComplete(emptyList(), failures + 1, authenticationRequired,
                            authenticationFailed)
                        return@launch
                    }
                    if (SubtitleSearchStatus.requiresAuthentication(true, hasAccount)) {
                        authenticationRequired++
                        continue
                    }
                }
                val response = try {
                    repo.searchForProfile(profileId, query)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                if (response.isFailure) {
                    if (SubtitleSearchStatus.isAuthenticationFailure(response.exceptionOrNull())) {
                        authenticationFailed++
                    } else {
                        failures++
                    }
                    continue
                }
                val providerResults = response.getOrNull().orEmpty()
                val batch = mutableListOf<SearchResult>()
                for (entity in providerResults.take(MAX_RESULTS_PER_PROVIDER)) {
                    if (entity.idPrefix == repo.idPrefix) {
                        batch += SearchResult(repo.idPrefix, repo.name, entity)
                    }
                }
                if (batch.isNotEmpty()) providerBatches += batch
            }
            if (!AccountManager.isActiveProfile(profileId)) {
                callback.onSearchComplete(emptyList(), failures + 1, authenticationRequired,
                    authenticationFailed)
                return@launch
            }
            callback.onSearchComplete(
                SubtitleResultInterleaver.interleave(providerBatches, MAX_RESULTS_TOTAL),
                failures,
                authenticationRequired,
                authenticationFailed,
            )
        }
    }

    @JvmStatic
    fun load(profileId: String, providerId: String, entity: SubtitleEntity, callback: Callback) {
        CoroutineScope(Dispatchers.IO).launch {
            val repo: SubtitleRepo? = AccountManager.getSubtitleProviders()
                .firstOrNull { it.idPrefix == providerId && entity.idPrefix == providerId }
            if (repo == null || !AccountManager.isActiveProfile(profileId)) {
                callback.onSubtitleLoaded(providerId, entity, emptyList(), true, false)
                return@launch
            }
            val result = try {
                repo.resourceForProfile(profileId, entity)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
            if (result.isFailure || !AccountManager.isActiveProfile(profileId)) {
                callback.onSubtitleLoaded(providerId, entity, emptyList(), true,
                    SubtitleSearchStatus.isAuthenticationFailure(result.exceptionOrNull()))
                return@launch
            }
            callback.onSubtitleLoaded(
                providerId,
                entity,
                result.getOrNull()?.getSubtitles().orEmpty(),
                false,
                false,
            )
        }
    }
}
