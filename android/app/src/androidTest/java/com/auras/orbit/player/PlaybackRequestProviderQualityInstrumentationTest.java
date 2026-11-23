package com.auras.orbit.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.providers.ProviderLinkCandidate;
import com.auras.orbit.providers.ProviderLinkResult;
import com.auras.orbit.providers.ProviderSubtitleCandidate;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Focused coverage for provider-link quality ordering in deferred Orbit playback. */
@RunWith(AndroidJUnit4.class)
public final class PlaybackRequestProviderQualityInstrumentationTest {
    @Test
    public void highestQualityWinsAndUnknownProviderTypeIsRetained() {
        String lowUri = "https://media.example/360.mp4";
        String firstEqualUri = "https://media.example/720-first.mp4";
        String secondEqualUri = "https://media.example/720-second.mp4";
        String highUsableUri = "https://media.example/1080.mp4";
        String unsupportedUri = "https://media.example/2160.unsupported";
        ProviderLinkResult result = new ProviderLinkResult(true, Arrays.asList(
                link("low", 360, lowUri, "VIDEO"),
                link("equal-first", 720, firstEqualUri, "VIDEO"),
                link("equal-second", 720, secondEqualUri, "VIDEO"),
                link("high-usable", 1080, highUsableUri, "VIDEO"),
                link("unsupported-high", 2160, unsupportedUri, "OTHER")),
                Collections.emptyList(), "");

        PlaybackRequest request = PlaybackRequest.fromProviderLinkResult("profile-id",
                new PlaybackRequest.MediaMetadata("series-id", "provider-id", "Series",
                        "Series", "", "", ""),
                new PlaybackRequest.EpisodeMetadata("episode-id", "Pilot", 1, 1),
                result, "Provider", 0L);

        assertEquals("the highest provider candidate becomes the default", "provider-source-4",
                request.defaultSourceId);
        assertEquals("unknown provider types remain available for a player attempt", 5,
                request.sources.size());
        assertEquals("supported sources sort by quality while equal-quality links stay stable",
                Arrays.asList(unsupportedUri, highUsableUri, firstEqualUri, secondEqualUri, lowUri),
                sourceUris(request.sources));
        assertEquals("stable source IDs continue to identify their original callback entries",
                Arrays.asList("provider-source-4", "provider-source-3", "provider-source-1",
                        "provider-source-2", "provider-source-0"), sourceIds(request.sources));
    }

    @Test
    public void providerSourceLabelRetainsCloudStreamLinkNameAndSource() {
        ProviderLinkResult result = new ProviderLinkResult(true, Collections.singletonList(
                new ProviderLinkCandidate("link-1", "The Movie", "FastHost",
                        "https://media.example/movie.mp4", "", 1080, "VIDEO", "",
                        Collections.emptyMap())), Collections.emptyList(), "");

        PlaybackRequest request = PlaybackRequest.fromProviderLinkResult("profile-id",
                new PlaybackRequest.MediaMetadata("movie-id", "provider-id", "The Movie",
                        "Movie", "", "", ""),
                new PlaybackRequest.EpisodeMetadata("", "", -1, -1),
                result, "StreamPlay", 0L);

        assertEquals("The Movie · FastHost · StreamPlay · 1080p · MP4",
                request.sources.get(0).label);
    }

    @Test
    public void providerSourceAdmissionRetainsEveryCandidateBeyond4096InQualityOrder() {
        List<ProviderLinkCandidate> candidates = providerLinks(4_200);
        List<ProviderSubtitleCandidate> subtitles = longProviderSubtitles(30);
        PlaybackRequest request = PlaybackRequest.fromProviderLinkResult("profile-id",
                new PlaybackRequest.MediaMetadata("series-id", "provider-id", "Series",
                        "Series", "", "", ""),
                new PlaybackRequest.EpisodeMetadata("episode-id", "Pilot", 1, 1),
                new ProviderLinkResult(true, candidates, subtitles, ""),
                "Provider", 0L);

        assertEquals("all provider candidates remain selectable", 4_200, request.sources.size());
        List<String> expectedUris = new ArrayList<>();
        for (int i = 4_199; i >= 0; i--) expectedUris.add(providerUri(i));
        assertEquals("quality ordering remains descending after admission", expectedUris,
                sourceUris(request.sources));

        Intent handoff = request.intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        assertNotNull(handoff.getExtras());
        assertTrue("the handoff carries only the in-process request key",
                handoff.getExtras().keySet().size() == 1
                        && handoff.hasExtra(PlaybackRequest.EXTRA_REQUEST_KEY));
        PlaybackRequest consumed = PlaybackRequest.fromIntent(handoff);
        assertNotNull("a multi-source request survives key-only handoff", consumed);
        assertEquals(4_200, consumed.sources.size());
    }

