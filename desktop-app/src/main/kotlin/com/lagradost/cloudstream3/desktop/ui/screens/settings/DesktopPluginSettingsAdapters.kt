package com.lagradost.cloudstream3.desktop.ui.screens.settings

import com.lagradost.cloudstream3.desktop.network.SystemBrowserCdpBypass
import com.lagradost.cloudstream3.desktop.pluginworker.PluginProviderWorkerRegistry
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.ui.components.AppToastManager
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.PluginSettingAction
import com.lagradost.common.storage.PluginSettingsSchemaRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

data class DesktopPluginSettingsContext(
    val internalName: String,
    val displayName: String,
    val prefName: String,
    val jarFile: File,
)

/**
 * Desktop translations for plugin-specific Android settings screens that cannot be hosted by
 * the desktop JVM. Adapters register only controls whose keys and meaning are known.
 */
interface DesktopPluginSettingsAdapter {
    fun supports(context: DesktopPluginSettingsContext): Boolean

    /** Register the plugin's desktop-renderable settings and return any coverage note. */
    fun register(context: DesktopPluginSettingsContext): String?
}

object DesktopPluginSettingsAdapters {
    private val adapters = CopyOnWriteArrayList<DesktopPluginSettingsAdapter>().apply {
        add(StreamPlaySettingsAdapter)
    }

    /** Register another native adapter from the desktop app's plugin integration layer. */
    fun register(adapter: DesktopPluginSettingsAdapter) {
        if (adapters.none { it === adapter }) adapters += adapter
    }

    fun hasAdapter(context: DesktopPluginSettingsContext): Boolean = adapters.any { it.supports(context) }

    fun prepare(context: DesktopPluginSettingsContext): String? =
        adapters.firstOrNull { it.supports(context) }?.register(context)
}

/** Native Compose schema for the verified, value-based part of StreamPlay's settings. */
private object StreamPlaySettingsAdapter : DesktopPluginSettingsAdapter {
    override fun supports(context: DesktopPluginSettingsContext): Boolean {
        val names = listOf(context.internalName, context.displayName, context.jarFile.nameWithoutExtension)
            .map { it.removeSuffix("-jvm").removeSuffix(".jar") }
        return names.any { it.equals("StreamPlay", ignoreCase = true) }
    }

    override fun register(context: DesktopPluginSettingsContext): String {
        val prefName = context.prefName.let { if (it.endsWith("_")) it else "${it}_" }
        val sourceDiscovery = sourcesFor(context)
        val sources = sourceDiscovery.sources
        val registry = PluginSettingsSchemaRegistry

        registry.register(
            pluginPrefName = prefName,
            key = "provider_concurrency",
            type = "Int",
            defaultValue = 20,
            title = "Provider Simultaneous Connections",
            summary = "StreamPlay accepts a value from 8 to 50. Changes take effect after the plugin reloads.",
            category = "Performance",
            controlType = "NumberInput",
            minimumNumber = 8.0,
            maximumNumber = 50.0,
        )

        val duplicateNames = sources.groupingBy { it.name }.eachCount()
        val sourceOptions = sources.associate { source ->
            val label = if ((duplicateNames[source.name] ?: 0) > 1) "${source.name} (${source.id})" else source.name
            label to source.id
        }
        registry.register(
            pluginPrefName = prefName,
            key = "disabled_providers",
            type = "StringSet",
            defaultValue = emptySet<String>(),
            options = sourceOptions,
            title = "Enable / Disable Sources",
            summary = if (sources.isEmpty()) {
                "Source choices could not be loaded; see the note above for details."
            } else {
                "Enable or disable the scrapers StreamPlay uses to find stream links. Changes apply after StreamPlay reloads."
            },
            category = "Sources",
            controlType = "DisabledProviderSelection",
        )

        registry.register(
            pluginPrefName = prefName,
            key = "token",
            type = "Action",
            defaultValue = "",
            title = "Febbox Login",
            summary = "Connect your Febbox account to access cloud files.",
            category = "Integrations",
            controlType = "FebboxLogin",
            action = PluginSettingAction { FebboxLoginAction.start(prefName) },
        )
        registry.register(
            pluginPrefName = prefName,
            key = "wyzie_key",
            type = "String",
            defaultValue = "",
            title = "Wyzie Key",
            summary = "Subtitle provider access key.",
            category = "Integrations",
        )
        registry.register(
            pluginPrefName = prefName,
            key = "tmdb_language_code",
            type = "String",
            defaultValue = "en-US",
            title = "TMDB Language Code",
            summary = "Language code for titles and metadata (for example, en-US).",
            category = "Localization",
        )

        val uncoveredWorkflows = "StreamPlay's source tests, saved profiles, extension toggles, and Stremio add-on managers use custom Android screens and are not exposed here."
        return if (sources.isEmpty()) "$uncoveredWorkflows ${sourceDiscovery.error ?: "No internal sources were reported."}" else uncoveredWorkflows
    }

    private data class StreamPlaySource(val id: String, val name: String)
    private data class SourceDiscovery(val sources: List<StreamPlaySource>, val error: String? = null)

    /** Read the plugin's actual ProvidersList entries. Their IDs are what disabled_providers stores. */
    private fun sourcesFor(context: DesktopPluginSettingsContext): SourceDiscovery {
        return try {
            val sources = PluginProviderWorkerRegistry.describeStreamPlaySources(context.jarFile)
                .map { StreamPlaySource(it.id, it.name) }
            SourceDiscovery(sources)
        } catch (error: Throwable) {
            AppLogger.w("Could not read StreamPlay's internal source list from its plugin worker: ${error.message}")
            SourceDiscovery(emptyList(), "Could not read StreamPlay's internal source list: ${error.message ?: error.javaClass.simpleName}")
        }
    }
}

/** Runs the desktop equivalent of StreamPlay's Febbox WebView login and stores only its ui cookie. */
private object FebboxLoginAction {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loginInProgress = AtomicBoolean(false)

    fun start(pluginPrefName: String): Boolean {
        if (!loginInProgress.compareAndSet(false, true)) {
            AppToastManager.showInfo("Febbox login is already open.")
            return false
        }

        AppToastManager.showInfo("Febbox login opened in a temporary browser window. Finish sign-in there.")
        scope.launch {
            try {
                val token = withContext(Dispatchers.IO) {
                    SystemBrowserCdpBypass.launchFebboxLogin()
                }
                if (token.isNullOrBlank()) {
                    AppToastManager.showInfo("Febbox login was cancelled or did not complete.")
                    return@launch
                }

                val normalizedPrefName = pluginPrefName.let { if (it.endsWith("_")) it else "${it}_" }
                DesktopDataStore.setKey("${normalizedPrefName}token", token)
                DesktopRepositoryManager.incrementSyncGeneration()
                AppToastManager.showSuccess("Febbox connected. Close StreamPlay settings to reload the plugin.")
            } catch (error: Throwable) {
                com.lagradost.common.logging.AppLogger.e("Febbox sign-in failed", error)
                AppToastManager.showError(error.message ?: "Could not complete Febbox login.")
            } finally {
                loginInProgress.set(false)
            }
        }
        return true
    }
}
