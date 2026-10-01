package com.lagradost.runtime.security

import java.io.File
import java.util.zip.ZipFile

object PluginArchiveLimits {
    fun verify(file: File) {
        require(file.length() <= 64L * 1024 * 1024) { "Plugin archive exceeds compressed size limit" }
        ZipFile(file).use { zip ->
            var total = 0L
            var count = 0
            val names = HashSet<String>()
            val buffer = ByteArray(8192)
            zip.entries().asSequence().forEach { entry ->
                require(++count <= 20_000 && names.add(entry.name)) { "Too many or duplicate archive entries" }
                require(!entry.name.startsWith("/") && entry.name.split('/', '\\').none { it == ".." }) { "Invalid archive path" }
                var entryBytes = 0L
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        total += read
                        require(entryBytes <= 64L * 1024 * 1024 && total <= 256L * 1024 * 1024) { "Expanded plugin archive exceeds limit" }
                    }
                }
            }
        }
    }
}
