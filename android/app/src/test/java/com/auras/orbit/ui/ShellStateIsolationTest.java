package com.auras.orbit.ui;

import com.auras.orbit.navigation.ShellRoute;
import com.auras.orbit.navigation.ShellState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Regression coverage for Activity-local routes and profile-scoped presentation state. */
public final class ShellStateIsolationTest {
    @Test
    public void shellWindowsKeepIndependentRouteFocusAndScroll() {
        ShellState firstWindow = new ShellState();
        ShellState secondWindow = new ShellState();

        firstWindow.setProfileScope("profile-one");
        firstWindow.navigateTo(ShellRoute.LIBRARY);
        firstWindow.rememberFocus(ShellRoute.LIBRARY, "library_favorites");
        firstWindow.rememberScroll(ShellRoute.LIBRARY, 420);

        assertEquals(ShellRoute.HOME, secondWindow.getRoute());
        assertEquals("", secondWindow.getProfileScope());
        assertNull(secondWindow.focusFor(ShellRoute.LIBRARY));
        assertEquals(0, secondWindow.scrollFor(ShellRoute.LIBRARY));
    }

    @Test
    public void matchingProfileRestoresPresentationAndAdvancesRevision() {
        ShellState source = new ShellState();
        source.setProfileScope("profile-one");
        source.navigateTo(ShellRoute.SEARCH);
        source.rememberFocus(ShellRoute.SEARCH, "search_query");
        source.rememberScroll(ShellRoute.SEARCH, 275);
        ShellState.Snapshot saved = source.snapshot();

        ShellState recreated = new ShellState();
        ShellState.Snapshot restored = recreated.restore(saved, "profile-one");

        assertEquals(ShellRoute.SEARCH, restored.route);
        assertEquals("profile-one", restored.profileScope);
        assertEquals("search_query", restored.focusByRoute.get(ShellRoute.SEARCH));
        assertEquals(Integer.valueOf(275), restored.scrollByRoute.get(ShellRoute.SEARCH));
        assertTrue("restoration must invalidate callbacks captured before recreation",
                restored.revision > saved.revision);
    }

    @Test
    public void changingProfileClearsOtherProfilesRouteFocusAndScroll() {
        ShellState state = new ShellState();
        state.setProfileScope("profile-one");
        state.navigateTo(ShellRoute.DOWNLOADS);
        state.rememberFocus(ShellRoute.DOWNLOADS, "downloads_queue_entry");
        state.rememberScroll(ShellRoute.DOWNLOADS, 180);
        long previousRevision = state.getRevision();

        ShellState.Snapshot switched = state.setProfileScope("profile-two");

        assertEquals(ShellRoute.HOME, switched.route);
        assertEquals("profile-two", switched.profileScope);
        assertNull(switched.focusByRoute.get(ShellRoute.DOWNLOADS));
        assertNull(switched.scrollByRoute.get(ShellRoute.DOWNLOADS));
        assertTrue("profile switching must invalidate callbacks from the previous profile",
                switched.revision > previousRevision);
    }
}
