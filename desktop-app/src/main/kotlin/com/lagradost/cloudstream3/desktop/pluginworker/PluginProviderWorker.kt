package com.lagradost.cloudstream3.desktop.pluginworker

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TorrentSearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.PluginSettingSchema
import com.lagradost.common.storage.PluginSettingsSchemaRegistry
import com.lagradost.runtime.executor.RestartablePluginWorker
import com.lagradost.runtime.loader.ExtensionLoader
import com.lagradost.runtime.loader.IsolatedPluginHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.buffer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintStream
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private const val MAX_PROVIDER_RPC_FRAME_BYTES = 4 * 1024 * 1024
private const val MAX_PLUGIN_SETTING_SCHEMA_FRAME_BYTES = 64 * 1024
private const val MAX_VIDEO_INTERCEPTOR_BODY_BYTES = 1024 * 1024

// First load may translate several MiB of DEX before the worker can answer its ready request.
private const val PROVIDER_WORKER_STARTUP_TIMEOUT_MS = 180_000L
private const val MAX_CROSS_PLUGIN_EXTRACTOR_DEPTH = 8
private const val HOST_EXTERNAL_EXTRACTOR_SOURCE = "__auras_host_external_extractor__"

/** Inert host-side plugin identity; all real plugin construction and lifecycle runs in a worker. */
internal class IsolatedDesktopPluginHandle : com.lagradost.cloudstream3.plugins.Plugin(), IsolatedPluginHandle

/**
 * Owns the provider workers and replaces installed MainAPI instances with RPC facades. Provider
 * methods execute in a restartable child process; the facade carries only the stable MainAPI
 * metadata that the host UI needs.
 */
object PluginProviderWorkerRegistry {
    private data class WorkerKey(val pluginPath: String)
    private data class ExternalRouteState(val processId: Long, val generation: Long)

    private val workers = ConcurrentHashMap<WorkerKey, RestartablePluginWorker>()
    private val externalExtractorGeneration = AtomicLong()
    private val externalRouteStates = ConcurrentHashMap<String, ExternalRouteState>()
    private val externalRouteLocks = ConcurrentHashMap<String, Mutex>()
    private val workerClassPathLock = Any()

    fun movePluginProvidersToWorkers(
        pluginFile: File,
        appDataDirectory: File = PlatformPaths.appDataDir,
    ) {
        val canonicalPath = pluginFile.canonicalPath
        val hostProviders = APIHolder.allProviders.filter { provider ->
            provider.sourcePlugin?.let { runCatching { File(it).canonicalPath == canonicalPath }.getOrDefault(false) } == true
        }
        val worker = workerFor(pluginFile, appDataDirectory)
        val pluginDescriptors = requestPluginDescriptors(worker, canonicalPath)
        val providerDescriptors = pluginDescriptors.path("providers").map(ProviderRpcJson::decodeProviderDescriptor)
        val extractorDescriptors = pluginDescriptors.path("extractors").map(ProviderRpcJson::decodeExtractorDescriptor)
        val hostPlugin = (
            ExtensionLoader.getPlugin(pluginFile.absolutePath)
                ?: ExtensionLoader.getPlugin(canonicalPath)
            ) as? Plugin
        hostPlugin?.openSettings = if (pluginDescriptors.path("plugin").path("hasOpenSettings").asBoolean(false)) {
            { _ -> invokePluginCallback(worker, canonicalPath, "openPluginSettings") }
        } else {
            null
        }

        synchronized(VideoClickActionHolder.allVideoClickActions) {
            VideoClickActionHolder.allVideoClickActions.removeIf { action ->
                action.sourcePlugin?.let { path -> runCatching { File(path).canonicalPath == canonicalPath }.getOrDefault(false) } == true
            }
            pluginDescriptors.path("plugin").path("videoClickActions").forEach { descriptor ->
                val actionId = descriptor.path("id").asInt()
                VideoClickAction(
                    name = descriptor.path("name").asText(),
                    iconId = descriptor.path("iconId").asInt(),
                    requiresAuthentication = descriptor.path("requiresAuthentication").asBoolean(false),
                ) {
                    invokePluginCallback(worker, canonicalPath, "invokeVideoClickAction", ProviderRpcJson.mapper.createObjectNode().put("actionId", actionId))
                }.also { action ->
                    action.sourcePlugin = pluginFile.absolutePath
                    VideoClickActionHolder.allVideoClickActions.add(action)
                }
            }
        }

        val insertionIndex = synchronized(APIHolder.allProviders) {
            val firstIndex = hostProviders.mapNotNull(APIHolder.allProviders::indexOf).filter { it >= 0 }.minOrNull()
            hostProviders.forEach(APIHolder::removePluginMapping)
            APIHolder.allProviders.removeAll(hostProviders.toSet())
            (firstIndex ?: APIHolder.allProviders.size).coerceAtMost(APIHolder.allProviders.size)
        }

        synchronized(APIHolder.allProviders) {
            providerDescriptors.forEachIndexed { index, descriptor ->
                val remote = WorkerBackedMainAPI(descriptor, pluginFile, index, worker)
                remote.sourcePlugin = pluginFile.absolutePath
                APIHolder.allProviders.add((insertionIndex + index).coerceAtMost(APIHolder.allProviders.size), remote)
                APIHolder.addPluginMapping(remote)
            }
        }
        synchronized(com.lagradost.cloudstream3.utils.extractorApis) {
            val hostExtractors = com.lagradost.cloudstream3.utils.extractorApis.filter { extractor ->
                extractor.sourcePlugin?.let { runCatching { File(it).canonicalPath == canonicalPath }.getOrDefault(false) } == true
            }
            hostExtractors.forEach { com.lagradost.cloudstream3.utils.extractorApis.remove(it) }
            extractorDescriptors.forEachIndexed { index, descriptor ->
                val remote = WorkerBackedExtractorApi(descriptor, pluginFile, index, worker).also {
                    it.sourcePlugin = pluginFile.absolutePath
                }
                com.lagradost.cloudstream3.utils.extractorApis.add(remote)
            }
        }
        externalExtractorGeneration.incrementAndGet()
        AppLogger.i(
            "PluginProviderWorker",
            "Installed ${providerDescriptors.size} process-backed provider facade(s) and ${extractorDescriptors.size} extractor facade(s) for ${pluginFile.name}; host registrations replaced: ${hostProviders.size}",
        )
    }

    fun closeForPlugin(pluginPath: String) {
        val canonicalPath = runCatching { File(pluginPath).canonicalPath }.getOrDefault(File(pluginPath).absolutePath)
        workers.entries.removeIf { (key, worker) ->
            if (key.pluginPath == canonicalPath || key.pluginPath == File(pluginPath).absolutePath) {
                if (worker.isAlive) {
                    runCatching {
                        runBlocking {
                            worker.request(controlRequest("shutdown"), 5_000L)
                        }
                    }
                }
                worker.close()
                true
            } else {
                false
            }
        }
        synchronized(com.lagradost.cloudstream3.utils.extractorApis) {
            com.lagradost.cloudstream3.utils.extractorApis.removeIf { extractor ->
                extractor.sourcePlugin?.let { samePluginPath(it, canonicalPath) } == true
            }
        }
        externalRouteStates.remove(canonicalPath)
        externalExtractorGeneration.incrementAndGet()
    }

    fun closeAll() {
        workers.keys.map(WorkerKey::pluginPath).distinct().forEach(::closeForPlugin)
    }

    /** Reapply host override data after repository metadata has finished loading. */
    fun applyProviderOverrides() {
        APIHolder.allProviders.filterIsInstance<WorkerBackedMainAPI>().forEach { it.applyDelegateOverrides() }
    }

    internal suspend fun ensureExternalExtractors(pluginPath: String, worker: RestartablePluginWorker) = worker.withLease {
        val canonicalPath = File(pluginPath).canonicalPath
        val lock = externalRouteLocks.computeIfAbsent(canonicalPath) { Mutex() }
        lock.withLock {
            if (!worker.isAlive || worker.processId == null) {
                readSuccess(
                    worker.request(
                        controlRequest("describeProviders"),
                        PROVIDER_WORKER_STARTUP_TIMEOUT_MS,
                        onIntermediateFrame = { frame -> handlePluginWorkerEvent(canonicalPath, frame) },
                    ),
                )
            }
            val processId = worker.processId ?: throw PluginProviderRpcException("Plugin worker exited during extractor setup")
            val generation = externalExtractorGeneration.get()
            if (externalRouteStates[canonicalPath] == ExternalRouteState(processId, generation)) return@withLock
            val descriptors = synchronized(com.lagradost.cloudstream3.utils.extractorApis) {
                com.lagradost.cloudstream3.utils.extractorApis.filterIsInstance<WorkerBackedExtractorApi>()
                    .filterNot { samePluginPath(it.pluginPath, canonicalPath) }
                    .toList()
            }
            val arguments = ProviderRpcJson.mapper.createObjectNode().apply {
                set<ArrayNode>(
                    "extractors",
                    ProviderRpcJson.mapper.createArrayNode().also { array ->
                        descriptors.forEach { extractor ->
                            array.add(
                                ProviderRpcJson.mapper.createObjectNode()
                                    .put("routeId", extractor.routeId)
                                    .put("name", extractor.name)
                                    .put("mainUrl", extractor.mainUrl)
                                    .put("requiresReferer", extractor.requiresReferer),
                            )
                        }
                    },
                )
            }
            readSuccess(
                worker.request(
                    controlRequest("setExternalExtractors", arguments),
                    30_000L,
                    onIntermediateFrame = { frame -> handlePluginWorkerEvent(canonicalPath, frame) },
                ),
            )
            val currentProcess = worker.processId ?: throw PluginProviderRpcException("Plugin worker exited during extractor setup")
            externalRouteStates[canonicalPath] = ExternalRouteState(currentProcess, generation)
        }
    }

