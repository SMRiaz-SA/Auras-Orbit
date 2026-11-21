package com.lagradost.cloudstream3;

import com.lagradost.cloudstream3.OrbitCs3FacadeBoundary;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.module.kotlin.KotlinModule;

/** Orbit-owned static descriptor facade; required service behavior is unimplemented. */
public class MainAPIKt {
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36";
    private static final JsonMapper ORBIT_MAPPER = createOrbitMapper();
    private static final kotlin.jvm.functions.Function2<Object, kotlin.coroutines.Continuation<? super kotlin.Unit>, Object>
            NO_SUSPEND_INITIALIZER = new kotlin.jvm.functions.Function2<Object, kotlin.coroutines.Continuation<? super kotlin.Unit>, Object>() {
        @Override public Object invoke(Object receiver,
                                       kotlin.coroutines.Continuation<? super kotlin.Unit> continuation) {
            return kotlin.Unit.INSTANCE;
        }
    };
    private static final kotlin.jvm.functions.Function1<AnimeSearchResponse, kotlin.Unit>
            NO_ANIME_SEARCH_RESPONSE_INITIALIZER = new kotlin.jvm.functions.Function1<AnimeSearchResponse, kotlin.Unit>() {
        @Override public kotlin.Unit invoke(AnimeSearchResponse response) {
            return kotlin.Unit.INSTANCE;
        }
    };
    private static final kotlin.jvm.functions.Function1<LiveSearchResponse, kotlin.Unit>
            NO_LIVE_SEARCH_RESPONSE_INITIALIZER = new kotlin.jvm.functions.Function1<LiveSearchResponse, kotlin.Unit>() {
        @Override public kotlin.Unit invoke(LiveSearchResponse response) {
            return kotlin.Unit.INSTANCE;
        }
    };
    private static final kotlin.jvm.functions.Function1<TvSeriesSearchResponse, kotlin.Unit>
            NO_TV_SERIES_SEARCH_RESPONSE_INITIALIZER = new kotlin.jvm.functions.Function1<TvSeriesSearchResponse, kotlin.Unit>() {
        @Override public kotlin.Unit invoke(TvSeriesSearchResponse response) {
            return kotlin.Unit.INSTANCE;
        }
    };
    private static final kotlin.jvm.functions.Function1<MovieSearchResponse, kotlin.Unit>
            NO_MOVIE_SEARCH_RESPONSE_INITIALIZER = new kotlin.jvm.functions.Function1<MovieSearchResponse, kotlin.Unit>() {
        @Override public kotlin.Unit invoke(MovieSearchResponse response) {
            return kotlin.Unit.INSTANCE;
        }
    };

    private static JsonMapper createOrbitMapper() {
        JsonMapper mapper = JsonMapper.builder().addModule(new KotlinModule.Builder().build())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build();
        return mapper;
    }
    private MainAPIKt() { super(); }
    /** Pinned MainAPIKt helper; returns null for unknown quality labels. */
    public static com.lagradost.cloudstream3.SearchQuality getQualityFromString(String value) {
        if (value == null) return null;
        String check = trimKotlinWhitespace(value).toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        switch (check) {
            case "cam": return SearchQuality.Cam;
            case "camrip": case "rip": return SearchQuality.CamRip;
            case "hdcam": case "hdtc": case "hdts": return SearchQuality.HdCam;
            case "highquality": case "hq": return SearchQuality.HQ;
            case "highdefinition": case "hdrip": case "hd": case "hdtv": case "fhd":
                return SearchQuality.HD;
            case "telecine": case "tc": return SearchQuality.Telecine;
            case "telesync": case "ts": return SearchQuality.Telesync;
            case "dvd": case "dvdrip": case "dvdscr": return SearchQuality.DVD;
            case "blueray": case "bluray": case "blu": case "br": case "blue":
                return SearchQuality.BlueRay;
            case "standard": case "sd": return SearchQuality.SD;
            case "4k": return SearchQuality.FourK;
            case "uhd": return SearchQuality.UHD;
            case "wp": case "workprint": return SearchQuality.WorkPrint;
            case "webrip": case "webdl": case "web": return SearchQuality.WebRip;
            case "hdr": return SearchQuality.HDR;
            case "sdr": return SearchQuality.SDR;
            default: return null;
        }
    }

    /** Pinned legacy rating parser: strips spaces and scales the absolute value by 1000. */
    public static Integer toRatingInt(String value) {
        if (value == null) return null;
        String normalized = trimKotlinWhitespace(value.replace(" ", ""));
        final double parsed;
        try {
            parsed = Double.parseDouble(normalized);
        } catch (NumberFormatException invalid) {
            return null;
        }
        return Integer.valueOf((int) (Math.abs(parsed) * 1000f));
    }

