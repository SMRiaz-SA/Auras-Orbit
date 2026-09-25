package com.lagradost.cloudstream3.syncproviders

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/** Protects tracker credentials with the current Windows user's DPAPI key. */
internal object TrackerTokenVault {
    private const val PREFIX = "dpapi-v1:"
    private val entropy = "AurasOrbit/tracker-credentials/v1".toByteArray(StandardCharsets.UTF_8)

    fun isSupported(): Boolean = System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows")

    fun protect(plaintext: String): String {
        check(isSupported()) { "Secure tracker credential storage is currently supported on Windows only." }
        require(plaintext.isNotEmpty())
        val encrypted = Crypt32Util.cryptProtectData(plaintext.toByteArray(StandardCharsets.UTF_8), entropy, 0, null, null)
        return PREFIX + Base64.getEncoder().encodeToString(encrypted)
    }

    fun unprotect(ciphertext: String): String {
        check(isSupported()) { "Secure tracker credential storage is currently supported on Windows only." }
        require(ciphertext.startsWith(PREFIX)) { "Unsupported tracker credential format." }
        val encrypted = Base64.getDecoder().decode(ciphertext.removePrefix(PREFIX))
        val plaintext = Crypt32Util.cryptUnprotectData(encrypted, entropy, 0, null)
        return String(plaintext, StandardCharsets.UTF_8)
    }
}
