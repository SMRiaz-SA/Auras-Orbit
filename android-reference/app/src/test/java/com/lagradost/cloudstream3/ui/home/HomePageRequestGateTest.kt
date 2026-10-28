package com.lagradost.cloudstream3.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePageRequestGateTest {
    @Test
    fun laterProviderSelectionWinsWhenOlderRequestCompletesLast() {
        val gate = HomePageRequestGate()
        val requestA = gate.begin(profileIdentity = "profile-1", requestedSource = "A")
        val requestB = gate.begin(profileIdentity = "profile-1", requestedSource = "B")
        val published = mutableListOf<String>()

        assertTrue(gate.commitIfCurrent(requestB, "profile-1") { published += "B" })
        assertFalse(gate.commitIfCurrent(requestA, "profile-1") { published += "A" })
        assertEquals(listOf("B"), published)
    }

    @Test
    fun profileSwitchInvalidatesAnOtherwiseLatestRequest() {
        val gate = HomePageRequestGate()
        val request = gate.begin(profileIdentity = "profile-1", requestedSource = "A")

        assertFalse(gate.isCurrent(request, profileIdentity = "profile-2"))
    }

    @Test
    fun sameSourceIsReboundToTheNewProfile() {
        val gate = HomePageRequestGate()
        val oldProfile = gate.begin(profileIdentity = "profile-1", requestedSource = "A")
        val newProfile = gate.begin(profileIdentity = "profile-2", requestedSource = "A")

        assertFalse(gate.isCurrent(oldProfile, profileIdentity = "profile-2"))
        assertTrue(gate.isCurrent(newProfile, profileIdentity = "profile-2"))
    }

    @Test
    fun newProfileCanBindToItsDifferentSavedSource() {
        val gate = HomePageRequestGate()
        gate.begin(profileIdentity = "profile-1", requestedSource = "A")
        val newProfile = gate.begin(profileIdentity = "profile-2", requestedSource = "B")

        assertEquals("B", newProfile.requestedSource)
        assertTrue(gate.isCurrent(newProfile, profileIdentity = "profile-2"))
    }

    @Test
    fun pluginRefreshInterleavedAfterUiSelectionReadsThePersistedChoice() {
        val gate = HomePageRequestGate()
        var savedSource = "A"
        val uiSelection = gate.beginFromUiSelection("profile-1", "B") { savedSource = it }

        // Models afterPluginsLoaded reading currentHomePage immediately after the UI request.
        val pluginRefresh = gate.begin("profile-1", savedSource)

        assertEquals("B", savedSource)
        assertEquals("B", pluginRefresh.requestedSource)
        assertFalse(gate.isCurrent(uiSelection, profileIdentity = "profile-1"))
        assertTrue(gate.isCurrent(pluginRefresh, profileIdentity = "profile-1"))
    }

    @Test
    fun automaticRefreshDoesNotUseSourceCapturedBeforeUiSelection() {
        val dispatchLock = Any()
        val gate = HomePageRequestGate()
        var savedSource = "A"

        // The event may begin before the user action, but it must not carry this pre-lock value
        // into dispatch. The real refresh reads the current persisted source under the shared lock.
        val stalePreLockRead = savedSource
        val uiSelection = synchronized(dispatchLock) {
            gate.beginFromUiSelection("profile-1", "B") { savedSource = it }
        }
        val automaticRefresh = withStoredHomeSourceDispatch(
            dispatchLock = dispatchLock,
            readSource = { savedSource },
            dispatch = { source -> gate.begin("profile-1", source) },
        )

        assertEquals("A", stalePreLockRead)
        assertEquals("B", uiSelection.requestedSource)
        assertEquals("B", automaticRefresh.requestedSource)
        assertTrue(gate.isCurrent(automaticRefresh, profileIdentity = "profile-1"))
    }
}
