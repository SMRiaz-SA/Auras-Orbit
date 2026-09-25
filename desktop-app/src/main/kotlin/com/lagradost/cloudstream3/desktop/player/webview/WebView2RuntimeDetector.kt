package com.lagradost.cloudstream3.desktop.player.webview

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/** Detects the Evergreen WebView2 Runtime using Microsoft's documented registry locations. */
object WebView2RuntimeDetector {
    const val DOWNLOAD_URL = "https://developer.microsoft.com/microsoft-edge/webview2/#download-section"

    private const val WEBVIEW2_CLIENT_ID = "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"
    private const val MACHINE_RUNTIME_KEY =
        "SOFTWARE\\WOW6432Node\\Microsoft\\EdgeUpdate\\Clients\\$WEBVIEW2_CLIENT_ID"
    private const val USER_RUNTIME_KEY =
        "Software\\Microsoft\\EdgeUpdate\\Clients\\$WEBVIEW2_CLIENT_ID"

    fun isInstalled(): Boolean {
        if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
            return false
        }

        return hasUsableVersion(readVersion(WinReg.HKEY_LOCAL_MACHINE, MACHINE_RUNTIME_KEY)) ||
            hasUsableVersion(readVersion(WinReg.HKEY_CURRENT_USER, USER_RUNTIME_KEY))
    }

    private fun readVersion(root: WinReg.HKEY, key: String): String? = runCatching {
        Advapi32Util.registryGetStringValue(root, key, "pv")
    }.getOrNull()

    internal fun hasUsableVersion(version: String?): Boolean {
        val parts = version?.split('.') ?: return false
        if (parts.size != 4) return false

        val numbers = parts.map { it.toIntOrNull() ?: return false }
        return numbers.all { it >= 0 } && numbers.any { it > 0 }
    }
}
