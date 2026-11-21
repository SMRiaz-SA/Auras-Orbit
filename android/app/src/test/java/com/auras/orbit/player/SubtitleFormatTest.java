package com.auras.orbit.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class SubtitleFormatTest {
    @Test public void mapsSupportedSubtitleSuffixesIgnoringCaseAndQuery() {
        assertEquals("text/vtt", SubtitleFormat.mimeType("https://sub.example/captions.VTT?token=x", null));
        assertEquals("application/x-subrip", SubtitleFormat.mimeType("https://sub.example/captions.srt", null));
        assertEquals("application/ttml+xml", SubtitleFormat.mimeType("https://sub.example/captions.ttml", null));
        assertEquals("application/ttml+xml", SubtitleFormat.mimeType("https://sub.example/captions.xml#track", null));
        assertEquals("text/x-ssa", SubtitleFormat.mimeType("https://sub.example/captions.ssa", null));
        assertEquals("text/x-ssa", SubtitleFormat.mimeType("https://sub.example/captions.ass", null));
    }

    @Test public void usesOnlyAnAllowedReportedMimeForExtensionlessUri() {
        assertEquals("text/vtt", SubtitleFormat.mimeType(
                "https://sub.example/download/42?token=x", "text/vtt; charset=utf-8"));
        assertEquals("application/x-subrip", SubtitleFormat.mimeType(
                "https://sub.example/download/42", "application/x-subrip"));
        assertEquals("application/ttml+xml", SubtitleFormat.mimeType(
                "https://sub.example/download/42", "application/ttml+xml; charset=UTF-8"));
        assertEquals("text/x-ssa", SubtitleFormat.mimeType(
                "https://sub.example/download/42", "text/x-ssa"));
        assertNull(SubtitleFormat.mimeType("https://sub.example/download/42", null));
        assertNull(SubtitleFormat.mimeType("https://sub.example/download/42", "text/html"));
    }

    @Test public void doesNotTreatUnknownSuffixAsSubtitleFromAnUntrustedHeader() {
        assertNull(SubtitleFormat.mimeType("https://sub.example/captions.exe", "text/vtt"));
    }

    @Test public void extensionlessDownloadUsesEffectiveSubtitleFilename() {
        assertEquals("application/x-subrip", SubtitleFormat.mimeType(
                "https://sub.example/download/47", null, "episode.eng.SRT"));
        assertEquals("text/x-ssa", SubtitleFormat.mimeType(
                "https://sub.example/download/48", null, "episode.ass"));
        assertNull(SubtitleFormat.mimeType(
                "https://sub.example/download/49", null, "episode.txt"));
        assertNull(SubtitleFormat.mimeType(
                "https://sub.example/download/episode.exe", null, "episode.srt"));
    }
}
