package com.lagradost.cloudstream3.desktop.profile

import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object ProfileManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private const val PREF_PROFILES = "cs_desktop_profiles_v1"
    private const val PREF_ACTIVE_ID = "cs_desktop_active_profile_id_v1"
    private const val PREF_SHOW_PICKER_STARTUP = "cs_desktop_show_profile_picker_on_startup"

    private val defaultProfile = Profile(
        id = 0,
        name = "Main",
        avatarColorIndex = 0,
        pinCode = null,
        isKids = false,
    )

    private val _profiles = MutableStateFlow<List<Profile>>(listOf(defaultProfile))
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow(defaultProfile)
    val activeProfile: StateFlow<Profile> = _activeProfile.asStateFlow()

    val activeProfileId: Int
        get() = _activeProfile.value.id

    private const val PREF_AUTO_SIGN_IN = "cs_desktop_auto_sign_in_startup"

    private val _isPickerOnStartup = MutableStateFlow(true)
    val isPickerOnStartup: StateFlow<Boolean> = _isPickerOnStartup.asStateFlow()

    private val _autoSignIn = MutableStateFlow(false)
    val autoSignIn: StateFlow<Boolean> = _autoSignIn.asStateFlow()

    private val _welcomeToast = MutableStateFlow<Profile?>(null)
    val welcomeToast: StateFlow<Profile?> = _welcomeToast.asStateFlow()

    fun triggerWelcomeToast(profile: Profile) {
        _welcomeToast.value = profile
    }

    fun dismissWelcomeToast() {
        _welcomeToast.value = null
    }

    fun init() {
        try {
            val savedProfilesJson = DesktopDataStore.getKey<String>(PREF_PROFILES)
            val loadedProfiles: List<Profile>? = if (!savedProfilesJson.isNullOrBlank()) {
                mapper.readValue(savedProfilesJson, object : TypeReference<List<Profile>>() {})
            } else {
                null
            }

            val validProfiles = if (loadedProfiles.isNullOrEmpty()) {
                listOf(defaultProfile)
            } else {
                loadedProfiles.map {
                    it.copy(
                        pinCode = ProfilePin.protect(it.pinCode),
                        pinLength = it.pinLength ?: ProfilePin.inputLength(it.pinCode),
                    )
                }
            }
            _profiles.value = validProfiles

            val activeId = DesktopDataStore.getKey<Int>(PREF_ACTIVE_ID) ?: 0
            val resolvedActive = validProfiles.find { it.id == activeId } ?: validProfiles.first()
            _activeProfile.value = resolvedActive

            val autoSignInPref = DesktopDataStore.getKey<Boolean>(PREF_AUTO_SIGN_IN)
            val pickerPref = DesktopDataStore.getKey<Boolean>(PREF_SHOW_PICKER_STARTUP)
            val resolvedAutoSignIn = when {
                autoSignInPref != null -> autoSignInPref
                pickerPref != null -> !pickerPref
                else -> false
            }
            _autoSignIn.value = resolvedAutoSignIn
            _isPickerOnStartup.value = !resolvedAutoSignIn

            try {
                saveProfilesInternal()
            } catch (failure: Exception) {
                // A migration write failure must not replace the loaded profile list with Main.
                AppLogger.e("Profile migration could not be saved; retaining the loaded profiles", failure)
            }
            AppLogger.i("ProfileManager initialized with ${validProfiles.size} profiles. Active: '${resolvedActive.name}' (ID: ${resolvedActive.id}, AutoSignIn: $resolvedAutoSignIn)")
        } catch (e: Exception) {
            AppLogger.e("Failed to initialize ProfileManager", e)
            throw IllegalStateException("Profiles could not be loaded; existing profile data was preserved", e)
        }
    }

    @Synchronized
    private fun saveProfilesInternal(
        currentProfiles: List<Profile> = _profiles.value,
        activeProfile: Profile = _activeProfile.value,
        currentAutoSignIn: Boolean = _autoSignIn.value,
    ) {
        DesktopDataStore.setKeys(
            mapOf(
                PREF_PROFILES to mapper.writeValueAsString(currentProfiles),
                PREF_ACTIVE_ID to activeProfile.id,
                PREF_SHOW_PICKER_STARTUP to !currentAutoSignIn,
                PREF_AUTO_SIGN_IN to currentAutoSignIn,
            ),
        )
        _profiles.value = currentProfiles
        _activeProfile.value = activeProfile
        _autoSignIn.value = currentAutoSignIn
        _isPickerOnStartup.value = !currentAutoSignIn
    }

    @Synchronized
    fun setAutoSignIn(enabled: Boolean) {
        saveProfilesInternal(currentAutoSignIn = enabled)
    }

    fun setShowPickerOnStartup(enabled: Boolean) {
        setAutoSignIn(!enabled)
    }

    @Synchronized
    fun createProfile(
        name: String,
        avatarColorIndex: Int = 0,
        customAvatarPath: String? = null,
        pinCode: String? = null,
        isKids: Boolean = false,
    ): Profile {
        // A random positive ID avoids inheriting orphaned namespaces from old releases too.
        var nextId: Int
        do {
            nextId = java.security.SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1
        } while (_profiles.value.any { it.id == nextId } || DesktopDataStore.isProfileDeleted(nextId) ||
            DesktopDataStore.rawKeyCache.keys.any { it.startsWith("$nextId/") || it.endsWith("_profile_$nextId") }
        )
        val newProfile = Profile(
            id = nextId,
            name = name.trim().take(16).ifEmpty { "Profile $nextId" },
            avatarColorIndex = avatarColorIndex,
            customAvatarPath = customAvatarPath?.trim()?.takeIf { it.isNotEmpty() },
            pinCode = ProfilePin.protect(pinCode),
            pinLength = ProfilePin.inputLength(pinCode),
            isKids = isKids,
        )
        saveProfilesInternal(currentProfiles = _profiles.value + newProfile)
        AppLogger.i("Created new profile '${newProfile.name}' (ID: ${newProfile.id})")
        return newProfile
    }

    private fun cleanupOldAvatarFile(oldPath: String?, newPath: String?) {
        if (!oldPath.isNullOrBlank() && oldPath != newPath) {
            scope.launch(Dispatchers.IO) {
                try {
                    val f = java.io.File(oldPath).canonicalFile
                    val root = java.io.File(com.lagradost.common.platform.PlatformPaths.appDataDir, "profiles/avatars").canonicalFile
                    if (f.toPath().startsWith(root.toPath()) && f != root && f.isFile) f.delete()
                } catch (_: Exception) {}
            }
        }
    }

    @Synchronized
    fun updateProfile(value: Profile) {
        val old = _profiles.value.find { it.id == value.id }
        val pinLength = when {
            value.pinCode.isNullOrBlank() -> null
            value.pinCode == old?.pinCode -> old.pinLength ?: ProfilePin.inputLength(value.pinCode)
            value.pinCode.startsWith("pbkdf2-v1:") -> value.pinLength?.takeIf { it in 4..6 }
            else -> ProfilePin.inputLength(value.pinCode)
        }
        val updated = value.copy(pinCode = ProfilePin.protect(value.pinCode), pinLength = pinLength)
        require(old != null) { "Profile no longer exists" }
        saveProfilesInternal(
            currentProfiles = _profiles.value.map { if (it.id == updated.id) updated else it },
            activeProfile = if (_activeProfile.value.id == updated.id) updated else _activeProfile.value,
        )
        if (old.customAvatarPath != updated.customAvatarPath) {
            cleanupOldAvatarFile(old.customAvatarPath, updated.customAvatarPath)
        }
        AppLogger.i("Updated profile '${updated.name}' (ID: ${updated.id})")
    }

    @Synchronized
    fun deleteProfile(id: Int): Boolean {
        if (_profiles.value.size <= 1) {
            AppLogger.i("Cannot delete the only remaining profile.")
            return false
        }
        val target = _profiles.value.find { it.id == id } ?: return false
        val remaining = _profiles.value.filter { it.id != id }
        val nextActive = if (_activeProfile.value.id == id) remaining.first() else _activeProfile.value
        DesktopDataStore.deleteProfileData(
            id,
            mapOf(
                PREF_PROFILES to mapper.writeValueAsString(remaining),
                PREF_ACTIVE_ID to nextActive.id,
            ),
        )
        cleanupOldAvatarFile(target.customAvatarPath, null)
        _profiles.update { list -> list.filter { it.id != id } }

        if (_activeProfile.value.id == id) {
            _activeProfile.value = _profiles.value.first()
            DesktopDataStore.notifyHistoryChanged(force = true)
        }
        AppLogger.i("Deleted profile '${target.name}' (ID: $id)")
        return true
    }

    @Synchronized
    fun switchProfile(id: Int, pin: String? = null): Boolean {
        val target = _profiles.value.find { it.id == id } ?: return false
        if (!ProfilePin.verify(target.pinCode, pin)) {
            AppLogger.i("Failed PIN verification for profile '${target.name}'")
            return false
        }
        DesktopDataStore.setKey(PREF_ACTIVE_ID, target.id)
        _activeProfile.value = target
        DesktopDataStore.notifyHistoryChanged(force = true)
        com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.reloadFromDataStore()
        com.lagradost.cloudstream3.desktop.metadata.MetadataConfig.reloadFromDataStore()
        triggerWelcomeToast(target)
        AppLogger.i("Switched active profile to '${target.name}' (ID: ${target.id})")
        return true
    }

    fun verifyPin(id: Int, pin: String): Boolean {
        val target = _profiles.value.find { it.id == id } ?: return false
        return ProfilePin.verify(target.pinCode, pin)
    }
}
