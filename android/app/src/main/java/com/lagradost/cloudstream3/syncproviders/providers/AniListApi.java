package com.lagradost.cloudstream3.syncproviders.providers;

import androidx.annotation.WorkerThread;

import com.auras.orbit.BuildConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.lagradost.cloudstream3.Actor;
import com.lagradost.cloudstream3.ActorData;
import com.lagradost.cloudstream3.ActorRole;
import com.lagradost.cloudstream3.ErrorLoadingException;
import com.lagradost.cloudstream3.MainActivityKt;
import com.lagradost.cloudstream3.MainAPIKt;
import com.lagradost.cloudstream3.NextAiring;
import com.lagradost.cloudstream3.Score;
import com.lagradost.cloudstream3.ShowStatus;
import com.lagradost.cloudstream3.TvType;
import com.lagradost.cloudstream3.syncproviders.AuthAPI;
import com.lagradost.cloudstream3.syncproviders.AuthData;
import com.lagradost.cloudstream3.syncproviders.AuthLoginPage;
import com.lagradost.cloudstream3.syncproviders.AuthToken;
import com.lagradost.cloudstream3.syncproviders.AuthUser;
import com.lagradost.cloudstream3.syncproviders.SyncAPI;
import com.lagradost.cloudstream3.syncproviders.SyncIdName;
import com.lagradost.cloudstream3.ui.SyncWatchType;
import com.lagradost.cloudstream3.ui.library.ListSorting;
import com.lagradost.cloudstream3.utils.UiText;
import com.lagradost.nicehttp.Requests;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.Proxy;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.TimeZone;

import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.Dispatchers;
import okhttp3.CookieJar;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** AniList GraphQL provider adapted to Orbit's profile-scoped SyncAPI contract. */
public class AniListApi extends SyncAPI {
    private static final String ANILIST_ORIGIN = "https://anilist.co";
    private static final String GRAPHQL_URL = "https://graphql.anilist.co";
    private static final String CALLBACK_SCHEME = "cloudstreamapp";
    private static final String CALLBACK_HOST = "anilistlogin";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private static final int MAX_URL_CHARS = 2_048;
    private static final int MAX_REDIRECT_CHARS = 8_192;
    private static final int MAX_ACCESS_TOKEN_CHARS = 4_096;
    private static final int MAX_QUERY_CHARS = 12_000;
    private static final int MAX_REQUEST_BYTES = 32 * 1024;
    /**
     * The bounded built-in library sync can return up to 11,000 entries, which may exceed the
     * generic 512 KiB CS3 response cap. Keep AniList's isolated response limit at 2 MiB and
     * enforce it again while streaming the decompressed response body.
     */
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SEARCH_CHARS = 200;
    private static final int MAX_SEARCH_RESULTS = 10;
    private static final int MAX_RECOMMENDATIONS = 20;
    private static final int MAX_ACTORS = 40;
    private static final int MAX_LIBRARY_ITEMS = 11_000;
    private static final int MAX_LIBRARY_LISTS = 100;
    private static final int MAX_PROGRESS = 100_000;
    private static final long MAX_TOKEN_LIFETIME_SECONDS = 31_536_000L;

    /**
     * Isolated AniList transport copies DNS from the active Orbit app client (the host-created
     * default uses validated public DNS) and disables proxy and cookies. A caller that replaces
     * the mutable app client can supply different DNS and is outside that host guarantee. The
     * fixed GraphQL endpoint disables redirects and connection retries so a bearer token is never
     * forwarded and a mutation is never replayed. Time bounds are stricter than the shared client;
     * the bounded 2 MiB AniList response cap is the documented exception.
     */
    private static final OkHttpClient HTTP = createHttpClient();

    private static OkHttpClient createHttpClient() {
        Requests sharedRequests = MainActivityKt.getApp();
        OkHttpClient sharedClient = sharedRequests == null ? null : sharedRequests.getBaseClient();
        if (sharedClient == null) throw new IllegalStateException("Orbit's shared HTTP client is unavailable.");
        return new OkHttpClient.Builder()
                .dns(sharedClient.dns())
                .proxy(Proxy.NO_PROXY)
                .cookieJar(CookieJar.NO_COOKIES)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build();
    }

    public AniListApi() { super(); }

    @Override public String getName() { return "AniList"; }
    @Override public String getIdPrefix() { return "anilist"; }
    @Override public String getMainUrl() { return ANILIST_ORIGIN; }
    @Override public String getCreateAccountUrl() { return ANILIST_ORIGIN + "/signup"; }
    @Override public boolean getHasOAuth2() { return true; }
    @Override public String getRedirectUrlIdentifier() { return CALLBACK_HOST; }

    @Override public SyncIdName getSyncIdName() { return SyncIdName.Anilist; }

    /** Missing or malformed Orbit configuration keeps OAuth unavailable. */
    @Override public AuthLoginPage loginRequest() {
        String clientId = BuildConfig.ORBIT_ANILIST_CLIENT_ID;
        if (clientId == null || !clientId.matches("[0-9]{1,20}")) return null;
        String url = ANILIST_ORIGIN + "/api/v2/oauth/authorize?client_id=" + clientId
                + "&response_type=token";
        if (url.length() > MAX_URL_CHARS) return null;
        return new AuthLoginPage(url, null);
    }

