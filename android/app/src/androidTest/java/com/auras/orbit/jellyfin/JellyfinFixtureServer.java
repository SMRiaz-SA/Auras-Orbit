package com.auras.orbit.jellyfin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

/** Test-only, loopback HTTPS Jellyfin-shaped server serving synthetic JSON, poster, and MP4 data. */
final class JellyfinFixtureServer implements Closeable {
    static final String USERNAME = "orbit-fixture-user";
    static final String PASSWORD = "synthetic-fixture-pässword";
    static final String ACCESS_TOKEN = "synthetic-fixture-token";
    static final String USER_ID = "fixture-user-id";
    static final String USER_NAME = "Alex";
    static final String SERVER_NAME = "Living Room Server";
    static final String MOVIES_LIBRARY_ID = "fixture-movies-library";
    static final String LIBRARY_ID = "fixture-tv-library-id";
    static final String ITEM_ID = "fixture-quiet-between";
    static final String ITEM_ID_AFTER_TIDE = "fixture-after-the-tide";
    static final String ITEM_ID_WINTER_LINE = "fixture-winter-line";
    static final String ITEM_ID_NORTHBOUND = "fixture-northbound";
    static final String ITEM_ID_GLASS_SEA = "fixture-glass-sea";
    static final String ITEM_ID_HARBOR_LIGHTS = "fixture-harbor-lights";
    static final String RESUME_ID_MERIDIAN = "fixture-silent-meridian";
    static final String SERIES_ID_SILENT_MERIDIAN = "fixture-series-silent-meridian";
    static final String SERIES_ID_GLASS_SEA = "fixture-series-glass-sea";
    static final String SERIES_ID_HARBOR_LIGHTS = "fixture-series-harbor-lights";
    static final String MEDIA_SOURCE_ID = "fixture-media-source";
    static final String PLAY_SESSION_ID = "fixture-play-session";
    static final String POSTER_TAG = "fixture-poster-v1";
    static final long VIDEO_DURATION_TICKS = 300_000_000L;

    private static final String KEYSTORE_PASSWORD = "orbit-fixture-p12";
    private static final Pattern RANGE_HEADER = Pattern.compile("bytes=(\\d*)-(\\d*)", Pattern.CASE_INSENSITIVE);
    private static final int MAX_BODY_BYTES = 1_048_576;

    private final SSLServerSocket serverSocket;
    private final boolean television;
    private final byte[] video;
    private final byte[] miraPoster;
    private final byte[] hannahPoster;
    private final CopyOnWriteArrayList<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger rejectedTlsHandshakes = new AtomicInteger();
    private final AtomicInteger ioFailures = new AtomicInteger();
    private final Thread serverThread;
    private volatile boolean closed;

