package com.auras.orbit.player;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.auras.orbit.providers.Cs3PlaybackHooks;
import com.auras.orbit.providers.ProviderLinkCandidate;
import com.auras.orbit.providers.ProviderLinkResult;
import com.auras.orbit.providers.ProviderOperationCoordinator;
import com.auras.orbit.providers.ProviderSubtitleCandidate;
import com.lagradost.cloudstream3.MainAPI;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Regression coverage for late provider subtitles and source metadata during playback. */
@RunWith(AndroidJUnit4.class)
public final class PlaybackRequestProgressiveSubtitleTest {
    @Test public void providerLevelSubtitleIsNormalizedOnceAcrossSources() {
        ProviderLinkCandidate first = new ProviderLinkCandidate("first", "1080p",
                "Test provider", "https://media.example/first.mp4", "", 1080, "VIDEO", "",
                Collections.emptyMap());
        ProviderLinkCandidate second = new ProviderLinkCandidate("second", "720p",
                "Test provider", "https://media.example/second.mp4", "", 720, "VIDEO", "",
                Collections.emptyMap());
        ProviderSubtitleCandidate subtitle = new ProviderSubtitleCandidate("subtitle-1",
                "English", "https://captions.example/movie.vtt", Collections.emptyMap());

        PlaybackRequest request = PlaybackRequest.fromProviderLinkResult("profile-1",
                new PlaybackRequest.MediaMetadata("movie-1", "provider-1", "Movie", "MOVIE",
                        "2026", "", ""), new PlaybackRequest.EpisodeMetadata("", "", -1, -1),
                result(Arrays.asList(first, second), Collections.singletonList(subtitle)),
                "Test provider", 0L);

        assertEquals(2, request.sources.size());
        assertEquals(1, request.sources.get(0).subtitles.size());
        assertEquals(1, request.sources.get(1).subtitles.size());
        assertSame("one provider-level subtitle result is shared across compatible sources",
                request.sources.get(0).subtitles.get(0), request.sources.get(1).subtitles.get(0));
    }

    @Test public void crossOriginSubtitleWithItsOwnHeadersIsRetained() throws Exception {
        ProviderLinkCandidate link = new ProviderLinkCandidate("video", "1080p",
                "Test provider", "https://video.example/movie.mp4", "", 1080, "VIDEO", "",
                Collections.singletonMap("Authorization", "video-token"));
        LinkedHashMap<String, String> subtitleHeaders = new LinkedHashMap<>();
        subtitleHeaders.put("Authorization", "subtitle-token");
        subtitleHeaders.put("Referer", "https://provider.example/watch");
        ProviderSubtitleCandidate subtitle = new ProviderSubtitleCandidate("subtitle-1", "English",
                "https://captions.example/movie.vtt", subtitleHeaders);

        PlaybackRequest request = PlaybackRequest.fromProviderLinkResult("profile-1",
                new PlaybackRequest.MediaMetadata("movie-1", "provider-1", "Movie", "MOVIE",
                        "2026", "", ""), new PlaybackRequest.EpisodeMetadata("", "", -1, -1),
                result(link, Collections.singletonList(subtitle)), "Test provider", 0L);

        assertEquals("a cross-origin caption with its own request headers remains selectable", 1,
                request.getDefaultSource().subtitles.size());
        assertEquals("caption credentials stay attached to the caption request",
                subtitleHeaders, field(request.getDefaultSource().subtitles.get(0), "headers"));
        assertEquals("video credentials stay attached to the video source",
                "video-token", request.getDefaultSource().headers.get("Authorization"));
    }

    @Test public void sameOriginSubtitleCanUseHeadersDifferentFromTheVideoHeaders() throws Exception {
        ProviderLinkCandidate link = new ProviderLinkCandidate("video", "1080p",
                "Test provider", "https://media.example/movie.mp4", "", 1080, "VIDEO", "",
                Collections.singletonMap("Authorization", "video-token"));
        ProviderSubtitleCandidate subtitle = new ProviderSubtitleCandidate("subtitle-1", "English",
                "https://media.example/movie.vtt",
                Collections.singletonMap("X-Subtitle-Token", "subtitle-token"));

        PlaybackRequest request = PlaybackRequest.fromProviderLinkResult("profile-1",
                new PlaybackRequest.MediaMetadata("movie-1", "provider-1", "Movie", "MOVIE",
                        "2026", "", ""), new PlaybackRequest.EpisodeMetadata("", "", -1, -1),
                result(link, Collections.singletonList(subtitle)), "Test provider", 0L);

        assertEquals("a same-origin caption can retain subtitle-only request headers", 1,
                request.getDefaultSource().subtitles.size());
        assertEquals("the caption-specific header is not discarded as a video-header mismatch",
                Collections.singletonMap("X-Subtitle-Token", "subtitle-token"),
                field(request.getDefaultSource().subtitles.get(0), "headers"));
        assertEquals("the video Authorization header is not overwritten by the caption header",
                "video-token", request.getDefaultSource().headers.get("Authorization"));
    }