    internal fun handleHostRequest(
        sourcePluginPath: String,
        workerPaths: List<String>,
        frame: ByteArray,
    ): ByteArray? {
        val request = runCatching { ProviderRpcJson.mapper.readTree(frame) }.getOrNull() ?: return null
        if (request.path("event").asText() != "hostRequest") return null
        val requestId = request.path("requestId").asText().take(80)
        val response = ProviderRpcJson.mapper.createObjectNode().put("hostResponseId", requestId)
        return try {
            require(workerPaths.isNotEmpty() && samePluginPath(workerPaths.last(), sourcePluginPath)) {
                "Invalid plugin worker call chain"
            }
            require(workerPaths.size <= MAX_CROSS_PLUGIN_EXTRACTOR_DEPTH) { "Cross-plugin extractor call depth exceeded" }
            val routeId = request.path("routeId").asText().take(80)
            val extractor = synchronized(com.lagradost.cloudstream3.utils.extractorApis) {
                com.lagradost.cloudstream3.utils.extractorApis
                    .filterIsInstance<WorkerBackedExtractorApi>()
                    .firstOrNull { it.routeId == routeId }
            } ?: throw PluginProviderRpcException("Requested plugin extractor is no longer available")
            val targetPath = extractor.pluginPath
            require(workerPaths.none { samePluginPath(it, targetPath) }) {
                "Cross-plugin extractor cycle rejected"
            }
            val nextWorkerPaths = workerPaths + targetPath
            when (request.path("operation").asText()) {
                "getUrl" -> {
                    val links = mutableListOf<ExtractorLink>()
                    val subtitles = mutableListOf<SubtitleFile>()
                    runBlocking(Dispatchers.IO) {
                        extractor.getUrlFromHost(
                            url = request.path("url").asText(),
                            referer = request.get("referer")?.takeUnless(JsonNode::isNull)?.asText(),
                            workerPaths = nextWorkerPaths,
                            subtitleCallback = subtitles::add,
                            callback = links::add,
                        )
                    }
                    response.set<JsonNode>("links", ProviderRpcJson.encodeExtractorLinks(links))
                    response.set<JsonNode>("subtitles", ProviderRpcJson.mapper.valueToTree(subtitles.map(ProviderRpcJson::encodeSubtitle)))
                }
                "getExtractorUrl" -> {
                    val value = runBlocking(Dispatchers.IO) {
                        extractor.getExtractorUrlFromHost(request.path("id").asText(), nextWorkerPaths)
                    }
                    response.put("value", value)
                }
                else -> throw PluginProviderRpcException("Unsupported plugin worker host request")
            }
            response.put("ok", true)
        } catch (failure: Throwable) {
            response.put("ok", false)
                .put("error", (failure.message ?: failure.javaClass.simpleName).take(1200))
        }.toString().toByteArray(StandardCharsets.UTF_8)
    }

    internal fun handlePluginWorkerEvent(pluginPath: String, frame: ByteArray): Boolean {
        if (frame.size > MAX_PROVIDER_RPC_FRAME_BYTES) {
            throw PluginProviderRpcException("Plugin worker event exceeds its size limit")
        }
        val event = ProviderRpcJson.mapper.readTree(frame)
        if (event.path("event").asText() != "pluginSettingSchema") return false
        if (frame.size > MAX_PLUGIN_SETTING_SCHEMA_FRAME_BYTES) {
            throw PluginProviderRpcException("Plugin settings schema event exceeds its size limit")
        }

        val internalName = ExtensionLoader.getPluginInternalName(pluginPath)
            ?: throw PluginProviderRpcException("Plugin settings schema arrived from an unknown plugin worker")
        val schema = ProviderRpcJson.decodePluginSettingSchema(event)
        require(schema.pluginPrefName == "${internalName}_") { "Plugin worker attempted to register settings outside its namespace" }
        require(schema.key.length in 1..256) { "Plugin setting key is outside its size limit" }
        require(schema.type in setOf("Boolean", "String", "Int", "Long", "Float", "StringSet")) {
            "Plugin worker requested an unsupported setting type"
        }
        val options = schema.options
        require(options == null || (options.size <= 256 && options.all { (key, value) -> key.length <= 1024 && value.length <= 1024 })) {
            "Plugin setting options exceed their size limit"
        }
        PluginSettingsSchemaRegistry.register(
            pluginPrefName = schema.pluginPrefName,
            key = schema.key,
            type = schema.type,
            defaultValue = schema.defaultValue,
            isGlobal = false,
            options = schema.options,
        )
        return true
    }

    private fun samePluginPath(first: String, second: String): Boolean =
        runCatching { File(first).canonicalPath == File(second).canonicalPath }.getOrDefault(first == second)

    private fun workerFor(pluginFile: File, appDataDirectory: File): RestartablePluginWorker {
        val canonicalPath = pluginFile.canonicalPath
        val key = WorkerKey(canonicalPath)
        return workers.computeIfAbsent(key) {
            RestartablePluginWorker(
                command = { workerCommand(pluginFile, -1, appDataDirectory) },
                workingDirectory = pluginFile.parentFile,
                maxResponseBytes = MAX_PROVIDER_RPC_FRAME_BYTES,
                workerKey = canonicalPath,
            )
        }
    }

    private fun requestPluginDescriptors(worker: RestartablePluginWorker, pluginPath: String): JsonNode = runBlocking {
        readSuccess(
            worker.request(
                controlRequest("describeProviders"),
                PROVIDER_WORKER_STARTUP_TIMEOUT_MS,
                onIntermediateFrame = { frame -> handlePluginWorkerEvent(pluginPath, frame) },
            ),
        )
    }

    private fun invokePluginCallback(
        worker: RestartablePluginWorker,
        pluginPath: String,
        operation: String,
        arguments: ObjectNode = ProviderRpcJson.mapper.createObjectNode(),
    ) {
        runBlocking {
            readSuccess(
                worker.request(
                    controlRequest(operation, arguments),
                    30_000L,
                    onIntermediateFrame = { frame -> handlePluginWorkerEvent(pluginPath, frame) },
                    onWorkerRequest = { frame -> handleHostRequest(pluginPath, listOf(File(pluginPath).canonicalPath), frame) },
                ),
            )
        }
    }

    private fun controlRequest(operation: String, arguments: ObjectNode = ProviderRpcJson.mapper.createObjectNode()): ByteArray = ProviderRpcJson.mapper.createObjectNode().apply {
        put("providerIndex", -1)
        put("providerClass", "")
        put("operation", operation)
        set<JsonNode>("arguments", arguments)
    }.toString().toByteArray(StandardCharsets.UTF_8)

    private fun readSuccess(bytes: ByteArray): JsonNode {
        val response = ProviderRpcJson.mapper.readTree(bytes)
        if (!response.path("ok").asBoolean(false)) {
            throw PluginProviderRpcException(response.path("errorMessage").asText("Plugin worker bootstrap failed").take(1200))
        }
        return response.get("value") ?: ProviderRpcJson.mapper.nullNode()
    }

    private fun windowsWorkerClassPath(classPath: String, appDataDirectory: File): String = synchronized(workerClassPathLock) {
        val cacheDirectory = File(appDataDirectory, "plugin-worker")
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
            throw IllegalStateException("Could not create plugin worker cache directory")
        }

        val cacheId = UUID.nameUUIDFromBytes(
            (cacheDirectory.canonicalPath + "\u0000" + classPath).toByteArray(StandardCharsets.UTF_8),
        )
        val classPathJar = File(cacheDirectory, "classpath-$cacheId.jar")
        if (classPathJar.isFile) return@synchronized classPathJar.absolutePath

        val classPathUrls = classPath.split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .joinToString(" ") { entry -> File(entry).absoluteFile.toURI().toASCIIString() }
        check(classPathUrls.isNotBlank()) { "Cannot create a plugin worker classpath from an empty classpath" }

        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Class-Path", classPathUrls)
        }
        val temporaryJar = File.createTempFile("classpath-$cacheId-", ".jar.tmp", cacheDirectory)
        try {
            JarOutputStream(FileOutputStream(temporaryJar), manifest).use { }
            try {
                Files.move(temporaryJar.toPath(), classPathJar.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: FileAlreadyExistsException) {
                // Another application process may have cached this same classpath first.
            } catch (_: AtomicMoveNotSupportedException) {
                try {
                    Files.move(temporaryJar.toPath(), classPathJar.toPath())
                } catch (_: FileAlreadyExistsException) {
                    // Another application process may have cached this same classpath first.
                }
            }
        } finally {
            Files.deleteIfExists(temporaryJar.toPath())
        }
        check(classPathJar.isFile) { "Could not create the plugin worker classpath archive" }
        classPathJar.absolutePath
    }

    private fun workerCommand(pluginFile: File, providerIndex: Int, appDataDirectory: File): List<String> {
        val currentCommand = ProcessHandle.current().info().command().orElse(null)
        val packagedExecutable = currentCommand?.let(::File)?.takeIf { file ->
            file.isFile && file.name.equals("Auras-Orbit.exe", ignoreCase = true)
        }
        val appDataPath = appDataDirectory.canonicalPath
        if (packagedExecutable != null) {
            return listOf(packagedExecutable.absolutePath, "--auras-plugin-worker", pluginFile.canonicalPath, providerIndex.toString(), appDataPath)
        }

        val javaExecutable = File(System.getProperty("java.home"), "bin/java${if (PlatformPaths.currentOS == PlatformPaths.OS.WINDOWS) ".exe" else ""}")
        val classPath = System.getProperty("java.class.path")
        check(javaExecutable.isFile && classPath.isNotBlank()) { "Cannot locate the Java runtime for a plugin worker" }
        val workerClassPath = if (PlatformPaths.currentOS == PlatformPaths.OS.WINDOWS) {
            windowsWorkerClassPath(classPath, appDataDirectory)
        } else {
            classPath
        }
        return listOf(
            javaExecutable.absolutePath,
            "-cp",
            workerClassPath,
            "com.lagradost.cloudstream3.desktop.MainKt",
            "--auras-plugin-worker",
            pluginFile.canonicalPath,
            providerIndex.toString(),
            appDataPath,
        )
    }
}

