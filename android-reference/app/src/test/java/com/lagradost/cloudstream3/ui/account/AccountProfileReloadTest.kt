package com.lagradost.cloudstream3.ui.account

import com.lagradost.cloudstream3.utils.DataStoreHelper.Account
import com.lagradost.cloudstream3.utils.normalizeAccountProfileIds
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountProfileReloadTest {
    @Test
    fun newProfileRemainsSelectedAfterItsStableIdIsPersisted() {
        val requestedAccount = Account(
            keyIndex = 4,
            name = "New profile",
            defaultImageIndex = 0,
        )
        val persistedAccounts = normalizeAccountProfileIds(listOf(requestedAccount)) {
            "00000000-0000-0000-0000-000000000004"
        }

        assertEquals(0, findAccountPositionByKey(persistedAccounts, requestedAccount.keyIndex))
    }

    @Test
    fun newProfileHomepageIsPersistedBeforeHomeReload() {
        var selectedProfile = "profile-1"
        val homepageByProfile = mutableMapOf("profile-1" to "Provider A")
        val events = mutableListOf<String>()
        var reloadedSource: String? = null

        rebindAccountHomepageBeforeReload(
            selectAccountWithoutHomeReload = {
                selectedProfile = "profile-2"
                events += "select"
            },
            homepage = "Provider A",
            persistHomepage = { homepage ->
                homepageByProfile[selectedProfile] = homepage ?: ""
                events += "homepage"
            },
            persistAccounts = { events += "accounts" },
            reloadHome = {
                reloadedSource = homepageByProfile[selectedProfile]
                events += "reload"
            },
        )

        assertEquals("Provider A", reloadedSource)
        assertEquals(listOf("select", "homepage", "accounts", "reload"), events)
    }
}
