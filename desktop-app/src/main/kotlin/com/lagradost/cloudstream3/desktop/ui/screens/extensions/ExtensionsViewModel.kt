package com.lagradost.cloudstream3.desktop.ui.screens.extensions

import com.lagradost.cloudstream3.desktop.di.AppContainerHolder
import com.lagradost.cloudstream3.desktop.domain.plugins.interactor.*
import com.lagradost.cloudstream3.desktop.domain.plugins.repository.PluginRepository
import com.lagradost.cloudstream3.desktop.repo.SitePlugin
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.extensions.contract.ExtensionsUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.extensions.contract.ExtensionsUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.extensions.contract.ExtensionsUiState
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.runtime.loader.ExtensionLoader
import com.lagradost.runtime.loader.PluginArchiveFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LocalPlugin(
    val file: File,
    val name: String,
    val internalName: String,
    val version: Int,
    val iconUrl: String?,
    val repoName: String,
    val language: String?,
    val tvTypes: List<String>?,
    val description: String? = null,
    val fileSize: Long = 0L,
)

/** Picks the loader's active copy, falling back to the same stable order used by loader rescan. */
internal fun selectInstalledPluginArchive(candidates: List<File>, loadedPath: File?): File? {
    val ordered = candidates.sortedWith(
        compareByDescending<File> { it.lastModified() }.thenBy { archiveCanonicalPath(it) },
    )
    val loadedCanonicalPath = loadedPath?.let(::archiveCanonicalPath)
    return ordered.firstOrNull { loadedCanonicalPath != null && archiveCanonicalPath(it) == loadedCanonicalPath }
        ?: ordered.firstOrNull()
}

private fun archiveCanonicalPath(file: File): String =
    runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

internal fun repositoryForPluginStorageFolder(
    repositories: List<RepositoryData>,
    folderName: String,
): RepositoryData? = repositories.filter {
    com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.safeDirectoryName(it.storageName)
        .equals(folderName, ignoreCase = true)
}.singleOrNull()

private data class InstalledArchiveCandidate(
    val file: File,
    val manifest: Map<String, Any>?,
    val name: String,
    val internalName: String,
)

internal data class InstalledPluginArchiveIdentity(val file: File, val internalName: String)

internal data class ProviderPreferenceIdentity(val name: String, val sourcePluginPath: String?)

internal fun activeProviderKeysAfterPluginUninstall(
    activeKeys: List<String>,
    targetSourcePaths: Set<String>,
    providers: List<ProviderPreferenceIdentity>,
): List<String> {
    val targetProviders = providers.filter { provider ->
        !provider.sourcePluginPath.isNullOrBlank() && provider.sourcePluginPath in targetSourcePaths
    }
    if (targetProviders.isEmpty()) return activeKeys

    val targetIdentities = targetProviders.toSet()
    val otherProviders = providers.filterNot { it in targetIdentities }
    val targetNames = targetProviders.map { it.name }.toSet()
    val targetFolders = targetSourcePaths.mapNotNull { path -> File(path).parentFile?.name }.toSet()
    val otherOwnedKeys = otherProviders.map(::providerPreferenceKey).toSet()
    val qualifiedTargetKeys = targetNames.flatMap { name -> targetFolders.map { folder -> "$folder::$name" } }
        .filterNot { it in otherOwnedKeys }
        .toSet()
    // Bare names are legacy keys. Keep one when another installed provider owns the
    // same exact name; qualified matches are never inferred from substrings.
    val unambiguousLegacyKeys = targetNames.filter { name -> otherProviders.none { it.name == name } }.toSet()
    val keysToRemove = qualifiedTargetKeys + unambiguousLegacyKeys
    return activeKeys.filterNot { it in keysToRemove }
}

private fun providerPreferenceKey(provider: ProviderPreferenceIdentity): String {
    val folder = provider.sourcePluginPath?.takeIf { it.isNotBlank() }?.let { File(it).parentFile?.name }
    return if (folder.isNullOrBlank()) provider.name else "$folder::${provider.name}"
}