internal class WorkerBackedMainAPI(
    descriptor: WorkerProviderDescriptor,
    private val pluginFile: File,
    private val providerIndex: Int,
    private val worker: RestartablePluginWorker,
    private val clonedSite: Boolean = false,
) : MainAPI() {
    private var descriptor = descriptor
    val originalClassSimpleName: String get() = descriptor.className.substringAfterLast('.')
    internal val workerProcessId: Long? get() = worker.processId
    internal val lastWorkerProcessId: Long? get() = worker.lastStartedProcessId
    internal val workerStartCount: Long get() = worker.workerStarts

    private val readinessLock = Mutex()
    private var readyProcessId: Long? = null

    fun applyDelegateOverrides() {
        if (clonedSite) return
        runBlocking {
            val updated = invoke("initializeProvider", ProviderRpcJson.mapper.createObjectNode())
            if (updated != null) descriptor = ProviderRpcJson.decodeProviderDescriptor(updated)
        }
    }

    override var name: String
        get() = descriptor.name
        set(value) {
            descriptor = descriptor.copy(name = value)
        }
    override var mainUrl: String
        get() = descriptor.mainUrl
        set(value) {
            descriptor = descriptor.copy(mainUrl = value)
        }
    override var storedCredentials: String?
        get() = descriptor.storedCredentials
        set(value) {
            descriptor = descriptor.copy(storedCredentials = value)
        }
    override var canBeOverridden: Boolean
        get() = descriptor.canBeOverridden
        set(value) {
            descriptor = descriptor.copy(canBeOverridden = value)
        }
    override var sequentialMainPage: Boolean
        get() = descriptor.sequentialMainPage
        set(value) {
            descriptor = descriptor.copy(sequentialMainPage = value)
        }
    override var sequentialMainPageDelay: Long
        get() = descriptor.sequentialMainPageDelay
        set(value) {
            descriptor = descriptor.copy(sequentialMainPageDelay = value)
        }
    override var sequentialMainPageScrollDelay: Long
        get() = descriptor.sequentialMainPageScrollDelay
        set(value) {
            descriptor = descriptor.copy(sequentialMainPageScrollDelay = value)
        }
    override var lang: String
        get() = descriptor.lang
        set(value) {
            descriptor = descriptor.copy(lang = value)
        }
    override val instantLinkLoading: Boolean get() = descriptor.instantLinkLoading
    override val hasChromecastSupport: Boolean get() = descriptor.hasChromecastSupport
    override val hasDownloadSupport: Boolean get() = descriptor.hasDownloadSupport
    override val usesWebView: Boolean get() = descriptor.usesWebView
    override val hasMainPage: Boolean get() = descriptor.hasMainPage
    override val hasQuickSearch: Boolean get() = descriptor.hasQuickSearch
    override val loadLinksTimeoutMs: Long? get() = descriptor.loadLinksTimeoutMs
    override val getMainPageTimeoutMs: Long? get() = descriptor.getMainPageTimeoutMs
    override val searchTimeoutMs: Long? get() = descriptor.searchTimeoutMs
    override val quickSearchTimeoutMs: Long? get() = descriptor.quickSearchTimeoutMs
    override val loadTimeoutMs: Long? get() = descriptor.loadTimeoutMs
    override val supportedSyncNames get() = descriptor.supportedSyncNames
    override val supportedTypes get() = descriptor.supportedTypes
    override val vpnStatus get() = descriptor.vpnStatus
    override val providerType get() = descriptor.providerType
    override val mainPage get() = descriptor.mainPage

    private suspend fun invoke(
        operation: String,
        arguments: JsonNode,
        timeoutMs: Long = 120_000L,
        onEvent: ((JsonNode) -> Unit)? = null,
        workerPaths: List<String> = listOf(pluginFile.canonicalPath),
        onHostRequest: ((ByteArray) -> ByteArray?)? = null,
    ): JsonNode? = worker.withLease {
        ensureWorkerReady()
        val request = makeRequest(operation, arguments).also { node ->
            node.set<JsonNode>("workerPaths", ProviderRpcJson.mapper.valueToTree(workerPaths))
        }
        val bytes = worker.request(
            request.toString().toByteArray(StandardCharsets.UTF_8),
            timeoutMs,
            onIntermediateFrame = { frame ->
                if (PluginProviderWorkerRegistry.handlePluginWorkerEvent(pluginFile.canonicalPath, frame)) {
                    true
                } else {
                    val event = ProviderRpcJson.mapper.readTree(frame)
                    if (event.path("event").asText() == "hostRequest" || !event.hasNonNull("event")) {
                        false
                    } else {
                        val handler = onEvent ?: throw PluginProviderRpcException("Unexpected plugin worker event")
                        handler(event)
                        true
                    }
                }
            },
            onWorkerRequest = { frame ->
                onHostRequest?.invoke(frame)
                    ?: PluginProviderWorkerRegistry.handleHostRequest(pluginFile.canonicalPath, workerPaths, frame)
            },
        )
        val response = ProviderRpcJson.mapper.readTree(bytes)
        if (!response.path("ok").asBoolean(false)) {
            val type = response.path("errorType").asText("PluginError").take(160)
            val message = response.path("errorMessage").asText("Plugin provider request failed").take(1200)
            throw PluginProviderRpcException("$type: $message")
        }
        response.get("value")?.takeUnless(JsonNode::isNull)
    }

    private suspend fun ensureWorkerReady() = readinessLock.withLock {
        val processId = worker.processId
        if (worker.isAlive && processId != null && processId == readyProcessId) {
            PluginProviderWorkerRegistry.ensureExternalExtractors(pluginFile.canonicalPath, worker)
            return@withLock
        }

        val request = makeRequest("ready", ProviderRpcJson.mapper.createObjectNode())
        val bytes = worker.request(
            request.toString().toByteArray(StandardCharsets.UTF_8),
            PROVIDER_WORKER_STARTUP_TIMEOUT_MS,
            onIntermediateFrame = { frame ->
                PluginProviderWorkerRegistry.handlePluginWorkerEvent(pluginFile.canonicalPath, frame)
            },
        )
        val response = ProviderRpcJson.mapper.readTree(bytes)
        if (!response.path("ok").asBoolean(false)) {
            throw PluginProviderRpcException(response.path("errorMessage").asText("Plugin worker initialization failed").take(1200))
        }
        readyProcessId = worker.processId ?: throw PluginProviderRpcException("Plugin worker exited during initialization")
        PluginProviderWorkerRegistry.ensureExternalExtractors(pluginFile.canonicalPath, worker)
    }

    private fun makeRequest(operation: String, arguments: JsonNode) = ProviderRpcJson.mapper.createObjectNode().apply {
        put("providerIndex", providerIndex)
        put("providerClass", descriptor.className)
        put("providerName", name)
        put("mainUrl", mainUrl)
        put("lang", lang)
        putNullableText("storedCredentials", storedCredentials)
        set<JsonNode>("workerPaths", ProviderRpcJson.mapper.valueToTree(listOf(pluginFile.canonicalPath)))
        put("operation", operation)
        set<JsonNode>("arguments", arguments)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val args = ProviderRpcJson.mapper.createObjectNode().put("page", page).set<JsonNode>("request", ProviderRpcJson.mapper.valueToTree(request))
        return invoke("getMainPage", args, getMainPageTimeoutMs ?: 120_000L)?.let(ProviderRpcJson::decodeHomePage)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val args = ProviderRpcJson.mapper.createObjectNode().put("query", query).put("page", page)
        return invoke("searchPage", args, searchTimeoutMs ?: 90_000L)?.let(ProviderRpcJson::decodeSearchResponseList)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val args = ProviderRpcJson.mapper.createObjectNode().put("query", query)
        val result = invoke("search", args, searchTimeoutMs ?: 90_000L) ?: return null
        return result.map(ProviderRpcJson::decodeSearchResponse)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val args = ProviderRpcJson.mapper.createObjectNode().put("query", query)
        val result = invoke("quickSearch", args, quickSearchTimeoutMs ?: 45_000L) ?: return null
        return result.map(ProviderRpcJson::decodeSearchResponse)
    }

    override suspend fun load(url: String): LoadResponse? {
        val args = ProviderRpcJson.mapper.createObjectNode().put("url", url)
        return invoke("load", args, loadTimeoutMs ?: 120_000L)?.let(ProviderRpcJson::decodeLoadResponse)
    }

    override suspend fun extractorVerifierJob(extractorData: String?) {
        val args = ProviderRpcJson.mapper.createObjectNode().putNullableText("extractorData", extractorData)
        invoke("extractorVerifierJob", args, 120_000L)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val args = ProviderRpcJson.mapper.createObjectNode().put("data", data).put("isCasting", isCasting)
        val response = invoke(
            "loadLinks",
            args,
            loadLinksTimeoutMs ?: 180_000L,
            onEvent = { event ->
                when (event.path("event").asText()) {
                    "link" -> callback(ProviderRpcJson.decodeExtractorLink(event.path("value")))
                    "subtitle" -> subtitleCallback(ProviderRpcJson.decodeSubtitle(event.path("value")))
                    else -> throw PluginProviderRpcException("Unknown plugin worker event")
                }
            },
        )
            ?: throw PluginProviderRpcException("Plugin worker returned no loadLinks result")
        return response.path("success").asBoolean(false)
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): okhttp3.Interceptor? {
        val interceptorId = UUID.randomUUID().toString()
        val args = ProviderRpcJson.mapper.createObjectNode()
            .put("interceptorId", interceptorId)
            .set<JsonNode>("link", ProviderRpcJson.mapper.valueToTree(extractorLink))
        val prepared = runBlocking { invoke("prepareVideoInterceptor", args, timeoutMs = 30_000L) }
        if (prepared?.path("registered")?.asBoolean(false) != true) return null

        return okhttp3.Interceptor { chain ->
            val hostResponse = java.util.concurrent.atomic.AtomicReference<Response?>(null)
            val chainProceeded = java.util.concurrent.atomic.AtomicBoolean(false)
            var responseOwnershipTransferred = false
            val requestArguments = ProviderRpcJson.mapper.createObjectNode().apply {
                put("interceptorId", interceptorId)
                set<JsonNode>("link", ProviderRpcJson.mapper.valueToTree(extractorLink))
                set<JsonNode>("request", ProviderRpcJson.encodeRequest(chain.request()))
            }
            try {
                val intercepted = runBlocking {
                    invoke(
                        "interceptVideoRequest",
                        requestArguments,
                        timeoutMs = 30_000L,
                        onHostRequest = hostHandler@{ frame ->
                            val request = runCatching { ProviderRpcJson.mapper.readTree(frame) }.getOrNull() ?: return@hostHandler null
                            if (request.path("event").asText() != "hostRequest" || request.path("operation").asText() != "videoInterceptorProceed") {
                                return@hostHandler null
                            }
                            val requestId = request.path("requestId").asText().take(80)
                            val reply = ProviderRpcJson.mapper.createObjectNode().put("hostResponseId", requestId)
                            try {
                                require(request.path("interceptorId").asText() == interceptorId) { "Video interceptor request identity mismatch" }
                                check(chainProceeded.compareAndSet(false, true)) { "Video interceptor attempted to proceed more than once" }
                                val proceedRequest = ProviderRpcJson.decodeRequest(request.path("request"), chain.request().body)
                                val actual = chain.proceed(proceedRequest)
                                hostResponse.set(actual)
                                val body = actual.body
                                var bodyAvailable = false
                                var bodyBytes = ByteArray(0)
                                if (canTransferVideoInterceptorBody(body)) {
                                    val source = body.source()
                                    source.request(MAX_VIDEO_INTERCEPTOR_BODY_BYTES + 1L)
                                    if (source.buffer.size <= MAX_VIDEO_INTERCEPTOR_BODY_BYTES) {
                                        bodyAvailable = true
                                        bodyBytes = source.buffer.clone().readByteArray()
                                    }
                                }
                                reply.put("ok", true)
                                reply.put("protocol", actual.protocol.name)
                                reply.put("code", actual.code)
                                reply.put("message", actual.message)
                                reply.put("bodyAvailable", bodyAvailable)
                                reply.put("bodyLength", body.contentLength())
                                reply.set<JsonNode>("request", ProviderRpcJson.encodeRequest(actual.request))
                                reply.set<JsonNode>("headers", ProviderRpcJson.mapper.valueToTree(actual.headers.toMultimap()))
                                if (bodyAvailable) reply.put("body", java.util.Base64.getEncoder().encodeToString(bodyBytes))
                            } catch (failure: Throwable) {
                                reply.put("ok", false).put("error", (failure.message ?: failure.javaClass.simpleName).take(1200))
                            }
                            reply.toString().toByteArray(StandardCharsets.UTF_8)
                        },
                    )
                } ?: throw PluginProviderRpcException("Plugin worker returned no video interceptor result")
                if (!intercepted.path("registered").asBoolean(false)) return@Interceptor chain.proceed(chain.request())

                val headers = ProviderRpcJson.decodeHeaders(intercepted.path("headers"))
                if (intercepted.path("proceeded").asBoolean(false)) {
                    val actual = hostResponse.get() ?: throw PluginProviderRpcException("Plugin interceptor proceeded without a host response")
                    val responseRequest = ProviderRpcJson.decodeRequest(intercepted.path("request"), actual.request.body)
                    val response = actual.newBuilder()
                        .request(responseRequest)
                        .protocol(Protocol.valueOf(intercepted.path("protocol").asText(actual.protocol.name)))
                        .code(intercepted.path("code").asInt(actual.code))
                        .message(intercepted.path("message").asText(actual.message))
                        .headers(headers)

                    if (intercepted.path("bodyAvailable").asBoolean(false) || intercepted.path("bodyChanged").asBoolean(false)) {
                        val bodyBytes = decodeInterceptorBody(intercepted)
                        response.removeHeader("Content-Length")
                        response.removeHeader("Transfer-Encoding")
                        response.header("Content-Length", bodyBytes.size.toString())
                        response.body(bodyBytes.toResponseBody(headers["Content-Type"]?.toMediaTypeOrNull() ?: actual.body.contentType()))
                        actual.close()
                    }
                    responseOwnershipTransferred = true
                    hostResponse.set(null)
                    return@Interceptor response.build()
                }

                val bodyBytes = decodeInterceptorBody(intercepted)
                responseOwnershipTransferred = true
                okhttp3.Response.Builder()
                    .request(ProviderRpcJson.decodeRequest(intercepted.path("request"), chain.request().body))
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(intercepted.path("code").asInt(200))
                    .message(intercepted.path("message").asText("OK"))
                    .headers(headers)
                    .body(bodyBytes.toResponseBody(headers["Content-Type"]?.toMediaTypeOrNull()))
                    .build()
            } finally {
                if (!responseOwnershipTransferred) hostResponse.getAndSet(null)?.close()
            }
        }
    }

    private fun canTransferVideoInterceptorBody(body: okhttp3.ResponseBody): Boolean {
        val length = body.contentLength()
        if (length > MAX_VIDEO_INTERCEPTOR_BODY_BYTES) return false
        if (length >= 0L) return true
        val type = body.contentType() ?: return false
        return type.type.equals("text", ignoreCase = true) ||
            type.subtype.lowercase() in setOf("json", "xml", "javascript", "x-mpegurl", "vnd.apple.mpegurl")
    }

    private fun decodeInterceptorBody(intercepted: JsonNode): ByteArray {
        val bodyBytes = runCatching {
            java.util.Base64.getDecoder().decode(intercepted.path("body").asText(""))
        }.getOrElse { throw PluginProviderRpcException("Plugin worker returned malformed video interceptor body") }
        if (bodyBytes.size > MAX_VIDEO_INTERCEPTOR_BODY_BYTES) {
            throw PluginProviderRpcException("Plugin worker video interceptor body exceeded its transfer limit")
        }
        return bodyBytes
    }

    override suspend fun getLoadUrl(name: com.lagradost.cloudstream3.syncproviders.SyncIdName, id: String): String? {
        val args = ProviderRpcJson.mapper.createObjectNode().put("name", name.name).put("id", id)
        return invoke("getLoadUrl", args, 60_000L)?.asText()
    }

    fun cloneRemote(
        name: String,
        mainUrl: String,
        lang: String,
    ): MainAPI {
        val copy = WorkerBackedMainAPI(descriptor, pluginFile, providerIndex, worker, clonedSite = true)
        copy.name = name
        copy.lang = lang
        copy.mainUrl = mainUrl
        copy.canBeOverridden = false
        return copy.also { it.sourcePlugin = sourcePlugin }
    }
}

