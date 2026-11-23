package com.auras.orbit.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class HomeRequestTest {
    @Test public void existingConstructorRemainsAnAllSectionsRequest() {
        HomeRequest request = new HomeRequest(1, null, Arrays.asList(MediaKind.MOVIE));

        assertEquals(1, request.page);
        assertEquals("", request.cursor);
        assertEquals("", request.sectionId);
        assertEquals(Arrays.asList(MediaKind.MOVIE), request.mediaKinds);
    }

    @Test public void sectionRequestPreservesStableIdOpaqueCursorAndFilters() {
        HomeRequest request = HomeRequest.forSection("provider:catalog:stable-id", 3,
                "opaque/provider-cursor", Arrays.asList(MediaKind.MOVIE, MediaKind.SERIES));

        assertEquals("provider:catalog:stable-id", request.sectionId);
        assertEquals(3, request.page);
        assertEquals("opaque/provider-cursor", request.cursor);
        assertEquals(Arrays.asList(MediaKind.MOVIE, MediaKind.SERIES), request.mediaKinds);
        assertTrue(request.isSectionRequest());
    }

    @Test public void providerContinuationHasItsOwnRequestModeAndCursor() {
        HomeRequest request = HomeRequest.forProviderContinuation(
                "opaque-catalog-batch-cursor", Arrays.asList(MediaKind.MOVIE));

        assertEquals("opaque-catalog-batch-cursor", request.providerCursor);
        assertEquals("", request.sectionId);
        assertEquals("", request.cursor);
        assertTrue(request.isProviderContinuationRequest());
        assertTrue(!request.isSectionRequest());
    }
}
