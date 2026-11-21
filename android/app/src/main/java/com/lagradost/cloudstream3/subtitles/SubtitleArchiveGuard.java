package com.lagradost.cloudstream3.subtitles;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Untrusted subtitle ZIP handling.  All created files stay under the supplied private cache.
 * Enforces both compressed transport and expanded archive budgets and rolls back on failure.
 * This deliberately has no Android dependencies so it can be fuzzed offline.
 */
public final class SubtitleArchiveGuard {
    public static final long MAX_ZIP_BYTES = 16L * 1024L * 1024L;
    public static final long MAX_ENTRY_BYTES = 8L * 1024L * 1024L;
    public static final long MAX_EXPANDED_BYTES = 32L * 1024L * 1024L;
    public static final int MAX_ENTRIES = 64;
    private static final int MAX_ENTRY_NAME_LENGTH = 240;
    private static final long MAX_HTTP_BODY_TIME_MS = 45_000L;

    private SubtitleArchiveGuard() { }

    public static final class Extracted {
        public final String name;
        public final File file;
        private Extracted(String name, File file) {
            this.name = name;
            this.file = file;
        }
    }

    /** Bounded copying shared by direct downloaded subtitles and ZIP transport. */
    public static long copyLimited(InputStream source, OutputStream destination, long byteLimit)
            throws IOException {
        if (source == null || destination == null || byteLimit < 0) {
            throw new IllegalArgumentException("Invalid bounded stream");
        }
        return copyLimitedUntil(source, destination, byteLimit, Long.MAX_VALUE);
    }

    /** Optional monotonic deadline also defeats a server that trickles endless small chunks. */
    public static long copyLimitedUntil(InputStream source, OutputStream destination,
                                        long byteLimit, long deadlineNanos) throws IOException {
        if (source == null || destination == null || byteLimit < 0) {
            throw new IllegalArgumentException("Invalid bounded stream");
        }
        byte[] buffer = new byte[8192];
        long written = 0;
        int zeroReads = 0;
        while (true) {
            if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() - deadlineNanos >= 0) {
                throw new IOException("Subtitle transfer exceeded the time budget");
            }
            int count = source.read(buffer);
            if (count == -1) return written;
            if (count == 0) {
                if (++zeroReads >= 8) throw new IOException("Subtitle input made no progress");
                continue;
            }
            zeroReads = 0;
            if (count > byteLimit - written) throw new IOException("Subtitle exceeds the byte budget");
            destination.write(buffer, 0, count);
            written += count;
        }
    }

    /** Rejects URL tricks and relative/local schemes before creating a network connection. */
    public static URL requireHttpsSubtitleUrl(String input) throws IOException {
        if (input == null || input.length() < 1 || input.length() > 4096
                || !input.equals(input.trim())) throw new IOException("Invalid subtitle URL");
        try {
            URI uri = new URI(input);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isEmpty()
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawAuthority() == null || uri.getRawAuthority().endsWith(":")
                    || host.endsWith(".") || host.startsWith(".") || host.contains("..")) {
                throw new IOException("Subtitle ZIP needs a valid HTTPS URL");
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            // Avoid IPv4/IPv6 literals and legacy numeric encodings that can bypass
            // hostname-based local-target restrictions (octal, hex, abbreviated IPv4).
            if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                    || normalized.endsWith(".local") || normalized.endsWith(".internal")
                    || normalized.endsWith(".home.arpa") || normalized.indexOf(':') >= 0
                    || normalized.matches("[0-9.]+") || normalized.startsWith("0x")) {
                throw new IOException("Subtitle ZIP cannot address a local endpoint");
            }
            return uri.toURL();
        } catch (URISyntaxException | IllegalArgumentException error) {
            throw new IOException("Invalid subtitle URL", error);
        }
    }

    /** Retrieves one HTTPS file with no redirects; the caller owns/deletes the temp file. */
    public static File downloadZip(String url, File privateCache) throws IOException {
        URL safe = requireHttpsSubtitleUrl(url);
        HttpURLConnection connection = (HttpURLConnection) safe.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(12000);
        connection.setUseCaches(false);
        File downloaded = null;
        final long bodyDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_HTTP_BODY_TIME_MS);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Subtitle ZIP request failed");
            long declared = connection.getContentLength();
            if (declared > MAX_ZIP_BYTES) throw new IOException("Subtitle ZIP is too large");
            downloaded = File.createTempFile("subtitle-zip-", ".tmp", privateCache);
            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(downloaded)) {
                copyLimitedUntil(input, output, MAX_ZIP_BYTES, bodyDeadline);
            }
            return downloaded;
        } catch (IOException | RuntimeException error) {
            if (downloaded != null) downloaded.delete();
            throw error;
        } finally {
            connection.disconnect();
        }
    }

    /** Never trusts ZIP entry length headers, compressed ratio, filename or entry count. */
    public static List<Extracted> extract(File archive, File privateCache) throws IOException {
        if (archive == null || !archive.isFile() || archive.length() > MAX_ZIP_BYTES
                || archive.length() < 1 || privateCache == null || !privateCache.isDirectory()) {
            throw new IOException("Invalid subtitle archive or private cache");
        }
        ArrayList<Extracted> extracted = new ArrayList<>();
        long expanded = 0;
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRIES) throw new IOException("Too many subtitle ZIP entries");
                String safeName = safeEntryName(entry.getName());
                if (!entry.isDirectory()) {
                    if (extracted.size() >= MAX_ENTRIES) throw new IOException("Too many subtitles");
                    File temporary = File.createTempFile("unzipped-subtitle-", ".tmp", privateCache);
                    extracted.add(new Extracted(safeName, temporary));
                    try (OutputStream output = new FileOutputStream(temporary)) {
                        long read = copyLimited(zip, output,
                                Math.min(MAX_ENTRY_BYTES, MAX_EXPANDED_BYTES - expanded));
                        expanded += read;
                    }
                }
                zip.closeEntry();
            }
            // ZipInputStream reports no entries for arbitrary plain bytes rather than
            // signaling a parse failure.  Treat that as corrupt input, never success.
            if (extracted.isEmpty()) throw new IOException("Subtitle ZIP contains no files");
            return Collections.unmodifiableList(extracted);
        } catch (IOException | RuntimeException | Error error) {
            for (Extracted item : extracted) item.file.delete();
            throw error;
        }
    }

    private static String safeEntryName(String raw) throws IOException {
        if (raw == null || raw.length() < 1 || raw.length() > MAX_ENTRY_NAME_LENGTH
                || raw.startsWith("/") || raw.startsWith("\\")) {
            throw new IOException("Unsafe subtitle ZIP entry name");
        }
        String path = raw.replace('\\', '/');
        for (String component : path.split("/", -1)) {
            if (component.equals(".") || component.equals("..")) {
                throw new IOException("Unsafe subtitle ZIP path component");
            }
        }
        String name = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.length() == 0 || name.equals(".") || name.equals("..")) {
            throw new IOException("Unsafe subtitle filename");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || c == ':') {
                throw new IOException("Subtitle ZIP entry has control characters");
            }
        }
        return name;
    }
}
