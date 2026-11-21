package com.lagradost.cloudstream3.syncproviders

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/** Access and refresh credentials returned by an authentication provider. */
@Serializable
data class AuthToken(
    @JsonProperty("accessToken") @SerialName("accessToken") val accessToken: String? = null,
    @JsonProperty("refreshToken") @SerialName("refreshToken") val refreshToken: String? = null,
    @JsonProperty("accessTokenLifetime") @SerialName("accessTokenLifetime") val accessTokenLifetime: Long? = null,
    @JsonProperty("refreshTokenLifetime") @SerialName("refreshTokenLifetime") val refreshTokenLifetime: Long? = null,
    @JsonProperty("payload") @SerialName("payload") val payload: String? = null,
) {
    fun isAccessTokenExpired(marginSec: Long = 10L): Boolean =
        accessTokenLifetime != null && currentUnixTime() + marginSec >= accessTokenLifetime

    fun isRefreshTokenExpired(marginSec: Long = 10L): Boolean =
        refreshTokenLifetime != null && currentUnixTime() + marginSec >= refreshTokenLifetime
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AuthUser(
    @JsonProperty("name") @SerialName("name") val name: String?,
    @JsonProperty("id") @SerialName("id") val id: Int,
    @JsonProperty("profilePicture") @SerialName("profilePicture") val profilePicture: String? = null,
    @JsonProperty("profilePictureHeaders") @JsonAlias("profilePictureHeader")
    @SerialName("profilePictureHeaders") @JsonNames("profilePictureHeader")
    val profilePictureHeaders: Map<String, String>? = null,
)

/** User identity and credentials are persisted together, scoped by profile and provider. */
@Serializable
data class AuthData(
    @JsonProperty("user") @SerialName("user") val user: AuthUser,
    @JsonProperty("token") @SerialName("token") val token: AuthToken,
)

@Serializable
data class AuthLoginPage(
    /** Browser URL requested by the provider. */
    val url: String,
    /** Optional state/control code returned to the provider on login. */
    val payload: String? = null,
)

data class AuthPinData(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresIn: Int,
    val interval: Int,
)

data class AuthLoginRequirement(
    val password: Boolean = false,
    val username: Boolean = false,
    val email: Boolean = false,
    val server: Boolean = false,
)

@Serializable
data class AuthLoginResponse(
    @JsonProperty("password") @SerialName("password") val password: String?,
    @JsonProperty("username") @SerialName("username") val username: String?,
    @JsonProperty("email") @SerialName("email") val email: String?,
    @JsonProperty("server") @SerialName("server") val server: String?,
)

internal fun currentUnixTime(): Long = System.currentTimeMillis() / 1000L
