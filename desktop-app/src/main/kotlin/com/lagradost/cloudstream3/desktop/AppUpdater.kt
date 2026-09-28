package com.lagradost.cloudstream3.desktop

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReleaseAsset(val name: String, val browser_download_url: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubRelease(
    val tag_name: String,
    val name: String,
    val body: String?,
    val html_url: String,
    val published_at: String,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)

internal class ReleaseChecker(
    private val client: OkHttpClient,
    private val endpoint: () -> String,
    private val includePrereleases: () -> Boolean,
    private val currentVersion: () -> String,
) {
    private val mapper = jacksonObjectMapper()
    private val mutex = Mutex()
    private val _latestRelease = MutableStateFlow<GitHubRelease?>(null)
    val latestRelease = _latestRelease.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()
    private var hasChecked = false

    suspend fun checkForUpdates(force: Boolean = false) = mutex.withLock {
        if (hasChecked && !force) return@withLock
        withContext(Dispatchers.IO) {
            _lastError.value = null
            try {
                val request = Request.Builder()
                    .url(endpoint())
                    .header("Accept", "application/vnd.github+json").build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Release service returned HTTP ${response.code}" }
                    val releases = mapper.readValue<List<GitHubRelease>>(response.body.string())
                    val latest = selectRelease(releases, includePrereleases())
                    _latestRelease.value = latest?.takeIf { compareVersions(it.tag_name.removePrefix("v"), currentVersion()) > 0 }
                }
                hasChecked = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                hasChecked = false
                _latestRelease.value = null
                _lastError.value = failure.message ?: "Update service unavailable"
                com.lagradost.common.logging.AppLogger.e("Update check failed", failure)
            }
        }
    }

    internal fun selectRelease(releases: List<GitHubRelease>, includePrereleases: Boolean): GitHubRelease? =
        releases.filter { !it.draft && (includePrereleases || !it.prerelease) }
            .maxWithOrNull { a, b -> compareVersions(a.tag_name.removePrefix("v"), b.tag_name.removePrefix("v")) }

    internal fun compareVersions(v1: String, v2: String): Int = VersionComparator.compare(v1, v2)
}

object AppUpdater {
    private val checker = ReleaseChecker(
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build(),
        endpoint = { "https://api.github.com/repos/${AppConfig.GITHUB_REPO}/releases?per_page=100" },
        includePrereleases = { java.lang.Boolean.getBoolean("auras.updates.prereleases") },
        currentVersion = { AppConfig.APP_VERSION },
    )

    val latestRelease = checker.latestRelease
    val lastError = checker.lastError

    suspend fun checkForUpdates(force: Boolean = false) = checker.checkForUpdates(force)

    internal fun selectRelease(releases: List<GitHubRelease>, includePrereleases: Boolean): GitHubRelease? =
        checker.selectRelease(releases, includePrereleases)

    internal fun compareVersions(v1: String, v2: String): Int = checker.compareVersions(v1, v2)
}