/** Host catalog entry for an extractor whose executable object remains inside its owning worker. */
internal class WorkerBackedExtractorApi(
    private val descriptor: WorkerExtractorDescriptor,
    private val pluginFile: File,
    private val extractorIndex: Int,
    private val worker: RestartablePluginWorker,
) : ExtractorApi() {
    internal val pluginPath: String = pluginFile.canonicalPath
    internal val routeId: String = UUID.randomUUID().toString()

    override val name: String get() = descriptor.name
    override val mainUrl: String get() = descriptor.mainUrl
    override val requiresReferer: Boolean get() = descriptor.requiresReferer

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val result = invoke("extractorGetUrl", extractorArguments(url, referer)) ?: return null
        return ProviderRpcJson.decodeExtractorLinks(result)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        invoke("extractorGetUrlEvents", extractorArguments(url, referer), onEvent = { event ->
            when (event.path("event").asText()) {
                "link" -> callback(ProviderRpcJson.decodeExtractorLink(event.path("value")))
                "subtitle" -> subtitleCallback(ProviderRpcJson.decodeSubtitle(event.path("value")))
                else -> throw PluginProviderRpcException("Unknown extractor worker event")
            }
        })
    }

    override fun getExtractorUrl(id: String): String = runBlocking {
        invoke("getExtractorUrl", ProviderRpcJson.mapper.createObjectNode().put("id", id))?.asText() ?: id
    }

    internal suspend fun getUrlFromHost(
        url: String,
        referer: String?,
        workerPaths: List<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        invoke(
            operation = "extractorGetUrlEvents",
            arguments = extractorArguments(url, referer),
            onEvent = { event ->
                when (event.path("event").asText()) {
                    "link" -> callback(ProviderRpcJson.decodeExtractorLink(event.path("value")))
                    "subtitle" -> subtitleCallback(ProviderRpcJson.decodeSubtitle(event.path("value")))
                    else -> throw PluginProviderRpcException("Unknown plugin extractor event")
                }
            },
            workerPaths = workerPaths,
        )
    }

    internal suspend fun getExtractorUrlFromHost(id: String, workerPaths: List<String>): String =
        invoke(
            operation = "getExtractorUrl",
            arguments = ProviderRpcJson.mapper.createObjectNode().put("id", id),
            workerPaths = workerPaths,
        )?.asText() ?: id

    private fun extractorArguments(url: String, referer: String?) = ProviderRpcJson.mapper.createObjectNode().apply {
        put("url", url)
        putNullableText("referer", referer)
    }

    private suspend fun invoke(
        operation: String,
        arguments: JsonNode,
        onEvent: ((JsonNode) -> Unit)? = null,
        workerPaths: List<String> = listOf(pluginPath),
    ): JsonNode? = worker.withLease {
        PluginProviderWorkerRegistry.ensureExternalExtractors(pluginPath, worker)
        val request = ProviderRpcJson.mapper.createObjectNode().apply {
            put("providerIndex", -1)
            put("providerClass", "")
            put("extractorIndex", extractorIndex)
            put("extractorClass", descriptor.className)
            set<JsonNode>("workerPaths", ProviderRpcJson.mapper.valueToTree(workerPaths))
            put("operation", operation)
            set<JsonNode>("arguments", arguments)
        }
        val bytes = worker.request(
            request.toString().toByteArray(StandardCharsets.UTF_8),
            120_000L,
            onIntermediateFrame = { frame ->
                if (PluginProviderWorkerRegistry.handlePluginWorkerEvent(pluginPath, frame)) {
                    true
                } else {
                    val event = ProviderRpcJson.mapper.readTree(frame)
                    if (event.path("event").asText() == "hostRequest" || !event.hasNonNull("event")) {
                        false
                    } else {
                        val handler = onEvent ?: throw PluginProviderRpcException("Unexpected extractor worker event")
                        handler(event)
                        true
                    }
                }
            },
            onWorkerRequest = { frame -> PluginProviderWorkerRegistry.handleHostRequest(pluginPath, workerPaths, frame) },
        )
        val response = ProviderRpcJson.mapper.readTree(bytes)
        if (!response.path("ok").asBoolean(false)) {
            val type = response.path("errorType").asText("PluginError").take(160)
            val message = response.path("errorMessage").asText("Plugin extractor request failed").take(1200)
            throw PluginProviderRpcException("$type: $message")
        }
        response.get("value")?.takeUnless(JsonNode::isNull)
    }
}

private class HostRoutedExtractorApi(
    private val routeId: String,
    override val name: String,
    override val mainUrl: String,
    override val requiresReferer: Boolean,
    private val requestHost: (ObjectNode) -> JsonNode,
) : ExtractorApi() {
    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        invokeHost("getUrl", url, referer).path("links").forEach { value ->
            links += ProviderRpcJson.decodeExtractorLink(value)
        }
        return links.takeIf { it.isNotEmpty() }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val result = invokeHost("getUrl", url, referer)
        result.path("links").forEach { value -> callback(ProviderRpcJson.decodeExtractorLink(value)) }
        result.path("subtitles").forEach { value -> subtitleCallback(ProviderRpcJson.decodeSubtitle(value)) }
    }

    override fun getExtractorUrl(id: String): String = runBlocking {
        invokeHost("getExtractorUrl", id = id).path("value").asText(id)
    }

    private suspend fun invokeHost(operation: String, url: String? = null, referer: String? = null, id: String? = null): JsonNode {
        val frame = ProviderRpcJson.mapper.createObjectNode()
            .put("event", "hostRequest")
            .put("requestId", UUID.randomUUID().toString())
            .put("operation", operation)
            .put("routeId", routeId)
        url?.let { frame.put("url", it) }
        putNullableText(frame, "referer", referer)
        id?.let { frame.put("id", it) }
        val response = requestHost(frame)
        if (response.path("hostResponseId").asText() != frame.path("requestId").asText()) {
            throw PluginProviderRpcException("Mismatched host extractor response")
        }
        if (!response.path("ok").asBoolean(false)) {
            throw PluginProviderRpcException(response.path("error").asText("Host extractor request failed").take(1200))
        }
        return response
    }

    private fun putNullableText(node: ObjectNode, field: String, value: String?) {
        if (value == null) node.putNull(field) else node.put(field, value)
    }
}

