package com.auras.orbit.extensions;

import android.content.Context;
import android.content.res.AssetManager;

import com.auras.orbit.extensions.CloudStreamExtensionManager.PluginInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only pinned StreamPlay metadata plus a one-request loopback package server. */
public final class PinnedStreamPlayV694Fixture implements Closeable {
    public static final String REPOSITORY_URL =
            "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json";
    public static final String PLUGIN_ID = CS3PluginRuntime.STREAMPLAY_V694_CANARY_PLUGIN_ID;
    public static final String PACKAGE_SHA256 =
            "1e4c77492069b3b8da2c2825c4d40e813436c0c2feed73b193b264ba4e1736ea";
    public static final long PACKAGE_SIZE = 2_106_784L;
    public static final long VERSION = 694L;
    public static final String PACKAGE_ASSET = "cs3-canary/StreamPlay_v694_1e4c7749_pinned.cs3";
    public static final String CATALOG_ASSET = "cs3-canary/StreamPlay_v694_catalog_4bf1a1_plugins.json";
    private static final String CATALOG_SHA256 =
            "d93280b14cb167ce3aabed165f67131fd1945458839bbea426fe528f68fe0588";
    private static final String UPSTREAM_CATALOG_REPOSITORY =
            "https://github.com/phisher98/cloudstream-extensions-phisher";
    private static final String UPSTREAM_PACKAGE_URL =
            "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/StreamPlay.cs3";
    private static final String HTTP_PATH = "/StreamPlay.cs3";
    private static final long MAX_CATALOG_BYTES = 512L * 1024L;
    private static final int MAX_HTTP_HEADER_BYTES = 16 * 1024;

    private final JSONObject sourceCatalogRecord;
    private final byte[] packageBytes;
    private final ServerSocket serverSocket;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final Thread serverThread;
    private volatile Socket activeSocket;
    private volatile String requestLine;
    private volatile IOException serverFailure;
    private volatile boolean closed;

    private PinnedStreamPlayV694Fixture(JSONObject sourceCatalogRecord, byte[] packageBytes)
            throws IOException {
        this.sourceCatalogRecord = sourceCatalogRecord;
        this.packageBytes = packageBytes;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(false);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
        serverSocket.setSoTimeout(30_000);
        serverThread = new Thread(this::serveOnePackageRequest, "orbit-pinned-cs3-canary-http");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    /** Loads and hash-checks both pinned test assets before binding the loopback server. */
    public static PinnedStreamPlayV694Fixture open(Context instrumentationContext) throws IOException {
        if (instrumentationContext == null) throw new IOException("Instrumentation context is unavailable.");
        AssetManager assets = instrumentationContext.getAssets();
        JSONObject record = readPinnedCatalogRecord(assets);
        byte[] bytes = readAsset(assets, PACKAGE_ASSET, PACKAGE_SIZE);
        if (bytes.length != PACKAGE_SIZE || !PACKAGE_SHA256.equals(sha256(bytes))) {
            throw new IOException("Pinned StreamPlay package asset failed its size or SHA-256 check.");
        }
        return new PinnedStreamPlayV694Fixture(record, bytes);
    }

    /** Builds the exact pinned catalog entry while directing only its package URL to loopback. */
    public PluginInfo pluginInfo(String repositoryUrl) throws IOException {
        return buildPluginInfo(sourceCatalogRecord, repositoryUrl, packageUrl());
    }

    /** Reads the pinned catalog entry for report-only checks without starting a server. */
    public static PluginInfo readPinnedPluginInfo(
            Context instrumentationContext, String repositoryUrl, String packageUrl) throws IOException {
        if (instrumentationContext == null) throw new IOException("Instrumentation context is unavailable.");
        return buildPluginInfo(readPinnedCatalogRecord(instrumentationContext.getAssets()), repositoryUrl, packageUrl);
    }

    public String packageUrl() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort() + HTTP_PATH;
    }

