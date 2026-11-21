package com.lagradost.cloudstream3;

import java.util.List;
import java.util.Map;

/** Orbit-owned interface matching the pinned CS3 LoadResponse source contract. */
public interface LoadResponse {
    Companion Companion = new Companion();

    /** Exposes the configured companion value without the Kotlin Companion name collision. */
    static String configuredSimklIdPrefix() {
        return Companion.getSimklIdPrefix();
    }

    /** Initializes the pinned CloudStream sync ID prefixes before providers are loaded. */
    static void initializeSyncIdPrefixes() {
        Companion.setMalIdPrefix("mal");
        Companion.setKitsuIdPrefix("kitsu");
        Companion.setAniListIdPrefix("anilist");
        Companion.setSimklIdPrefix("simkl");
    }

    String getName();
    void setName(String value);
    String getUrl();
    void setUrl(String value);
    String getApiName();
    void setApiName(String value);
    TvType getType();
    void setType(TvType value);
    String getPosterUrl();
    void setPosterUrl(String value);
    Integer getYear();
    void setYear(Integer value);
    String getPlot();
    void setPlot(String value);
    Score getScore();
    void setScore(Score value);
    /** Deprecated pinned CS3 0..10,000 rating property backed by Score. */
    default Integer getRating() {
        Score score = getScore();
        return score == null ? null : score.toOld();
    }
    default void setRating(Integer value) {
        setScore(Score.Companion.fromOld(value));
    }
    List<String> getTags();
    void setTags(List<String> value);
    Integer getDuration();
    void setDuration(Integer value);
    List<TrailerData> getTrailers();
    void setTrailers(List<TrailerData> value);
    List<SearchResponse> getRecommendations();
    void setRecommendations(List<? extends SearchResponse> value);
    List<ActorData> getActors();
    void setActors(List<ActorData> value);
    boolean getComingSoon();
    void setComingSoon(boolean value);
    Map<String, String> getSyncData();
    void setSyncData(Map<String, String> value);
    Map<String, String> getPosterHeaders();
    void setPosterHeaders(Map<String, String> value);
    String getBackgroundPosterUrl();
    void setBackgroundPosterUrl(String value);
    String getLogoUrl();
    void setLogoUrl(String value);
    String getContentRating();
    void setContentRating(String value);
    String getUniqueUrl();
    void setUniqueUrl(String value);

    final class Companion {
        private String malIdPrefix = "";
        private String kitsuIdPrefix = "";
        private String aniListIdPrefix = "";
        private String simklIdPrefix = "";
        private boolean trailersEnabled = true;

        private Companion() { }

        public void addActorNames(LoadResponse response, List names) {
            requireResponse(response);
            if (names == null) {
                response.setActors(null);
                return;
            }
            java.util.ArrayList<ActorData> actors = new java.util.ArrayList<>(names.size());
            for (Object name : names) {
                actors.add(new ActorData(new Actor((String) name, null), null, null, null));
            }
            response.setActors(actors);
        }

        public void addActorsRole(LoadResponse response, List actors) {
            requireResponse(response);
            if (actors == null) {
                response.setActors(null);
                return;
            }
            java.util.ArrayList<ActorData> mapped = new java.util.ArrayList<>(actors.size());
            for (Object value : actors) {
                kotlin.Pair pair = (kotlin.Pair) value;
                mapped.add(new ActorData((Actor) pair.getFirst(), (ActorRole) pair.getSecond(), null, null));
            }
            response.setActors(mapped);
        }

        public void addAniListId(LoadResponse response, Integer id) {
            requireResponse(response);
            if (id == null) return;
            response.getSyncData().put(getAniListIdPrefix(), id.toString());
            addSimklId(response, "AniList", id.toString());
        }

        public void addImdbId(LoadResponse response, String id) {
            requireResponse(response);
            addSimklId(response, "Imdb", id);
        }

