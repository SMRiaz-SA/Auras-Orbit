package com.lagradost.cloudstream3.desktop.domain.plugins.interactor

import com.lagradost.cloudstream3.desktop.domain.plugins.repository.PluginRepository
import com.lagradost.cloudstream3.desktop.repo.PluginFileUtils
import com.lagradost.runtime.loader.ExtensionLoader
import java.io.File

class UninstallPlugin(
    private val repository: PluginRepository,
) {
    suspend fun await(file: File, internalName: String, name: String): Boolean {
        try {
            ExtensionLoader.unloadPlugin(file.absolutePath)
            @Suppress("ExplicitGarbageCollectionCall")
            System.gc()
            Thread.sleep(100)
            @Suppress("deprecation")
            System.runFinalization()

            for (f in PluginFileUtils.pluginArtifactFiles(file)) {
                if (f.exists()) {
                    val ok = f.delete()
                    if (!ok) f.deleteOnExit()
                }
            }
            ExtensionLoader.removeTrusted(file, internalName, manifestName = name)
            repository.incrementSyncGeneration()
            return true
        } catch (_: Throwable) {
            return false
        }
    }
}
