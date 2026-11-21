package com.auras.orbit.player;

import static org.junit.Assert.assertEquals;

import com.auras.orbit.providers.ProviderLinkCandidate;
import com.auras.orbit.providers.ProviderLinkResult;
import com.auras.orbit.providers.ProviderSubtitleCandidate;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class PlaybackRequestSubtitleDiagnosticsInstrumentationTest {
    @Test public void requestPublishesRejectedSubtitleCountForPlayerDiagnostic() {
        ProviderLinkCandidate video = new ProviderLinkCandidate("video", "", "StreamPlay",
                "https://video.example/stream.m3u8", "", 400, "M3U8", "",
                Collections.emptyMap());
        ProviderSubtitleCandidate rejected = new ProviderSubtitleCandidate("rejected", "English",
                "http://subs.example/subtitle.srt?token=x", Collections.emptyMap());
        ProviderSubtitleCandidate accepted = new ProviderSubtitleCandidate("accepted", "English",
                "https://subs.example/subtitle.srt", Collections.emptyMap());
        ProviderLinkResult result = new ProviderLinkResult(true,
                Collections.singletonList(video), Arrays.asList(rejected, accepted), "");

        PlaybackRequest request = PlaybackRequest.fromProviderLinkResult("profile",
                new PlaybackRequest.MediaMetadata("media", "provider", "Title", "movie",
                        "", "", ""), null, result, "StreamPlay", 0L);

        assertEquals(1, request.getDefaultSource().unsupportedSubtitleCount);
        assertEquals(1, request.getDefaultSource().subtitles.size());
    }

    @Test public void requestRetainsSafeUnknownAndKnownProviderSubtitleFormats() {
        ProviderLinkCandidate video = new ProviderLinkCandidate("video", "", "StreamPlay",
                "https://video.example/stream.m3u8", "", 400, "M3U8", "",
                Collections.emptyMap());
        ProviderSubtitleCandidate unknown = new ProviderSubtitleCandidate("unknown", "English",
                "https://subs.example/subtitle.txt", Collections.emptyMap());
        ProviderSubtitleCandidate known = new ProviderSubtitleCandidate("known", "French",
                "https://subs.example/subtitle.srt", Collections.emptyMap());
        ProviderLinkResult result = new ProviderLinkResult(true,
                Collections.singletonList(video), Arrays.asList(unknown, known), "");

        PlaybackRequest request = PlaybackRequest.fromProviderLinkResult("profile",
                new PlaybackRequest.MediaMetadata("media", "provider", "Title", "movie",
                        "", "", ""), null, result, "StreamPlay", 0L);

        assertEquals(0, request.getDefaultSource().unsupportedSubtitleCount);
        assertEquals(2, request.getDefaultSource().subtitles.size());
        assertEquals(SubtitleFormat.UNKNOWN_MIME_TYPE,
                request.getDefaultSource().subtitles.get(0).mimeType);
        assertEquals("application/x-subrip",
                request.getDefaultSource().subtitles.get(1).mimeType);
    }
}