internal fun pluginArtifactsToRemoveByIdentity(
    archives: List<InstalledPluginArchiveIdentity>,
    internalNames: Set<String>,
): Map<String, List<File>> {
    val targets = internalNames.map {
        com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.installedPluginIdentity(it)
    }.toSet()
    val otherIdentityArtifactPaths = archives.asSequence()
        .filter {
            com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.installedPluginIdentity(it.internalName) !in targets
        }
        .flatMap { archive ->
            com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.pluginArtifactFiles(archive.file).asSequence()
        }
        .map(::archiveCanonicalPath)
        .toSet()

    return archives
        .groupBy {
            com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.installedPluginIdentity(it.internalName)
        }
        .filterKeys { it in targets }
        .mapValues { (_, identityArchives) ->
            val sourceArchivePaths = identityArchives.map { archiveCanonicalPath(it.file) }.toSet()
            identityArchives.asSequence()
                .flatMap { archive ->
                    com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.pluginArtifactFiles(archive.file).asSequence()
                }
                .filter { artifact ->
                    val path = archiveCanonicalPath(artifact)
                    path in sourceArchivePaths || path !in otherIdentityArtifactPaths
                }
                .distinctBy(::archiveCanonicalPath)
                .toList()
        }
}

class ExtensionsViewModel(
    private val pluginRepo: PluginRepository = AppContainerHolder.container.pluginRepository,
    private val getPluginRepositories: GetPluginRepositories = GetPluginRepositories(pluginRepo),
    private val addPluginRepository: AddPluginRepository = AddPluginRepository(pluginRepo),
    private val removePluginRepository: RemovePluginRepository = RemovePluginRepository(pluginRepo),
    private val getAvailablePlugins: GetAvailablePlugins = GetAvailablePlugins(pluginRepo),
    private val installPluginUseCase: InstallPlugin = InstallPlugin(pluginRepo),
    private val syncPluginRepositories: SyncPluginRepositories = SyncPluginRepositories(pluginRepo),
    private val getPluginIconUseCase: GetPluginIcon = GetPluginIcon(pluginRepo),
) : BaseMviViewModel<ExtensionsUiState, ExtensionsUiEvent, ExtensionsUiEffect>(
    initialState = ExtensionsUiState(),
) {
    init {
        viewModelScope.launch {
            getPluginRepositories.subscribe().collect { repos ->
                updateState { copy(savedRepositories = repos) }
            }
        }
        viewModelScope.launch {
            getPluginIconUseCase.subscribeIcons().collect { icons ->
                updateState { copy(remotePluginIcons = icons) }
            }
        }
        viewModelScope.launch {
            pluginRepo.syncGeneration.collect { gen ->
                val allPlugins = getAvailablePlugins.get()
                updateState { copy(syncGeneration = gen, plugins = allPlugins) }
            }
        }
        // Immediately populate both the Catalog and Installed tabs from local cache (0ms instant render)
        viewModelScope.launch(Dispatchers.IO) {
            loadPluginsFromManager()
            refreshInstalled()
        }
        viewModelScope.launch(Dispatchers.IO) {
            com.lagradost.common.storage.DesktopDataStore.pluginUpdatesFlow.collect {
                val history = com.lagradost.common.storage.DesktopDataStore.getUpdatesHistory()
                updateState { copy(updatesHistory = history) }
            }
        }
    }

    override fun handleEvent(event: ExtensionsUiEvent) {
        when (event) {
            is ExtensionsUiEvent.OnFetchPlugins -> fetchPlugins()
            is ExtensionsUiEvent.OnLoadPluginsFromManager -> loadPluginsFromManager()
            is ExtensionsUiEvent.OnRefreshInstalled -> refreshInstalled()
            is ExtensionsUiEvent.OnInspectRepository -> inspectRepository(event.repoName)
            is ExtensionsUiEvent.OnInstallPlugin -> installPlugin(event.repoName, event.plugin)
            is ExtensionsUiEvent.OnUninstallPlugins -> uninstallPlugins(event.plugins)
            is ExtensionsUiEvent.OnUninstallByInternalName -> uninstallByInternalName(event.internalName)
            is ExtensionsUiEvent.OnUninstallPlugin -> uninstallByInternalName(event.internalName)
            is ExtensionsUiEvent.OnLoadLocalPlugin -> loadLocalPlugin(event.file)
            is ExtensionsUiEvent.OnRemoveRepository -> removeRepository(event.url)
            is ExtensionsUiEvent.OnAddRepositoryFromInput -> addRepositoryFromInput(event.input)
            is ExtensionsUiEvent.OnSyncAllRepos -> syncAllRepos()
            is ExtensionsUiEvent.OnClearUpdateHistory -> clearUpdateHistory()
            is ExtensionsUiEvent.OnReloadPluginAfterSettings -> reloadPluginAfterSettings(event.file, event.pluginName)
            is ExtensionsUiEvent.OnAddStremioAddon -> addStremioAddon(event.url, event.onResult)
            is ExtensionsUiEvent.OnRemoveStremioAddon -> removeStremioAddon(event.manifestUrl)
            is ExtensionsUiEvent.OnSetStremioAddonEnabled -> setStremioAddonEnabled(event.manifestUrl, event.enabled)
            is ExtensionsUiEvent.OnRefreshStremioAddon -> refreshStremioAddon(event.manifestUrl)
            is ExtensionsUiEvent.OnMoveStremioAddon -> moveStremioAddon(event.fromIndex, event.toIndex)
        }
    }

    private fun addRepositoryFromInput(input: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val addedRepos = addPluginRepository.await(input)
                if (!addedRepos.isNullOrEmpty()) {
                    val repoNames = addedRepos.take(2).joinToString { it.name } + if (addedRepos.size > 2) " and ${addedRepos.size - 2} more" else ""
                    val allPlugins = getAvailablePlugins.get()
                    updateState { copy(plugins = allPlugins, statusText = "Added ${addedRepos.size} repository(s): $repoNames.") }
                    refreshInstalled()
                } else {
                    updateState { copy(statusText = "Failed to load repository. Check the URL and try again.") }
                }
            } catch (e: Throwable) {
                updateState { copy(statusText = "Error: ${e.message}") }
            }
        }
    }

    private fun removeRepository(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            removePluginRepository.await(url)
            pluginRepo.incrementSyncGeneration()
        }
    }

    private fun syncAllRepos() {
        viewModelScope.launch {
            updateState { copy(isSyncing = true, isFetching = true, statusText = "Syncing repositories...") }
            try {
                withContext(Dispatchers.IO) {
                    syncPluginRepositories.await { done, total ->
                        val currentPlugins = getAvailablePlugins.get()
                        updateState {
                            copy(
                                plugins = currentPlugins,
                                statusText = "Syncing repositories ($done/$total)...",
                            )
                        }
                    }
                }
                val allPlugins = getAvailablePlugins.get()
                updateState { copy(plugins = allPlugins, statusText = "Sync completed successfully.") }
            } catch (e: Throwable) {
                updateState { copy(statusText = "Error syncing: ${e.message}") }
            } finally {
                updateState { copy(isSyncing = false, isFetching = false) }
            }
        }
    }

    private fun inspectRepository(repoName: String?) {
        updateState { copy(inspectedRepoName = repoName) }
    }

    private fun fetchPlugins() {
        updateState { copy(isFetching = true, statusText = "Fetching plugins from repositories...") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    syncPluginRepositories.await { done, total ->
                        val currentPlugins = getAvailablePlugins.get()
                        updateState {
                            copy(
                                plugins = currentPlugins,
                                statusText = "Fetching repositories ($done/$total)...",
                            )
                        }
                    }
                }
                val allPlugins = getAvailablePlugins.get()
                val text = "Fetched ${allPlugins.size} plugins from ${getPluginRepositories.get().size} repositories."
                updateState { copy(plugins = allPlugins, statusText = text) }
            } catch (e: Throwable) {
                updateState { copy(statusText = "Error: ${e.message}") }
            } finally {
                updateState { copy(isFetching = false) }
            }
        }
    }

    private fun loadPluginsFromManager() {
        val allPlugins = getAvailablePlugins.get()
        val text = "Showing ${allPlugins.size} plugins from ${getPluginRepositories.get().size} repositories."
        updateState { copy(plugins = allPlugins, statusText = text) }
    }

    private fun refreshInstalled() {
        val list = mutableListOf<LocalPlugin>()
        val extensionsDir = pluginRepo.getExtensionsDir()
        val allRemote = getAvailablePlugins.get()
        val savedRepos = getPluginRepositories.get()
        if (extensionsDir.exists()) {
            val candidates = extensionsDir.walkTopDown()
                .filter(PluginArchiveFilter::isLoadablePluginArchive)
                .map { jar ->
                    val manifest = pluginRepo.readPluginManifest(jar)
                    val name = manifest?.get("name") as? String ?: jar.nameWithoutExtension
                    val internalName = manifest?.get("internalName") as? String ?: name
                    InstalledArchiveCandidate(jar, manifest, name, internalName)
                }
                .toList()
            val selectedCandidates = candidates
                .groupBy { com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.installedPluginIdentity(it.internalName) }
                .toSortedMap()
                .values
                .mapNotNull { copies ->
                    val internalName = copies.firstOrNull()?.internalName ?: return@mapNotNull null
                    val selectedFile = selectInstalledPluginArchive(
                        candidates = copies.map { it.file },
                        loadedPath = ExtensionLoader.getLoadedPluginPath(internalName),
                    ) ?: return@mapNotNull null
                    copies.firstOrNull { archiveCanonicalPath(it.file) == archiveCanonicalPath(selectedFile) }
                }

            selectedCandidates.forEach { candidate ->
                val jar = candidate.file
                val manifest = candidate.manifest
                val name = candidate.name
                val internalName = candidate.internalName
                val version = manifest?.get("version")?.toString()?.toIntOrNull() ?: 0
                val iconUrl = manifest?.get("iconUrl") as? String

                // Exact repository matching based on folder structure on disk
                val folderName = jar.parentFile?.name ?: ""
                val repositoriesForFolder = savedRepos.filter {
                    com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.safeDirectoryName(it.storageName)
                        .equals(folderName, ignoreCase = true)
                }
                val matchingSavedRepo = repositoriesForFolder.singleOrNull()
                val remoteMatch = if (repositoriesForFolder.size > 1) {
                    null
                } else {
                    allRemote.find { (rName, p) ->
                        val storageName = savedRepos.firstOrNull { it.name == rName }?.storageName ?: rName
                        val cleanRName = com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.safeDirectoryName(storageName)
                        p.internalName == internalName && cleanRName.equals(folderName, ignoreCase = true)
                    }
                }

                val repoName = matchingSavedRepo?.name
                    ?: remoteMatch?.first
                    ?: folderName.replace("_", " ").ifBlank { "Local" }

                val rawTvTypes = manifest?.get("tvTypes")
                val tvTypes = when (rawTvTypes) {
                    is List<*> -> rawTvTypes.filterIsInstance<String>()
                    is String -> listOf(rawTvTypes)
                    else -> remoteMatch?.second?.tvTypes ?: emptyList()
                }
                val language = manifest?.get("language") as? String ?: remoteMatch?.second?.language

                val description = manifest?.get("description") as? String ?: remoteMatch?.second?.description
                list.add(LocalPlugin(jar, name, internalName, version, iconUrl, repoName, language, tvTypes, description, jar.length()))
            }
        }
        updateState { copy(installedPlugins = list) }
    }

    private fun installedPluginForIdentity(internalName: String): LocalPlugin? {
        val identity = com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.installedPluginIdentity(internalName)
        return uiState.value.installedPlugins.firstOrNull {
            com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.installedPluginIdentity(it.internalName) == identity
        }
    }

    private fun installPlugin(repoName: String, plugin: SitePlugin) {
        viewModelScope.launch {
            updateState { copy(installingPlugins = installingPlugins + plugin.internalName) }
            val installedPlugin = installedPluginForIdentity(plugin.internalName)
            val activeFile = installedPlugin?.file
            val targetRepoName = installedPlugin?.repoName ?: repoName
            val jarFile = com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.pluginFile(targetRepoName, plugin.internalName, ".jar")
            val activeFileAtDifferentPath = activeFile?.takeIf {
                archiveCanonicalPath(it) != archiveCanonicalPath(jarFile)
            }
            val targetArtifacts = com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.pluginArtifactFiles(jarFile)
            val targetArtifactPaths = targetArtifacts.map(::archiveCanonicalPath).toSet()
            val rollbackFiles = buildList {
                addAll(targetArtifacts)
                activeFileAtDifferentPath?.let {
                    addAll(com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.pluginArtifactFiles(it))
                }
            }
            var downloadFailed = false

            try {
                val downloadedFile = withContext(Dispatchers.IO) {
                    com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.withFileRollback(
                        files = rollbackFiles,
                        onBeforeRestore = {
                            activeFile?.let { ExtensionLoader.unloadPlugin(it.absolutePath) }
                            ExtensionLoader.unloadPlugin(jarFile.absolutePath)
                        },
                        onAfterRestore = {
                            val restoreFile = activeFile?.takeIf { it.isFile } ?: jarFile.takeIf { it.isFile }
                            restoreFile?.let { ExtensionLoader.loadAndInit(it) }
                        },
                    ) {
                        // Unload before replacing files so Windows can release the active class loader.
                        activeFile?.let { ExtensionLoader.unloadPlugin(it.absolutePath) }
                        ExtensionLoader.unloadPlugin(jarFile.absolutePath)
                        val downloaded = installPluginUseCase.await(targetRepoName, plugin)
                            ?: run {
                                downloadFailed = true
                                throw java.io.IOException("Plugin download failed")
                            }
                        ExtensionLoader.unloadPlugin(downloaded.absolutePath)
                        ExtensionLoader.loadAndInit(downloaded)
                        if (activeFileAtDifferentPath != null) {
                            com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.pluginArtifactFiles(activeFileAtDifferentPath)
                                .filterNot { archiveCanonicalPath(it) in targetArtifactPaths }
                                .forEach { java.nio.file.Files.deleteIfExists(it.toPath()) }
                        }
                        downloaded
                    }
                }
                refreshInstalled()
                pluginRepo.incrementSyncGeneration()
                com.lagradost.cloudstream3.desktop.ui.components.AppToastManager.showSuccess(
                    "Installed '${plugin.name}' (v${plugin.version})",
                )
            } catch (e: Throwable) {
                withContext(Dispatchers.IO) {
                    refreshInstalled()
                }
                com.lagradost.common.logging.AppLogger.e("Error loading plugin", e)
                if (downloadFailed) {
                    com.lagradost.cloudstream3.desktop.ui.components.AppToastManager.showError(
                        "Failed to download '${plugin.name}': Network or server error",
                    )
                } else {
                    com.lagradost.cloudstream3.desktop.ui.components.AppToastManager.showError(
                        "Failed to install '${plugin.name}': ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            } finally {
                updateState { copy(installingPlugins = installingPlugins - plugin.internalName) }
            }
        }
    }

    private fun uninstallPlugins(plugins: List<LocalPlugin>) {
        viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(isUninstalling = true) }
            val targetPlugins = plugins.distinctBy {
                com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.installedPluginIdentity(it.internalName)
            }
            val extensionsDir = pluginRepo.getExtensionsDir()
            val archiveIdentities = if (extensionsDir.exists()) {
                extensionsDir.walkTopDown()
                    .filter(PluginArchiveFilter::isLoadablePluginArchive)
                    .mapNotNull { file ->
                        val manifest = pluginRepo.readPluginManifest(file)
                        val name = manifest?.get("name") as? String ?: file.nameWithoutExtension
                        val internalName = manifest?.get("internalName") as? String ?: name
                        InstalledPluginArchiveIdentity(file, internalName)
                    }
                    .toMutableList()
            } else {
                mutableListOf()
            }
            targetPlugins.forEach { plugin ->
                val identity = com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.installedPluginIdentity(plugin.internalName)
                val relevantFiles = listOfNotNull(plugin.file, ExtensionLoader.getLoadedPluginPath(identity))
                relevantFiles.forEach { file ->
                    if (archiveIdentities.none { archiveCanonicalPath(it.file) == archiveCanonicalPath(file) }) {
                        archiveIdentities += InstalledPluginArchiveIdentity(file, identity)
                    }
                }
            }
            val identitiesToRemove = targetPlugins.map {
                com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.installedPluginIdentity(it.internalName)
            }.toSet()
            val artifactsToRemove = pluginArtifactsToRemoveByIdentity(archiveIdentities, identitiesToRemove)

            for (plugin in targetPlugins) {
                try {
                    val identity = com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.installedPluginIdentity(plugin.internalName)
                    val matchingArchives = archiveIdentities.filter {
                        com.lagradost.cloudstream3.desktop.repo.PluginFileUtils.installedPluginIdentity(it.internalName) == identity
                    }
                    val activeFile = ExtensionLoader.getLoadedPluginPath(identity) ?: plugin.file
                    val pluginSourcePaths = (matchingArchives.map { it.file } + activeFile + plugin.file)
                        .map { it.absolutePath }
                        .toSet()
                    // Capture names while the active copy still has its provider registrations.
                    val providerIdentities = com.lagradost.cloudstream3.APIHolder.allProviders.map {
                        ProviderPreferenceIdentity(it.name, it.sourcePlugin)
                    }

                    // Unload the active identity copy before removing every on-disk source copy.
                    ExtensionLoader.unloadPlugin(activeFile.absolutePath)

                    // Step 2: Unload releases URLClassLoader handles immediately via ExtensionLoader.
                    @Suppress("ExplicitGarbageCollectionCall")
                    System.gc()

                    // Step 3: Remove active provider keys captured before unload cleared registrations.
                    val activeKeys = com.lagradost.cloudstream3.desktop.repo.ActiveProviderRepository.activeProviderKeys.value
                    val updatedKeys = activeProviderKeysAfterPluginUninstall(
                        activeKeys = activeKeys,
                        targetSourcePaths = pluginSourcePaths,
                        providers = providerIdentities,
                    )
                    if (updatedKeys.size != activeKeys.size) {
                        com.lagradost.cloudstream3.desktop.repo.ActiveProviderRepository.setActiveProviders(updatedKeys)
                    }

                    // Step 4: Delete all source copies and their artifacts, preserving paths shared with another identity.
                    for (f in artifactsToRemove[identity].orEmpty()) {
                        if (f.exists()) {
                            val ok = try {
                                java.nio.file.Files.deleteIfExists(f.toPath())
                                true
                            } catch (_: Throwable) {
                                f.delete()
                            }
                            if (!ok) f.deleteOnExit()
                            com.lagradost.common.logging.AppLogger.i("Delete '${f.name}': ok=$ok")
                        }
                    }

                    // Step 5: Revoke persistent trust so future fresh re-installs require re-verification
                    ExtensionLoader.removeTrusted(activeFile, plugin.internalName, manifestName = plugin.name)

                    com.lagradost.common.logging.AppLogger.i("Uninstalled plugin '${plugin.name}' successfully.")
                } catch (e: Throwable) {
                    com.lagradost.common.logging.AppLogger.e("Error uninstalling plugin '${plugin.name}'", e)
                }
            }
            refreshInstalled()
            pluginRepo.incrementSyncGeneration()
            updateState { copy(isUninstalling = false) }
        }
    }

    private fun uninstallByInternalName(internalName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val installedMatch = uiState.value.installedPlugins.find { it.internalName == internalName }
            if (installedMatch != null) {
                uninstallPlugins(listOf(installedMatch))
            }
        }
    }

    private fun loadLocalPlugin(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetDir = File(pluginRepo.getExtensionsDir(), "Local_Sandbox")
            targetDir.mkdirs()
            val targetFile = File(targetDir, file.name)
            file.copyTo(targetFile, overwrite = true)
            try {
                ExtensionLoader.loadAndInit(targetFile)
            } catch (e: Exception) {
                com.lagradost.common.logging.AppLogger.e("Error loading local plugin", e)
            }
            refreshInstalled()
            pluginRepo.incrementSyncGeneration()
        }
    }

    private fun reloadPluginAfterSettings(file: File, pluginName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ExtensionLoader.unloadPlugin(file.absolutePath)
                ExtensionLoader.loadAndInit(file)
                pluginRepo.incrementSyncGeneration()
                com.lagradost.common.logging.AppLogger.i("Reloaded plugin $pluginName after settings update")
                com.lagradost.cloudstream3.desktop.ui.components.AppToastManager.showSuccess("Reloaded '$pluginName'")
            } catch (e: Throwable) {
                com.lagradost.common.logging.AppLogger.e("Failed to reload plugin $pluginName", e)
                com.lagradost.cloudstream3.desktop.ui.components.AppToastManager.showError(
                    "Failed to reload '$pluginName': ${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    private fun clearUpdateHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            com.lagradost.common.storage.DesktopDataStore.clearUpdatesHistory()
        }
    }

    private fun addStremioAddon(url: String, onResult: (Result<com.lagradost.cloudstream3.desktop.stremio.ManagedStremioAddon>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = com.lagradost.cloudstream3.desktop.stremio.StremioAddonManager.addAddon(url)
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    private fun removeStremioAddon(manifestUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            com.lagradost.cloudstream3.desktop.stremio.StremioAddonManager.removeAddon(manifestUrl)
        }
    }

    private fun setStremioAddonEnabled(manifestUrl: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            com.lagradost.cloudstream3.desktop.stremio.StremioAddonManager.setAddonEnabled(manifestUrl, enabled)
        }
    }

    private fun refreshStremioAddon(manifestUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            com.lagradost.cloudstream3.desktop.stremio.StremioAddonManager.refreshAddon(manifestUrl)
        }
    }

    private fun moveStremioAddon(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            com.lagradost.cloudstream3.desktop.stremio.StremioAddonManager.moveAddon(fromIndex, toIndex)
        }
    }
}
