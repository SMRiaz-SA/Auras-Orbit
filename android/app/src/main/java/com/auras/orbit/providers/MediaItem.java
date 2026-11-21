package com.auras.orbit.providers;

import com.lagradost.cloudstream3.ShowStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** Provider-neutral title identity and display metadata. URLs are metadata only, never auto-opened. */
public final class MediaItem {
    public static final int MAX_EPISODES = 25_000;
    /** Scan limits count malformed entries as work; output-only caps can still scan indefinitely. */
    private static final int METADATA_INPUT_SCAN_MULTIPLIER = 4;
    public static final int MAX_EPISODE_DATA_CHARS = 65_536;
    public final String stableId;
    public final String title;
    public final MediaKind kind;
    public final String detailUrl;
    public final String posterUrl;
    public final String year;
    public final String overview;
    /** Opaque provider payload for the next operation, such as CS3 loadLinks dataUrl. */
    public final String providerData;
    /** Provider-supplied classification tags, not inferred genres. */
    public final List<String> tags;
    /** Provider-reported runtime in minutes; zero means unavailable. */
    public final int durationMinutes;
    public final String contentRating;
    public final String backgroundPosterUrl;
    public final String logoUrl;
    /** Optional provider artwork request headers. Consumers must not forward them cross-origin. */
    public final Map<String, String> posterHeaders;
    /** Provider-formatted rating in its native 0..10 display scale, when supplied. */
    public final String score;
    /** Bounded provider-supplied cast. No inferred actor or role data is added. */
    public final List<CastMember> cast;
    /** Ephemeral, bounded provider-supplied related titles; never written to library storage. */
    public final List<MediaItem> relatedTitles;
    /** Ephemeral provider episode payloads; never written to library or archive storage. */
    public final List<EpisodeItem> episodes;
    /** Ephemeral CS3 show status, when this item came from a series response. */
    public final ShowStatus seriesStatus;
    /** Ephemeral bounded season labels keyed by their actual provider season values. */
    public final List<SeasonInfo> seasonNames;
    /** Ephemeral next-airing metadata, when provided by CS3. */
    public final NextAiringInfo nextAiring;

    public MediaItem(String stableId, String title, MediaKind kind, String detailUrl,
                     String posterUrl, String year, String overview) {
        this(stableId, title, kind, detailUrl, posterUrl, year, overview, "");
    }

