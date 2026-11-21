package com.auras.orbit.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class SubtitleContentSnifferTest {
    @Test public void detectsSubripBodyForMedia3CueTrackDeclaredAsVtt() {
        byte[] body = ("1\r\n00:00:00,000 --> 00:00:02,000\r\nEnglish subtitle\r\n")
                .getBytes(StandardCharsets.UTF_8);

        assertTrue(SubtitleContentSniffer.shouldUseSubripFallback(
                "application/x-media3-cues", "text/vtt", body, 0, body.length));
    }

    @Test public void doesNotOverrideActualWebvttOrOtherFormats() {
        byte[] webvtt = "WEBVTT\n\n00:00:00.000 --> 00:00:02.000\nText"
                .getBytes(StandardCharsets.UTF_8);
        byte[] subrip = "1\n00:00:00,000 --> 00:00:02,000\nText"
                .getBytes(StandardCharsets.UTF_8);

        assertFalse(SubtitleContentSniffer.shouldUseSubripFallback(
                "text/vtt", "", webvtt, 0, webvtt.length));
        assertFalse(SubtitleContentSniffer.shouldUseSubripFallback(
                "application/x-media3-cues", "text/ssa", subrip, 0, subrip.length));
    }

    @Test public void rejectsSubripLookingDataOutsideRequestedByteRange() {
        byte[] body = "ignored 1\n00:00:00,000 --> 00:00:02,000\nText".getBytes(StandardCharsets.UTF_8);

        assertFalse(SubtitleContentSniffer.shouldUseSubripFallback(
                "text/vtt", null, body, 0, 8));
        assertFalse(SubtitleContentSniffer.shouldUseSubripFallback(
                "text/vtt", null, body, -1, body.length));
    }
}
