package com.auras.orbit.player;

import java.util.List;
import java.util.Set;

/** Chooses the next provider source strictly after the active source in quality order. */
final class SourceFailoverPolicy {
    private SourceFailoverPolicy() { }

    static int nextUnattemptedIndexAfterCurrent(List<String> sourceIds, String currentSourceId,
            Set<String> attemptedSourceIds, Set<String> failedSourceIds) {
        if (sourceIds == null || sourceIds.isEmpty() || currentSourceId == null
                || currentSourceId.isEmpty()) return -1;

        int currentIndex = -1;
        for (int index = 0; index < sourceIds.size(); index++) {
            if (currentSourceId.equals(sourceIds.get(index))) {
                currentIndex = index;
                break;
            }
        }
        if (currentIndex < 0) return -1;

        for (int index = currentIndex + 1; index < sourceIds.size(); index++) {
            String sourceId = sourceIds.get(index);
            if (sourceId == null || sourceId.isEmpty()) continue;
            if (attemptedSourceIds != null && attemptedSourceIds.contains(sourceId)) continue;
            if (failedSourceIds != null && failedSourceIds.contains(sourceId)) continue;
            return index;
        }
        return -1;
    }
}