    /** Only the registered Orbit callback host is accepted by the provider. */
    @Override public boolean isValidRedirectUrl(String url) {
        return parseCallback(url) != null;
    }

    @Override public Object login(String redirectUrl, String payload,
                                  Continuation<? super AuthToken> continuation) {
        return onIo(new IoAction<AuthToken>() {
            @Override public AuthToken run() {
                try {
                    URI callback = parseCallback(redirectUrl);
                    if (callback == null) return null;
                    Map<String, String> parameters = AuthAPI.Companion.splitRedirectUrl(redirectUrl);
                    String accessToken = parameters.get("access_token");
                    String expiresIn = parameters.get("expires_in");
                    if (!isUsableAccessToken(accessToken)) return null;
                    Long lifetime = parseTokenLifetime(expiresIn);
                    if (lifetime == null) return null;
                    long expiresAt = System.currentTimeMillis() / 1000L + lifetime;
                    return new AuthToken(accessToken, null, expiresAt, null, null);
                } catch (Exception malformedRedirect) {
                    return null;
                }
            }
        }, null, continuation);
    }

    @Override public Object user(AuthToken token, Continuation<? super AuthUser> continuation) {
        return onIoPropagating(new IoAction<AuthUser>() {
            @Override public AuthUser run() throws Exception {
                if (token == null || !isUsableAccessToken(token.getAccessToken())) return null;
                JsonObject data = postGraphQl(token.getAccessToken(), """
                        query {
                          Viewer {
                            id
                            name
                            avatar { large }
                          }
                        }
                        """, new JsonObject());
                JsonObject viewer = child(data, "Viewer");
                Integer id = integer(viewer, "id");
                String name = text(viewer, "name", 256);
                if (id == null || id < 1 || name == null || name.isEmpty()) {
                    throw new ErrorLoadingException("Unable to fetch user data");
                }
                String avatar = safeHttpsUrl(text(child(viewer, "avatar"), "large", MAX_URL_CHARS));
                return new AuthUser(name, id, avatar, null);
            }
        }, continuation);
    }

    @Override public String urlToId(String url) {
        Integer id = parseMediaId(url, true);
        return id == null ? null : String.valueOf(id);
    }

    @Override @WorkerThread public Object search(AuthData auth, String query,
            Continuation<? super List<SyncSearchResult>> continuation) {
        if (query == null || query.trim().isEmpty() || query.length() > MAX_SEARCH_CHARS) {
            return Collections.emptyList();
        }
        return onIo(new IoAction<List<SyncSearchResult>>() {
            @Override public List<SyncSearchResult> run() throws Exception {
                JsonObject variables = new JsonObject();
                variables.addProperty("search", query.trim());
                JsonObject data = postGraphQl(null, """
                        query ($search: String!) {
                          Page(page: 1, perPage: 10) {
                            media(search: $search, type: ANIME) {
                              id
                              title { romaji }
                              bannerImage
                            }
                          }
                        }
                        """, variables);
                JsonObject page = child(data, "Page");
                JsonArray media = array(page, "media");
                if (media == null) return null;
                List<SyncSearchResult> results = new ArrayList<>();
                int count = Math.min(media.size(), MAX_SEARCH_RESULTS);
                for (int index = 0; index < count; index++) {
                    JsonObject item = object(media.get(index));
                    Integer id = integer(item, "id");
                    JsonObject title = child(item, "title");
                    String name = firstText(title, "romaji");
                    if (id == null || id < 1) continue;
                    if (name == null || name.isEmpty()) return null;
                    String banner = safeHttpsUrl(text(item, "bannerImage", MAX_URL_CHARS));
                    results.add(searchResult(id, name, banner));
                }
                return results;
            }
        }, null, continuation);
    }

    @Override @WorkerThread public Object load(AuthData auth, String id,
            Continuation<? super SyncResult> continuation) {
        Integer mediaId = parseMediaId(id, true);
        return onIoPropagating(new IoAction<SyncResult>() {
            @Override public SyncResult run() throws Exception {
                if (mediaId == null) throw new ErrorLoadingException("Invalid internalId");
                JsonObject variables = new JsonObject();
                variables.addProperty("id", mediaId);
                JsonObject data = postGraphQl(null, """
                        query ($id: Int!) {
                          Media(id: $id, type: ANIME) {
                            id
                            status
                            title { romaji english native userPreferred }
                            coverImage { extraLarge large medium }
                            bannerImage
                            duration
                            episodes
                            genres
                            synonyms
                            averageScore
                            isAdult
                            description(asHtml: false)
                            characters(sort: ROLE, page: 1, perPage: 20) {
                              edges {
                                role
                                node { name { userPreferred full native } image { large medium } }
                                voiceActors {
                                  name { userPreferred full native }
                                  image { large medium }
                                }
                              }
                            }
                            trailer { id site }
                            recommendations {
                              edges { node { mediaRecommendation {
                                id
                                title { romaji english native userPreferred }
                                coverImage { extraLarge large medium }
                              } } }
                            }
                            nextAiringEpisode { episode timeUntilAiring }
                          }
                        }
                        """, variables);
                JsonObject media = child(data, "Media");
                Integer returnedId = integer(media, "id");
                if (returnedId == null || returnedId.intValue() != mediaId.intValue()) return null;
                JsonObject titleData = child(media, "title");
                String title = firstText(titleData, "userPreferred", "english", "romaji", "native");
                JsonObject cover = child(media, "coverImage");
                Integer episodes = boundedInt(media, "episodes", 0, MAX_PROGRESS);
                Integer duration = boundedInt(media, "duration", 0, 1000);
                Integer score = boundedInt(media, "averageScore", 0, 100);
                List<String> genres = strings(media, "genres", 50, 80);
                List<String> synonyms = strings(media, "synonyms", 50, 256);
                List<ActorData> actors = actors(child(media, "characters"));
                List<SyncSearchResult> recommendations = recommendations(child(media, "recommendations"));
                List<String> trailers = trailerUrls(child(media, "trailer"));
                NextAiring nextAiring = nextAiring(child(media, "nextAiringEpisode"));
                ShowStatus airStatus = showStatus(text(media, "status", 40));
                String poster = coverUrl(cover);
                String banner = safeHttpsUrl(text(media, "bannerImage", MAX_URL_CHARS));
                String synopsis = text(media, "description", 8_192);
                Boolean adult = bool(media, "isAdult");

                return new SyncResult(
                        String.valueOf(returnedId), episodes, title,
                        Score.from100(score), duration, synopsis, airStatus, nextAiring,
                        null, genres, synonyms, trailers, adult, poster, banner,
                        null, null, recommendations, null, null, actors);
            }
        }, continuation);
    }