        public void addImdbUrl(LoadResponse response, String url) {
            requireResponse(response);
            addSimklId(response, "Imdb", MainAPIKt.imdbUrlToIdNullable(url));
        }
        public void addActorsOnly(LoadResponse response, List actors) {
            requireResponse(response);
            if (actors == null) {
                response.setActors(null);
                return;
            }
            java.util.ArrayList<ActorData> mapped = new java.util.ArrayList<>(actors.size());
            for (Object actor : actors) {
                mapped.add(new ActorData((Actor) actor, null, null, null));
            }
            response.setActors(mapped);
        }
        public void addTMDbId(LoadResponse response, String id) {
            requireResponse(response);
            addSimklId(response, "Tmdb", id);
        }
        public void addKitsuId(LoadResponse response, String id) {
            requireResponse(response);
            // The pinned String overload is intentionally a no-op pending Kitsu sync support.
        }

        public void addMalId(LoadResponse response, Integer id) {
            requireResponse(response);
            if (id == null) return;
            response.getSyncData().put(getMalIdPrefix(), id.toString());
            addSimklId(response, "Mal", id.toString());
        }

        public final Object addTrailer(LoadResponse response, String trailerUrl,
                String referer, boolean addRaw,
                kotlin.coroutines.Continuation<? super kotlin.Unit> continuation) {
            requireResponse(response);
            requireContinuation(continuation);
            addTrailerUrl(response, trailerUrl, referer, addRaw);
            return kotlin.Unit.INSTANCE;
        }

        public final Object addTrailer(LoadResponse response, String trailerUrl,
                String referer, boolean addRaw, Map<String, String> headers,
                kotlin.coroutines.Continuation<? super kotlin.Unit> continuation) {
            requireResponse(response);
            if (headers == null) throw new NullPointerException("headers");
            requireContinuation(continuation);
            addTrailerUrl(response, trailerUrl, referer, addRaw, headers);
            return kotlin.Unit.INSTANCE;
        }

        public final Object addTrailer(LoadResponse response, List<String> trailerUrls,
                String referer, boolean addRaw,
                kotlin.coroutines.Continuation<? super kotlin.Unit> continuation) {
            requireResponse(response);
            requireContinuation(continuation);
            addTrailerUrls(response, trailerUrls, referer, addRaw);
            return kotlin.Unit.INSTANCE;
        }

        public static Object addTrailer$default(Companion companion, LoadResponse response,
                String trailerUrl, String referer, boolean addRaw, Map<String, String> headers,
                kotlin.coroutines.Continuation<? super kotlin.Unit> continuation,
                int mask, Object marker) {
            requireCompanion(companion);
            if ((mask & 0x2) != 0) referer = null;
            if ((mask & 0x4) != 0) addRaw = false;
            if ((mask & 0x8) != 0) headers = java.util.Collections.emptyMap();
            return companion.addTrailer(response, trailerUrl, referer, addRaw, headers, continuation);
        }

        public static Object addTrailer$default(Companion companion, LoadResponse response,
                String trailerUrl, String referer, boolean addRaw,
                kotlin.coroutines.Continuation<? super kotlin.Unit> continuation,
                int mask, Object marker) {
            requireCompanion(companion);
            requireResponse(response);
            if ((mask & 0x2) != 0) referer = null;
            if ((mask & 0x4) != 0) addRaw = false;
            return companion.addTrailer(response, trailerUrl, referer, addRaw, continuation);
        }

        public static Object addTrailer$default(Companion companion, LoadResponse response,
                List<String> trailerUrls, String referer, boolean addRaw,
                kotlin.coroutines.Continuation<? super kotlin.Unit> continuation,
                int mask, Object marker) {
            requireCompanion(companion);
            requireResponse(response);
            if ((mask & 0x2) != 0) referer = null;
            if ((mask & 0x4) != 0) addRaw = false;
            return companion.addTrailer(response, trailerUrls, referer, addRaw, continuation);
        }

