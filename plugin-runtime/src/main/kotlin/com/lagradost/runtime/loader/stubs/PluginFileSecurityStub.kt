package com.lagradost.runtime.loader.stubs

import com.lagradost.common.platform.PlatformPaths
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/** Compatibility shims for transformed plugin bytecode; filesystem calls retain normal JVM behavior. */
@Suppress("UNUSED_PARAMETER")
object PluginFileSecurityStub {
    @Volatile
    var customBaseDir: File? = null

    fun grantAllowedPath(pluginName: String, path: File) = Unit

    fun revokeAllowedPath(pluginName: String, path: File) = Unit

    fun clearGrantedPaths(pluginName: String? = null) = Unit

    fun getStorageRootForPlugin(pluginName: String): File {
        val base = customBaseDir ?: PlatformPaths.appDataDir
        val cleaned = pluginName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val safeName = if (cleaned.isBlank() || cleaned.all { it == '.' }) "_" else cleaned
        return File(base, "Extensions/$safeName/storage")
    }

    @JvmStatic
    fun checkPath(rawPath: String): String = File(rawPath).path

    @JvmStatic
    fun checkFile(file: File): File = file

    @JvmStatic
    fun listRoots(): Array<File> = File.listRoots()

    @JvmStatic
    fun createTempFile(prefix: String, suffix: String?): File = File.createTempFile(prefix, suffix)

    @JvmStatic
    fun createTempFile(prefix: String, suffix: String?, directory: File): File =
        File.createTempFile(prefix, suffix, directory)

    @JvmStatic
    fun checkParentChild(parent: String, child: String): String = File(parent, child).path

    @JvmStatic
    fun checkFileChild(parent: File, child: String): File = File(parent, child)

    @JvmStatic
    fun checkFileChildPath(parent: File, child: String): String = File(parent, child).path

    @JvmStatic
    fun checkUri(uri: URI): URI = uri

    @JvmStatic
    fun getPath(first: String, vararg more: String): Path = Paths.get(first, *more)
}
