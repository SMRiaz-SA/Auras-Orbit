package com.lagradost.cloudstream3.desktop.repo

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.lagradost.cloudstream3.desktop.network.AutoRetryInterceptor
import com.lagradost.cloudstream3.desktop.network.DevNetworkInterceptor
import com.lagradost.cloudstream3.desktop.network.RateLimitInterceptor
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.net.readBoundedBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import java.net.ProtocolException

/**
 * Internal network utility for repository and plugin list fetching.
 * Not part of the public API — consumed only by [DesktopRepositoryManager].
 */
internal object PluginNetworkClient {

    private data class RepositoryTransportOrigin(val url: String)

    /** OkHttp client that follows safe redirects. Used for all repository content fetches. */
    internal val redirectClient by lazy {
        val builder = com.lagradost.cloudstream3.app.baseClient.newBuilder()
            .followRedirects(true)
            .followSslRedirects(false)
            .connectTimeout(java.time.Duration.ofSeconds(4))
            .readTimeout(java.time.Duration.ofSeconds(6))
            .callTimeout(java.time.Duration.ofSeconds(15))
        // Strip scraper-only interceptors — repo fetches are static JSON, not scrapers.
        // RateLimitInterceptor queues 20+ concurrent requests to the same host behind a
        // 500ms/host lock, easily blowing the callTimeout before the request is even sent.
        builder.interceptors().removeAll { it is RateLimitInterceptor || it is AutoRetryInterceptor || it is DevNetworkInterceptor }
        builder.addInterceptor(repositoryTransportOrigin)
        builder.addNetworkInterceptor(repositoryTransportPolicy)
        builder.build()
    }