    public MediaItem(String stableId, String title, MediaKind kind, String detailUrl,
                     String posterUrl, String year, String overview, String providerData) {
        this(stableId, title, kind, detailUrl, posterUrl, year, overview, providerData,
                Collections.emptyList(), 0, "", "", "", Collections.emptyMap(), "",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    public MediaItem(String stableId, String title, MediaKind kind, String detailUrl,
                     String posterUrl, String year, String overview, String providerData,
                     List<String> tags, int durationMinutes, String contentRating,
                     String backgroundPosterUrl, String logoUrl,
                     Map<String, String> posterHeaders) {
        this(stableId, title, kind, detailUrl, posterUrl, year, overview, providerData,
                tags, durationMinutes, contentRating, backgroundPosterUrl, logoUrl,
                posterHeaders, "", Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    public MediaItem(String stableId, String title, MediaKind kind, String detailUrl,
                     String posterUrl, String year, String overview, String providerData,
                     List<String> tags, int durationMinutes, String contentRating,
                     String backgroundPosterUrl, String logoUrl,
                     Map<String, String> posterHeaders, String score, List<CastMember> cast) {
        this(stableId, title, kind, detailUrl, posterUrl, year, overview, providerData,
                tags, durationMinutes, contentRating, backgroundPosterUrl, logoUrl,
                posterHeaders, score, cast, Collections.emptyList(), Collections.emptyList());
    }

    public MediaItem(String stableId, String title, MediaKind kind, String detailUrl,
                     String posterUrl, String year, String overview, String providerData,
                     List<String> tags, int durationMinutes, String contentRating,
                     String backgroundPosterUrl, String logoUrl,
                     Map<String, String> posterHeaders, String score, List<CastMember> cast,
                     List<MediaItem> relatedTitles) {
        this(stableId, title, kind, detailUrl, posterUrl, year, overview, providerData,
                tags, durationMinutes, contentRating, backgroundPosterUrl, logoUrl,
                posterHeaders, score, cast, relatedTitles, Collections.emptyList());
    }

    public MediaItem(String stableId, String title, MediaKind kind, String detailUrl,
                     String posterUrl, String year, String overview, String providerData,
                     List<String> tags, int durationMinutes, String contentRating,
                     String backgroundPosterUrl, String logoUrl,
                     Map<String, String> posterHeaders, String score, List<CastMember> cast,
                     List<MediaItem> relatedTitles, List<EpisodeItem> episodes) {
        this(stableId, title, kind, detailUrl, posterUrl, year, overview, providerData,
                tags, durationMinutes, contentRating, backgroundPosterUrl, logoUrl,
                posterHeaders, score, cast, relatedTitles, episodes, null,
                Collections.emptyList(), null);
    }

    public MediaItem(String stableId, String title, MediaKind kind, String detailUrl,
                     String posterUrl, String year, String overview, String providerData,
                     List<String> tags, int durationMinutes, String contentRating,
                     String backgroundPosterUrl, String logoUrl,
                     Map<String, String> posterHeaders, String score, List<CastMember> cast,
                     List<MediaItem> relatedTitles, List<EpisodeItem> episodes,
                     ShowStatus seriesStatus, List<SeasonInfo> seasonNames,
                     NextAiringInfo nextAiring) {
        this.stableId = requireText(stableId, "stable title id");
        this.title = requireText(title, "title");
        this.kind = kind == null ? MediaKind.UNKNOWN : kind;
        this.detailUrl = clean(detailUrl);
        this.posterUrl = clean(posterUrl);
        this.year = clean(year);
        this.overview = clean(overview);
        this.providerData = this.kind == MediaKind.LIVE
                ? providerData == null ? "" : providerData : clean(providerData);
        this.tags = immutableStrings(tags, 50, 160);
        this.durationMinutes = durationMinutes > 0 && durationMinutes <= 1000 ? durationMinutes : 0;
        this.contentRating = bounded(contentRating, 64);
        this.backgroundPosterUrl = bounded(backgroundPosterUrl, 4096);
        this.logoUrl = bounded(logoUrl, 4096);
        this.posterHeaders = immutableHeaders(posterHeaders);
        this.score = bounded(score, 64);
        this.cast = immutableCast(cast);
        this.relatedTitles = immutableRelatedTitles(relatedTitles);
        this.episodes = immutableEpisodes(episodes);
        this.seriesStatus = seriesStatus;
        this.seasonNames = immutableSeasonNames(seasonNames);
        this.nextAiring = nextAiring;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static String bounded(String value, int maxLength) {
        String cleaned = clean(value);
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private static List<String> immutableStrings(List<String> values, int maxCount, int maxLength) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        ArrayList<String> result = new ArrayList<>();
        int scanned = 0;
        for (Iterator<String> iterator = values.iterator();
                scanned < maxCount * METADATA_INPUT_SCAN_MULTIPLIER && iterator.hasNext(); scanned++) {
            String value = iterator.next();
            String cleaned = bounded(value, maxLength);
            if (!cleaned.isEmpty()) result.add(cleaned);
            if (result.size() >= maxCount) break;
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, String> immutableHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return Collections.emptyMap();
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        int scanned = 0;
        for (Iterator<Map.Entry<String, String>> iterator = headers.entrySet().iterator();
                scanned < 64 * METADATA_INPUT_SCAN_MULTIPLIER && iterator.hasNext(); scanned++) {
            Map.Entry<String, String> entry = iterator.next();
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String name = bounded(entry.getKey(), 256);
            String value = bounded(entry.getValue(), 2048);
            if (isHttpHeaderName(name) && !isRestrictedTransportHeader(name)
                    && !hasUnsafeHeaderChars(value) && !containsHeaderIgnoreCase(result, name)) {
                result.put(name, value);
            }
            if (result.size() >= 64) break;
        }
        return result.isEmpty() ? Collections.emptyMap()
                : Collections.unmodifiableMap(result);
    }

    private static boolean isHttpHeaderName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128) return false;
        for (int index = 0; index < name.length(); index++) {
            char ch = name.charAt(index);
            if (!((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || "!#$%&'*+-.^_`|~".indexOf(ch) >= 0)) return false;
        }
        return true;
    }

    private static boolean isRestrictedTransportHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return "host".equals(lower) || "connection".equals(lower)
                || "proxy-authorization".equals(lower) || "proxy-connection".equals(lower)
                || "content-length".equals(lower) || "transfer-encoding".equals(lower)
                || "upgrade".equals(lower);
    }

    private static boolean containsHeaderIgnoreCase(Map<String, String> headers, String name) {
        for (String existing : headers.keySet()) {
            if (existing.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static boolean hasUnsafeHeaderChars(String value) {
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '\r' || ch == '\n' || ch == '\u0000'
                    || (Character.isISOControl(ch) && ch != '\t')) return true;
        }
        return false;
    }

    private static List<CastMember> immutableCast(List<CastMember> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        ArrayList<CastMember> result = new ArrayList<>();
        int scanned = 0;
        for (Iterator<CastMember> iterator = values.iterator();
                scanned < 30 * METADATA_INPUT_SCAN_MULTIPLIER && iterator.hasNext(); scanned++) {
            CastMember value = iterator.next();
            if (value == null || value.name.trim().isEmpty()) continue;
            result.add(value);
            if (result.size() >= 30) break;
        }
        return Collections.unmodifiableList(result);
    }

    private static List<MediaItem> immutableRelatedTitles(List<MediaItem> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        ArrayList<MediaItem> result = new ArrayList<>();
        int scanned = 0;
        for (Iterator<MediaItem> iterator = values.iterator();
                scanned < 30 * METADATA_INPUT_SCAN_MULTIPLIER && iterator.hasNext(); scanned++) {
            MediaItem value = iterator.next();
            if (value == null || (value.kind != MediaKind.MOVIE && value.kind != MediaKind.SERIES)
                    || value.title.trim().isEmpty() || value.detailUrl.trim().isEmpty()
                    || !value.relatedTitles.isEmpty()) continue;
            result.add(value);
            if (result.size() >= 30) break;
        }
        return Collections.unmodifiableList(result);
    }

    private static List<EpisodeItem> immutableEpisodes(List<EpisodeItem> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        // Capture size once and use index access: a provider-supplied AbstractList
        // may advertise an infinite iterator or mutate its toArray implementation.
        // Preserve all finite, admitted items. Never silently remove later episodes.
        int count = values.size();
        if (count < 0 || count > MAX_EPISODES) {
            throw new IllegalArgumentException("Provider episode collection exceeds the in-memory admission budget");
        }
        ArrayList<EpisodeItem> result = new ArrayList<>(Math.min(count, 256));
        for (int index = 0; index < count; index++) {
            EpisodeItem value = values.get(index);
            if (value != null) result.add(value);
        }
        return Collections.unmodifiableList(result);
    }

    private static List<SeasonInfo> immutableSeasonNames(List<SeasonInfo> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        ArrayList<SeasonInfo> result = new ArrayList<>();
        int scanned = 0;
        for (Iterator<SeasonInfo> iterator = values.iterator();
                scanned < 100 * METADATA_INPUT_SCAN_MULTIPLIER && iterator.hasNext(); scanned++) {
            SeasonInfo value = iterator.next();
            if (value == null) continue;
            result.add(value);
            if (result.size() >= 100) break;
        }
        return Collections.unmodifiableList(result);
    }

    public static final class CastMember {
        public final String name;
        public final String role;
        public final String imageUrl;

        public CastMember(String name, String role, String imageUrl) {
            this.name = bounded(name, 160);
            this.role = bounded(role, 160);
            this.imageUrl = bounded(imageUrl, 4096);
        }
    }

    /** Provider season label with its original season identity and optional display number. */
    public static final class SeasonInfo {
        public final int actualSeason;
        /** Optional CS3 season label. */
        public final String name;
        public final Integer displaySeason;

        public SeasonInfo(int actualSeason, String name, Integer displaySeason) {
            this.actualSeason = actualSeason;
            this.name = name == null ? null : bounded(name, 240);
            this.displaySeason = displaySeason;
        }
    }

    /** CS3 next-airing values; unixTime is seconds since the Unix epoch. */
    public static final class NextAiringInfo {
        public final Integer season;
        public final int episode;
        public final long unixTime;

        public NextAiringInfo(Integer season, int episode, long unixTime) {
            this.season = season;
            this.episode = episode;
            this.unixTime = unixTime;
        }
    }

    /** Provider episode details plus the opaque loadLinks payload, held in memory only. */
    public static final class EpisodeItem {
        public final String stableId;
        public final String title;
        public final int season;
        public final int number;
        public final String posterUrl;
        public final String overview;
        /** Provider runtime in seconds; zero means unavailable. */
        public final int runtimeSeconds;
        public final String score;
        public final long airDateEpochMillis;
        /** Dub/Sub/Original grouping for anime; empty for standard TV-series responses. */
        public final String variant;
        /** Opaque CS3 Episode.data value; consumers must not persist or display it. */
        public final String providerData;

        public EpisodeItem(String stableId, String title, int season, int number,
                           String posterUrl, String overview, int runtimeSeconds,
                           String score, long airDateEpochMillis, String variant,
                           String providerData) {
            this.stableId = requireText(stableId, "stable episode id");
            this.title = bounded(title, 240);
            this.season = season >= -1 ? season : -1;
            this.number = number >= -1 && number <= 9999 ? number : -1;
            this.posterUrl = bounded(posterUrl, 4096);
            this.overview = bounded(overview, 2048);
            this.runtimeSeconds = runtimeSeconds > 0 && runtimeSeconds <= 172800
                    ? runtimeSeconds : 0;
            this.score = bounded(score, 64);
            this.airDateEpochMillis = airDateEpochMillis >= 0L ? airDateEpochMillis : 0L;
            this.variant = bounded(variant, 32);
            this.providerData = bounded(providerData, MAX_EPISODE_DATA_CHARS);
        }
    }
}
