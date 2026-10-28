package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.DataStoreHelper.Account
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountProfileIdentityTest {
    @Test
    fun globalAccountListMigrationAssignsIdsAndRoundTrips() {
        val oldGlobalList = listOf(
            account(name = "Profile 4"),
            accountAt(keyIndex = 5, name = "Profile 5"),
        )
        val generatedIds = listOf(PROFILE_A, PROFILE_B).iterator()
        val migrated = normalizeAccountProfileIds(oldGlobalList) { generatedIds.next() }.toTypedArray()
        val restoredGlobalList = parseJson<Array<Account>>(migrated.toJson())

        assertArrayEquals(intArrayOf(4, 5), restoredGlobalList.map { it.keyIndex }.toIntArray())
        assertArrayEquals(
            migrated.map { it.profileId }.toTypedArray(),
            restoredGlobalList.map { it.profileId }.toTypedArray(),
        )
        assertEquals("Profile 4", restoredGlobalList.first().name)
    }

    @Test
    fun renameAndAvatarEditsKeepThePersistedProfileId() {
        val original = account(profileId = PROFILE_A)
        val edited = original.copy(
            name = "Renamed profile",
            customImage = "https://images.example/avatar.png",
            profileId = "",
        )

        val updated = reconcileAccountProfileIds(listOf(original), listOf(edited)) { PROFILE_B }.single()

        assertEquals(PROFILE_A, updated.profileId)
    }

    @Test
    fun slotReplacementGetsANewIdAndRestoredAccountsKeepTheirId() {
        val original = normalizeAccountProfileIds(listOf(account())) { PROFILE_A }.single()
        val persistedGlobalList = listOf(original).toTypedArray().toJson()
        val restored = parseJson<Array<Account>>(persistedGlobalList).single()
        assertEquals(PROFILE_A, restored.profileId)

        val editedAfterRestore = restored.copy(name = "After restart", profileId = "")
        val updated = reconcileAccountProfileIds(listOf(restored), listOf(editedAfterRestore)) { PROFILE_B }.single()
        assertEquals(PROFILE_A, updated.profileId)

        val recreated = reconcileAccountProfileIds(
            existingAccounts = emptyList(),
            requestedAccounts = listOf(account()),
        ) { PROFILE_B }.single()
        assertEquals(PROFILE_B, recreated.profileId)
        assertTrue(recreated.profileId != restored.profileId)
    }

    @Test
    fun defaultProfileIdUsesItsPersistedValueAcrossProcessRestoration() {
        val firstValue = getOrCreateProfileId(existingProfileId = null) { PROFILE_A }
        val restoredValue = getOrCreateProfileId(existingProfileId = firstValue) { PROFILE_B }

        assertEquals(PROFILE_A, firstValue)
        assertEquals(firstValue, restoredValue)
    }

    private fun account(
        name: String = "Profile 4",
        profileId: String = "",
    ) = accountAt(keyIndex = 4, name = name, profileId = profileId)

    private fun accountAt(
        keyIndex: Int,
        name: String,
        profileId: String = "",
    ) = Account(
        keyIndex = keyIndex,
        name = name,
        defaultImageIndex = 0,
        profileId = profileId,
    )

    private companion object {
        const val PROFILE_A = "00000000-0000-0000-0000-00000000000a"
        const val PROFILE_B = "00000000-0000-0000-0000-00000000000b"
    }
}
