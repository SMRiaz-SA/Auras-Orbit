package com.lagradost.cloudstream3

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Orbit-owned audio track value matching the public shape pinned at
 * recloudstream/cloudstream 5024ace2922a148d38df2a816b483b529789cf58.
 * The upstream source is GPL-3.0; this compatibility declaration preserves
 * its serialization metadata without copying upstream implementation bodies.
 */
@ConsistentCopyVisibility
@Serializable
data class AudioFile internal constructor(
    @JsonProperty("url") @SerialName("url") var url: String,
    @JsonProperty("headers") @SerialName("headers") var headers: Map<String, String>? = null,
)