private class PluginWorkerCallContext(
    val workerPaths: List<String>,
) : ThreadContextElement<List<String>?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PluginWorkerCallContext> {
        private val currentPaths = ThreadLocal<List<String>?>()
        fun current(): List<String> = currentPaths.get().orEmpty()
    }

    override fun updateThreadContext(context: CoroutineContext): List<String>? {
        val previous = currentPaths.get()
        currentPaths.set(workerPaths)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: List<String>?) {
        if (oldState == null) currentPaths.remove() else currentPaths.set(oldState)
    }
}

class PluginProviderRpcException(message: String) : IllegalStateException(message)

internal object ProviderRpcJson {
    private const val EXTRACTOR_LINK_ENVELOPE = "orbit.extractor-link.v1"
    private val searchTypes = mapOf(
        "anime" to AnimeSearchResponse::class.java,
        "torrent" to TorrentSearchResponse::class.java,
        "movie" to MovieSearchResponse::class.java,
        "live" to LiveSearchResponse::class.java,
        "tvSeries" to TvSeriesSearchResponse::class.java,
    )
    private val loadTypes = mapOf(
        "torrent" to TorrentLoadResponse::class.java,
        "anime" to AnimeLoadResponse::class.java,
        "liveStream" to LiveStreamLoadResponse::class.java,
        "movie" to MovieLoadResponse::class.java,
        "tvSeries" to TvSeriesLoadResponse::class.java,
    )

    val mapper: ObjectMapper = jacksonObjectMapper().registerModule(kotlinModule()).registerModule(
        SimpleModule().addDeserializer(
            SearchResponse::class.java,
            object : JsonDeserializer<SearchResponse>() {
                override fun deserialize(p: com.fasterxml.jackson.core.JsonParser, ctxt: DeserializationContext): SearchResponse {
                    val envelope = p.codec.readTree<JsonNode>(p)
                    return decodeSearchResponse(envelope)
                }
            },
        ),
    )

    fun encodeExtractorLink(link: ExtractorLink): ObjectNode {
        val payload = mapper.valueToTree<ObjectNode>(link)
        if (link is DrmExtractorLink) payload.put("uuid", link.uuid.toString())
        val kind = when (link) {
            is ExtractorLinkPlayList -> "playlist"
            is DrmExtractorLink -> "drm"
            else -> "standard"
        }
        return mapper.createObjectNode()
            .put("_rpcType", EXTRACTOR_LINK_ENVELOPE)
            .put("kind", kind)
            .set<ObjectNode>("value", payload)
    }

    fun encodeExtractorLinks(links: Iterable<ExtractorLink>): ArrayNode =
        mapper.createArrayNode().also { output -> links.forEach { output.add(encodeExtractorLink(it)) } }

    fun decodeExtractorLinks(value: JsonNode): List<ExtractorLink> {
        if (!value.isArray) throw PluginProviderRpcException("Plugin worker returned invalid extractor links")
        return value.map(::decodeExtractorLink)
    }

    fun decodeExtractorLink(value: JsonNode): ExtractorLink {
        val isEnvelope = value.path("_rpcType").asText() == EXTRACTOR_LINK_ENVELOPE
        val kind = if (isEnvelope) {
            value.path("kind").asText()
        } else {
            // Accept frames from older workers, which serialized concrete objects without a discriminator.
            when {
                value.has("playlist") -> "playlist"
                value.has("uuid") && (value.has("kid") || value.has("key") || value.has("licenseUrl")) -> "drm"
                else -> "standard"
            }
        }
        val payload = if (isEnvelope) value.path("value") else value
        return when (kind) {
            "standard" -> mapper.treeToValue(payload, ExtractorLink::class.java)
            "playlist" -> mapper.treeToValue(payload, ExtractorLinkPlayList::class.java)
            "drm" -> decodeDrmExtractorLink(payload)
            else -> throw PluginProviderRpcException("Plugin worker returned an unknown extractor link type")
        }
    }

    @Suppress("DEPRECATION")
    private fun decodeDrmExtractorLink(value: JsonNode): DrmExtractorLink {
        fun text(name: String, fallback: String = ""): String = value.get(name)?.takeUnless(JsonNode::isNull)?.asText() ?: fallback
        val uuidNode = value.get("uuid")?.takeUnless(JsonNode::isNull)
            ?: throw PluginProviderRpcException("Plugin worker returned a missing DRM UUID")
        val uuid = runCatching {
            when {
                uuidNode.isTextual -> java.util.UUID.fromString(uuidNode.asText())
                uuidNode.isObject && uuidNode.has("mostSignificantBits") && uuidNode.has("leastSignificantBits") ->
                    java.util.UUID(uuidNode.path("mostSignificantBits").asLong(), uuidNode.path("leastSignificantBits").asLong())
                else -> throw IllegalArgumentException("Unsupported UUID representation")
            }
        }
            .getOrElse { throw PluginProviderRpcException("Plugin worker returned an invalid DRM UUID") }
        val mediaType = value.get("type")?.takeUnless(JsonNode::isNull)?.let {
            mapper.treeToValue(it, ExtractorLinkType::class.java)
        }
        return runBlocking {
            newDrmExtractorLink(
                source = text("source"),
                name = text("name"),
                url = text("url"),
                type = mediaType,
                uuid = uuid,
            ) {
                referer = text("referer")
                quality = value.path("quality").asInt()
                headers = mapper.convertValue(value.path("headers"), object : TypeReference<Map<String, String>>() {})
                extractorData = value.get("extractorData")?.takeUnless(JsonNode::isNull)?.asText()
                kid = value.get("kid")?.takeUnless(JsonNode::isNull)?.asText()
                key = value.get("key")?.takeUnless(JsonNode::isNull)?.asText()
                kty = value.get("kty")?.takeUnless(JsonNode::isNull)?.asText()
                keyRequestParameters = mapper.convertValue(value.path("keyRequestParameters"), object : TypeReference<HashMap<String, String>>() {})
                licenseUrl = value.get("licenseUrl")?.takeUnless(JsonNode::isNull)?.asText()
                audioTracks = mapper.convertValue(value.path("audioTracks"), object : TypeReference<List<AudioFile>>() {})
            }
        }
    }

    fun encodePluginSettingSchema(schema: PluginSettingSchema): ObjectNode = mapper.createObjectNode().apply {
        put("event", "pluginSettingSchema")
        put("pluginPrefName", schema.pluginPrefName)
        put("key", schema.key)
        put("type", schema.type)
        if (schema.defaultValue == null) putNull("defaultValue") else set<JsonNode>("defaultValue", mapper.valueToTree(schema.defaultValue))
        put("isGlobal", schema.isGlobal)
        if (schema.options == null) putNull("options") else set<JsonNode>("options", mapper.valueToTree(schema.options))
    }

    fun decodePluginSettingSchema(node: JsonNode): PluginSettingSchema {
        val type = node.path("type").asText()
        val defaultNode = node.get("defaultValue")
        val defaultValue: Any? = when (type) {
            "Boolean" -> defaultNode?.takeUnless(JsonNode::isNull)?.asBoolean()
            "String" -> defaultNode?.takeUnless(JsonNode::isNull)?.asText()
            "Int" -> defaultNode?.takeUnless(JsonNode::isNull)?.asInt()
            "Long" -> defaultNode?.takeUnless(JsonNode::isNull)?.asLong()
            "Float" -> defaultNode?.takeUnless(JsonNode::isNull)?.floatValue()
            "StringSet" -> defaultNode?.takeUnless(JsonNode::isNull)?.map(JsonNode::asText)?.toSet()
            else -> throw PluginProviderRpcException("Unsupported plugin setting type")
        }
        val optionsNode = node.get("options")?.takeUnless(JsonNode::isNull)
        val options = optionsNode?.let {
            mapper.convertValue(it, object : TypeReference<Map<String, String>>() {})
        }
        return PluginSettingSchema(
            pluginPrefName = node.path("pluginPrefName").asText(),
            key = node.path("key").asText(),
            type = type,
            defaultValue = defaultValue,
            isGlobal = node.path("isGlobal").asBoolean(false),
            options = options,
        )
    }

    fun encodeRequest(request: Request): ObjectNode = mapper.createObjectNode().apply {
        put("url", request.url.toString())
        put("method", request.method)
        set<JsonNode>("headers", mapper.valueToTree(request.headers.toMultimap()))
    }

    fun decodeRequest(node: JsonNode, body: RequestBody? = null): Request {
        val method = node.path("method").asText("GET")
        return Request.Builder()
            .url(node.path("url").asText())
            .headers(decodeHeaders(node.path("headers")))
            .method(method, body)
            .build()
    }

    fun decodeHeaders(node: JsonNode): Headers {
        val values: Map<String, List<String>> = runCatching {
            mapper.convertValue(node, object : TypeReference<Map<String, List<String>>>() {})
        }.getOrDefault(emptyMap())
        val builder = Headers.Builder()
        values.forEach { (name, entries) -> entries.forEach { value -> builder.add(name, value) } }
        return builder.build()
    }

    fun encodePluginDescriptors(providers: List<MainAPI>, extractors: List<ExtractorApi>): ObjectNode = mapper.createObjectNode().apply {
        put("providerCount", providers.size)
        set<ArrayNode>(
            "providers",
            mapper.createArrayNode().also { output ->
                providers.forEach { output.add(encodeProviderDescriptor(it)) }
            },
        )
        put("extractorCount", extractors.size)
        set<ArrayNode>(
            "extractors",
            mapper.createArrayNode().also { output ->
                extractors.forEach { extractor ->
                    output.add(
                        mapper.createObjectNode()
                            .put("className", extractor.javaClass.name)
                            .put("name", extractor.name)
                            .put("mainUrl", extractor.mainUrl)
                            .put("requiresReferer", extractor.requiresReferer),
                    )
                }
            },
        )
    }