    private static String trimKotlinWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isKotlinWhitespace(value.charAt(start))) start++;
        while (end > start && isKotlinWhitespace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean isKotlinWhitespace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }

    public static void addDate(com.lagradost.cloudstream3.Episode p0, java.lang.String p1, java.lang.String p2) {
        OrbitCs3DateHelpers.addDate(p0, p1, p2);
    }
    public static void addDate$default(com.lagradost.cloudstream3.Episode p0, java.lang.String p1, java.lang.String p2, int p3, java.lang.Object p4) {
        if (p4 != null) throw new UnsupportedOperationException("Super calls with default arguments are not supported");
        addDate(p0, p1, (p3 & 0x2) != 0 ? "yyyy-MM-dd" : p2);
    }
    public static void addDub(com.lagradost.cloudstream3.AnimeSearchResponse p0, java.lang.Integer p1) {
        if (p0 == null) throw new NullPointerException("response");
        if (p1 == null || p1.intValue() <= 0) return;
        addAnimeSearchDubStatus(p0, DubStatus.Dubbed, p1);
    }
    public static void addDubStatus(com.lagradost.cloudstream3.AnimeSearchResponse p0, boolean p1, boolean p2, java.lang.Integer p3, java.lang.Integer p4) {
        if (p0 == null) throw new NullPointerException("response");
        if (p1) addAnimeSearchDubStatus(p0, DubStatus.Dubbed, p3);
        if (p2) addAnimeSearchDubStatus(p0, DubStatus.Subbed, p4);
    }
    public static void addDubStatus$default(com.lagradost.cloudstream3.AnimeSearchResponse p0, boolean p1, boolean p2, java.lang.Integer p3, java.lang.Integer p4, int p5, java.lang.Object p6) {
        addDubStatus(p0, p1, p2,
                (p5 & 0x4) != 0 ? null : p3,
                (p5 & 0x8) != 0 ? null : p4);
    }
    public static void addSub(com.lagradost.cloudstream3.AnimeSearchResponse p0, java.lang.Integer p1) {
        if (p0 == null) throw new NullPointerException("response");
        if (p1 == null || p1.intValue() <= 0) return;
        addAnimeSearchDubStatus(p0, DubStatus.Subbed, p1);
    }
    public static boolean isMovieType(com.lagradost.cloudstream3.TvType p0) {
        if (p0 == null) throw new NullPointerException("type");
        return p0 == TvType.AnimeMovie || p0 == TvType.Live || p0 == TvType.Movie
                || p0 == TvType.Torrent || p0 == TvType.Video;
    }
    private static void addAnimeSearchDubStatus(AnimeSearchResponse response, DubStatus status, Integer episodes) {
        java.util.Set<DubStatus> statuses = response.getDubStatus();
        if (statuses == null) {
            statuses = new java.util.LinkedHashSet<>();
            response.setDubStatus(statuses);
        }
        statuses.add(status);

        TvType type = response.getType();
        if ((type == null || !isMovieType(type)) && episodes != null && episodes.intValue() > 0) {
            response.getEpisodes().put(status, episodes);
        }
    }
    public static java.lang.String base64Decode(java.lang.String p0) {
        byte[] decoded = base64DecodeArray(p0);
        StringBuilder result = new StringBuilder(decoded.length);
        for (byte value : decoded) {
            result.append((char) (value & 0xFF));
        }
        return result.toString();
    }
    public static byte[] base64DecodeArray(java.lang.String p0) {
        return kotlin.io.encoding.Base64.Default.decode(p0, 0, p0.length());
    }
    public static java.lang.String base64Encode(byte[] p0) {
        return kotlin.io.encoding.Base64.Default.encode(p0, 0, p0.length);
    }
    public static java.lang.String fixTitle(java.lang.String p0) {
        return OrbitCs3TextHelpers.fixTitle(p0);
    }
    public static java.lang.String fixUrl(com.lagradost.cloudstream3.MainAPI p0, java.lang.String p1) {
        if (p0 == null) throw new IllegalArgumentException("provider is required");
        if (p1 == null) throw new IllegalArgumentException("url is required");
        if (p1.startsWith("http") || p1.startsWith("{\"") || p1.startsWith("[")) return p1;
        if (p1.isEmpty()) return "";
        if (p1.startsWith("//")) return "https:" + p1;
        String base = p0.getMainUrl();
        if (base == null) base = "";
        return p1.startsWith("/") ? base + p1 : base + "/" + p1;
    }
    public static java.lang.String fixUrlNull(com.lagradost.cloudstream3.MainAPI p0, java.lang.String p1) {
        if (p1 == null || p1.isEmpty()) return null;
        return fixUrl(p0, p1);
    }
    public static kotlinx.serialization.json.Json getJson() {
        return OrbitCs3ResponseBuilders.getCs3Json();
    }
    public static com.fasterxml.jackson.databind.json.JsonMapper getMapper() {
        return ORBIT_MAPPER;
    }
    public static java.lang.String imdbUrlToIdNullable(java.lang.String p0) {
        if (p0 == null) return null;
        java.util.regex.Matcher titleMatcher = java.util.regex.Pattern
                .compile("/title/(tt[0-9]*)").matcher(p0);
        if (titleMatcher.find()) return titleMatcher.group(1);
        java.util.regex.Matcher fallbackMatcher = java.util.regex.Pattern
                .compile("tt[0-9]{5,}").matcher(p0);
        return fallbackMatcher.find() ? fallbackMatcher.group() : null;
    }
    public static java.util.List<com.lagradost.cloudstream3.MainPageData> mainPageOf(kotlin.Pair[] p0) {
        if (p0 == null) throw new IllegalArgumentException("page entries are required");
        java.util.ArrayList<com.lagradost.cloudstream3.MainPageData> pages = new java.util.ArrayList<>(p0.length);
        for (kotlin.Pair pair : p0) {
            if (pair == null) throw new IllegalArgumentException("page entry cannot be null");
            Object url = pair.getFirst();
            Object name = pair.getSecond();
            if (!(url instanceof String) || !(name instanceof String)) {
                throw new IllegalArgumentException("page entries must contain URL/name string pairs");
            }
            pages.add(new com.lagradost.cloudstream3.MainPageData((String) name, (String) url, false));
        }
        return pages;
    }
    public static java.util.List<com.lagradost.cloudstream3.MainPageData> mainPageOf(com.lagradost.cloudstream3.MainPageData[] p0) {
        if (p0 == null) throw new IllegalArgumentException("page entries are required");
        java.util.ArrayList<com.lagradost.cloudstream3.MainPageData> pages = new java.util.ArrayList<>(p0.length);
        for (com.lagradost.cloudstream3.MainPageData page : p0) {
            if (page == null) throw new IllegalArgumentException("page entry cannot be null");
            pages.add(page);
        }
        return pages;
    }
    public static java.lang.Object newAnimeLoadResponse$default(com.lagradost.cloudstream3.MainAPI p0, java.lang.String p1, java.lang.String p2, com.lagradost.cloudstream3.TvType p3, boolean p4, kotlin.jvm.functions.Function2<? super AnimeLoadResponse, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends Object> p5, kotlin.coroutines.Continuation<? super AnimeLoadResponse> p6, int p7, java.lang.Object p8) {
        boolean comingSoonIfNone = (p7 & (1 << 3)) != 0 || p4;
        kotlin.jvm.functions.Function2<? super AnimeLoadResponse, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends Object> initializer =
                (p7 & (1 << 4)) != 0 ? NO_SUSPEND_INITIALIZER : p5;
        return newAnimeLoadResponse(p0, p1, p2, p3, comingSoonIfNone, initializer, p6);
    }
    public static java.lang.Object newAnimeLoadResponse(com.lagradost.cloudstream3.MainAPI provider, java.lang.String name, java.lang.String url, com.lagradost.cloudstream3.TvType type, boolean comingSoonIfNone, kotlin.jvm.functions.Function2<? super AnimeLoadResponse, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends Object> initializer, kotlin.coroutines.Continuation<? super AnimeLoadResponse> continuation) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(provider, "this");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(name, "name");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(url, "url");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(type, "type");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(initializer, "initializer");
        return OrbitCs3ResponseBuilders.newAnimeLoadResponse(
                provider, name, url, type, comingSoonIfNone, initializer, continuation);
    }
    public static com.lagradost.cloudstream3.AnimeSearchResponse newAnimeSearchResponse$default(com.lagradost.cloudstream3.MainAPI p0, java.lang.String p1, java.lang.String p2, com.lagradost.cloudstream3.TvType p3, boolean p4, kotlin.jvm.functions.Function1<? super AnimeSearchResponse, kotlin.Unit> p5, int p6, java.lang.Object p7) {
        com.lagradost.cloudstream3.TvType type = (p6 & (1 << 2)) != 0
                ? com.lagradost.cloudstream3.TvType.Anime : p3;
        boolean fix = (p6 & (1 << 3)) != 0 || p4;
        kotlin.jvm.functions.Function1<? super AnimeSearchResponse, kotlin.Unit> initializer = (p6 & (1 << 4)) != 0
                ? NO_ANIME_SEARCH_RESPONSE_INITIALIZER : p5;
        return newAnimeSearchResponse(p0, p1, p2, type, fix, initializer);
    }
    public static com.lagradost.cloudstream3.AnimeSearchResponse newAnimeSearchResponse(com.lagradost.cloudstream3.MainAPI p0, java.lang.String p1, java.lang.String p2, com.lagradost.cloudstream3.TvType p3, boolean p4, kotlin.jvm.functions.Function1<? super AnimeSearchResponse, kotlin.Unit> p5) {
        if (p0 == null) throw new NullPointerException("provider");
        if (p1 == null) throw new NullPointerException("name");
        if (p2 == null) throw new NullPointerException("url");
        if (p3 == null) throw new NullPointerException("type");
        if (p5 == null) throw new NullPointerException("initializer");

        String providerName = p0.getName();
        if (providerName == null) throw new NullPointerException("provider.name");
        String responseUrl = p4 ? fixUrl(p0, p2) : p2;
        com.lagradost.cloudstream3.AnimeSearchResponse response =
                new com.lagradost.cloudstream3.AnimeSearchResponse(p1, responseUrl, providerName, p3);
        p5.invoke(response);
        return response;
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }
    private static kotlin.jvm.functions.Function1<? super Episode, kotlin.Unit> emptyEpisodeInitializer() {
        return new kotlin.jvm.functions.Function1<Episode, kotlin.Unit>() {
            @Override public kotlin.Unit invoke(Episode ignored) { return kotlin.Unit.INSTANCE; }
        };
    }
    public static com.lagradost.cloudstream3.Episode newEpisode(com.lagradost.cloudstream3.MainAPI p0, java.lang.Object p1, kotlin.jvm.functions.Function1<? super Episode, kotlin.Unit> p2) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(p0, "provider");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(p2, "initializer");
        if (p1 instanceof String) return newEpisode(p0, (String) p1, p2, true);
        if (p1 == null) {
            return MainAPIKt.<RuntimeException, Episode>sneakyThrow(
                    new ErrorLoadingException("invalid newEpisode"));
        }
        String data = OrbitCs3ResponseBuilders.serializeCs3ProviderData(p1);
        Episode episode = new Episode(data, null, null, null, null,
                (Score) null, null, null, null);
        p2.invoke(episode);
        return episode;
    }
    public static com.lagradost.cloudstream3.Episode newEpisode$default(com.lagradost.cloudstream3.MainAPI provider, java.lang.Object data, kotlin.jvm.functions.Function1<? super Episode, kotlin.Unit> initializer, int mask, java.lang.Object marker) {
        if ((mask & (1 << 1)) != 0) initializer = emptyEpisodeInitializer();
        return newEpisode(provider, data, initializer);
    }
    public static com.lagradost.cloudstream3.Episode newEpisode(com.lagradost.cloudstream3.MainAPI provider, java.lang.String url, kotlin.jvm.functions.Function1<? super Episode, kotlin.Unit> initializer, boolean fix) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(provider, "provider");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(url, "url");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(initializer, "initializer");
        Episode episode = new Episode(fix ? fixUrl(provider, url) : url, null, null, null, null,
                (Score) null, null, null, null);
        initializer.invoke(episode);
        return episode;
    }
    public static com.lagradost.cloudstream3.Episode newEpisode$default(com.lagradost.cloudstream3.MainAPI provider, java.lang.String url, kotlin.jvm.functions.Function1<? super Episode, kotlin.Unit> initializer, boolean fix, int mask, java.lang.Object marker) {
        if ((mask & (1 << 1)) != 0) initializer = emptyEpisodeInitializer();
        if ((mask & (1 << 2)) != 0) fix = true;
        return newEpisode(provider, url, initializer, fix);
    }
    public static void addEpisodes(AnimeLoadResponse response, DubStatus status, java.util.List<Episode> episodes) {
        if (response == null || status == null || episodes == null || episodes.isEmpty()) return;
        java.util.ArrayList<Episode> merged = new java.util.ArrayList<>();
        java.util.Map<DubStatus, java.util.List<Episode>> current = response.getEpisodes();
        java.util.List<Episode> existing = current.get(status);
        if (existing != null) for (Episode value : existing) {
            if (value != null) merged.add(value);
        }
        for (Object value : episodes) {
            if (!(value instanceof Episode)) {
                throw new IllegalArgumentException("Anime episode lists must contain Episode values.");
            }
            merged.add((Episode) value);
        }
        current.put(status, merged);
    }
    public static com.lagradost.cloudstream3.HomePageResponse newHomePageResponse(com.lagradost.cloudstream3.HomePageList p0, java.lang.Boolean p1) {
        if (p0 == null) throw new IllegalArgumentException("home page list is required");
        return newHomePageResponse(java.util.Collections.singletonList(p0), p1);
    }
    public static com.lagradost.cloudstream3.HomePageResponse newHomePageResponse(com.lagradost.cloudstream3.MainPageRequest p0, java.util.List<? extends com.lagradost.cloudstream3.SearchResponse> p1, java.lang.Boolean p2) {
        if (p0 == null || p1 == null) throw new IllegalArgumentException("home page request and items are required");
        com.lagradost.cloudstream3.HomePageList row = new com.lagradost.cloudstream3.HomePageList(
                p0.getName(), p1,
                p0.getHorizontalImages());
        return newHomePageResponse(row, p2);
    }
    public static com.lagradost.cloudstream3.HomePageResponse newHomePageResponse(java.lang.String p0, java.util.List<? extends com.lagradost.cloudstream3.SearchResponse> p1, java.lang.Boolean p2) {
        if (p0 == null || p1 == null) throw new IllegalArgumentException("home page name and items are required");
        com.lagradost.cloudstream3.HomePageList row = new com.lagradost.cloudstream3.HomePageList(
                p0, p1, false);
        boolean hasNext = p2 == null ? !p1.isEmpty() : p2.booleanValue();
        return new com.lagradost.cloudstream3.HomePageResponse(java.util.Collections.singletonList(row), hasNext);
    }
    public static com.lagradost.cloudstream3.HomePageResponse newHomePageResponse(java.util.List<com.lagradost.cloudstream3.HomePageList> p0, java.lang.Boolean p1) {
        if (p0 == null) throw new IllegalArgumentException("home page lists are required");
        boolean hasItems = false;
        for (Object item : p0) {
            if (!(item instanceof com.lagradost.cloudstream3.HomePageList)) {
                throw new IllegalArgumentException("home page response contains an unsupported row type");
            }
            if (!((com.lagradost.cloudstream3.HomePageList) item).getList().isEmpty()) hasItems = true;
        }
        boolean hasNext = p1 == null ? hasItems : p1.booleanValue();
        return new com.lagradost.cloudstream3.HomePageResponse(
                p0, hasNext);
    }
    public static com.lagradost.cloudstream3.HomePageResponse newHomePageResponse$default(java.lang.String p0, java.util.List<? extends com.lagradost.cloudstream3.SearchResponse> p1, java.lang.Boolean p2, int p3, java.lang.Object p4) {
        return newHomePageResponse(p0, p1, (p3 & 4) != 0 ? null : p2);
    }
    public static com.lagradost.cloudstream3.HomePageResponse newHomePageResponse$default(com.lagradost.cloudstream3.MainPageRequest p0, java.util.List<? extends com.lagradost.cloudstream3.SearchResponse> p1, java.lang.Boolean p2, int p3, java.lang.Object p4) {
        return newHomePageResponse(p0, p1, (p3 & 4) != 0 ? null : p2);
    }
    public static com.lagradost.cloudstream3.HomePageResponse newHomePageResponse$default(com.lagradost.cloudstream3.HomePageList p0, java.lang.Boolean p1, int p2, java.lang.Object p3) {
        return newHomePageResponse(p0, (p2 & 2) != 0 ? null : p1);
    }
    public static com.lagradost.cloudstream3.HomePageResponse newHomePageResponse$default(java.util.List<com.lagradost.cloudstream3.HomePageList> p0, java.lang.Boolean p1, int p2, java.lang.Object p3) {
        return newHomePageResponse(p0, (p2 & 2) != 0 ? null : p1);
    }
    public static com.lagradost.cloudstream3.LiveSearchResponse newLiveSearchResponse(com.lagradost.cloudstream3.MainAPI p0, java.lang.String p1, java.lang.String p2, com.lagradost.cloudstream3.TvType p3, boolean p4, kotlin.jvm.functions.Function1<? super LiveSearchResponse, kotlin.Unit> p5) {
        if (p0 == null) throw new NullPointerException("provider");
        if (p1 == null) throw new NullPointerException("name");
        if (p2 == null) throw new NullPointerException("url");
        if (p3 == null) throw new NullPointerException("type");
        if (p5 == null) throw new NullPointerException("initializer");

        String providerName = p0.getName();
        if (providerName == null) throw new NullPointerException("provider.name");
        String responseUrl = p4 ? fixUrl(p0, p2) : p2;
        LiveSearchResponse response = new LiveSearchResponse(
                p1, responseUrl, providerName, p3,
                null, null, null, null, null, null);
        p5.invoke(response);
        return response;
    }
    public static com.lagradost.cloudstream3.LiveSearchResponse newLiveSearchResponse$default(com.lagradost.cloudstream3.MainAPI p0, java.lang.String p1, java.lang.String p2, com.lagradost.cloudstream3.TvType p3, boolean p4, kotlin.jvm.functions.Function1<? super LiveSearchResponse, kotlin.Unit> p5, int p6, java.lang.Object p7) {
        if (p0 == null) throw new NullPointerException("provider");
        if (p1 == null) throw new NullPointerException("name");
        if (p2 == null) throw new NullPointerException("url");
        return newLiveSearchResponse(
                p0, p1, p2,
                (p6 & 0x4) != 0 ? TvType.Live : p3,
                (p6 & 0x8) != 0 || p4,
                (p6 & 0x10) != 0 ? NO_LIVE_SEARCH_RESPONSE_INITIALIZER : p5);
    }
    public static com.lagradost.cloudstream3.TvSeriesSearchResponse newTvSeriesSearchResponse$default(com.lagradost.cloudstream3.MainAPI p0, java.lang.String p1, java.lang.String p2, com.lagradost.cloudstream3.TvType p3, boolean p4, kotlin.jvm.functions.Function1<? super TvSeriesSearchResponse, kotlin.Unit> p5, int p6, java.lang.Object p7) {
        if (p0 == null) throw new NullPointerException("provider");
        if (p1 == null) throw new NullPointerException("name");
        if (p2 == null) throw new NullPointerException("url");
        return newTvSeriesSearchResponse(
                p0, p1, p2,
                (p6 & 0x4) != 0 ? TvType.TvSeries : p3,
                (p6 & 0x8) != 0 || p4,
                (p6 & 0x10) != 0 ? NO_TV_SERIES_SEARCH_RESPONSE_INITIALIZER : p5);
    }
    public static com.lagradost.cloudstream3.TvSeriesSearchResponse newTvSeriesSearchResponse(
            com.lagradost.cloudstream3.MainAPI provider, java.lang.String name,
            java.lang.String url, com.lagradost.cloudstream3.TvType type, boolean fix,
            kotlin.jvm.functions.Function1<? super TvSeriesSearchResponse, kotlin.Unit> initializer) {
        if (provider == null) throw new NullPointerException("provider");
        if (name == null) throw new NullPointerException("name");
        if (url == null) throw new NullPointerException("url");
        if (type == null) throw new NullPointerException("type");
        if (initializer == null) throw new NullPointerException("initializer");

        String responseUrl = fix ? fixUrl(provider, url) : url;
        String providerName = provider.getName();
        if (providerName == null) throw new NullPointerException("provider.name");
        TvSeriesSearchResponse response = new TvSeriesSearchResponse(
                name, responseUrl, providerName, type,
                null, null, null, null, null, null, null);
        initializer.invoke(response);
        return response;
    }
    public static java.lang.Object newLiveStreamLoadResponse(com.lagradost.cloudstream3.MainAPI provider, java.lang.String name, java.lang.String url, java.lang.String dataUrl, kotlin.jvm.functions.Function2<? super LiveStreamLoadResponse, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends Object> initializer, kotlin.coroutines.Continuation<? super LiveStreamLoadResponse> continuation) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(provider, "provider");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(name, "name");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(url, "url");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(dataUrl, "dataUrl");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(initializer, "initializer");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(continuation, "continuation");
        return OrbitCs3ResponseBuilders.newLiveStreamLoadResponse(
                provider, name, url, dataUrl, initializer, continuation);
    }
    public static java.lang.Object newLiveStreamLoadResponse$default(com.lagradost.cloudstream3.MainAPI provider, java.lang.String name, java.lang.String url, java.lang.String dataUrl, kotlin.jvm.functions.Function2<? super LiveStreamLoadResponse, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends Object> initializer, kotlin.coroutines.Continuation<? super LiveStreamLoadResponse> continuation, int mask, java.lang.Object marker) {
        if ((mask & 0x8) != 0) initializer = NO_SUSPEND_INITIALIZER;
        return newLiveStreamLoadResponse(provider, name, url, dataUrl, initializer, continuation);
    }
    public static java.lang.Object newMovieLoadResponse(com.lagradost.cloudstream3.MainAPI p0, java.lang.String p1, java.lang.String p2, com.lagradost.cloudstream3.TvType p3, java.lang.Object p4, kotlin.jvm.functions.Function2<? super MovieLoadResponse, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends Object> p5, kotlin.coroutines.Continuation<? super MovieLoadResponse> p6) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(p5, "initializer");
        return OrbitCs3ResponseBuilders.newMovieLoadResponse(p0, p1, p2, p3, p4, p5, p6);
    }
    public static java.lang.Object newMovieLoadResponse(com.lagradost.cloudstream3.MainAPI p0, java.lang.String p1, java.lang.String p2, com.lagradost.cloudstream3.TvType p3, java.lang.String p4, kotlin.jvm.functions.Function2<? super MovieLoadResponse, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends Object> p5, kotlin.coroutines.Continuation<? super MovieLoadResponse> p6) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(p5, "initializer");
        return OrbitCs3ResponseBuilders.newMovieLoadResponse(p0, p1, p2, p3, p4, p5, p6);
    }
    public static java.lang.Object newMovieLoadResponse$default(com.lagradost.cloudstream3.MainAPI p0, java.lang.String p1, java.lang.String p2, com.lagradost.cloudstream3.TvType p3, java.lang.Object p4, kotlin.jvm.functions.Function2<? super MovieLoadResponse, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends Object> p5, kotlin.coroutines.Continuation<? super MovieLoadResponse> p6, int p7, java.lang.Object p8) {
        return newMovieLoadResponse(p0, p1, p2, p3, p4,
                (p7 & 16) != 0 ? NO_SUSPEND_INITIALIZER : p5, p6);
    }
    public static java.lang.Object newMovieLoadResponse$default(com.lagradost.cloudstream3.MainAPI p0, java.lang.String p1, java.lang.String p2, com.lagradost.cloudstream3.TvType p3, java.lang.String p4, kotlin.jvm.functions.Function2<? super MovieLoadResponse, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends Object> p5, kotlin.coroutines.Continuation<? super MovieLoadResponse> p6, int p7, java.lang.Object p8) {
        return newMovieLoadResponse(p0, p1, p2, p3, p4,
                (p7 & 16) != 0 ? NO_SUSPEND_INITIALIZER : p5, p6);
    }
    public static com.lagradost.cloudstream3.MovieSearchResponse newMovieSearchResponse$default(com.lagradost.cloudstream3.MainAPI p0, java.lang.String p1, java.lang.String p2, com.lagradost.cloudstream3.TvType p3, boolean p4, kotlin.jvm.functions.Function1<? super MovieSearchResponse, kotlin.Unit> p5, int p6, java.lang.Object p7) {
        com.lagradost.cloudstream3.TvType type = (p6 & 4) != 0
                ? com.lagradost.cloudstream3.TvType.Movie : p3;
        boolean fix = (p6 & 8) != 0 || p4;
        kotlin.jvm.functions.Function1<? super MovieSearchResponse, kotlin.Unit> initializer = (p6 & 16) != 0
                ? NO_MOVIE_SEARCH_RESPONSE_INITIALIZER : p5;
        return newMovieSearchResponse(p0, p1, p2, type, fix, initializer);
    }

    /** Source-backed pinned MainAPIKt.newMovieSearchResponse builder. */
    public static com.lagradost.cloudstream3.MovieSearchResponse newMovieSearchResponse(
            com.lagradost.cloudstream3.MainAPI provider, java.lang.String name,
            java.lang.String url, com.lagradost.cloudstream3.TvType type, boolean fix,
            kotlin.jvm.functions.Function1<? super MovieSearchResponse, kotlin.Unit> initializer) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(provider, "this");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(name, "name");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(url, "url");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(type, "type");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(initializer, "initializer");
        String fixedUrl = fix ? fixUrl(provider, url) : url;
        com.lagradost.cloudstream3.MovieSearchResponse response =
                new com.lagradost.cloudstream3.MovieSearchResponse(name, fixedUrl, provider.getName(),
                        type, null, null, null, null, null, null);
        initializer.invoke(response);
        return response;
    }
    public static com.lagradost.cloudstream3.SearchResponseList newSearchResponseList(java.util.List<? extends com.lagradost.cloudstream3.SearchResponse> p0, java.lang.Boolean p1) {
        if (p0 == null) throw new IllegalArgumentException("search items are required");
        return new com.lagradost.cloudstream3.SearchResponseList(
                p0,
                p1 == null ? !p0.isEmpty() : p1.booleanValue());
    }
    public static com.lagradost.cloudstream3.SearchResponseList newSearchResponseList$default(java.util.List<? extends com.lagradost.cloudstream3.SearchResponse> p0, java.lang.Boolean p1, int p2, java.lang.Object p3) {
        return newSearchResponseList(p0, (p2 & 2) != 0 ? null : p1);
    }
    public static com.lagradost.cloudstream3.SearchResponseList toNewSearchResponseList(java.util.List<? extends com.lagradost.cloudstream3.SearchResponse> p0, java.lang.Boolean p1) {
        return newSearchResponseList(p0, p1);
    }
    public static java.lang.Object newSubtitleFile(java.lang.String p0, java.lang.String p1, kotlin.jvm.functions.Function2<? super SubtitleFile, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends Object> p2, kotlin.coroutines.Continuation<? super SubtitleFile> p3) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(p2, "initializer");
        return OrbitCs3ResponseBuilders.newSubtitleFile(p0, p1, p2, p3);
    }
    public static java.lang.Object newSubtitleFile$default(java.lang.String p0, java.lang.String p1, kotlin.jvm.functions.Function2<? super SubtitleFile, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends Object> p2, kotlin.coroutines.Continuation<? super SubtitleFile> p3, int p4, java.lang.Object p5) {
        return newSubtitleFile(p0, p1, (p4 & 4) != 0 ? NO_SUSPEND_INITIALIZER : p2, p3);
    }
    public static java.lang.Object newAudioFile(java.lang.String url, kotlin.jvm.functions.Function2<? super AudioFile, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends Object> initializer, kotlin.coroutines.Continuation<? super AudioFile> continuation) {
        if (url == null) throw new NullPointerException("url");
        if (initializer == null) throw new NullPointerException("initializer");
        if (continuation == null) throw new NullPointerException("continuation");
        final AudioFile audioFile = new AudioFile(url, null);
        kotlin.coroutines.Continuation<kotlin.Unit> completion = new kotlin.coroutines.Continuation<kotlin.Unit>() {
            @Override public kotlin.coroutines.CoroutineContext getContext() {
                return continuation.getContext();
            }
            @Override public void resumeWith(Object outcome) {
                try {
                    kotlin.ResultKt.throwOnFailure(outcome);
                } catch (Throwable failure) {
                    continuation.resumeWith(kotlin.ResultKt.createFailure(failure));
                    return;
                }
                continuation.resumeWith(audioFile);
            }
        };
        Object result = initializer.invoke(audioFile, completion);
        return result == kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()
                ? result : audioFile;
    }
    public static java.lang.Object newAudioFile$default(java.lang.String url, kotlin.jvm.functions.Function2<? super AudioFile, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends Object> initializer, kotlin.coroutines.Continuation<? super AudioFile> continuation, int mask, java.lang.Object marker) {
        return newAudioFile(url, (mask & 2) != 0 ? NO_SUSPEND_INITIALIZER : initializer, continuation);
    }
    public static java.lang.Object newTvSeriesLoadResponse(com.lagradost.cloudstream3.MainAPI p0, java.lang.String p1, java.lang.String p2, com.lagradost.cloudstream3.TvType p3, java.util.List<Episode> p4, kotlin.jvm.functions.Function2<? super TvSeriesLoadResponse, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends Object> p5, kotlin.coroutines.Continuation<? super TvSeriesLoadResponse> p6) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(p0, "this");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(p1, "name");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(p2, "url");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(p3, "type");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(p4, "episodes");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(p5, "initializer");
        return OrbitCs3ResponseBuilders.newTvSeriesLoadResponse(p0, p1, p2, p3, p4, p5, p6);
    }
    public static java.lang.Object newTvSeriesLoadResponse$default(com.lagradost.cloudstream3.MainAPI provider, java.lang.String name, java.lang.String url, com.lagradost.cloudstream3.TvType type, java.util.List<Episode> episodes, kotlin.jvm.functions.Function2<? super TvSeriesLoadResponse, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends Object> initializer, kotlin.coroutines.Continuation<? super TvSeriesLoadResponse> continuation, int mask, java.lang.Object marker) {
        return newTvSeriesLoadResponse(provider, name, url, type, episodes,
                (mask & (1 << 4)) != 0 ? NO_SUSPEND_INITIALIZER : initializer, continuation);
    }
    public static com.lagradost.cloudstream3.SearchResponseList toNewSearchResponseList$default(java.util.List<? extends com.lagradost.cloudstream3.SearchResponse> p0, java.lang.Boolean p1, int p2, java.lang.Object p3) {
        return newSearchResponseList(p0, (p2 & 2) != 0 ? null : p1);
    }
}
