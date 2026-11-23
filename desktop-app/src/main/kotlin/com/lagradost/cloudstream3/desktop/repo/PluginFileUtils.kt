package com.lagradost.cloudstream3.desktop.repo

import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.runtime.loader.ExtensionLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/**
 * Internal file utility for plugin downloads, hashing, and cache directories.
 * Not part of the public API — consumed by the desktop plugin repository and lifecycle UI.
 */
internal object PluginFileUtils {

    fun getExtensionsDir(): File = PlatformPaths.extensionsDir

    fun safeDirectoryName(value: String): String {
        val cleaned = value.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return if (cleaned.isBlank() || cleaned.all { it == '.' }) "_" else cleaned
    }

    fun repositoryDirectoryCollisionKey(repoName: String): String =
        safeDirectoryName(repoName).lowercase(Locale.ROOT)

    internal fun hasRepositoryFolderCollision(repositories: List<com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData>, repoName: String): Boolean {
        val matchingRepositories = repositories.filter { it.name == repoName }
        if (matchingRepositories.size > 1) return true
        val folderName = matchingRepositories.singleOrNull()?.storageName ?: repoName
        val folderKey = repositoryDirectoryCollisionKey(folderName)
        return repositories.count { repositoryDirectoryCollisionKey(it.storageName) == folderKey } > 1
    }

    fun repositoryDirectory(repoName: String): File {
        val root = getExtensionsDir().canonicalFile
        val repositories = DesktopRepositoryManager.getSavedRepositories()
        require(!hasRepositoryFolderCollision(repositories, repoName)) {
            "Repository '$repoName' shares a legacy plugin folder; install and update operations are blocked until the folder identity is resolved."
        }
        val storageName = repositories.singleOrNull { it.name == repoName }?.storageName ?: repoName
        val directory = File(root, safeDirectoryName(storageName)).canonicalFile
        require(directory.parentFile == root) { "Repository directory must be inside Extensions" }
        return directory
    }

    fun pluginFile(repoName: String, internalName: String, suffix: String): File {
        val directory = repositoryDirectory(repoName)
        val file = File(directory, safeDirectoryName(internalName) + suffix).canonicalFile
        require(file.parentFile == directory) { "Plugin file must be inside its repository" }
        return file
    }

    /**
     * Returns a source plugin package and all of its generated cache artifacts, including
     * identity markers. Callers pass the source path selected by the installed-plugin scan.
     */
    internal fun pluginArtifactFiles(pluginFile: File): List<File> {
        val name = pluginFile.name
        val sourceSuffix = listOf(".cs3", ".dex", ".jar")
            .firstOrNull { name.endsWith(it, ignoreCase = true) }
        val stem = sourceSuffix?.let { name.dropLast(it.length) } ?: pluginFile.nameWithoutExtension
        val parent = pluginFile.parentFile
        val archives = listOf(
            File(parent, "$stem.jar"),
            File(parent, "$stem-jvm.jar"),
            File(parent, "$stem-secure.jar"),
            File(parent, "$stem.cs3"),
            File(parent, "$stem.dex"),
        )
        return withCacheIdentityFiles(archives)
    }

    /** Manifest internalName is the stable identity across versions and repositories. */
    internal fun installedPluginIdentity(internalName: String): String = internalName

    /** Adds each JVM archive's cache identity marker to a rollback or cleanup set. */
    internal fun withCacheIdentityFiles(files: List<File>): List<File> = files
        .flatMap { file ->
            if (file.extension.equals("jar", ignoreCase = true)) {
                listOf(file, File(file.path + ".identity"))
            } else {
                listOf(file)
            }
        }
        .distinctBy { it.absoluteFile.normalize().path }

