package com.lagradost.cloudstream3;

import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Source-shaped CS3 anime response; episode variants retain their DubStatus grouping. */
public final class AnimeLoadResponse extends OrbitLoadResponse implements EpisodeResponse {
    private String engName;
    private String japName;
    private EpisodeMap episodes = new EpisodeMap();
    private ShowStatus showStatus;
    private List<String> synonyms;
    private NextAiring nextAiring;
    private List<SeasonData> seasonNames;

    public AnimeLoadResponse(String name, String url, String apiName, TvType type) {
        setName(required(name, "name"));
        setUrl(required(url, "url"));
        setApiName(required(apiName, "apiName"));
        setType(required(type, "type"));
        setUniqueUrl(url);
        setTrailers(new ArrayList<>());
        setSyncData(new LinkedHashMap<>());
    }

    public String getEngName() { return engName; }
    public void setEngName(String value) { engName = value; }
    public String getJapName() { return japName; }
    public void setJapName(String value) { japName = value; }
    public Map<DubStatus, List<Episode>> getEpisodes() { return episodes; }
    public void setEpisodes(Map<DubStatus, List<Episode>> value) {
        EpisodeMap bounded = new EpisodeMap();
        if (value != null) {
            for (Map.Entry<DubStatus, List<Episode>> entry : value.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    bounded.put(entry.getKey(), entry.getValue());
                }
            }
        }
        episodes = bounded;
    }
    public ShowStatus getShowStatus() { return showStatus; }
    public void setShowStatus(ShowStatus value) { showStatus = value; }
    public List<String> getSynonyms() { return synonyms; }
    public void setSynonyms(List<String> value) {
        if (value == null || value.isEmpty()) { synonyms = null; return; }
        ArrayList<String> bounded = new ArrayList<>();
        for (String synonym : value) {
            if (synonym == null || synonym.trim().isEmpty()) continue;
            bounded.add(synonym.length() > 240 ? synonym.substring(0, 240) : synonym);
            if (bounded.size() >= 50) break;
        }
        synonyms = Collections.unmodifiableList(bounded);
    }

    /** Legacy CS3 rating scale is 0..10,000; Score is normalized internally. */
    public Integer getRating() { return getScore() == null ? null : getScore().toInt(10000); }
    public void setRating(Integer value) { setScore(Score.Companion.fromOld(value)); }

    @Override public NextAiring getNextAiring() { return nextAiring; }
    @Override public void setNextAiring(NextAiring value) { nextAiring = value; }
    @Override public List<SeasonData> getSeasonNames() { return seasonNames; }
    @Override public void setSeasonNames(List<SeasonData> value) {
        if (value == null || value.isEmpty()) { seasonNames = null; return; }
        ArrayList<SeasonData> bounded = new ArrayList<>();
        for (SeasonData season : value) {
            if (season == null) continue;
            bounded.add(season);
            if (bounded.size() >= 100) break;
        }
        seasonNames = Collections.unmodifiableList(bounded);
    }

    @Override public Map<DubStatus, Integer> getLatestEpisodes() {
        LinkedHashMap<DubStatus, Integer> latest = new LinkedHashMap<>();
        for (Map.Entry<DubStatus, List<Episode>> group : episodes.entrySet()) {
            Integer maxSeason = null;
            for (Episode episode : group.getValue()) {
                int season = episode.getSeason() == null ? Integer.MIN_VALUE : episode.getSeason();
                if (maxSeason == null || season > maxSeason) maxSeason = season;
            }
            Integer maxEpisode = null;
            for (Episode episode : group.getValue()) {
                int season = episode.getSeason() == null ? Integer.MIN_VALUE : episode.getSeason();
                if (maxSeason != null && season == maxSeason && episode.getEpisode() != null
                        && (maxEpisode == null || episode.getEpisode() > maxEpisode)) {
                    maxEpisode = episode.getEpisode();
                }
            }
            latest.put(group.getKey(), maxEpisode);
        }
        return Collections.unmodifiableMap(latest);
    }

    @Override public int getTotalEpisodeIndex(int episode, int season) {
        Map<Integer, Integer> displayMap = new LinkedHashMap<>();
        if (seasonNames != null) {
            for (SeasonData value : seasonNames) {
                if (value.getDisplaySeason() != null) {
                    displayMap.put(value.getSeason(), value.getDisplaySeason());
                }
            }
        }
        int largestPreviousCount = 0;
        for (List<Episode> group : episodes.values()) {
            int previousCount = 0;
            for (Episode value : group) {
                Integer actualSeason = value.getSeason();
                int displaySeason = actualSeason == null ? Integer.MIN_VALUE
                        : displayMap.getOrDefault(actualSeason, actualSeason);
                if (displaySeason >= 1 && displaySeason < season) previousCount++;
            }
            largestPreviousCount = Math.max(largestPreviousCount, previousCount);
        }
        return largestPreviousCount + episode;
    }

    @Override public void setActors(List<ActorData> value) { super.setActors(value); }
    @Override public void setBackgroundPosterUrl(String value) { super.setBackgroundPosterUrl(value); }
    @Override public void setContentRating(String value) { super.setContentRating(value); }
    @Override public void setDuration(Integer value) { super.setDuration(value); }
    @Override public void setLogoUrl(String value) { super.setLogoUrl(value); }
    @Override public void setPlot(String value) { super.setPlot(value); }
    @Override public void setPosterUrl(String value) { super.setPosterUrl(value); }
    @Override public void setRecommendations(List<? extends SearchResponse> value) {
        super.setRecommendations(value);
    }
    @Override public void setScore(Score value) { super.setScore(value); }
    @Override public void setTags(List<String> value) { super.setTags(value); }
    @Override public void setYear(Integer value) { super.setYear(value); }

    private static <T> T required(T value, String field) {
        if (value == null) throw new NullPointerException(field + " cannot be null");
        return value;
    }

    /** Mutable CS3 episode map: preserves complete provider data and map write semantics. */
    private static final class EpisodeMap extends AbstractMap<DubStatus, List<Episode>> {
        private final LinkedHashMap<DubStatus, List<Episode>> values = new LinkedHashMap<>();

        @Override public List<Episode> put(DubStatus key, List<Episode> value) {
            Objects.requireNonNull(key, "episode status cannot be null");
            Objects.requireNonNull(value, "episode list cannot be null");
            // CloudStream exposes provider-owned episode collections. Do not silently
            // discard entries at this ABI boundary; conversion and rendering are separate.
            return values.put(key, value);
        }

        @Override public void putAll(Map<? extends DubStatus, ? extends List<Episode>> source) {
            Objects.requireNonNull(source, "episodes cannot be null");
            for (Map.Entry<? extends DubStatus, ? extends List<Episode>> entry : source.entrySet()) {
                put(entry.getKey(), entry.getValue());
            }
        }

        @Override public List<Episode> get(Object key) { return values.get(key); }
        @Override public boolean containsKey(Object key) { return values.containsKey(key); }
        @Override public List<Episode> remove(Object key) { return values.remove(key); }
        @Override public void clear() { values.clear(); }
        @Override public int size() { return values.size(); }

        @Override public Set<Map.Entry<DubStatus, List<Episode>>> entrySet() {
            return new AbstractSet<Map.Entry<DubStatus, List<Episode>>>() {
                @Override public int size() { return values.size(); }

                @Override public Iterator<Map.Entry<DubStatus, List<Episode>>> iterator() {
                    Iterator<DubStatus> keys = new ArrayList<>(values.keySet()).iterator();
                    return new Iterator<Map.Entry<DubStatus, List<Episode>>>() {
                        private DubStatus currentKey;
                        private boolean canRemove;

                        @Override public boolean hasNext() { return keys.hasNext(); }

                        @Override public Map.Entry<DubStatus, List<Episode>> next() {
                            if (!keys.hasNext()) throw new java.util.NoSuchElementException();
                            currentKey = keys.next();
                            canRemove = true;
                            final DubStatus entryKey = currentKey;
                            return new Map.Entry<DubStatus, List<Episode>>() {
                                @Override public DubStatus getKey() { return entryKey; }
                                @Override public List<Episode> getValue() {
                                    return EpisodeMap.this.get(entryKey);
                                }
                                @Override public List<Episode> setValue(List<Episode> value) {
                                    return EpisodeMap.this.put(entryKey, value);
                                }
                                @Override public boolean equals(Object other) {
                                    if (!(other instanceof Map.Entry)) return false;
                                    Map.Entry<?, ?> entry = (Map.Entry<?, ?>) other;
                                    return Objects.equals(entryKey, entry.getKey())
                                            && Objects.equals(getValue(), entry.getValue());
                                }
                                @Override public int hashCode() {
                                    return Objects.hashCode(entryKey) ^ Objects.hashCode(getValue());
                                }
                            };
                        }

                        @Override public void remove() {
                            if (!canRemove) throw new IllegalStateException();
                            EpisodeMap.this.remove(currentKey);
                            canRemove = false;
                        }
                    };
                }
            };
        }
    }
}
