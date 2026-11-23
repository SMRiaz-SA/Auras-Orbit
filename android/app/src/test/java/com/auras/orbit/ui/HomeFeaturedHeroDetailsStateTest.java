package com.auras.orbit.ui;

import com.auras.orbit.providers.MediaItem;
import com.auras.orbit.providers.MediaKind;
import com.auras.orbit.providers.MediaProvider;
import com.auras.orbit.providers.ProviderDescriptor;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HomeFeaturedHeroDetailsStateTest {
    @Test public void stateIsScopedToSelectedItemAndOnlyStartsItsRequestOnce() {
        MediaProvider provider = provider("hero-state-provider", true);
        List<MediaItem> candidates = Arrays.asList(
                item("first", "https://provider.fixture.invalid/first", ""),
                item("selected", "https://provider.fixture.invalid/selected", ""),
                item("third", "https://provider.fixture.invalid/third", ""));
        int selectedIndex = 1;
        MediaItem selected = candidates.get(selectedIndex);
        HomeFeaturedHeroDetailsState state = new HomeFeaturedHeroDetailsState(
                "profile", provider, selected, "sources", selectedIndex, 4, 8);
        MediaProvider currentAdapter = provider("hero-state-provider", true);
        MediaProvider staleAdapter = provider("hero-state-provider", false);

        assertEquals("selected", state.stableId);
        assertTrue(state.matches(provider, "profile", selected, "sources", 1, 4, 8));
        assertTrue("a fresh CS3 adapter for the same live registration reuses hero state",
                state.matches(currentAdapter, "profile", selected, "sources", 1, 4, 8));
        assertFalse("a stale registration cannot reuse an earlier hero request",
                state.matches(staleAdapter, "profile", selected, "sources", 1, 4, 8));
        assertFalse("a different carousel selection must not reuse this request",
                state.matches(provider, "profile", selected, "sources", 0, 5, 8));
        assertTrue("the selected item starts one details request", state.markDetailsAttempted());
        assertFalse("same-item recompositions do not start a second request",
                state.markDetailsAttempted());
    }

    @Test public void onlyCurrentMatchingDetailsCanSupplyTheBackdropUrl() {
        MediaProvider provider = provider("hero-backdrop-provider");
        MediaItem seed = item("selected", "https://provider.fixture.invalid/selected", "");
        HomeFeaturedHeroDetailsState state = new HomeFeaturedHeroDetailsState(
                "profile", provider, seed, "sources", 0, 2, 5);
        MediaItem wrongItem = item("another", seed.detailUrl,
                "https://images.fixture.invalid/wrong-background.jpg");
        MediaItem details = new MediaItem(seed.stableId, seed.title, seed.kind, seed.detailUrl,
                seed.posterUrl, seed.year, "", "", Collections.emptyList(), 0, "",
                "https://images.fixture.invalid/background.jpg", "",
                Collections.singletonMap("X-Poster", "details-fixture"));

        assertFalse(state.applyDetails(seed, wrongItem, true, true));
        assertFalse("callbacks from an old selected index are ignored",
                state.applyDetails(seed, details, false, true));
        assertTrue(state.applyDetails(seed, details, true, true));
        assertEquals("https://images.fixture.invalid/background.jpg", state.backgroundPosterUrl);
        assertEquals(seed.posterUrl, state.backgroundPosterHeaderOriginUrl);
        assertEquals("details-fixture", state.backgroundPosterHeaders.get("X-Poster"));
    }

    private static MediaProvider provider(String id) {
        return provider(id, false);
    }

    private static MediaProvider provider(String id, boolean currentRegistration) {
        return new MediaProvider() {
            @Override public ProviderDescriptor descriptor() {
                return new ProviderDescriptor(id, "Fixture", true, true, true, true, true);
            }
            @Override public String owningPluginId() { return "fixture-plugin"; }
            @Override public String profileScopeId() { return "profile"; }
            @Override public boolean isRegistrationCurrent() { return currentRegistration; }
        };
    }

    private static MediaItem item(String id, String detailUrl, String backgroundUrl) {
        return new MediaItem(id, id, MediaKind.MOVIE, detailUrl,
                "https://images.fixture.invalid/poster.jpg", "2024", "", "",
                Collections.emptyList(), 0, "", backgroundUrl, "", Collections.emptyMap());
    }
}