    /** Binary plugin archives need longer transfer timeouts than small repository manifests. */
    internal val pluginDownloadClient by lazy {
        redirectClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(60))
            .callTimeout(java.time.Duration.ofMinutes(5))
            .build()
    }

    /** OkHttp client that does NOT follow redirects. Used for short-link resolution. */
    private val noRedirectClient by lazy {
        val builder = com.lagradost.cloudstream3.app.baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .readTimeout(java.time.Duration.ofSeconds(4))
        builder.interceptors().removeAll { it is RateLimitInterceptor || it is AutoRetryInterceptor || it is DevNetworkInterceptor }
        builder.addInterceptor(repositoryTransportOrigin)
        builder.addNetworkInterceptor(repositoryTransportPolicy)
        builder.build()
    }

    private val repositoryTransportPolicy = Interceptor { chain ->
        val request = chain.request()
        val origin = request.tag(RepositoryTransportOrigin::class.java)?.url ?: request.url.toString()
        if (!isAllowedRepositoryRequest(request.url.toString(), origin)) {
            throw ProtocolException("Repository and plugin downloads require HTTPS")
        }
        chain.proceed(request)
    }

    private val repositoryTransportOrigin = Interceptor { chain ->
        val request = chain.request()
        val taggedRequest = if (request.tag(RepositoryTransportOrigin::class.java) == null) {
            request.newBuilder()
                .tag(RepositoryTransportOrigin::class.java, RepositoryTransportOrigin(request.url.toString()))
                .build()
        } else {
            request
        }
        chain.proceed(taggedRequest)
    }

    /** HTTPS is required for remote repositories; loopback HTTP remains available for local development. */
    internal fun isAllowedRepositoryUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.scheme == "https" ||
            (parsed.scheme == "http" && parsed.host in LOOPBACK_HOSTS)
    }

    /** Loopback repositories are a development exception for direct local requests, never remote redirects. */
    internal fun isAllowedRepositoryRequest(url: String, originalUrl: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val original = originalUrl.toHttpUrlOrNull() ?: return false
        val targetsLoopback = parsed.host in LOOPBACK_HOSTS
        val beganOnLoopback = original.host in LOOPBACK_HOSTS
        return isAllowedRepositoryUrl(url) && (!targetsLoopback || beganOnLoopback)
    }

    /** Adds the transport guard to injectable download clients as well as the production clients. */
    internal fun enforceRepositoryTransport(client: okhttp3.OkHttpClient): okhttp3.OkHttpClient =
        client.newBuilder()
            .followSslRedirects(false)
            .addInterceptor(repositoryTransportOrigin)
            .addNetworkInterceptor(repositoryTransportPolicy)
            .build()

    /** Shared Jackson mapper — lenient, ignores unknown properties. */
    internal val mapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /**
     * Resolves a user-provided input (short codes, custom schemes, plain URLs) to a
     * canonical HTTPS URL. Returns null if the input cannot be resolved.
     */
    suspend fun parseRepoUrl(url: String): String? = withContext(Dispatchers.IO) {
        val fixedUrl = url.trim()
        if (fixedUrl.matches(Regex("^[a-zA-Z0-9!_-]+$"))) {
            val request = Request.Builder().url("https://cutt.ly/$fixedUrl").build()
            noRedirectClient.newCall(request).execute().use { response ->
                val loc = response.header("Location")
                val resolvedLocation = loc?.let { response.request.url.resolve(it)?.toString() }
                if (resolvedLocation != null &&
                    !resolvedLocation.startsWith("https://cutt.ly/404") &&
                    isAllowedRepositoryUrl(resolvedLocation)
                ) {
                    return@withContext resolvedLocation
                }
            }
            return@withContext null
        }
        if (fixedUrl.contains(Regex("^(cloudstreamrepo://)|(https://cs\\.repo/\\??)"))) {
            val expanded = fixedUrl
                .replace(Regex("^(cloudstreamrepo://)|(https://cs\\.repo/\\??)"), "")
                .let { if (!it.startsWith("http")) "https://$it" else it }
            return@withContext expanded.takeIf(::isAllowedRepositoryUrl)
        }
        if (!isAllowedRepositoryUrl(fixedUrl)) return@withContext null
        return@withContext fixedUrl
    }

    /**
     * Resolves a relative or absolute URL string against a base URL.
     * Guarantees a fully qualified HTTPS URL, except for loopback HTTP used in local development.
     */
    fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String {
        val trimmed = relativeOrAbsolute.trim()
        val resolved = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            try {
                val baseUri = java.net.URI(baseUrl)
                baseUri.resolve(trimmed).toString()
            } catch (_: Exception) {
                val base = baseUrl.substringBeforeLast('/')
                "$base/${trimmed.removePrefix("./").removePrefix("/")}"
            }
        }
        require(isAllowedRepositoryRequest(resolved, baseUrl)) {
            "Repository and plugin URLs must use HTTPS unless the repository itself is local"
        }
        return resolved
    }

    /** Fetches and parses a [Repository] manifest JSON from [url]. Returns null on failure. */
    suspend fun fetchRepository(url: String): Repository? = withContext(Dispatchers.IO) {
        val finalUrl = parseRepoUrl(url) ?: return@withContext null
        val request = Request.Builder().url(finalUrl).build()
        try {
            redirectClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body.byteStream().readBoundedBytes(8 * 1024 * 1024).toString(Charsets.UTF_8)
                if (body.trimStart().startsWith("<")) {
                    AppLogger.i("Repo fetch from $url returned HTML — likely behind a WAF.")
                    return@withContext null
                }
                val trimmedBody = body.trim()
                if (trimmedBody.startsWith("[")) {
                    // Check if it's a direct plugins.json list
                    val sampleFirst = mapper.readTree(trimmedBody).firstOrNull()
                    if (sampleFirst != null && sampleFirst.has("internalName") && sampleFirst.has("url")) {
                        val repoName = finalUrl.substringBeforeLast('/').substringAfterLast('/').ifBlank { "Custom Repository" }
                        return@withContext Repository(
                            name = repoName,
                            description = "Imported plugin repository",
                            manifestVersion = 1,
                            pluginLists = listOf(finalUrl),
                            iconUrl = null,
                        )
                    }
                }

                val rawRepo = mapper.readValue(body, Repository::class.java)
                val rawLists = if (rawRepo.pluginLists.isNullOrEmpty()) {
                    listOf(
                        if (finalUrl.endsWith("repo.json", ignoreCase = true)) {
                            finalUrl.replace(Regex("repo\\.json$", RegexOption.IGNORE_CASE), "builds/plugins.json")
                        } else if (finalUrl.endsWith("plugins.json", ignoreCase = true)) {
                            finalUrl
                        } else {
                            "${finalUrl.trimEnd('/')}/builds/plugins.json"
                        },
                    )
                } else {
                    rawRepo.pluginLists
                }

                val resolvedLists = rawLists.map { listUrl ->
                    resolveUrl(finalUrl, listUrl)
                }
                val resolvedIcon = rawRepo.iconUrl?.takeIf { it.isNotBlank() }?.let { resolveUrl(finalUrl, it) }
                return@withContext rawRepo.copy(
                    iconUrl = resolvedIcon,
                    pluginLists = resolvedLists,
                )
            }
        } catch (e: Exception) {
            AppLogger.i("Failed to fetch repository $url: ${e.message}")
            return@withContext null
        }
    }

    /** Null means failure; an empty list is a successful empty catalog. */
    suspend fun fetchPlugins(pluginListUrl: String): List<SitePlugin>? = withContext(Dispatchers.IO) {
        if (!isAllowedRepositoryUrl(pluginListUrl)) return@withContext null
        try {
            val request = Request.Builder().url(pluginListUrl).build()
            redirectClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body.byteStream().readBoundedBytes(8 * 1024 * 1024).toString(Charsets.UTF_8)
                if (body.trimStart().startsWith("<")) {
                    AppLogger.i("Plugin list from $pluginListUrl returned HTML — likely behind a WAF.")
                    return@withContext null
                }
                val rawPlugins = mapper.readValue(body, object : TypeReference<List<SitePlugin>>() {})
                return@withContext rawPlugins
                    .filter { it.status != 0 }
                    .map { plugin ->
                        val resolvedUrl = resolveUrl(pluginListUrl, plugin.url)
                        val resolvedJarUrl = plugin.jarUrl?.takeIf { it.isNotBlank() }?.let { resolveUrl(pluginListUrl, it) }
                        val resolvedIconUrl = plugin.iconUrl?.takeIf { it.isNotBlank() }?.let { resolveUrl(pluginListUrl, it) }
                        plugin.copy(
                            url = resolvedUrl,
                            jarUrl = resolvedJarUrl,
                            iconUrl = resolvedIconUrl,
                        )
                    }
            }
        } catch (e: Exception) {
            AppLogger.i("Failed to fetch or parse plugins from $pluginListUrl: ${e.message}")
            null
        }
    }

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
}
