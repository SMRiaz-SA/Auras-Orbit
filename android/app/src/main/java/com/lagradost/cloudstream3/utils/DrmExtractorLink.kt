package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.AudioFile
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Orbit-authored mutable DRM link value matching the public contract pinned at
 * recloudstream/cloudstream 5024ace2922a148d38df2a816b483b529789cf58.
 * It uses the upstream property and constructor shapes without copying source
 * implementation bodies; that upstream source is GPL-3.0.
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
open class DrmExtractorLink private constructor(
    source: String,
    name: String,
    url: String,
    referer: String,
    quality: Int,
    headers: Map<String, String>,
    extractorData: String?,
    type: ExtractorLinkType?,
    open var kid: String?,
    open var key: String?,
    open var uuid: Uuid,
    open var kty: String?,
    open var keyRequestParameters: HashMap<String, String>,
    open var licenseUrl: String?,
    audioTracks: List<AudioFile> = emptyList(),
) : ExtractorLink(
    source,
    name,
    url,
    referer,
    quality,
    headers,
    extractorData,
    type,
    audioTracks,
) {
    private var drmAudioTracks: List<AudioFile> = audioTracks

    override fun getAudioTracks(): List<AudioFile> = drmAudioTracks

    override fun setAudioTracks(audioTracks: List<AudioFile>) {
        drmAudioTracks = audioTracks
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use newDrmExtractorLink", level = DeprecationLevel.ERROR)
    constructor(
        source: String,
        name: String,
        url: String,
        referer: String? = null,
        quality: Int? = null,
        type: ExtractorLinkType? = null,
        headers: Map<String, String> = emptyMap(),
        extractorData: String? = null,
        kid: String? = null,
        key: String? = null,
        uuid: Uuid = ExtractorApiKt.getCLEARKEY_DRM_UUID(),
        kty: String? = "oct",
        keyRequestParameters: HashMap<String, String> = hashMapOf(),
        licenseUrl: String? = null,
    ) : this(
        source,
        name,
        url,
        referer ?: "",
        quality ?: 400,
        headers,
        extractorData,
        type,
        kid,
        key,
        uuid,
        kty,
        keyRequestParameters,
        licenseUrl,
        emptyList(),
    )

    @Suppress("DEPRECATION")
    @Deprecated("Use newDrmExtractorLink", level = DeprecationLevel.ERROR)
    constructor(
        source: String,
        name: String,
        url: String,
        referer: String,
        quality: Int,
        type: ExtractorLinkType?,
        headers: Map<String, String> = emptyMap(),
        extractorData: String? = null,
        kid: String? = null,
        key: String? = null,
        uuid: Uuid = ExtractorApiKt.getCLEARKEY_DRM_UUID(),
        kty: String? = "oct",
        keyRequestParameters: HashMap<String, String> = hashMapOf(),
        licenseUrl: String? = null,
    ) : this(
        source,
        name,
        url,
        referer,
        quality,
        headers,
        extractorData,
        type,
        kid,
        key,
        uuid,
        kty,
        keyRequestParameters,
        licenseUrl,
        emptyList(),
    )

    @Deprecated("Use Kotlin Uuid", level = DeprecationLevel.HIDDEN)
    fun setUuid(uuid: java.util.UUID) {
        this.uuid = uuid.toKotlinUuid()
    }

    @Deprecated("Use Kotlin Uuid", level = DeprecationLevel.HIDDEN)
    fun getUuid(): java.util.UUID = uuid.toJavaUuid()
}
