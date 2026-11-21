package com.auras.orbit.jellyfin;

import com.auras.orbit.jellyfin.JellyfinModels.Connection;
import com.auras.orbit.jellyfin.JellyfinModels.LibraryView;
import com.auras.orbit.jellyfin.JellyfinModels.MediaItem;
import com.auras.orbit.jellyfin.JellyfinModels.MediaPage;
import com.auras.orbit.jellyfin.JellyfinModels.MediaStream;
import com.auras.orbit.jellyfin.JellyfinModels.PlaybackEvent;
import com.auras.orbit.jellyfin.JellyfinModels.PlaybackInfo;
import com.auras.orbit.jellyfin.JellyfinModels.PlaybackRequest;
import com.auras.orbit.jellyfin.JellyfinModels.PlaybackSource;
import com.auras.orbit.jellyfin.JellyfinModels.User;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * HTTP integration for one explicitly configured Jellyfin server. Every network operation is
 * submitted to this client's worker pool; callers must observe Future results away from the UI
 * thread. No server discovery, redirects, external authentication, or fake media is performed.
 *
 * Playback support is the Jellyfin source adapter only: the returned stream request and session
 * reporting values are intended for Orbit's shared player, which is implemented separately.
 */
public final class JellyfinClient implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final String CLIENT_NAME = "Auras Orbit";
    private static final String CLIENT_VERSION = "0.1.0";

    private final BoundedFutureExecutor workers;

    public JellyfinClient() {
        workers = new BoundedFutureExecutor("orbit-jellyfin-io", 3, 64);
    }

    /** Authenticates only to the user-entered HTTP(S) server URL. The password is not retained. */
    public Future<Connection> authenticate(String serverUrl, String username, String password) {
        return submit(() -> authenticateBlocking(serverUrl, username, password));
    }

    /** Confirms that a saved token still identifies a valid user on its configured server. */
    public Future<User> validate(Connection connection) {
        return submit(() -> {
            requireConnection(connection);
            JSONObject value = requestJson(connection, "GET", "Users/Me", null);
            return user(value);
        });
    }

    public Future<List<LibraryView>> getLibraryViews(Connection connection) {
        return submit(() -> getLibraryViewsBlocking(connection));
    }

    public Future<MediaPage> getContinueWatching(Connection connection, int startIndex, int limit) {
        return submit(() -> {
            requireConnection(connection);
            int start = Math.max(0, startIndex);
            int count = clampLimit(limit);
            LinkedHashMap<String, String> query = new LinkedHashMap<>();
            query.put("userId", connection.userId);
            query.put("StartIndex", String.valueOf(start));
            query.put("Limit", String.valueOf(count));
            query.put("EnableUserData", "true");
            query.put("EnableImages", "true");
            query.put("EnableTotalRecordCount", "true");
            query.put("ExcludeActiveSessions", "false");
            query.put("MediaTypes", "Video");
            query.put("Fields", "Overview,ParentId,ProviderIds,MediaStreams,DateCreated");
            JSONObject response = requestJsonWithFallback(connection, "GET",
                    withQuery("UserItems/Resume", query),
                    withQuery("Users/" + encodePathSegment(connection.userId) + "/Items/Resume", query), null);
            return mediaPage(response, start);
        });
    }

    public Future<List<MediaItem>> getRecentlyAdded(Connection connection, String libraryId, int limit) {
        return submit(() -> {
            requireConnection(connection);
            if (!isBlank(libraryId)) requireId(libraryId, "library id");
            LinkedHashMap<String, String> query = new LinkedHashMap<>();
            query.put("userId", connection.userId);
            if (!isBlank(libraryId)) query.put("ParentId", libraryId.trim());
            query.put("Limit", String.valueOf(clampLimit(limit)));
            query.put("EnableUserData", "true");
            query.put("EnableImages", "true");
            query.put("GroupItems", "false");
            query.put("IncludeItemTypes", "Movie,Episode,Series");
            query.put("Fields", "Overview,ParentId,ProviderIds,MediaStreams,DateCreated");
            JSONArray response = requestArrayWithFallback(connection, "GET",
                    withQuery("Items/Latest", query),
                    withQuery("Users/" + encodePathSegment(connection.userId) + "/Items/Latest", query), null);
            JellyfinResponseBudgets.requireCount(response.length(),
                    JellyfinResponseBudgets.MAX_RECENT_ITEMS, "recent items");
            ArrayList<MediaItem> items = new ArrayList<>();
            for (int index = 0; index < response.length(); index++) {
                JSONObject item = response.optJSONObject(index);
                if (item != null) items.add(mediaItem(item));
            }
            return Collections.unmodifiableList(items);
        });
    }

    /**
     * Lists or searches within a server library. Pass an empty itemTypes list for all supported
     * server types; a non-empty list becomes Jellyfin's IncludeItemTypes filter.
     */
    public Future<MediaPage> getItems(Connection connection, String parentId, int startIndex,
                                      int limit, String searchTerm, List<String> itemTypes) {
        return submit(() -> {
            requireConnection(connection);
            if (!isBlank(parentId)) requireId(parentId, "parent id");
            if (!isBlank(searchTerm) && searchTerm.trim().length() > 512) {
                throw new IOException("The Jellyfin search is too long.");
            }
            int start = Math.max(0, startIndex);
            LinkedHashMap<String, String> query = new LinkedHashMap<>();
            query.put("userId", connection.userId);
            if (!isBlank(parentId)) query.put("ParentId", parentId.trim());
            query.put("StartIndex", String.valueOf(start));
            query.put("Limit", String.valueOf(clampLimit(limit)));
            query.put("EnableUserData", "true");
            query.put("EnableImages", "true");
            query.put("EnableTotalRecordCount", "true");
            query.put("SortBy", "SortName");
            query.put("SortOrder", "Ascending");
            query.put("Fields", "Overview,ParentId,ProviderIds,MediaStreams,DateCreated");
            if (!isBlank(searchTerm)) query.put("SearchTerm", searchTerm.trim());
            if (itemTypes != null && !itemTypes.isEmpty()) {
                ArrayList<String> safeTypes = new ArrayList<>();
                for (String type : itemTypes) {
                    if (safeTypes.size() == 32) break;
                    if (!isBlank(type) && type.length() <= 32 && type.matches("[A-Za-z]+")
                            && !safeTypes.contains(type)) safeTypes.add(type);
                }
                if (!safeTypes.isEmpty()) query.put("IncludeItemTypes", join(safeTypes, ","));
            }
            JSONObject response = requestJsonWithFallback(connection, "GET",
                    withQuery("Items", query),
                    withQuery("Users/" + encodePathSegment(connection.userId) + "/Items", query), null);
            return mediaPage(response, start);
        });
    }

    /** Requests server-selected playback sources; no media is opened by this method. */
    public Future<PlaybackInfo> getPlaybackInfo(Connection connection, String itemId,
                                                long startPositionTicks) {
        return submit(() -> {
            requireConnection(connection);
            requireId(itemId, "item id");
            JSONObject body = new JSONObject();
            try {
                body.put("UserId", connection.userId);
                body.put("EnableDirectPlay", true);
                body.put("EnableDirectStream", true);
                body.put("EnableTranscoding", true);
                if (startPositionTicks > 0L) body.put("StartTimeTicks", startPositionTicks);
            } catch (JSONException error) {
                throw new IOException("Could not prepare Jellyfin playback request.", error);
            }
            String path = "Items/" + encodePathSegment(itemId) + "/PlaybackInfo";
            JSONObject response = requestJson(connection, "POST",
                    withQuery(path, Collections.singletonMap("userId", connection.userId)), body);
            return playbackInfo(response, Math.max(0L, startPositionTicks));
        });
    }

    /**
     * Converts one server playback source into a request for Orbit's player. Authorization is
     * attached in memory as a header and must not be logged or persisted. Cross-origin server URLs
     * are rejected so the Jellyfin token cannot be sent to an unrelated host.
     */
    public PlaybackRequest createPlaybackRequest(Connection connection, String itemId,
                                                 PlaybackInfo info, PlaybackSource source) throws IOException {
        requireConnection(connection);
        requireId(itemId, "item id");
        if (source == null) throw new IOException("A Jellyfin media source must be selected.");

        URI uri = null;
        if (!isBlank(source.directStreamUrl)
                && (source.supportsDirectPlay || source.supportsDirectStream)) {
            uri = resolveSameServerUrl(connection.serverUrl, source.directStreamUrl);
        } else if (!isBlank(source.transcodingUrl)) {
            uri = resolveSameServerUrl(connection.serverUrl, source.transcodingUrl);
        } else if (!isBlank(source.directStreamUrl)) {
            uri = resolveSameServerUrl(connection.serverUrl, source.directStreamUrl);
        }
        if (uri == null) {
            if (!(source.supportsDirectPlay || source.supportsDirectStream) || isBlank(source.id)) {
                throw new IOException("Jellyfin did not provide a direct or transcoded stream URL for this source.");
            }
            LinkedHashMap<String, String> query = new LinkedHashMap<>();
            query.put("Static", "true");
            if (!isBlank(source.id)) query.put("MediaSourceId", source.id);
            if (info != null && !isBlank(info.playSessionId)) query.put("PlaySessionId", info.playSessionId);
            if (info != null && info.startPositionTicks > 0L) {
                query.put("StartTimeTicks", String.valueOf(info.startPositionTicks));
            }
            uri = serverUri(connection.serverUrl,
                    "Videos/" + encodePathSegment(itemId) + "/stream?" + encodeQuery(query));
        }
        if (info != null && info.startPositionTicks > 0L) {
            uri = addQueryIfMissing(uri, "StartTimeTicks", String.valueOf(info.startPositionTicks));
        }
        return new PlaybackRequest(uri, Collections.singletonMap("Authorization", authorization(connection)));
    }

    /** Returns a short-lived authenticated image request; null means the server has no primary art. */
    public PlaybackRequest createPrimaryImageRequest(Connection connection, MediaItem item,
                                                     int maxWidth) throws IOException {
        requireConnection(connection);
        if (item == null || isBlank(item.primaryImageTag)) return null;
        requireId(item.id, "item id");
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("tag", item.primaryImageTag);
        query.put("maxWidth", String.valueOf(Math.max(64, Math.min(2048, maxWidth))));
        URI uri = serverUri(connection.serverUrl,
                "Items/" + encodePathSegment(item.id) + "/Images/Primary?" + encodeQuery(query));
        return new PlaybackRequest(uri, Collections.singletonMap("Authorization", authorization(connection)));
    }

    /** Downloads primary artwork on a worker thread, returning null when the server has no artwork. */
    public Future<byte[]> getPrimaryImage(Connection connection, MediaItem item, int maxWidth) {
        return submit(() -> {
            requireConnection(connection);
            if (item == null || isBlank(item.primaryImageTag)) return null;
            requireId(item.id, "item id");
            LinkedHashMap<String, String> query = new LinkedHashMap<>();
            query.put("tag", item.primaryImageTag);
            query.put("maxWidth", String.valueOf(Math.max(64, Math.min(2048, maxWidth))));
            String path = "Items/" + encodePathSegment(item.id) + "/Images/Primary?" + encodeQuery(query);
            return request(connection, "GET", path, null);
        });
    }

    public Future<Void> reportPlaybackStarted(Connection connection, PlaybackEvent event) {
        return reportPlayback(connection, event, "Sessions/Playing", false);
    }

    public Future<Void> reportPlaybackProgress(Connection connection, PlaybackEvent event) {
        return reportPlayback(connection, event, "Sessions/Playing/Progress", false);
    }

    public Future<Void> reportPlaybackStopped(Connection connection, PlaybackEvent event, boolean failed) {
        return reportPlayback(connection, event, "Sessions/Playing/Stopped", failed);
    }

    /** Clears worker threads when the owning application service is shut down. */
    @Override public void close() { workers.shutdownNow(); }

    public static String normalizeServerUrl(String raw) throws IOException {
        if (isBlank(raw)) throw new IOException("Enter a Jellyfin server address.");
        String trimmed = raw.trim();
        if (trimmed.length() > 2048 || containsWhitespace(trimmed)) {
            throw new IOException("The Jellyfin server address is invalid.");
        }
        try {
            URI uri = new URI(trimmed).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || isBlank(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IOException("Use a server URL with HTTP or HTTPS and no embedded credentials or query.");
            }
            int port = uri.getPort();
            if (port == 0 || port > 65535) throw new IOException("The Jellyfin server port is invalid.");
            String path = uri.getRawPath();
            if (isBlank(path)) path = "/";
            if (!path.endsWith("/")) path += "/";
            StringBuilder normalized = new StringBuilder(scheme).append("://").append(uri.getRawAuthority()).append(path);
            URI result = new URI(normalized.toString()).normalize();
            if (result.getRawQuery() != null || result.getRawFragment() != null) {
                throw new IOException("The Jellyfin server URL is invalid.");
            }
            return result.toASCIIString();
        } catch (URISyntaxException error) {
            throw new IOException("The Jellyfin server address is invalid.", error);
        }
    }

    private Connection authenticateBlocking(String rawServerUrl, String username, String password) throws IOException {
        String serverUrl = normalizeServerUrl(rawServerUrl);
        if (isBlank(username) || username.trim().length() > 256) {
            throw new IOException("Enter a valid Jellyfin username.");
        }
        if (password == null || password.length() > 4096) {
            throw new IOException("The Jellyfin password is invalid.");
        }
        String deviceId = UUID.randomUUID().toString();
        JSONObject body = new JSONObject();
        try {
            body.put("Username", username.trim());
            body.put("Pw", password);
        } catch (JSONException error) {
            throw new IOException("Could not prepare Jellyfin sign-in.", error);
        }
        JSONObject response = requestJson(serverUrl, deviceId, null, "POST", "Users/AuthenticateByName", body);
        JSONObject userValue = response.optJSONObject("User");
        if (userValue == null) throw new IOException("Jellyfin sign-in did not return a user.");
        String userId = userValue.optString("Id", "").trim();
        String accessToken = response.optString("AccessToken", "").trim();
        if (isBlank(userId) || isBlank(accessToken)) {
            throw new IOException("Jellyfin sign-in did not return an access token and user id.");
        }
        String serverName = "";
        try {
            JSONObject publicInfo = requestJson(serverUrl, deviceId, null,
                    "GET", "System/Info/Public", null);
            serverName = publicInfo.optString("ServerName", "").trim();
        } catch (IOException ignored) {
            // Keep the authenticated connection usable when a server omits optional public info.
        }
        return new Connection(serverUrl, serverName, userId,
                userValue.optString("Name", username.trim()),
                response.optString("ServerId", ""), deviceId, accessToken);
    }

    private List<LibraryView> getLibraryViewsBlocking(Connection connection) throws IOException {
        requireConnection(connection);
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("userId", connection.userId);
        query.put("includeHidden", "false");
        query.put("Fields", "ChildCount");
        JSONObject response = requestJsonWithFallback(connection, "GET",
                withQuery("UserViews", query),
                withQuery("Users/" + encodePathSegment(connection.userId) + "/Views", query), null);
        JSONArray values = response.optJSONArray("Items");
        if (values == null) return Collections.emptyList();
        JellyfinResponseBudgets.requireCount(values.length(),
                JellyfinResponseBudgets.MAX_LIBRARIES, "libraries");
        ArrayList<LibraryView> views = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) continue;
            String id = value.optString("Id", "").trim();
            if (isBlank(id)) continue;
            JSONObject imageTags = value.optJSONObject("ImageTags");
            long childCount = value.has("ChildCount") && !value.isNull("ChildCount")
                    ? value.optLong("ChildCount", -1L) : -1L;
            views.add(new LibraryView(id, value.optString("Name", "Library"),
                    value.optString("CollectionType", ""),
                    imageTags == null ? "" : imageTags.optString("Primary", ""),
                    childCount));
        }
        return Collections.unmodifiableList(views);
    }

    private Future<Void> reportPlayback(Connection connection, PlaybackEvent event,
                                        String path, boolean failed) {
        return submit(() -> {
            requireConnection(connection);
            if (event == null) throw new IOException("Playback session data is missing.");
            requireId(event.itemId, "item id");
            JSONObject body = new JSONObject();
            try {
                body.put("ItemId", event.itemId);
                body.put("PositionTicks", event.positionTicks);
                body.put("CanSeek", event.canSeek);
                body.put("IsPaused", event.paused);
                body.put("IsMuted", event.muted);
                body.put("PlayMethod", normalizePlayMethod(event.playMethod));
                body.put("RepeatMode", "RepeatNone");
                body.put("PlaybackOrder", "Default");
                if (path.endsWith("/Stopped")) body.put("Failed", failed);
                if (!isBlank(event.mediaSourceId)) body.put("MediaSourceId", event.mediaSourceId);
                if (!isBlank(event.playSessionId)) body.put("PlaySessionId", event.playSessionId);
                if (event.audioStreamIndex != null) body.put("AudioStreamIndex", event.audioStreamIndex);
                if (event.subtitleStreamIndex != null) body.put("SubtitleStreamIndex", event.subtitleStreamIndex);
            } catch (JSONException error) {
                throw new IOException("Could not prepare Jellyfin playback progress.", error);
            }
            request(connection, "POST", path, body);
            return null;
        });
    }

    private JSONObject requestJson(Connection connection, String method, String path,
                                   JSONObject body) throws IOException {
        requireConnection(connection);
        return requestJson(connection.serverUrl, connection.deviceId, connection.accessToken,
                method, path, body);
    }

    private JSONObject requestJson(String serverUrl, String deviceId, String token, String method,
                                   String path, JSONObject body) throws IOException {
        byte[] bytes = request(serverUrl, deviceId, token, method, path, body);
        if (bytes.length == 0) return new JSONObject();
        try { return new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }
        catch (JSONException error) { throw new IOException("Jellyfin returned invalid JSON.", error); }
    }

    private JSONArray requestArray(Connection connection, String method, String path,
                                   JSONObject body) throws IOException {
        requireConnection(connection);
        byte[] bytes = request(connection.serverUrl, connection.deviceId, connection.accessToken,
                method, path, body);
        try { return new JSONArray(new String(bytes, StandardCharsets.UTF_8)); }
        catch (JSONException error) { throw new IOException("Jellyfin returned an invalid item list.", error); }
    }

    private JSONObject requestJsonWithFallback(Connection connection, String method,
                                               String currentPath, String legacyPath,
                                               JSONObject body) throws IOException {
        try {
            return requestJson(connection, method, currentPath, body);
        } catch (JellyfinException error) {
            if (error.httpStatus != HttpURLConnection.HTTP_NOT_FOUND) throw error;
            return requestJson(connection, method, legacyPath, body);
        }
    }

    private JSONArray requestArrayWithFallback(Connection connection, String method,
                                               String currentPath, String legacyPath,
                                               JSONObject body) throws IOException {
        try {
            return requestArray(connection, method, currentPath, body);
        } catch (JellyfinException error) {
            if (error.httpStatus != HttpURLConnection.HTTP_NOT_FOUND) throw error;
            return requestArray(connection, method, legacyPath, body);
        }
    }

    private byte[] request(Connection connection, String method, String path,
                           JSONObject body) throws IOException {
        requireConnection(connection);
        return request(connection.serverUrl, connection.deviceId, connection.accessToken,
                method, path, body);
    }

    private byte[] request(String serverUrl, String deviceId, String token, String method,
                           String path, JSONObject body) throws IOException {
        URI uri = serverUri(serverUrl, path);
        HttpURLConnection connection = null;
        try {
            URL url = uri.toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", authorization(deviceId, token));
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(encoded.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(encoded); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new JellyfinException(status, status >= 300 && status < 400
                        ? "Jellyfin redirected this request; redirects are not followed."
                        : "Jellyfin returned HTTP " + status + ".");
            }
            InputStream input = connection.getInputStream();
            return readBounded(input);
        } catch (JellyfinException error) {
            throw error;
        } catch (IOException error) {
            throw new IOException("Could not reach the configured Jellyfin server.", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (count == 0) throw new IOException("Input stream made no progress.");
                total += count;
                if (total > MAX_RESPONSE_BYTES) throw new IOException("Jellyfin response exceeded the size limit.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static MediaPage mediaPage(JSONObject value, int requestedStart) throws IOException {
        JSONArray values = value.optJSONArray("Items");
        if (values != null) JellyfinResponseBudgets.requireCount(values.length(),
                JellyfinPageAccumulator.MAX_ITEMS_PER_RESPONSE, "media items");
        ArrayList<MediaItem> items = new ArrayList<>();
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                JSONObject item = values.optJSONObject(index);
                if (item != null) items.add(mediaItem(item));
            }
        }
        int returnedStart = value.optInt("StartIndex", requestedStart);
        int start = returnedStart < 0 ? Math.max(0, requestedStart) : returnedStart;
        int total = JellyfinPageBounds.effectiveTotal(start, items.size(),
                value.optInt("TotalRecordCount", -1));
        return new MediaPage(items, start, total);
    }

    private static MediaItem mediaItem(JSONObject value) {
        JSONObject imageTags = value.optJSONObject("ImageTags");
        JSONObject userData = value.optJSONObject("UserData");
        return new MediaItem(value.optString("Id", ""), value.optString("Name", ""),
                value.optString("Type", ""), value.optString("Overview", ""),
                value.optString("ParentId", ""),
                imageTags == null ? "" : imageTags.optString("Primary", ""),
                value.optLong("RunTimeTicks", 0L),
                userData == null ? 0L : userData.optLong("PlaybackPositionTicks", 0L),
                userData != null && userData.optBoolean("Played", false),
                value.optInt("ProductionYear", 0),
                value.optInt("ParentIndexNumber", 0),
                value.optInt("IndexNumber", 0));
    }

    private static PlaybackInfo playbackInfo(JSONObject value, long startPositionTicks) throws IOException {
        JSONArray values = value.optJSONArray("MediaSources");
        if (values != null) JellyfinResponseBudgets.requireCount(values.length(),
                JellyfinResponseBudgets.MAX_PLAYBACK_SOURCES, "playback sources");
        ArrayList<PlaybackSource> sources = new ArrayList<>();
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                JSONObject source = values.optJSONObject(index);
                if (source == null) continue;
                ArrayList<MediaStream> streams = new ArrayList<>();
                JSONArray streamValues = source.optJSONArray("MediaStreams");
                if (streamValues != null) JellyfinResponseBudgets.requireCount(streamValues.length(),
                        JellyfinResponseBudgets.MAX_MEDIA_STREAMS_PER_SOURCE, "media streams");
                if (streamValues != null) {
                    for (int streamIndex = 0; streamIndex < streamValues.length(); streamIndex++) {
                        JSONObject stream = streamValues.optJSONObject(streamIndex);
                        if (stream == null) continue;
                        streams.add(new MediaStream(stream.optInt("Index", -1),
                                stream.optString("Type", ""), stream.optString("Codec", ""),
                                stream.optString("Language", ""), stream.optString("DisplayTitle", ""),
                                stream.optBoolean("IsDefault", false), stream.optBoolean("IsExternal", false)));
                    }
                }
                sources.add(new PlaybackSource(source.optString("Id", ""),
                        source.optString("Name", ""), source.optString("Container", ""),
                        source.optString("Protocol", ""), source.optBoolean("IsRemote", false),
                        source.optBoolean("SupportsDirectPlay", false),
                        source.optBoolean("SupportsDirectStream", false),
                        source.optBoolean("SupportsTranscoding", false),
                        source.optString("DirectStreamUrl", ""), source.optString("TranscodingUrl", ""), streams));
            }
        }
        return new PlaybackInfo(value.optString("PlaySessionId", ""),
                value.optString("ErrorCode", ""), startPositionTicks, sources);
    }

    private static User user(JSONObject value) throws IOException {
        String id = value.optString("Id", "").trim();
        if (isBlank(id)) throw new IOException("Jellyfin did not return the current user identity.");
        return new User(id, value.optString("Name", ""));
    }

    private static URI resolveSameServerUrl(String serverUrl, String candidate) throws IOException {
        URI base = URI.create(normalizeServerUrl(serverUrl));
        try {
            URI supplied = new URI(candidate.trim());
            URI resolved = supplied.isAbsolute() ? supplied.normalize() : base.resolve(supplied).normalize();
            if (!sameOrigin(base, resolved) || resolved.getRawUserInfo() != null || resolved.getRawFragment() != null) {
                throw new IOException("Jellyfin returned a media URL on a different server; it was rejected.");
            }
            String basePath = base.getRawPath();
            String resolvedPath = resolved.getRawPath();
            if (basePath != null && !"/".equals(basePath) && resolvedPath != null
                    && resolvedPath.startsWith("/") && !resolvedPath.startsWith(basePath)) {
                String prefixedPath = basePath.substring(0, basePath.length() - 1) + resolvedPath;
                StringBuilder absolute = new StringBuilder(resolved.getScheme()).append("://")
                        .append(resolved.getRawAuthority()).append(prefixedPath);
                if (resolved.getRawQuery() != null) absolute.append('?').append(resolved.getRawQuery());
                resolved = new URI(absolute.toString());
            }
            return removeSensitiveQuery(resolved);
        } catch (URISyntaxException error) {
            throw new IOException("Jellyfin returned an invalid playback URL.", error);
        }
    }

    private static URI removeSensitiveQuery(URI uri) throws IOException {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) return uri;
        ArrayList<String> safeParts = new ArrayList<>();
        for (String part : query.split("&")) {
            String key = part;
            int equals = key.indexOf('=');
            if (equals >= 0) key = key.substring(0, equals);
            String normalized;
            try { normalized = URLDecoder.decode(key, "UTF-8").toLowerCase(java.util.Locale.ROOT); }
            catch (java.io.UnsupportedEncodingException error) { throw new IOException("UTF-8 is unavailable.", error); }
            if (normalized.contains("token") || normalized.contains("api_key")
                    || normalized.contains("apikey") || normalized.contains("accesskey")) continue;
            safeParts.add(part);
        }
        StringBuilder value = new StringBuilder(uri.getScheme()).append("://")
                .append(uri.getRawAuthority()).append(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (!safeParts.isEmpty()) value.append('?').append(join(safeParts, "&"));
        try { return new URI(value.toString()); }
        catch (URISyntaxException error) { throw new IOException("Jellyfin returned an invalid playback URL.", error); }
    }

    private static URI addQueryIfMissing(URI uri, String key, String value) throws IOException {
        String query = uri.getRawQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                String existingKey = part;
                int equals = existingKey.indexOf('=');
                if (equals >= 0) existingKey = existingKey.substring(0, equals);
                try {
                    if (key.equalsIgnoreCase(URLDecoder.decode(existingKey, "UTF-8"))) return uri;
                } catch (java.io.UnsupportedEncodingException error) {
                    throw new IOException("UTF-8 is unavailable.", error);
                }
            }
        }
        String addition = encode(key) + "=" + encode(value);
        StringBuilder result = new StringBuilder(uri.getScheme()).append("://")
                .append(uri.getRawAuthority()).append(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (query == null || query.isEmpty()) result.append('?').append(addition);
        else result.append('?').append(query).append('&').append(addition);
        try { return new URI(result.toString()); }
        catch (URISyntaxException error) { throw new IOException("Could not add Jellyfin playback position.", error); }
    }

    private static boolean sameOrigin(URI left, URI right) {
        if (left == null || right == null || right.getHost() == null) return false;
        String leftScheme = left.getScheme() == null ? "" : left.getScheme().toLowerCase(java.util.Locale.ROOT);
        String rightScheme = right.getScheme() == null ? "" : right.getScheme().toLowerCase(java.util.Locale.ROOT);
        return leftScheme.equals(rightScheme)
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static URI serverUri(String rawServerUrl, String relativePath) throws IOException {
        String base = normalizeServerUrl(rawServerUrl);
        try {
            URI baseUri = new URI(base);
            URI resolved = baseUri.resolve(relativePath);
            if (!sameOrigin(baseUri, resolved)) throw new IOException("Jellyfin request left the configured server.");
            return resolved;
        } catch (URISyntaxException error) {
            throw new IOException("Could not build a Jellyfin request URL.", error);
        }
    }

    private static String withQuery(String path, Map<String, String> query) throws IOException {
        if (query == null || query.isEmpty()) return path;
        return path + "?" + encodeQuery(query);
    }

    private static String encodeQuery(Map<String, String> query) throws IOException {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (result.length() > 0) result.append('&');
            result.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return result.toString();
    }

    private static String encodePathSegment(String value) throws IOException {
        requireId(value, "identifier");
        return encode(value.trim());
    }

    private static String encode(String value) throws IOException {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException error) { throw new IOException("UTF-8 is unavailable.", error); }
    }

    private static String authorization(Connection connection) {
        return authorization(connection.deviceId, connection.accessToken);
    }

    private static String authorization(String deviceId, String token) {
        StringBuilder value = new StringBuilder("MediaBrowser Client=\"").append(CLIENT_NAME)
                .append("\", Device=\"Android\", DeviceId=\"").append(quoteHeader(deviceId))
                .append("\", Version=\"").append(CLIENT_VERSION).append('\"');
        if (!isBlank(token)) value.append(", Token=\"").append(quoteHeader(token)).append('\"');
        return value.toString();
    }

    private static String quoteHeader(String value) {
        if (value == null) return "";
        return value.replace("\\", "").replace("\"", "").replace("\r", "").replace("\n", "");
    }

    private <T> Future<T> submit(Callable<T> work) {
        return workers.submit(work);
    }

    private static int clampLimit(int limit) { return Math.max(1, Math.min(100, limit)); }

    private static void requireConnection(Connection connection) throws IOException {
        if (connection == null || isBlank(connection.serverUrl) || isBlank(connection.userId)
                || isBlank(connection.deviceId) || isBlank(connection.accessToken)) {
            throw new IOException("Connect to a Jellyfin server before requesting media.");
        }
        normalizeServerUrl(connection.serverUrl);
    }

    private static void requireId(String value, String label) throws IOException {
        if (isBlank(value) || value.length() > 512 || containsControl(value)
                || value.contains("/") || value.contains("\\") || value.contains("?")
                || value.contains("#") || "..".equals(value.trim())) {
            throw new IOException("The Jellyfin " + label + " is invalid.");
        }
    }

    private static boolean containsWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) if (Character.isWhitespace(value.charAt(index))) return true;
        return false;
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return true;
        return false;
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }

    private static String normalizePlayMethod(String value) {
        if ("DirectStream".equals(value) || "Transcode".equals(value)) return value;
        return "DirectPlay";
    }

    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }

    public static final class JellyfinException extends IOException {
        public final int httpStatus;

        JellyfinException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }
}
