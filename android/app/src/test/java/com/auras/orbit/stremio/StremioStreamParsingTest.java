package com.auras.orbit.stremio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.Test;

public final class StremioStreamParsingTest {
    @Test public void oversizedUrlRetainsEntryAndMalformedMetadataSkipsOnlyItsEntry() {
        JsonArray entries = new JsonArray();
        entries.add(validStream("First", "https://media.example/first.mp4"));

        JsonObject oversized = new JsonObject();
        oversized.addProperty("name", "Provider CDN");
        oversized.addProperty("title", "Oversized URL entry");
        oversized.addProperty("description", "Keep bounded stream metadata");
        oversized.addProperty("url", "https://media.example/" + repeat('x', 16_400));
        entries.add(oversized);

        JsonObject unsafeOptionalMetadata = validStream("Malformed headers",
                "https://media.example/second.mp4");
        unsafeOptionalMetadata.add("behaviorHints", invalidProxyHeaders());
        entries.add(unsafeOptionalMetadata);

        entries.add(validStream("Last", "https://media.example/last.mp4"));

        List<StremioModels.Stream> parsed = new StremioAddonClient().parseStreams(entries);

        assertEquals(3, parsed.size());
        assertEquals("https://media.example/first.mp4", parsed.get(0).url);
        StremioModels.Stream retained = parsed.get(1);
        assertEquals("Provider CDN", retained.name);
        assertEquals("Oversized URL entry", retained.title);
        assertEquals("Keep bounded stream metadata", retained.description);
        assertNull(retained.url);
        assertTrue(retained.unavailableReason.contains("URL"));
        assertTrue(retained.unavailableReason.contains("limit"));
        assertEquals("https://media.example/last.mp4", parsed.get(2).url);
    }

    @Test public void manyMalformedAndNullEntriesDoNotCreatePlaceholderStreams() {
        JsonArray entries = new JsonArray();
        entries.add(validStream("First", "https://media.example/first.mp4"));
        for (int index = 0; index < 1_000; index++) {
            entries.add(com.google.gson.JsonNull.INSTANCE);
            JsonObject malformed = new JsonObject();
            malformed.add("sources", new JsonArray());
            malformed.getAsJsonArray("sources").add(7);
            entries.add(malformed);
            if (index == 499) {
                entries.add(validStream("Middle", "https://media.example/middle.mp4"));
            }
        }
        entries.add(validStream("Last", "https://media.example/last.mp4"));

        List<StremioModels.Stream> parsed = new StremioAddonClient().parseStreams(entries);

        assertEquals(3, parsed.size());
        assertEquals("https://media.example/first.mp4", parsed.get(0).url);
        assertEquals("https://media.example/middle.mp4", parsed.get(1).url);
        assertEquals("https://media.example/last.mp4", parsed.get(2).url);
    }

    @Test public void streamWithoutSafeDirectUrlKeepsExistingDiagnostic() {
        JsonArray entries = new JsonArray();
        JsonObject external = new JsonObject();
        external.addProperty("title", "External provider page");
        external.addProperty("externalUrl", "https://provider.example/watch/123");
        entries.add(external);

        List<StremioModels.Stream> parsed = new StremioAddonClient().parseStreams(entries);

        assertEquals(1, parsed.size());
        assertNull(parsed.get(0).url);
        assertTrue(parsed.get(0).unavailableReason.contains("no safe direct stream URL"));
        assertFalse(parsed.get(0).unavailableReason.contains("provider.example"));
    }

    @Test public void validDirectStreamsKeepSafeHeadersWithoutNotWebReadyHint() {
        JsonArray entries = new JsonArray();
        JsonObject missingHint = validStream("Missing hint", "https://media.example/one.mp4");
        missingHint.add("behaviorHints", safeProxyHeaders(null));
        entries.add(missingHint);

        JsonObject falseHint = validStream("False hint", "https://media.example/two.mp4");
        falseHint.add("behaviorHints", safeProxyHeaders(false));
        entries.add(falseHint);

        List<StremioModels.Stream> parsed = new StremioAddonClient().parseStreams(entries);

        assertEquals(2, parsed.size());
        assertEquals("https://media.example/one.mp4", parsed.get(0).url);
        assertEquals("Orbit test client",
                parsed.get(0).behaviorHints.requestHeaders.get("User-Agent"));
        assertNull(parsed.get(0).behaviorHints.notWebReady);
        assertEquals("https://media.example/two.mp4", parsed.get(1).url);
        assertEquals("Orbit test client",
                parsed.get(1).behaviorHints.requestHeaders.get("User-Agent"));
        assertEquals(Boolean.FALSE, parsed.get(1).behaviorHints.notWebReady);
    }

