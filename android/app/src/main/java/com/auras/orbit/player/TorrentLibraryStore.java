package com.auras.orbit.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Private storage for resumable torrent inputs. It stores metainfo files, never media payloads. */
public final class TorrentLibraryStore {
    static final int MAX_TORRENT_FILE_BYTES = 4 * 1024 * 1024;

    private static final String DIRECTORY_NAME = "orbit-torrent-library";
    private static final String METAINFO_DIRECTORY_NAME = "metainfo";
    private static final String PREFERENCES_NAME = "orbit-torrent-library-v1";
    private static final int MAX_PROFILE_ID_LENGTH = 512;
    private static final int MAX_STABLE_ID_LENGTH = 2048;
    private static final int MAX_SOURCE_LENGTH = 8192;
    private static final Object STORE_LOCK = new Object();
    private static final Map<String, Integer> ACTIVE_STAGED_FILES = new HashMap<>();

    private TorrentLibraryStore() {}

    /** A saved source descriptor for a profile-specific media identity. */
    public static final class Entry {
        public final String profileId;
        public final String stableId;
        public final String source;

        private Entry(String profileId, String stableId, String source) {
            this.profileId = profileId;
            this.stableId = stableId;
            this.source = source;
        }
    }

    /** Copies and validates a selected document into durable app-private storage. */
    public static File copyTorrentFile(Context context, Uri sourceUri) {
        if (context == null || sourceUri == null) {
            throw new IllegalArgumentException("A torrent document is required.");
        }
        File partial = null;
        try {
            File directory = stagedDirectory(context);
            cleanup(context);
            partial = File.createTempFile("orbit-", ".partial", directory);
            try (InputStream input = context.getContentResolver().openInputStream(sourceUri);
                    FileOutputStream output = new FileOutputStream(partial, false)) {
                if (input == null) throw new IOException("torrent document could not be opened");
                byte[] buffer = new byte[8192];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) throw new IOException("Input stream made no progress.");
                    total += count;
                    if (total > MAX_TORRENT_FILE_BYTES) {
                        throw new IOException("torrent metainfo exceeds the allowed size");
                    }
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }

            String infoHash = readValidatedInfoHash(partial);
            File durable = new File(directory, "torrent-" + infoHash + "-" + UUID.randomUUID() + ".torrent");
            if (!partial.renameTo(durable)) throw new IOException("torrent metainfo could not be stored");
            partial = null;
            return durable.getCanonicalFile();
        } catch (IOException | SecurityException failure) {
            throw new IllegalStateException("The selected file is not a supported torrent metainfo file.", failure);
        } finally {
            if (partial != null && partial.exists()) partial.delete();
        }
    }

    /** Saves a validated magnet URI or private staged metainfo URI for this profile and title. */
    public static void save(Context context, String profileId, String stableId, String source) {
        requireIdentity(profileId, stableId);
        if (context == null || source == null || source.isEmpty() || source.length() > MAX_SOURCE_LENGTH
                || !OrbitTorrentEngine.isValidSource(context, source)) {
            throw new IllegalArgumentException("The torrent source is invalid.");
        }

        try {
            JSONObject record = new JSONObject();
            record.put("profileId", profileId);
            record.put("stableId", stableId);
            record.put("source", source);
            synchronized (STORE_LOCK) {
                boolean saved = preferences(context).edit()
                        .putString(storageKey(profileId, stableId), record.toString())
                        .commit();
                if (!saved) throw new IllegalStateException("Torrent source could not be saved.");
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException("Torrent source could not be saved.", impossible);
        }
    }

    /** Returns the source saved for this exact profile and stable media identity, if present. */
    public static Entry find(Context context, String profileId, String stableId) {
        if (context == null || !isValidIdentity(profileId, stableId)) return null;
        String encoded = preferences(context).getString(storageKey(profileId, stableId), null);
        if (encoded == null || encoded.length() > (6 * (MAX_SOURCE_LENGTH + MAX_PROFILE_ID_LENGTH
                + MAX_STABLE_ID_LENGTH)) + 128) return null;
        try {
            JSONObject record = new JSONObject(encoded);
            String storedProfile = record.optString("profileId", "");
            String storedStableId = record.optString("stableId", "");
            String source = record.optString("source", "");
            if (!profileId.equals(storedProfile) || !stableId.equals(storedStableId)
                    || source.isEmpty() || source.length() > MAX_SOURCE_LENGTH) return null;
            return new Entry(storedProfile, storedStableId, source);
        } catch (JSONException malformed) {
            return null;
        }
    }

    /** Removes a saved descriptor without disturbing another title that references the same file. */
    public static void remove(Context context, String profileId, String stableId) {
        if (context == null || !isValidIdentity(profileId, stableId)) return;
        synchronized (STORE_LOCK) {
            Entry previous = find(context, profileId, stableId);
            preferences(context).edit().remove(storageKey(profileId, stableId)).commit();
            removeSourceFileIfUnreferenced(context, previous == null ? null : previous.source);
        }
    }

    /** Removes abandoned staging files only; durable torrent descriptors remain available. */
    public static void cleanup(Context context) {
        if (context == null) return;
        try {
            synchronized (STORE_LOCK) {
                File directory = stagedDirectory(context);
                File[] files = directory.listFiles();
                if (files == null) return;
                long staleBefore = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
                for (File file : files) {
                    String name = file.getName();
                    if (!file.isFile() || file.lastModified() >= staleBefore) continue;
                    if ((name.startsWith("orbit-") && name.endsWith(".partial"))
                            || (name.startsWith("torrent-") && name.endsWith(".torrent")
                                && !isReferenced(context, file))) {
                        file.delete();
                    }
                }
            }
        } catch (IOException | SecurityException ignored) {
            // Cleanup is best-effort and never blocks playback or a selected document.
        }
    }

    static void removeStagedFileIfUnreferenced(Context context, File file) {
        if (context == null || file == null) return;
        synchronized (STORE_LOCK) {
            try {
                File candidate = file.getCanonicalFile();
                File directory = stagedDirectory(context);
                if (directory.equals(candidate.getParentFile()) && candidate.isFile()
                        && !isReferenced(context, candidate)) {
                    candidate.delete();
                }
            } catch (IOException | SecurityException ignored) {
                // A failed playback must not be made worse by best-effort metainfo cleanup.
            }
        }
    }

    static void retainForPlayback(Context context, File file) throws IOException {
        if (context == null || file == null) return;
        File canonicalFile = file.getCanonicalFile();
        File directory = stagedDirectory(context);
        if (!directory.equals(canonicalFile.getParentFile()) || !canonicalFile.isFile()) {
            throw new IOException("torrent metainfo is outside private staging");
        }
        synchronized (STORE_LOCK) {
            String path = canonicalFile.getPath();
            Integer count = ACTIVE_STAGED_FILES.get(path);
            ACTIVE_STAGED_FILES.put(path, count == null ? 1 : count + 1);
        }
    }

    static void releaseRetain(File file) {
        if (file == null) return;
        synchronized (STORE_LOCK) {
            try {
                String path = file.getCanonicalPath();
                Integer count = ACTIVE_STAGED_FILES.get(path);
                if (count == null || count <= 1) ACTIVE_STAGED_FILES.remove(path);
                else ACTIVE_STAGED_FILES.put(path, count - 1);
            } catch (IOException | SecurityException ignored) {
                // The retained source remains protected by its durable library descriptor.
            }
        }
    }

    static void discardUnreferencedStagedSource(Context context, String source) {
        removeSourceFileIfUnreferenced(context, source);
    }

    static File stagedDirectory(Context context) throws IOException {
        if (context == null) throw new IOException("private torrent storage unavailable");
        Context appContext = context.getApplicationContext();
        File filesDirectory = (appContext == null ? context : appContext).getFilesDir().getCanonicalFile();
        File base = new File(filesDirectory, DIRECTORY_NAME);
        File directory = new File(base, METAINFO_DIRECTORY_NAME);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("private torrent storage unavailable");
        }
        File canonicalBase = base.getCanonicalFile();
        File canonicalDirectory = directory.getCanonicalFile();
        String basePath = canonicalBase.getPath();
        if (!base.getAbsoluteFile().equals(canonicalBase)
                || !canonicalDirectory.equals(new File(canonicalBase, METAINFO_DIRECTORY_NAME).getAbsoluteFile())
                || !canonicalDirectory.getPath().startsWith(basePath + File.separator)) {
            throw new IOException("private torrent storage path is invalid");
        }
        return canonicalDirectory;
    }

    static String readValidatedInfoHash(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() <= 0L
                || file.length() > MAX_TORRENT_FILE_BYTES) {
            throw new IOException("torrent metainfo size is invalid");
        }
        byte[] bytes;
        try (InputStream input = new FileInputStream(file);
                ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length())) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) throw new IOException("Input stream made no progress.");
                total += count;
                if (total > MAX_TORRENT_FILE_BYTES) throw new IOException("torrent metainfo is too large");
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        return new MetainfoParser(bytes).readV1InfoHash();
    }

    private static SharedPreferences preferences(Context context) {
        Context appContext = context.getApplicationContext();
        return (appContext == null ? context : appContext)
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static void removeSourceFileIfUnreferenced(Context context, String source) {
        if (source == null || source.isEmpty()) return;
        try {
            URI uri = new URI(source);
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                    || uri.getRawAuthority() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) return;
            removeStagedFileIfUnreferenced(context, new File(uri));
        } catch (IllegalArgumentException | URISyntaxException | SecurityException ignored) {
            // Magnet descriptors and malformed legacy values have no staged file to remove.
        }
    }

    private static boolean isReferenced(Context context, File candidate) throws IOException {
        File canonicalCandidate = candidate.getCanonicalFile();
        synchronized (STORE_LOCK) {
            Integer activeCount = ACTIVE_STAGED_FILES.get(canonicalCandidate.getPath());
            if (activeCount != null && activeCount > 0) return true;
        }
        for (Object value : preferences(context).getAll().values()) {
            if (!(value instanceof String)) continue;
            try {
                JSONObject record = new JSONObject((String) value);
                String source = record.optString("source", "");
                URI uri = new URI(source);
                if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                        || uri.getRawAuthority() != null || uri.getRawQuery() != null
                        || uri.getRawFragment() != null) continue;
                File stored = new File(uri).getCanonicalFile();
                if (canonicalCandidate.equals(stored)) return true;
            } catch (JSONException | IllegalArgumentException | URISyntaxException ignored) {
                // Ignore malformed records when deciding whether a staged file is still in use.
            }
        }
        return false;
    }

    private static void requireIdentity(String profileId, String stableId) {
        if (!isValidIdentity(profileId, stableId)) {
            throw new IllegalArgumentException("A profile and stable media identity are required.");
        }
    }

    private static boolean isValidIdentity(String profileId, String stableId) {
        return profileId != null && !profileId.isEmpty() && profileId.length() <= MAX_PROFILE_ID_LENGTH
                && stableId != null && !stableId.isEmpty() && stableId.length() <= MAX_STABLE_ID_LENGTH;
    }

    private static String storageKey(String profileId, String stableId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] profileBytes = profileId.getBytes(StandardCharsets.UTF_8);
            byte[] stableBytes = stableId.getBytes(StandardCharsets.UTF_8);
            digest.update((byte) (profileBytes.length >>> 24));
            digest.update((byte) (profileBytes.length >>> 16));
            digest.update((byte) (profileBytes.length >>> 8));
            digest.update((byte) profileBytes.length);
            digest.update(profileBytes);
            byte[] hash = digest.digest(stableBytes);
            StringBuilder key = new StringBuilder("source-");
            for (byte value : hash) {
                int unsigned = value & 0xff;
                key.append("0123456789abcdef".charAt(unsigned >>> 4));
                key.append("0123456789abcdef".charAt(unsigned & 0x0f));
            }
            return key.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Torrent source storage is unavailable.", impossible);
        }
    }

    private static final class MetainfoParser {
        private static final int MAX_DEPTH = 64;
        private static final int MAX_NODES = 200_000;

        private final byte[] data;
        private int position;
        private int nodes;

        MetainfoParser(byte[] data) {
            this.data = data;
        }

        String readV1InfoHash() throws IOException {
            if (data.length == 0 || readByte() != 'd') throw invalid();
            boolean infoSeen = false;
            int infoStart = -1;
            int infoEnd = -1;
            while (peekByte() != 'e') {
                byte[] key = readBytes(true);
                if (isKey(key, "info")) {
                    if (infoSeen) throw invalid();
                    infoSeen = true;
                    infoStart = position;
                    readInfoDictionary();
                    infoEnd = position;
                } else {
                    skipValue(1);
                }
            }
            readByte();
            if (position != data.length || !infoSeen || infoEnd <= infoStart) throw invalid();

            try {
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                byte[] hash = sha1.digest(copyRange(infoStart, infoEnd));
                StringBuilder hex = new StringBuilder(40);
                for (byte value : hash) {
                    int unsigned = value & 0xff;
                    hex.append("0123456789abcdef".charAt(unsigned >>> 4));
                    hex.append("0123456789abcdef".charAt(unsigned & 0x0f));
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException impossible) {
                throw new IOException("torrent metainfo hashing is unavailable", impossible);
            }
        }

        private void readInfoDictionary() throws IOException {
            if (readByte() != 'd') throw invalid();
            boolean piecesSeen = false;
            boolean nameAsciiSeen = false;
            boolean nameUtf8Seen = false;
            long pieceLength = -1L;
            long metaVersion = 1L;
            boolean metaVersionSeen = false;
            boolean pieceLengthSeen = false;
            int piecesLength = 0;
            while (peekByte() != 'e') {
                byte[] key = readBytes(true);
                if (isKey(key, "pieces")) {
                    if (piecesSeen) throw invalid();
                    piecesSeen = true;
                    piecesLength = readBytes(false).length;
                } else if (isKey(key, "piece length")) {
                    if (pieceLengthSeen) throw invalid();
                    pieceLengthSeen = true;
                    pieceLength = readInteger();
                } else if (isKey(key, "name")) {
                    if (nameAsciiSeen) throw invalid();
                    nameAsciiSeen = true;
                    if (readBytes(false).length == 0) throw invalid();
                } else if (isKey(key, "name.utf-8")) {
                    if (nameUtf8Seen) throw invalid();
                    nameUtf8Seen = true;
                    if (readBytes(false).length == 0) throw invalid();
                } else if (isKey(key, "meta version")) {
                    if (metaVersionSeen) throw invalid();
                    metaVersionSeen = true;
                    metaVersion = readInteger();
                } else {
                    skipValue(2);
                }
            }
            readByte();
            // A v2-only info dictionary has no v1 pieces string and cannot be served by Orbit's BTIH flow.
            if ((!nameAsciiSeen && !nameUtf8Seen) || !piecesSeen || piecesLength == 0
                    || piecesLength % 20 != 0 || pieceLength <= 0L
                    || (metaVersionSeen && metaVersion != 1L && metaVersion != 2L)) {
                throw invalid();
            }
        }

        private void skipValue(int depth) throws IOException {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) throw invalid();
            int marker = peekByte();
            if (marker >= '0' && marker <= '9') {
                readBytes(false);
            } else if (marker == 'i') {
                readInteger();
            } else if (marker == 'l') {
                readByte();
                while (peekByte() != 'e') skipValue(depth + 1);
                readByte();
            } else if (marker == 'd') {
                readByte();
                while (peekByte() != 'e') {
                    readBytes(true);
                    skipValue(depth + 1);
                }
                readByte();
            } else {
                throw invalid();
            }
        }

        private long readInteger() throws IOException {
            if (readByte() != 'i') throw invalid();
            int start = position;
            while (peekByte() != 'e') position++;
            int length = position - start;
            if (length == 0 || length > 20) throw invalid();
            String number = new String(data, start, length, StandardCharsets.US_ASCII);
            if (!number.matches("-?(0|[1-9][0-9]*)") || "-0".equals(number)) throw invalid();
            readByte();
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException invalid) {
                throw invalid();
            }
        }

        private byte[] readBytes(boolean dictionaryKey) throws IOException {
            int first = peekByte();
            if (first < '0' || first > '9') throw invalid();
            int lengthStart = position;
            long byteCount = 0L;
            while (peekByte() != ':') {
                int digit = readByte();
                if (digit < '0' || digit > '9') throw invalid();
                int value = digit - '0';
                if (byteCount > (data.length - value) / 10L) throw invalid();
                byteCount = byteCount * 10L + value;
                if (byteCount > data.length) throw invalid();
            }
            int digits = position - lengthStart;
            if (digits <= 0 || (digits > 1 && data[lengthStart] == '0')
                    || (dictionaryKey && byteCount > 1024L)) throw invalid();
            readByte();
            if (byteCount > data.length - position) throw invalid();
            byte[] value = new byte[(int) byteCount];
            System.arraycopy(data, position, value, 0, value.length);
            position += value.length;
            return value;
        }

        private int readByte() throws IOException {
            if (position >= data.length) throw invalid();
            return data[position++] & 0xff;
        }

        private int peekByte() throws IOException {
            if (position >= data.length) throw invalid();
            return data[position] & 0xff;
        }

        private byte[] copyRange(int start, int end) {
            byte[] result = new byte[end - start];
            System.arraycopy(data, start, result, 0, result.length);
            return result;
        }

        private static boolean isKey(byte[] key, String expected) {
            return expected.equals(new String(key, StandardCharsets.US_ASCII));
        }

        private static IOException invalid() {
            return new IOException("invalid or unsupported torrent metainfo");
        }
    }
}
