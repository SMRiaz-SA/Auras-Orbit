package com.lagradost.cloudstream3.desktop.pluginworker

/** Serializable host-facing metadata for an extractor owned by a plugin worker process. */
internal data class WorkerExtractorDescriptor(
    val className: String,
    val name: String,
    val mainUrl: String,
    val requiresReferer: Boolean,
)
