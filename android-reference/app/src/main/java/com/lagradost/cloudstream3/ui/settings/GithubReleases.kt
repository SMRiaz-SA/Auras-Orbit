package com.lagradost.cloudstream3.ui.settings

import androidx.compose.runtime.Immutable
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.Throws

object GithubReleases {
    @Serializable
    private data class GithubAsset(
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
            .sortedByDescending { it.createdAt }
            .mapNotNull { release ->
                // GitHub can label APK uploads as octet-stream, so accept the .apk suffix too.
                val asset = release.assets.firstOrNull { asset ->
                    asset.contentType == contentType || asset.name.endsWith(".apk", ignoreCase = true)
                } ?: return@mapNotNull null
                release to asset
            }
            .firstOrNull()

        if (releaseAndAsset == null) {
            return null
        }

        val (latestRelease, foundAsset) = releaseAndAsset

        return GithubFile(
            digest = foundAsset.digest,
            downloadUrl = foundAsset.browserDownloadUrl,
            displayName = foundAsset.name.substringBeforeLast("."),
            changeLog = latestRelease.body.orEmpty(),
            tagName = latestRelease.tagName,
            nodeId = latestRelease.nodeId
        )
    }
}
