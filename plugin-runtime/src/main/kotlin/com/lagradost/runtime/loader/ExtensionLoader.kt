package com.lagradost.runtime.loader

import android.content.DesktopContextProvider
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.common.logging.AppLogger
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile

object ExtensionLoader {

    private const val TRANSFORM_POLICY_ID = "transform-policy-v4"
    private val DEX_ENTRY_PATTERN = Regex("^classes(?:([2-9][0-9]*))?\\.dex$")

    private val mapper = ObjectMapper().registerModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // Keep track of loaded plugins by absolute path
    val plugins: MutableMap<String, BasePlugin> = mutableMapOf()
    private val internalNamesByPluginPath = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Host lifecycle hook for resources owned by a plugin, such as process workers. */
    @Volatile
    var onPluginUnloaded: ((String) -> Unit)? = null

    /** Host lifecycle hook invoked after plugin registration completes. */
    @Volatile
    var onPluginLoaded: ((File) -> Unit)? = null

    /** Optional host hook that replaces dynamic plugin instances with a safe host-side handle. */
    @Volatile
    var isolatedPluginFactory: ((File, String) -> BasePlugin?)? = null

    // Map class loader to plugin name
    val classLoaders: MutableMap<ClassLoader, String> = java.util.concurrent.ConcurrentHashMap()

    // Map class loader to jar file
    val classLoaderToJar: MutableMap<ClassLoader, File> = java.util.concurrent.ConcurrentHashMap()

