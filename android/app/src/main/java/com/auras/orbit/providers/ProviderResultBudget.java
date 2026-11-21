package com.auras.orbit.providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Preserves complete admitted provider data; UI renders independent 40-card pages. */
public final class ProviderResultBudget {
    public static final int MAX_SEARCH_ITEMS = 25_000;
    public static final int MAX_SEARCH_INPUT_SCAN = 25_000;
    public static final int MAX_HOME_SECTIONS = 2048;
    public static final int MAX_HOME_ITEMS_PER_SECTION = HomeSection.MAX_DISPLAY_ITEMS;
    private ProviderResultBudget() { }

    public static List<AttributedSearchHit> search(ProviderDescriptor provider,
            List<MediaItem> items, SearchType type) {
        if (provider == null || items == null || type == null) {
            throw new IllegalArgumentException("Provider, items, and search type are required");
        }
        int count = items.size();
        if (count < 0 || count > MAX_SEARCH_INPUT_SCAN) {
            throw new IllegalArgumentException("Provider search exceeds emergency conversion ceiling; no partial result returned");
        }
        ArrayList<AttributedSearchHit> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            MediaItem item = items.get(index);
            if (item == null || !matches(type, item.kind)) continue;
            result.add(new AttributedSearchHit(provider, item));
        }
        return Collections.unmodifiableList(result);
    }

    public static List<AttributedHomeSection> home(ProviderDescriptor provider,
            int sourceOrder, List<HomeSection> sections) {
        if (provider == null || sourceOrder < 0 || sections == null) {
            throw new IllegalArgumentException("Invalid Home response");
        }
        int count = sections.size();
        if (count < 0 || count > MAX_HOME_SECTIONS) {
            throw new IllegalArgumentException("Provider Home exceeds emergency shelf ceiling; no partial result returned");
        }
        ArrayList<AttributedHomeSection> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            HomeSection section = sections.get(index);
            if (section == null) continue;
            result.add(new AttributedHomeSection(provider, sourceOrder, section));
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean matches(SearchType type, MediaKind kind) {
        if (type == SearchType.ALL) return true;
        return type == SearchType.MOVIES ? kind == MediaKind.MOVIE : kind == MediaKind.SERIES;
    }
}
