package com.auras.orbit.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Format;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class SubtitleOffsetParserFactoryInstrumentationTest {
    @Test public void parsesSubripBodyOnMedia3VttTrackAndRetainsConfiguredOffset() {
        Format format = new Format.Builder()
                .setSampleMimeType("application/x-media3-cues")
                .setCodecs("text/vtt")
                .build();
        SubtitleOffsetParserFactory factory = new SubtitleOffsetParserFactory(
                new DefaultSubtitleParserFactory(), () -> 1_500L);
        assertTrue(factory.supportsFormat(format));
        SubtitleParser parser = factory.create(format);
        byte[] sample = ("1\r\n00:00:00,500 --> 00:00:02,000\r\nEnglish subtitle\r\n")
                .getBytes(StandardCharsets.UTF_8);
        List<CuesWithTiming> cues = new ArrayList<>();

        parser.parse(sample, 0, sample.length, SubtitleParser.OutputOptions.allCues(), cues::add);

        assertEquals(1, cues.size());
        assertEquals(2_000_000L, cues.get(0).startTimeUs);
        assertEquals(1_500_000L, cues.get(0).durationUs);
        assertEquals("English subtitle", cues.get(0).cues.get(0).text.toString());
    }
}
