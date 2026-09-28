package com.lagradost.common.storage

import com.lagradost.common.db.DatabaseFactory
import com.lagradost.common.platform.PlatformPaths
import java.io.File
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistenceRegressionTest {
    @Test
    fun acknowledgedMutationsAreDurableAndOrdered() {
        DesktopDataStore.setKey("ordered-test", "A")
        DesktopDataStore.setKey("ordered-test", "B")
        DesktopDataStore.removeKey("ordered-test")
        DesktopDataStore.setKey("ordered-test", "C")
        assertEquals("\"C\"", DatabaseFactory.database.cloudstreamDBQueries.selectKeyValue("ordered-test").executeAsOne())
    }

    @Test
    fun credentialReplacementAndProfileDeletionPreserveOtherProfiles() {
        DesktopDataStore.setKey("auth_tokens_mal_profile_81", "legacy")
        DesktopDataStore.setKeys(mapOf("tracker_credentials_v1_auth_tokens_mal_profile_81" to "encrypted"), setOf("auth_tokens_mal_profile_81"))
        assertNull(DatabaseFactory.database.cloudstreamDBQueries.selectKeyValue("auth_tokens_mal_profile_81").executeAsOneOrNull())
        DesktopDataStore.setKey("82/theme", "keep")
        DesktopDataStore.setKey("81/theme", "remove")
        DesktopDataStore.deleteProfileData(81, emptyMap())
        assertNull(DesktopDataStore.getKey<String>("81/theme"))
        assertNull(DesktopDataStore.getKey<String>("tracker_credentials_v1_auth_tokens_mal_profile_81"))
        assertEquals("keep", DesktopDataStore.getKey<String>("82/theme"))
        assertFailsWith<IllegalStateException> { DesktopDataStore.setKey("81/theme", "late write") }
        assertFailsWith<IllegalStateException> { DesktopDataStore.setKey("auth_tokens_mal_profile_81", "late token") }
    }

    @Test
    fun deletedProfileRejectsLateBookmarkAndHistoryWritesWithoutTouchingAnotherProfile() {
        val suffix = UUID.randomUUID().toString().take(8)
        val deletedProfileId = (UUID.randomUUID().hashCode() and 0x3FFFFFFF) + 1
        val retainedProfileId = deletedProfileId + 1
        val deletedBookmark = DesktopBookmark(
            id = "p-content-$suffix",
            name = "Deleted profile bookmark",
            url = "https://fixture.invalid/deleted/$suffix",
            apiName = "Fixture",
            posterUrl = null,
        )
        val retainedBookmark = DesktopBookmark(
            id = "retained-$suffix",
            name = "Retained profile bookmark",
            url = "https://fixture.invalid/retained/$suffix",
            apiName = "Fixture",
            posterUrl = null,
        )
        val lateBookmark = deletedBookmark.copy(id = "p-late-$suffix", name = "Late bookmark")
        val deletedHistory = historyForProfile(deletedProfileId, suffix, "deleted")
        val retainedHistory = historyForProfile(retainedProfileId, suffix, "retained")
        val lateHistory = historyForProfile(deletedProfileId, suffix, "late")

        try {
            DesktopDataStore.addBookmark(deletedBookmark, deletedProfileId)
            DesktopDataStore.addBookmark(retainedBookmark, retainedProfileId)
            DesktopDataStore.setLastWatched(deletedHistory)
            DesktopDataStore.setLastWatched(retainedHistory)
            assertEquals(deletedBookmark.id, DesktopDataStore.getBookmarks(deletedProfileId).single().id)
            assertEquals(retainedBookmark.id, DesktopDataStore.getBookmarks(retainedProfileId).single().id)

            DesktopDataStore.deleteProfileData(deletedProfileId, emptyMap())
            DesktopDataStore.addBookmark(lateBookmark, deletedProfileId)
            DesktopDataStore.setLastWatched(lateHistory)
            DesktopDataStore.setMultipleLastWatched(listOf(lateHistory.copy(position = 9_000)))

            val queries = DatabaseFactory.database.cloudstreamDBQueries
            assertNull(queries.selectBookmarkById("p${deletedProfileId}_${deletedBookmark.id}").executeAsOneOrNull())
            assertNull(queries.selectBookmarkById("p${deletedProfileId}_${lateBookmark.id}").executeAsOneOrNull())
            assertNull(DesktopDataStore.getLastWatched(deletedHistory.parentId))
            assertNull(DesktopDataStore.getLastWatched(lateHistory.parentId))
            assertEquals(retainedBookmark.id, DesktopDataStore.getBookmarks(retainedProfileId).single().id)
            assertEquals(retainedHistory.showName, DesktopDataStore.getLastWatched(retainedHistory.parentId)?.showName)
        } finally {
            DesktopDataStore.deleteProfileData(deletedProfileId, emptyMap())
            DesktopDataStore.deleteProfileData(retainedProfileId, emptyMap())
        }
    }

    @Test
    fun failedPluginTrustWriteDoesNotChangePersistedOrCachedTrust() {
        val databasePath = File(PlatformPaths.dataDir, "cloudstream.db").absolutePath
        val trustedName = "durability-fixture-${UUID.randomUUID()}"
        val triggerName = "fail_plugin_trust_fixture_insert"
        val queries = DatabaseFactory.database.cloudstreamDBQueries

        try {
            DesktopDataStore.setPluginTrusted(trustedName, true)
            val persistedBeforeFailure = queries.selectKeyValue("trusted_plugins_set").executeAsOne()
            val cachedBeforeFailure = DesktopDataStore.rawKeyCache["trusted_plugins_set"]
            assertNotNull(cachedBeforeFailure)
            assertTrue(DesktopDataStore.getTrustedPlugins().contains(trustedName))

            DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TRIGGER $triggerName BEFORE INSERT ON KeyValueStore " +
                            "WHEN NEW.key = 'trusted_plugins_set' BEGIN SELECT RAISE(ABORT, 'injected trust write failure'); END",
                    )
                }
            }

            val failure = runCatching { DesktopDataStore.setPluginTrusted(trustedName, false) }.exceptionOrNull()
            assertNotNull(failure, "trust write failure must reach the caller")
            assertEquals(persistedBeforeFailure, queries.selectKeyValue("trusted_plugins_set").executeAsOne())
            assertEquals(cachedBeforeFailure, DesktopDataStore.rawKeyCache["trusted_plugins_set"])
            assertTrue(DesktopDataStore.getTrustedPlugins().contains(trustedName))
        } finally {
            DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                connection.createStatement().use { it.execute("DROP TRIGGER IF EXISTS $triggerName") }
            }
            DesktopDataStore.setPluginTrusted(trustedName, false)
            assertFalse(DesktopDataStore.getTrustedPlugins().contains(trustedName))
        }
    }

    private fun historyForProfile(profileId: Int, suffix: String, label: String) = WatchHistory(
        parentId = DesktopDataStore.watchHistoryId("Fixture", "https://fixture.invalid/$label/$suffix", profileId = profileId),
        showName = "History $label $suffix",
        showUrl = "https://fixture.invalid/$label/$suffix",
        apiName = "Fixture",
        posterUrl = null,
        episodeThumbnailUrl = null,
        screenshotUrl = null,
        episode = 1,
        season = 1,
        episodeId = "episode-$label",
        position = 3_000,
        duration = 10_000,
    )
}
