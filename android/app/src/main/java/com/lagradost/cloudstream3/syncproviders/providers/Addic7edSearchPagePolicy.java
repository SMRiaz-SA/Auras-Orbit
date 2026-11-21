package com.lagradost.cloudstream3.syncproviders.providers;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/** Maps a successful Addic7ed search-page lookup to either one title page or no matches. */
final class Addic7edSearchPagePolicy {
    private Addic7edSearchPagePolicy() { }

    static <T> List<T> successfulTitleMatch(Supplier<T> selectMatch) {
        T match = selectMatch.get();
        return match == null ? Collections.emptyList() : Collections.singletonList(match);
    }

    static void requireSuccessfulResponse(boolean isSuccessful) {
        if (!isSuccessful) {
            throw new IllegalStateException("Addic7ed returned an unsuccessful HTTP response.");
        }
    }
}