    /** Seeds the existing manager cache through its serializer; callers read it back via getCatalog(). */
    public static void seedCatalog(CloudStreamExtensionManager manager, PluginInfo plugin) throws IOException {
        if (manager == null || plugin == null) throw new IOException("Pinned catalog target is unavailable.");
        if (!REPOSITORY_URL.equals(plugin.repositoryUrl) || !PLUGIN_ID.equals(plugin.id)
                || !PACKAGE_SHA256.equals(plugin.expectedSha256) || plugin.expectedSize != PACKAGE_SIZE
                || plugin.version != VERSION) {
            throw new IOException("Refusing to seed a catalog record outside the pinned StreamPlay identity.");
        }
        try {
            Method saveCatalog = CloudStreamExtensionManager.class.getDeclaredMethod(
                    "saveCatalog", String.class, List.class);
            saveCatalog.setAccessible(true);
            saveCatalog.invoke(manager, plugin.repositoryUrl, Collections.singletonList(plugin));
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("Could not persist the pinned StreamPlay catalog record.", cause);
        } catch (ReflectiveOperationException | SecurityException error) {
            throw new IOException("The existing repository catalog serializer is unavailable to the test fixture.", error);
        }
    }

    /** Confirms the installer fetched the exact package once from the deterministic loopback endpoint. */
    public void assertServedExactlyOnce() throws IOException, InterruptedException {
        serverThread.join(5_000L);
        if (serverThread.isAlive()) throw new IOException("The loopback package request did not finish.");
        if (serverFailure != null) throw new IOException("The loopback package server failed.", serverFailure);
        if (requestCount.get() != 1 || !("GET " + HTTP_PATH + " HTTP/1.1").equals(requestLine)) {
            throw new IOException("The installer did not make exactly one GET for the pinned package.");
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (!serverSocket.isClosed()) serverSocket.close();
        Socket accepted = activeSocket;
        if (accepted != null) accepted.close();
        try {
            serverThread.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while closing the loopback package server.", interrupted);
        }
        if (serverThread.isAlive()) serverThread.interrupt();
    }

    private void serveOnePackageRequest() {
        Socket accepted = null;
        try {
            accepted = serverSocket.accept();
            activeSocket = accepted;
            try (Socket socket = accepted) {
                requestCount.incrementAndGet();
                socket.setSoTimeout(10_000);
                InputStream input = socket.getInputStream();
                requestLine = readHttpLine(input);
                int headerBytes = requestLine == null ? 0 : requestLine.length() + 2;
                String header;
                do {
                    header = readHttpLine(input);
                    if (header == null) throw new IOException("The installer request ended inside its headers.");
                    headerBytes += header.length() + 2;
                    if (headerBytes > MAX_HTTP_HEADER_BYTES) {
                        throw new IOException("The installer request headers exceeded the fixture limit.");
                    }
                } while (!header.isEmpty());

                OutputStream output = socket.getOutputStream();
                if (("GET " + HTTP_PATH + " HTTP/1.1").equals(requestLine)) {
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                            + "Content-Length: " + packageBytes.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.write(packageBytes);
                } else {
                    output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                }
                output.flush();
            }
        } catch (IOException error) {
            if (!closed) serverFailure = error;
        } finally {
            activeSocket = null;
        }
    }

    private static String readHttpLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int next;
        while ((next = input.read()) != -1) {
            if (previous == '\r' && next == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
            }
            line.write(next);
            if (line.size() > MAX_HTTP_HEADER_BYTES) throw new IOException("The installer request line exceeded the fixture limit.");
            previous = next;
        }
        return line.size() == 0 ? null : new String(line.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static PluginInfo buildPluginInfo(JSONObject record, String repositoryUrl, String downloadUrl)
            throws IOException {
        String canonicalRepository = CloudStreamExtensionManager.canonicalize(repositoryUrl);
        String canonicalDownload = CloudStreamExtensionManager.canonicalize(downloadUrl);
        if (!REPOSITORY_URL.equals(canonicalRepository) || canonicalDownload == null) {
            throw new IOException("Pinned StreamPlay metadata requires its exact repository and a valid download URL.");
        }
        try {
            String name = record.getString("name");
            String internalName = record.getString("internalName");
            String sourceUrl = record.getString("url");
            String sourceRepository = record.getString("repositoryUrl");
            String hash = normalizeHash(record.getString("fileHash"));
            long version = record.getLong("version");
            long fileSize = record.getLong("fileSize");
            if (!"StreamPlay".equals(name) || !"StreamPlay".equals(internalName)
                    || !UPSTREAM_PACKAGE_URL.equals(sourceUrl)
                    || !UPSTREAM_CATALOG_REPOSITORY.equals(sourceRepository)
                    || !PACKAGE_SHA256.equals(hash) || version != VERSION || fileSize != PACKAGE_SIZE) {
                throw new IOException("Pinned upstream catalog record does not match StreamPlay v694.");
            }
            String pluginId = canonicalRepository + "|" + internalName.toLowerCase(Locale.ROOT);
            if (!PLUGIN_ID.equals(pluginId)) throw new IOException("Pinned StreamPlay plugin ID is unexpected.");
            JSONArray authorsJson = record.optJSONArray("authors");
            List<String> authors = new ArrayList<>();
            if (authorsJson != null) {
                for (int index = 0; index < authorsJson.length(); index++) {
                    String author = authorsJson.optString(index, "").trim();
                    if (!author.isEmpty()) authors.add(author);
                }
            }
            StringBuilder authorNames = new StringBuilder();
            for (String author : authors) {
                if (authorNames.length() > 0) authorNames.append(", ");
                authorNames.append(author);
            }
            return new PluginInfo(pluginId, canonicalRepository, name, internalName, canonicalDownload,
                    record.optString("description", ""), authorNames.toString(),
                    record.optString("language", ""), hash, version, fileSize, record.optInt("status", 1));
        } catch (JSONException error) {
            throw new IOException("Pinned upstream catalog record is malformed.", error);
        }
    }

    private static JSONObject readPinnedCatalogRecord(AssetManager assets) throws IOException {
        byte[] bytes = readAsset(assets, CATALOG_ASSET, MAX_CATALOG_BYTES);
        if (!CATALOG_SHA256.equals(sha256(bytes))) {
            throw new IOException("Pinned StreamPlay catalog asset failed its SHA-256 check.");
        }
        try {
            JSONArray catalog = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            JSONObject match = null;
            int matches = 0;
            for (int index = 0; index < catalog.length(); index++) {
                JSONObject record = catalog.optJSONObject(index);
                if (record != null && "StreamPlay".equalsIgnoreCase(record.optString("internalName", ""))) {
                    match = record;
                    matches++;
                }
            }
            if (matches != 1 || match == null) {
                throw new IOException("Pinned upstream catalog must contain exactly one StreamPlay record.");
            }
            return match;
        } catch (JSONException error) {
            throw new IOException("Pinned upstream catalog asset is not valid JSON.", error);
        }
    }

    private static byte[] readAsset(AssetManager assets, String name, long limit) throws IOException {
        try (InputStream input = assets.open(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            long total = 0L;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) throw new IOException("Pinned fixture asset made no read progress.");
                total += count;
                if (total > limit) throw new IOException("Pinned fixture asset exceeded its size limit.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String normalizeHash(String rawHash) throws IOException {
        String hash = rawHash == null ? "" : rawHash.trim().toLowerCase(Locale.ROOT);
        if (hash.startsWith("sha256-")) hash = hash.substring(7);
        else if (hash.startsWith("sha256:")) hash = hash.substring(7);
        if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Pinned catalog hash is malformed.");
        return hash;
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte value : digest) output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return output.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable.", error);
        }
    }
}
