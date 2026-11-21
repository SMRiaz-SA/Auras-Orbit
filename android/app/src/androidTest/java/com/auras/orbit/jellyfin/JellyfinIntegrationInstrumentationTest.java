package com.auras.orbit.jellyfin;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.auras.orbit.jellyfin.JellyfinModels.Connection;
import com.auras.orbit.jellyfin.JellyfinModels.LibraryView;
import com.auras.orbit.jellyfin.JellyfinModels.MediaItem;
import com.auras.orbit.jellyfin.JellyfinModels.MediaPage;
import com.auras.orbit.jellyfin.JellyfinModels.PlaybackEvent;
import com.auras.orbit.jellyfin.JellyfinModels.PlaybackInfo;
import com.auras.orbit.jellyfin.JellyfinModels.PlaybackRequest;
import com.auras.orbit.jellyfin.JellyfinModels.PlaybackSource;
import com.auras.orbit.jellyfin.JellyfinModels.User;
import com.auras.orbit.player.OrbitPlayerActivity;
import com.auras.orbit.player.PlaybackRequest.EpisodeMetadata;
import com.auras.orbit.player.PlaybackRequest.MediaMetadata;
import com.auras.orbit.player.PlaybackRequest.Source;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Verifies the Jellyfin connector and shared-player session callbacks against a local HTTP fixture. */
@RunWith(AndroidJUnit4.class)
public final class JellyfinIntegrationInstrumentationTest {
    private static final String USERNAME = "orbit-fixture-user";
    private static final String PASSWORD = "synthetic-fixture-pässword";
    private static final String ACCESS_TOKEN = "synthetic-fixture-token";
    private static final String USER_ID = "fixture-user-id";
    private static final String SERVER_NAME = "Living Room Server";
    private static final String ITEM_ID = "fixture-item-id";
    private static final String MEDIA_SOURCE_ID = "fixture-media-source";
    private static final String PLAY_SESSION_ID = "fixture-play-session";
    private static final long RESUME_TICKS = 456_700_000L;

