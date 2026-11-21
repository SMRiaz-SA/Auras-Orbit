package com.auras.orbit.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Flattens provider result batches in round-robin order so one provider cannot dominate. */
public final class SubtitleResultInterleaver {
    private SubtitleResultInterleaver() { }

    public static <T> List<T> interleave(List<? extends List<? extends T>> batches, int limit) {
        if (batches == null || batches.isEmpty() || limit <= 0) return Collections.emptyList();
        ArrayList<T> results = new ArrayList<>(Math.min(limit, 90));
        for (int index = 0; results.size() < limit; index++) {
            boolean foundAtIndex = false;
            for (List<? extends T> batch : batches) {
                if (batch == null || index >= batch.size()) continue;
                foundAtIndex = true;
                T item = batch.get(index);
                if (item != null) results.add(item);
                if (results.size() >= limit) break;
            }
            if (!foundAtIndex) break;
        }
        return Collections.unmodifiableList(results);
    }
}
