package com.lagradost.cloudstream3.desktop.ui.screens.settings

import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.base.UiEffect
import com.lagradost.cloudstream3.desktop.ui.base.UiEvent
import com.lagradost.cloudstream3.desktop.ui.base.UiState
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.PluginSettingSchema
import com.lagradost.common.storage.PluginSettingsSchemaRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class PluginSettingsUiState(
    val pluginName: String = "",
    val prefName: String = "",
    val activePrefName: String = "",
    val settings: List<PluginSettingSchema> = emptyList(),
    val currentValues: Map<String, Any?> = emptyMap(),
    val originalValues: Map<String, Any?> = emptyMap(),
    val hasChanged: Boolean = false,
    val isSaving: Boolean = false,
    val closeWhenSaved: Boolean = false,
    val saveError: String? = null,
    val isLoading: Boolean = true,
) : UiState

sealed interface PluginSettingsUiEvent : UiEvent {
    data class OnInit(val pluginName: String, val prefName: String) : PluginSettingsUiEvent
    data class OnSettingChanged(val schema: PluginSettingSchema, val newValue: Any?) : PluginSettingsUiEvent
    data class OnSchemaUpdated(val updateTick: Int) : PluginSettingsUiEvent
    data object OnApply : PluginSettingsUiEvent
}

sealed interface PluginSettingsUiEffect : UiEffect

class PluginSettingsViewModel : BaseMviViewModel<PluginSettingsUiState, PluginSettingsUiEvent, PluginSettingsUiEffect>(
    initialState = PluginSettingsUiState(),
) {

    override fun handleEvent(event: PluginSettingsUiEvent) {
        when (event) {
            is PluginSettingsUiEvent.OnInit -> {
                updateState { copy(pluginName = event.pluginName, prefName = event.prefName, isLoading = true) }
                reloadSettings()
            }
            is PluginSettingsUiEvent.OnSchemaUpdated -> reloadSettings()
            is PluginSettingsUiEvent.OnSettingChanged -> updateSetting(event.schema, event.newValue)
            PluginSettingsUiEvent.OnApply -> applySettings()
        }
    }

    private fun reloadSettings() {
        val state = uiState.value
        if (state.pluginName.isEmpty()) return

        val activePrefName = PluginSettingsSchemaRegistry.resolvePrefName(state.prefName, state.pluginName)
        val settings = PluginSettingsSchemaRegistry.getSettingsForPlugin(activePrefName, state.pluginName).sortedWith(
            compareBy<PluginSettingSchema> { it.order }
                .thenBy { it.title ?: it.key },
        )

        viewModelScope.launch(Dispatchers.IO) {
            val map = mutableMapOf<String, Any?>()
            settings.forEach { schema ->
                val fullKey = if (schema.isGlobal) schema.key else schema.pluginPrefName + schema.key
                val value = if (schema.isGlobal) {
                    DataStore.getKey<Any>(fullKey) ?: schema.defaultValue
                } else {
                    DesktopDataStore.getKey<Any>(fullKey) ?: schema.defaultValue
                }
                map[fullKey] = value
            }

            updateState {
                copy(
                    activePrefName = activePrefName,
                    settings = settings,
                    currentValues = map,
                    originalValues = map,
                    hasChanged = false,
                    isSaving = false,
                    closeWhenSaved = false,
                    saveError = null,
                    isLoading = false,
                )
            }
        }
    }

    private fun updateSetting(schema: PluginSettingSchema, newValue: Any?) {
        val fullKey = if (schema.isGlobal) schema.key else schema.pluginPrefName + schema.key

        updateState {
            val updatedValues = currentValues.toMutableMap()
            updatedValues[fullKey] = newValue
            copy(
                currentValues = updatedValues,
                hasChanged = updatedValues != originalValues,
                closeWhenSaved = false,
            )
        }
    }

    private fun applySettings() {
        val snapshot = uiState.value
        if (!snapshot.hasChanged) {
            updateState { copy(closeWhenSaved = true) }
            return
        }

        updateState { copy(isSaving = true, closeWhenSaved = false, saveError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val changed = snapshot.settings.mapNotNull { schema ->
                val fullKey = if (schema.isGlobal) schema.key else schema.pluginPrefName + schema.key
                if (snapshot.currentValues[fullKey] == snapshot.originalValues[fullKey]) {
                    null
                } else {
                    Triple(schema, fullKey, snapshot.currentValues[fullKey])
                }
            }

            try {
                val pluginValues = changed.filter { !it.first.isGlobal && it.third != null }
                    .associate { it.second to it.third }
                val pluginRemovals = changed.filter { !it.first.isGlobal && it.third == null }
                    .map { it.second }
                    .toSet()
                if (pluginValues.isNotEmpty() || pluginRemovals.isNotEmpty()) {
                    DesktopDataStore.setKeys(pluginValues, pluginRemovals)
                }

                changed.filter { it.first.isGlobal }.forEach { (_, fullKey, value) ->
                    if (value == null) DataStore.removeKey(fullKey) else DataStore.setKey(fullKey, value)
                }

                com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager.incrementSyncGeneration()
                updateState {
                    copy(
                        originalValues = snapshot.currentValues,
                        hasChanged = currentValues != snapshot.currentValues,
                        isSaving = false,
                        closeWhenSaved = currentValues == snapshot.currentValues,
                        saveError = null,
                    )
                }
            } catch (error: Throwable) {
                com.lagradost.common.logging.AppLogger.e("Could not save plugin settings", error)
                updateState { copy(isSaving = false, saveError = error.message ?: "Could not save plugin settings") }
            }
        }
    }
}