    @Test
    public void playbackRequestRetainsMoreThan4096Sources() {
        List<PlaybackRequest.Source> sources = new ArrayList<>();
        for (int i = 0; i < 4_097; i++) {
            sources.add(new PlaybackRequest.Source("source-" + i, "Source",
                    "https://media.example/video.mp4", "video/mp4",
                    Collections.emptyMap(), Collections.emptyList()));
        }
        PlaybackRequest request = new PlaybackRequest("profile-id",
                new PlaybackRequest.MediaMetadata("movie-id", "provider-id", "Movie",
                        "Movie", "", "", ""),
                new PlaybackRequest.EpisodeMetadata("", "", -1, -1), sources,
                "source-0", Collections.emptyList(), Collections.emptyList());
        assertEquals(4_097, request.sources.size());
    }

    @Test
    public void playbackIntentRejectsExcessiveAggregateSourceMetadata() {
        char[] valueCharacters = new char[8192];
        Arrays.fill(valueCharacters, 'x');
        String headerValue = new String(valueCharacters);
        java.util.Map<String, String> headers = Collections.singletonMap("X-Large", headerValue);
        List<PlaybackRequest.Source> sources = new ArrayList<>();
        for (int i = 0; i < 4_096; i++) {
            sources.add(new PlaybackRequest.Source("source-" + i, "Source",
                    "https://media.example/video.mp4", "video/mp4", headers,
                    Collections.emptyList()));
        }
        PlaybackRequest request = new PlaybackRequest("profile-id",
                new PlaybackRequest.MediaMetadata("movie-id", "provider-id", "Movie",
                        "Movie", "", "", ""),
                new PlaybackRequest.EpisodeMetadata("", "", -1, -1), sources,
                "source-0", Collections.emptyList(), Collections.emptyList());

        try {
            request.intent(InstrumentationRegistry.getInstrumentation().getTargetContext());
            fail("an excessive aggregate source metadata payload must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("source metadata"));
        }
    }

    @Test
    public void progressiveProviderMergeRetainsCandidatesPastIndexFifty() {
        List<ProviderLinkCandidate> candidates = providerLinks(80);
        PlaybackRequest request = PlaybackRequest.fromProviderLinkResult("profile-id",
                new PlaybackRequest.MediaMetadata("series-id", "provider-id", "Series",
                        "Series", "", "", ""),
                new PlaybackRequest.EpisodeMetadata("episode-id", "Pilot", 1, 1),
                new ProviderLinkResult(false, new ArrayList<>(candidates.subList(0, 40)),
                        Collections.emptyList(), ""), "Provider", 0L);

        request.mergeProviderLinkResult(new ProviderLinkResult(false, candidates,
                Collections.emptyList(), ""), "Provider", null);

        assertEquals("a later full snapshot can append candidates beyond the old cap", 80,
                request.sources.size());
        assertEquals("the highest-quality appended candidate sorts first", providerUri(79),
                request.sources.get(0).uri);
        assertEquals("the lowest-quality candidate is retained", providerUri(0),
                request.sources.get(79).uri);
    }

    @Test
    public void progressiveMergeKeepsCompleteUnionBeyondFormer4096Ceiling() {
        List<PlaybackRequest.Source> initialSources = new ArrayList<>();
        for (int i = 0; i < 4_100; i++) {
            String id = i == 0 ? "provider-source-0" : "legacy-source-" + i;
            initialSources.add(new PlaybackRequest.Source(id, i == 0 ? "Original" : "Legacy",
                    "https://media.example/old-" + i + ".mp4", "video/mp4",
                    Collections.emptyMap(), Collections.emptyList()));
        }
        PlaybackRequest request = new PlaybackRequest("profile-id",
                new PlaybackRequest.MediaMetadata("series-id", "provider-id", "Series",
                        "Series", "", "", ""),
                new PlaybackRequest.EpisodeMetadata("episode-id", "Pilot", 1, 1),
                initialSources, "provider-source-0", Collections.emptyList(),
                Collections.emptyList());
        PlaybackRequest.Source overlappingSource = request.sources.get(0);
        ProviderLinkResult oversizedUnionSnapshot = new ProviderLinkResult(false,
                providerLinks(4_200), Collections.singletonList(
                        new ProviderSubtitleCandidate("late-caption", "English",
                                "https://captions.example/late.vtt", Collections.emptyMap())), "");

        request.mergeProviderLinkResult(oversizedUnionSnapshot, "Provider", null);

        assertEquals(8_299, request.sources.size());
        assertTrue("the final provider candidate is retained", containsSource(request.sources,
                "provider-source-4199"));
        assertSame("an overlapping source keeps its published identity", overlappingSource,
                findSource(request.sources, "provider-source-0"));
        assertEquals("provider subtitles merge after the full union is accepted", "English",
                findSource(request.sources, "provider-source-0").subtitles.get(0).language);
    }

    @Test
    public void declaredMediaTypeAndHeadersWorkWithoutMatchingUrlSuffix() {
        java.util.Map<String, String> headers = Collections.singletonMap("User-Agent", "Orbit test client");
        List<ProviderLinkCandidate> candidates = Arrays.asList(
                new ProviderLinkCandidate("hls", "HLS", "Provider",
                        "https://media.example/watch?token=hls", "", 1080, "M3U8", "", headers),
                new ProviderLinkCandidate("dash", "DASH", "Provider",
                        "https://media.example/manifest?token=dash", "", 720, "DASH", "", headers),
                new ProviderLinkCandidate("video", "Video", "Provider",
                        "https://media.example/file?token=video", "", 480, "VIDEO", "", headers));

        PlaybackRequest request = PlaybackRequest.fromProviderLinkResult("profile-id",
                new PlaybackRequest.MediaMetadata("movie-id", "provider-id", "Movie",
                        "Movie", "", "", ""),
                new PlaybackRequest.EpisodeMetadata("", "", -1, -1),
                new ProviderLinkResult(true, candidates, Collections.emptyList(), ""),
                "Provider", 0L);

        assertEquals(3, request.sources.size());
        assertEquals("application/x-mpegurl", request.sources.get(0).mimeType);
        assertEquals("application/dash+xml", request.sources.get(1).mimeType);
        assertEquals("video/mp4", request.sources.get(2).mimeType);
        assertEquals("Orbit test client", request.sources.get(0).headers.get("User-Agent"));
        assertTrue(request.sources.get(0).uri.contains("?token=hls"));
    }

    private static ProviderLinkCandidate link(String id, int quality, String uri, String type) {
        return new ProviderLinkCandidate(id, "", "", uri, "", quality, type, "",
                Collections.emptyMap());
    }

    private static List<ProviderLinkCandidate> providerLinks(int count) {
        List<ProviderLinkCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            candidates.add(link("candidate-" + i, i, providerUri(i), "VIDEO"));
        }
        return candidates;
    }

