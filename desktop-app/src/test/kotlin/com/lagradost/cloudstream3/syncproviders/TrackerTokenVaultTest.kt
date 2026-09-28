package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.common.db.DatabaseFactory
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerTokenVaultTest {
    @Test
    fun `DPAPI protects tracker tokens at rest and round trips for the current user`() {
        assumeTrue(TrackerTokenVault.isSupported(), "DPAPI tests run on Windows")
        val tokenJson = "{\"accessToken\":\"test-access-token\",\"refreshToken\":\"test-refresh-token\"}"

        val protected = TrackerTokenVault.protect(tokenJson)

        assertFalse(protected.contains("test-access-token"))
        assertFalse(protected.contains("test-refresh-token"))
        assertEquals(tokenJson, TrackerTokenVault.unprotect(protected))
    }

    @Test
    fun `failed legacy credential migration preserves plaintext until protected write commits`() {
        assumeTrue(TrackerTokenVault.isSupported(), "DPAPI migration tests run on Windows")
        ProfileManager.init()
        val originalProfileId = ProfileManager.activeProfileId
        val testProfile = ProfileManager.createProfile("credential-migration-${UUID.randomUUID().toString().take(8)}")
        val legacyKey = "auth_tokens_mal_profile_${testProfile.id}"
        val protectedKey = "tracker_credentials_v1_$legacyKey"
        val triggerName = "fail_tracker_credential_${UUID.randomUUID().toString().replace("-", "")}"
        val databasePath = File(PlatformPaths.dataDir, "cloudstream.db").absolutePath
        val queries = DatabaseFactory.database.cloudstreamDBQueries

        try {
            check(ProfileManager.switchProfile(testProfile.id))
            DesktopDataStore.setKey(
                legacyKey,
                arrayOf(
                    AuthData(
                        user = AuthUser(name = "Synthetic", id = 17701),
                        token = AuthToken(accessToken = "migration-only-secret"),
                    ),
                ),
            )

            DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TRIGGER $triggerName BEFORE INSERT ON KeyValueStore " +
                            "WHEN NEW.key = '$protectedKey' BEGIN SELECT RAISE(ABORT, 'injected credential migration failure'); END",
                    )
                }
            }

            val failedRead = AccountManager.accounts("mal")
            assertEquals(0, failedRead.size, "failed protection must not expose the legacy credential as an active account")
            assertEquals("migration-only-secret", DesktopDataStore.getKey<Array<AuthData>>(legacyKey)?.single()?.token?.accessToken)
            assertNull(DesktopDataStore.getKey<String>(protectedKey))

            DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                connection.createStatement().use { it.execute("DROP TRIGGER IF EXISTS $triggerName") }
            }

            val recovered = AccountManager.accounts("mal")
            assertEquals("migration-only-secret", recovered.single().token.accessToken)
            val protected = DesktopDataStore.getKey<String>(protectedKey)
            assertNotNull(protected)
            assertFalse(protected.contains("migration-only-secret"))
            assertNull(DesktopDataStore.getKey<Array<AuthData>>(legacyKey))
            assertTrue(TrackerTokenVault.unprotect(protected).contains("migration-only-secret"))
            val persistedProtected = queries.selectKeyValue(protectedKey).executeAsOneOrNull()
            assertNotNull(persistedProtected)
            assertEquals(DesktopDataStore.rawKeyCache[protectedKey], persistedProtected)
            assertFalse(persistedProtected.contains("migration-only-secret"))
        } finally {
            DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                connection.createStatement().use { it.execute("DROP TRIGGER IF EXISTS $triggerName") }
            }
            DesktopDataStore.setKeys(emptyMap(), setOf(legacyKey, protectedKey))
            if (ProfileManager.activeProfileId != originalProfileId) ProfileManager.switchProfile(originalProfileId)
            ProfileManager.deleteProfile(testProfile.id)
        }
    }
}
