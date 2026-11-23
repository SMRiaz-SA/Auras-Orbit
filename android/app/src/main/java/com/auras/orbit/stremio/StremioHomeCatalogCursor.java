package com.auras.orbit.stremio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Opaque provider Home cursor for fresh catalog scans and deferred retry indices. */
final class StremioHomeCatalogCursor {
    private static final String VERSIONED_PREFIX = "stremio-home-v2:";
    private static final String LEGACY_PREFIX = "stremio-home-catalog:";
    final int nextFreshIndex;
    final List<Integer> failedCatalogIndices;

    private StremioHomeCatalogCursor(int nextFreshIndex, List<Integer> failedCatalogIndices) {
        this.nextFreshIndex = nextFreshIndex;
        this.failedCatalogIndices = failedCatalogIndices;
    }

    static StremioHomeCatalogCursor create(int nextFreshIndex, List<Integer> failed,
            int catalogCount) {
        if (catalogCount < 0 || nextFreshIndex < 0 || nextFreshIndex > catalogCount) {
            throw new IllegalArgumentException("Home catalog cursor index is outside the manifest");
        }
        LinkedHashSet<Integer> ordered = new LinkedHashSet<>();
        if (failed != null) for (Integer index : failed) {
            if (index == null || index < 0 || index >= nextFreshIndex || index >= catalogCount) {
                throw new IllegalArgumentException("Failed catalog index is outside the scanned range");
            }
            ordered.add(index);
        }
        return new StremioHomeCatalogCursor(nextFreshIndex,
                Collections.unmodifiableList(new ArrayList<>(ordered)));
    }

    static StremioHomeCatalogCursor parse(String cursor, int catalogCount) throws IOException {
        if (catalogCount < 0) throw new IOException("The Stremio catalog count is invalid.");
        if (cursor == null || cursor.isEmpty()) {
            return new StremioHomeCatalogCursor(0, Collections.emptyList());
        }
        if (cursor.startsWith(LEGACY_PREFIX)) {
            int index = parseIndex(cursor.substring(LEGACY_PREFIX.length()));
            if (index < 0 || index >= catalogCount) {
                throw new IOException("The Stremio Home catalog cursor is outside the manifest.");
            }
            return new StremioHomeCatalogCursor(index, Collections.emptyList());
        }
        if (!cursor.startsWith(VERSIONED_PREFIX) || cursor.length() > 16_384) {
            throw new IOException("The Stremio Home catalog cursor is invalid.");
        }
        String[] fields = cursor.substring(VERSIONED_PREFIX.length()).split(":", -1);
        if (fields.length != 2) throw new IOException("The Stremio Home catalog cursor is invalid.");
        int nextFreshIndex = parseIndex(fields[0]);
        if (nextFreshIndex < 0 || nextFreshIndex > catalogCount) {
            throw new IOException("The Stremio Home catalog cursor is outside the manifest.");
        }
        ArrayList<Integer> failed = new ArrayList<>();
        if (!fields[1].isEmpty()) {
            Set<Integer> seen = new LinkedHashSet<>();
            for (String value : fields[1].split(",", -1)) {
                int index = parseIndex(value);
                if (index < 0 || !seen.add(index) || index >= nextFreshIndex
                        || index >= catalogCount) {
                    throw new IOException("The Stremio Home retry indices are invalid.");
                }
                failed.add(index);
            }
        }
        if (nextFreshIndex == catalogCount && failed.isEmpty()) {
            throw new IOException("The Stremio Home cursor has no remaining catalogs.");
        }
        return new StremioHomeCatalogCursor(nextFreshIndex,
                Collections.unmodifiableList(failed));
    }

    String encode(int catalogCount) {
        StremioHomeCatalogCursor validated = create(nextFreshIndex, failedCatalogIndices, catalogCount);
        if (validated.nextFreshIndex == catalogCount && validated.failedCatalogIndices.isEmpty()) return "";
        StringBuilder encoded = new StringBuilder(VERSIONED_PREFIX)
                .append(validated.nextFreshIndex).append(':');
        for (int i = 0; i < validated.failedCatalogIndices.size(); i++) {
            if (i > 0) encoded.append(',');
            encoded.append(validated.failedCatalogIndices.get(i));
        }
        return encoded.toString();
    }

    boolean hasMoreFresh(int catalogCount) { return nextFreshIndex < catalogCount; }

    boolean isRetryOnly(int catalogCount) {
        return nextFreshIndex >= catalogCount && !failedCatalogIndices.isEmpty();
    }

    StremioHomeCatalogCursor afterFreshAttempt(int catalogIndex, boolean failed,
            int catalogCount) throws IOException {
        if (catalogCount < 0 || catalogIndex != nextFreshIndex || catalogIndex >= catalogCount) {
            throw new IOException("The Stremio Home fresh-catalog cursor is invalid.");
        }
        ArrayList<Integer> failures = new ArrayList<>(failedCatalogIndices);
        if (failed) failures.add(catalogIndex);
        try {
            return create(catalogIndex + 1, failures, catalogCount);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("The Stremio Home retry indices are invalid.");
        }
    }

    static List<Integer> remainingFailures(List<Integer> failed, List<Integer> attempted,
            List<Integer> stillFailed) {
        Set<Integer> attemptedSet = new java.util.HashSet<>(attempted == null
                ? Collections.<Integer>emptyList() : attempted);
        Set<Integer> stillFailedSet = new java.util.HashSet<>(stillFailed == null
                ? Collections.<Integer>emptyList() : stillFailed);
        LinkedHashSet<Integer> remaining = new LinkedHashSet<>();
        if (failed != null) for (Integer index : failed) {
            if (index != null && !attemptedSet.contains(index)) {
                remaining.add(index);
            }
        }
        if (failed != null) for (Integer index : failed) {
            if (index != null && attemptedSet.contains(index) && stillFailedSet.contains(index)) {
                remaining.add(index);
            }
        }
        // Production supplies a subset of attempted indices here. Preserve any unexpected
        // provider result at the end rather than dropping retry work or reordering the queue.
        if (stillFailed != null) for (Integer index : stillFailed) {
            if (index != null) remaining.add(index);
        }
        return Collections.unmodifiableList(new ArrayList<>(remaining));
    }

    private static int parseIndex(String value) throws IOException {
        if (value == null || !value.matches("0|[1-9][0-9]*")) {
            throw new IOException("The Stremio Home catalog index is invalid.");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IOException("The Stremio Home catalog index is invalid.");
        }
    }
}
