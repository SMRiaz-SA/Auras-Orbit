package com.lagradost.common.storage

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Stable, collision-resistant identifiers for grouping a profile's watch history. */
internal object WatchHistoryKey {
    fun create(
        profileId: Int,
        apiName: String,
        showUrl: String,
        season: Int? = null,
        episode: Int? = null,
        episodeData: String? = null,
    ): String {
        val base = base(profileId, apiName, showUrl)
        return if (season != null || episode != null || !episodeData.isNullOrBlank()) {
            "${base}_s${season ?: 0}_e${episode ?: 0}_${digest(episodeData.orEmpty())}"
        } else {
            base
        }
    }

    /** Replaces the legacy 32-bit URL hash while preserving any legacy episode suffix. */
    fun migrateLegacyId(
        parentId: String,
        apiName: String,
        showUrl: String,
        season: Long?,
        episode: Long?,
        episodeId: String,
    ): String? {
        val parsedProfileId = PROFILE_PREFIX.find(parentId)?.groupValues?.get(1)?.toIntOrNull()
        val scopedPrefix = parsedProfileId?.let { "p${it}_${apiName}_${showUrl.hashCode()}" }
        val legacyPrefix = "${apiName}_${showUrl.hashCode()}"
        val (profileId, oldPrefix) = when {
            scopedPrefix != null && hasLegacyKeyBoundary(parentId, scopedPrefix) -> parsedProfileId to scopedPrefix
            hasLegacyKeyBoundary(parentId, legacyPrefix) -> 0 to legacyPrefix
            else -> return null
        }

        val oldSuffix = parentId.substring(oldPrefix.length)
        if (oldSuffix.isEmpty()) return base(profileId, apiName, showUrl)

        val expectedEpisodeSuffix = "_s${season ?: 0}_e${episode ?: 0}_${episodeId.hashCode()}"
        if (oldSuffix != expectedEpisodeSuffix ||
            (season == null && episode == null && episodeId.isBlank())
        ) {
            return null
        }

        return base(profileId, apiName, showUrl) + "_s${season ?: 0}_e${episode ?: 0}_${digest(episodeId)}"
    }

    private fun hasLegacyKeyBoundary(parentId: String, prefix: String): Boolean =
        parentId == prefix || parentId.startsWith("${prefix}_")

    private fun base(profileId: Int, apiName: String, showUrl: String): String =
        "p${profileId}_${digest("$apiName\u0000$showUrl")}"

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(hex[unsigned ushr 4])
                append(hex[unsigned and 0x0f])
            }
        }
    }

    private val PROFILE_PREFIX = Regex("^p(\\d+)_")
}
