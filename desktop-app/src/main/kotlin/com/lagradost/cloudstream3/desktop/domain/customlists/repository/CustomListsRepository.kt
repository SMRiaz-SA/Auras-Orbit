package com.lagradost.cloudstream3.desktop.domain.customlists.repository

import com.lagradost.common.storage.DesktopCustomList
import com.lagradost.common.storage.DesktopCustomListsSnapshot
import kotlinx.coroutines.flow.Flow

interface CustomListsRepository {
    fun subscribeActive(): Flow<DesktopCustomListsSnapshot>
    suspend fun getSnapshot(profileId: Int): DesktopCustomListsSnapshot
    suspend fun create(name: String, profileId: Int): DesktopCustomList
    suspend fun rename(listId: String, name: String, profileId: Int): Boolean
    suspend fun reorder(listIds: List<String>, profileId: Int): Boolean
    suspend fun delete(listId: String, profileId: Int): Boolean
    suspend fun setPinnedToHome(listId: String, pinned: Boolean, profileId: Int): Boolean
    suspend fun setBookmark(listId: String, bookmarkId: String, included: Boolean, profileId: Int): Boolean
}