    @Test public void oversizedExternalUrlRetainsOtherStreamMetadata() {
        JsonObject stream = new JsonObject();
        stream.addProperty("title", "External result with a long URL");
        stream.addProperty("description", "Keep this provider description");
        stream.addProperty("externalUrl", "https://provider.example/" + repeat('x', 16_400));
        JsonArray entries = new JsonArray();
        entries.add(stream);

        List<StremioModels.Stream> parsed = new StremioAddonClient().parseStreams(entries);

        assertEquals(1, parsed.size());
        assertEquals("External result with a long URL", parsed.get(0).title);
        assertEquals("Keep this provider description", parsed.get(0).description);
        assertNull(parsed.get(0).externalUrl);
        assertTrue(parsed.get(0).unavailableReason.contains("URL"));
        assertTrue(parsed.get(0).unavailableReason.contains("limit"));
    }

    @Test public void oversizedInlineSubtitleUrlDoesNotDropVideoOrSiblingSubtitle() {
        JsonObject stream = validStream("Video with an oversized subtitle URL",
                "https://media.example/video.mp4");
        JsonArray subtitles = new JsonArray();
        JsonObject oversized = new JsonObject();
        oversized.addProperty("url", "https://subs.example/" + repeat('x', 4_100));
        oversized.addProperty("lang", "zz");
        subtitles.add(oversized);
        JsonObject valid = new JsonObject();
        valid.addProperty("url", "https://subs.example/valid.vtt");
        valid.addProperty("lang", "en");
        subtitles.add(valid);
        stream.add("subtitles", subtitles);
        JsonArray entries = new JsonArray();
        entries.add(stream);

        List<StremioModels.Stream> parsed = new StremioAddonClient().parseStreams(entries);

        assertEquals(1, parsed.size());
        assertEquals("https://media.example/video.mp4", parsed.get(0).url);
        assertEquals(1, parsed.get(0).subtitles.size());
        assertEquals("https://subs.example/valid.vtt", parsed.get(0).subtitles.get(0).url);
        assertTrue(parsed.get(0).inlineSubtitleMetadataIncomplete);
    }

    @Test public void excessiveInlineSubtitlesAreBoundedWithoutDroppingVideoStream() {
        JsonObject stream = validStream("Video with many subtitles",
                "https://media.example/video.mp4");
        JsonArray subtitles = new JsonArray();
        for (int index = 0; index < 1_001; index++) {
            JsonObject subtitle = new JsonObject();
            subtitle.addProperty("url", "https://subs.example/track-" + index + ".vtt");
            subtitle.addProperty("lang", "en");
            subtitles.add(subtitle);
        }
        stream.add("subtitles", subtitles);
        JsonArray entries = new JsonArray();
        entries.add(stream);

        List<StremioModels.Stream> parsed = new StremioAddonClient().parseStreams(entries);

        assertEquals(1, parsed.size());
        assertEquals("https://media.example/video.mp4", parsed.get(0).url);
        assertEquals(1_000, parsed.get(0).subtitles.size());
        assertTrue(parsed.get(0).inlineSubtitleMetadataIncomplete);
    }

    private static JsonObject validStream(String title, String url) {
        JsonObject stream = new JsonObject();
        stream.addProperty("title", title);
        stream.addProperty("url", url);
        return stream;
    }

    private static JsonObject invalidProxyHeaders() {
        JsonObject requestHeaders = new JsonObject();
        requestHeaders.addProperty("Host", "evil.example");
        JsonObject proxyHeaders = new JsonObject();
        proxyHeaders.add("request", requestHeaders);
        JsonObject behaviorHints = new JsonObject();
        behaviorHints.addProperty("notWebReady", true);
        behaviorHints.add("proxyHeaders", proxyHeaders);
        return behaviorHints;
    }

    private static JsonObject safeProxyHeaders(Boolean notWebReady) {
        JsonObject requestHeaders = new JsonObject();
        requestHeaders.addProperty("User-Agent", "Orbit test client");
        JsonObject proxyHeaders = new JsonObject();
        proxyHeaders.add("request", requestHeaders);
        JsonObject behaviorHints = new JsonObject();
        if (notWebReady != null) behaviorHints.addProperty("notWebReady", notWebReady);
        behaviorHints.add("proxyHeaders", proxyHeaders);
        return behaviorHints;
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