    @Test public void libraryPlaybackAndSharedPlayerEventsUseJellyfinSessionContract() throws Exception {
        try (FixtureServer server = new FixtureServer(); JellyfinClient client = new JellyfinClient()) {
            Connection connection = client.authenticate(server.baseUrl(), USERNAME, PASSWORD)
                    .get(10, TimeUnit.SECONDS);
            assertEquals(USER_ID, connection.userId);
            assertEquals(SERVER_NAME, connection.serverName);
            assertEquals("Fixture User", connection.userName);
            assertFalse("the connection summary must not reveal the synthetic token",
                    connection.toString().contains(ACCESS_TOKEN));

            CapturedRequest authentication = server.takeRequest();
            assertRequest(authentication, "POST", "/jellyfin/Users/AuthenticateByName");
            JSONObject authenticationBody = authentication.jsonBody();
            assertTrue("the configured username is sent to the selected fixture server",
                    USERNAME.equals(authenticationBody.optString("Username")));
            assertTrue("the password is sent in the authentication body only",
                    PASSWORD.equals(authenticationBody.optString("Pw")));
            assertTrue("the password is not copied into the authorization header",
                    !authentication.header("Authorization").contains(PASSWORD));

            CapturedRequest publicInfo = server.takeRequest();
            assertRequest(publicInfo, "GET", "/jellyfin/System/Info/Public");
            assertFalse("public server info does not send the bearer token",
                    publicInfo.header("Authorization").contains(ACCESS_TOKEN));

            User validatedUser = client.validate(connection).get(10, TimeUnit.SECONDS);
            assertEquals(USER_ID, validatedUser.id);
            assertEquals("Fixture User", validatedUser.name);
            CapturedRequest validateRequest = server.takeRequest();
            assertRequest(validateRequest, "GET", "/jellyfin/Users/Me");
            assertAuthenticated(validateRequest);

            List<LibraryView> libraries = client.getLibraryViews(connection).get(10, TimeUnit.SECONDS);
            assertEquals(2, libraries.size());
            assertEquals("fixture-library-id", libraries.get(0).id);
            assertEquals("Orbit Library", libraries.get(0).name);
            assertTrue(libraries.get(0).hasChildCount);
            assertEquals(126, libraries.get(0).childCount);
            assertEquals("Countless Library", libraries.get(1).name);
            assertFalse("an omitted ChildCount remains distinguishable from a real zero",
                    libraries.get(1).hasChildCount);
            assertEquals(0, libraries.get(1).childCount);
            CapturedRequest primaryLibraryRequest = server.takeRequest();
            assertRequest(primaryLibraryRequest, "GET", "/jellyfin/UserViews");
            assertEquals("ChildCount is requested on the current views endpoint",
                    "ChildCount", primaryLibraryRequest.queryParameter("Fields"));
            assertEquals(USER_ID, primaryLibraryRequest.queryParameter("userId"));
            assertAuthenticated(primaryLibraryRequest);
            CapturedRequest libraryRequest = server.takeRequest();
            assertRequest(libraryRequest, "GET", "/jellyfin/Users/" + USER_ID + "/Views");
            assertEquals("ChildCount is preserved on the legacy views fallback",
                    "ChildCount", libraryRequest.queryParameter("Fields"));
            assertEquals(USER_ID, libraryRequest.queryParameter("userId"));
            assertEquals("false", libraryRequest.queryParameter("includeHidden"));
            assertAuthenticated(libraryRequest);

            MediaPage continueWatching = client.getContinueWatching(connection, 2, 8)
                    .get(10, TimeUnit.SECONDS);
            assertEquals(2, continueWatching.startIndex);
            assertEquals(5, continueWatching.totalRecordCount);
            assertEquals(1, continueWatching.items.size());
            assertEquals(RESUME_TICKS, continueWatching.items.get(0).playbackPositionTicks);
            assertEquals(2024, continueWatching.items.get(0).productionYear);
            CapturedRequest resumeRequest = server.takeRequest();
            assertRequest(resumeRequest, "GET", "/jellyfin/UserItems/Resume");
            assertEquals(USER_ID, resumeRequest.queryParameter("userId"));
            assertEquals("2", resumeRequest.queryParameter("StartIndex"));
            assertEquals("8", resumeRequest.queryParameter("Limit"));
            assertEquals("Video", resumeRequest.queryParameter("MediaTypes"));
            assertEquals("true", resumeRequest.queryParameter("EnableUserData"));
            assertAuthenticated(resumeRequest);

            List<MediaItem> recentlyAdded = client.getRecentlyAdded(connection, "fixture-library-id", 6)
                    .get(10, TimeUnit.SECONDS);
            assertEquals(1, recentlyAdded.size());
            assertEquals("Recently Added Fixture", recentlyAdded.get(0).name);
            CapturedRequest latestRequest = server.takeRequest();
            assertRequest(latestRequest, "GET", "/jellyfin/Items/Latest");
            assertEquals(USER_ID, latestRequest.queryParameter("userId"));
            assertEquals("fixture-library-id", latestRequest.queryParameter("ParentId"));
            assertEquals("6", latestRequest.queryParameter("Limit"));
            assertAuthenticated(latestRequest);

            MediaPage libraryItems = client.getItems(connection, "fixture-library-id", 3, 7,
                    "Star Orbit", Collections.singletonList("Episode")).get(10, TimeUnit.SECONDS);
            assertEquals(1, libraryItems.items.size());
            assertEquals(ITEM_ID, libraryItems.items.get(0).id);
            assertEquals("Fixture Episode", libraryItems.items.get(0).name);
            CapturedRequest itemsRequest = server.takeRequest();
            assertRequest(itemsRequest, "GET", "/jellyfin/Items");
            assertEquals(USER_ID, itemsRequest.queryParameter("userId"));
            assertEquals("fixture-library-id", itemsRequest.queryParameter("ParentId"));
            assertEquals("3", itemsRequest.queryParameter("StartIndex"));
            assertEquals("7", itemsRequest.queryParameter("Limit"));
            assertEquals("Star Orbit", itemsRequest.queryParameter("SearchTerm"));
            assertEquals("Episode", itemsRequest.queryParameter("IncludeItemTypes"));
            assertAuthenticated(itemsRequest);

            MediaItem item = libraryItems.items.get(0);
            PlaybackInfo playbackInfo = client.getPlaybackInfo(connection, item.id, RESUME_TICKS)
                    .get(10, TimeUnit.SECONDS);
            assertEquals(PLAY_SESSION_ID, playbackInfo.playSessionId);
            assertEquals(RESUME_TICKS, playbackInfo.startPositionTicks);
            assertEquals(1, playbackInfo.sources.size());
            PlaybackSource source = playbackInfo.sources.get(0);
            assertEquals(MEDIA_SOURCE_ID, source.id);
            assertTrue(source.supportsDirectPlay);
            CapturedRequest playbackInfoRequest = server.takeRequest();
            assertRequest(playbackInfoRequest, "POST", "/jellyfin/Items/" + ITEM_ID + "/PlaybackInfo");
            assertEquals(USER_ID, playbackInfoRequest.queryParameter("userId"));
            assertEquals(USER_ID, playbackInfoRequest.jsonBody().optString("UserId"));
            assertTrue(playbackInfoRequest.jsonBody().optBoolean("EnableDirectPlay"));
            assertTrue(playbackInfoRequest.jsonBody().optBoolean("EnableDirectStream"));
            assertTrue(playbackInfoRequest.jsonBody().optBoolean("EnableTranscoding"));
            assertEquals(RESUME_TICKS, playbackInfoRequest.jsonBody().optLong("StartTimeTicks"));
            assertAuthenticated(playbackInfoRequest);

            PlaybackRequest orbitPlayback = client.createPlaybackRequest(
                    connection, item.id, playbackInfo, source);
            assertTrue("the playback URL must retain the selected source", orbitPlayback.url.toString()
                    .contains("MediaSourceId=" + MEDIA_SOURCE_ID));
            assertFalse("credential query parameters must be removed from the playback URL",
                    orbitPlayback.url.toString().toLowerCase(Locale.ROOT).contains("api_key"));
            assertTrue("the playback request keeps server authorization in memory",
                    orbitPlayback.getHeadersForPlayer().get("Authorization").contains(ACCESS_TOKEN));
            assertFalse("the playback request summary must redact authorization",
                    orbitPlayback.toString().contains(ACCESS_TOKEN));

            OrbitPlayerActivity.PlaybackSnapshot startedSnapshot = snapshot(item, orbitPlayback,
                    1_234L, true);
            OrbitPlayerActivity.PlaybackSnapshot progressSnapshot = snapshot(item, orbitPlayback,
                    2_345L, false);
            OrbitPlayerActivity.PlaybackSnapshot stoppedSnapshot = snapshot(item, orbitPlayback,
                    3_456L, false);
            JellyfinPlaybackObserver observer = new JellyfinPlaybackObserver(
                    client, connection, item, playbackInfo, source);

            observer.onPlaybackStarted(startedSnapshot);
            CapturedRequest started = server.takeRequest();
            assertSessionEvent(started, "/jellyfin/Sessions/Playing", 12_340_000L, false, null);

            observer.onPlaybackProgress(progressSnapshot);
            CapturedRequest progress = server.takeRequest();
            assertSessionEvent(progress, "/jellyfin/Sessions/Playing/Progress", 23_450_000L, true, null);

            observer.onPlaybackStopped(stoppedSnapshot, OrbitPlayerActivity.StopReason.USER_EXIT);
            CapturedRequest stopped = server.takeRequest();
            assertSessionEvent(stopped, "/jellyfin/Sessions/Playing/Stopped", 34_560_000L, true, false);
            assertTrue("session JSON must not contain the authorization token",
                    !stopped.body.contains(ACCESS_TOKEN));
        }
    }

