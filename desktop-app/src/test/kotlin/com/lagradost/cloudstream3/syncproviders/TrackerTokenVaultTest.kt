package com.lagradost.cloudstream3.syncproviders

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TrackerTokenVaultTest {
    @Test
    fun `DPAPI protects tracker tokens at rest and round trips for the current user`() {
        assumeTrue(TrackerTokenVault.isSupported(), "DPAPI tests run on Windows")
        val tokenJson = "{\"accessToken\":\"test-access-token\",\"refreshToken\":\"test-refresh-token\"}"

        val protected = TrackerTokenVault.protect(tokenJson)

        assertFalse(protected.contains("test-access-token"))
        assertFalse(protected.contains("test-refresh-token"))
        assertEquals(tokenJson, TrackerTokenVault.unprotect(protected))
    }
}
