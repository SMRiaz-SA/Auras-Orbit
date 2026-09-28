package com.lagradost.cloudstream3.desktop.profile

import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ProfileCommitTest {
    @Test
    fun failedMutationsLeavePublishedProfilesAndAvatarsIntact() {
        ProfileManager.init()
        val avatar = File(PlatformPaths.appDataDir, "profiles/avatars/commit-fixture.txt").apply {
            parentFile.mkdirs()
            writeText("fixture")
        }
        val profile = ProfileManager.createProfile("commit fixture", customAvatarPath = avatar.absolutePath)
        val before = ProfileManager.profiles.value
        val active = ProfileManager.activeProfile.value
        val auto = ProfileManager.autoSignIn.value
        val connection = DriverManager.getConnection("jdbc:sqlite:${File(PlatformPaths.dataDir, "cloudstream.db")}")
        try {
            connection.createStatement().use {
                it.execute("CREATE TRIGGER fail_profile_commit BEFORE INSERT ON KeyValueStore WHEN NEW.key LIKE 'cs_desktop_%' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            }
            assertFails { ProfileManager.createProfile("must not appear") }
            assertFails { ProfileManager.updateProfile(profile.copy(name = "must not change", customAvatarPath = null)) }
            assertFails { ProfileManager.switchProfile(profile.id) }
            assertFails { ProfileManager.setAutoSignIn(!auto) }
            assertFails { ProfileManager.deleteProfile(profile.id) }
            assertEquals(before, ProfileManager.profiles.value)
            assertEquals(active, ProfileManager.activeProfile.value)
            assertEquals(auto, ProfileManager.autoSignIn.value)
            assertTrue(avatar.exists())
            ProfileManager.init()
            assertEquals(before, ProfileManager.profiles.value)
            assertEquals(active.id, DesktopDataStore.getKey<Int>("cs_desktop_active_profile_id_v1"))
        } finally {
            connection.createStatement().use { it.execute("DROP TRIGGER IF EXISTS fail_profile_commit") }
            connection.close()
            ProfileManager.deleteProfile(profile.id)
        }
    }
}
