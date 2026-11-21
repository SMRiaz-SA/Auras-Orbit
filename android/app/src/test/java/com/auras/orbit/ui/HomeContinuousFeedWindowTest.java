package com.auras.orbit.ui;

import com.auras.orbit.navigation.ShellRoute;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Deterministic presentation-window coverage for the Home provider feed. */
public final class HomeContinuousFeedWindowTest {
    @Test
    public void providerAuthoredHomeShelvesUseOneUnpagedRange() {
        OrbitShellActivity.HomeFeedWindow window =
                OrbitShellActivity.HomeFeedWindow.allHomeShelves(13);

        assertEquals(13, window.total);
        assertEquals(0, window.start);
        assertEquals(13, window.end);
        assertFalse(window.hasPager());
    }

    @Test
    public void homeProviderRailsExposeAllTitlesWithoutChangingSearchPaging() {
        OrbitShellActivity.HomeFeedWindow home =
                OrbitShellActivity.HomeFeedWindow.providerRail(ShellRoute.HOME, 25_000, 3);

        assertEquals(0, home.start);
        assertEquals(25_000, home.end);
        assertFalse(home.hasPrevious());
        assertFalse(home.hasNext());
        assertFalse(home.hasPager());

        OrbitShellActivity.HomeFeedWindow searchPage =
                OrbitShellActivity.HomeFeedWindow.providerRail(ShellRoute.SEARCH, 91, 1);
        assertEquals(40, searchPage.start);
        assertEquals(80, searchPage.end);
        assertEquals(1, searchPage.page);
        assertTrue(searchPage.hasPrevious());
        assertTrue(searchPage.hasNext());
        assertTrue(searchPage.hasPager());

        OrbitShellActivity.HomeFeedWindow finalSearchPage =
                OrbitShellActivity.HomeFeedWindow.providerRail(ShellRoute.SEARCH, 91, 2);
        assertEquals(80, finalSearchPage.start);
        assertEquals(91, finalSearchPage.end);
        assertTrue(finalSearchPage.hasPrevious());
        assertFalse(finalSearchPage.hasNext());
    }

    @Test
    public void otherProviderRoutesRetainPagedRails() {
        OrbitShellActivity.HomeFeedWindow libraryPage =
                OrbitShellActivity.HomeFeedWindow.providerRail(ShellRoute.LIBRARY, 91, 1);

        assertEquals(40, libraryPage.start);
        assertEquals(80, libraryPage.end);
        assertTrue(libraryPage.hasPager());
    }
}
