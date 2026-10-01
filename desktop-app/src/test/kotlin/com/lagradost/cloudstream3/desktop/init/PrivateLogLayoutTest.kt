package com.lagradost.cloudstream3.desktop.init

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivateLogLayoutTest {
    @Test
    fun dependencyLogsAndCrashReportsDiscardSecretsAtTheSink() {
        val failure = IllegalStateException("EXCEPTION_CANARY", IllegalArgumentException("CAUSE_CANARY"))
        failure.addSuppressed(IllegalStateException("SUPPRESSED_CANARY"))
        val event = LoggingEvent().apply {
            threadName = "Authorization: Bearer THREAD_CANARY"
            loggerName = "test"
            level = Level.ERROR
            message = "https://example.test/path?signature=URL_CANARY"
            setThrowableProxy(ThrowableProxy(failure))
        }
        val rendered = PrivateLogLayout().doLayout(event) + privateCrashTrace(failure)
        listOf("EXCEPTION_CANARY", "CAUSE_CANARY", "SUPPRESSED_CANARY", "THREAD_CANARY", "URL_CANARY").forEach {
            assertFalse(rendered.contains(it), rendered)
        }
        assertTrue(rendered.contains("IllegalStateException"))
        assertTrue(rendered.contains("IllegalArgumentException"))
        assertTrue(rendered.contains("[message redacted]"))
    }
}