    private static OrbitPlayerActivity.PlaybackSnapshot snapshot(MediaItem item,
            PlaybackRequest playback, long positionMs, boolean isPlaying) throws Exception {
        MediaMetadata media = new MediaMetadata(item.id, "jellyfin-fixture", item.name,
                "episode", String.valueOf(item.productionYear), item.overview, "");
        EpisodeMetadata episode = new EpisodeMetadata(item.id, item.name, 1, 1);
        Source playerSource = new Source("fixture-player-source", "Jellyfin", playback.url.toString(),
                "video/mp4", playback.getHeadersForPlayer(), Collections.emptyList());
        com.auras.orbit.player.PlaybackRequest request = new com.auras.orbit.player.PlaybackRequest(
                "fixture-profile", "fixture-observer", media, episode,
                Collections.singletonList(playerSource), playerSource.id,
                Collections.emptyList(), Collections.emptyList());
        Constructor<OrbitPlayerActivity.PlaybackSnapshot> constructor =
                OrbitPlayerActivity.PlaybackSnapshot.class.getDeclaredConstructor(
                        String.class, com.auras.orbit.player.PlaybackRequest.class,
                        long.class, long.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance("fixture-session", request, positionMs, 120_000L, isPlaying);
    }

    private static void assertSessionEvent(CapturedRequest request, String path, long positionTicks,
                                           boolean paused, Boolean failed) throws Exception {
        assertRequest(request, "POST", path);
        JSONObject body = request.jsonBody();
        assertEquals(ITEM_ID, body.getString("ItemId"));
        assertEquals(MEDIA_SOURCE_ID, body.getString("MediaSourceId"));
        assertEquals(PLAY_SESSION_ID, body.getString("PlaySessionId"));
        assertEquals(positionTicks, body.getLong("PositionTicks"));
        assertTrue(body.getBoolean("CanSeek"));
        assertEquals(paused, body.getBoolean("IsPaused"));
        assertEquals("DirectPlay", body.getString("PlayMethod"));
        assertAuthenticated(request);
        if (failed != null) assertEquals(failed.booleanValue(), body.getBoolean("Failed"));
        else assertFalse("progress events do not include a stop-only Failed flag", body.has("Failed"));
    }

    private static void assertAuthenticated(CapturedRequest request) {
        assertTrue("authenticated Jellyfin requests carry the fixture access token",
                request.header("Authorization").contains(ACCESS_TOKEN));
        assertFalse("the fixture token is never copied into a JSON request body",
                request.body.contains(ACCESS_TOKEN));
    }

    private static void assertRequest(CapturedRequest request, String method, String path) {
        assertNotNull("the fixture server should have captured the request", request);
        assertEquals(method, request.method);
        assertEquals(path, request.path);
    }

    private static final class CapturedRequest {
        final String method;
        final String path;
        final Map<String, String> headers;
        final String body;

        CapturedRequest(String method, String target, Map<String, String> headers, String body)
                throws IOException {
            this.method = method;
            int query = target.indexOf('?');
            this.path = query < 0 ? target : target.substring(0, query);
            this.query = query < 0 ? "" : target.substring(query + 1);
            this.headers = headers;
            this.body = body;
        }

        private final String query;

        String header(String name) {
            String value = headers.get(name.toLowerCase(Locale.ROOT));
            return value == null ? "" : value;
        }

        String queryParameter(String name) throws Exception {
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) continue;
                String[] pieces = pair.split("=", 2);
                String key = URLDecoder.decode(pieces[0], "UTF-8");
                if (name.equals(key)) {
                    return URLDecoder.decode(pieces.length == 1 ? "" : pieces[1], "UTF-8");
                }
            }
            return null;
        }

