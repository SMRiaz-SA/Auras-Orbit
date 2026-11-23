package com.auras.orbit.stremio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.auras.orbit.providers.ProviderLinkCandidate;
import com.auras.orbit.providers.ProviderLinkResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Ensures Stremio results without a usable URL still reach Orbit's source chooser. */
public final class StremioUnavailableResultTest {
    @Test public void missingOrOversizedUrlsRemainChooserRowsWithReasons() {
        JsonObject oversized = new JsonObject();
        oversized.addProperty("title", "Both URLs too long");
        oversized.addProperty("url", "https://media.example/" + repeat('x', 16_400));
        oversized.addProperty("externalUrl", "https://provider.example/" + repeat('y', 16_400));

        JsonObject absent = new JsonObject();
        absent.addProperty("title", "No URLs");

        JsonArray entries = new JsonArray();
        entries.add(oversized);
        entries.add(absent);
        List<StremioModels.Stream> streams = new StremioAddonClient().parseStreams(entries);
        assertEquals(2, streams.size());

        List<ProviderLinkCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < streams.size(); index++) {
            candidates.add(StremioMediaProvider.unavailableResultCandidate(
                    "Test add-on", index, streams.get(index)));
        }
        ProviderLinkResult result = new ProviderLinkResult(true, candidates,
                Collections.emptyList(), "");

        assertTrue(result.resolved);
        assertEquals(2, result.links.size());
        assertUnavailableRow(result.links.get(0), "Both URLs too long");
        assertTrue(result.links.get(0).unavailableReason.contains("limit"));
        assertUnavailableRow(result.links.get(1), "No URLs");
        assertFalse(result.links.get(1).unavailableReason.isEmpty());
    }

    private static void assertUnavailableRow(ProviderLinkCandidate candidate, String title) {
        assertEquals(title, candidate.name);
        assertEquals("UNSUPPORTED", candidate.type);
        assertEquals("", candidate.uri);
        assertFalse(candidate.unavailableReason.isEmpty());
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
