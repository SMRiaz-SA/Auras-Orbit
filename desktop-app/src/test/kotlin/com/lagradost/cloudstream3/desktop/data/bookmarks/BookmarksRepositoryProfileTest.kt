package com.lagradost.cloudstream3.desktop.data.bookmarks

import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookmarksRepositoryProfileTest {
    @Test
    fun switchingProfilesReloadsBookmarksWithoutCarryingThePreviousProfileCache() = runBlocking {
        ProfileManager.init()
        val originalProfileId = ProfileManager.activeProfileId
        val suffix = UUID.randomUUID().toString().take(8)
        val firstProfile = ProfileManager.createProfile("bookmark-a-$suffix")
        val secondProfile = ProfileManager.createProfile("bookmark-b-$suffix")
        val firstBookmark = DesktopBookmark(
            id = "p-fixture-$suffix-a",
            name = "Bookmark A $suffix",
            url = "https://fixture.invalid/a/$suffix",
            apiName = "Fixture",
            posterUrl = null,
        )
        val secondBookmark = DesktopBookmark(
            id = "fixture-$suffix-b",
            name = "Bookmark B $suffix",
            url = "https://fixture.invalid/b/$suffix",
            apiName = "Fixture",
            posterUrl = null,
        )
        val firstStoredId = firstBookmark.id
        val secondStoredId = secondBookmark.id
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        try {
            DesktopDataStore.addBookmark(firstBookmark, firstProfile.id)
            DesktopDataStore.addBookmark(secondBookmark, secondProfile.id)
            check(ProfileManager.switchProfile(firstProfile.id))

            val repository = BookmarksRepositoryImpl(scope)
            val firstProfileBookmarks = withTimeout(5_000) {
                repository.subscribeAll().first { it.keys == setOf(firstStoredId) }
            }
            assertEquals(firstBookmark.name, firstProfileBookmarks.getValue(firstStoredId).name)
            assertTrue(repository.isBookmarked(firstBookmark.id))
            repository.addBookmark(firstBookmark.copy(watchType = 3))
            assertEquals(3, repository.getById(firstBookmark.id)?.watchType)

            check(ProfileManager.switchProfile(secondProfile.id))
            val secondProfileBookmarks = withTimeout(5_000) {
                repository.subscribeAll().first { it.keys == setOf(secondStoredId) }
            }
            assertEquals(secondBookmark.name, secondProfileBookmarks.getValue(secondStoredId).name)
            assertFalse(firstStoredId in secondProfileBookmarks)
            assertFalse(repository.isBookmarked(firstBookmark.id))

            val delayedBookmark = firstBookmark.copy(id = "p-delayed-$suffix")
            repository.addBookmark(delayedBookmark, firstProfile.id)
            assertTrue(DesktopDataStore.getBookmarks(firstProfile.id).any { it.id == delayedBookmark.id })
            assertFalse(DesktopDataStore.getBookmarks(secondProfile.id).any { it.id == delayedBookmark.id })
            assertEquals(setOf(secondStoredId), repository.subscribeAll().value.keys)

            check(ProfileManager.deleteProfile(firstProfile.id))
            val afterDeletionBookmark = delayedBookmark.copy(id = "p-after-delete-$suffix")
            repository.addBookmark(afterDeletionBookmark, firstProfile.id)
            assertFalse(DesktopDataStore.getBookmarks(firstProfile.id).any { it.id == afterDeletionBookmark.id })
        } finally {
            scope.cancel()
            if (ProfileManager.activeProfileId != originalProfileId) ProfileManager.switchProfile(originalProfileId)
            ProfileManager.deleteProfile(firstProfile.id)
            ProfileManager.deleteProfile(secondProfile.id)
        }
    }
}