        public final String getMalIdPrefix() { return malIdPrefix; }
        public final void setMalIdPrefix(String value) { malIdPrefix = requireValue(value, "malIdPrefix"); }
        public final String getKitsuIdPrefix() { return kitsuIdPrefix; }
        public final void setKitsuIdPrefix(String value) { kitsuIdPrefix = requireValue(value, "kitsuIdPrefix"); }
        public final String getAniListIdPrefix() { return aniListIdPrefix; }
        public final void setAniListIdPrefix(String value) { aniListIdPrefix = requireValue(value, "aniListIdPrefix"); }
        public final String getSimklIdPrefix() { return simklIdPrefix; }
        public final void setSimklIdPrefix(String value) { simklIdPrefix = requireValue(value, "simklIdPrefix"); }
        public final boolean isTrailersEnabled() { return trailersEnabled; }
        public final void setTrailersEnabled(boolean value) { trailersEnabled = value; }

        private static void requireResponse(LoadResponse response) {
            if (response == null) throw new NullPointerException("response");
        }

        private static void requireCompanion(Companion companion) {
            if (companion == null) throw new NullPointerException("this");
        }

        private static void requireContinuation(kotlin.coroutines.Continuation<?> continuation) {
            if (continuation == null) throw new NullPointerException("continuation");
        }

        private static String requireValue(String value, String name) {
            if (value == null) throw new NullPointerException(name);
            return value;
        }

        private static void addTrailerUrl(LoadResponse response, String trailerUrl,
                String referer, boolean addRaw) {
            addTrailerUrl(response, trailerUrl, referer, addRaw, java.util.Collections.emptyMap());
        }

        private static void addTrailerUrl(LoadResponse response, String trailerUrl,
                String referer, boolean addRaw, Map<String, String> headers) {
            if (!Companion.isTrailersEnabled() || isNullOrBlank(trailerUrl)) return;
            List<TrailerData> trailers = response.getTrailers();
            if (trailers == null) throw new NullPointerException("trailers");
            trailers.add(new TrailerData(trailerUrl, referer, addRaw, headers));
        }

        private static void addTrailerUrls(LoadResponse response, List<String> trailerUrls,
                String referer, boolean addRaw) {
            if (!Companion.isTrailersEnabled() || trailerUrls == null) return;
            List<TrailerData> trailers = response.getTrailers();
            if (trailers == null) throw new NullPointerException("trailers");
            java.util.ArrayList<TrailerData> mapped = new java.util.ArrayList<>(trailerUrls.size());
            for (String trailerUrl : trailerUrls) {
                mapped.add(new TrailerData(trailerUrl, referer, addRaw));
            }
            trailers.addAll(mapped);
        }

        private static boolean isNullOrBlank(String value) {
            if (value == null) return true;
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return false;
            }
            return true;
        }

        /** Mirrors the pinned Simkl-compatible ID map stored under its configured prefix. */
        private static void addSimklId(LoadResponse response, String service, String id) {
            try {
                Map<String, String> syncData = response.getSyncData();
                if (syncData == null) return;
                String prefix = Companion.getSimklIdPrefix();
                String encodedIds = syncData.get(prefix);
                java.util.LinkedHashMap<String, String> ids = new java.util.LinkedHashMap<>();
                if (encodedIds != null) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode existing = MainAPIKt.getMapper().readTree(encodedIds);
                        if (existing == null || !existing.isObject()) {
                            throw new IllegalArgumentException("Expected an ID object");
                        }
                        java.util.Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields =
                                existing.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> field = fields.next();
                            if (!isSimklService(field.getKey()) || !field.getValue().isTextual()) {
                                ids.clear();
                                break;
                            }
                            ids.put(field.getKey(), field.getValue().textValue());
                        }
                    } catch (Exception malformedExistingIds) {
                        // Pinned tryParseJson returns null on malformed input, then uses an empty map.
                    }
                }
                // Kotlin's nullable id.toString() yields the literal "null" for null IMDb/TMDb IDs.
                ids.put(service, String.valueOf(id));
                String updated = MainAPIKt.getMapper().writeValueAsString(ids);
                syncData.put(prefix, updated);
            } catch (Exception ignored) {
                // Pinned safe { } suppresses serialization/map errors and leaves syncData unchanged.
            }
        }

        private static boolean isSimklService(String service) {
            return "Simkl".equals(service) || "Imdb".equals(service) || "Tmdb".equals(service)
                    || "AniList".equals(service) || "Mal".equals(service);
        }
    }
}
