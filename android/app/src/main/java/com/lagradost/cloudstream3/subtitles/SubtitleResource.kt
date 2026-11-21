package com.lagradost.cloudstream3.subtitles

import android.net.Uri
import androidx.annotation.WorkerThread
import com.auras.orbit.extensions.OrbitCs3HostServices
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import okio.BufferedSource
import java.io.File

/** Subtitle resource builder retained for the pinned SubtitleAPI method signatures. */
class SubtitleResource {
    @WorkerThread
    fun downloadFile(source: BufferedSource): File {
        val file = createPrivateTempFile("temp-subtitle")
        try {
            file.outputStream().use { output ->
                source.inputStream().use { SubtitleArchiveGuard.copyLimited(it, output, SubtitleArchiveGuard.MAX_ZIP_BYTES) }
            }
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun unzip(file: File): List<Pair<String, File>> =
        SubtitleArchiveGuard.extract(file, OrbitCs3HostServices.get().applicationContext.cacheDir)
            .map { it.name to it.file }

    data class SingleSubtitleResource(
        val name: String?,
        val url: String,
        val origin: SubtitleOrigin,
        val headers: Map<String, String> = emptyMap(),
    )

    private val resources: MutableList<SingleSubtitleResource> = mutableListOf()

    fun getSubtitles(): List<SingleSubtitleResource> = resources.toList()

    fun addUrl(url: String?, name: String? = null, headers: Map<String, String> = emptyMap()) {
        if (url == null) return
        resources.add(SingleSubtitleResource(name, url, SubtitleOrigin.URL, headers.toMap()))
    }

    fun addFile(file: File, name: String? = null) {
        resources.add(
            SingleSubtitleResource(name, Uri.fromFile(file).toString(), SubtitleOrigin.DOWNLOADED_FILE)
        )
        file.deleteOnExit()
    }

    @WorkerThread
    suspend fun addZipUrl(
        url: String,
        nameGenerator: (String, File) -> String? = { _, _ -> null },
    ) {
        val zip = SubtitleArchiveGuard.downloadZip(
            url, OrbitCs3HostServices.get().applicationContext.cacheDir
        )
        val files = try {
            unzip(zip)
        } finally {
            zip.delete()
        }
        registerExtractedFiles(files, nameGenerator)
    }

    /** Stage filenames first; a failed callback must not leave dangling subtitle references. */
    internal fun registerExtractedFiles(
        files: List<Pair<String, File>>,
        nameGenerator: (String, File) -> String?,
    ) {
        try {
            val staged = files.map { (entryName, subtitleFile) ->
                SingleSubtitleResource(
                    nameGenerator(entryName, subtitleFile),
                    Uri.fromFile(subtitleFile).toString(),
                    SubtitleOrigin.DOWNLOADED_FILE,
                )
            }
            resources.addAll(staged)
            files.forEach { (_, file) -> file.deleteOnExit() }
        } catch (error: Throwable) {
            files.forEach { (_, file) -> file.delete() }
            throw error
        }
    }

    private fun createPrivateTempFile(prefix: String): File = File.createTempFile(
        prefix,
        ".tmp",
        OrbitCs3HostServices.get().applicationContext.cacheDir,
    )
}
