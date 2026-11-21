package com.lagradost.cloudstream3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public final class AnimeLoadResponseDefaultsTest {
    @Test public void initializesMutableEmptyDefaults() {
        AnimeLoadResponse response = new AnimeLoadResponse(
                "One Piece", "https://example.com/one-piece", "StreamPlay", TvType.Anime);

        assertNotNull(response.getTrailers());
        assertTrue(response.getTrailers().isEmpty());
        assertNotNull(response.getSyncData());
        assertTrue(response.getSyncData().isEmpty());

        response.getTrailers().add(new TrailerData("https://example.com/trailer", null, false));
        response.getSyncData().put("test", "mutable");
        assertEquals(1, response.getTrailers().size());
        assertEquals("mutable", response.getSyncData().get("test"));
    }

    @Test public void trailerListHelperAppendsToAnimeResponse() {
        AnimeLoadResponse response = new AnimeLoadResponse(
                "One Piece", "https://example.com/one-piece", "StreamPlay", TvType.Anime);

        LoadResponse.Companion.addTrailer$default(
                LoadResponse.Companion,
                response,
                Arrays.asList("https://example.com/trailer"),
                null,
                false,
                synchronousContinuation(),
                0,
                null);

        assertNotNull(response.getTrailers());
        assertEquals(1, response.getTrailers().size());
        assertEquals("https://example.com/trailer",
                ((TrailerData) response.getTrailers().get(0)).getExtractorUrl());
    }

    private static kotlin.coroutines.Continuation<kotlin.Unit> synchronousContinuation() {
        return new kotlin.coroutines.Continuation<kotlin.Unit>() {
            @Override public kotlin.coroutines.CoroutineContext getContext() {
                return kotlin.coroutines.EmptyCoroutineContext.INSTANCE;
            }

            @Override public void resumeWith(Object result) { }
        };
    }

    @Test public void setEpisodesPreservesValidDubGroupsAndFiltersInvalidEntries() {
        AnimeLoadResponse response = new AnimeLoadResponse(
                "One Piece", "https://example.com/one-piece", "StreamPlay", TvType.Anime);
        List<Episode> subbed = Arrays.asList(
                new Episode("sub-1", "Episode 1", 1, 1, null, (Integer) null, null, null),
                new Episode("sub-2", "Episode 2", 1, 2, null, (Integer) null, null, null));
        List<Episode> dubbed = Arrays.asList(
                new Episode("dub-1", "Episode 1", 1, 1, null, (Integer) null, null, null));
        Map<DubStatus, List<Episode>> supplied = new LinkedHashMap<>();
        supplied.put(DubStatus.Subbed, subbed);
        supplied.put(DubStatus.Dubbed, dubbed);
        supplied.put(null, Arrays.asList(new Episode(
                "invalid-key", "Invalid", 1, 1, null, (Integer) null, null, null)));
        supplied.put(DubStatus.None, null);

        response.setEpisodes(supplied);

        assertEquals(2, response.getEpisodes().size());
        assertEquals(Arrays.asList(DubStatus.Subbed, DubStatus.Dubbed),
                new ArrayList<>(response.getEpisodes().keySet()));
        assertEquals(subbed, response.getEpisodes().get(DubStatus.Subbed));
        assertEquals(dubbed, response.getEpisodes().get(DubStatus.Dubbed));
    }
}
