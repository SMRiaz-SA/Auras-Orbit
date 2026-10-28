package com.lagradost.cloudstream3.ui.result

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Identifies a detail load so callbacks from an older navigation cannot update the current page. */
internal class ResultLoadGeneration {
    @Volatile
    private var generation: Long = 0

    @Synchronized
    fun begin(): Long = ++generation

    @Synchronized
    fun invalidate(): Long = ++generation

    @Synchronized
    fun currentTicket(): Long = generation

    fun isCurrent(ticket: Long): Boolean = generation == ticket
}

/**
 * Publishes provider page and episode data, then waits for their first presentation before optional
 * enrichment starts. Enrichment failure or timeout falls back to the provider response. Callers
 * publish enriched episodes and mark stage three ready only from [publishEpisodes].
 */
internal suspend fun <Provider, Enriched> runProviderFirstDetailStages(
    provider: Provider,
    isCurrent: () -> Boolean,
    publishProvider: suspend (Provider) -> Unit,
    awaitFirstPresentation: suspend () -> Unit,
    enrich: suspend (Provider) -> Enriched,
    fallback: (Provider) -> Enriched,
    publishEpisodes: suspend (Enriched) -> Unit,
    enrichmentTimeoutMs: Long,
): Boolean {
    if (!isCurrent()) return false

    publishProvider(provider)
    awaitFirstPresentation()
    if (!isCurrent()) return false

    val enriched = try {
        withTimeout(enrichmentTimeoutMs) { enrich(provider) }
    } catch (_: TimeoutCancellationException) {
        fallback(provider)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fallback(provider)
    }

    if (!isCurrent()) return false
    publishEpisodes(enriched)
    return true
}
