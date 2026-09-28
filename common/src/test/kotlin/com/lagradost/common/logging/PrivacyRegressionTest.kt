package com.lagradost.common.logging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyRegressionTest {
    @Test
    fun jsonSignedUrlsAndExceptionExportsRedactCanaries() {
        val input = """{"access_token":"TOKEN_CANARY","client_secret":"SECRET_CANARY"} https://host/path?signature=URL_CANARY https%3A%2F%2Fhost%2Fpath%3Fsignature%3DENCODED_URL_CANARY"""
        val safe = LogBuffer.sanitize(input)
        listOf("TOKEN_CANARY", "SECRET_CANARY", "URL_CANARY", "ENCODED_URL_CANARY")
            .forEach { assertFalse(safe.contains(it), safe) }
        val entry = LogBuffer.record(LogLevel.ERROR, "test", "failure", IllegalStateException("token=STACK_CANARY"))
        assertFalse(LogBuffer.exportLogsAsText(listOf(entry)).contains("STACK_CANARY"))
    }

    @Test
    fun directLogRecordsDoNotRetainRawThrowableMessages() {
        val failure = IllegalStateException("RAW_EXCEPTION_CANARY").apply {
            initCause(IllegalArgumentException("RAW_CAUSE_CANARY"))
            addSuppressed(IllegalStateException("RAW_SUPPRESSED_CANARY"))
        }
        val entry = LogBuffer.record(
            LogLevel.ERROR,
            "Authorization: Bearer TAG_CANARY",
            "failure",
            failure,
            threadName = "Authorization: Bearer THREAD_CANARY",
        )
        val rendered = listOf(
            entry.toString(),
            entry.throwable?.message.orEmpty(),
            entry.stackTraceString.orEmpty(),
            LogBuffer.exportLogsAsText(listOf(entry)),
            LogBuffer.buildAiDebugSnapshot(entry.id),
        ).joinToString("\n")

        listOf("RAW_EXCEPTION_CANARY", "RAW_CAUSE_CANARY", "RAW_SUPPRESSED_CANARY", "TAG_CANARY", "THREAD_CANARY")
            .forEach { assertFalse(rendered.contains(it), rendered) }
        assertTrue(rendered.contains("IllegalStateException"))
        assertTrue(rendered.contains("[message redacted]"))
    }
}
