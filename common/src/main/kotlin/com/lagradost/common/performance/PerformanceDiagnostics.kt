package com.lagradost.common.performance

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/** Fixed list of timing points that may be recorded by the application. */
enum class PerformanceDiagnosticStage(val key: String) {
    PROVIDER_DETAIL("provider_detail"),
    METADATA_IDENTITY("metadata_identity"),
    METADATA_ENRICHMENT("metadata_enrichment"),
    SOURCE_LOOKUP("source_lookup"),
    FIRST_FRAME("first_frame"),
    BUFFERING("buffering"),
    RECOVERY("recovery"),
    SOURCE_SUCCESS("source_success"),
}

/** Status is deliberately an enum so arbitrary error text cannot enter diagnostics. */
enum class PerformanceDiagnosticStatus {
    SUCCESS,
    FAILURE,
    TIMEOUT,
    CANCELLED,
    UNKNOWN,
}

/** Contains only allowlisted timing metadata. No title, URL, stream, or exception fields exist. */
data class PerformanceDiagnosticSample(
    val stage: PerformanceDiagnosticStage,
    val sourceLabel: String,
    val profileId: String?,
    val status: PerformanceDiagnosticStatus,
    val durationMs: Long,
    val timestampMs: Long,
)

data class PerformanceDiagnosticSummary(
    val stage: PerformanceDiagnosticStage,
    val sourceLabel: String,
    val profileId: String?,
    val sampleCount: Int,
    val medianMs: Double,
    val p95Ms: Double,
)

/**
 * Desktop entry point. Samples are stored under the existing private application data directory.
 * The public API intentionally accepts only a stage, provider/source identifier, optional profile
 * identifier, status, and duration.
 */
object PerformanceDiagnostics {
    private val IO_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy {
        PerformanceDiagnosticsStore(File(PlatformPaths.dataDir, "performance-diagnostics.json"))
    }

    fun record(
        stage: PerformanceDiagnosticStage,
        sourceLabel: String,
        profileId: String? = null,
        status: PerformanceDiagnosticStatus,
        durationMs: Long,
    ): Boolean = store.record(stage, sourceLabel, profileId, status, durationMs)

    /** Schedules serialization and persistence on an I/O dispatcher; safe to call from UI/player callbacks. */
    fun recordAsync(
        stage: PerformanceDiagnosticStage,
        sourceLabel: String,
        profileId: String? = null,
        status: PerformanceDiagnosticStatus,
        durationMs: Long,
    ): Deferred<Boolean> = IO_SCOPE.async {
        // Resolve PlatformPaths.dataDir here as well: its lazy getter may create directories.
        store.record(stage, sourceLabel, profileId, status, durationMs)
    }

    fun snapshot(): List<PerformanceDiagnosticSample> = store.snapshot()

    fun summaries(): List<PerformanceDiagnosticSummary> = store.summaries()
}

