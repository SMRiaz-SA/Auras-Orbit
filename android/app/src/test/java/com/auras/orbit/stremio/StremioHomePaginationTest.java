package com.auras.orbit.stremio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.auras.orbit.providers.HomeRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class StremioHomePaginationTest {
    @Test public void sectionRequestTargetsOnlyItsCatalog() {
        StremioModels.CatalogDefinition first = catalog("movie", "popular", true);
        StremioModels.CatalogDefinition second = catalog("series", "popular", true);
        HomeRequest request = HomeRequest.forSection(
                StremioMediaProvider.catalogSectionId(first), 2, "stremio-skip:100",
                Collections.emptyList());

        assertTrue(StremioMediaProvider.homeRequestTargetsCatalog(request, first));
        assertFalse(StremioMediaProvider.homeRequestTargetsCatalog(request, second));
        assertTrue(StremioMediaProvider.homeRequestTargetsCatalog(
                new HomeRequest(1, null, Collections.emptyList()), second));
    }

    @Test public void fullSkipPageReturnsNextOpaqueCursorAndPartialPageStops() {
        StremioModels.CatalogDefinition catalog = catalog("movie", "popular", true);
        StremioModels.CatalogResponse fullPage = new StremioModels.CatalogResponse(
                Collections.nCopies(100, null), null);
        StremioModels.CatalogResponse partialPage = new StremioModels.CatalogResponse(
                Collections.nCopies(99, null), null);

        assertEquals("stremio-skip:100",
                StremioMediaProvider.nextHomeCursor(catalog, fullPage, 0L));
        assertEquals("", StremioMediaProvider.nextHomeCursor(catalog, partialPage, 0L));
        assertEquals("", StremioMediaProvider.nextHomeCursor(
                catalog("movie", "one-page", false), fullPage, 0L));
        assertEquals("stremio-skip:100100",
                StremioMediaProvider.nextHomeCursor(catalog, fullPage, 100_000L));
        assertEquals("stremio-skip:100200",
                StremioMediaProvider.nextHomeCursor(catalog, fullPage, 100_100L));
        assertEquals("", StremioMediaProvider.nextHomeCursor(
                catalog, fullPage, Long.MAX_VALUE - 50));
    }

    @Test public void numericSkipCursorMustMatchTheRequestedPage() throws Exception {
        assertEquals(100_100L, StremioMediaProvider.resolveHomeSkip(
                HomeRequest.forSection("section", 1_002, "stremio-skip:100100", Collections.emptyList())));
        try {
            StremioMediaProvider.resolveHomeSkip(
                    HomeRequest.forSection("section", 1_002, "stremio-skip:100200", Collections.emptyList()));
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Mismatched Stremio skip cursors must be rejected");
    }

    @Test public void providerCatalogCursorAdvancesAcrossBoundedEligibleBatches() throws Exception {
        assertEquals("stremio-home-v2:8:",
                StremioMediaProvider.nextHomeCatalogCursor(8, 11));
        assertEquals(8, StremioMediaProvider.resolveHomeCatalogStartIndex(
                HomeRequest.forProviderContinuation("stremio-home-v2:8:", Collections.emptyList()), 11));
        assertEquals("", StremioMediaProvider.nextHomeCatalogCursor(11, 11));
        assertEquals("", StremioMediaProvider.nextHomeCatalogCursor(0, 0));
    }

    @Test public void providerContinuationCursorRejectsInvalidAndOutOfRangeIndices() throws Exception {
        assertInvalidCatalogContinuation("stremio-home-catalog:abc", 11);
        assertInvalidCatalogContinuation("stremio-home-catalog:11", 11);
        assertInvalidCatalogContinuation("stremio-home-catalog:-1", 11);
    }

    private static void assertInvalidCatalogContinuation(String cursor, int eligibleCatalogCount)
            throws Exception {
        try {
            StremioMediaProvider.resolveHomeCatalogStartIndex(
                    HomeRequest.forProviderContinuation(cursor, Collections.emptyList()),
                    eligibleCatalogCount);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Invalid continuation cursor should be rejected: " + cursor);
    }

    private static StremioModels.CatalogDefinition catalog(
            String type, String id, boolean supportsSkip) {
        return new StremioModels.CatalogDefinition(type, id, id,
                supportsSkip ? Arrays.asList(new StremioModels.ExtraOption(
                        "skip", false, Collections.emptyList(), null)) : Collections.emptyList());
    }
}
