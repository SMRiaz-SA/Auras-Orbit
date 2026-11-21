package com.auras.orbit.jellyfin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Exercise identity, consumed server offsets, and adversarial paging responses. */
public final class JellyfinPageAccumulatorRegression {
    private static long checks;
    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    private static JellyfinModels.MediaItem item(String id) {
        return new JellyfinModels.MediaItem(id, "Test", "Movie", "", "", "", 0, 0,
                false, 2026, 0, 0);
    }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("bad input was not rejected");
    }
    public static void main(String[] args) {
        JellyfinPageAccumulator.Page p = JellyfinPageAccumulator.first(0,
                List.of(item("A"), item("B")), 6);
        check(p.items.size() == 2 && p.nextServerIndex == 2 && p.hasMore(), "first page");
        p = JellyfinPageAccumulator.append(p, List.of(item("A"), item("C")), 6);
        check(p.items.size() == 3 && p.nextServerIndex == 4, "server offset and dedup differ");
        p = JellyfinPageAccumulator.append(p, List.of(item("A"), item("B")), 6);
        check(p.items.size() == 3 && !p.hasMore() && p.stalled, "repeated page terminates");
        check(JellyfinPageAccumulator.first(0, Collections.emptyList(), 100).stalled,
                "empty response terminates");
        check(JellyfinPageAccumulator.first(Integer.MAX_VALUE - 1,
                List.of(item("a"), item("b"), item("c")), Integer.MAX_VALUE)
                .nextServerIndex == Integer.MAX_VALUE, "overflow clamps");
        List<JellyfinModels.MediaItem> oversized = new ArrayList<>();
        for (int i = 0; i < 101; i++) oversized.add(item("x" + i));
        rejected(() -> JellyfinPageAccumulator.first(0, oversized, 200));
        rejected(() -> JellyfinPageAccumulator.append(null, Collections.emptyList(), 0));
        rejected(() -> JellyfinPageAccumulator.first(0, null, 0));
        try {
            JellyfinPageAccumulator.first(0, List.of(item("Q")), 1).items.add(item("Y"));
            throw new AssertionError("mutable output");
        } catch (UnsupportedOperationException expected) { checks++; }
        JellyfinPageAccumulator.Page large = JellyfinPageAccumulator.empty();
        for (int base = 0; base < JellyfinPageAccumulator.MAX_LIBRARY_ITEMS; base += 100) {
            List<JellyfinModels.MediaItem> group = new ArrayList<>();
            for (int offset = 0; offset < 100; offset++) group.add(item("large-" + (base + offset)));
            large = JellyfinPageAccumulator.append(large, group, 30_000);
        }
        check(large.items.size() == 20_000 && large.limitReached && !large.hasMore(),
                "large library explicitly bounded");
        Random rng = new Random(607233L);
        for (int test = 0; test < 10_000; test++) {
            int total = 1 + rng.nextInt(300);
            int size = 1 + rng.nextInt(70);
            List<JellyfinModels.MediaItem> first = new ArrayList<>();
            for (int i = 0; i < size; i++) first.add(item("id-" + rng.nextInt(800)));
            JellyfinPageAccumulator.Page state = JellyfinPageAccumulator.first(0, first, total);
            check(state.nextServerIndex == size, "initial server count");
            check(state.items.size() <= size, "bounded unique count");
            check(state.totalRecords >= state.nextServerIndex, "nondecreasing total");
            if (state.hasMore()) {
                List<JellyfinModels.MediaItem> additional = new ArrayList<>();
                int nextSize = 1 + rng.nextInt(70);
                for (int i = 0; i < nextSize; i++) additional.add(item("id-" + rng.nextInt(800)));
                JellyfinPageAccumulator.Page after = JellyfinPageAccumulator.append(state, additional, total);
                check(after.nextServerIndex >= state.nextServerIndex, "server offset monotonic");
                check(after.items.size() >= state.items.size(), "items monotonic");
                check(after.totalRecords >= after.nextServerIndex, "new total valid");
                check(after.items.size() <= state.items.size() + nextSize, "growth budget");
            }
        }
        System.out.println("JELLYFIN_PAGE_ACCUMULATOR_PASS checks=" + checks);
    }
}
