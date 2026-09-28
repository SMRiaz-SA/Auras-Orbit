package com.lagradost.cloudstream3.desktop.profile

import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileSecurityTest {
    @Test
    fun pinVerifierIsSaltedAndCannotBeUsedAsThePin() {
        val a = ProfilePin.protect("1234")
        val b = ProfilePin.protect("1234")
        assertNotEquals(a, b)
        assertNotEquals("1234", a)
        assertTrue(ProfilePin.verify(a, "1234"))
        assertFalse(ProfilePin.verify(a, "9999"))
        assertFalse(ProfilePin.verify(a, a))
    }

    @Test
    fun pinLengthIsPreservedSeparatelyFromTheProtectedHash() {
        val pin4 = ProfilePin.protect("1234")
        val pin6 = ProfilePin.protect("123456")
        assertEquals(4, ProfilePin.inputLength("1234"))
        assertEquals(6, ProfilePin.inputLength("123456"))
        assertEquals(4, ProfilePin.expectedLength(pin4, 4))
        assertEquals(6, ProfilePin.expectedLength(pin6, 6))
        assertNull(ProfilePin.expectedLength(pin6, null))
    }

    @Test
    fun deletingAndRecreatingDoesNotRestoreCredentials() {
        ProfileManager.init()
        val removed = ProfileManager.createProfile("removed")
        val showUrl = "https://fixture.invalid/profile-${removed.id}"
        val bookmark = DesktopBookmark(
            id = "p-profile-${removed.id}",
            name = "Private bookmark",
            url = showUrl,
            apiName = "Fixture",
            posterUrl = null,
        )
        val history = WatchHistory(
            parentId = DesktopDataStore.watchHistoryId("Fixture", showUrl, profileId = removed.id),
            showName = "Private show",
            showUrl = showUrl,
            apiName = "Fixture",
            posterUrl = null,
            episodeThumbnailUrl = null,
            screenshotUrl = null,
            episode = 1,
            season = 1,
            episodeId = "episode-${removed.id}",
            position = 10,
            duration = 100,
        )
        DesktopDataStore.setKey("tracker_credentials_v1_auth_tokens_mal_profile_${removed.id}", "synthetic")
        DesktopDataStore.setProfileKey("theme", "private", removed.id)
        DesktopDataStore.addBookmark(bookmark, removed.id)
        DesktopDataStore.setLastWatched(history)
        assertTrue(ProfileManager.deleteProfile(removed.id))
        val fresh = ProfileManager.createProfile("fresh")
        assertNotEquals(removed.id, fresh.id)
        assertNull(DesktopDataStore.getProfileKey<String>("theme", fresh.id))
        assertNull(DesktopDataStore.getKey<String>("tracker_credentials_v1_auth_tokens_mal_profile_${removed.id}"))
        assertTrue(DesktopDataStore.getBookmarks(fresh.id).isEmpty())
        assertTrue(DesktopDataStore.getAllWatchHistory(fresh.id).isEmpty())
        assertNull(DesktopDataStore.getLastWatched(history.parentId))
        ProfileManager.deleteProfile(fresh.id)
    }
}
