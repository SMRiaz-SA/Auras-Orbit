package com.lagradost.cloudstream3.desktop.utils

object DesktopStrings {
    // Dock & Navigation
    const val HOME = "Home"
    const val SEARCH = "Search"
    const val LIBRARY = "Library"
    const val EXTENSIONS = "Extensions"
    const val SETTINGS = "Settings"

    // Home Screen
    const val HOME_START_TITLE = "Get started with extensions"
    const val HOME_START_DESCRIPTION = "Browse community repositories, then review and install compatible extensions. Adding a repository does not install extensions."
    const val HOME_BROWSE_PLUGINS = "Browse Plugins"
    const val HOME_STREMIO_CTA = "Set up Stremio add-ons"
    const val HOME_STREMIO_DESCRIPTION = "A separate manifest-based integration; it does not install provider extensions."

    // Extensions Screen
    const val INSTALLED = "Installed"
    const val BROWSE = "Browse"
    const val ADD_REPOSITORY = "Add Repository"
    const val REPOSITORY_URL = "Repository URL"
    const val REPOSITORY_NAME = "Repository Name (Optional)"
    const val ADD = "Add"
    const val CANCEL = "Cancel"
    const val NO_EXTENSIONS_INSTALLED = "No extensions installed"

    // Updates
    const val UPDATES = "Updates"
    const val NO_UPDATES = "No recent updates"
    const val PLUGIN_UPDATED = "updated to"
    const val PLUGIN_INSTALLED = "installed"

    // Details
    const val WATCH_TRAILER = "Watch Trailer"
    const val MORE_INFO = "More Info"
}

object DeveloperModeManager {
    private const val KEY = "developer_mode_enabled"

    private val _isEnabled = androidx.compose.runtime.mutableStateOf(
        com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(KEY) ?: false,
    )
    val isEnabled: Boolean get() = _isEnabled.value

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        com.lagradost.common.storage.DesktopDataStore.setKey(KEY, enabled)
    }

    fun toggle() {
        setEnabled(!isEnabled)
    }
}
