package com.lagradost.cloudstream3.desktop.pluginworker

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.PluginSettingsSchemaRegistry
import com.lagradost.runtime.executor.PluginWorkerTimeoutException
import com.lagradost.runtime.loader.ExtensionLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.example.orbitworker.WorkerBootstrapFailureFixturePlugin
import org.example.orbitworker.WorkerCrossExtractorPlugin
import org.example.orbitworker.WorkerFixturePlugin
import org.example.orbitworker.WorkerHealthyFixturePlugin
import org.example.orbitworker.WorkerTrustedFixturePlugin
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginProviderWorkerIntegrationTest {
    @Test
    fun providerRunsInChildAndRestartsAfterNonCooperativeTimeout() = runBlocking {
        PluginSettingsSchemaRegistry.schemas.remove("WorkerFixture_")
        val testRoot = Files.createTempDirectory("auras-provider-worker-").toFile()
        // Preserve a non-canonical path to exercise the same identity mismatch as Windows short paths.
        val pluginFile = createFixturePlugin(File(testRoot, "."))
        val crossExtractorPluginFile = createCrossExtractorPlugin(testRoot)
        val healthyPluginFile = createHealthyPlugin(testRoot)
        val trustedPluginFile = createTrustedPlugin(testRoot)
        val dataDirectory = PlatformPaths.appDataDir
        val previousLoadedHook = ExtensionLoader.onPluginLoaded
        val previousUnloadedHook = ExtensionLoader.onPluginUnloaded
        val previousIsolatedPluginFactory = ExtensionLoader.isolatedPluginFactory
        val unloadedPluginPaths = mutableListOf<String>()

        try {
            ExtensionLoader.isolatedPluginFactory = { _, _ -> IsolatedDesktopPluginHandle() }
            ExtensionLoader.onPluginLoaded = { file ->
                PluginProviderWorkerRegistry.movePluginProvidersToWorkers(file, dataDirectory)
            }
            ExtensionLoader.onPluginUnloaded = { path ->
                unloadedPluginPaths += path
                PluginProviderWorkerRegistry.closeForPlugin(path)
            }
            val hostHandle = ExtensionLoader.loadAndInit(pluginFile)
            ExtensionLoader.loadAndInit(crossExtractorPluginFile)
            ExtensionLoader.loadAndInit(healthyPluginFile)
            ExtensionLoader.onPluginLoaded = { file ->
                PluginProviderWorkerRegistry.movePluginProvidersToWorkers(file, dataDirectory)
            }
            ExtensionLoader.loadAndInit(trustedPluginFile)
            assertTrue(hostHandle is com.lagradost.runtime.loader.IsolatedPluginHandle)
            val matchingProviders = APIHolder.allProviders.filterIsInstance<WorkerBackedMainAPI>()
                .filter { it.sourcePlugin == pluginFile.absolutePath }
            assertEquals(1, matchingProviders.size, "matching worker providers: ${matchingProviders.map { it.name }}")
            val provider = matchingProviders.single()
            assertEquals("Worker Fixture", provider.name, "descriptor must be reported by the child-loaded plugin")
            val healthyProvider = APIHolder.allProviders.filterIsInstance<WorkerBackedMainAPI>()
                .single { it.sourcePlugin == healthyPluginFile.absolutePath }
            assertEquals("Healthy Worker Fixture", healthyProvider.name)
            val trustedProvider = APIHolder.allProviders.filterIsInstance<WorkerBackedMainAPI>()
                .single { it.sourcePlugin == trustedPluginFile.absolutePath }
            assertEquals("Trusted Worker Fixture", trustedProvider.name)
            assertEquals("Trusted worker result", withTimeout(30_000) { trustedProvider.search("normal") }?.single()?.name)
            val hostPlugin = ExtensionLoader.getPlugin(pluginFile.absolutePath) as com.lagradost.cloudstream3.plugins.Plugin
            val forgedSchema = ProviderRpcJson.mapper.createObjectNode().apply {
                put("event", "pluginSettingSchema")
                put("pluginPrefName", "AnotherPlugin_")
                put("key", "forged_setting")
                put("type", "String")
                put("defaultValue", "should-not-register")
            }
            assertFailsWith<IllegalArgumentException> {
                PluginProviderWorkerRegistry.handlePluginWorkerEvent(pluginFile.canonicalPath, forgedSchema.toString().toByteArray())
            }
            assertFalse(PluginSettingsSchemaRegistry.hasSettings("AnotherPlugin_"))
            val largeUnrelatedEvent = ProviderRpcJson.mapper.createObjectNode().apply {
                put("event", "hostRequest")
                put("payload", "x".repeat(70 * 1024))
            }.toString().toByteArray()
            assertTrue(largeUnrelatedEvent.size > 64 * 1024)
            assertFalse(PluginProviderWorkerRegistry.handlePluginWorkerEvent(pluginFile.canonicalPath, largeUnrelatedEvent))
            assertNotNull(hostPlugin.openSettings, "custom settings callback must be represented by the host facade")
            hostPlugin.openSettings!!.invoke(android.content.DesktopContextProvider.context)
            assertEquals("opened", testRoot.walkTopDown().first { it.name == "worker-fixture-settings-opened.txt" }.readText())
            val hostAction = VideoClickActionHolder.allVideoClickActions.single { it.sourcePlugin == pluginFile.absolutePath }
            assertEquals("Worker Fixture Action", hostAction.name)
            assertEquals(7, hostAction.iconId)
            assertTrue(hostAction.requiresAuthentication)
            hostAction.callback.invoke()
            assertEquals("invoked", testRoot.walkTopDown().first { it.name == "worker-fixture-action-invoked.txt" }.readText())
            val matchingExtractors = com.lagradost.cloudstream3.utils.extractorApis.filterIsInstance<WorkerBackedExtractorApi>()
                .filter { it.sourcePlugin == pluginFile.absolutePath }
            assertEquals(1, matchingExtractors.size, "matching worker extractors: ${matchingExtractors.map { it.name }}")
            val remoteExtractor = matchingExtractors.single()
            val extractorLinks = withTimeout(30_000) { remoteExtractor.getUrl("https://fixture-extractor.invalid/page", null) }
            assertEquals("https://fixture-extractor.invalid/page/video.mp4", extractorLinks?.single()?.url)
            val streamedExtractorLinks = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
            withTimeout(30_000) {
                remoteExtractor.getUrl(
                    "https://fixture-extractor.invalid/stream",
                    null,
                    subtitleCallback = {},
                    callback = streamedExtractorLinks::add,
                )
            }
            assertEquals("https://fixture-extractor.invalid/stream/video.mp4", streamedExtractorLinks.single().url)

            withTimeout(30_000) {
                assertFailsWith<PluginWorkerTimeoutException> { provider.search("spin") }
            }
            val killedProcessId = provider.lastWorkerProcessId ?: error("Provider worker never started")
            assertTrue(ProcessHandle.of(killedProcessId).map { !it.isAlive }.orElse(true), "timed-out provider worker must be dead")

            val recovered = withTimeout(30_000) { provider.search("normal") }
            assertEquals("Worker process result", recovered?.single()?.name)
            val workerSetting = PluginSettingsSchemaRegistry.getSettingsForPlugin("WorkerFixture_")
                .single { it.key == "relay_mode" }
            assertEquals("worker-default", workerSetting.defaultValue)
            assertNotEquals(ProcessHandle.current().pid(), provider.workerProcessId)
            assertEquals(2, provider.workerStartCount)

            val crossPluginLinks = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
            val crossPluginSubtitles = mutableListOf<com.lagradost.cloudstream3.SubtitleFile>()
            val crossPluginLoaded = withTimeout(30_000) {
                provider.loadLinks(
                    "cross-plugin",
                    isCasting = false,
                    subtitleCallback = crossPluginSubtitles::add,
                    callback = crossPluginLinks::add,
                )
            }
            assertTrue(crossPluginLoaded)
            assertEquals("https://independent-catalog-xyz.invalid/embed/resolved.m3u8", crossPluginLinks.single().url)
            assertEquals("https://independent-catalog-xyz.invalid/embed/subtitles.vtt", crossPluginSubtitles.single().url)

            val specializedLinks = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
            assertTrue(
                withTimeout(30_000) {
                    provider.loadLinks("specialized", isCasting = false, subtitleCallback = {}, callback = specializedLinks::add)
                },
            )
            val remoteDrmLink = assertIs<com.lagradost.cloudstream3.utils.DrmExtractorLink>(specializedLinks[0])
            assertEquals("fixture-kid", remoteDrmLink.kid)
            assertEquals("fixture-key", remoteDrmLink.key)
            assertEquals("https://worker-fixture.invalid/license", remoteDrmLink.licenseUrl)
            val remotePlaylistLink = assertIs<com.lagradost.cloudstream3.utils.ExtractorLinkPlayList>(specializedLinks[1])
            assertEquals("https://worker-fixture.invalid/segment.ts", remotePlaylistLink.playlist.single().url)

            val drmInterceptor = provider.getVideoInterceptor(remoteDrmLink) ?: error("DRM worker interceptor was not returned")
            val drmInterceptorChain = FixtureInterceptorChain(Request.Builder().url(remoteDrmLink.url).build())
            drmInterceptor.intercept(drmInterceptorChain).close()
            assertEquals("fixture-kid", drmInterceptorChain.proceededRequest?.header("X-Worker-Drm-Kid"))

            ExtensionLoader.unloadPlugin(crossExtractorPluginFile.absolutePath)
            assertTrue(unloadedPluginPaths.any { it == crossExtractorPluginFile.absolutePath }, "plugin unload hook must invalidate worker catalogs")
            assertTrue(
                com.lagradost.cloudstream3.utils.extractorApis.filterIsInstance<WorkerBackedExtractorApi>()
                    .none { it.name == "Worker Cross Extractor" },
                "unloaded plugin extractor must be removed from the host catalog; remaining=" +
                    com.lagradost.cloudstream3.utils.extractorApis.filterIsInstance<WorkerBackedExtractorApi>()
                        .filter { it.name == "Worker Cross Extractor" }
                        .map { "${it.sourcePlugin} (target=${crossExtractorPluginFile.absolutePath})" },
            )
            val linksAfterCrossExtractorUnload = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
            val subtitlesAfterCrossExtractorUnload = mutableListOf<com.lagradost.cloudstream3.SubtitleFile>()
            withTimeout(30_000) {
                provider.loadLinks(
                    "cross-plugin",
                    isCasting = false,
                    subtitleCallback = subtitlesAfterCrossExtractorUnload::add,
                    callback = linksAfterCrossExtractorUnload::add,
                )
            }
            assertTrue(linksAfterCrossExtractorUnload.isEmpty(), "an unloaded extractor must not emit links")
            assertTrue(subtitlesAfterCrossExtractorUnload.isEmpty(), "an unloaded extractor must not emit subtitles")

            val link = com.lagradost.cloudstream3.utils.newExtractorLink(
                source = "Worker Fixture",
                name = "Fixture stream",
                url = "https://worker-fixture.invalid/video.mp4",
                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
            ) {
                referer = ""
                quality = 1080
            }
            val remoteInterceptor = provider.getVideoInterceptor(link) ?: error("Worker interceptor was not returned")
            val hostChain = FixtureInterceptorChain(Request.Builder().url(link.url).build())
            remoteInterceptor.intercept(hostChain).close()
            assertEquals("active", hostChain.proceededRequest?.header("X-Worker-Interceptor"))
            val responseLink = com.lagradost.cloudstream3.utils.newExtractorLink(
                source = link.source,
                name = link.name,
                url = "https://worker-fixture.invalid/response-interceptor.mp4",
                type = link.type,
            ) {
                referer = link.referer
                quality = link.quality
            }
            val responseInterceptor = provider.getVideoInterceptor(responseLink) ?: error("Worker response interceptor was not returned")
            val responseChain = FixtureInterceptorChain(Request.Builder().url(responseLink.url).build())
            val transformedResponse = responseInterceptor.intercept(responseChain)
            transformedResponse.use { response ->
                assertEquals("response", responseChain.proceededRequest?.header("X-Worker-Interceptor"))
                assertEquals(206, response.code)
                assertEquals("Worker transformed response", response.message)
                assertEquals("active", response.header("X-Worker-Response"))
                assertEquals("preserved", response.header("X-Host-Response"))
                assertEquals(null, response.header("X-Remove-Me"), "a plugin must be able to remove a host response header")
                assertEquals("worker transformed: host response body", response.body.string())
            }
            val largeResponseLink = com.lagradost.cloudstream3.utils.newExtractorLink(
                source = link.source,
                name = link.name,
                url = "https://worker-fixture.invalid/large-response-interceptor.mp4",
                type = link.type,
            ) {
                referer = link.referer
                quality = link.quality
            }
            val largeResponseChain = FixtureInterceptorChain(Request.Builder().url(largeResponseLink.url).build())
            provider.getVideoInterceptor(largeResponseLink)!!.intercept(largeResponseChain).use { response ->
                assertEquals("large", response.header("X-Worker-Response"))
                val returnedBytes = response.body.bytes()
                assertEquals(1024 * 1024 + 1, returnedBytes.size)
                assertTrue(returnedBytes.all { it == 'L'.code.toByte() }, "large media body should remain intact without IPC buffering")
            }
            val noInterceptorLink = com.lagradost.cloudstream3.utils.newExtractorLink(
                source = "Worker Fixture",
                name = "Fixture stream",
                url = "https://worker-fixture.invalid/no-interceptor.mp4",
                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
            ) {
                referer = ""
                quality = 1080
            }
            assertEquals(null, provider.getVideoInterceptor(noInterceptorLink))

            val clone = provider.cloneRemote("Cloned Worker Fixture", "https://clone-fixture.invalid", "fr")
            assertEquals("Cloned Worker Fixture", clone.name)
            assertEquals("https://clone-fixture.invalid", clone.mainUrl)
            assertEquals("fr", clone.lang)
            val clonedSearch = withTimeout(30_000) { clone.search("normal") }
            assertEquals("https://clone-fixture.invalid/result", clonedSearch?.single()?.url)
            assertEquals("https://worker-fixture.invalid", provider.mainUrl, "clone settings must not mutate the base provider")

            val deliveredLinks = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
            val deliveredSubtitles = mutableListOf<com.lagradost.cloudstream3.SubtitleFile>()
            val bothEventsReceived = CompletableDeferred<Unit>()
            var eventCount = 0
            fun recordEvent() {
                eventCount++
                if (eventCount == 2) bothEventsReceived.complete(Unit)
            }
            val streamingCall = async {
                provider.loadLinks(
                    "stream",
                    isCasting = true,
                    subtitleCallback = { subtitle ->
                        deliveredSubtitles.add(subtitle)
                        recordEvent()
                    },
                    callback = { link ->
                        deliveredLinks.add(link)
                        recordEvent()
                    },
                )
            }
            withTimeout(10_000) { bothEventsReceived.await() }
            val streamingProcessId = provider.workerProcessId ?: error("Streaming provider worker is not running")
            assertTrue(ProcessHandle.of(streamingProcessId).map { it.isAlive }.orElse(false), "child should remain active after streaming callbacks")
            val healthyDuringStream = withTimeout(30_000) { healthyProvider.search("normal") }
            assertEquals("Healthy worker result", healthyDuringStream?.single()?.name)
            val healthyProcessId = healthyProvider.workerProcessId ?: error("Healthy provider worker never started")
            assertNotEquals(streamingProcessId, healthyProcessId, "each plugin must have an independently owned worker process")
            assertTrue(ProcessHandle.of(healthyProcessId).map { it.isAlive }.orElse(false), "healthy worker must remain responsive while another plugin blocks")
            streamingCall.cancelAndJoin()
            assertTrue(ProcessHandle.of(streamingProcessId).map { !it.isAlive }.orElse(true), "cancelled plugin worker must be killed")
            assertEquals("https://worker-fixture.invalid/video.mp4", deliveredLinks.single().url)
            assertEquals("https://worker-fixture.invalid/subtitles.vtt", deliveredSubtitles.single().url)
            assertEquals("subtitle", deliveredSubtitles.single().headers?.get("X-Fixture"))

            val restarted = withTimeout(30_000) { provider.search("normal") }
            assertEquals("Worker process result", restarted?.single()?.name)
            assertEquals(3, provider.workerStartCount)
            assertTrue(testRoot.walkTopDown().any { it.name == "worker-fixture-constructor.txt" && it.readText() == "constructed" })
            ExtensionLoader.unloadPlugin(pluginFile.absolutePath)
            assertTrue(testRoot.walkTopDown().any { it.name == "worker-fixture-before-unload.txt" && it.readText() == "unloaded" })
        } finally {
            ExtensionLoader.unloadPlugin(pluginFile.absolutePath)
            ExtensionLoader.unloadPlugin(crossExtractorPluginFile.absolutePath)
            ExtensionLoader.unloadPlugin(healthyPluginFile.absolutePath)
            ExtensionLoader.unloadPlugin(trustedPluginFile.absolutePath)
            ExtensionLoader.removeTrusted(trustedPluginFile)
            ExtensionLoader.onPluginLoaded = previousLoadedHook
            ExtensionLoader.onPluginUnloaded = previousUnloadedHook
            ExtensionLoader.isolatedPluginFactory = previousIsolatedPluginFactory
            PluginProviderWorkerRegistry.closeForPlugin(pluginFile.absolutePath)
            PluginProviderWorkerRegistry.closeForPlugin(trustedPluginFile.absolutePath)
            PluginSettingsSchemaRegistry.schemas.remove("WorkerFixture_")
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun workerBootstrapFailureReturnsRpcErrorInsteadOfCrashingLauncher() = runBlocking {
        val testRoot = Files.createTempDirectory("auras-provider-worker-bootstrap-").toFile()
        val pluginFile = createBootstrapFailurePlugin(testRoot)
        val dataDirectory = PlatformPaths.appDataDir
        val previousLoadedHook = ExtensionLoader.onPluginLoaded
        val previousUnloadedHook = ExtensionLoader.onPluginUnloaded
        val previousIsolatedPluginFactory = ExtensionLoader.isolatedPluginFactory

        try {
            ExtensionLoader.isolatedPluginFactory = { _, _ -> IsolatedDesktopPluginHandle() }
            ExtensionLoader.onPluginLoaded = { file ->
                PluginProviderWorkerRegistry.movePluginProvidersToWorkers(file, dataDirectory)
            }
            ExtensionLoader.onPluginUnloaded = PluginProviderWorkerRegistry::closeForPlugin

            val failure = assertFailsWith<PluginProviderRpcException> {
                ExtensionLoader.loadAndInit(pluginFile)
            }
            assertTrue(failure.message.orEmpty().contains("worker bootstrap fixture failed"))
        } finally {
            ExtensionLoader.unloadPlugin(pluginFile.absolutePath)
            ExtensionLoader.onPluginLoaded = previousLoadedHook
            ExtensionLoader.onPluginUnloaded = previousUnloadedHook
            ExtensionLoader.isolatedPluginFactory = previousIsolatedPluginFactory
            PluginProviderWorkerRegistry.closeForPlugin(pluginFile.absolutePath)
            testRoot.deleteRecursively()
        }
    }

    private fun createFixturePlugin(directory: File): File {
        val pluginClass = WorkerFixturePlugin::class.java
        val pluginFile = File(directory, "worker-fixture.jar")
        ZipOutputStream(pluginFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"pluginClassName":"${pluginClass.name}","internalName":"WorkerFixture"}""".toByteArray())
            zip.closeEntry()
            addPluginClasses(zip, pluginClass)
        }
        return pluginFile
    }

    private fun createCrossExtractorPlugin(directory: File): File {
        val pluginClass = WorkerCrossExtractorPlugin::class.java
        val pluginFile = File(directory, "cross-extractor.jar")
        ZipOutputStream(pluginFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"pluginClassName":"${pluginClass.name}","internalName":"CrossWorkerExtractor"}""".toByteArray())
            zip.closeEntry()
            addPluginClasses(zip, pluginClass)
        }
        return pluginFile
    }

    private fun createHealthyPlugin(directory: File): File {
        val pluginClass = WorkerHealthyFixturePlugin::class.java
        val pluginFile = File(directory, "healthy-worker-fixture.jar")
        ZipOutputStream(pluginFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"pluginClassName":"${pluginClass.name}","internalName":"HealthyWorkerFixture"}""".toByteArray())
            zip.closeEntry()
            addPluginClasses(zip, pluginClass)
        }
        return pluginFile
    }

    private fun createTrustedPlugin(directory: File): File {
        val pluginClass = WorkerTrustedFixturePlugin::class.java
        val pluginFile = File(directory, "trusted-worker-fixture.jar")
        ZipOutputStream(pluginFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"pluginClassName":"${pluginClass.name}","internalName":"TrustedWorkerFixture"}""".toByteArray())
            zip.closeEntry()
            addPluginClasses(zip, pluginClass)
        }
        return pluginFile
    }

    private fun createBootstrapFailurePlugin(directory: File): File {
        val pluginClass = WorkerBootstrapFailureFixturePlugin::class.java
        val pluginFile = File(directory, "bootstrap-failure-fixture.jar")
        ZipOutputStream(pluginFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"pluginClassName":"${pluginClass.name}","internalName":"BootstrapFailureFixture"}""".toByteArray())
            zip.closeEntry()
            addPluginClasses(zip, pluginClass)
        }
        return pluginFile
    }

    private fun addPluginClasses(zip: ZipOutputStream, pluginClass: Class<*>) {
        val packagePath = pluginClass.name.substringBeforeLast('.').replace('.', '/')
        val packageDirectory = File(pluginClass.classLoader.getResource(packagePath)!!.toURI())
        val classPrefix = pluginClass.simpleName
        packageDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "class" && (it.name == "$classPrefix.class" || it.name.startsWith("$classPrefix\$")) }
            .sortedBy(File::getName)
            .forEach { classFile ->
                val resource = "$packagePath/${classFile.name}"
                zip.putNextEntry(ZipEntry(resource))
                classFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
    }

    private class FixtureInterceptorChain(
        private val initialRequest: Request,
    ) : Interceptor.Chain {
        var proceededRequest: Request? = null
            private set

        override fun request(): Request = initialRequest

        override fun proceed(request: Request): Response {
            proceededRequest = request
            val isLargeMedia = request.url.encodedPath.contains("large-response-interceptor")
            val body = if (isLargeMedia) {
                ByteArray(1024 * 1024 + 1) { 'L'.code.toByte() }.toResponseBody("video/mp2t".toMediaType())
            } else {
                "host response body".toResponseBody("text/plain".toMediaType())
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("X-Host-Response", "preserved")
                .header("X-Remove-Me", "remove")
                .header("Content-Type", if (isLargeMedia) "video/mp2t" else "text/plain")
                .body(body)
                .build()
        }

        override fun connection(): okhttp3.Connection? = null
        override fun call(): okhttp3.Call = OkHttpClient().newCall(initialRequest)
        override fun connectTimeoutMillis(): Int = 10_000
        override fun readTimeoutMillis(): Int = 10_000
        override fun writeTimeoutMillis(): Int = 10_000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