    private static String providerUri(int index) {
        return "https://media.example/video-" + index + ".mp4";
    }

    private static List<ProviderSubtitleCandidate> longProviderSubtitles(int count) {
        List<ProviderSubtitleCandidate> subtitles = new ArrayList<>();
        String suffix = ".vtt";
        for (int index = 0; index < count; index++) {
            String prefix = "https://captions.example/caption-" + index + "-";
            StringBuilder uri = new StringBuilder(prefix);
            while (uri.length() < 4096 - suffix.length()) uri.append('x');
            uri.append(suffix);
            subtitles.add(new ProviderSubtitleCandidate("caption-" + index, "English",
                    uri.toString(), Collections.emptyMap()));
        }
        return subtitles;
    }

    private static List<String> sourceUris(List<PlaybackRequest.Source> sources) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (PlaybackRequest.Source source : sources) result.add(source.uri);
        return result;
    }

    private static List<String> sourceIds(List<PlaybackRequest.Source> sources) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (PlaybackRequest.Source source : sources) result.add(source.id);
        return result;
    }

    private static boolean containsSource(List<PlaybackRequest.Source> sources, String id) {
        return findSource(sources, id) != null;
    }

    private static PlaybackRequest.Source findSource(List<PlaybackRequest.Source> sources, String id) {
        for (PlaybackRequest.Source source : sources) if (id.equals(source.id)) return source;
        return null;
    }
}
