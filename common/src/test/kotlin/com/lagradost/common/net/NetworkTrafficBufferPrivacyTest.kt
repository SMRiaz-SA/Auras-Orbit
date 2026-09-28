package com.lagradost.common.net

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkTrafficBufferPrivacyTest {
    @BeforeTest
    fun clearBuffer() {
        NetworkTrafficBuffer.clear()
    }

    @Test
    fun sanitizesAllFieldsBeforePublishingOrExportingEntries() {
        val id = NetworkTrafficBuffer.recordStart(
            method = "POST",
            url = "https://host.invalid/api?access_token=URL_CANARY",
            host = "host.invalid",
            path = "/api?signature=PATH_CANARY",
            requestHeaders = mapOf(
                "X-Client-Secret" to "HEADER_CANARY",
                "Authorization" to "Bearer AUTH_CANARY",
                "X-Debug" to "token=DEBUG_CANARY",
                "Accept" to "application/json",
            ),
            requestBody = """{"password":"BODY_CANARY"}""",
        )
        NetworkTrafficBuffer.recordComplete(
            id = id,
            statusCode = -1,
            statusMessage = "failed token=STATUS_CANARY",
            durationMs = 7,
            responseHeaders = mapOf("Set-Cookie" to "COOKIE_CANARY", "X-Trace" to "secret=TRACE_CANARY"),
            responseBody = """{"api_key":"RESPONSE_CANARY"}""",
            contentType = "application/json",
            error = "I/O failure at https://host.invalid/path?signature=ERROR_CANARY",
        )

        val entry = NetworkTrafficBuffer.getSnapshot().single()
        val rendered = entry.toString() + NetworkTrafficBuffer.exportAsText(listOf(entry)) + entry.toCurlCommand()
        listOf(
            "URL_CANARY", "PATH_CANARY", "HEADER_CANARY", "AUTH_CANARY", "DEBUG_CANARY", "BODY_CANARY",
            "COOKIE_CANARY", "TRACE_CANARY", "RESPONSE_CANARY", "ERROR_CANARY",
        ).forEach { assertFalse(rendered.contains(it), rendered) }
        assertTrue(entry.requestHeaders["Accept"] == "application/json")
        assertTrue(entry.requestHeaders["X-Client-Secret"] == "***MASKED***")
        assertTrue(entry.statusMessage.contains("***MASKED***"))
    }
}
