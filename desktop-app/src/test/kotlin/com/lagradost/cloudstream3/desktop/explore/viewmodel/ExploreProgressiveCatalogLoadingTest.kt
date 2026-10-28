package com.lagradost.cloudstream3.desktop.explore.viewmodel

import com.lagradost.cloudstream3.desktop.explore.models.ManifestCatalogDescriptor
import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.cloudstream3.desktop.stremio.ManagedStremioAddon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExploreProgressiveCatalogLoadingTest {
    @Test
    fun `catalog sources publish progressively and retry only the failed addon`() = runBlocking {
        val fast = ManagedStremioAddon("Fast Add-on", "https://fast.invalid/manifest.json")
        val delayed = ManagedStremioAddon("Delayed Add-on", "https://delayed.invalid/manifest.json")
        val flaky = ManagedStremioAddon("Flaky Add-on", "https://flaky.invalid/manifest.json")
        val delayedGate = CompletableDeferred<Unit>()
        val delayedStarted = CompletableDeferred<Unit>()
        val calls = ConcurrentHashMap<String, AtomicInteger>()

        val viewModel = ExploreViewModel(
            addonsFlow = MutableStateFlow(listOf(fast, delayed, flaky)),
            catalogDiscoverer = { addon, _ ->
                val attempt = calls.computeIfAbsent(addon.manifestUrl) { AtomicInteger() }.incrementAndGet()
                when (addon.manifestUrl) {
                    fast.manifestUrl -> listOf(catalog(fast, "fast-catalog", "Fast catalog"))
                    delayed.manifestUrl -> {
                        delayedStarted.complete(Unit)
                        delayedGate.await()
                        listOf(catalog(delayed, "delayed-catalog", "Delayed catalog"))
                    }
                    flaky.manifestUrl -> if (attempt == 1) {
                        throw IllegalStateException("fixture manifest failure")
                    } else {
                        listOf(catalog(flaky, "recovered-catalog", "Recovered catalog"))
                    }
                    else -> error("Unexpected fixture add-on ${addon.manifestUrl}")
                }
            },
            catalogItemsLoader = { _, _, _, _, _ -> emptyList() },
        )

        try {
            val early = withTimeout(5_000) {
                viewModel.uiState.first { state ->
                    state.allCatalogs.any { it.id == "fast-catalog" } &&
                        state.catalogDiscoverySources.any { it.addonManifestUrl == delayed.manifestUrl && it.isLoading } &&
                        state.catalogDiscoverySources.any { it.addonManifestUrl == flaky.manifestUrl && it.error != null }
                }
            }
            delayedStarted.await()
            assertEquals("fast-catalog", early.selectedCatalog?.id)
            assertEquals(listOf("fast-catalog"), early.allCatalogs.map { it.id })

            viewModel.onEvent(ExploreUiEvent.RetryCatalogSource(flaky.manifestUrl))
            val afterRetry = withTimeout(5_000) {
                viewModel.uiState.first { state -> state.allCatalogs.any { it.id == "recovered-catalog" } }
            }
            assertEquals("fast-catalog", afterRetry.selectedCatalog?.id)
            assertEquals(1, calls[fast.manifestUrl]?.get())
            assertEquals(1, calls[delayed.manifestUrl]?.get())
            assertEquals(2, calls[flaky.manifestUrl]?.get())

            delayedGate.complete(Unit)
            val completed = withTimeout(5_000) {
                viewModel.uiState.first { state ->
                    state.catalogDiscoverySources.size == 3 && state.catalogDiscoverySources.none { it.isLoading }
                }
            }
            assertEquals(
                listOf("fast-catalog", "delayed-catalog", "recovered-catalog"),
                completed.allCatalogs.map { it.id },
            )
            assertEquals("fast-catalog", completed.selectedCatalog?.id)
            assertTrue(completed.catalogDiscoverySources.all { it.error == null })
        } finally {
            delayedGate.complete(Unit)
            viewModel.dispose()
        }
    }

    @Test
    fun `removed source cannot publish its late catalog result`() = runBlocking {
        val addon = ManagedStremioAddon("Late Add-on", "https://late.invalid/manifest.json")
        val addons = MutableStateFlow(listOf(addon))
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val viewModel = ExploreViewModel(
            addonsFlow = addons,
            catalogDiscoverer = { source, _ ->
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                listOf(catalog(source, "late-catalog", "Late catalog"))
            },
            catalogItemsLoader = { _, _, _, _, _ -> emptyList() },
        )

        try {
            withTimeout(5_000) {
                viewModel.uiState.first { it.catalogDiscoverySources.any { source -> source.isLoading } }
            }
            started.await()
            addons.value = emptyList()
            withTimeout(5_000) {
                viewModel.uiState.first { it.catalogDiscoverySources.isEmpty() }
            }
            release.complete(Unit)
            withTimeout(5_000) { kotlinx.coroutines.delay(50) }
            assertTrue(viewModel.uiState.value.allCatalogs.isEmpty())
            assertTrue(viewModel.uiState.value.catalogDiscoverySources.isEmpty())
        } finally {
            release.complete(Unit)
            viewModel.dispose()
        }
    }

    @Test
    fun `source result from an earlier profile is rejected`() = runBlocking {
        val addon = ManagedStremioAddon("Profile Add-on", "https://profile.invalid/manifest.json")
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var activeProfileId = ProfileManager.activeProfileId
        val viewModel = ExploreViewModel(
            addonsFlow = MutableStateFlow(listOf(addon)),
            catalogDiscoverer = { source, _ ->
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                listOf(catalog(source, "old-profile-catalog", "Old profile catalog"))
            },
            catalogItemsLoader = { _, _, _, _, _ -> emptyList() },
            activeProfileId = { activeProfileId },
        )

        try {
            withTimeout(5_000) {
                viewModel.uiState.first { it.catalogDiscoverySources.any { source -> source.isLoading } }
            }
            started.await()
            activeProfileId += 1
            release.complete(Unit)
            withTimeout(5_000) { kotlinx.coroutines.delay(50) }
            assertTrue(viewModel.uiState.value.allCatalogs.isEmpty())
        } finally {
            release.complete(Unit)
            viewModel.dispose()
        }
    }

    private fun catalog(addon: ManagedStremioAddon, id: String, name: String) = ManifestCatalogDescriptor(
        addonName = addon.name,
        addonBaseUrl = addon.manifestUrl.removeSuffix("manifest.json"),
        type = "movie",
        id = id,
        name = name,
        addonManifestUrl = addon.manifestUrl,
    )
}