    fun encodeProviderDescriptor(provider: MainAPI): ObjectNode = mapper.createObjectNode().apply {
        put("className", provider.javaClass.name)
        put("name", provider.name)
        put("mainUrl", provider.mainUrl)
        putNullableText("storedCredentials", provider.storedCredentials)
        put("canBeOverridden", provider.canBeOverridden)
        put("sequentialMainPage", provider.sequentialMainPage)
        put("sequentialMainPageDelay", provider.sequentialMainPageDelay)
        put("sequentialMainPageScrollDelay", provider.sequentialMainPageScrollDelay)
        put("lang", provider.lang)
        put("instantLinkLoading", provider.instantLinkLoading)
        put("hasChromecastSupport", provider.hasChromecastSupport)
        put("hasDownloadSupport", provider.hasDownloadSupport)
        put("usesWebView", provider.usesWebView)
        put("hasMainPage", provider.hasMainPage)
        put("hasQuickSearch", provider.hasQuickSearch)
        putNullableLong("loadLinksTimeoutMs", provider.loadLinksTimeoutMs)
        putNullableLong("getMainPageTimeoutMs", provider.getMainPageTimeoutMs)
        putNullableLong("searchTimeoutMs", provider.searchTimeoutMs)
        putNullableLong("quickSearchTimeoutMs", provider.quickSearchTimeoutMs)
        putNullableLong("loadTimeoutMs", provider.loadTimeoutMs)
        set<JsonNode>("supportedSyncNames", mapper.valueToTree(provider.supportedSyncNames.map { it.name }))
        set<JsonNode>("supportedTypes", mapper.valueToTree(provider.supportedTypes.map { it.name }))
        put("vpnStatus", provider.vpnStatus.name)
        put("providerType", provider.providerType.name)
        set<JsonNode>("mainPage", mapper.valueToTree(provider.mainPage))
    }

    fun decodeProviderDescriptor(node: JsonNode): WorkerProviderDescriptor {
        fun enumNames(field: String): List<String> = node.path(field).map(JsonNode::asText)
        fun <T : Enum<T>> enumSet(field: String, enumClass: Class<T>): Set<T> = enumNames(field).mapNotNull { name ->
            runCatching { java.lang.Enum.valueOf(enumClass, name) }.getOrNull()
        }.toSet()
        fun nullableLong(field: String): Long? = node.get(field)?.takeUnless(JsonNode::isNull)?.asLong()

        return WorkerProviderDescriptor(
            className = node.path("className").asText(),
            name = node.path("name").asText("Unnamed provider"),
            mainUrl = node.path("mainUrl").asText(),
            storedCredentials = node.get("storedCredentials")?.takeUnless(JsonNode::isNull)?.asText(),
            canBeOverridden = node.path("canBeOverridden").asBoolean(true),
            sequentialMainPage = node.path("sequentialMainPage").asBoolean(false),
            sequentialMainPageDelay = node.path("sequentialMainPageDelay").asLong(0L),
            sequentialMainPageScrollDelay = node.path("sequentialMainPageScrollDelay").asLong(0L),
            lang = node.path("lang").asText("en"),
            instantLinkLoading = node.path("instantLinkLoading").asBoolean(false),
            hasChromecastSupport = node.path("hasChromecastSupport").asBoolean(true),
            hasDownloadSupport = node.path("hasDownloadSupport").asBoolean(true),
            usesWebView = node.path("usesWebView").asBoolean(false),
            hasMainPage = node.path("hasMainPage").asBoolean(false),
            hasQuickSearch = node.path("hasQuickSearch").asBoolean(false),
            loadLinksTimeoutMs = nullableLong("loadLinksTimeoutMs"),
            getMainPageTimeoutMs = nullableLong("getMainPageTimeoutMs"),
            searchTimeoutMs = nullableLong("searchTimeoutMs"),
            quickSearchTimeoutMs = nullableLong("quickSearchTimeoutMs"),
            loadTimeoutMs = nullableLong("loadTimeoutMs"),
            supportedSyncNames = enumSet("supportedSyncNames", com.lagradost.cloudstream3.syncproviders.SyncIdName::class.java),
            supportedTypes = enumSet("supportedTypes", com.lagradost.cloudstream3.TvType::class.java),
            vpnStatus = runCatching { com.lagradost.cloudstream3.VPNStatus.valueOf(node.path("vpnStatus").asText()) }.getOrDefault(com.lagradost.cloudstream3.VPNStatus.None),
            providerType = runCatching { com.lagradost.cloudstream3.ProviderType.valueOf(node.path("providerType").asText()) }.getOrDefault(com.lagradost.cloudstream3.ProviderType.DirectProvider),
            mainPage = mapper.convertValue(node.path("mainPage"), object : TypeReference<List<MainPageData>>() {}),
        )
    }

    fun decodeExtractorDescriptor(node: JsonNode): WorkerExtractorDescriptor = WorkerExtractorDescriptor(
        className = node.path("className").asText(),
        name = node.path("name").asText("Unnamed extractor"),
        mainUrl = node.path("mainUrl").asText(),
        requiresReferer = node.path("requiresReferer").asBoolean(false),
    )

    fun encodeSearchResponse(response: SearchResponse): ObjectNode {
        val kind = when (response) {
            is AnimeSearchResponse -> "anime"
            is TorrentSearchResponse -> "torrent"
            is MovieSearchResponse -> "movie"
            is LiveSearchResponse -> "live"
            is TvSeriesSearchResponse -> "tvSeries"
            else -> throw PluginProviderRpcException("Unsupported search response type ${response.javaClass.name}")
        }
        return mapper.createObjectNode().put("kind", kind).set("value", mapper.valueToTree(response))
    }

    fun decodeSearchResponse(envelope: JsonNode): SearchResponse {
        val kind = envelope.path("kind").asText()
        val responseType = searchTypes[kind] ?: throw PluginProviderRpcException("Unsupported search response kind '$kind'")
        return mapper.treeToValue(envelope.path("value"), responseType) as SearchResponse
    }

    fun encodeSearchResponseList(response: SearchResponseList): ObjectNode = mapper.createObjectNode().apply {
        put("hasNext", response.hasNext)
        set<ArrayNode>("items", mapper.createArrayNode().also { output -> response.items.forEach { output.add(encodeSearchResponse(it)) } })
    }

    @Suppress("DEPRECATION_ERROR")
    fun decodeSearchResponseList(node: JsonNode): SearchResponseList = SearchResponseList(
        node.path("items").map(::decodeSearchResponse),
        node.path("hasNext").asBoolean(false),
    )

    fun encodeHomePage(response: HomePageResponse): ObjectNode = mapper.createObjectNode().apply {
        put("hasNext", response.hasNext)
        set<ArrayNode>(
            "items",
            mapper.createArrayNode().also { output ->
                response.items.forEach { page ->
                    output.add(
                        mapper.createObjectNode().apply {
                            put("name", page.name)
                            put("isHorizontalImages", page.isHorizontalImages)
                            set<ArrayNode>("list", mapper.createArrayNode().also { list -> page.list.forEach { list.add(encodeSearchResponse(it)) } })
                        },
                    )
                }
            },
        )
    }

    @Suppress("DEPRECATION_ERROR")
    fun decodeHomePage(node: JsonNode): HomePageResponse = HomePageResponse(
        node.path("items").map { page ->
            HomePageList(page.path("name").asText(), page.path("list").map(::decodeSearchResponse), page.path("isHorizontalImages").asBoolean(false))
        },
        node.path("hasNext").asBoolean(false),
    )

    fun encodeLoadResponse(response: LoadResponse): ObjectNode {
        val kind = when (response) {
            is TorrentLoadResponse -> "torrent"
            is AnimeLoadResponse -> "anime"
            is LiveStreamLoadResponse -> "liveStream"
            is MovieLoadResponse -> "movie"
            is TvSeriesLoadResponse -> "tvSeries"
            else -> throw PluginProviderRpcException("Unsupported load response type ${response.javaClass.name}")
        }
        val value = mapper.valueToTree<ObjectNode>(response)
        value.set<ArrayNode>(
            "recommendations",
            mapper.createArrayNode().also { output ->
                response.recommendations?.forEach { output.add(encodeSearchResponse(it)) }
            },
        )
        return mapper.createObjectNode().put("kind", kind).set("value", value)
    }

    fun decodeLoadResponse(envelope: JsonNode): LoadResponse {
        val kind = envelope.path("kind").asText()
        val responseType = loadTypes[kind] ?: throw PluginProviderRpcException("Unsupported load response kind '$kind'")
        return mapper.treeToValue(envelope.path("value"), responseType) as LoadResponse
    }

    fun encodeSubtitle(subtitle: SubtitleFile): ObjectNode = mapper.createObjectNode().apply {
        put("lang", subtitle.lang)
        put("url", subtitle.url)
        set<JsonNode>("headers", mapper.valueToTree(subtitle.headers))
    }

    fun decodeSubtitle(node: JsonNode): SubtitleFile = runBlocking {
        newSubtitleFile(node.path("lang").asText(), node.path("url").asText()) {
            headers = node.get("headers")?.let { mapper.convertValue(it, object : TypeReference<Map<String, String>>() {}) }
        }
    }
}

private fun ObjectNode.putNullableText(field: String, value: String?): ObjectNode {
    if (value == null) putNull(field) else put(field, value)
    return this
}

private fun ObjectNode.putNullableLong(field: String, value: Long?): ObjectNode {
    if (value == null) putNull(field) else put(field, value)
    return this
}