    // Map class loader to class names loaded from its jar
    val classLoaderToClassNames: MutableMap<ClassLoader, Set<String>> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Creates a classloader that searches all registered plugin classloaders before
     * delegating to [fallback]. Used to fix kotlin-reflect resolution failures when
     * Jackson deserializes plugin-defined inner classes across classloader boundaries.
     */
    fun createCompositeClassLoader(fallback: ClassLoader): ClassLoader {
        val pluginLoaders = classLoaders.keys.toList()
        return object : ClassLoader(fallback) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                for (loader in pluginLoaders) {
                    try {
                        return loader.loadClass(name)
                    } catch (_: ClassNotFoundException) {}
                }
                return super.loadClass(name, resolve)
            }
        }
    }

    fun getCallingPluginName(): String? = getCallingPluginClassLoader()?.let(classLoaders::get)

    private fun getCallingPluginClassLoader(): ClassLoader? {
        try {
            val walker = java.lang.StackWalker.getInstance(java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE)
            val loader = walker.walk { stream ->
                stream.map { it.declaringClass }
                    .filter { clazz ->
                        val loader = clazz.classLoader
                        loader != null && classLoaders.containsKey(loader)
                    }
                    .map { clazz -> clazz.classLoader }
                    .findFirst()
                    .orElse(null)
            }
            if (loader != null) return loader
        } catch (e: Throwable) {
            // Ignored, fallback below
        }

        val stackTrace = Thread.currentThread().stackTrace
        for (element in stackTrace) {
            val className = element.className
            if (className.startsWith("com.lagradost.") || className.startsWith("java.") || className.startsWith("kotlin.")) continue

            for ((loader, _) in classLoaders) {
                val classes = classLoaderToClassNames[loader]
                if (classes != null && classes.contains(className)) {
                    return loader
                }
            }
        }
        return null
    }

    // Native plugin interceptors
    var nativePluginInterceptor: ((String) -> BasePlugin?)? = null

    internal fun isTransformedCacheValid(sourcePluginJar: File, transformedJar: File): Boolean {
        val identityFile = File(transformedJar.path + ".identity")
        return transformedJar.isFile && identityFile.isFile &&
            runCatching { identityFile.readText() == transformedCacheIdentity(sourcePluginJar, transformedJar) }.getOrDefault(false)
    }

    /** Applies the current bytecode policy to a downloaded precompiled JVM sidecar and binds it to both artifacts. */
    fun preparePrecompiledJvmJar(sourcePluginJar: File, precompiledJvmJar: File) {
        require(sourcePluginJar.isFile) { "Source plugin archive does not exist" }
        require(precompiledJvmJar.isFile) { "Precompiled JVM plugin archive does not exist" }
        com.lagradost.runtime.security.PluginArchiveLimits.verify(precompiledJvmJar)
        PluginBytecodeTransformer.transform(precompiledJvmJar)
        File(precompiledJvmJar.path + ".identity").writeText(transformedCacheIdentity(sourcePluginJar, precompiledJvmJar))
    }

    private fun transformedCacheIdentity(sourcePluginJar: File, transformedJar: File): String =
        "$TRANSFORM_POLICY_ID:${sha256(sourcePluginJar)}:${sha256(transformedJar)}"

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun loadJar(
        jarFile: File,
        fallbackPluginClassName: String? = null,
        @Suppress("UNUSED_PARAMETER") forceBypassSecurity: Boolean = false,
    ): BasePlugin {
        if (!jarFile.exists()) {
            throw IllegalArgumentException("Jar file does not exist: ${jarFile.absolutePath}")
        }
        com.lagradost.runtime.security.PluginArchiveLimits.verify(jarFile)
        fun cacheMatches(file: File) = isTransformedCacheValid(jarFile, file)
        fun markCache(file: File) {
            File(file.path + ".identity").writeText(transformedCacheIdentity(jarFile, file))
        }

        var pluginClassName = fallbackPluginClassName
        var internalNameFromManifest: String? = null
        var nameFromManifest: String? = null
        var jarToLoad = jarFile

        ZipFile(jarFile).use { zip ->
            // Try to extract manifest to get actual class name
            val manifestEntry = zip.getEntry("manifest.json")
            if (manifestEntry != null) {
                zip.getInputStream(manifestEntry).use { input ->
                    val manifestData = mapper.readValue(input, Map::class.java)
                    val className = manifestData["pluginClassName"] as? String
                    if (className != null) {
                        pluginClassName = className
                    }
                    internalNameFromManifest = manifestData["internalName"] as? String
                    nameFromManifest = manifestData["name"] as? String
                }
            }

            // Check if archive already contains compiled JVM .class bytecode
            val hasJvmClasses = zip.entries().asSequence().any { it.name.endsWith(".class") }

            val hasDexFiles = zip.entries().asSequence().any { DEX_ENTRY_PATTERN.matches(it.name) }
            if (hasJvmClasses) {
                val secureJar = if (jarFile.name.endsWith("-secure.jar")) {
                    jarFile
                } else {
                    File(jarFile.parentFile, jarFile.nameWithoutExtension.substringBefore("-secure") + "-secure.jar")
                }
                val isCacheValid = secureJar == jarFile || (
                    secureJar.exists() && cacheMatches(secureJar) &&
                        (pluginClassName == null || checkJarHasClass(secureJar, pluginClassName!!))
                    )

                if (!isCacheValid) {
                    AppLogger.i("[PluginLoader] Securing Native JVM JAR: ${jarFile.name}...")
                    java.nio.file.Files.copy(jarFile.toPath(), secureJar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    PluginBytecodeTransformer.transform(secureJar)
                    markCache(secureJar)
                } else if (secureJar != jarFile) {
                    AppLogger.i("[PluginLoader] Using cached Secure JVM JAR: ${secureJar.name}")
                }
                jarToLoad = secureJar
            } else if (hasDexFiles) {
                val convertedJar = File(jarFile.parentFile, jarFile.nameWithoutExtension + "-jvm.jar")
                val isCacheValid = convertedJar.exists() && cacheMatches(convertedJar) &&
                    (pluginClassName == null || checkJarHasClass(convertedJar, pluginClassName!!))

                if (!isCacheValid) {
                    AppLogger.i("[PluginLoader] Transpiling Dalvik DEX -> JVM JAR for ${jarFile.name}...")
                    try {
                        DexToJvmTranslator.translateArchive(jarFile, convertedJar)
                        PluginBytecodeTransformer.transform(convertedJar)
                        markCache(convertedJar)
                    } catch (failure: Throwable) {
                        convertedJar.delete()
                        File(convertedJar.path + ".identity").delete()
                        throw failure
                    }
                } else {
                    AppLogger.i("[PluginLoader] Using cached JVM JAR: ${convertedJar.name}")
                }

                jarToLoad = convertedJar
            }
        }

        if (pluginClassName == null) {
            throw IllegalArgumentException("Could not determine pluginClassName from manifest.json and no fallback provided.")
        }

        val finalInternalName = internalNameFromManifest ?: nameFromManifest ?: pluginClassName?.split(".")?.lastOrNull() ?: jarFile.nameWithoutExtension.removeSuffix("-jvm")

        AppLogger.i("[PluginLoader] Initializing class $pluginClassName from ${jarToLoad.name}")

        AppLogger.i("Validating plugin archive ${jarToLoad.name}...")
        com.lagradost.runtime.security.PluginSecurityVerifier.verifyJar(jarToLoad, finalInternalName)

        val nativeIntercept = nativePluginInterceptor?.invoke(pluginClassName!!)
        val isolatedIntercept = if (nativeIntercept == null) isolatedPluginFactory?.invoke(jarFile, pluginClassName!!) else null
        var providersBeforePluginLoad: List<com.lagradost.cloudstream3.MainAPI> = emptyList()
        var extractorsBeforePluginLoad: List<com.lagradost.cloudstream3.utils.ExtractorApi> = emptyList()
        val pluginInstance: BasePlugin = if (nativeIntercept != null) {
            AppLogger.i("Intercepted plugin $pluginClassName! Injecting native JVM implementation.")
            nativeIntercept
        } else if (isolatedIntercept != null) {
            AppLogger.i("Using isolated host handle for plugin ${jarFile.name}; plugin code will be loaded in its worker process.")
            isolatedIntercept
        } else {
            providersBeforePluginLoad = synchronized(com.lagradost.cloudstream3.APIHolder.allProviders) {
                com.lagradost.cloudstream3.APIHolder.allProviders.toList()
            }
            extractorsBeforePluginLoad = synchronized(com.lagradost.cloudstream3.utils.extractorApis) {
                com.lagradost.cloudstream3.utils.extractorApis.toList()
            }
            val safeParentLoader = SafePluginClassLoader(this::class.java.classLoader, isTrusted = true)
            val classLoader = CompatPluginClassLoader(arrayOf(jarToLoad.toURI().toURL()), safeParentLoader)
            classLoaders[classLoader] = finalInternalName
            classLoaderToJar[classLoader] = jarFile
            val pluginClass = try {
                classLoader.loadClass(pluginClassName)
            } catch (failure: Throwable) {
                classLoaders.remove(classLoader)
                classLoaderToJar.remove(classLoader)
                throw failure
            }

            // MegaPlugin VerifiedRepo MixIn injection
            if (pluginClassName == "com.mega.MegaPlugin") {
                try {
                    val verifiedRepoClass = classLoader.loadClass("com.mega.MegaPlugin\$getRepositories\$VerifiedRepo")
                    com.lagradost.cloudstream3.mapper.addMixIn(verifiedRepoClass, VerifiedRepoMixIn::class.java)
                } catch (e: Exception) {
                    AppLogger.i("Failed to inject VerifiedRepo MixIn for MegaPlugin (it might not be loaded yet)")
                }
            }

            val instance = try {
                pluginClass.getDeclaredConstructor().newInstance() as BasePlugin
            } catch (failure: Throwable) {
                classLoaders.remove(classLoader)
                classLoaderToJar.remove(classLoader)
                throw failure
            }
            classLoaders[classLoader] = finalInternalName
            classLoaderToJar[classLoader] = jarFile

            val classNames = mutableSetOf<String>()
            try {
                ZipFile(jarToLoad).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.endsWith(".class")) {
                            val cName = entry.name.removeSuffix(".class").replace("/", ".")
                            classNames.add(cName)
                        }
                    }
                }
            } catch (t: Throwable) {
                // Ignore zip errors
            }
            classLoaderToClassNames[classLoader] = classNames

            // Synchronize any static Requests fields immediately
            synchronizePluginNetworkClients(classLoader, classNames)

            if (finalInternalName == "CineStream") {
                try {
                    val registryClass = classLoader.loadClass("com.megix.ProviderRegistry")
                    val instanceField = registryClass.getField("INSTANCE")
                    val registryInstance = instanceField.get(null)
                    val getBuiltInProvidersMethod = registryClass.getMethod("getBuiltInProviders")
                    val providers = getBuiltInProvidersMethod.invoke(registryInstance) as List<*>

                    for (provider in providers) {
                        val getKeyMethod = provider!!.javaClass.getMethod("getKey")
                        val key = getKeyMethod.invoke(provider) as String

                        com.lagradost.common.storage.PluginSettingsSchemaRegistry.register(
                            pluginPrefName = "CineStream_",
                            key = key,
                            type = "String",
                            defaultValue = "true",
                            isGlobal = false,
                        )
                    }
                    AppLogger.i("CineStream: Proactively registered ${providers.size} sub-providers in settings registry.")
                } catch (e: Exception) {
                    AppLogger.e("CineStream: Failed to proactively register sub-providers", e)
                }
            }

            instance
        }

        // Preference XML is inert metadata and can be parsed in the host without loading plugin code.
        scanAllXmlPreferences(jarToLoad, finalInternalName)

        pluginInstance.filename = jarFile.absolutePath
        // store plugin instance for later unloading
        plugins[jarFile.absolutePath] = pluginInstance
        internalNamesByPluginPath[jarFile.absolutePath] = finalInternalName
        internalNamesByPluginPath[jarFile.canonicalPath] = finalInternalName

        // Backfill sourcePlugin for any provider/extractor registered during constructor init
        // when pluginInstance.filename was not yet assigned
        try {
            if (isolatedIntercept != null || nativeIntercept != null) return pluginInstance
            synchronized(com.lagradost.cloudstream3.APIHolder.allProviders) {
                com.lagradost.cloudstream3.APIHolder.allProviders.forEach { provider ->
                    val existedBeforeLoad = providersBeforePluginLoad.any { it === provider }
                    if (!existedBeforeLoad && provider.sourcePlugin == null) {
                        provider.sourcePlugin = jarFile.absolutePath
                    }
                }
                // Only replace duplicate instances belonging to the exact same plugin file path (e.g. in-place update)
                val seenKeys = mutableSetOf<String>()
                val toKeep = mutableListOf<com.lagradost.cloudstream3.MainAPI>()
                for (provider in com.lagradost.cloudstream3.APIHolder.allProviders.reversed()) {
                    val uniqueKey = "${provider.name}::${provider.sourcePlugin ?: ""}"
                    if (provider.sourcePlugin != jarFile.absolutePath || seenKeys.add(uniqueKey)) {
                        toKeep.add(provider)
                    } else {
                        try {
                            com.lagradost.cloudstream3.APIHolder.removePluginMapping(provider)
                        } catch (ignored: Throwable) {}
                    }
                }
                com.lagradost.cloudstream3.APIHolder.allProviders.clear()
                com.lagradost.cloudstream3.APIHolder.allProviders.addAll(toKeep.reversed())
            }
            com.lagradost.cloudstream3.APIHolder.apis.forEach { provider ->
                val existedBeforeLoad = providersBeforePluginLoad.any { it === provider }
                if (!existedBeforeLoad && provider.sourcePlugin == null) {
                    provider.sourcePlugin = jarFile.absolutePath
                }
            }
            synchronized(com.lagradost.cloudstream3.utils.extractorApis) {
                com.lagradost.cloudstream3.utils.extractorApis.forEach { extractor ->
                    val existedBeforeLoad = extractorsBeforePluginLoad.any { it === extractor }
                    if (!existedBeforeLoad && extractor.sourcePlugin == null) {
                        extractor.sourcePlugin = jarFile.absolutePath
                    }
                }
                // Only replace duplicate extractors belonging to the exact same plugin file path
                val seenExtKeys = mutableSetOf<String>()
                val extsToKeep = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorApi>()
                for (ext in com.lagradost.cloudstream3.utils.extractorApis.reversed()) {
                    val uniqueKey = "${ext.name}::${ext.sourcePlugin ?: ""}"
                    if (ext.sourcePlugin != jarFile.absolutePath || seenExtKeys.add(uniqueKey)) {
                        extsToKeep.add(ext)
                    }
                }
                com.lagradost.cloudstream3.utils.extractorApis.clear()
                com.lagradost.cloudstream3.utils.extractorApis.addAll(extsToKeep.reversed())
            }
        } catch (t: Throwable) {
            AppLogger.i("Failed to backfill sourcePlugin or deduplicate for ${jarFile.name}: ${t.message}")
        }

        return pluginInstance
    }

    fun getPluginAliases(
        jarFile: File? = null,
        internalName: String? = null,
        pluginClassName: String? = null,
        manifestName: String? = null,
    ): Set<String> {
        val keys = mutableSetOf<String>()
        val repoDir = jarFile?.parentFile?.name?.lowercase()?.trim()

        fun addKey(k: String?) {
            if (k.isNullOrBlank()) return
            val clean = k.removeSuffix(".jar").removeSuffix(".cs3").removeSuffix("-jvm").removeSuffix("-secure").lowercase().trim()
            if (clean.isNotBlank()) {
                keys.add(clean)
                val stripped = clean.removeSuffix("provider").removeSuffix("plugin").removePrefix("com.")
                if (stripped.isNotBlank()) keys.add(stripped)
                val lastSegment = clean.substringAfterLast('.')
                if (lastSegment.isNotBlank()) keys.add(lastSegment)
                val lastStripped = lastSegment.removeSuffix("provider").removeSuffix("plugin")
                if (lastStripped.isNotBlank()) keys.add(lastStripped)

                if (repoDir != null && repoDir != "extensions") {
                    keys.add("$repoDir/$clean")
                    if (stripped.isNotBlank()) keys.add("$repoDir/$stripped")
                    if (lastSegment.isNotBlank()) keys.add("$repoDir/$lastSegment")
                    if (lastStripped.isNotBlank()) keys.add("$repoDir/$lastStripped")

                    val repoWithSpaces = repoDir.replace('_', ' ')
                    val repoClean = repoDir.replace(Regex("[^a-z0-9]"), "")
                    if (repoWithSpaces != repoDir) {
                        keys.add("$repoWithSpaces/$clean")
                        if (stripped.isNotBlank()) keys.add("$repoWithSpaces/$stripped")
                    }
                    if (repoClean.isNotBlank() && repoClean != repoDir) {
                        keys.add("$repoClean/$clean")
                        if (stripped.isNotBlank()) keys.add("$repoClean/$stripped")
                    }
                }
            }
        }

        jarFile?.nameWithoutExtension?.let { addKey(it) }
        internalName?.let { addKey(it) }
        manifestName?.let { addKey(it) }
        pluginClassName?.let {
            addKey(it)
            addKey(it.substringAfterLast('.'))
            addKey(it.substringBeforeLast('.'))
        }

        jarFile?.let {
            keys.add(it.absolutePath.lowercase().replace('\\', '/'))
            val relPath = "${repoDir ?: ""}/${it.nameWithoutExtension.removeSuffix("-jvm").removeSuffix("-secure")}".lowercase().trim('/')
            if (relPath.isNotBlank()) keys.add(relPath)
        }

        return keys
    }

    /** Compatibility API retained for callers; plugin loading no longer has trust tiers. */
    @Suppress("UNUSED_PARAMETER")
    fun isTrusted(
        jarFile: File,
        internalName: String? = null,
        pluginClassName: String? = null,
        manifestName: String? = null,
    ): Boolean = true

    /** Compatibility no-op: there are no per-plugin runtime permission tiers. */
    @Suppress("UNUSED_PARAMETER")
    fun addTrusted(
        jarFile: File,
        internalName: String? = null,
        pluginClassName: String? = null,
        manifestName: String? = null,
    ) = Unit

    /** Compatibility no-op: uninstalling a plugin does not change its access policy. */
    @Suppress("UNUSED_PARAMETER")
    fun removeTrusted(
        jarFile: File? = null,
        internalName: String? = null,
        pluginClassName: String? = null,
        manifestName: String? = null,
    ) = Unit

    fun loadAndInit(
        jarFile: File,
        fallbackPluginClassName: String? = null,
        @Suppress("UNUSED_PARAMETER") forceBypassSecurity: Boolean = false,
    ): BasePlugin {
        val pluginInstance = loadJar(jarFile, fallbackPluginClassName)
        initializePlugin(pluginInstance)
        onPluginLoaded?.invoke(jarFile)
        return pluginInstance
    }

    fun initializePlugin(pluginInstance: BasePlugin) {
        if (pluginInstance is IsolatedPluginHandle) return
        if (pluginInstance is Plugin) {
            pluginInstance.load(DesktopContextProvider.context)
        } else {
            pluginInstance.load()
        }
        val loader = pluginInstance.javaClass.classLoader
        if (loader != null) {
            val names = classLoaderToClassNames[loader] ?: emptySet()
            synchronizePluginNetworkClients(loader, names)
        }
    }

    fun unloadPlugin(absolutePath: String) {
        runCatching { onPluginUnloaded?.invoke(absolutePath) }
        val normPath = File(absolutePath).absolutePath
        val canonicalPath = try {
            File(absolutePath).canonicalPath
        } catch (_: Throwable) {
            normPath
        }
        val plugin = plugins[normPath] ?: plugins[absolutePath] ?: plugins[canonicalPath]

        if (plugin != null && plugin !is IsolatedPluginHandle) {
            try {
                plugin.beforeUnload()
            } catch (t: Throwable) {
                AppLogger.i("Failed to run beforeUnload for $absolutePath: ${t.message}")
            }
        }

        val pathsToRemove = setOfNotNull(normPath, absolutePath, canonicalPath, plugin?.filename)

        // Close the ClassLoader to release file locks on Windows
        val classLoader = plugin?.javaClass?.classLoader
            ?: classLoaderToJar.entries.firstOrNull { pathsToRemove.contains(it.value.absolutePath) }?.key
        if (classLoader != null) {
            classLoaders.remove(classLoader) // Fix Metaspace Leak!
            classLoaderToJar.remove(classLoader)
            classLoaderToClassNames.remove(classLoader)
            if (classLoader is URLClassLoader) {
                try {
                    classLoader.close()
                } catch (t: Throwable) {
                    AppLogger.i("Failed to close URLClassLoader for $absolutePath: ${t.message}")
                }
            }
        }

        // Remove providers and mappings registered by this plugin
        try {
            com.lagradost.cloudstream3.APIHolder.apis.filter { pathsToRemove.contains(it.sourcePlugin) }.forEach {
                com.lagradost.cloudstream3.APIHolder.removePluginMapping(it)
            }
            synchronized(com.lagradost.cloudstream3.APIHolder.allProviders) {
                com.lagradost.cloudstream3.APIHolder.allProviders.removeIf { pathsToRemove.contains(it.sourcePlugin) }
            }
        } catch (t: Throwable) {
            AppLogger.i("Failed to remove plugin mappings for $absolutePath: ${t.message}")
        }

        try {
            synchronized(com.lagradost.cloudstream3.utils.extractorApis) {
                com.lagradost.cloudstream3.utils.extractorApis.removeIf { pathsToRemove.contains(it.sourcePlugin) }
            }
        } catch (t: Throwable) {
            // ignore
        }

        try {
            com.lagradost.cloudstream3.actions.VideoClickActionHolder.allVideoClickActions.removeIf { pathsToRemove.contains(it.sourcePlugin) }
        } catch (t: Throwable) {
            // ignore
        }

        // Remove from tracked plugins across all possible path keys
        pathsToRemove.forEach {
            plugins.remove(it)
            internalNamesByPluginPath.remove(it)
        }
    }

    fun unloadAllPlugins() {
        val allPaths = plugins.keys.toList()
        for (path in allPaths) {
            try {
                unloadPlugin(path)
            } catch (t: Throwable) {
                AppLogger.e("Failed to unload plugin $path: ${t.message}")
            }
        }
        for ((loader, _) in classLoaders.toList()) {
            if (loader is java.io.Closeable) {
                try {
                    loader.close()
                } catch (_: Throwable) {}
            }
        }
        classLoaders.clear()
        classLoaderToJar.clear()
        classLoaderToClassNames.clear()
        plugins.clear()
    }

    fun isPluginLoaded(absolutePath: String): Boolean = plugins.containsKey(absolutePath)

    fun getPlugin(absolutePath: String): BasePlugin? = plugins[absolutePath]

    /** Resolves the manifest identity used to scope settings and other plugin-owned host state. */
    @JvmStatic
    fun getPluginInternalName(absolutePath: String): String? {
        internalNamesByPluginPath[absolutePath]?.let { return it }
        val normalized = runCatching { File(absolutePath).canonicalPath }.getOrElse { File(absolutePath).absolutePath }
        return internalNamesByPluginPath[normalized]
    }

    /**
     * Loads any extension jars on disk that are not already in memory (e.g. after sync/install).
     */
    fun rescanAndLoadNewPlugins(extensionsDir: File): Int {
        if (!extensionsDir.exists()) return 0

        var loaded = 0
        extensionsDir.walkTopDown()
            .filter(PluginArchiveFilter::isLoadablePluginArchive)
            .sortedBy { it.lastModified() }
            .forEach { jar ->
                if (!isPluginLoaded(jar.absolutePath)) {
                    try {
                        loadAndInit(jar)
                        loaded++
                        AppLogger.i("Rescan: loaded ${jar.name}")
                    } catch (e: Throwable) {
                        AppLogger.e("Rescan: failed ${jar.name}", e)
                    }
                }
            }
        return loaded
    }

    @JvmStatic
    fun parsePluginPreferences(fragment: Any, resId: Int) {
        try {
            val classLoader = fragment.javaClass.classLoader
            val jarFile = classLoaderToJar[classLoader] ?: return
            val pluginPrefName = classLoaders[classLoader] ?: return

            scanAllXmlPreferences(jarFile, pluginPrefName)
        } catch (e: Exception) {
            AppLogger.e("Failed to parse plugin preferences", e)
        }
    }

    @JvmStatic
    fun scanAllXmlPreferences(jarFile: java.io.File, pluginPrefName: String) {
        val finalPrefName = pluginPrefName + "_"
        try {
            val jvmJar = java.io.File(jarFile.parentFile, jarFile.nameWithoutExtension.removeSuffix("-jvm") + "-jvm.jar")
            val scanTarget = if (jvmJar.exists()) jvmJar else jarFile
            com.lagradost.runtime.loader.utils.PluginSettingsScanner.scanJarForSettings(pluginPrefName, scanTarget)
            AppLogger.i("Scanning all XML preferences for $finalPrefName from ${scanTarget.absolutePath}")

            var apkFileLazy: net.dongliu.apk.parser.ApkFile? = null

            java.util.zip.ZipFile(jarFile).use { zip ->
                val xmlEntries = zip.entries().toList().filter { it.name.startsWith("res/xml/") && it.name.endsWith(".xml") }
                for (entry in xmlEntries) {
                    val path = entry.name
                    AppLogger.i("Found XML path: $path")

                    try {
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        var xmlString = String(bytes, Charsets.UTF_8)

                        // Check if it's likely a binary XML (binary XML typically doesn't start with human-readable '<')
                        if (!xmlString.trimStart().startsWith("<")) {
                            if (apkFileLazy == null) {
                                try {
                                    apkFileLazy = net.dongliu.apk.parser.ApkFile(jarFile)
                                } catch (e: Exception) {
                                    AppLogger.i("Failed to init ApkFile for binary XML decoding: ${e.message}")
                                }
                            }
                            if (apkFileLazy != null) {
                                xmlString = apkFileLazy!!.transBinaryXml(path) ?: ""
                            }
                        }

                        if (xmlString.isNullOrEmpty()) continue

                        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                        val builder = factory.newDocumentBuilder()
                        val document = builder.parse(org.xml.sax.InputSource(java.io.StringReader(xmlString)))

                        val nodeList = document.getElementsByTagName("*")
                        for (i in 0 until nodeList.length) {
                            val node = nodeList.item(i)
                            if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                                val element = node as org.w3c.dom.Element
                                val key = element.getAttribute("android:key")
                                if (key.isNotEmpty()) {
                                    // A plain Preference is a display/action row, not a persisted
                                    // value control. Do not invent a text field for unknown XML tags.
                                    if (element.tagName !in setOf(
                                            "CheckBoxPreference",
                                            "SwitchPreference",
                                            "SwitchPreferenceCompat",
                                            "ListPreference",
                                            "DropDownPreference",
                                            "MultiSelectListPreference",
                                            "EditTextPreference",
                                            "SeekBarPreference",
                                        )
                                    ) {
                                        continue
                                    }

                                    val defValueStr = element.getAttribute("android:defaultValue")
                                    val title = element.getAttribute("android:title").takeIf { it.isNotBlank() && !it.startsWith("@") }
                                    val summary = element.getAttribute("android:summary").takeIf { it.isNotBlank() && !it.startsWith("@") }
                                    val order = element.getAttribute("android:order").toIntOrNull() ?: 0
                                    var category: String? = null
                                    var parent = element.parentNode
                                    while (parent is org.w3c.dom.Element) {
                                        if (parent.tagName == "PreferenceCategory") {
                                            category = parent.getAttribute("android:title")
                                                .takeIf { it.isNotBlank() && !it.startsWith("@") }
                                            break
                                        }
                                        parent = parent.parentNode
                                    }
                                    var type = "String"
                                    var defValue: Any = defValueStr
                                    var optionsMap: Map<String, String>? = null

                                    when (element.tagName) {
                                        "CheckBoxPreference", "SwitchPreference", "SwitchPreferenceCompat" -> {
                                            type = "Boolean"
                                            defValue = defValueStr.equals("true", ignoreCase = true)
                                        }
                                        "ListPreference", "DropDownPreference" -> {
                                            type = "String"
                                            val entriesStr = element.getAttribute("android:entries")
                                            val valuesStr = element.getAttribute("android:entryValues")
                                            if (entriesStr.isNotBlank() && valuesStr.isNotBlank() && !entriesStr.startsWith("@") && !valuesStr.startsWith("@")) {
                                                val entries = entriesStr.split("|", ",").map { it.trim() }
                                                val values = valuesStr.split("|", ",").map { it.trim() }
                                                if (entries.size == values.size && entries.isNotEmpty()) {
                                                    optionsMap = entries.zip(values).toMap()
                                                }
                                            }
                                        }
                                        "MultiSelectListPreference" -> {
                                            type = "StringSet"
                                            val entriesStr = element.getAttribute("android:entries")
                                            val valuesStr = element.getAttribute("android:entryValues")
                                            if (entriesStr.isNotBlank() && valuesStr.isNotBlank() && !entriesStr.startsWith("@") && !valuesStr.startsWith("@")) {
                                                val entries = entriesStr.split("|", ",").map { it.trim() }
                                                val values = valuesStr.split("|", ",").map { it.trim() }
                                                if (entries.size == values.size && entries.isNotEmpty()) {
                                                    optionsMap = entries.zip(values).toMap()
                                                }
                                            }
                                            defValue = if (defValueStr.isBlank() || defValueStr.startsWith("@")) {
                                                emptySet<String>()
                                            } else {
                                                defValueStr.split("|", ",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                                            }
                                        }
                                        "EditTextPreference" -> {
                                            type = "String"
                                        }
                                        "SeekBarPreference" -> {
                                            type = "Int"
                                            defValue = defValueStr.toIntOrNull() ?: 0
                                        }
                                    }
                                    com.lagradost.common.storage.PluginSettingsSchemaRegistry.register(
                                        finalPrefName,
                                        key,
                                        type,
                                        defValue,
                                        false,
                                        optionsMap,
                                        title,
                                        summary,
                                        category,
                                        order,
                                        element.tagName,
                                    )
                                    AppLogger.i("Registered XML plugin setting: $finalPrefName -> $key ($type = $defValue)")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.e("Failed to parse XML path $path", e)
                    }
                }
            }
            apkFileLazy?.close()
        } catch (e: Exception) {
            AppLogger.e("Failed to parse plugin preferences", e)
        }
    }

    fun synchronizePluginNetworkClients(
        classLoader: ClassLoader,
        classNames: Set<String>,
    ) {
        val globalBase = app.baseClient

        fun syncRequests(requests: Any?, source: String) {
            if (requests == null) return
            try {
                if (requests is com.lagradost.nicehttp.Requests) {
                    val hasCfKiller = requests.baseClient.interceptors.any {
                        it.javaClass.name.contains("CloudflareKiller")
                    }
                    if (!hasCfKiller) {
                        requests.baseClient = globalBase
                        com.lagradost.runtime.loader.stubs.RequestsStub.syncedClients.add(requests)
                        AppLogger.d("[PluginLoader] Synchronized Requests instance ($source) to global baseClient.")
                    }
                }
            } catch (t: Throwable) {
                AppLogger.w("[PluginLoader] Failed to sync Requests instance ($source): ${t.message}")
            }
        }

        // 1. Sweep all classes in plugin JAR for static Requests fields
        for (className in classNames) {
            try {
                val clazz = Class.forName(className, true, classLoader)
                for (field in clazz.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                        com.lagradost.nicehttp.Requests::class.java.isAssignableFrom(field.type)
                    ) {
                        field.isAccessible = true
                        val req = field.get(null)
                        syncRequests(req, "static field ${clazz.name}.${field.name}")
                    }
                }
            } catch (_: Throwable) {
                // Ignore classes that cannot be initialized or reflection errors
            }
        }

        // 2. Sweep all registered providers for instance Requests fields
        try {
            synchronized(com.lagradost.cloudstream3.APIHolder.allProviders) {
                for (provider in com.lagradost.cloudstream3.APIHolder.allProviders) {
                    var currentClass: Class<*>? = provider.javaClass
                    while (currentClass != null && currentClass != Any::class.java) {
                        for (field in currentClass.declaredFields) {
                            if (!java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                                com.lagradost.nicehttp.Requests::class.java.isAssignableFrom(field.type)
                            ) {
                                field.isAccessible = true
                                val req = field.get(provider)
                                syncRequests(req, "provider field ${provider.name}.${field.name}")
                            }
                        }
                        currentClass = currentClass.superclass
                    }
                }
            }
        } catch (_: Throwable) {}

        // 3. Sweep all registered extractors for instance Requests fields
        try {
            synchronized(com.lagradost.cloudstream3.utils.extractorApis) {
                for (extractor in com.lagradost.cloudstream3.utils.extractorApis) {
                    var currentClass: Class<*>? = extractor.javaClass
                    while (currentClass != null && currentClass != Any::class.java) {
                        for (field in currentClass.declaredFields) {
                            if (!java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                                com.lagradost.nicehttp.Requests::class.java.isAssignableFrom(field.type)
                            ) {
                                field.isAccessible = true
                                val req = field.get(extractor)
                                syncRequests(req, "extractor field ${extractor.name}.${field.name}")
                            }
                        }
                        currentClass = currentClass.superclass
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun checkJarHasClass(jar: File, className: String): Boolean {
        return try {
            val entryPath = className.replace('.', '/') + ".class"
            ZipFile(jar).use { zip ->
                zip.getEntry(entryPath) != null
            }
        } catch (e: Exception) {
            false
        }
    }
}

/** Marker for inert host handles whose real plugin instance lives in a supervised worker. */
interface IsolatedPluginHandle

abstract class VerifiedRepoMixIn {
    @com.fasterxml.jackson.annotation.JsonCreator
    constructor(
        @com.fasterxml.jackson.annotation.JsonProperty("url") url: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("verified") verified: Boolean?,
    )
}