    @Override @WorkerThread public Object status(AuthData auth, String id,
            Continuation<? super AbstractSyncStatus> continuation) {
        Integer mediaId = parseMediaId(id, false);
        if (auth == null || auth.getToken() == null || mediaId == null
                || !isUsableAccessToken(auth.getToken().getAccessToken())) {
            return null;
        }
        return onIoPropagating(new IoAction<AbstractSyncStatus>() {
            @Override public AbstractSyncStatus run() throws Exception {
                JsonObject variables = new JsonObject();
                variables.addProperty("id", mediaId);
                JsonObject data = postGraphQl(auth.getToken().getAccessToken(), """
                        query ($id: Int!) {
                          Media(id: $id, type: ANIME) {
                            id
                            episodes
                            isFavourite
                            mediaListEntry { progress status score(format: POINT_100) }
                          }
                        }
                        """, variables);
                JsonObject media = child(data, "Media");
                if (integer(media, "id") == null) return null;
                Integer episodes = boundedInt(media, "episodes", 0, MAX_PROGRESS);
                Boolean favorite = bool(media, "isFavourite");
                JsonObject entry = child(media, "mediaListEntry");
                if (entry == null) {
                    return new SyncStatus(SyncWatchType.NONE, Score.from100(0), 0, favorite, episodes);
                }
                Integer progress = boundedInt(entry, "progress", 0, MAX_PROGRESS);
                Integer rawScore = boundedInt(entry, "score", 0, 100);
                SyncWatchType watchType = watchType(text(entry, "status", 32));
                return new SyncStatus(watchType, Score.from100(rawScore), progress, favorite, episodes);
            }
        }, continuation);
    }

    @Override @WorkerThread public Object updateStatus(AuthData auth, String id, AbstractSyncStatus newStatus,
            Continuation<? super Boolean> continuation) {
        Integer mediaId = parseMediaId(id, false);
        if (auth == null || newStatus == null || mediaId == null
                || auth.getToken() == null || !isUsableAccessToken(auth.getToken().getAccessToken())) {
            return false;
        }
        return onIoPropagating(new IoAction<Boolean>() {
            @Override public Boolean run() throws Exception {
                String token = auth.getToken().getAccessToken();
                SyncWatchType status = newStatus.getStatus();
                String anilistStatus = anilistStatus(status);
                if (anilistStatus == null) return false;

                boolean updated = anilistStatus.equals("NONE")
                        ? removeListEntry(token, auth.getUser().getId(), mediaId)
                        : saveListEntry(token, mediaId, anilistStatus,
                                newStatus.getScore(), newStatus.getWatchedEpisodes());
                if (!updated) return false;

                Boolean favorite = newStatus.isFavorite();
                if (favorite != null) {
                    Boolean currentFavorite = readFavorite(token, mediaId);
                    if (currentFavorite == null) return false;
                    if (!currentFavorite.equals(favorite)) return toggleFavorite(token, mediaId);
                }
                return true;
            }
        }, continuation);
    }

