package org.example.orbitworker

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.PlayListItem
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.common.storage.PluginSettingsSchemaRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File

class WorkerFixturePlugin : Plugin() {
    private object Settings {
        @Volatile
        var context: Context? = null
    }

    init {
        File("worker-fixture-constructor.txt").writeText("constructed")
    }

    override fun load(context: Context) {
        Settings.context = context
        // Mirror providers that declare plugin settings during load. The worker replays these
        // schemas after every process restart, before it sends the ready response.
        PluginSettingsSchemaRegistry.register("WorkerFixture_", "relay_mode", "String", "worker-default")
        context.getSharedPreferences("WorkerFixture", Context.MODE_PRIVATE)
            .getString("relay_mode", "worker-default")
        registerMainAPI(Provider())
        registerExtractorAPI(FixtureExtractor())
        openSettings = {
            File("worker-fixture-settings-opened.txt").writeText("opened")
        }
        registerVideoClickAction(
            VideoClickAction("Worker Fixture Action", 7, requiresAuthentication = true) {
                File("worker-fixture-action-invoked.txt").writeText("invoked")
            },
        )
    }

    override fun beforeUnload() {
        File("worker-fixture-before-unload.txt").writeText("unloaded")
    }

    class Provider : MainAPI() {
        override var name = "Worker Fixture"
        override var mainUrl = "https://worker-fixture.invalid"
        override val searchTimeoutMs: Long? = 10_000

        override fun getVideoInterceptor(extractorLink: ExtractorLink): okhttp3.Interceptor? {
            if (extractorLink is DrmExtractorLink) return FixtureDrmVideoInterceptor(extractorLink.kid.orEmpty())
            if (extractorLink.url.contains("no-interceptor")) return null
            if (extractorLink.url.contains("large-response-interceptor")) return FixtureLargeResponseVideoInterceptor()
            if (extractorLink.url.contains("response-interceptor")) return FixtureResponseVideoInterceptor()
            return FixtureVideoInterceptor()
        }

        private class FixtureVideoInterceptor : okhttp3.Interceptor {
            override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response =
                chain.proceed(chain.request().newBuilder().header("X-Worker-Interceptor", "active").build())
        }

        private class FixtureDrmVideoInterceptor(private val keyId: String) : okhttp3.Interceptor {
            override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response =
                chain.proceed(chain.request().newBuilder().header("X-Worker-Drm-Kid", keyId).build())
        }

        private class FixtureResponseVideoInterceptor : okhttp3.Interceptor {
            override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
                val response = chain.proceed(chain.request().newBuilder().header("X-Worker-Interceptor", "response").build())
                val originalBody = response.body.string()
                check(originalBody == "host response body") { "Worker did not receive the actual host response body" }
                return response.newBuilder()
                    .code(206)
                    .message("Worker transformed response")
                    .header("X-Worker-Response", "active")
                    .removeHeader("X-Remove-Me")
                    .body("worker transformed: $originalBody".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
        }

        private class FixtureLargeResponseVideoInterceptor : okhttp3.Interceptor {
            override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response =
                chain.proceed(chain.request()).newBuilder().header("X-Worker-Response", "large").build()
        }

        override suspend fun search(query: String): List<SearchResponse>? {
            check(
                Settings.context
                    ?.getSharedPreferences("WorkerFixture", Context.MODE_PRIVATE)
                    ?.getString("relay_mode", "worker-default") == "worker-default",
            )
            if (query == "spin") {
                while (true) {
                    // Deliberately ignore coroutine cancellation; only process termination can stop this.
                }
            }
            return listOf(newMovieSearchResponse("Worker process result", "$mainUrl/result"))
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            if (data == "specialized") {
                callback(
                    newDrmExtractorLink(
                        source = "Worker Fixture",
                        name = "Protected fixture stream",
                        url = "$mainUrl/protected.mpd",
                        type = ExtractorLinkType.DASH,
                        uuid = java.util.UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"),
                    ) {
                        kid = "fixture-kid"
                        key = "fixture-key"
                        licenseUrl = "$mainUrl/license"
                    },
                )
                callback(
                    ExtractorLinkPlayList(
                        source = "Worker Fixture",
                        name = "Concatenated fixture stream",
                        playlist = listOf(PlayListItem("$mainUrl/segment.ts", 10_000_000)),
                        referer = mainUrl,
                        quality = 720,
                        type = ExtractorLinkType.M3U8,
                    ),
                )
                return true
            }
            if (data == "cross-plugin") {
                return loadExtractor(
                    "https://independent-catalog-xyz.invalid/embed",
                    referer = mainUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
            }
            if (data != "stream") return false
            @Suppress("DEPRECATION")
            callback(
                ExtractorLink(
                    source = "Worker Fixture",
                    name = "Fixture stream",
                    url = "$mainUrl/video.mp4",
                    referer = "",
                    quality = 1080,
                    type = ExtractorLinkType.VIDEO,
                ),
            )
            @Suppress("DEPRECATION")
            subtitleCallback(SubtitleFile("English", "$mainUrl/subtitles.vtt").apply { headers = mapOf("X-Fixture" to "subtitle") })
            if (isCasting) {
                while (true) {
                    // The host must receive callback events while the child is still executing.
                }
            }
            return true
        }
    }

    class FixtureExtractor : ExtractorApi() {
        override val name = "Worker Fixture Extractor"
        override val mainUrl = "https://fixture-extractor.invalid"
        override val requiresReferer = false

        override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> = listOf(
            ExtractorLink(
                source = name,
                name = "Remote extractor result",
                url = "$url/video.mp4",
                referer = referer.orEmpty(),
                quality = 720,
                type = ExtractorLinkType.VIDEO,
            ),
        )
    }
}

class WorkerCrossExtractorPlugin : Plugin() {
    override fun load(context: Context) {
        registerExtractorAPI(CrossExtractor())
    }

    class CrossExtractor : ExtractorApi() {
        override val name = "Worker Cross Extractor"
        override val mainUrl = "https://independent-catalog-xyz.invalid"
        override val requiresReferer = true

        override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> = listOf(
            ExtractorLink(
                source = name,
                name = "Cross-worker stream",
                url = "$url/resolved.m3u8",
                referer = referer.orEmpty(),
                quality = 1080,
                type = ExtractorLinkType.M3U8,
            ),
        )

        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ) {
            callback(getUrl(url, referer).single())
            subtitleCallback(SubtitleFile("Cross Worker", "$url/subtitles.vtt"))
        }
    }
}
