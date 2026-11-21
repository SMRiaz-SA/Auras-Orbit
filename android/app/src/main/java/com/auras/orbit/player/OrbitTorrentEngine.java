package com.auras.orbit.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.auras.orbit.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import go.Seq;
import torrServer.TorrServer;

/**
 * Orbit's consent-gated, process-local wrapper around its loopback-only Torrentserver build.
 * Magnet and private staged-metainfo input is held only while a playback request is active. The
 * native server is intentionally left running for the app process: the upstream stop entry point
 * is unsafe on Android.
 */
public final class OrbitTorrentEngine {
    public static final String TORRENT_AUTH_HEADER = "X-Orbit-Torrent-Token";
    private static final String TAG = "OrbitTorrentEngine";

    private static final int MAX_MAGNET_LENGTH = 8192;
    private static final int MAX_MAGNET_TRACKERS = 32;
    private static final int MAX_TRACKER_LENGTH = 2048;
    private static final int MAX_TORRENT_FILE_INDEX = 1_000_000;
    private static final int MAX_TORRENT_PATH_LENGTH = 4096;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_RAW_STREAM_PATH_LENGTH = 4096;
    private static final int SERVER_CONNECT_TIMEOUT_MS = 1500;
    private static final int SERVER_START_TIMEOUT_MS = 10000;
    private static final int API_CONNECT_TIMEOUT_MS = 5000;
    private static final int API_READ_TIMEOUT_MS = 60000;
    private static final String ENGINE_DIRECTORY = "orbit-torrent-engine";
    private static final Pattern BTIH = Pattern.compile("(?i)^urn:btih:([0-9a-f]{40}|[a-z2-7]{32})$");
    private static final Pattern HASH = Pattern.compile("(?i)^[0-9a-f]{40}$");
    private static final Pattern HEX_TOKEN = Pattern.compile("(?i)^[0-9a-f]{64}$");
    private static final int MAX_SOURCE_LENGTH = 8192;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object SERVER_LOCK = new Object();
    private static final Object SESSION_LOCK = new Object();
    private static final Map<String, Integer> ACTIVE_SESSIONS = new HashMap<>();
    private static final Map<String, List<File>> ACTIVE_SESSION_FILES = new HashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "orbit-torrent-engine");
            thread.setDaemon(true);
            return thread;
        }
    });

    private static volatile Server server;
    private static final SecureRandom RANDOM = new SecureRandom();

    private OrbitTorrentEngine() {}

    public interface ConsentGate {
        void requestConsent(ConsentCallback callback);
    }

    public interface ConsentCallback {
        void onDecision(boolean accepted);
    }

    public interface PlaybackCallback {
        void onReady(Session session);
        void onDenied();
        void onError(String safeMessage);
    }

    /** In-memory playback identity. The token is available only as a request header. */
    public static final class Session {
        public final String streamUri;
        public final Map<String, String> requestHeaders;
        public final String infoHash;
        public final String filePath;
        public final int fileIndex;
        private final AtomicBoolean released = new AtomicBoolean();

        private Session(String streamUri, String token, String infoHash, String filePath, int fileIndex) {
            this.streamUri = streamUri;
            this.infoHash = infoHash;
            this.filePath = filePath;
            this.fileIndex = fileIndex;
            Map<String, String> headers = new HashMap<>();
            headers.put(TORRENT_AUTH_HEADER, token);
            this.requestHeaders = Collections.unmodifiableMap(headers);
        }

        public String getStreamUri() { return streamUri; }
        public Map<String, String> getRequestHeaders() { return requestHeaders; }
        public String getInfoHash() { return infoHash; }
        public String getFilePath() { return filePath; }
        public int getFileIndex() { return fileIndex; }
    }

    /**
     * Validates a magnet, obtains this playback's UI consent, then starts the engine and adds
     * the torrent. Calling this method alone never starts native code or peer activity.
     */
    public static void playAfterConsent(Context context, String magnetUri,
            ConsentGate consentGate, PlaybackCallback callback) {
        playSourceAfterConsent(context, magnetUri, consentGate, callback);
    }

    /** Validates a BTIH magnet or private staged .torrent URI, obtains consent, then starts playback. */
    public static void playSourceAfterConsent(Context context, String source,
            ConsentGate consentGate, PlaybackCallback callback) {
        if (callback == null) return;
        if (context == null || consentGate == null) {
            postError(callback, "Torrent playback is unavailable.");
            return;
        }

        final TorrentInput input;
        try {
            input = validateSource(context, source);
        } catch (RuntimeException invalid) {
            TorrentLibraryStore.discardUnreferencedStagedSource(context, source);
            postError(callback, "The torrent source is invalid or unsupported.");
            return;
        }

        AtomicBoolean decided = new AtomicBoolean();
        Context appContext = context.getApplicationContext();
        MAIN.post(() -> {
            try {
                consentGate.requestConsent(accepted -> {
                    if (!decided.compareAndSet(false, true)) return;
                    if (!accepted) {
                        TorrentLibraryStore.removeStagedFileIfUnreferenced(appContext, input.file);
                        MAIN.post(callback::onDenied);
                        return;
                    }
                    WORKER.execute(() -> startPlayback(appContext, input, callback));
                });
            } catch (RuntimeException rejected) {
                if (decided.compareAndSet(false, true)) {
                    TorrentLibraryStore.removeStagedFileIfUnreferenced(appContext, input.file);
                    postError(callback, "Torrent playback could not be authorized.");
                }
            }
        });
    }

    /** Validates a source before the library persists it; no native code or peer activity starts. */
    static boolean isValidSource(Context context, String source) {
        try {
            validateSource(context, source);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** Checks a magnet with playback's parser without starting the torrent engine or network work. */
    public static boolean isValidMagnetSource(String source) {
        try {
            validateMagnet(source);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** Releases only this playback's torrent record and cache after player completion or exit. */
    public static void release(Context context, Session session) {
        if (context == null || session == null || !session.released.compareAndSet(false, true)) return;
        releaseByInfoHash(context, session.infoHash);
    }

    /** Used by the player observer after it receives only the canonical info hash. */
    public static void releaseByInfoHash(Context context, String infoHash) {
        if (context == null || infoHash == null || !HASH.matcher(infoHash).matches()) return;
        String canonicalHash = infoHash.toLowerCase(Locale.ROOT);
        Server activeServer = server;
        WORKER.execute(() -> {
            boolean remove;
            List<File> stagedFiles = null;
            synchronized (SESSION_LOCK) {
                Integer count = ACTIVE_SESSIONS.get(canonicalHash);
                if (count == null || count <= 0) return;
                remove = count == 1;
                if (!remove) {
                    ACTIVE_SESSIONS.put(canonicalHash, count - 1);
                } else {
                    ACTIVE_SESSIONS.remove(canonicalHash);
                    stagedFiles = ACTIVE_SESSION_FILES.remove(canonicalHash);
                }
            }
            if (remove && activeServer != null) removeTorrent(activeServer, canonicalHash);
            if (stagedFiles != null) {
                for (File stagedFile : stagedFiles) {
                    TorrentLibraryStore.releaseRetain(stagedFile);
                    TorrentLibraryStore.removeStagedFileIfUnreferenced(context, stagedFile);
                }
            }
        });
    }

    /** Binds the Player's local HTTP exception to this live engine and an active playback. */
    public static boolean isTrustedStreamRequest(String streamUri, Map<String, String> headers) {
        Server active = server;
        if (active == null || !active.ready || !isStrictLoopbackStreamUri(streamUri)
                || headers == null || headers.size() != 1) return false;

        final URI uri;
        try {
            uri = new URI(streamUri);
        } catch (URISyntaxException invalid) {
            return false;
        }
        if (!sameServerOrigin(active, uri)) return false;

        Map.Entry<String, String> entry = headers.entrySet().iterator().next();
        if (entry.getKey() == null || !TORRENT_AUTH_HEADER.equalsIgnoreCase(entry.getKey())
                || !constantTimeEquals(entry.getValue(), active.token)) return false;

        String query = uri.getRawQuery();
        int indexDelimiter = query.indexOf("&index=");
        if (!query.startsWith("link=") || indexDelimiter <= 5) return false;
        String hash = query.substring(5, indexDelimiter).toLowerCase(Locale.ROOT);
        if (!HASH.matcher(hash).matches()) return false;
        synchronized (SESSION_LOCK) {
            Integer count = ACTIVE_SESSIONS.get(hash);
            return count != null && count > 0;
        }
    }

    private static void startPlayback(Context context, TorrentInput input, PlaybackCallback callback) {
        String stage = "verify_source";
        String addedHash = null;
        boolean addRequestAttempted = false;
        File stagedFile = null;
        boolean stagedRetained = false;
        boolean readyPosted = false;
        boolean sessionRegistered = false;
        try {
            TorrentInput verified = input.file == null
                    ? input : validateStagedFile(context, input.file.toURI().toString());
            stagedFile = verified.file;
            stage = "retain_metainfo";
            if (stagedFile != null) {
                TorrentLibraryStore.retainForPlayback(context, stagedFile);
                stagedRetained = true;
            }
            stage = "start_server";
            Server activeServer = ensureServer(context);
            stage = "add_torrent";
            JSONObject add = new JSONObject();
            add.put("action", "add");
            add.put("link", verified.engineUri);
            add.put("save_to_db", false);
            addRequestAttempted = true;
            JSONObject status = postJson(activeServer, "/torrents", add);
            String hash = status.optString("hash", "").toLowerCase(Locale.ROOT);
            if (!HASH.matcher(hash).matches() || !hash.equals(verified.infoHash)) {
                throw new IOException("invalid local torrent status");
            }
            addedHash = hash;
            stage = "select_file";
            JSONObject file = selectFile(status.optJSONArray("file_stats"), verified.preferredFileIndex);
            String path = file.getString("path");
            int index = file.getInt("id");
            stage = "build_stream";
            String streamUri = buildStreamUri(activeServer, hash, path, index);
            stage = "register_session";
            if (verified.file != null) TorrentLibraryStore.retainForPlayback(context, verified.file);
            Session session = new Session(streamUri, activeServer.token, hash, path, index);
            synchronized (SESSION_LOCK) {
                Integer count = ACTIVE_SESSIONS.get(hash);
                ACTIVE_SESSIONS.put(hash, count == null ? 1 : count + 1);
                if (verified.file != null) {
                    List<File> stagedFiles = ACTIVE_SESSION_FILES.get(hash);
                    if (stagedFiles == null) {
                        stagedFiles = new ArrayList<>();
                        ACTIVE_SESSION_FILES.put(hash, stagedFiles);
                    }
                    stagedFiles.add(verified.file);
                }
            }
            sessionRegistered = true;
            stage = "post_callback";
            if (stagedRetained) {
                File callbackFile = stagedFile;
                if (!MAIN.post(() -> {
                    try {
                        callback.onReady(session);
                    } finally {
                        TorrentLibraryStore.releaseRetain(callbackFile);
                    }
                })) throw new IOException("playback callback unavailable");
                stagedRetained = false;
            } else if (!MAIN.post(() -> callback.onReady(session))) {
                throw new IOException("playback callback unavailable");
            }
            readyPosted = true;
        } catch (Exception | LinkageError failed) {
            if (sessionRegistered) {
                releaseByInfoHash(context, addedHash);
            } else if (addedHash == null && addRequestAttempted) {
                addedHash = input.infoHash;
            }
            if (!sessionRegistered && addedHash != null && !hasActiveSession(addedHash)) {
                Server activeServer = server;
                if (activeServer != null) removeTorrent(activeServer, addedHash);
            }
            if (BuildConfig.DEBUG) Log.w(TAG, stage + " " + failed.getClass().getSimpleName());
            postError(callback, "Torrent playback could not be prepared.");
        } finally {
            if (stagedRetained) TorrentLibraryStore.releaseRetain(stagedFile);
            if (!readyPosted) TorrentLibraryStore.removeStagedFileIfUnreferenced(context, stagedFile);
        }
    }

    private static TorrentInput validateSource(Context context, String source) {
        if (source == null || source.isEmpty() || source.length() > MAX_SOURCE_LENGTH
                || !source.equals(source.trim()) || source.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("invalid torrent source");
        }
        if (source.regionMatches(true, 0, "magnet:", 0, 7)) {
            MagnetInput magnet = validateMagnet(source);
            return new TorrentInput(magnet.engineUri, magnet.infoHash, null, magnet.preferredFileIndex);
        }
        try {
            URI uri = new URI(source);
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                    || uri.getRawAuthority() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getUserInfo() != null
                    || (uri.getHost() != null && !uri.getHost().isEmpty())) {
                throw new IllegalArgumentException("invalid private torrent file URI");
            }
            return validateStagedFile(context, source);
        } catch (URISyntaxException | IOException | SecurityException invalid) {
            throw new IllegalArgumentException("invalid private torrent file URI", invalid);
        }
    }

    private static TorrentInput validateStagedFile(Context context, String source)
            throws URISyntaxException, IOException {
        URI uri = new URI(source);
        if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                || uri.getRawAuthority() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getUserInfo() != null
                || (uri.getHost() != null && !uri.getHost().isEmpty())) {
            throw new IOException("invalid private torrent file URI");
        }
        File candidate;
        try {
            candidate = new File(uri).getCanonicalFile();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid private torrent file URI", invalid);
        }
        File directory = TorrentLibraryStore.stagedDirectory(context).getCanonicalFile();
        if (!directory.equals(candidate.getParentFile()) || !candidate.isFile()
                || candidate.length() <= 0L
                || candidate.length() > TorrentLibraryStore.MAX_TORRENT_FILE_BYTES) {
            throw new IOException("torrent file is outside private staging or has an invalid size");
        }
        String infoHash = TorrentLibraryStore.readValidatedInfoHash(candidate);
        return new TorrentInput(candidate.toURI().toString(), infoHash, candidate, null);
    }

    private static Server ensureServer(Context context) throws IOException {
        Server active = server;
        if (active != null) {
            awaitServer(active);
            return active;
        }
        synchronized (SERVER_LOCK) {
            active = server;
            if (active != null) {
                awaitServer(active);
                return active;
            }

            File cacheDirectory = new File(context.getCacheDir(), ENGINE_DIRECTORY);
            if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
                throw new IOException("private torrent cache unavailable");
            }

            String token = createToken();
            Seq.load();
            final long result;
            try {
                result = TorrServer.startTorrentServer(cacheDirectory.getAbsolutePath(), 0L, token);
            } catch (LinkageError | RuntimeException nativeFailure) {
                throw new IOException("native torrent server unavailable");
            }
            if (result < 1024L || result > 65535L) {
                throw new IOException("native torrent server unavailable");
            }
            active = new Server("http://127.0.0.1:" + result, token);
            server = active;
            awaitServer(active);
            return active;
        }
    }

    private static void awaitServer(Server active) throws IOException {
        long deadline = System.currentTimeMillis() + SERVER_START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse response = request(active, "GET", "/echo", null, SERVER_CONNECT_TIMEOUT_MS,
                        SERVER_CONNECT_TIMEOUT_MS);
                if (response.statusCode == HttpURLConnection.HTTP_OK) {
                    active.ready = true;
                    return;
                }
            } catch (IOException ignored) {
                // The native HTTP server starts on its own goroutine after the port is returned.
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("torrent server startup interrupted");
            }
        }
        throw new IOException("native torrent server unavailable");
    }

    private static JSONObject postJson(Server active, String path, JSONObject body) throws IOException, JSONException {
        HttpResponse response = request(active, "POST", path, body, API_CONNECT_TIMEOUT_MS, API_READ_TIMEOUT_MS);
        if (response.statusCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("local torrent request failed");
        }
        return new JSONObject(new String(response.body, StandardCharsets.UTF_8));
    }

    private static HttpResponse request(Server active, String method, String path, JSONObject body,
            int connectTimeout, int readTimeout) throws IOException {
        if (active == null || !path.startsWith("/") || path.startsWith("//")) {
            throw new IOException("invalid local torrent request");
        }
        URL url = new URL(active.baseUrl + path);
        if (!"http".equalsIgnoreCase(url.getProtocol()) || !"127.0.0.1".equals(url.getHost())
                || url.getPort() < 1024 || url.getPort() > 65535) {
            throw new IOException("invalid local torrent endpoint");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setRequestMethod(method);
            connection.setRequestProperty(TORRENT_AUTH_HEADER, active.token);
            connection.setRequestProperty("Accept", "application/json, text/plain");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(encoded.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(encoded);
                }
            }

            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) throw new IOException("local torrent redirect rejected");
            InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            byte[] bytes = input == null ? new byte[0] : readBounded(input, MAX_RESPONSE_BYTES);
            return new HttpResponse(status, bytes);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (count == 0) throw new IOException("Input stream made no progress.");
                total += count;
                if (total > maximum) throw new IOException("local torrent response too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static JSONObject selectFile(JSONArray files, Integer preferredFileIndex)
            throws IOException {
        if (files == null) {
            throw fileSelectionFailure(files, preferredFileIndex,
                    "file_stats_missing", false, false);
        }
        if (files.length() == 0) {
            throw fileSelectionFailure(files, preferredFileIndex,
                    "file_stats_empty", false, false);
        }
        if (files.length() > 10000) {
            throw fileSelectionFailure(files, preferredFileIndex,
                    "file_count_out_of_range", false, false);
        }
        if (preferredFileIndex != null) {
            if (preferredFileIndex < 0 || preferredFileIndex > MAX_TORRENT_FILE_INDEX) {
                throw fileSelectionFailure(files, preferredFileIndex,
                        "requested_index_out_of_range", false, false);
            }
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                int id = torrentFileId(file, i);
                if (id != preferredFileIndex) continue;
                String path = file.optString("path", "");
                if (!isSafeTorrentPath(path)) {
                    throw fileSelectionFailure(files, preferredFileIndex,
                            "requested_path_unsafe", true, false);
                }
                return normalizeImplicitZeroId(file, i);
            }
            throw fileSelectionFailure(files, preferredFileIndex,
                    "requested_index_not_found", false, false);
        }
        JSONObject first = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String path = file.optString("path", "");
            int id = torrentFileId(file, i);
            if (id < 0 || id > MAX_TORRENT_FILE_INDEX || !isSafeTorrentPath(path)) continue;
            if (first == null) first = normalizeImplicitZeroId(file, i);
            if (isVideoPath(path)) return normalizeImplicitZeroId(file, i);
        }
        if (first == null) {
            throw fileSelectionFailure(files, null, "no_safe_file", false, false);
        }
        return first;
    }

    /** Torrentserver's Go JSON omitempty omits ID 0, so only the first entry implies zero. */
    private static int torrentFileId(JSONObject file, int position) {
        if (file == null) return -1;
        if (!file.has("id")) return position == 0 ? 0 : -1;
        return file.optInt("id", -1);
    }

    private static JSONObject normalizeImplicitZeroId(JSONObject file, int position)
            throws IOException {
        if (position != 0 || file.has("id")) return file;
        try {
            file.put("id", 0);
            return file;
        } catch (JSONException invalid) {
            throw new IOException("torrent files unavailable");
        }
    }

    private static IOException fileSelectionFailure(JSONArray files, Integer requestedIndex,
            String category, boolean requestedIdMatched, boolean selectedPathSafe) {
        if (BuildConfig.DEBUG) {
            int fileCount = files == null ? -1 : files.length();
            StringBuilder ids = new StringBuilder("[");
            int idCount = files == null ? 0 : Math.min(files.length(), 16);
            for (int i = 0; i < idCount; i++) {
                if (i > 0) ids.append(',');
                JSONObject file = files.optJSONObject(i);
                ids.append(file == null ? -1 : file.optInt("id", -1));
            }
            ids.append(']');
            Log.w(TAG, "select_file category=" + category
                    + " requestedIndex=" + (requestedIndex == null ? "none" : requestedIndex)
                    + " fileCount=" + fileCount
                    + " ids=" + ids
                    + " requestedIdMatched=" + requestedIdMatched
                    + " selectedPathSafe=" + selectedPathSafe);
        }
        return new IOException("torrent files unavailable");
    }

    private static String buildStreamUri(Server active, String hash, String path, int index) throws IOException {
        if (!HASH.matcher(hash).matches() || index < 0 || index > 1_000_000 || !isSafeTorrentPath(path)) {
            throw new IOException("invalid local torrent stream");
        }
        final String encodedPath;
        try {
            encodedPath = URLEncoder.encode(path, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IOException("invalid local torrent stream");
        }
        String result = active.baseUrl + "/stream/" + encodedPath + "?link=" + hash + "&index=" + index + "&play";
        if (!isStrictLoopbackStreamUri(result)) throw new IOException("invalid local torrent stream");
        return result;
    }

    private static boolean isStrictLoopbackStreamUri(String value) {
        if (value == null || value.length() > 8192) return false;
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException invalid) {
            return false;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() < 1024 || uri.getPort() > 65535 || uri.getUserInfo() != null
                || uri.getRawFragment() != null || uri.getRawQuery() == null) return false;
        String rawPath = uri.getRawPath();
        if (rawPath == null || !rawPath.startsWith("/stream/") || rawPath.length() <= 8
                || rawPath.length() > MAX_RAW_STREAM_PATH_LENGTH || rawPath.indexOf('\\') >= 0) return false;
        try {
            if (!isSafeTorrentPath(URLDecoder.decode(rawPath.substring("/stream/".length()), "UTF-8"))) return false;
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException invalid) {
            return false;
        }
        String query = uri.getRawQuery();
        return query.matches("link=(?i:[0-9a-f]{40})&index=[0-9]{1,7}&play")
                && Integer.parseInt(query.substring(query.indexOf("index=") + 6,
                        query.lastIndexOf("&play"))) <= 1_000_000;
    }

    private static boolean sameServerOrigin(Server active, URI uri) {
        try {
            URI expected = new URI(active.baseUrl);
            return "http".equalsIgnoreCase(uri.getScheme())
                    && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() == expected.getPort()
                    && uri.getHost().equalsIgnoreCase(expected.getHost());
        } catch (URISyntaxException invalid) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String candidate, String expected) {
        if (candidate == null || expected == null) return false;
        byte[] candidateBytes = candidate.getBytes(StandardCharsets.US_ASCII);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        return candidateBytes.length == expectedBytes.length
                && MessageDigest.isEqual(candidateBytes, expectedBytes);
    }

    private static boolean isSafeTorrentPath(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_TORRENT_PATH_LENGTH
                || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\u0000') >= 0) return false;
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) return false;
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    private static boolean isVideoPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return false;
        String extension = lower.substring(dot + 1);
        return extension.equals("mkv") || extension.equals("mp4") || extension.equals("avi")
                || extension.equals("mov") || extension.equals("webm") || extension.equals("ts")
                || extension.equals("m4v");
    }

    private static MagnetInput validateMagnet(String source) {
        if (source == null || source.isEmpty() || source.length() > MAX_MAGNET_LENGTH
                || !source.equals(source.trim()) || source.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("invalid magnet");
        }
        final URI uri;
        try {
            uri = new URI(source);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("invalid magnet");
        }
        if (!"magnet".equalsIgnoreCase(uri.getScheme()) || uri.getRawAuthority() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("invalid magnet");
        }

        // magnet:?xt=... is opaque to java.net.URI, so read its query from the scheme-specific part.
        String rawQuery = uri.getRawQuery();
        if (uri.isOpaque()) {
            String schemeSpecificPart = uri.getRawSchemeSpecificPart();
            if (schemeSpecificPart == null || !schemeSpecificPart.startsWith("?")) {
                throw new IllegalArgumentException("invalid magnet");
            }
            rawQuery = schemeSpecificPart.substring(1);
        }
        if (rawQuery == null || rawQuery.isEmpty()) throw new IllegalArgumentException("invalid magnet");

        String infoHash = null;
        int infoHashComponent = -1;
        int trackerCount = 0;
        boolean displayNameSeen = false;
        boolean fileIndexSeen = false;
        Integer preferredFileIndex = null;
        String[] components = rawQuery.split("&", -1);
        if (components.length > MAX_MAGNET_TRACKERS + 3) throw new IllegalArgumentException("invalid magnet");
        for (int componentIndex = 0; componentIndex < components.length; componentIndex++) {
            String component = components[componentIndex];
            int equals = component.indexOf('=');
            if (equals <= 0 || equals == component.length() - 1) throw new IllegalArgumentException("invalid magnet");
            String key = decodeQueryPart(component.substring(0, equals));
            String value = decodeQueryPart(component.substring(equals + 1));
            if ("xt".equals(key)) {
                java.util.regex.Matcher match = BTIH.matcher(value);
                if (infoHash != null || !match.matches()) throw new IllegalArgumentException("invalid magnet");
                infoHash = canonicalBtih(match.group(1));
                if (infoHash == null) throw new IllegalArgumentException("invalid magnet");
                infoHashComponent = componentIndex;
            } else if ("dn".equals(key)) {
                if (displayNameSeen || value.length() > 512 || containsControl(value)) {
                    throw new IllegalArgumentException("invalid magnet");
                }
                displayNameSeen = true;
            } else if ("tr".equals(key)) {
                if (++trackerCount > MAX_MAGNET_TRACKERS || value.length() > MAX_TRACKER_LENGTH
                        || !isSafeTrackerUri(value)) throw new IllegalArgumentException("invalid magnet");
            } else if ("index".equals(key)) {
                if (fileIndexSeen || value.length() > 7 || !isAsciiDigits(value)) {
                    throw new IllegalArgumentException("invalid magnet");
                }
                try {
                    preferredFileIndex = Integer.parseInt(value);
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("invalid magnet");
                }
                if (preferredFileIndex > MAX_TORRENT_FILE_INDEX) {
                    throw new IllegalArgumentException("invalid magnet");
                }
                fileIndexSeen = true;
            } else {
                throw new IllegalArgumentException("invalid magnet");
            }
        }
        if (infoHash == null) throw new IllegalArgumentException("invalid magnet");
        return new MagnetInput(normalizeMagnetForEngine(source, components, infoHashComponent, infoHash),
                infoHash, preferredFileIndex);
    }

    private static boolean isAsciiDigits(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < '0' || character > '9') return false;
        }
        return true;
    }

    /** Converts accepted BTIH encodings to the engine's canonical lowercase hexadecimal form. */
    private static String canonicalBtih(String value) {
        if (value.length() == 40) return value.toLowerCase(Locale.ROOT);
        if (value.length() != 32) return null;

        byte[] decoded = new byte[20];
        int buffer = 0;
        int bitsAvailable = 0;
        int outputIndex = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= 'a' && character <= 'z') character = (char) (character - ('a' - 'A'));
            int digit;
            if (character >= 'A' && character <= 'Z') digit = character - 'A';
            else if (character >= '2' && character <= '7') digit = character - '2' + 26;
            else return null;

            buffer = (buffer << 5) | digit;
            bitsAvailable += 5;
            if (bitsAvailable >= 8) {
                bitsAvailable -= 8;
                if (outputIndex >= decoded.length) return null;
                decoded[outputIndex++] = (byte) ((buffer >> bitsAvailable) & 0xff);
            }
            buffer &= (1 << bitsAvailable) - 1;
        }
        if (outputIndex != decoded.length || bitsAvailable != 0 || buffer != 0) return null;

        final char[] hexDigits = "0123456789abcdef".toCharArray();
        StringBuilder hex = new StringBuilder(40);
        for (byte octet : decoded) {
            int unsigned = octet & 0xff;
            hex.append(hexDigits[unsigned >>> 4]).append(hexDigits[unsigned & 0x0f]);
        }
        return hex.toString();
    }

    /** Replaces only the raw xt value, preserving all other magnet query components verbatim. */
    private static String normalizeMagnetForEngine(String source, String[] components,
            int infoHashComponent, String infoHash) {
        int queryStart = source.indexOf('?');
        int schemeEnd = source.indexOf(':');
        if (queryStart < 0 || schemeEnd <= 0 || schemeEnd > queryStart || infoHashComponent < 0) {
            throw new IllegalArgumentException("invalid magnet");
        }

        StringBuilder normalized = new StringBuilder(source.length() + 8);
        normalized.append("magnet").append(source, schemeEnd, queryStart + 1);
        for (int index = 0; index < components.length; index++) {
            if (index > 0) normalized.append('&');
            String component = components[index];
            if (index == infoHashComponent) {
                int equals = component.indexOf('=');
                if (equals <= 0) throw new IllegalArgumentException("invalid magnet");
                normalized.append(component, 0, equals + 1).append("urn:btih:").append(infoHash);
            } else {
                normalized.append(component);
            }
        }
        return normalized.toString();
    }

    private static String decodeQueryPart(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException invalid) {
            throw new IllegalArgumentException("invalid magnet");
        }
    }

    private static boolean isSafeTrackerUri(String value) {
        if (value.isEmpty() || containsControl(value) || value.indexOf('\\') >= 0) return false;
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException invalid) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("udp".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null || uri.getHost().isEmpty()
                || uri.getUserInfo() != null || uri.getRawFragment() != null
                || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) return false;
        return true;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    private static String createToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        char[] hex = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            hex[i * 2] = alphabet[value >>> 4];
            hex[i * 2 + 1] = alphabet[value & 0x0f];
        }
        String token = new String(hex);
        if (!HEX_TOKEN.matcher(token).matches()) throw new IllegalStateException("token generation failed");
        return token;
    }

    private static void removeTorrent(Server active, String hash) {
        if (!HASH.matcher(hash).matches()) return;
        try {
            JSONObject drop = new JSONObject();
            drop.put("action", "drop");
            drop.put("hash", hash);
            postJson(active, "/torrents", drop);
        } catch (Exception | LinkageError ignored) {
            // Cleanup stays best-effort and never discloses the torrent identity.
        }
        try {
            JSONObject remove = new JSONObject();
            remove.put("action", "rem");
            remove.put("hash", hash);
            postJson(active, "/torrents", remove);
        } catch (Exception | LinkageError ignored) {
            // Cleanup stays best-effort and never discloses the torrent identity.
        }
    }

    private static boolean hasActiveSession(String hash) {
        synchronized (SESSION_LOCK) {
            Integer count = ACTIVE_SESSIONS.get(hash);
            return count != null && count > 0;
        }
    }

    private static void postError(PlaybackCallback callback, String message) {
        MAIN.post(() -> callback.onError(message));
    }

    private static final class MagnetInput {
        final String engineUri;
        final String infoHash;
        final Integer preferredFileIndex;
        MagnetInput(String engineUri, String infoHash, Integer preferredFileIndex) {
            this.engineUri = engineUri;
            this.infoHash = infoHash;
            this.preferredFileIndex = preferredFileIndex;
        }
    }

    private static final class TorrentInput {
        final String engineUri;
        final String infoHash;
        final File file;
        final Integer preferredFileIndex;

        TorrentInput(String engineUri, String infoHash, File file, Integer preferredFileIndex) {
            this.engineUri = engineUri;
            this.infoHash = infoHash;
            this.file = file;
            this.preferredFileIndex = preferredFileIndex;
        }
    }

    private static final class Server {
        final String baseUrl;
        final String token;
        volatile boolean ready;
        Server(String baseUrl, String token) {
            this.baseUrl = baseUrl;
            this.token = token;
        }
    }

    private static final class HttpResponse {
        final int statusCode;
        final byte[] body;
        HttpResponse(int statusCode, byte[] body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }
}
