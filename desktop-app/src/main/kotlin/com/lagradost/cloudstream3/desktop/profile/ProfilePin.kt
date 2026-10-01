package com.lagradost.cloudstream3.desktop.profile

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal object ProfilePin {
    private const val PREFIX = "pbkdf2-v1:"

    /** The protected v1 value does not encode its original digit count. */
    fun inputLength(pin: String?): Int? {
        val value = pin?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return value.length.takeIf { it in 4..6 && value.all(Char::isDigit) }
    }

    /** Returns null for old hashes whose original PIN length was not persisted. */
    fun expectedLength(stored: String?, persistedLength: Int?): Int? {
        persistedLength?.takeIf { it in 4..6 }?.let { return it }
        if (stored.isNullOrBlank()) return 4
        return if (stored.startsWith(PREFIX)) null else inputLength(stored)
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
    fun protect(pin: String?): String? {
        if (pin.isNullOrBlank()) return null
        if (pin.startsWith(PREFIX)) return pin
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val encoder = Base64.getEncoder()
        return PREFIX + encoder.encodeToString(salt) + ":" + encoder.encodeToString(derive(pin.trim(), salt))
    }
    fun verify(stored: String?, input: String?): Boolean {
        if (stored.isNullOrBlank()) return true
        if (input == null) return false
        if (!stored.startsWith(PREFIX)) return MessageDigest.isEqual(stored.toByteArray(), input.trim().toByteArray())
        return runCatching {
            val parts = stored.removePrefix(PREFIX).split(':')
            if (parts.size != 2) return false
            val decoder = Base64.getDecoder()
            MessageDigest.isEqual(decoder.decode(parts[1]), derive(input.trim(), decoder.decode(parts[0])))
        }.getOrDefault(false)
    }
}
