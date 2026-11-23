package com.auras.orbit.providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Result of CS3 loadLinks; candidates are not opened, persisted, or handed to a player here. */
public final class ProviderLinkResult {
    /** Safe, user-visible diagnostic when CS3 source metadata reaches the retention budget. */
    public static final String LINK_METADATA_LIMIT_DIAGNOSTIC =
            "Some remaining provider results could not be retained because their combined source metadata exceeded Orbit's 32 MiB safety budget.";
    public static final String STREMIO_SUBTITLE_METADATA_DIAGNOSTIC =
            "Some Stremio subtitle tracks were omitted because URLs exceeded their size limit or the response exceeded Orbit's 1,000-track metadata limit.";
    public static final int MAX_SUBTITLES = 1024;
    public final boolean resolved;
    public final List<ProviderLinkCandidate> links;
    public final List<ProviderSubtitleCandidate> subtitles;
    /** Subtitle callbacks rejected before a candidate could be materialized; bounded at 1,024. */
    public final int rejectedSubtitleCount;
    public final String diagnostic;
    /** Original CS3 callback payload, retained only for the current action chooser. */
    public final Cs3ActionContext cs3Actions;

    /** Callback indexes sorted by quality for playback; stable sorting preserves callback order. */
    public List<Integer> orderedLinkIndexesByQuality() {
        ArrayList<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < links.size(); index++) {
            if (links.get(index) != null) indexes.add(index);
        }
        Collections.sort(indexes, (left, right) -> Integer.compare(
                links.get(right).quality, links.get(left).quality));
        return indexes.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(indexes);
    }

    public ProviderLinkResult(boolean resolved, List<ProviderLinkCandidate> links,
                              List<ProviderSubtitleCandidate> subtitles, String diagnostic) {
        this(resolved, links, subtitles, diagnostic, null, 0);
    }

    public ProviderLinkResult(boolean resolved, List<ProviderLinkCandidate> links,
                              List<ProviderSubtitleCandidate> subtitles, String diagnostic,
                              Cs3ActionContext cs3Actions) {
        this(resolved, links, subtitles, diagnostic, cs3Actions, 0);
    }

    public ProviderLinkResult(boolean resolved, List<ProviderLinkCandidate> links,
                              List<ProviderSubtitleCandidate> subtitles, String diagnostic,
                              Cs3ActionContext cs3Actions, int rejectedSubtitleCount) {
        this.resolved = resolved;
        this.links = immutable(links, "links");
        this.subtitles = immutable(subtitles, MAX_SUBTITLES, "subtitles");
        this.rejectedSubtitleCount = Math.max(0, Math.min(MAX_SUBTITLES,
                rejectedSubtitleCount));
        this.diagnostic = diagnostic == null ? "" : diagnostic.trim();
        this.cs3Actions = cs3Actions;
        if (resolved && this.links.isEmpty()
                && (cs3Actions == null || cs3Actions.getResult().getLinks().isEmpty())) {
            throw new IllegalArgumentException("A successful link resolution must provide at least one link");
        }
    }

    private static <T> List<T> immutable(List<T> value, String category) {
        if (value == null) return Collections.emptyList();
        // Snapshot the callback's reported count and index only that many entries. Avoid
        // ArrayList(Collection), which may invoke a provider-controlled toArray().
        int count = value.size();
        if (count < 0) throw new IllegalArgumentException("Provider returned an invalid " + category + " count.");
        if (count == 0) return Collections.emptyList();
        ArrayList<T> copy = new ArrayList<>();
        // Avoid a provider-controlled toArray() implementation, too.
        for (int index = 0; index < count; index++) copy.add(value.get(index));
        return Collections.unmodifiableList(copy);
    }

    private static <T> List<T> immutable(List<T> value, int maximum, String category) {
        if (value != null && value.size() > maximum) {
            throw new IllegalArgumentException("Provider returned too many " + category + ".");
        }
        return immutable(value, category);
    }
}
