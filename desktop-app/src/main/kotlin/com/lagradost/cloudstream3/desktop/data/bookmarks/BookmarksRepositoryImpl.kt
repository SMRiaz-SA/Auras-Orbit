package com.lagradost.cloudstream3.desktop.data.bookmarks

import com.lagradost.cloudstream3.desktop.domain.bookmarks.repository.BookmarksRepository
import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BookmarksRepositoryImpl(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : BookmarksRepository {

    private val _bookmarksFlow = MutableStateFlow<Map<String, DesktopBookmark>>(emptyMap())
    private val initMutex = Mutex()

    @Volatile
    private var loadedProfileId: Int? = null

    init {
        scope.launch {
            ProfileManager.activeProfile.collect { profile ->
                synchronized(ProfileManager) {
                    if (ProfileManager.activeProfileId == profile.id && loadedProfileId != profile.id) {
                        loadedProfileId = null
                        _bookmarksFlow.value = emptyMap()
                    }
                }
                refreshForProfile(profile.id)
            }
        }
    }

    private suspend fun loadFromStorage() = withContext(Dispatchers.IO) {
        refreshForProfile(ProfileManager.activeProfileId)
    }

    private suspend fun bookmarksForProfile(profileId: Int): Map<String, DesktopBookmark>? {
        refreshForProfile(profileId)
        return if (ProfileManager.activeProfileId == profileId && loadedProfileId == profileId) {
            _bookmarksFlow.value
        } else {
            null
        }
    }

    private suspend fun refreshForProfile(profileId: Int, force: Boolean = false) = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (ProfileManager.activeProfileId != profileId || (!force && loadedProfileId == profileId)) return@withLock

            val bookmarks = DesktopDataStore.getBookmarks(profileId).associateBy { it.id }
            // A slow read for the old profile must never replace the active profile's cache.
            synchronized(ProfileManager) {
                if (ProfileManager.activeProfileId == profileId) {
                    _bookmarksFlow.value = bookmarks
                    loadedProfileId = profileId
                }
            }
        }
    }

    override fun subscribeAll(): StateFlow<Map<String, DesktopBookmark>> {
        if (loadedProfileId != ProfileManager.activeProfileId) {
            scope.launch {
                loadFromStorage()
            }
        }
        return _bookmarksFlow.asStateFlow()
    }

    override suspend fun getAll(profileId: Int?): List<DesktopBookmark> {
        val requestedProfileId = profileId ?: ProfileManager.activeProfileId
        return withContext(Dispatchers.IO) {
            bookmarksForProfile(requestedProfileId)?.values?.toList().orEmpty()
        }
    }

    override suspend fun getById(id: String, profileId: Int?): DesktopBookmark? {
        val requestedProfileId = profileId ?: ProfileManager.activeProfileId
        return withContext(Dispatchers.IO) {
            bookmarksForProfile(requestedProfileId)?.get(id)
        }
    }

    override suspend fun isBookmarked(id: String, profileId: Int?): Boolean {
        val requestedProfileId = profileId ?: ProfileManager.activeProfileId
        return withContext(Dispatchers.IO) {
            bookmarksForProfile(requestedProfileId)?.containsKey(id) == true
        }
    }

    override suspend fun addBookmark(bookmark: DesktopBookmark, profileId: Int?) {
        val requestedProfileId = profileId ?: ProfileManager.activeProfileId
        withContext(Dispatchers.IO) {
            DesktopDataStore.addBookmark(bookmark, requestedProfileId)
            refreshForProfile(requestedProfileId, force = true)
        }
    }

    override suspend fun removeBookmark(id: String, profileId: Int?) {
        val requestedProfileId = profileId ?: ProfileManager.activeProfileId
        withContext(Dispatchers.IO) {
            DesktopDataStore.removeBookmark(id, requestedProfileId)
            refreshForProfile(requestedProfileId, force = true)
        }
    }

    override suspend fun refresh(profileId: Int?) {
        val requestedProfileId = profileId ?: ProfileManager.activeProfileId
        withContext(Dispatchers.IO) {
            refreshForProfile(requestedProfileId, force = true)
        }
    }
}
