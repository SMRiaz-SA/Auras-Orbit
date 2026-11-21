package com.auras.orbit.player;

import static org.junit.Assert.assertEquals;

import com.auras.orbit.providers.ProviderSubtitleCandidate;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class PlaybackRequestSubtitleDiagnosticsTest {
    @Test public void countsOnlyInvalidOrOverCapCandidatesAndPreMaterializationRejects() throws Exception {
        List<ProviderSubtitleCandidate> candidates = new ArrayList<>();
        candidates.add(candidate("malformed", "not a valid URI", Collections.emptyMap(), null));
        candidates.add(withOversizedHeaders(candidate("oversized",
                "https://subs.example/subtitle.srt", Collections.emptyMap(), null)));
        candidates.add(candidate("cleartext-headers", "http://subs.example/subtitle.srt",
                Collections.singletonMap("User-Agent", "Orbit"), null));
        candidates.add(candidate("cleartext-query", "http://subs.example/subtitle.srt?token=x",
                Collections.emptyMap(), null));
        candidates.add(candidate("unknown-format", "https://subs.example/subtitle.txt",
                Collections.emptyMap(), null));
        candidates.add(candidate("accepted", "https://subs.example/subtitle.srt",
                Collections.emptyMap(), null));

        assertEquals(4, PlaybackRequest.countRejectedProviderSubtitles(candidates));
        assertEquals(7, PlaybackRequest.countRejectedProviderSubtitles(candidates, 3));
    }

    @Test public void countsSupportedCandidatesSkippedPastPlaybackTrackLimit() {
        List<ProviderSubtitleCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < 31; index++) {
            candidates.add(candidate("sub-" + index,
                    "https://subs.example/subtitle-" + index + ".srt",
                    Collections.emptyMap(), null));
        }

        assertEquals(1, PlaybackRequest.countRejectedProviderSubtitles(candidates));
    }

    private static ProviderSubtitleCandidate candidate(String id, String uri,
            Map<String, String> headers, String mimeType) {
        return new ProviderSubtitleCandidate(id, "English", uri, headers, mimeType);
    }

    private static ProviderSubtitleCandidate withOversizedHeaders(
            ProviderSubtitleCandidate candidate) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int index = 0; index < 65; index++) headers.put("X-Test-" + index, "value");
        Field field = ProviderSubtitleCandidate.class.getDeclaredField("headers");
        field.setAccessible(true);
        field.set(candidate, Collections.unmodifiableMap(headers));
        return candidate;
    }
}