    /**
     * Keep the current plugin files available until a replacement has downloaded and loaded.
     * The callbacks unload a partially loaded replacement before restoring files, then reload
     * the previous plugin after every file has been restored.
     */
    internal suspend fun <T> withFileRollback(
        files: List<File>,
        onBeforeRestore: () -> Unit = {},
        onAfterRestore: () -> Unit = {},
        operation: suspend () -> T,
    ): T = withContext(Dispatchers.IO) {
        val targets = withCacheIdentityFiles(files).map { it.canonicalFile }.distinct()
        val extensionsDir = getExtensionsDir()
        if (!extensionsDir.exists()) extensionsDir.mkdirs()
        val backupDir = Files.createTempDirectory(extensionsDir.toPath(), ".plugin-rollback-").toFile()
        val backups = try {
            targets.mapIndexed { index, target ->
                val backup = File(backupDir, index.toString())
                val existed = target.isFile
                if (existed) Files.copy(target.toPath(), backup.toPath())
                target to backup.takeIf { existed }
            }
        } catch (failure: Throwable) {
            backupDir.deleteRecursively()
            throw failure
        }

        try {
            operation()
        } catch (failure: Throwable) {
            try {
                onBeforeRestore()
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }

            var restored = true
            backups.forEach { (target, backup) ->
                try {
                    if (backup == null) {
                        Files.deleteIfExists(target.toPath())
                    } else {
                        target.parentFile?.mkdirs()
                        restoreAtomically(backup, target)
                    }
                } catch (rollbackFailure: Throwable) {
                    restored = false
                    failure.addSuppressed(rollbackFailure)
                }
            }
            if (restored) {
                try {
                    onAfterRestore()
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        } finally {
            backupDir.deleteRecursively()
        }
    }

    private fun restoreAtomically(backup: File, target: File) {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile(".${target.name}.", ".restore", target.parentFile)
        try {
            Files.copy(backup.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            moveReplacing(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var read = fis.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = fis.read(buffer)
            }
        }
        return "sha256-" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun alternatePluginUrls(
        plugin: SitePlugin,
        candidates: List<Pair<String, SitePlugin>>,
    ): List<String> = candidates.asSequence()
        .map { it.second }
        .filter {
            plugin.fileHash != null &&
                it.internalName == plugin.internalName &&
                it.fileHash.equals(plugin.fileHash, ignoreCase = true) &&
                it.url != plugin.url && PluginNetworkClient.isAllowedRepositoryUrl(it.url)
        }
        .map { it.url }
        .distinct()
        .toList()

    /**
     * Downloads a `.jar` or `.cs3` plugin to the correct repository directory.
     * Optionally fetches a pre-compiled JVM bytecode jar to bypass Dex2Jar.
     */
    suspend fun downloadPlugin(repoName: String, plugin: SitePlugin): File? = withContext(Dispatchers.IO) {
        val destFile = pluginFile(repoName, plugin.internalName, ".jar")
        val alternateUrls = alternatePluginUrls(plugin, DesktopRepositoryManager.getAllPlugins())
        downloadPlugin(plugin, destFile, (listOf(plugin.url) + alternateUrls).distinct(), PluginNetworkClient.pluginDownloadClient)
    }

    internal suspend fun downloadPlugin(
        plugin: SitePlugin,
        destFile: File,
        candidateUrls: List<String>,
        client: okhttp3.OkHttpClient,
    ): File? = withContext(Dispatchers.IO) {
        val repoDir = destFile.parentFile ?: return@withContext null
        if (!repoDir.exists()) repoDir.mkdirs()
        val tempFile = File.createTempFile(destFile.name, ".tmp", repoDir)
        val safeClient = PluginNetworkClient.enforceRepositoryTransport(client)

        try {
            var downloadSuccess = false
            for (candidateUrl in candidateUrls) {
                try {
                    require(PluginNetworkClient.isAllowedRepositoryUrl(candidateUrl)) {
                        "Plugin downloads require HTTPS"
                    }
                    val request = Request.Builder().url(candidateUrl).build()
                    safeClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code} downloading from $candidateUrl")
                        val body = response.body
                        val contentLength = body.contentLength()
                        if (contentLength > 64 * 1024 * 1024L) {
                            throw IllegalStateException("Plugin package exceeded 64 MB maximum buffer limit")
                        }
                        FileOutputStream(tempFile).use { out ->
                            val buffer = ByteArray(8192)
                            var totalBytes = 0L
                            val input = body.byteStream()
                            var read = input.read(buffer)
                            while (read != -1) {
                                totalBytes += read
                                if (totalBytes > 64 * 1024 * 1024L) {
                                    throw IllegalStateException("Plugin package exceeded 64 MB maximum buffer limit")
                                }
                                out.write(buffer, 0, read)
                                read = input.read(buffer)
                            }
                        }
                    }

                    if (plugin.fileHash != null) {
                        val downloadHash = sha256(tempFile)
                        if (!plugin.fileHash.equals(downloadHash, ignoreCase = true)) {
                            throw IllegalStateException("Extension hash mismatch when validating '${destFile.name}'! Expected: '${plugin.fileHash}', got: '$downloadHash'.")
                        }
                    }

                    com.lagradost.runtime.security.PluginArchiveLimits.verify(tempFile)
                    downloadSuccess = true
                    break
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    AppLogger.w("Failed to download '${plugin.internalName}' from $candidateUrl: ${e.message}. Trying next mirror if available...")
                }
            }

            if (!downloadSuccess) {
                return@withContext null
            }

            // [PERFORMANCE] Stage and transform a pre-compiled JVM jar before committing either
            // artifact. Its marker is bound to the staged source and remains valid after both
            // files are moved into place.
            val jvmDestFile = File(destFile.parentFile, "${destFile.nameWithoutExtension}-jvm.jar")
            val jvmIdentityFile = File(jvmDestFile.path + ".identity")
            val secureDestFile = File(destFile.parentFile, "${destFile.nameWithoutExtension}-secure.jar")
            val secureIdentityFile = File(secureDestFile.path + ".identity")
            val sourceIdentityFile = File(destFile.path + ".identity")
            val dexPackageFile = File(destFile.parentFile, "${destFile.nameWithoutExtension}.cs3")
            val extractedDexFile = File(destFile.parentFile, "${destFile.nameWithoutExtension}.dex")
            val jvmTempFile = File.createTempFile(jvmDestFile.name, ".tmp", jvmDestFile.parentFile)
            val jvmTempIdentityFile = File(jvmTempFile.path + ".identity")
            var preparedJvmSidecar = false
            if (!plugin.jarUrl.isNullOrBlank() && !plugin.jarHash.isNullOrBlank()) {
                try {
                    require(PluginNetworkClient.isAllowedRepositoryUrl(plugin.jarUrl)) {
                        "Pre-compiled plugin downloads require HTTPS"
                    }
                    val jvmRequest = Request.Builder().url(plugin.jarUrl).build()
                    safeClient.newCall(jvmRequest).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body
                            if (body.contentLength() > 64 * 1024 * 1024L) {
                                throw IllegalStateException("JVM plugin exceeded 64 MB maximum buffer limit")
                            }
                            FileOutputStream(jvmTempFile).use { out ->
                                val buffer = ByteArray(8192)
                                var totalBytes = 0L
                                val input = body.byteStream()
                                var read = input.read(buffer)
                                while (read != -1) {
                                    totalBytes += read
                                    if (totalBytes > 64 * 1024 * 1024L) {
                                        throw IllegalStateException("JVM plugin exceeded 64 MB maximum buffer limit")
                                    }
                                    out.write(buffer, 0, read)
                                    read = input.read(buffer)
                                }
                            }

                            com.lagradost.runtime.security.PluginArchiveLimits.verify(jvmTempFile)
                            val downloadHash = sha256(jvmTempFile)
                            if (plugin.jarHash != downloadHash) {
                                throw IllegalStateException("JVM Extension hash mismatch when validating '${jvmDestFile.name}'! Expected: '${plugin.jarHash}', got: '$downloadHash'.")
                            }

                            ExtensionLoader.preparePrecompiledJvmJar(tempFile, jvmTempFile)
                            preparedJvmSidecar = true
                            AppLogger.i("Successfully prepared JVM bytecode for ${plugin.internalName}!")
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    AppLogger.i("Failed to download pre-compiled JVM jar for ${plugin.internalName}: ${e.message}")
                }
            }

            try {
                withFileRollback(withCacheIdentityFiles(listOf(destFile, jvmDestFile, secureDestFile, dexPackageFile, extractedDexFile))) {
                    moveReplacing(tempFile, destFile)
                    Files.deleteIfExists(sourceIdentityFile.toPath())
                    Files.deleteIfExists(secureDestFile.toPath())
                    Files.deleteIfExists(secureIdentityFile.toPath())
                    Files.deleteIfExists(dexPackageFile.toPath())
                    Files.deleteIfExists(extractedDexFile.toPath())
                    if (preparedJvmSidecar) {
                        moveReplacing(jvmTempFile, jvmDestFile)
                        moveReplacing(jvmTempIdentityFile, jvmIdentityFile)
                    } else {
                        // A cache generated from the previous source package cannot be reused
                        // with this replacement. Remove it and its marker as a single rollback set.
                        Files.deleteIfExists(jvmDestFile.toPath())
                        Files.deleteIfExists(jvmIdentityFile.toPath())
                    }
                }
            } finally {
                jvmTempFile.delete()
                jvmTempIdentityFile.delete()
            }

            return@withContext destFile
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Failed to download or unzip plugin ${plugin.url}", e)
            return@withContext null
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /** Unloads jars and physically deletes the repo directory. */
    fun deleteRepositoryDirectory(repoName: String) {
        val repoDir = repositoryDirectory(repoName)
        if (repoDir.exists()) {
            // Must explicitly unload all plugins from this repo first to release Windows file locks
            val jars = repoDir.listFiles { f -> f.isFile && (f.extension == "jar" || f.extension == "cs3") }
            jars?.forEach { jar ->
                com.lagradost.runtime.loader.ExtensionLoader.unloadPlugin(jar.absolutePath)
            }

            // GC and finalize to release locks (same as individual plugin uninstall)
            @Suppress("ExplicitGarbageCollectionCall")
            System.gc()
            Thread.sleep(150)
            @Suppress("deprecation")
            System.runFinalization()

            val deleted = repoDir.deleteRecursively()
            AppLogger.i("Deleted physical repository directory '${repoDir.name}': ok=$deleted")
        }
    }
}