/** Child-process entry point invoked by the packaged app executable. Stdout is reserved for framed RPC. */
object PluginProviderWorkerMain {
    fun run(args: Array<String>) {
        require(args.size == 3) { "Expected plugin archive, provider index, and app data path" }
        System.setProperty("auras.data.dir", args[2])
        val pluginFile = File(args[0]).canonicalFile
        require(pluginFile.isFile) { "Plugin archive does not exist" }
        val providerIndex = args[1].toIntOrNull()?.takeIf { it >= -1 } ?: error("Invalid provider index")

        val rawInput = DataInputStream(BufferedInputStream(FileInputStream(java.io.FileDescriptor.`in`)))
        val rawOutput = DataOutputStream(BufferedOutputStream(FileOutputStream(java.io.FileDescriptor.out)))
        var activeHostRequest: ((ObjectNode) -> JsonNode)? = null
        System.setOut(PrintStream(System.err, true, StandardCharsets.UTF_8))
        System.setProperty("auras.logs.dir", PlatformPaths.logsDir.absolutePath.replace('\\', '/'))
        runCatching { DesktopDataStore.init() }
        runCatching { com.lagradost.cloudstream3.desktop.init.initWindowsEnvironment() }
        runCatching { com.lagradost.cloudstream3.desktop.init.initSecurity() }
        runCatching { com.lagradost.cloudstream3.desktop.init.initNetwork() }

        try {
            ExtensionLoader.loadAndInit(pluginFile)
            APIHolder.initAll()
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            AppLogger.e("Plugin worker bootstrap failed for ${pluginFile.name}", failure)
            reportBootstrapFailure(rawInput, rawOutput, failure)
            return
        }
        val providers = APIHolder.allProviders.filter { provider ->
            provider.sourcePlugin?.let { runCatching { File(it).canonicalPath == pluginFile.canonicalPath }.getOrDefault(false) } == true
        }
        val extractors = com.lagradost.cloudstream3.utils.extractorApis.filter { extractor ->
            extractor.sourcePlugin?.let { runCatching { File(it).canonicalPath == pluginFile.canonicalPath }.getOrDefault(false) } == true
        }
        check(providerIndex >= -1) { "Invalid worker provider index" }
        val videoInterceptors = ConcurrentHashMap<String, Interceptor>()
        val reportedPluginSettings = mutableMapOf<String, String>()

        fun pendingPluginSettingFrames(): List<ByteArray> {
            val internalName = ExtensionLoader.getPluginInternalName(pluginFile.absolutePath) ?: return emptyList()
            val prefName = "${internalName}_"
            val schemas = PluginSettingsSchemaRegistry.schemas[prefName]?.values.orEmpty()
                .filterNot { PluginSettingsSchemaRegistry.isHiddenKey(it.key) }
                .sortedBy(PluginSettingSchema::key)
            return schemas.mapNotNull { schema ->
                val bytes = ProviderRpcJson.encodePluginSettingSchema(schema).toString().toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= MAX_PLUGIN_SETTING_SCHEMA_FRAME_BYTES) { "Plugin settings schema event exceeds its size limit" }
                val signature = String(bytes, StandardCharsets.UTF_8)
                if (reportedPluginSettings[schema.key] == signature) {
                    null
                } else {
                    reportedPluginSettings[schema.key] = signature
                    bytes
                }
            }
        }

