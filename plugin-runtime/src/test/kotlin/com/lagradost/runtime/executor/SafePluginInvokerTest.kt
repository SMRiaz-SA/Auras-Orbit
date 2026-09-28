package com.lagradost.runtime.executor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class SafePluginInvokerTest {

    @Test
    fun testSuccessfulExecution() = runTest {
        val result = SafePluginInvoker.invoke(timeoutMs = 1000L) {
            "hello from plugin"
        }
        assertTrue(result.isSuccess)
        assertEquals("hello from plugin", result.getOrNull())
    }

    @Test
    fun testTimeoutHandling() = runTest {
        val result = SafePluginInvoker.invoke(timeoutMs = 100L) {
            delay(500L)
            "should not reach"
        }
        assertTrue(result.isFailure)
        val failure = result.exceptionOrNull()
        assertTrue(failure is TimeoutException)
        assertTrue(failure?.message.orEmpty().contains("(limit was 100ms)"))
    }

    @Test
    fun unpenalizedTimeoutUsesSharedInvocationAccounting() = runTest {
        val providerName = "unpenalized-timeout-fixture"
        PluginCircuitBreaker.resetProvider(providerName)

        val result = SafePluginInvoker.invoke(
            providerName = providerName,
            timeoutMs = 100L,
            penalizeOnTimeout = false,
        ) {
            delay(500L)
            "should not reach"
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TimeoutException)
        val stats = PluginCircuitBreaker.getStats(providerName)
        assertEquals(1, stats?.successfulCalls)
        assertEquals(0, stats?.failedCalls)
        PluginCircuitBreaker.resetProvider(providerName)
    }

    @Test
    fun testThrowableCatching() = runTest {
        val result = SafePluginInvoker.invoke<String>(timeoutMs = 1000L) {
            throw NoClassDefFoundError("android/widget/Toast")
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NoClassDefFoundError)
    }

    @Test
    fun testInvokeOrNull() = runTest {
        val success = SafePluginInvoker.invokeOrNull(timeoutMs = 1000L) { 42 }
        assertEquals(42, success)

        val failure = SafePluginInvoker.invokeOrNull<Int>(timeoutMs = 1000L) {
            throw RuntimeException("Boom")
        }
        assertNull(failure)
    }

    @Test
    fun testWrapCallbackSuppressesError() {
        val called = AtomicBoolean(false)
        val safeCb = SafePluginInvoker.wrapCallback<String> { item ->
            called.set(true)
            throw IllegalStateException("UI glitch in callback")
        }

        // Should not throw
        assertDoesNotThrow {
            safeCb("test")
        }
        assertTrue(called.get())
    }
}
