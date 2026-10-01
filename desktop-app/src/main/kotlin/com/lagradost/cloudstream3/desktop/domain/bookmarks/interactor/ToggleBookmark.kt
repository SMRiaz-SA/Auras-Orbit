package com.lagradost.cloudstream3.desktop.domain.bookmarks.interactor

import com.lagradost.cloudstream3.desktop.domain.bookmarks.repository.BookmarksRepository
import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.common.storage.DesktopBookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ToggleBookmark(
    private val repository: BookmarksRepository,
) {
    suspend fun saveBookmark(
        bookmark: DesktopBookmark,
        profileId: Int = ProfileManager.activeProfileId,
    ) {
        withContext(Dispatchers.IO) {
            repository.addBookmark(bookmark, profileId)
        }
    }

    suspend fun toggle(
        id: String,
        name: String,
        url: String,
        apiName: String,
        posterUrl: String?,
        watchType: Int,
        profileId: Int = ProfileManager.activeProfileId,
    ) {
        withContext(Dispatchers.IO) {
            val existing = repository.getById(id, profileId)
            if (existing != null && existing.watchType == watchType) {
                repository.removeBookmark(id, profileId)
            } else {
                val newBookmark = DesktopBookmark(
                    id = id,
                    name = name,
                    url = url,
                    apiName = apiName,
                    posterUrl = posterUrl,
                    watchType = watchType,
                    dateAdded = existing?.dateAdded ?: System.currentTimeMillis(),
                )
                repository.addBookmark(newBookmark, profileId)
            }
        }
    }
}