        while (true) {
            val size = try {
                rawInput.readInt()
            } catch (_: java.io.EOFException) {
                return
            }
            require(size in 1..MAX_PROVIDER_RPC_FRAME_BYTES) { "Invalid provider RPC frame length" }
            val payload = ByteArray(size)
            rawInput.readFully(payload)
            val outputLock = Any()
            val hostRequestLock = Any()
            var acceptingEvents = true
            var currentOperation = "unknown"
            fun writeFrame(bytes: ByteArray) {
                require(bytes.size <= MAX_PROVIDER_RPC_FRAME_BYTES) { "Plugin worker frame exceeded its size limit" }
                rawOutput.writeInt(bytes.size)
                rawOutput.write(bytes)
                rawOutput.flush()
            }
            fun requestHost(frame: ObjectNode): JsonNode = synchronized(hostRequestLock) {
                val requestBytes = frame.toString().toByteArray(StandardCharsets.UTF_8)
                require(requestBytes.size <= MAX_PROVIDER_RPC_FRAME_BYTES) { "Host extractor request exceeded its transfer limit" }
                synchronized(outputLock) {
                    check(acceptingEvents) { "Host extractor request made outside an active plugin call" }
                    writeFrame(requestBytes)
                }
                val responseSize = rawInput.readInt()
                require(responseSize in 1..MAX_PROVIDER_RPC_FRAME_BYTES) { "Invalid host extractor response frame length" }
                val responseBytes = ByteArray(responseSize)
                rawInput.readFully(responseBytes)
                val response = ProviderRpcJson.mapper.readTree(responseBytes)
                require(response.path("hostResponseId").asText() == frame.path("requestId").asText()) {
                    "Host extractor response did not match its request"
                }
                response
            }
            activeHostRequest = ::requestHost
            val response = runCatching {
                val request = ProviderRpcJson.mapper.readTree(payload)
                currentOperation = request.path("operation").asText("unknown")
                dispatch(
                    request,
                    providers,
                    extractors,
                    pluginFile,
                    videoInterceptors,
                    emitEvent = { event ->
                        val bytes = event.toString().toByteArray(StandardCharsets.UTF_8)
                        synchronized(outputLock) {
                            if (acceptingEvents) writeFrame(bytes)
                        }
                    },
                    requestHost = { frame ->
                        (activeHostRequest ?: throw PluginProviderRpcException("Host extractor request made outside an active plugin call"))(frame)
                    },
                )
            }.fold(
                onSuccess = { result -> ProviderRpcJson.mapper.createObjectNode().put("ok", true).set<JsonNode>("value", result ?: ProviderRpcJson.mapper.nullNode()) },
                onFailure = { failure ->
                    ProviderRpcJson.mapper.createObjectNode().put("ok", false)
                        .put("errorType", failure.javaClass.simpleName.take(160))
                        .put("errorMessage", "$currentOperation: ${failure.message ?: "Plugin provider request failed"}".take(1200))
                },
            ).toString().toByteArray(StandardCharsets.UTF_8)
            pendingPluginSettingFrames().forEach { frame ->
                synchronized(outputLock) {
                    if (acceptingEvents) writeFrame(frame)
                }
            }
            synchronized(outputLock) {
                acceptingEvents = false
                writeFrame(response)
            }
            activeHostRequest = null
        }
    }

    private fun reportBootstrapFailure(
        input: DataInputStream,
        output: DataOutputStream,
        failure: Throwable,
    ) {
        val requestSize = input.readInt()
        require(requestSize in 1..MAX_PROVIDER_RPC_FRAME_BYTES) { "Invalid provider RPC bootstrap frame length" }
        input.readFully(ByteArray(requestSize))
        val response = ProviderRpcJson.mapper.createObjectNode()
            .put("ok", false)
            .put("errorType", failure.javaClass.simpleName.take(160))
            .put("errorMessage", "Plugin worker bootstrap failed: ${failure.message ?: "Plugin initialization failed"}".take(1200))
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        require(response.size in 1..MAX_PROVIDER_RPC_FRAME_BYTES) { "Plugin worker bootstrap error exceeded its transfer limit" }
        output.writeInt(response.size)
        output.write(response)
        output.flush()
    }

    private fun dispatch(
        request: JsonNode,
        providers: List<MainAPI>,
        extractors: List<ExtractorApi>,
        pluginFile: File,
        videoInterceptors: ConcurrentHashMap<String, Interceptor>,
        emitEvent: (JsonNode) -> Unit,
        requestHost: (ObjectNode) -> JsonNode,
    ): JsonNode? {
        when (request.path("operation").asText()) {
            "setExternalExtractors" -> {
                val descriptors = request.path("arguments").path("extractors")
                val routes = descriptors.map { descriptor ->
                    HostRoutedExtractorApi(
                        routeId = descriptor.path("routeId").asText(),
                        name = descriptor.path("name").asText(),
                        mainUrl = descriptor.path("mainUrl").asText(),
                        requiresReferer = descriptor.path("requiresReferer").asBoolean(false),
                        requestHost = requestHost,
                    ).also { it.sourcePlugin = HOST_EXTERNAL_EXTRACTOR_SOURCE }
                }
                synchronized(com.lagradost.cloudstream3.utils.extractorApis) {
                    com.lagradost.cloudstream3.utils.extractorApis.removeIf { it.sourcePlugin == HOST_EXTERNAL_EXTRACTOR_SOURCE }
                    com.lagradost.cloudstream3.utils.extractorApis.addAll(routes)
                }
                return ProviderRpcJson.mapper.createObjectNode().put("installed", routes.size)
            }
            "describeProviders" -> return ProviderRpcJson.encodePluginDescriptors(providers, extractors).apply {
                set<JsonNode>("plugin", describePlugin(pluginFile))
            }
            "openPluginSettings" -> {
                val plugin = ExtensionLoader.getPlugin(pluginFile.absolutePath) as? Plugin
                    ?: throw PluginProviderRpcException("Loaded plugin does not expose custom settings")
                val openSettings = plugin.openSettings ?: throw PluginProviderRpcException("Plugin custom settings callback is no longer registered")
                openSettings.invoke(android.content.DesktopContextProvider.context)
                return ProviderRpcJson.mapper.createObjectNode().put("invoked", true)
            }
            "invokeVideoClickAction" -> {
                val actionId = request.path("arguments").path("actionId").asInt(-1)
                val action = pluginVideoActions(pluginFile).getOrNull(actionId)
                    ?: throw PluginProviderRpcException("Video click action is no longer registered")
                action.callback.invoke()
                return ProviderRpcJson.mapper.createObjectNode().put("invoked", true)
            }
            "shutdown" -> {
                ExtensionLoader.unloadPlugin(pluginFile.absolutePath)
                return ProviderRpcJson.mapper.createObjectNode().put("shutdown", true)
            }
        }
        val operation = request.path("operation").asText()
        if (operation == "extractorGetUrl" || operation == "extractorGetUrlEvents" || operation == "getExtractorUrl") {
            val extractorIndex = request.path("extractorIndex").asInt(-1)
            require(extractorIndex in extractors.indices) { "Missing or invalid extractor index" }
            val extractor = extractors[extractorIndex]
            require(request.path("extractorClass").asText() == extractor.javaClass.name) { "Extractor class changed in worker" }
            val args = request.path("arguments")
            return runBlocking(Dispatchers.IO + PluginWorkerCallContext(request.path("workerPaths").map { it.asText() })) {
                when (operation) {
                    "extractorGetUrl" -> extractor.getUrl(
                        args.path("url").asText(),
                        args.get("referer")?.takeUnless(JsonNode::isNull)?.asText(),
                    )?.let { links -> ProviderRpcJson.mapper.valueToTree<JsonNode>(links) }
                    "extractorGetUrlEvents" -> {
                        val url = args.path("url").asText()
                        val referer = args.get("referer")?.takeUnless(JsonNode::isNull)?.asText()
                        extractor.getUrl(url, referer, { subtitle ->
                            emitEvent(ProviderRpcJson.mapper.createObjectNode().put("event", "subtitle").set<JsonNode>("value", ProviderRpcJson.encodeSubtitle(subtitle)))
                        }, { link ->
                            val linkValue: JsonNode = ProviderRpcJson.encodeExtractorLink(link)
                            val event = ProviderRpcJson.mapper.createObjectNode().put("event", "link")
                            event.set<JsonNode>("value", linkValue)
                            emitEvent(event)
                        })
                        ProviderRpcJson.mapper.createObjectNode().put("success", true)
                    }
                    else -> extractor.getExtractorUrl(args.path("id").asText()).let { value ->
                        ProviderRpcJson.mapper.valueToTree<JsonNode>(value)
                    }
                }
            }
        }
        val providerIndex = request.path("providerIndex").asInt(-1)
        require(providerIndex in providers.indices) { "Missing or invalid provider index" }
        val provider = providers[providerIndex]
        require(request.path("providerClass").asText() == provider.javaClass.name) { "Provider class changed in worker" }
        provider.name = request.path("providerName").asText(provider.name)
        provider.mainUrl = request.path("mainUrl").asText(provider.mainUrl)
        provider.lang = request.path("lang").asText(provider.lang)
        provider.storedCredentials = request.get("storedCredentials")?.takeUnless(JsonNode::isNull)?.asText()
        val args = request.path("arguments")
        return runBlocking(Dispatchers.IO + PluginWorkerCallContext(request.path("workerPaths").map { it.asText() })) {
            when (request.path("operation").asText()) {
                "ready" -> ProviderRpcJson.mapper.createObjectNode().put("ready", true)
                "initializeProvider" -> {
                    provider.init()
                    ProviderRpcJson.encodeProviderDescriptor(provider)
                }
                "prepareVideoInterceptor" -> {
                    val link = ProviderRpcJson.decodeExtractorLink(args.path("link"))
                    val interceptor = provider.getVideoInterceptor(link)
                    val id = args.path("interceptorId").asText()
                    if (interceptor != null && id.isNotBlank()) videoInterceptors[id] = interceptor
                    ProviderRpcJson.mapper.createObjectNode().put("registered", interceptor != null)
                }
                "interceptVideoRequest" -> {
                    val id = args.path("interceptorId").asText()
                    val link = ProviderRpcJson.decodeExtractorLink(args.path("link"))
                    val interceptor = videoInterceptors[id] ?: provider.getVideoInterceptor(link)?.also {
                        if (id.isNotBlank()) videoInterceptors[id] = it
                    }
                    if (interceptor == null) {
                        ProviderRpcJson.mapper.createObjectNode().put("registered", false)
                    } else {
                        val input = ProviderRpcJson.decodeRequest(args.path("request"))
                        runRequestInterceptor(interceptor, input, id, requestHost)
                    }
                }
                "getMainPage" -> provider.getMainPage(
                    args.path("page").asInt(),
                    ProviderRpcJson.mapper.treeToValue(args.path("request"), MainPageRequest::class.java),
                )?.let(ProviderRpcJson::encodeHomePage)
                "searchPage" -> provider.search(args.path("query").asText(), args.path("page").asInt())?.let(ProviderRpcJson::encodeSearchResponseList)
                "search" -> provider.search(args.path("query").asText())?.let { list -> ProviderRpcJson.mapper.createArrayNode().also { output -> list.forEach { output.add(ProviderRpcJson.encodeSearchResponse(it)) } } }
                "quickSearch" -> provider.quickSearch(args.path("query").asText())?.let { list -> ProviderRpcJson.mapper.createArrayNode().also { output -> list.forEach { output.add(ProviderRpcJson.encodeSearchResponse(it)) } } }
                "load" -> provider.load(args.path("url").asText())?.let(ProviderRpcJson::encodeLoadResponse)
                "extractorVerifierJob" -> {
                    provider.extractorVerifierJob(args.get("extractorData")?.takeUnless(JsonNode::isNull)?.asText())
                    null
                }
                "getLoadUrl" -> provider.getLoadUrl(
                    com.lagradost.cloudstream3.syncproviders.SyncIdName.valueOf(args.path("name").asText()),
                    args.path("id").asText(),
                )?.let(ProviderRpcJson.mapper::valueToTree)
                "loadLinks" -> {
                    val success = provider.loadLinks(
                        args.path("data").asText(),
                        args.path("isCasting").asBoolean(false),
                        { subtitle ->
                            emitEvent(
                                ProviderRpcJson.mapper.createObjectNode()
                                    .put("event", "subtitle")
                                    .set<JsonNode>("value", ProviderRpcJson.encodeSubtitle(subtitle)),
                            )
                        },
                        { link ->
                            emitEvent(
                                ProviderRpcJson.mapper.createObjectNode()
                                    .put("event", "link")
                                    .set<JsonNode>("value", ProviderRpcJson.encodeExtractorLink(link)),
                            )
                        },
                    )
                    ProviderRpcJson.mapper.createObjectNode().put("success", success)
                }
                else -> error("Unsupported provider operation")
            }
        }
    }

    private fun describePlugin(pluginFile: File): ObjectNode {
        val plugin = ExtensionLoader.getPlugin(pluginFile.absolutePath) as? Plugin
        val actions = pluginVideoActions(pluginFile)
        return ProviderRpcJson.mapper.createObjectNode().apply {
            put("hasOpenSettings", plugin?.openSettings != null)
            set<ArrayNode>(
                "videoClickActions",
                ProviderRpcJson.mapper.createArrayNode().also { output ->
                    actions.forEachIndexed { index, action ->
                        output.add(
                            ProviderRpcJson.mapper.createObjectNode()
                                .put("id", index)
                                .put("name", action.name)
                                .put("iconId", action.iconId)
                                .put("requiresAuthentication", action.requiresAuthentication),
                        )
                    }
                },
            )
        }
    }

    private fun pluginVideoActions(pluginFile: File): List<VideoClickAction> {
        val pluginPath = pluginFile.canonicalPath
        return synchronized(VideoClickActionHolder.allVideoClickActions) {
            VideoClickActionHolder.allVideoClickActions.filter { action ->
                action.sourcePlugin?.let { path -> runCatching { File(path).canonicalPath == pluginPath }.getOrDefault(false) } == true
            }
        }
    }

    private fun runRequestInterceptor(
        interceptor: Interceptor,
        initialRequest: Request,
        interceptorId: String,
        requestHost: (ObjectNode) -> JsonNode,
    ): JsonNode {
        var currentRequest = initialRequest
        var proceeded = false
        var proceededBodyAvailable = false
        var proceededResponse: Response? = null
        val chainProxy = Proxy.newProxyInstance(
            Interceptor.Chain::class.java.classLoader,
            arrayOf(Interceptor.Chain::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "request" -> currentRequest
                "proceed" -> {
                    check(!proceeded) { "Video interceptor called proceed more than once" }
                    proceeded = true
                    currentRequest = args!![0] as Request
                    val frame = ProviderRpcJson.mapper.createObjectNode()
                        .put("event", "hostRequest")
                        .put("requestId", UUID.randomUUID().toString())
                        .put("operation", "videoInterceptorProceed")
                        .put("interceptorId", interceptorId)
                    frame.set<JsonNode>("request", ProviderRpcJson.encodeRequest(currentRequest))
                    val hostResponse = requestHost(frame)
                    require(hostResponse.path("hostResponseId").asText() == frame.path("requestId").asText()) {
                        "Video interceptor host response did not match its request"
                    }
                    if (!hostResponse.path("ok").asBoolean(false)) {
                        throw PluginProviderRpcException(hostResponse.path("error").asText("Video interceptor host request failed").take(1200))
                    }
                    proceededBodyAvailable = hostResponse.path("bodyAvailable").asBoolean(false)
                    val headers = ProviderRpcJson.decodeHeaders(hostResponse.path("headers"))
                    val body = if (proceededBodyAvailable) {
                        val bodyBytes = runCatching {
                            java.util.Base64.getDecoder().decode(hostResponse.path("body").asText(""))
                        }.getOrElse { throw PluginProviderRpcException("Host returned malformed video response body") }
                        check(bodyBytes.size <= MAX_VIDEO_INTERCEPTOR_BODY_BYTES) { "Host video response body exceeded its transfer limit" }
                        bodyBytes.toResponseBody(headers["Content-Type"]?.toMediaTypeOrNull())
                    } else {
                        unavailableVideoResponseBody(
                            headers["Content-Type"]?.toMediaTypeOrNull(),
                            hostResponse.path("bodyLength").asLong(-1L),
                        )
                    }
                    Response.Builder()
                        .request(ProviderRpcJson.decodeRequest(hostResponse.path("request")))
                        .protocol(Protocol.valueOf(hostResponse.path("protocol").asText(Protocol.HTTP_1_1.name)))
                        .code(hostResponse.path("code").asInt(200))
                        .message(hostResponse.path("message").asText("OK"))
                        .headers(headers)
                        .body(body)
                        .build()
                        .also { proceededResponse = it }
                }
                "connection" -> null
                "call" -> okhttp3.OkHttpClient().newCall(currentRequest)
                "connectTimeoutMillis", "readTimeoutMillis", "writeTimeoutMillis" -> 30_000
                "withConnectTimeout", "withReadTimeout", "withWriteTimeout" -> proxy
                "toString" -> "RemoteVideoInterceptorChain"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as Interceptor.Chain

        val response = interceptor.intercept(chainProxy)
        val returnedBody = response.body
        val bodyChanged = !proceeded || returnedBody !== proceededResponse?.body
        val output = ProviderRpcJson.mapper.createObjectNode().apply {
            put("registered", true)
            put("proceeded", proceeded)
            put("protocol", response.protocol.name)
            put("code", response.code)
            put("message", response.message)
            set<JsonNode>("request", ProviderRpcJson.encodeRequest(response.request))
            set<JsonNode>("proceedRequest", ProviderRpcJson.encodeRequest(currentRequest))
            set<JsonNode>("headers", ProviderRpcJson.mapper.valueToTree(response.headers.toMultimap()))
            put("bodyChanged", bodyChanged)
            put("bodyAvailable", proceededBodyAvailable)
        }
        if (!proceeded || bodyChanged || proceededBodyAvailable) {
            val source = returnedBody.source()
            source.request(MAX_VIDEO_INTERCEPTOR_BODY_BYTES + 1L)
            val buffer: Buffer = source.buffer
            check(buffer.size <= MAX_VIDEO_INTERCEPTOR_BODY_BYTES) { "Video interceptor response body exceeds its transfer limit" }
            output.put("body", java.util.Base64.getEncoder().encodeToString(buffer.clone().readByteArray()))
        }
        response.close()
        return output
    }

    private fun unavailableVideoResponseBody(contentType: okhttp3.MediaType?, contentLength: Long): okhttp3.ResponseBody =
        object : okhttp3.ResponseBody() {
            private val rawBodySource = object : okio.Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (byteCount == 0L) return 0L
                    throw java.io.IOException("Video response body exceeds the plugin transfer limit")
                }

                override fun timeout(): okio.Timeout = okio.Timeout.NONE

                override fun close() = Unit
            }
            private val bodySource = rawBodySource.buffer()

            override fun contentType(): okhttp3.MediaType? = contentType

            override fun contentLength(): Long = contentLength

            override fun source(): okio.BufferedSource = bodySource
        }
}
