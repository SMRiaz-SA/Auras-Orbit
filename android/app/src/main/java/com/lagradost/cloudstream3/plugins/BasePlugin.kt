package com.lagradost.cloudstream3.plugins

import com.auras.orbit.extensions.OrbitCs3HostServices
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val PLUGIN_TAG = "PluginInstance"

/** Orbit-owned CS3 lifecycle base. Registrations are scoped to a host load transaction. */
abstract class BasePlugin {
    /** Called while this plugin's host load transaction is open. */
    fun registerMainAPI(element: MainAPI) {
        OrbitCs3HostServices.registerMainAPI(this, element)
    }

    /** Called while this plugin's host load transaction is open. */
    fun registerExtractorAPI(element: ExtractorApi) {
        OrbitCs3HostServices.registerExtractorAPI(this, element)
    }

    @Throws(Throwable::class)
    open fun beforeUnload() { }

    @Throws(Throwable::class)
    open fun load() { }

    /** Full file path to the plugin. */
    var filename: String? = null

    /** Legacy property retained for bytecode built against the pre-rename field. */
    @Deprecated(
        "Renamed to `filename` to follow conventions",
        replaceWith = ReplaceWith("filename"),
        level = DeprecationLevel.ERROR,
    )
    var __filename: String?
        get() = filename
        set(value) {
            filename = value
        }

    @Serializable
    class Manifest {
        @JsonProperty("name")
        @SerialName("name")
        var name: String? = null

        @JsonProperty("pluginClassName")
        @SerialName("pluginClassName")
        var pluginClassName: String? = null

        @JsonProperty("requiresResources")
        @SerialName("requiresResources")
        var requiresResources: Boolean = false

        @JsonProperty("version")
        @SerialName("version")
        var version: Int? = null
    }
}
