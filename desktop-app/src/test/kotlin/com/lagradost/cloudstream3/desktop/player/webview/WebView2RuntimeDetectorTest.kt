package com.lagradost.cloudstream3.desktop.player.webview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebView2RuntimeDetectorTest {
    @Test
    fun `accepts a nonzero four-part runtime version`() {
        assertTrue(WebView2RuntimeDetector.hasUsableVersion("140.0.3485.44"))
    }

    @Test
    fun `rejects absent zero or malformed runtime versions`() {
        assertFalse(WebView2RuntimeDetector.hasUsableVersion(null))
        assertFalse(WebView2RuntimeDetector.hasUsableVersion(""))
        assertFalse(WebView2RuntimeDetector.hasUsableVersion("0.0.0.0"))
        assertFalse(WebView2RuntimeDetector.hasUsableVersion("-1.0.0.0"))
        assertFalse(WebView2RuntimeDetector.hasUsableVersion("not-a-version"))
        assertFalse(WebView2RuntimeDetector.hasUsableVersion("140.0.3485"))
    }
}