    @Override @WorkerThread public Object library(AuthData auth,
            Continuation<? super LibraryMetadata> continuation) {
        if (auth == null || auth.getToken() == null
                || !isUsableAccessToken(auth.getToken().getAccessToken())
                || auth.getUser() == null || auth.getUser().getId() < 1) return null;
        return onIoPropagating(new IoAction<LibraryMetadata>() {
            @Override public LibraryMetadata run() throws Exception {
                JsonObject variables = new JsonObject();
                variables.addProperty("userId", auth.getUser().getId());
                JsonObject data = postGraphQl(auth.getToken().getAccessToken(), """
                        query ($userId: Int!) {
                          MediaListCollection(userId: $userId, type: ANIME) {
                            lists {
                              name
                              status
                              isCustomList
                              entries {
                                status
                                updatedAt
                                progress
                                score(format: POINT_100)
                                media {
                                  id
                                  episodes
                                  seasonYear
                                  title { romaji english userPreferred }
                                  synonyms
                                  coverImage { extraLarge large medium }
                                }
                              }
                            }
                          }
                        }
                        """, variables);
                JsonArray rawLists = array(child(data, "MediaListCollection"), "lists");
                if (rawLists == null || rawLists.size() > MAX_LIBRARY_LISTS) return null;

                LinkedHashMap<String, List<LibraryItem>> grouped = emptyLibraryGroups();
                int totalItems = 0;
                for (int listIndex = 0; listIndex < rawLists.size(); listIndex++) {
                    JsonObject rawList = object(rawLists.get(listIndex));
                    JsonArray entries = array(rawList, "entries");
                    if (entries == null) continue;
                    totalItems += entries.size();
                    if (totalItems > MAX_LIBRARY_ITEMS) return null;

                    String status = text(rawList, "status", 32);
                    String listName = text(rawList, "name", 128);
                    boolean custom = Boolean.TRUE.equals(bool(rawList, "isCustomList"));
                    String group = custom || status == null ? safeListName(listName) : displayStatus(status);
                    List<LibraryItem> items = grouped.computeIfAbsent(group, ignored -> new ArrayList<>());
                    for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
                        LibraryItem item = libraryItem(object(entries.get(entryIndex)));
                        if (item != null) items.add(item);
                    }
                }

                List<LibraryList> lists = new ArrayList<>();
                for (Map.Entry<String, List<LibraryItem>> group : grouped.entrySet()) {
                    lists.add(new LibraryList(new UiText.DynamicString(group.getKey()), group.getValue()));
                }
                return new LibraryMetadata(lists, Collections.unmodifiableSet(new java.util.LinkedHashSet<>(
                        java.util.Arrays.asList(ListSorting.AlphabeticalA, ListSorting.AlphabeticalZ,
                                ListSorting.UpdatedNew, ListSorting.UpdatedOld,
                                ListSorting.ReleaseDateNew, ListSorting.ReleaseDateOld,
                                ListSorting.RatingHigh, ListSorting.RatingLow))));
            }
        }, continuation);
    }

    private static JsonObject postGraphQl(String accessToken, String query, JsonObject variables)
            throws IOException {
        if (query == null || query.length() > MAX_QUERY_CHARS || variables == null) {
            throw new IOException("The AniList request is outside its size limits.");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("query", query);
        payload.add("variables", variables);
        RequestBody body = RequestBody.create(payload.toString(), JSON_MEDIA_TYPE);
        if (body.contentLength() < 0 || body.contentLength() > MAX_REQUEST_BYTES) {
            throw new IOException("The AniList request is outside its size limits.");
        }
        Request.Builder request = new Request.Builder()
                .url(GRAPHQL_URL)
                .post(body)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache, no-store")
                .header("User-Agent", MainAPIKt.USER_AGENT);
        if (accessToken != null) {
            if (!isUsableAccessToken(accessToken)) return null;
            request.header("Authorization", "Bearer " + accessToken);
        }
        try (Response response = HTTP.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) throw new IOException("AniList returned HTTP " + response.code() + ".");
            ResponseBody responseBody = response.body();
            if (responseBody == null) throw new IOException("AniList returned an empty response.");
            String contentType = response.header("Content-Type");
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("json")) {
                throw new IOException("AniList returned a non-JSON response.");
            }
            long declaredLength = responseBody.contentLength();
            if (declaredLength > MAX_RESPONSE_BYTES) {
                throw new IOException("AniList returned a response that is too large.");
            }
            byte[] bytes;
            try (InputStream input = responseBody.byteStream()) {
                bytes = readBounded(input, MAX_RESPONSE_BYTES);
            }
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            try (JsonReader reader = new JsonReader(new StringReader(json))) {
                reader.setStrictness(Strictness.STRICT);
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed == null || !parsed.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
                    throw new IOException("AniList returned invalid JSON.");
                }
                JsonObject root = parsed.getAsJsonObject();
                JsonArray errors = array(root, "errors");
                if (errors != null && !errors.isEmpty()) {
                    throw new IOException("AniList rejected the GraphQL request.");
                }
                JsonObject data = child(root, "data");
                if (data == null) throw new IOException("AniList returned no GraphQL data.");
                return data;
            } catch (IllegalStateException | NumberFormatException malformedJson) {
                throw new IOException("AniList returned invalid JSON.");
            }
        }
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8_192));
        byte[] buffer = new byte[8_192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) throw new IOException("Input stream made no progress.");
            if (read > limit - total) throw new IOException("AniList returned a response that is too large.");
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static URI parseCallback(String redirectUrl) {
        if (redirectUrl == null || redirectUrl.isEmpty() || redirectUrl.length() > MAX_REDIRECT_CHARS) return null;
        try {
            URI uri = new URI(redirectUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (!CALLBACK_SCHEME.equals(scheme) || uri.isOpaque() || uri.getRawUserInfo() != null
                    || uri.getPort() != -1 || host == null || !CALLBACK_HOST.equalsIgnoreCase(host)
                    || !"/".equals(path)) return null;
            if (uri.getRawQuery() != null && uri.getRawQuery().length() > MAX_REDIRECT_CHARS) return null;
            if (uri.getRawFragment() != null && (uri.getRawQuery() != null
                    || !"/".equals(path) || uri.getRawFragment().length() > MAX_REDIRECT_CHARS)) return null;
            return uri;
        } catch (Exception malformed) {
            return null;
        }
    }

    private static Long parseTokenLifetime(String value) {
        if (value == null || value.isEmpty() || value.length() > 10) return null;
        try {
            long seconds = Long.parseLong(value);
            return seconds > 0 && seconds <= MAX_TOKEN_LIFETIME_SECONDS ? seconds : null;
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static boolean isUsableAccessToken(String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_ACCESS_TOKEN_CHARS) return false;
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (value <= 0x20 || value >= 0x7f || value == '"' || value == '\\') return false;
        }
        return true;
    }

    /** Accepts an AniList numeric ID or a canonical HTTPS anime URL only. */
    private static Integer parseMediaId(String value, boolean allowUrl) {
        if (value == null || value.isEmpty() || value.length() > MAX_URL_CHARS) return null;
        String candidate = value;
        if (allowUrl && value.startsWith("https://")) {
            try {
                URI uri = new URI(value);
                String path = uri.getRawPath();
                if (!"https".equals(uri.getScheme()) || !"anilist.co".equalsIgnoreCase(uri.getHost())
                        || uri.getPort() != -1 || uri.getRawUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || path == null || !path.matches("/anime/[1-9][0-9]{0,9}/?")) return null;
                candidate = path.substring("/anime/".length()).replaceFirst("/$", "");
            } catch (Exception malformed) {
                return null;
            }
        } else if (!value.matches("[1-9][0-9]{0,9}")) {
            return null;
        }
        try {
            int id = Integer.parseInt(candidate);
            return id > 0 ? id : null;
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static SyncSearchResult searchResult(int id, String title, String poster) {
        return new SyncSearchResult(title, "AniList", String.valueOf(id), animeUrl(id), poster,
                TvType.Anime, null, null, id, null);
    }

    private static String animeUrl(int id) {
        return ANILIST_ORIGIN + "/anime/" + id;
    }

    private static String coverUrl(JsonObject image) {
        return safeHttpsUrl(firstText(image, "extraLarge", "large", "medium"));
    }

    private static String safeHttpsUrl(String value) {
        if (value == null || value.length() > MAX_URL_CHARS) return null;
        try {
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getPort() != -1) return null;
            return value;
        } catch (Exception malformed) {
            return null;
        }
    }

    private static List<ActorData> actors(JsonObject connection) {
        JsonArray edges = array(connection, "edges");
        if (edges == null || edges.isEmpty()) return null;
        List<ActorData> result = new ArrayList<>();
        for (int index = 0; index < Math.min(edges.size(), MAX_ACTORS); index++) {
            JsonObject edge = object(edges.get(index));
            JsonObject character = child(edge, "node");
            JsonObject name = child(character, "name");
            String actorName = firstText(name, "userPreferred", "full", "native");
            if (actorName == null || actorName.isEmpty()) continue;
            JsonObject image = child(character, "image");
            Actor actor = new Actor(actorName,
                    safeHttpsUrl(firstText(image, "large", "medium")));
            ActorRole role = actorRole(text(edge, "role", 32));
            Actor voiceActor = firstVoiceActor(array(edge, "voiceActors"));
            result.add(new ActorData(actor, role, null, voiceActor));
        }
        return result.isEmpty() ? null : result;
    }

    private static Actor firstVoiceActor(JsonArray voiceActors) {
        if (voiceActors == null) return null;
        for (int index = 0; index < Math.min(voiceActors.size(), 10); index++) {
            JsonObject staff = object(voiceActors.get(index));
            String name = firstText(child(staff, "name"), "userPreferred", "full", "native");
            if (name == null || name.isEmpty()) continue;
            JsonObject image = child(staff, "image");
            return new Actor(name, safeHttpsUrl(firstText(image, "large", "medium")));
        }
        return null;
    }

    private static ActorRole actorRole(String value) {
        if ("MAIN".equals(value)) return ActorRole.Main;
        if ("SUPPORTING".equals(value)) return ActorRole.Supporting;
        if ("BACKGROUND".equals(value)) return ActorRole.Background;
        return null;
    }

    private static List<SyncSearchResult> recommendations(JsonObject connection) {
        JsonArray edges = array(connection, "edges");
        if (edges == null || edges.isEmpty()) return null;
        List<SyncSearchResult> result = new ArrayList<>();
        for (int index = 0; index < Math.min(edges.size(), MAX_RECOMMENDATIONS); index++) {
            JsonObject node = child(object(edges.get(index)), "node");
            JsonObject media = child(node, "mediaRecommendation");
            Integer id = integer(media, "id");
            String title = firstText(child(media, "title"), "userPreferred", "english", "romaji", "native");
            if (id == null || id < 1 || title == null || title.isEmpty()) continue;
            result.add(searchResult(id, title, coverUrl(child(media, "coverImage"))));
        }
        return result.isEmpty() ? null : result;
    }

    private static List<String> trailerUrls(JsonObject trailer) {
        String site = text(trailer, "site", 32);
        String id = text(trailer, "id", 128);
        if (site == null || id == null || !"youtube".equals(site.toLowerCase(Locale.ROOT))) return null;
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) return null;
        return Collections.singletonList("https://www.youtube.com/watch?v=" + id);
    }

    private static NextAiring nextAiring(JsonObject airing) {
        Integer episode = boundedInt(airing, "episode", 1, MAX_PROGRESS);
        Integer seconds = boundedInt(airing, "timeUntilAiring", 0, 31_536_000);
        if (episode == null || seconds == null) return null;
        return new NextAiring(episode, System.currentTimeMillis() / 1000L + seconds, null);
    }

    private static ShowStatus showStatus(String value) {
        if ("FINISHED".equals(value)) return ShowStatus.Completed;
        if ("RELEASING".equals(value) || "NOT_YET_RELEASED".equals(value)) return ShowStatus.Ongoing;
        return null;
    }

    private static SyncWatchType watchType(String status) {
        if ("CURRENT".equals(status)) return SyncWatchType.WATCHING;
        if ("COMPLETED".equals(status)) return SyncWatchType.COMPLETED;
        if ("PAUSED".equals(status)) return SyncWatchType.ONHOLD;
        if ("DROPPED".equals(status)) return SyncWatchType.DROPPED;
        if ("PLANNING".equals(status)) return SyncWatchType.PLANTOWATCH;
        if ("REPEATING".equals(status)) return SyncWatchType.REWATCHING;
        return SyncWatchType.NONE;
    }

    private static String anilistStatus(SyncWatchType status) {
        if (status == null || status == SyncWatchType.NONE) return "NONE";
        if (status == SyncWatchType.WATCHING) return "CURRENT";
        if (status == SyncWatchType.COMPLETED) return "COMPLETED";
        if (status == SyncWatchType.ONHOLD) return "PAUSED";
        if (status == SyncWatchType.DROPPED) return "DROPPED";
        if (status == SyncWatchType.PLANTOWATCH) return "PLANNING";
        if (status == SyncWatchType.REWATCHING) return "REPEATING";
        return null;
    }

    private static boolean saveListEntry(String token, int id, String status,
                                         Score score, Integer progress) throws IOException {
        if (progress != null && (progress < 0 || progress > MAX_PROGRESS)) return false;
        StringBuilder query = new StringBuilder("mutation ($id: Int!, $status: MediaListStatus!");
        if (score != null) query.append(", $scoreRaw: Int");
        if (progress != null) query.append(", $progress: Int");
        query.append(") { SaveMediaListEntry(mediaId: $id, status: $status");
        if (score != null) query.append(", scoreRaw: $scoreRaw");
        if (progress != null) query.append(", progress: $progress");
        query.append(") { id status progress score } }");

        JsonObject variables = new JsonObject();
        variables.addProperty("id", id);
        variables.addProperty("status", status);
        if (score != null) variables.addProperty("scoreRaw", score.toInt(100));
        if (progress != null) variables.addProperty("progress", progress);
        JsonObject data = postGraphQl(token, query.toString(), variables);
        JsonObject result = child(data, "SaveMediaListEntry");
        return result != null && integer(result, "id") != null;
    }

    private static boolean removeListEntry(String token, int userId, int mediaId) throws IOException {
        JsonObject variables = new JsonObject();
        variables.addProperty("userId", userId);
        variables.addProperty("mediaId", mediaId);
        JsonObject lookup = postGraphQl(token, """
                query ($userId: Int!, $mediaId: Int!) {
                  MediaList(userId: $userId, mediaId: $mediaId) { id }
                }
                """, variables);
        Integer listId = integer(child(lookup, "MediaList"), "id");
        if (listId == null) return true;

        JsonObject deleteVariables = new JsonObject();
        deleteVariables.addProperty("id", listId);
        JsonObject deleted = postGraphQl(token, """
                mutation ($id: Int!) {
                  DeleteMediaListEntry(id: $id) { deleted }
                }
                """, deleteVariables);
        return Boolean.TRUE.equals(bool(child(deleted, "DeleteMediaListEntry"), "deleted"));
    }

    private static Boolean readFavorite(String token, int mediaId) throws IOException {
        JsonObject variables = new JsonObject();
        variables.addProperty("id", mediaId);
        JsonObject data = postGraphQl(token, """
                query ($id: Int!) {
                  Media(id: $id, type: ANIME) { id isFavourite }
                }
                """, variables);
        JsonObject media = child(data, "Media");
        if (integer(media, "id") == null) return null;
        return bool(media, "isFavourite");
    }

    private static boolean toggleFavorite(String token, int mediaId) throws IOException {
        JsonObject variables = new JsonObject();
        variables.addProperty("animeId", mediaId);
        JsonObject data = postGraphQl(token, """
                mutation ($animeId: Int!) {
                  ToggleFavourite(animeId: $animeId) { anime { nodes { id } } }
                }
                """, variables);
        return child(data, "ToggleFavourite") != null;
    }

    private static LinkedHashMap<String, List<LibraryItem>> emptyLibraryGroups() {
        LinkedHashMap<String, List<LibraryItem>> groups = new LinkedHashMap<>();
        groups.put("Watching", new ArrayList<>());
        groups.put("Completed", new ArrayList<>());
        groups.put("Paused", new ArrayList<>());
        groups.put("Dropped", new ArrayList<>());
        groups.put("Planning", new ArrayList<>());
        groups.put("Repeating", new ArrayList<>());
        return groups;
    }

    private static String displayStatus(String status) {
        if ("CURRENT".equals(status)) return "Watching";
        if ("COMPLETED".equals(status)) return "Completed";
        if ("PAUSED".equals(status)) return "Paused";
        if ("DROPPED".equals(status)) return "Dropped";
        if ("PLANNING".equals(status)) return "Planning";
        if ("REPEATING".equals(status)) return "Repeating";
        return safeListName(status);
    }

    private static String safeListName(String value) {
        return value == null || value.trim().isEmpty() ? "Other" : value.trim();
    }

    private static Date releaseDate(int year) {
        GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        calendar.clear();
        calendar.set(year, Calendar.JANUARY, 1, 0, 0, 0);
        return calendar.getTime();
    }

    private static LibraryItem libraryItem(JsonObject entry) {
        JsonObject media = child(entry, "media");
        Integer id = integer(media, "id");
        if (id == null || id < 1) return null;
        JsonObject titleData = child(media, "title");
        String title = firstText(titleData, "english", "romaji", "userPreferred");
        if (title == null || title.isEmpty()) {
            List<String> synonyms = strings(media, "synonyms", 20, 256);
            title = synonyms.isEmpty() ? "Untitled" : synonyms.get(0);
        }
        Integer progress = boundedInt(entry, "progress", 0, MAX_PROGRESS);
        Integer episodes = boundedInt(media, "episodes", 0, MAX_PROGRESS);
        Integer score = boundedInt(entry, "score", 0, 100);
        Integer updated = boundedInt(entry, "updatedAt", 0, Integer.MAX_VALUE);
        Integer year = boundedInt(media, "seasonYear", 0, 9999);
        Date releaseDate = year == null || year < 1 ? null : releaseDate(year);
        return new LibraryItem(title, animeUrl(id), String.valueOf(id), progress, episodes,
                Score.from100(score), updated == null ? null : updated.longValue(), "AniList",
                TvType.Anime, coverUrl(child(media, "coverImage")), null, null, releaseDate,
                id, null, null, null);
    }

    private static JsonObject child(JsonObject parent, String name) {
        if (parent == null || name == null) return null;
        JsonElement child = parent.get(name);
        return child == null || child.isJsonNull() || !child.isJsonObject() ? null : child.getAsJsonObject();
    }

    private static JsonArray array(JsonObject parent, String name) {
        if (parent == null || name == null) return null;
        JsonElement child = parent.get(name);
        return child == null || child.isJsonNull() || !child.isJsonArray() ? null : child.getAsJsonArray();
    }

    private static JsonObject object(JsonElement element) {
        return element == null || element.isJsonNull() || !element.isJsonObject()
                ? null : element.getAsJsonObject();
    }

    private static String firstText(JsonObject object, String... names) {
        for (String name : names) {
            String value = text(object, name, 1_024);
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    private static String text(JsonObject object, String name, int maxChars) {
        if (object == null || name == null) return null;
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) return null;
        String text = value.getAsString();
        return text.length() <= maxChars ? text : null;
    }

    private static Integer integer(JsonObject object, String name) {
        if (object == null || name == null) return null;
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException malformed) {
            return null;
        }
    }

    private static Integer boundedInt(JsonObject object, String name, int min, int max) {
        Integer value = integer(object, name);
        return value == null || value < min || value > max ? null : value;
    }

    private static Boolean bool(JsonObject object, String name) {
        if (object == null || name == null) return null;
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) return null;
        return value.getAsBoolean();
    }

    private static List<String> strings(JsonObject object, String name, int limit, int maxChars) {
        JsonArray values = array(object, name);
        if (values == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (int index = 0; index < Math.min(values.size(), limit); index++) {
            JsonElement item = values.get(index);
            if (item == null || item.isJsonNull() || !item.isJsonPrimitive()
                    || !item.getAsJsonPrimitive().isString()) continue;
            String value = item.getAsString();
            if (value.length() <= maxChars && !value.trim().isEmpty()) result.add(value.trim());
        }
        return result;
    }

    private interface IoAction<T> {
        T run() throws Exception;
    }

    /** Executes IO work and lets failures reach the suspend caller as Result.failure. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Object onIoPropagating(IoAction<T> action, Continuation<? super T> continuation) {
        Function2 block = new Function2() {
            @Override public Object invoke(Object scope, Object workerContinuation) {
                try {
                    return action.run();
                } catch (Exception failure) {
                    return AniListApi.<RuntimeException, Object>sneakyThrow(failure);
                }
            }
        };
        return BuildersKt.withContext(Dispatchers.getIO(), block, continuation);
    }

    /** Re-throws checked provider failures through the Kotlin suspend bridge without changing type. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    /** Executes synchronous OkHttp and parsing work in Dispatchers.IO, preserving the suspend ABI. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Object onIo(IoAction<T> action, T fallback, Continuation<? super T> continuation) {
        Function2 block = new Function2() {
            @Override public Object invoke(Object scope, Object workerContinuation) {
                try {
                    return action.run();
                } catch (Exception failure) {
                    return fallback;
                }
            }
        };
        return BuildersKt.withContext(Dispatchers.getIO(), block, continuation);
    }

    public static class CoverImage {
        private final String medium;
        private final String large;
        private final String extraLarge;

        private CoverImage() { this(null, null, null); }
        public CoverImage(String medium, String large, String extraLarge) {
            this.medium = medium;
            this.large = large;
            this.extraLarge = extraLarge;
        }
        public String getExtraLarge() { return extraLarge; }
        public String getLarge() { return large; }
        public String getMedium() { return medium; }
        @Override public int hashCode() {
            int result = medium == null ? 0 : medium.hashCode();
            result = 31 * result + (large == null ? 0 : large.hashCode());
            result = 31 * result + (extraLarge == null ? 0 : extraLarge.hashCode());
            return result;
        }
    }

    public static class LikePageInfo {
        private final Integer total;
        private final Integer currentPage;
        private final Integer lastPage;
        private final Integer perPage;
        private final Boolean hasNextPage;

        private LikePageInfo() { this(null, null, null, null, null); }
        public LikePageInfo(Integer total, Integer currentPage, Integer lastPage,
                            Integer perPage, Boolean hasNextPage) {
            this.total = total;
            this.currentPage = currentPage;
            this.lastPage = lastPage;
            this.perPage = perPage;
            this.hasNextPage = hasNextPage;
        }
        public Boolean getHasNextPage() { return hasNextPage; }
        @Override public int hashCode() {
            int result = total == null ? 0 : total.hashCode();
            result = 31 * result + (currentPage == null ? 0 : currentPage.hashCode());
            result = 31 * result + (lastPage == null ? 0 : lastPage.hashCode());
            result = 31 * result + (perPage == null ? 0 : perPage.hashCode());
            result = 31 * result + (hasNextPage == null ? 0 : hasNextPage.hashCode());
            return result;
        }
    }

    public static class MediaCoverImage {
        private final String extraLarge;
        private final String large;
        private final String medium;
        private final String color;

        private MediaCoverImage() { this(null, null, null, null); }
        public MediaCoverImage(String extraLarge, String large, String medium, String color) {
            this.extraLarge = extraLarge;
            this.large = large;
            this.medium = medium;
            this.color = color;
        }
        public String getLarge() { return large; }
    }

    public static class MediaTitle {
        private final String romaji;
        private final String english;
        private final String nativeTitle;
        private final String userPreferred;

        private MediaTitle() { this(null, null, null, null); }
        public MediaTitle(String romaji, String english, String nativeTitle, String userPreferred) {
            this.romaji = romaji;
            this.english = english;
            this.nativeTitle = nativeTitle;
            this.userPreferred = userPreferred;
        }
        public String getEnglish() { return english; }
        public String getRomaji() { return romaji; }
    }

    public static class Recommendation {
        private final RecommendedMedia mediaRecommendation;

        private Recommendation() { this(null); }
        public Recommendation(RecommendedMedia mediaRecommendation) {
            this.mediaRecommendation = mediaRecommendation;
        }
        public RecommendedMedia getMediaRecommendation() { return mediaRecommendation; }
    }

    public static class RecommendationConnection {
        private final List edges;
        private final List nodes;

        public RecommendationConnection() {
            this(Collections.emptyList(), Collections.emptyList());
        }
        public RecommendationConnection(List edges, List nodes) {
            this.edges = java.util.Objects.requireNonNull(edges, "edges");
            this.nodes = java.util.Objects.requireNonNull(nodes, "nodes");
        }
        public List getEdges() { return edges; }
        @Override public int hashCode() {
            int result = edges.hashCode();
            result = 31 * result + nodes.hashCode();
            return result;
        }
    }

    public static class RecommendationEdge {
        private final Recommendation node;

        private RecommendationEdge() { this(null); }
        public RecommendationEdge(Recommendation node) {
            this.node = java.util.Objects.requireNonNull(node, "node");
        }
        public Recommendation getNode() { return node; }
    }

    public static class RecommendedMedia {
        private final Integer id;
        private final MediaTitle title;
        private final MediaCoverImage coverImage;

        private RecommendedMedia() { this(null, null, null); }
        public RecommendedMedia(Integer id, MediaTitle title, MediaCoverImage coverImage) {
            this.id = id;
            this.title = title;
            this.coverImage = coverImage;
        }
        public MediaCoverImage getCoverImage() { return coverImage; }
        public Integer getId() { return id; }
        public MediaTitle getTitle() { return title; }
    }

    public static class SeasonNextAiringEpisode {
        private final Integer episode;
        private final Integer timeUntilAiring;

        private SeasonNextAiringEpisode() { this(null, null); }
        public SeasonNextAiringEpisode(Integer episode, Integer timeUntilAiring) {
            this.episode = episode;
            this.timeUntilAiring = timeUntilAiring;
        }
        public Integer getEpisode() { return episode; }
        @Override public int hashCode() {
            int result = episode == null ? 0 : episode.hashCode();
            return 31 * result + (timeUntilAiring == null ? 0 : timeUntilAiring.hashCode());
        }
    }

    public static class Title {
        private final String english;
        private final String romaji;

        private Title() { this(null, null); }
        public Title(String english, String romaji) {
            this.english = english;
            this.romaji = romaji;
        }
        public String getEnglish() { return english; }
        public String getRomaji() { return romaji; }
        @Override public int hashCode() {
            int result = english == null ? 0 : english.hashCode();
            return 31 * result + (romaji == null ? 0 : romaji.hashCode());
        }
    }
}
