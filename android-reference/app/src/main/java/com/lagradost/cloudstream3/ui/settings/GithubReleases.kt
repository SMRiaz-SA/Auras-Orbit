package com.lagradost.cloudstream3.ui.settings

import androidx.compose.runtime.Immutable
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.Throws

object GithubReleases {
    private val numericVersionRegex = Regex("""^(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?$""")

    @Serializable
    internal data class GithubAsset(
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("size") @SerialName("size") val size: Int, // Size in bytes
        @JsonProperty("browser_download_url") @SerialName("browser_download_url") val browserDownloadUrl: String,
        @JsonProperty("content_type") @SerialName("content_type") val contentType: String, // application/vnd.android.package-archive
        @JsonProperty("digest") @SerialName("digest") val digest: String? = null, // sha256:..., may be null
    )

    @Serializable
    private data class GithubRelease(
        @JsonProperty("tag_name") @SerialName("tag_name") val tagName: String, // Version code
        @JsonProperty("body") @SerialName("body") val body: String? = null, // Description
        @JsonProperty("assets") @SerialName("assets") val assets: List<GithubAsset>,
        @JsonProperty("target_commitish") @SerialName("target_commitish") val targetCommitish: String, // Branch
        @JsonProperty("prerelease") @SerialName("prerelease") val prerelease: Boolean,
        @JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
        @JsonProperty("created_at") @SerialName("created_at") val createdAt: String, // YYYY-MM-DDTHH:MM:SSZ
        @JsonProperty("draft") @SerialName("draft") val draft: Boolean = false,
    )

    /** GitHub file update package */
    @Immutable
    data class GithubFile(
        /** File digest, sha:xxx */
        val digest: String?,
        /** File url for download */
        val downloadUrl: String,
        /** Filename without the extension */
        val displayName: String,
        /** Changelog, aka the commit message */
        val changeLog: String,
        /** Name of the tag, aka unique release name like vX.X.X or pre-release */
        val tagName: String,
        /** Unique node id */
        val nodeId: String,
    )

    private val defaultHeaders = mapOf("Accept" to "application/vnd.github.v3+json")

    @Throws
    suspend fun getLatestReleaseFile(
        userName: String,
        repository: String,
        contentType: String,
    ): GithubFile? {
        // Read the latest published stable release directly.
        val releases = app.get(
            url = "https://api.github.com/repos/$userName/$repository/releases?per_page=100",
            headers = defaultHeaders
        ).parsed<Array<GithubRelease>>()

        val releaseAndAsset = releases.asSequence()
            .filterNot { it.draft }
            .filterNot { it.prerelease }
            .mapNotNull { release ->
                val versionCode = androidVersionCode(release.tagName) ?: return@mapNotNull null
                val asset = selectApkAsset(release.assets, contentType) ?: return@mapNotNull null
                Triple(release, asset, versionCode)
            }
            .maxByOrNull { it.third }

        if (releaseAndAsset == null) {
            return null
        }

        val (latestRelease, foundAsset, _) = releaseAndAsset

        return GithubFile(
            digest = foundAsset.digest,
            downloadUrl = foundAsset.browserDownloadUrl,
            displayName = latestRelease.tagName.removePrefix("v"),
            changeLog = latestRelease.body.orEmpty(),
            tagName = latestRelease.tagName,
            nodeId = latestRelease.nodeId
        )
    }

    internal fun isNewerStableVersion(candidate: String, current: String): Boolean {
        val candidateVersionCode = androidVersionCode(candidate) ?: return false
        val currentVersionCode = androidVersionCode(current) ?: return false
        return candidateVersionCode > currentVersionCode
    }

    internal fun selectApkAsset(assets: List<GithubAsset>, contentType: String): GithubAsset? {
        // GitHub can label APK uploads as octet-stream, so accept the .apk suffix too.
        val apkAssets = assets.filter { asset ->
            asset.browserDownloadUrl.isNotBlank() &&
                (asset.contentType.equals(contentType, ignoreCase = true) ||
                    asset.name.endsWith(".apk", ignoreCase = true))
        }
        return apkAssets.firstOrNull { it.name.equals("Auras-Orbit.apk", ignoreCase = true) }
            ?: apkAssets.firstOrNull()
    }

    private fun androidVersionCode(versionName: String): Long? {
        val match = numericVersionRegex.matchEntire(versionName.removePrefix("v")) ?: return null
        val major = match.groupValues[1].toLongOrNull() ?: return null
        val minor = match.groupValues[2].toLongOrNull() ?: return null
        val patch = match.groupValues[3].toLongOrNull() ?: return null
        val build = match.groupValues[4].takeIf(String::isNotEmpty)?.toLongOrNull() ?: 0L
        if (major !in 0..21 || minor !in 0..99 || patch !in 0..99 || build !in 0..9999) return null
        val versionCode = major * 100_000_000L + minor * 1_000_000L + patch * 10_000L + build
        return versionCode.takeIf { it in 1..2_100_000_000L }
    }
}
