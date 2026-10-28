package com.lagradost.common.performance

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerformanceDiagnosticsTest {
    @Test
    fun metadataIdentityStageIsAccepted() {
        val file = Files.createTempDirectory("perf-metadata-identity").resolve("samples.json").toFile()
        val store = PerformanceDiagnosticsStore(file) { 42L }

        assertTrue(
            store.record(
                PerformanceDiagnosticStage.METADATA_IDENTITY,
                "provider_a",
                status = PerformanceDiagnosticStatus.SUCCESS,
                durationMs = 17,
            ),
        )
        assertEquals("metadata_identity", store.snapshot().single().stage.key)
    }

    @Test
    fun recordAsyncCompletesAndRunsTheClockOnAnIoWorker() = runBlocking {
        val file = Files.createTempDirectory("perf-async").resolve("samples.json").toFile()
        val callerThread = Thread.currentThread().name
        val recordingThread = AtomicReference<String>()
        val store = PerformanceDiagnosticsStore(file) {
            recordingThread.set(Thread.currentThread().name)
            99L
        }

        assertTrue(
            store.recordAsync(
                PerformanceDiagnosticStage.METADATA_IDENTITY,
                "provider_a",
                status = PerformanceDiagnosticStatus.SUCCESS,
                durationMs = 12,
            ).await(),
        )
        assertFalse(callerThread == recordingThread.get())
        assertEquals(12L, store.snapshot().single().durationMs)
        assertEquals(99L, store.snapshot().single().timestampMs)
    }

    @Test
    fun summariesKeepProfilesAndSourcesIsolated() {
        val file = Files.createTempDirectory("perf-summary").resolve("samples.json").toFile()
        var now = 1_000L
        val store = PerformanceDiagnosticsStore(file) { now++ }

        assertTrue(store.record(PerformanceDiagnosticStage.FIRST_FRAME, "provider_a", "profile_1", PerformanceDiagnosticStatus.SUCCESS, 10))
        assertTrue(store.record(PerformanceDiagnosticStage.FIRST_FRAME, "provider_a", "profile_1", PerformanceDiagnosticStatus.SUCCESS, 20))
        assertTrue(store.record(PerformanceDiagnosticStage.FIRST_FRAME, "provider_a", "profile_1", PerformanceDiagnosticStatus.FAILURE, 30))
        assertTrue(store.record(PerformanceDiagnosticStage.FIRST_FRAME, "provider_a", "profile_1", PerformanceDiagnosticStatus.SUCCESS, 100))
        assertTrue(store.record(PerformanceDiagnosticStage.FIRST_FRAME, "provider_a", "profile_2", PerformanceDiagnosticStatus.SUCCESS, 50))
        assertTrue(store.record(PerformanceDiagnosticStage.FIRST_FRAME, "provider_b", "profile_1", PerformanceDiagnosticStatus.SUCCESS, 70))

        val summaries = store.summaries()
        val profileOne = summaries.single { it.sourceLabel == "provider_a" && it.profileId == "profile_1" }
        assertEquals(4, profileOne.sampleCount)
        assertEquals(25.0, profileOne.medianMs)
        assertEquals(100.0, profileOne.p95Ms)
        assertEquals(3, summaries.size)
        assertEquals(50.0, summaries.single { it.profileId == "profile_2" }.medianMs)
        assertEquals(70.0, summaries.single { it.sourceLabel == "provider_b" }.medianMs)
    }

    @Test
    fun retentionIsBoundedTo512Samples() {
        val directory = Files.createTempDirectory("perf-retention")
        var now = 1L
        val store = PerformanceDiagnosticsStore(directory.resolve("samples.json").toFile()) { now++ }

        repeat(PerformanceDiagnosticsStore.MAX_SAMPLES + 3) { index ->
            assertTrue(
                store.record(
                    PerformanceDiagnosticStage.SOURCE_LOOKUP,
                    "source_a",
                    status = PerformanceDiagnosticStatus.SUCCESS,
                    durationMs = index.toLong(),
                ),
            )
        }

        val snapshot = store.snapshot()
        assertEquals(PerformanceDiagnosticsStore.MAX_SAMPLES, snapshot.size)
        assertEquals(3L, snapshot.first().durationMs)
        assertEquals(PerformanceDiagnosticsStore.MAX_SAMPLES + 2L, snapshot.last().durationMs)
    }

    @Test
    fun rejectsUrlsCredentialsAndFreeFormTitleLikeLabelsBeforePersistence() {
        val directory = Files.createTempDirectory("perf-privacy")
        val file = directory.resolve("samples.json").toFile()
        val store = PerformanceDiagnosticsStore(file) { 123L }

        assertFalse(
            store.record(
                PerformanceDiagnosticStage.SOURCE_SUCCESS,
                "https://media.invalid/watch?token=URL_CANARY",
                status = PerformanceDiagnosticStatus.SUCCESS,
                durationMs = 8,
            ),
        )
        assertFalse(
            store.record(
                PerformanceDiagnosticStage.SOURCE_SUCCESS,
                "source_a",
                profileId = "password_CANARY",
                status = PerformanceDiagnosticStatus.SUCCESS,
                durationMs = 8,
            ),
        )
        assertFalse(
            store.record(
                PerformanceDiagnosticStage.SOURCE_SUCCESS,
                "A Show Title",
                status = PerformanceDiagnosticStatus.SUCCESS,
                durationMs = 8,
            ),
        )
        assertTrue(
            store.record(
                PerformanceDiagnosticStage.SOURCE_SUCCESS,
                "source_a",
                profileId = "profile-12",
                status = PerformanceDiagnosticStatus.SUCCESS,
                durationMs = 8,
            ),
        )

        val saved = file.readText()
        assertFalse(saved.contains("URL_CANARY"))
        assertFalse(saved.contains("password_CANARY"))
        assertFalse(saved.contains("A Show Title"))
        val sample = store.snapshot().single()
        assertEquals("source_a", sample.sourceLabel)
        assertEquals("profile-12", sample.profileId)
        assertEquals(123L, sample.timestampMs)
    }

    @Test
    fun concurrentStoreInstancesDoNotLoseRecordedSamples() {
        val directory = Files.createTempDirectory("perf-concurrent")
        val file = directory.resolve("samples.json").toFile()
        val stores = List(4) { PerformanceDiagnosticsStore(file) }
        val executor = Executors.newFixedThreadPool(stores.size)

        try {
            val writes = stores.mapIndexed { storeIndex, store ->
                executor.submit {
                    repeat(40) { item ->
                        check(
                            store.record(
                                PerformanceDiagnosticStage.BUFFERING,
                                "source_$storeIndex",
                                status = PerformanceDiagnosticStatus.SUCCESS,
                                durationMs = item.toLong(),
                            ),
                        )
                    }
                }
            }
            writes.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        val samples = stores.first().snapshot()
        assertEquals(160, samples.size)
        assertEquals(4, samples.map { it.sourceLabel }.distinct().size)
    }
}
