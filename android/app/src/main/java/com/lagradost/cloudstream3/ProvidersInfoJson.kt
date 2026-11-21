package com.lagradost.cloudstream3

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Provider override record used by the pinned CloudStream MainAPI initialization path. */
@Serializable
data class ProvidersInfoJson(
    @JsonProperty("name") @SerialName("name") var name: String,
    @JsonProperty("url") @SerialName("url") var url: String,
    @JsonProperty("credentials") @SerialName("credentials") var credentials: String? = null,
    @JsonProperty("status") @SerialName("status") var status: Int,
)