    JellyfinFixtureServer(Context testContext, boolean television) throws Exception {
        this.television = television;
        FixtureSecurity security = createSecurity(testContext);
        try (InputStream input = testContext.getAssets().open("jellyfin-fixture.mp4")) {
            video = readAll(input);
        }
        try (InputStream input = testContext.getAssets().open("mockup-poster-mira.png")) {
            miraPoster = readAll(input);
        }
        try (InputStream input = testContext.getAssets().open("mockup-poster-hannah.png")) {
            hannahPoster = readAll(input);
        }
        if (video.length < 1_024 || miraPoster.length < 1_024 || hannahPoster.length < 1_024) {
            throw new IOException("Jellyfin fixture media assets are unexpectedly small.");
        }
        SSLServerSocketFactory serverFactory = security.serverContext.getServerSocketFactory();
        serverSocket = (SSLServerSocket) serverFactory.createServerSocket(0, 16,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        serverSocket.setReuseAddress(false);
        serverThread = new Thread(this::serve, "orbit-jellyfin-https-fixture");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    int videoBytes() { return video.length; }

    int posterBytes() { return miraPoster.length; }

    int continueWatchingItemCount() throws JSONException { return continueWatchingItems().length(); }

    int recentlyAddedItemCount() throws JSONException { return latestItems().length(); }

    int libraryCount() throws JSONException { return libraryItems().length(); }

    int rejectedTlsHandshakes() { return rejectedTlsHandshakes.get(); }

    int ioFailures() { return ioFailures.get(); }

    String baseUrl() { return "https://127.0.0.1:" + serverSocket.getLocalPort() + "/jellyfin/"; }

    List<CapturedRequest> requests() { return Collections.unmodifiableList(new ArrayList<>(requests)); }

    CapturedRequest awaitRequest(String method, String path, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        do {
            for (CapturedRequest request : requests) {
                if (method.equals(request.method) && path.equals(request.path)) return request;
            }
            Thread.sleep(50L);
        } while (System.nanoTime() < deadline);
        return null;
    }

    private static FixtureSecurity createSecurity(Context context) throws Exception {
        char[] password = KEYSTORE_PASSWORD.toCharArray();
        KeyStore identity = KeyStore.getInstance("PKCS12");
        try (InputStream input = context.getAssets().open("jellyfin-fixture-keystore.p12")) {
            identity.load(input, password);
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(identity, password);
        SSLContext server = SSLContext.getInstance("TLS");
        server.init(keyManagers.getKeyManagers(), null, new SecureRandom());
        java.util.Arrays.fill(password, '\0');
        return new FixtureSecurity(server);
    }

    private void serve() {
        while (!closed) {
            try (SSLSocket client = (SSLSocket) serverSocket.accept()) {
                client.setSoTimeout(20_000);
                client.startHandshake();
                CapturedRequest request = readRequest(client);
                Response response = responseFor(request);
                writeResponse(client.getOutputStream(), request, response);
                request.responseStatus = response.status;
                request.responseBytes = response.body.length;
                if ("GET".equals(request.method) && "/jellyfin/Items".equals(request.path)) {
                    request.responseBody = new String(response.body, StandardCharsets.UTF_8);
                }
                requests.add(request);
                System.out.println("JELLYFIN_FIXTURE_HTTP " + request.method + ' ' + request.path
                        + " status=" + response.status + " bytes=" + response.body.length);
            } catch (SocketException closedSocket) {
                if (!closed) ioFailures.incrementAndGet();
            } catch (SSLHandshakeException rejected) {
                if (!closed) {
                    int count = rejectedTlsHandshakes.incrementAndGet();
                    System.out.println("JELLYFIN_FIXTURE_TLS_REJECT count=" + count);
                }
            } catch (IOException failure) {
                if (!closed) ioFailures.incrementAndGet();
            }
        }
    }

    private Response responseFor(CapturedRequest request) {
        try {
            if ("POST".equals(request.method)
                    && "/jellyfin/Users/AuthenticateByName".equals(request.path)) {
                return json(new JSONObject()
                        .put("User", new JSONObject().put("Id", USER_ID).put("Name", USER_NAME))
                        .put("AccessToken", ACCESS_TOKEN)
                        .put("ServerId", "orbit-fixture-server"));
            }
            if ("GET".equals(request.method) && "/jellyfin/System/Info/Public".equals(request.path)) {
                return json(new JSONObject().put("ServerName", SERVER_NAME)
                        .put("ProductName", "Jellyfin Server").put("Id", "orbit-fixture-server"));
            }
            if ("GET".equals(request.method) && "/jellyfin/Users/Me".equals(request.path)) {
                return json(new JSONObject().put("Id", USER_ID).put("Name", USER_NAME));
            }
            if ("GET".equals(request.method) && "/jellyfin/UserViews".equals(request.path)) {
                return json(new JSONObject().put("Items", libraryItems()));
            }
            if ("GET".equals(request.method) && "/jellyfin/UserItems/Resume".equals(request.path)) {
                JSONArray items = continueWatchingItems();
                return json(new JSONObject().put("Items", items)
                        .put("StartIndex", 0).put("TotalRecordCount", items.length()));
            }
            if ("GET".equals(request.method) && "/jellyfin/Items/Latest".equals(request.path)) {
                return json(latestItems());
            }
            if ("GET".equals(request.method) && "/jellyfin/Items".equals(request.path)) {
                String parentId = request.queryParameter("ParentId");
                JSONArray items;
                if (MOVIES_LIBRARY_ID.equals(parentId)) items = movieItems();
                else if (LIBRARY_ID.equals(parentId)) items = seriesItems();
                else return json(404, new JSONObject().put("Error", "Unknown fixture library."));
                return json(new JSONObject().put("Items", items)
                        .put("StartIndex", 0).put("TotalRecordCount", items.length()));
            }
            if ("GET".equals(request.method)
                    && request.path.matches("/jellyfin/Items/[^/]+/Images/Primary")) {
                String itemId = request.path.substring("/jellyfin/Items/".length(),
                        request.path.indexOf("/Images/Primary"));
                byte[] image = posterFor(itemId);
                return new Response(200, "image/png", image, Collections.emptyMap());
            }
            if ("POST".equals(request.method)
                    && ("/jellyfin/Items/" + ITEM_ID + "/PlaybackInfo").equals(request.path)) {
                String directStreamUrl = "Videos/" + ITEM_ID + "/stream?static=true&MediaSourceId="
                        + MEDIA_SOURCE_ID;
                JSONObject mediaSource = new JSONObject()
                        .put("Id", MEDIA_SOURCE_ID)
                        .put("Name", "Jellyfin fixture")
                        .put("Container", "mp4")
                        .put("Protocol", "Http")
                        .put("SupportsDirectPlay", true)
                        .put("SupportsDirectStream", true)
                        .put("SupportsTranscoding", false)
                        .put("DirectStreamUrl", directStreamUrl)
                        .put("MediaStreams", new JSONArray());
                return json(new JSONObject().put("PlaySessionId", PLAY_SESSION_ID)
                        .put("MediaSources", new JSONArray().put(mediaSource)));
            }
            if ("GET".equals(request.method)
                    && ("/jellyfin/Videos/" + ITEM_ID + "/stream").equals(request.path)) {
                return videoResponse(request);
            }
            if (request.path.startsWith("/jellyfin/Sessions/Playing")) return json(new JSONObject());
            return json(404, new JSONObject().put("Error", "Fixture endpoint not found."));
        } catch (JSONException error) {
            return json(500, new JSONObject());
        }
    }

    private JSONArray libraryItems() throws JSONException {
        return new JSONArray()
                .put(library(MOVIES_LIBRARY_ID, "Movies", "movies", 126))
                .put(library(LIBRARY_ID, "TV Shows", "tvshows", 48));
    }

    private JSONArray continueWatchingItems() throws JSONException {
        JSONArray items = new JSONArray()
                .put(episodeItem(RESUME_ID_MERIDIAN, "Silent Meridian",
                        "A listening post catches a signal across the fog.", 2024,
                        900_000_000L, 288_000_000L, 1, 4))
                .put(mediaItem(ITEM_ID_NORTHBOUND, "Northbound", "Movie",
                        "A winter crossing leads a small crew toward an unmapped coast.", 2025,
                        5_100_000_000L, 3_978_000_000L))
                .put(episodeItem(ITEM_ID_GLASS_SEA, "The Glass Sea",
                        "An observatory follows a signal moving beneath the waves.", 2024,
                        840_000_000L, 117_600_000L, 2, 2));
        if (television) {
            items.put(episodeItem(ITEM_ID_HARBOR_LIGHTS, "Harbor Lights",
                    "A keeper traces a quiet signal along the northern shore.", 2025,
                    780_000_000L, 475_800_000L, 1, 5));
        }
        return items;
    }

    private JSONArray latestItems() throws JSONException {
        JSONArray items = new JSONArray()
                .put(mediaItem(ITEM_ID, "Amber Sky", "Movie",
                        "A courier follows a pattern of lights over a distant coast.",
                        2026, VIDEO_DURATION_TICKS, 0L))
                .put(mediaItem(ITEM_ID_AFTER_TIDE, "After the Tide", "Series",
                        "A map appears among the objects left by the receding water.",
                        2026, 5_400_000_000L, 0L))
                .put(mediaItem(ITEM_ID_WINTER_LINE, "Winter Line", "Movie",
                        "A quiet crossing brings a family back to a winter harbor.",
                        2025, 5_700_000_000L, 0L))
                .put(mediaItem(ITEM_ID_HARBOR_LIGHTS, "Harbor Lights", "Series",
                        "A keeper traces a quiet signal along the northern shore.",
                        2025, 5_400_000_000L, 0L));
        if (television) {
            items.put(mediaItem(ITEM_ID_NORTHBOUND, "Northbound", "Movie",
                    "A winter crossing leads a small crew toward an unmapped coast.",
                    2025, 5_100_000_000L, 0L));
            items.put(mediaItem(ITEM_ID_GLASS_SEA, "The Glass Sea", "Series",
                    "An observatory follows a signal moving beneath the waves.",
                    2024, 5_250_000_000L, 0L));
        }
        return items;
    }

    private JSONArray movieItems() throws JSONException {
        JSONArray latest = latestItems();
        JSONArray movies = new JSONArray();
        for (int index = 0; index < latest.length(); index++) {
            JSONObject item = latest.optJSONObject(index);
            if (item != null && "Movie".equals(item.optString("Type"))) movies.put(item);
        }
        return movies;
    }

    private static JSONArray seriesItems() throws JSONException {
        return new JSONArray()
                .put(mediaItem(SERIES_ID_SILENT_MERIDIAN, "Silent Meridian", "Series",
                        "A listening post catches a signal across the fog.", 2024, 0L, 0L))
                .put(mediaItem(SERIES_ID_GLASS_SEA, "The Glass Sea", "Series",
                        "An observatory follows a signal moving beneath the waves.", 2024, 0L, 0L))
                .put(mediaItem(SERIES_ID_HARBOR_LIGHTS, "Harbor Lights", "Series",
                        "A keeper traces a quiet signal along the northern shore.", 2025, 0L, 0L));
    }

    private static JSONObject mediaItem(String id, String name, String type, String overview,
                                        int year, long durationTicks, long positionTicks) throws JSONException {
        return new JSONObject().put("Id", id).put("Name", name).put("Type", type)
                .put("Overview", overview).put("ProductionYear", year)
                .put("RunTimeTicks", durationTicks).put("ImageTags", new JSONObject()
                        .put("Primary", POSTER_TAG))
                .put("UserData", new JSONObject().put("PlaybackPositionTicks", positionTicks)
                        .put("Played", false));
    }

    private static JSONObject episodeItem(String id, String name, String overview,
                                          int year, long durationTicks, long positionTicks,
                                          int seasonNumber, int episodeNumber) throws JSONException {
        return mediaItem(id, name, "Episode", overview, year, durationTicks, positionTicks)
                .put("ParentIndexNumber", seasonNumber)
                .put("IndexNumber", episodeNumber);
    }

    private static JSONObject library(String id, String name, String type, int childCount)
            throws JSONException {
        return new JSONObject().put("Id", id).put("Name", name).put("CollectionType", type)
                .put("ChildCount", childCount)
                .put("ImageTags", new JSONObject().put("Primary", POSTER_TAG));
    }

    private byte[] posterFor(String itemId) {
        if (ITEM_ID.equals(itemId)) return miraPoster;
        if (ITEM_ID_AFTER_TIDE.equals(itemId) || RESUME_ID_MERIDIAN.equals(itemId)
                || ITEM_ID_GLASS_SEA.equals(itemId) || ITEM_ID_NORTHBOUND.equals(itemId)) return miraPoster;
        if (ITEM_ID_WINTER_LINE.equals(itemId) || ITEM_ID_HARBOR_LIGHTS.equals(itemId)) {
            return hannahPoster;
        }
        return miraPoster;
    }

    private Response videoResponse(CapturedRequest request) {
        String value = request.header("range");
        Matcher matcher = RANGE_HEADER.matcher(value);
        boolean ranged = matcher.matches();
        long total = video.length;
        long start = 0L;
        long end = total - 1L;
        if (ranged) {
            try {
                if (!matcher.group(1).isEmpty()) start = Long.parseLong(matcher.group(1));
                if (!matcher.group(2).isEmpty()) end = Long.parseLong(matcher.group(2));
                if (matcher.group(1).isEmpty() && !matcher.group(2).isEmpty()) {
                    long suffix = Long.parseLong(matcher.group(2));
                    start = Math.max(0L, total - suffix);
                }
            } catch (NumberFormatException invalidRange) {
                return rangeNotSatisfiable(total);
            }
        }
        if (start < 0L || start >= total || end < start) return rangeNotSatisfiable(total);
        end = Math.min(total - 1L, end);
        byte[] body = java.util.Arrays.copyOfRange(video, (int) start, (int) end + 1);
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        if (ranged) headers.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
        return new Response(ranged ? 206 : 200, "video/mp4", body, headers);
    }

    private static Response rangeNotSatisfiable(long total) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Range", "bytes */" + total);
        return new Response(416, "text/plain", new byte[0], headers);
    }

    private void writeResponse(OutputStream output, CapturedRequest request, Response response)
            throws IOException {
        int status = response.status;
        String statusText = status == 200 ? "OK" : status == 206 ? "Partial Content"
                : status == 404 ? "Not Found" : status == 416 ? "Range Not Satisfiable" : "Error";
        LinkedHashMap<String, String> headers = new LinkedHashMap<>(response.headers);
        headers.put("Content-Type", response.contentType);
        headers.put("Content-Length", String.valueOf(response.body.length));
        headers.put("Connection", "close");
        if (request.path.endsWith("/stream")) headers.put("Accept-Ranges", "bytes");
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(' ')
                .append(statusText).append("\r\n");
        for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        head.append("\r\n");
        output.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        output.write(response.body);
        output.flush();
    }

    private static CapturedRequest readRequest(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        String line = readAsciiLine(input);
        if (line == null) throw new IOException("Fixture received an empty request.");
        String[] parts = line.split(" ", 3);
        if (parts.length != 3) throw new IOException("Fixture received an invalid HTTP request.");
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        String header;
        while ((header = readAsciiLine(input)) != null && !header.isEmpty()) {
            int separator = header.indexOf(':');
            if (separator <= 0) throw new IOException("Fixture received an invalid HTTP header.");
            headers.put(header.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                    header.substring(separator + 1).trim());
        }
        int contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
            throw new IOException("Fixture received an oversized request.");
        }
        byte[] body = new byte[contentLength];
        int offset = 0;
        while (offset < body.length) {
            int count = input.read(body, offset, body.length - offset);
            if (count < 0) throw new IOException("Fixture received a truncated request body.");
            offset += count;
        }
        return new CapturedRequest(parts[0], parts[1], headers,
                new String(body, StandardCharsets.UTF_8));
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') line.write(value);
        }
        if (value == -1 && line.size() == 0) return null;
        return line.toString("US-ASCII");
    }

