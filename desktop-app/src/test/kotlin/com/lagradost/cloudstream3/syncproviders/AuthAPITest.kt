package com.lagradost.cloudstream3.syncproviders

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthAPITest {
    @Test
    fun `redirect query and fragment parameters are decoded`() {
        val values = AuthAPI.splitRedirectUrl(
            "http://127.0.0.1/callback?state=query%2Bstate&code=abc%2F123#access_token=fragment%2Btoken&expires_in=60",
        )

        assertEquals("query+state", values["state"])
        assertEquals("abc/123", values["code"])
        assertEquals("fragment+token", values["access_token"])
        assertEquals("60", values["expires_in"])
    }

    @Test
    fun `fragment values take precedence over duplicate query values`() {
        val values = AuthAPI.splitRedirectUrl("https://example.invalid/?state=query#state=fragment")

        assertEquals("fragment", values["state"])
    }

    @Test
    fun `PKCE verifier is random url-safe and S256 follows RFC vector`() {
        val verifier = AuthAPI.generateCodeVerifier()
        assertEquals(43, verifier.length)
        assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]+")))
        assertFalse(verifier == AuthAPI.generateCodeVerifier())

        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            AuthAPI.generateS256CodeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }
}
