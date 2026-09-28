package com.lagradost.runtime.executor

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class PluginBlockingBoundaryTest {
    @Test
    fun blockingSleepDoesNotPreventDeadline() = runBlocking {
        val started = System.nanoTime()
        val result = SafePluginInvoker.invokeDetailed(providerName = "blocking-fixture", timeoutMs = 100) {
            Thread.sleep(10_000)
        }
        assertTrue(result is PluginCallResult.Timeout)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }
}
