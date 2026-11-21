package com.auras.orbit.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Format;
import androidx.media3.common.util.Consumer;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public final class SubtitleOffsetParserFactoryTest {
    @Test public void parserFactoryRecognizesMedia3CueTrackWithVttCodecAndSubripBody() {
        Format format = new Format.Builder()
                .setSampleMimeType("application/x-media3-cues")
                .setCodecs("text/vtt")
                .build();
        SubtitleOffsetParserFactory factory = new SubtitleOffsetParserFactory(
                new androidx.media3.extractor.text.DefaultSubtitleParserFactory(), () -> 0L);
        byte[] body = "1\r\n00:00:00,000 --> 00:00:02,000\r\nEnglish subtitle\r\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(factory.supportsFormat(format));
        assertNotNull(factory.create(format));
        assertTrue(SubtitleContentSniffer.shouldUseSubripFallback(
                format.sampleMimeType, format.codecs, body, 0, body.length));
    }

    @Test public void parserFactoryShiftsCueStartAndKeepsDuration() {
        SubtitleParser.Factory delegate = fixedParserFactory(
                new CuesWithTiming(Collections.emptyList(), 1_500_000L, 4_000_000L), null);
        SubtitleParser parser = new SubtitleOffsetParserFactory(delegate, () -> 1_000L)
                .create(new Format.Builder().setSampleMimeType("application/ttml+xml").build());
        List<CuesWithTiming> output = new ArrayList<>();

        parser.parse(new byte[0], 0, 0, SubtitleParser.OutputOptions.allCues(), output::add);

        assertEquals(1, output.size());
        assertEquals(2_500_000L, output.get(0).startTimeUs);
        assertEquals(4_000_000L, output.get(0).durationUs);
    }

    @Test public void parserFactoryAppliesCurrentOffsetWhenParsingEachSample() {
        long[] delayMs = {0L};
        SubtitleParser parser = new SubtitleOffsetParserFactory(
                fixedParserFactory(new CuesWithTiming(Collections.emptyList(), 500_000L, 2_000_000L), null),
                () -> delayMs[0])
                .create(new Format.Builder().setSampleMimeType("application/ttml+xml").build());
        List<CuesWithTiming> first = new ArrayList<>();
        parser.parse(new byte[0], 0, 0, SubtitleParser.OutputOptions.allCues(), first::add);
        delayMs[0] = -1_000L;
        List<CuesWithTiming> second = new ArrayList<>();
        parser.parse(new byte[0], 0, 0, SubtitleParser.OutputOptions.allCues(), second::add);

        assertEquals(500_000L, first.get(0).startTimeUs);
        assertEquals(0L, second.get(0).startTimeUs);
    }

    @Test public void negativeDelayDoesNotSkipCuesAtTimelineZero() {
        AtomicLong delegatedThresholdUs = new AtomicLong(Long.MIN_VALUE);
        SubtitleParser parser = new SubtitleOffsetParserFactory(
                fixedParserFactory(new CuesWithTiming(Collections.emptyList(), 0L, 2_000_000L),
                        delegatedThresholdUs),
                () -> -1_000L)
                .create(new Format.Builder().setSampleMimeType("application/ttml+xml").build());

        parser.parse(new byte[0], 0, 0, SubtitleParser.OutputOptions.onlyCuesAfter(0L), ignored -> { });

        assertEquals(0L, delegatedThresholdUs.get());
    }

    @Test public void parserPassesTimeUnsetOutputThresholdWithoutArithmetic() {
        AtomicLong delegatedThresholdUs = new AtomicLong(Long.MIN_VALUE);
        SubtitleParser parser = new SubtitleOffsetParserFactory(
                fixedParserFactory(new CuesWithTiming(Collections.emptyList(), 0L, 2_000_000L),
                        delegatedThresholdUs),
                () -> 1_000L)
                .create(new Format.Builder().setSampleMimeType("application/ttml+xml").build());

        parser.parse(new byte[0], 0, 0,
                SubtitleParser.OutputOptions.onlyCuesAfter(androidx.media3.common.C.TIME_UNSET),
                ignored -> { });

        assertEquals(androidx.media3.common.C.TIME_UNSET, delegatedThresholdUs.get());
    }

    @Test public void negativeDelayClampsCueDurationInDelegatedParser() {
        SubtitleParser parser = new SubtitleOffsetParserFactory(
                fixedParserFactory(new CuesWithTiming(Collections.emptyList(),
                        500_000L, 1_000_000L), null),
                () -> -1_000L)
                .create(new Format.Builder().setSampleMimeType("application/ttml+xml").build());
        List<CuesWithTiming> output = new ArrayList<>();

        parser.parse(new byte[0], 0, 0, SubtitleParser.OutputOptions.allCues(), output::add);

        assertEquals(1, output.size());
        assertEquals(0L, output.get(0).startTimeUs);
        assertEquals(500_000L, output.get(0).durationUs);
    }

    private static SubtitleParser.Factory fixedParserFactory(CuesWithTiming emitted,
            AtomicLong delegatedThresholdUs) {
        return new SubtitleParser.Factory() {
            @Override public boolean supportsFormat(Format format) { return true; }
            @Override public int getCueReplacementBehavior(Format format) { return 0; }
            @Override public SubtitleParser create(Format format) {
                return new SubtitleParser() {
                    @Override public void parse(byte[] data, int offset, int length,
                            OutputOptions options, Consumer<CuesWithTiming> output) {
                        if (delegatedThresholdUs != null) delegatedThresholdUs.set(options.startTimeUs);
                        output.accept(emitted);
                    }
                    @Override public int getCueReplacementBehavior() { return 0; }
                };
            }
        };
    }
}