    @Test public void lateSubtitleMergesWithoutReplacingActiveSourceOrHookOwner() {
        ProviderOperationCoordinator coordinator = new ProviderOperationCoordinator(1);
        Cs3PlaybackHooks hookSeed = new Cs3PlaybackHooks("plugin-1", "profile-1", "provider-1",
                0, "Test provider", "", new MainAPI() { }, null, null, () -> true, coordinator);
        Cs3PlaybackHooks appendedHookSeed = new Cs3PlaybackHooks("plugin-1", "profile-1", "provider-1",
                0, "Test provider", "", new MainAPI() { }, null, null, () -> true, coordinator);
        PlaybackRequest request = null;
        try {
            ProviderLinkCandidate link = new ProviderLinkCandidate("callback-link-0", "720p",
                    "Test provider", "https://media.example/movie.mp4", "", 720, "VIDEO", "",
                    Collections.emptyMap(), Collections.emptyList(), hookSeed);
            request = PlaybackRequest.fromProviderLinkResult("profile-1",
                    new PlaybackRequest.MediaMetadata("movie-1", "provider-1", "Movie", "MOVIE",
                            "2026", "", ""), new PlaybackRequest.EpisodeMetadata("", "", -1, -1),
                    result(link, Collections.emptyList()), "Test provider", 0L);
            request.activateProviderHooks(coordinator);
            PlaybackRequest.Source activeSource = request.sources.get(0);
            Cs3PlaybackHooks activeHooks = request.providerHooksFor(activeSource);
            assertNotNull(activeHooks);

            ProviderSubtitleCandidate lateSubtitle = new ProviderSubtitleCandidate("subtitle-1",
                    "English", "https://media.example/subtitle.vtt", Collections.emptyMap());
            ProviderLinkCandidate updatedLink = new ProviderLinkCandidate("callback-link-0",
                    "720p updated", "Test mirror", "https://media.example/movie.mp4", "",
                    720, "VIDEO", "", Collections.emptyMap(), Collections.emptyList(), hookSeed);
            ProviderLinkCandidate appendedLink = new ProviderLinkCandidate("callback-link-1", "1080p",
                    "Test provider", "https://media.example/movie-1080.mp4", "", 1080, "VIDEO", "",
                    Collections.emptyMap(), Collections.emptyList(), appendedHookSeed);
            ProviderLinkResult snapshot = result(Arrays.asList(updatedLink, appendedLink),
                    Collections.singletonList(lateSubtitle));
            request.mergeProviderLinkResult(snapshot, "Test provider", coordinator);
            request.mergeProviderLinkResult(snapshot, "Test provider", coordinator);

            assertEquals("the original default source remains stable during progressive updates",
                    "provider-source-0", request.defaultSourceId);
            assertEquals("new links remain in provider quality order for failover",
                    Arrays.asList("provider-source-1", "provider-source-0"),
                    Arrays.asList(request.sources.get(0).id, request.sources.get(1).id));
            assertSame("a late metadata snapshot keeps the exact active source object",
                    activeSource, request.sources.get(1));
            assertEquals("provider-source-0", activeSource.id);
            assertEquals("https://media.example/movie.mp4", activeSource.uri);
            assertTrue("the updated source label keeps the link name",
                    activeSource.label.contains("720p updated"));
            assertTrue("the source label keeps the provider name", activeSource.label.contains("Test provider"));
            assertEquals("repeated callback snapshots do not duplicate late subtitles", 1,
                    activeSource.subtitles.size());
            assertEquals("the merged subtitle keeps its candidate identity",
                    "provider-subtitle-1", activeSource.subtitles.get(0).id);
            assertEquals("the merged subtitle remains a safe WebVTT track",
                    "text/vtt", activeSource.subtitles.get(0).mimeType);
            assertSame("subtitle merging retains the active provider hook owner", activeHooks,
                    request.providerHooksFor(activeSource));
            Cs3PlaybackHooks appendedHooks = request.providerHooksFor(request.sources.get(0));
            assertNotNull("a genuinely new link receives its provider hook owner", appendedHooks);
            assertNotSame(activeHooks, appendedHooks);
        } finally {
            if (request != null) request.closeProviderHooks();
            hookSeed.close();
            appendedHookSeed.close();
            coordinator.close();
        }
    }

    private static ProviderLinkResult result(ProviderLinkCandidate link,
            java.util.List<ProviderSubtitleCandidate> subtitles) {
        return result(Collections.singletonList(link), subtitles);
    }

    private static ProviderLinkResult result(List<ProviderLinkCandidate> links,
            List<ProviderSubtitleCandidate> subtitles) {
        return new ProviderLinkResult(false, links, subtitles, "");
    }

    private static Object field(Object instance, String name) throws Exception {
        java.lang.reflect.Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }
}
