package com.auras.orbit.providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Result of CS3 loadLinks; candidates are not opened, persisted, or handed to a player here. */
public final class ProviderLinkResult {
    /** Explicit provider callback admission ceilings; retained sources are paged in the UI. */
    // Emergency materialization limits; CS3 callback overflow is explicit, never silent.
    public static final int MAX_LINKS = 4096;
    public static final int MAX_SUBTITLES = 1024;
    public final boolean resolved;
    public final List<ProviderLinkCandidate> links;
    public final List<ProviderSubtitleCandidate> subtitles;
    /** Subtitle callbacks rejected before a candidate could be materialized; bounded at 1,024. */
    public final int rejectedSubtitleCount;
    public final String diagnostic;
    /** Original CS3 callback payload, retained only for the current action chooser. */
    public final Cs3ActionContext cs3Actions;

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
        this.links = immutable(links, MAX_LINKS, "links");
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

    private static <T> List<T> immutable(List<T> value, int max, String category) {
        if (value == null) return Collections.emptyList();
        // Validate before allocating: plugin-owned AbstractList may advertise
        // Integer.MAX_VALUE and ArrayList(Collection) would attempt that allocation.
        int count = value.size();
        if (count > max || count < 0) {
            throw new IllegalArgumentException("Provider returned too many " + category + ".");
        }
        if (count == 0) return Collections.emptyList();
        ArrayList<T> copy = new ArrayList<>(count);
        // Avoid a provider-controlled toArray() implementation, too.
        for (int index = 0; index < count; index++) copy.add(value.get(index));
        return Collections.unmodifiableList(copy);
    }
}
