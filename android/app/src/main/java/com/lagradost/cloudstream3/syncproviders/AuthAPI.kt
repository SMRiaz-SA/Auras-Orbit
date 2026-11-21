package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.APIHolder
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.Locale

/** Stateless authentication contract inherited by CloudStream sync providers. */
abstract class AuthAPI {
    open val name: String = "NONE"
    open val idPrefix: String = "NONE"
    open val icon: Int? = null
    open val requiresLogin: Boolean = true
    open val createAccountUrl: String? = null
    open val redirectUrlIdentifier: String? = null
    open val hasOAuth2: Boolean = false
    open val hasPin: Boolean = false
    open val hasInApp: Boolean = false
    open val inAppLoginRequirement: AuthLoginRequirement? = null

    @Throws
    open fun isValidRedirectUrl(url: String): Boolean =
        redirectUrlIdentifier != null && url.contains("/$redirectUrlIdentifier")

    @Throws
    open suspend fun login(redirectUrl: String, payload: String?): AuthToken? = throw NotImplementedError()

    @Throws
    open fun loginRequest(): AuthLoginPage? = throw NotImplementedError()

    @Throws
    open suspend fun pinRequest(): AuthPinData? = throw NotImplementedError()

    @Throws
    open suspend fun refreshToken(token: AuthToken): AuthToken? = throw NotImplementedError()

    @Throws
    open suspend fun login(payload: AuthPinData): AuthToken? = throw NotImplementedError()

    @Throws
    open suspend fun login(form: AuthLoginResponse): AuthToken? = throw NotImplementedError()

    @Throws
    open suspend fun user(token: AuthToken?): AuthUser? = throw NotImplementedError()

    @Throws
    open suspend fun invalidateToken(token: AuthToken): Nothing = throw NotImplementedError()

    @Throws
    @Deprecated("Please use the new API for AuthAPI", level = DeprecationLevel.ERROR)
    fun toRepo(): AuthRepo = when (this) {
        is SubtitleAPI -> SubtitleRepo(this)
        is SyncAPI -> SyncRepo(this)
        else -> throw NotImplementedError("Unknown inheritance from AuthAPI")
    }

    @Suppress("DEPRECATION_ERROR")
    @Deprecated("Please use the new API for AuthAPI", level = DeprecationLevel.ERROR)
    fun loginInfo(): LoginInfo? = toRepo().authUser()?.let { user ->
        LoginInfo(profilePicture = user.profilePicture, name = user.name, accountIndex = -1)
    }

    @Suppress("DEPRECATION_ERROR")
    @Deprecated("Please use the new API for AuthAPI", level = DeprecationLevel.ERROR)
    suspend fun getPersonalLibrary(): SyncAPI.LibraryMetadata? =
        (toRepo() as? SyncRepo)?.library()?.getOrThrow()

    class LoginInfo(
        val profilePicture: String? = null,
        val name: String?,
        val accountIndex: Int,
    )

    companion object {
        @Deprecated("Use APIHolder.unixTime instead", replaceWith = ReplaceWith("APIHolder.unixTime"))
        val unixTime: Long get() = APIHolder.INSTANCE.unixTime

        @Deprecated("Use APIHolder.unixTimeMS instead", replaceWith = ReplaceWith("APIHolder.unixTimeMS"))
        val unixTimeMs: Long get() = APIHolder.INSTANCE.unixTimeMS

        fun splitRedirectUrl(redirectUrl: String): Map<String, String> {
            val normalized = redirectUrl.replace(AccountManager.APP_STRING, "https").replace("/#", "?")
            val uri = try {
                URI(normalized)
            } catch (malformed: Exception) {
                throw IllegalArgumentException("Malformed authentication redirect URL.", malformed)
            }
            // OAuth implicit-flow callbacks (including AniList) return their token and state
            // in the URI fragment. Parse the query when present and include the fragment so
            // security-sensitive values duplicated across the two locations are rejected.
            val query = listOfNotNull(uri.rawQuery, uri.rawFragment)
                .filter { it.isNotEmpty() }
                .joinToString("&")
            if (query.isEmpty()) return emptyMap()

            val securitySensitive = setOf(
                "access_token", "refresh_token", "id_token", "code", "state",
                "code_verifier", "nonce", "token_type", "expires_in", "error", "error_description",
            )
            val seenSecurityParameters = HashSet<String>()
            val result = LinkedHashMap<String, String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val split = part.indexOf('=')
                val key = decodeQueryComponent(if (split < 0) part else part.substring(0, split))
                val value = decodeQueryComponent(if (split < 0) "" else part.substring(split + 1))
                val normalizedKey = key.lowercase(Locale.ROOT)
                if (normalizedKey in securitySensitive && !seenSecurityParameters.add(normalizedKey)) {
                    throw IllegalArgumentException("Repeated security-sensitive authentication parameter.")
                }
                // This mirrors CloudStream's splitUrlParameters contract for ordinary duplicates.
                if (!result.containsKey(key)) result[key] = value
            }
            return result
        }

        private fun decodeQueryComponent(raw: String): String {
            val bytes = ByteArrayOutputStream(raw.length)
            var index = 0
            while (index < raw.length) {
                when (raw[index]) {
                    '%' -> {
                        if (index + 2 >= raw.length) {
                            throw IllegalArgumentException("Malformed percent escape in authentication redirect URL.")
                        }
                        val high = raw[index + 1].digitToIntOrNull(16)
                        val low = raw[index + 2].digitToIntOrNull(16)
                        if (high == null || low == null) {
                            throw IllegalArgumentException("Malformed percent escape in authentication redirect URL.")
                        }
                        bytes.write((high shl 4) or low)
                        index += 3
                    }
                    else -> {
                        val codePoint = raw.codePointAt(index)
                        val rawChars = String(Character.toChars(codePoint))
                        bytes.write(rawChars.toByteArray(StandardCharsets.UTF_8))
                        index += Character.charCount(codePoint)
                    }
                }
            }
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            } catch (malformed: Exception) {
                throw IllegalArgumentException("Malformed UTF-8 in authentication redirect URL.", malformed)
            }
        }

        fun generateCodeVerifier(): String {
            val secureRandom = SecureRandom()
            val bytes = ByteArray(96)
            secureRandom.nextBytes(bytes)
            return Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        }
    }
}
