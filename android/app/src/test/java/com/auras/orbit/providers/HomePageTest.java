package com.auras.orbit.providers;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;

public final class HomePageTest {
    @Test public void providerContinuationCursorIsSeparateFromSectionItemCursors() {
        HomeSection section = new HomeSection("catalog-a", "Catalog A",
                java.util.Collections.emptyList(), "stremio-skip:100");
        HomePage page = new HomePage(Arrays.asList(section), "opaque-next-catalog");

        assertEquals("stremio-skip:100", page.sections.get(0).nextCursor);
        assertEquals("opaque-next-catalog", page.nextProviderCursor);
        assertEquals(1, page.sections.size());
    }

    @Test public void partialPageCarriesFailureAndCursorForAVisibleRetry() {
        HomePage page = new HomePage(java.util.Collections.emptyList(),
                "opaque-failed-catalog", "Catalog request failed; more catalogs remain.",
                true, false);

        assertEquals("opaque-failed-catalog", page.nextProviderCursor);
        assertEquals("Catalog request failed; more catalogs remain.", page.continuationError);
        assertEquals(true, page.hasMoreProviderSections);
        assertEquals(false, page.retryAvailable);
    }

    @Test public void retryOnlyPageSignalsManualRetry() {
        HomePage page = new HomePage(java.util.Collections.emptyList(),
                "opaque-failed-catalog", "Retry this catalog.", false, true);

        assertEquals(false, page.hasMoreProviderSections);
        assertEquals(true, page.retryAvailable);
    }

    @Test public void providerAttributionPreservesPartialFailureForHostRetry() {
        ProviderDescriptor provider = new ProviderDescriptor(
                "provider-a", "Provider A", true, false, false, false, false);
        HomePage page = new HomePage(java.util.Collections.emptyList(),
                "opaque-failed-catalog", "Retry this catalog.", false, true);

        AttributedHomePage attributed = ProviderResultBudget.homePage(provider, 4, page);

        assertEquals(4, attributed.sourceOrder);
        assertEquals("opaque-failed-catalog", attributed.nextProviderCursor);
        assertEquals("Retry this catalog.", attributed.continuationError);
        assertEquals(false, attributed.hasMoreProviderSections);
        assertEquals(true, attributed.retryAvailable);
    }
}