    private static Response json(Object value) {
        return new Response(200, "application/json; charset=utf-8",
                value.toString().getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
    }

    private static Response json(int status, Object value) {
        return new Response(status, "application/json; charset=utf-8",
                value.toString().getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    @Override public void close() throws IOException {
        closed = true;
        serverSocket.close();
        try { serverThread.join(2_000L); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    static final class CapturedRequest {
        final String method;
        final String target;
        final String path;
        final String body;
        final java.util.Map<String, String> headers;
        int responseStatus;
        int responseBytes;
        String responseBody = "";

        CapturedRequest(String method, String target, java.util.Map<String, String> headers, String body) {
            this.method = method;
            this.target = target;
            int query = target.indexOf('?');
            this.path = query < 0 ? target : target.substring(0, query);
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.body = body;
        }

        String header(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
        }

        String queryParameter(String name) {
            int queryStart = target.indexOf('?');
            if (queryStart < 0) return null;
            for (String part : target.substring(queryStart + 1).split("&")) {
                int separator = part.indexOf('=');
                if (separator <= 0) continue;
                try {
                    String key = URLDecoder.decode(part.substring(0, separator), "UTF-8");
                    if (name.equals(key)) {
                        return URLDecoder.decode(part.substring(separator + 1), "UTF-8");
                    }
                } catch (java.io.UnsupportedEncodingException impossible) {
                    throw new AssertionError("UTF-8 is unavailable.", impossible);
                }
            }
            return null;
        }

        JSONObject jsonBody() throws JSONException { return new JSONObject(body); }
    }

    private static final class FixtureSecurity {
        final SSLContext serverContext;
        FixtureSecurity(SSLContext serverContext) { this.serverContext = serverContext; }
    }

    private static final class Response {
        final int status;
        final String contentType;
        final byte[] body;
        final java.util.Map<String, String> headers;
        Response(int status, String contentType, byte[] body, java.util.Map<String, String> headers) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.headers = headers;
        }
    }
}
