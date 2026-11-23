package com.auras.orbit.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Regression coverage for provider-wide link retention without an arbitrary count ceiling. */
public final class ProviderLinkResultUnboundedTest {
    @Test public void retainsEveryCandidateBeyondFormer4096Ceiling() {
        List<ProviderLinkCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < 4_200; index++) {
            candidates.add(new ProviderLinkCandidate("link-" + index, "Mirror " + index,
                    "Provider", "https://media.example/watch?id=" + index, "", index,
                    "VIDEO", "", Collections.emptyMap()));
        }

        ProviderLinkResult result = new ProviderLinkResult(true, candidates,
                Collections.emptyList(), "");

        assertEquals(4_200, result.links.size());
        assertEquals("link-0", result.links.get(0).id);
        assertEquals("link-4199", result.links.get(4_199).id);
        assertEquals("https://media.example/watch?id=4199", result.links.get(4_199).uri);
    }

    @Test public void keepsDeclaredTypeSignedUrlAndProviderHeadersWithoutSuffixGuessing() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", "https://provider.example/watch");
        headers.put("User-Agent", "Orbit test client");
        String signedUrl = "https://media.example/master?token=secret&expires=123";

        ProviderLinkCandidate candidate = new ProviderLinkCandidate("signed-hls", "Mirror",
                "Provider", signedUrl, "https://provider.example/watch", 1080,
                "M3U8", "", headers);

        assertEquals("M3U8", candidate.type);
        assertEquals(signedUrl, candidate.uri);
        assertFalse(candidate.headers.isEmpty());
        assertEquals("https://provider.example/watch", candidate.referer);
    }

    @Test public void playbackOrderingRetainsAllCandidatesBeyond4096() {
        List<ProviderLinkCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < 4_200; index++) {
            candidates.add(new ProviderLinkCandidate("ordered-" + index, "Mirror " + index,
                    "Provider", "https://media.example/play?token=" + index, "", index,
                    "VIDEO", "", Collections.emptyMap()));
        }

        ProviderLinkResult result = new ProviderLinkResult(true, candidates,
                Collections.emptyList(), "");
        List<Integer> orderedIndexes = result.orderedLinkIndexesByQuality();

        assertEquals(4_200, orderedIndexes.size());
        assertEquals(Integer.valueOf(4_199), orderedIndexes.get(0));
        assertEquals(Integer.valueOf(0), orderedIndexes.get(4_199));
    }

    @Test public void declaredMediaTypeMappingDoesNotInspectUrlSuffix() {
        assertEquals("application/x-mpegurl",
                ProviderMediaTypeMapper.mimeType("M3U8", ""));
        assertEquals("application/dash+xml",
                ProviderMediaTypeMapper.mimeType("DASH", ""));
        assertEquals("video/mp4",
                ProviderMediaTypeMapper.mimeType("VIDEO", ""));
        assertEquals("video/x-matroska",
                ProviderMediaTypeMapper.mimeType("VIDEO", "video/x-matroska"));
    }

    @Test public void failedLargeReservationDoesNotBlockLaterCandidatesThatFit() {
        ProviderLinkMetadataBudget budget = new ProviderLinkMetadataBudget(100L);

        assertTrue(budget.tryReserve(60L));
        assertFalse(budget.tryReserve(50L));
        assertEquals(60L, budget.retainedChars());
        assertTrue(budget.tryReserve(40L));
        assertFalse(budget.tryReserve(1L));
        assertEquals(100L, budget.retainedChars());
    }
}
