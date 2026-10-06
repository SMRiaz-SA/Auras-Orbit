package com.lagradost.cloudstream3.desktop.data.customlists

import com.lagradost.cloudstream3.desktop.domain.customlists.repository.CustomListsRepository
import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.common.storage.DesktopCustomList
import com.lagradost.common.storage.DesktopCustomListsSnapshot
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class CustomListsRepositoryImpl : CustomListsRepository {
    override fun subscribeActive(): Flow<DesktopCustomListsSnapshot> =
        combine(ProfileManager.activeProfile, DesktopDataStore.customListUpdates) { profile, _ ->
            DesktopCustomListsSnapshot(
                lists = DesktopDataStore.getCustomLists(profile.id),
                items = DesktopDataStore.getCustomListItems(profile.id),
            )
        }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun getSnapshot(profileId: Int): DesktopCustomListsSnapshot = withContext(Dispatchers.IO) {
        DesktopCustomListsSnapshot(
            lists = DesktopDataStore.getCustomLists(profileId),
            items = DesktopDataStore.getCustomListItems(profileId),
        )
    }

    override suspend fun create(name: String, profileId: Int): DesktopCustomList = withContext(Dispatchers.IO) {
        DesktopDataStore.createCustomList(name, profileId)
    }

    override suspend fun rename(listId: String, name: String, profileId: Int): Boolean = withContext(Dispatchers.IO) {
        DesktopDataStore.renameCustomList(listId, name, profileId)
    }

    override suspend fun reorder(listIds: List<String>, profileId: Int): Boolean = withContext(Dispatchers.IO) {
        DesktopDataStore.reorderCustomLists(listIds, profileId)
    }

    override suspend fun delete(listId: String, profileId: Int): Boolean = withContext(Dispatchers.IO) {
        DesktopDataStore.deleteCustomList(listId, profileId)
    }

    override suspend fun setPinnedToHome(listId: String, pinned: Boolean, profileId: Int): Boolean =
        withContext(Dispatchers.IO) {
            DesktopDataStore.setCustomListShownOnHome(listId, pinned, profileId)
        }

    override suspend fun setBookmark(listId: String, bookmarkId: String, included: Boolean, profileId: Int): Boolean =
        withContext(Dispatchers.IO) {
            DesktopDataStore.setBookmarkInCustomList(listId, bookmarkId, included, profileId)
        }
}
