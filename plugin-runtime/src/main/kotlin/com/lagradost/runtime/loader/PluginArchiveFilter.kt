package com.lagradost.runtime.loader

import java.io.File
import java.util.Locale

/** Keeps transformed JVM and secured plugin artifacts out of source-plugin scans. */
object PluginArchiveFilter {
    fun isLoadablePluginArchive(file: File): Boolean {
        val name = file.name.lowercase(Locale.ROOT)
        val extension = file.extension.lowercase(Locale.ROOT)
        return file.isFile && extension in setOf("jar", "cs3") &&
            !name.endsWith("-jvm.jar") &&
            !name.endsWith("-secure.jar")
    }
}