/** Internal file-backed store also used by focused tests. */
internal class PerformanceDiagnosticsStore(
    private val file: File,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    fun record(
        stage: PerformanceDiagnosticStage,
        sourceLabel: String,
        profileId: String? = null,
        status: PerformanceDiagnosticStatus,
        durationMs: Long,
    ): Boolean {
        val safeSource = PerformanceDiagnosticSanitizer.label(sourceLabel) ?: return false
        val safeProfile = PerformanceDiagnosticSanitizer.profileId(profileId) ?: if (profileId == null) null else return false
        if (durationMs < 0) return false
        val timestamp = clockMs()
        if (timestamp < 0) return false
        val sample = PerformanceDiagnosticSample(stage, safeSource, safeProfile, status, durationMs, timestamp)

        return try {
            withFileLock {
                val next = (readLocked() + sample).takeLast(MAX_SAMPLES)
                writeLocked(next)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** The returned Deferred completes after the local write attempt finishes on Dispatchers.IO. */
    fun recordAsync(
        stage: PerformanceDiagnosticStage,
        sourceLabel: String,
        profileId: String? = null,
        status: PerformanceDiagnosticStatus,
        durationMs: Long,
    ): Deferred<Boolean> = IO_SCOPE.async {
        record(stage, sourceLabel, profileId, status, durationMs)
    }

    fun snapshot(): List<PerformanceDiagnosticSample> = try {
        withFileLock { readLocked() }
    } catch (_: Exception) {
        emptyList()
    }

    fun summaries(): List<PerformanceDiagnosticSummary> = summarizePerformanceSamples(snapshot())

    fun clear(): Boolean = try {
        withFileLock {
            if (file.exists() && !file.delete()) throw IllegalStateException("Unable to clear diagnostics")
            backupFile.delete()
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun <T> withFileLock(action: () -> T): T {
        val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val processLock = PROCESS_LOCKS.computeIfAbsent(key) { Any() }
        synchronized(processLock) {
            file.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                    throw IllegalStateException("Unable to create diagnostics directory")
                }
            }
            val lockFile = File(file.parentFile ?: File("."), file.name + ".lock")
            RandomAccessFile(lockFile, "rw").use { lockHandle ->
                lockHandle.channel.lock().use { return action() }
            }
        }
    }

    private fun readLocked(): List<PerformanceDiagnosticSample> {
        val source = when {
            file.isFile -> file
            backupFile.isFile -> backupFile
            else -> return emptyList()
        }
        val stored = try {
            mapper.readValue(source, SAMPLE_LIST_TYPE)
        } catch (_: Exception) {
            if (source == backupFile) return emptyList()
            return try {
                mapper.readValue(backupFile, SAMPLE_LIST_TYPE)
            } catch (_: Exception) {
                emptyList()
            }
        }
        return stored.mapNotNull(::sanitizeStoredSample).takeLast(MAX_SAMPLES)
    }

    private fun sanitizeStoredSample(sample: PerformanceDiagnosticSample): PerformanceDiagnosticSample? {
        val safeSource = PerformanceDiagnosticSanitizer.label(sample.sourceLabel) ?: return null
        val safeProfile = PerformanceDiagnosticSanitizer.profileId(sample.profileId)
            ?: if (sample.profileId == null) null else return null
        if (sample.durationMs < 0 || sample.timestampMs < 0) return null
        return sample.copy(sourceLabel = safeSource, profileId = safeProfile)
    }

    private fun writeLocked(samples: List<PerformanceDiagnosticSample>) {
        val temp = File(file.parentFile ?: File("."), file.name + ".tmp")
        val backup = backupFile
        try {
            FileOutputStream(temp).use { output ->
                output.write(mapper.writeValueAsBytes(samples))
                output.fd.sync()
            }
            if (file.exists()) {
                if (backup.exists() && !backup.delete()) throw IllegalStateException("Unable to replace diagnostics backup")
                if (!file.renameTo(backup)) throw IllegalStateException("Unable to stage diagnostics file")
            }
            if (!temp.renameTo(file)) {
                if (backup.exists()) backup.renameTo(file)
                throw IllegalStateException("Unable to replace diagnostics file")
            }
            backup.delete()
        } catch (error: Exception) {
            temp.delete()
            if (!file.exists() && backup.exists()) backup.renameTo(file)
            throw error
        }
    }

    private val backupFile: File get() = File(file.parentFile ?: File("."), file.name + ".bak")

    companion object {
        const val MAX_SAMPLES = 512
        private val PROCESS_LOCKS = ConcurrentHashMap<String, Any>()
        private val IO_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mapper = jacksonObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        private val SAMPLE_LIST_TYPE = object : TypeReference<List<PerformanceDiagnosticSample>>() {}
    }
}

/** Sanitizers reject malformed values as a whole instead of trying to scrub arbitrary text. */
/** Converts a provider/add-on display name into a bounded identifier before persistence. */
fun normalizePerformanceSourceLabel(value: String?): String? {
    val candidate = value?.trim()?.lowercase(Locale.ROOT)
        ?.replace(Regex("[^A-Za-z0-9_.-]+"), "_")
        ?.trim('_')
    return PerformanceDiagnosticSanitizer.label(candidate)
}

internal object PerformanceDiagnosticSanitizer {
    private val safeLabel = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
    private val safeProfileId = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val sensitiveMarker = Regex("(?i)(token|password|secret|authorization|api[_-]?key|cookie|bearer|https?)")

    fun label(value: String?): String? {
        val candidate = value?.trim() ?: return null
        if (!safeLabel.matches(candidate) || sensitiveMarker.containsMatchIn(candidate)) return null
        return candidate
    }

    fun profileId(value: String?): String? {
        if (value == null) return null
        val candidate = value.trim()
        if (!safeProfileId.matches(candidate) || sensitiveMarker.containsMatchIn(candidate)) return null
        return candidate
    }
}

/** Nearest-rank p95 and arithmetic median, grouped exactly by stage/source/profile. */
internal fun summarizePerformanceSamples(
    samples: List<PerformanceDiagnosticSample>,
): List<PerformanceDiagnosticSummary> = samples
    .groupBy { Triple(it.stage, it.sourceLabel, it.profileId) }
    .map { (key, group) ->
        val sorted = group.map { it.durationMs }.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 0) {
            sorted[middle - 1] / 2.0 + sorted[middle] / 2.0
        } else {
            sorted[middle].toDouble()
        }
        val p95Index = (ceil(sorted.size * 0.95).toInt() - 1).coerceAtLeast(0)
        PerformanceDiagnosticSummary(
            stage = key.first,
            sourceLabel = key.second,
            profileId = key.third,
            sampleCount = sorted.size,
            medianMs = median,
            p95Ms = sorted[p95Index].toDouble(),
        )
    }
    .sortedWith(compareBy({ it.stage.key }, { it.sourceLabel }, { it.profileId ?: "" }))
