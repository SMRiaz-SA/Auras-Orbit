package com.auras.orbit.player;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class SubtitleResultInterleaverTest {
    @Test public void interleavesProviderBatchesInRoundRobinOrder() {
        List<List<String>> batches = Arrays.asList(
                Arrays.asList("opensubs-1", "opensubs-2", "opensubs-3"),
                Arrays.asList("subsource-1"),
                Arrays.asList("subdl-1", "subdl-2"));

        assertEquals(Arrays.asList("opensubs-1", "subsource-1", "subdl-1",
                        "opensubs-2", "subdl-2", "opensubs-3"),
                SubtitleResultInterleaver.interleave(batches, 20));
    }

    @Test public void respectsTotalLimitAcrossProviderBatches() {
        List<List<Integer>> batches = Arrays.asList(
                Arrays.asList(1, 2, 3), Arrays.asList(4, 5, 6));

        assertEquals(Arrays.asList(1, 4, 2), SubtitleResultInterleaver.interleave(batches, 3));
        assertEquals(Collections.emptyList(), SubtitleResultInterleaver.interleave(batches, 0));
    }
}
