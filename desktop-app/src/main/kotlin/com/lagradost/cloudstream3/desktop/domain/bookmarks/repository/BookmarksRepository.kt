package com.lagradost.cloudstream3.desktop.domain.bookmarks.repository

import com.lagradost.common.storage.DesktopBookmark
import kotlinx.coroutines.flow.StateFlow

interface BookmarksRepository {
    fun subscribeAll(): StateFlow<Map<String, DesktopBookmark>>
    suspend fun getAll(profileId: Int? = null): List<DesktopBookmark>
    suspend fun getById(id: String, profileId: Int? = null): DesktopBookmark?
    suspend fun isBookmarked(id: String, profileId: Int? = null): Boolean
    suspend fun addBookmark(bookmark: DesktopBookmark, profileId: Int? = null)
    suspend fun removeBookmark(id: String, profileId: Int? = null)
    suspend fun refresh(profileId: Int? = null)
}