        JSONObject jsonBody() throws JSONException { return new JSONObject(body); }
    }

    private static final class FixtureServer implements Closeable {
        private static final String RESUME_ITEM = "{\"Id\":\"" + ITEM_ID
                + "\",\"Name\":\"Fixture Episode\",\"Type\":\"Episode\",\"Overview\":\"A local test item.\","
                + "\"ProductionYear\":2024,\"RunTimeTicks\":1200000000,\"ImageTags\":{\"Primary\":\"poster-tag\"},"
                + "\"UserData\":{\"PlaybackPositionTicks\":" + RESUME_TICKS + ",\"Played\":false}}";
        private final ServerSocket socket;
        private final BlockingQueue<CapturedRequest> requests = new LinkedBlockingQueue<>();
        private final Thread serverThread;
        private volatile boolean closed;

        FixtureServer() throws IOException {
            socket = new ServerSocket(0, 8, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            serverThread = new Thread(this::serve, "orbit-jellyfin-http-fixture");
            serverThread.setDaemon(true);
            serverThread.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + socket.getLocalPort() + "/jellyfin/"; }

        CapturedRequest takeRequest() throws InterruptedException {
            CapturedRequest request = requests.poll(10, TimeUnit.SECONDS);
            assertNotNull("the Jellyfin call should reach the local fixture", request);
            return request;
        }

        private void serve() {
            while (!closed) {
                try (Socket client = socket.accept()) {
                    client.setSoTimeout(20_000);
                    CapturedRequest request = readRequest(client);
                    byte[] response = responseFor(request).getBytes(StandardCharsets.UTF_8);
                    OutputStream output = client.getOutputStream();
                    boolean currentViewsEndpoint = "GET".equals(request.method)
                            && "/jellyfin/UserViews".equals(request.path);
                    output.write(("HTTP/1.1 " + (currentViewsEndpoint ? "404 Not Found" : "200 OK")
                            + "\r\nContent-Type: application/json; charset=utf-8\r\n"
                            + "Content-Length: " + response.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.write(response);
                    output.flush();
                    requests.offer(request);
                } catch (SocketException closedSocket) {
                    if (!closed) throw new RuntimeException("Jellyfin fixture socket failed.", closedSocket);
                } catch (IOException failure) {
                    if (!closed) throw new RuntimeException("Jellyfin fixture request failed.", failure);
                }
            }
        }

        private static CapturedRequest readRequest(Socket socket) throws IOException {
            InputStream input = socket.getInputStream();
            String requestLine = readAsciiLine(input);
            if (requestLine == null) throw new IOException("Fixture received an empty request.");
            String[] line = requestLine.split(" ", 3);
            if (line.length != 3) throw new IOException("Fixture received an invalid request line.");
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            String header;
            while ((header = readAsciiLine(input)) != null && !header.isEmpty()) {
                int separator = header.indexOf(':');
                if (separator <= 0) throw new IOException("Fixture received an invalid header.");
                headers.put(header.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                        header.substring(separator + 1).trim());
            }
            int contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            if (contentLength < 0 || contentLength > 1_048_576) {
                throw new IOException("Fixture received an unsupported request body size.");
            }
            byte[] body = new byte[contentLength];
            int offset = 0;
            while (offset < body.length) {
                int count = input.read(body, offset, body.length - offset);
                if (count < 0) throw new IOException("Fixture received a truncated JSON body.");
                offset += count;
            }
            return new CapturedRequest(line[0], line[1], headers,
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
            return new String(line.toByteArray(), StandardCharsets.US_ASCII);
        }

        private static String responseFor(CapturedRequest request) {
            if ("POST".equals(request.method) && "/jellyfin/Users/AuthenticateByName".equals(request.path)) {
                return "{\"User\":{\"Id\":\"" + USER_ID
                        + "\",\"Name\":\"Fixture User\"},\"AccessToken\":\"" + ACCESS_TOKEN
                        + "\",\"ServerId\":\"fixture-server\"}";
            }
            if ("GET".equals(request.method) && "/jellyfin/System/Info/Public".equals(request.path)) {
                return "{\"ServerName\":\"" + SERVER_NAME + "\",\"Id\":\"fixture-server\"}";
            }
            if ("GET".equals(request.method) && "/jellyfin/Users/Me".equals(request.path)) {
                return "{\"Id\":\"" + USER_ID + "\",\"Name\":\"Fixture User\"}";
            }
            if ("GET".equals(request.method)
                    && ("/jellyfin/UserViews".equals(request.path)
                    || ("/jellyfin/Users/" + USER_ID + "/Views").equals(request.path))) {
                return "{\"Items\":[{\"Id\":\"fixture-library-id\",\"Name\":\"Orbit Library\","
                        + "\"CollectionType\":\"movies\",\"ChildCount\":126,\"ImageTags\":{\"Primary\":\"library-poster\"}},"
                        + "{\"Id\":\"fixture-library-no-count\",\"Name\":\"Countless Library\","
                        + "\"CollectionType\":\"mixed\"}]}";
            }
            if ("GET".equals(request.method) && "/jellyfin/UserItems/Resume".equals(request.path)) {
                return "{\"Items\":[" + RESUME_ITEM + "],\"StartIndex\":2,\"TotalRecordCount\":5}";
            }
            if ("GET".equals(request.method) && "/jellyfin/Items/Latest".equals(request.path)) {
                return "[{\"Id\":\"fixture-latest-id\",\"Name\":\"Recently Added Fixture\","
                        + "\"Type\":\"Movie\",\"ProductionYear\":2025}]";
            }
            if ("GET".equals(request.method) && "/jellyfin/Items".equals(request.path)) {
                return "{\"Items\":[" + RESUME_ITEM + "],\"StartIndex\":3,\"TotalRecordCount\":1}";
            }
            if ("POST".equals(request.method)
                    && ("/jellyfin/Items/" + ITEM_ID + "/PlaybackInfo").equals(request.path)) {
                return "{\"PlaySessionId\":\"" + PLAY_SESSION_ID + "\",\"MediaSources\":[{"
                        + "\"Id\":\"" + MEDIA_SOURCE_ID + "\",\"Name\":\"Main source\","
                        + "\"Container\":\"mp4\",\"Protocol\":\"File\",\"IsRemote\":false,"
                        + "\"SupportsDirectPlay\":true,\"SupportsDirectStream\":true,"
                        + "\"SupportsTranscoding\":true,\"DirectStreamUrl\":\"/Videos/" + ITEM_ID
                        + "/stream?MediaSourceId=" + MEDIA_SOURCE_ID + "&api_key=" + ACCESS_TOKEN + "\","
                        + "\"MediaStreams\":[{\"Index\":1,\"Type\":\"Audio\",\"Codec\":\"aac\","
                        + "\"Language\":\"en\",\"DisplayTitle\":\"English AAC\",\"IsDefault\":true}]}]}";
            }
            if (request.path.startsWith("/jellyfin/Sessions/Playing")) return "{}";
            return "{}";
        }

        @Override public void close() throws IOException {
            closed = true;
            socket.close();
            try { serverThread.join(500); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
