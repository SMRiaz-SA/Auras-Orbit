package com.lagradost.common.storage

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

fun interface PluginSettingAction {
    fun invoke(): Boolean
}

data class PluginSettingSchema(
    val pluginPrefName: String,
    val key: String,
    val type: String, // "Boolean", "String", "Int", "Long", "Float", "StringSet"
    val defaultValue: Any?,
    val isGlobal: Boolean = false,
    val options: Map<String, String>? = null,
    val title: String? = null,
    val summary: String? = null,
    val category: String? = null,
    val order: Int = 0,
    val controlType: String? = null,
    val action: PluginSettingAction? = null,
    val minimumNumber: Double? = null,
    val maximumNumber: Double? = null,
)

object PluginSettingsSchemaRegistry {
    // Map of pluginPrefName (e.g. "CineStream_") to a map of keys and their schemas
    val schemas = ConcurrentHashMap<String, ConcurrentHashMap<String, PluginSettingSchema>>()

    // Observable flow to trigger UI updates when new settings are detected
    val schemaUpdates = MutableStateFlow(0)

    /** The plugin whose settings callback is currently being evaluated on this thread. */
    private val activePluginName = ThreadLocal<String?>()

    fun activePluginPrefName(): String? = activePluginName.get()?.let { "${it.removeSuffix("_")}_" }

    fun <T> withPlugin(pluginName: String, block: () -> T): T {
        val previous = activePluginName.get()
        activePluginName.set(pluginName.removeSuffix("_"))
        return try {
            block()
        } finally {
            if (previous == null) activePluginName.remove() else activePluginName.set(previous)
        }
    }

    fun registerPrefName(pluginPrefName: String) {
        schemas.getOrPut(pluginPrefName) { ConcurrentHashMap() }
    }

    @JvmOverloads
    fun register(
        pluginPrefName: String,
        key: String,
        type: String,
        defaultValue: Any?,
        isGlobal: Boolean = false,
        options: Map<String, String>? = null,
        title: String? = null,
        summary: String? = null,
        category: String? = null,
        order: Int = 0,
        controlType: String? = null,
        action: PluginSettingAction? = null,
        minimumNumber: Double? = null,
        maximumNumber: Double? = null,
    ) {
        val pluginMap = schemas.getOrPut(pluginPrefName) { ConcurrentHashMap() }

        val existing = pluginMap[key]
        val registeredAction = action?.let { callback ->
            val owner = activePluginName.get() ?: pluginPrefName.removeSuffix("_")
            PluginSettingAction { withPlugin(owner) { callback.invoke() } }
        }
        val updated = PluginSettingSchema(
            pluginPrefName = pluginPrefName,
            key = key,
            type = type,
            defaultValue = defaultValue ?: existing?.defaultValue,
            isGlobal = isGlobal,
            options = options ?: existing?.options,
            title = title?.takeIf(String::isNotBlank) ?: existing?.title,
            summary = summary?.takeIf(String::isNotBlank) ?: existing?.summary,
            category = category?.takeIf(String::isNotBlank) ?: existing?.category,
            order = if (order != 0 || existing == null) order else existing.order,
            controlType = controlType ?: existing?.controlType,
            action = registeredAction ?: existing?.action,
            minimumNumber = minimumNumber ?: existing?.minimumNumber,
            maximumNumber = maximumNumber ?: existing?.maximumNumber,
        )
        if (existing != updated) {
            pluginMap[key] = updated
            schemaUpdates.value++
        }
    }

    /**
     * Keys that represent internal cache, cookie state, or serialized internal ordering
     * rather than human-configurable settings.
     */
    fun isHiddenKey(key: String): Boolean {
        val lower = key.lowercase()
        return lower.startsWith("_") ||
            lower.contains("__") ||
            lower.contains("cookie") ||
            lower.contains("csrf") ||
            lower.contains("clearance") ||
            lower.contains("session_token") ||
            lower.endsWith("_order") ||
            lower.endsWith("_seen") ||
            lower.endsWith("_cache") ||
            lower.endsWith("_history") ||
            lower.endsWith("_index")
    }

    fun resolvePrefName(prefName: String, pluginName: String? = null): String {
        val candidates = buildList {
            add(prefName)
            add(if (prefName.endsWith("_")) prefName else "${prefName}_")
            pluginName?.removeSuffix("_")?.takeIf(String::isNotBlank)?.let { add("${it}_") }
        }.distinct()

        // Preference namespaces are identity boundaries. Fuzzy substring matching could show
        // one extension's settings on another extension's page when names overlap.
        return candidates.firstOrNull { !schemas[it].isNullOrEmpty() } ?: candidates.first()
    }

    fun getSettingsForPlugin(pluginPrefName: String, pluginName: String? = null): List<PluginSettingSchema> {
        val resolved = resolvePrefName(pluginPrefName, pluginName)
        val schemasList = schemas[resolved]?.values?.toList() ?: emptyList()
        return schemasList.filterNot { isHiddenKey(it.key) }
    }

    fun hasSettings(pluginPrefName: String, pluginName: String? = null): Boolean {
        val resolved = resolvePrefName(pluginPrefName, pluginName)
        val schemasList = schemas[resolved]?.values?.toList() ?: emptyList()
        return schemasList.any { !isHiddenKey(it.key) }
    }
}
