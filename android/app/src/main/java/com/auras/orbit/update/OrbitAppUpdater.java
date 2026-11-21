package com.auras.orbit.update;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import com.auras.orbit.update.PackageInstallerStatusReceiver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.DigestException;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android release updater following CloudStream's GitHub release and PackageInstaller flow. */
public final class OrbitAppUpdater {
    private static final String RELEASES_URL =
            "https://api.github.com/repos/SMRiaz-SA/Auras-Orbit/releases?per_page=100";
    private static final String APK_CONTENT_TYPE = "application/vnd.android.package-archive";
    private static final String USER_AGENT = "Auras-Orbit-Android-Updater";
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:\\.(\\d+))?$");
    private static final int MAX_RELEASE_RESPONSE_BYTES = 8 * 1024 * 1024;

    private OrbitAppUpdater() { }

    /** Returns the newest stable Orbit APK only when its Android versionCode is higher. */
    public static Update findLatestStableUpdate(String currentVersionName)
            throws IOException, JSONException {
        long currentVersionCode = androidVersionCode(currentVersionName);
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub release check failed with HTTP " + responseCode + ".");
            }
            long responseLength = connection.getContentLengthLong();
            if (responseLength > MAX_RELEASE_RESPONSE_BYTES) {
                throw new IOException("GitHub returned an unexpectedly large release list.");
            }
            byte[] response = readBounded(connection.getInputStream(), MAX_RELEASE_RESPONSE_BYTES);
            JSONArray releases = new JSONArray(new String(response, StandardCharsets.UTF_8));
            Update best = null;
            for (int index = 0; index < releases.length(); index++) {
                JSONObject release = releases.optJSONObject(index);
                if (release == null || release.optBoolean("draft", false)
                        || release.optBoolean("prerelease", false)) continue;

                String tag = release.optString("tag_name", "");
                long versionCode;
                try { versionCode = androidVersionCode(tag); }
                catch (IllegalArgumentException invalidTag) { continue; }
                if (versionCode <= currentVersionCode
                        || (best != null && versionCode <= best.versionCode)) continue;

                JSONObject asset = selectApkAsset(release.optJSONArray("assets"));
                if (asset == null) continue;
                String downloadUrl = asset.optString("browser_download_url", "");
                if (downloadUrl.trim().isEmpty()) continue;

                best = new Update(tag.startsWith("v") ? tag.substring(1) : tag,
                        tag, versionCode, release.optString("body", ""),
                        asset.optString("name", ""), downloadUrl,
                        asset.isNull("digest") ? null : asset.optString("digest", null),
                        asset.optLong("size", -1L), release.optString("node_id", ""));
            }
            return best;
        } finally {
            connection.disconnect();
        }
    }

    static long androidVersionCode(String versionName) {
        String normalized = versionName == null ? "" : versionName.trim();
        if (normalized.startsWith("v")) normalized = normalized.substring(1);
        Matcher match = VERSION_PATTERN.matcher(normalized);
        if (!match.matches()) throw new IllegalArgumentException("Unsupported Android version: " + versionName);
        long major = parseVersionPart(match.group(1));
        long minor = parseVersionPart(match.group(2));
        long patch = parseVersionPart(match.group(3));
        long build = match.group(4) == null ? 0L : parseVersionPart(match.group(4));
        if (major > 21 || minor > 99 || patch > 99 || build > 9999) {
            throw new IllegalArgumentException("Android version is outside the allocated versionCode range.");
        }
        long versionCode = major * 100_000_000L + minor * 1_000_000L + patch * 10_000L + build;
        if (versionCode < 1L || versionCode > 2_100_000_000L) {
            throw new IllegalArgumentException("Android version is outside the supported versionCode range.");
        }
        return versionCode;
    }

    private static long parseVersionPart(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid numeric Android version.", invalid); }
    }

    private static JSONObject selectApkAsset(JSONArray assets) {
        if (assets == null) return null;
        JSONObject firstApk = null;
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String url = asset.optString("browser_download_url", "");
            String contentType = asset.optString("content_type", "");
            boolean apk = contentType.equalsIgnoreCase(APK_CONTENT_TYPE)
                    || name.toLowerCase(Locale.ROOT).endsWith(".apk");
            if (!apk || url.trim().isEmpty()) continue;
            if ("Auras-Orbit.apk".equalsIgnoreCase(name)) return asset;
            if (firstApk == null) firstApk = asset;
        }
        return firstApk;
    }

    /** Downloads the selected GitHub release asset, verifies its published digest, and opens the OS installer. */
    public static void downloadAndInstall(Activity activity, Update update, ProgressListener progress)
            throws Exception {
        if (activity == null || update == null) throw new IllegalArgumentException("An Activity and update are required.");
        URL apkUrl = new URL(update.downloadUrl);
        if (!"https".equalsIgnoreCase(apkUrl.getProtocol())
                || !"github.com".equalsIgnoreCase(apkUrl.getHost())) {
            throw new IOException("The release APK URL is not a GitHub HTTPS download.");
        }

        HttpURLConnection connection = (HttpURLConnection) apkUrl.openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/octet-stream");
        int sessionId = -1;
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("The release APK download failed with HTTP " + responseCode + ".");
            }
            long contentLength = update.assetSize > 0L
                    ? update.assetSize : connection.getContentLengthLong();
            if (contentLength > 512L * 1024L * 1024L) {
                throw new IOException("The release APK exceeds the allowed download size.");
            }
            Digest digest = Digest.parse(update.digest);
            PackageInstaller packageInstaller = activity.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            if (contentLength > 0L) params.setSize(contentLength);
            sessionId = packageInstaller.createSession(params);

            long transferred = 0L;
            MessageDigest verifier = digest == null ? null : MessageDigest.getInstance(digest.algorithm);
            try (InputStream input = connection.getInputStream();
                 PackageInstaller.Session session = packageInstaller.openSession(sessionId);
                 OutputStream output = session.openWrite("base.apk", 0L, contentLength > 0L ? contentLength : -1L)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    if (verifier != null) verifier.update(buffer, 0, read);
                    transferred += read;
                    if (transferred > 512L * 1024L * 1024L) {
                        throw new IOException("The release APK exceeds the allowed download size.");
                    }
                    if (progress != null) progress.onProgress(transferred, contentLength);
                }
                output.flush();
                session.fsync(output);
                if (update.assetSize > 0L && transferred != update.assetSize) {
                    throw new IOException("The downloaded APK size does not match GitHub release metadata.");
                }
                if (verifier != null && !MessageDigest.isEqual(verifier.digest(), digest.bytes)) {
                    throw new DigestException("The downloaded APK SHA-256 does not match the GitHub release digest.");
                }
            }

            Intent receiverIntent = new Intent(activity, PackageInstallerStatusReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent status = PendingIntent.getBroadcast(activity,
                    (int) (System.currentTimeMillis() & 0x7fffffff), receiverIntent, flags);
            PackageInstaller.Session session = packageInstaller.openSession(sessionId);
            try {
                session.commit(status.getIntentSender());
            } finally {
                session.close();
            }
        } catch (Exception failure) {
            if (sessionId >= 0) {
                try { activity.getPackageManager().getPackageInstaller().abandonSession(sessionId); }
                catch (RuntimeException ignored) { }
            }
            throw failure;
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        try (InputStream stream = input; java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            int total = 0;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > limit) throw new IOException("GitHub release response exceeded the size limit.");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    public interface ProgressListener {
        void onProgress(long transferredBytes, long totalBytes);
    }

    public static final class Update {
        public final String versionName;
        public final String tagName;
        public final long versionCode;
        public final String changelog;
        public final String assetName;
        public final String downloadUrl;
        public final String digest;
        public final long assetSize;
        public final String releaseNodeId;

        Update(String versionName, String tagName, long versionCode, String changelog,
                String assetName, String downloadUrl, String digest, long assetSize, String releaseNodeId) {
            this.versionName = versionName;
            this.tagName = tagName;
            this.versionCode = versionCode;
            this.changelog = changelog;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
            this.digest = digest;
            this.assetSize = assetSize;
            this.releaseNodeId = releaseNodeId;
        }
    }

    private static final class Digest {
        final String algorithm;
        final byte[] bytes;

        private Digest(String algorithm, byte[] bytes) {
            this.algorithm = algorithm;
            this.bytes = bytes;
        }

        static Digest parse(String encoded) throws IOException {
            if (encoded == null || encoded.trim().isEmpty()) return null;
            String[] parts = encoded.trim().split("[:\\-]", 2);
            if (parts.length != 2 || parts[1].length() == 0 || (parts[1].length() & 1) != 0) {
                throw new IOException("The GitHub release digest is malformed.");
            }
            String algorithm;
            if ("sha256".equalsIgnoreCase(parts[0])) algorithm = "SHA-256";
            else if ("sha512".equalsIgnoreCase(parts[0])) algorithm = "SHA-512";
            else throw new IOException("The GitHub release uses an unsupported digest algorithm.");
            byte[] bytes = new byte[parts[1].length() / 2];
            for (int index = 0; index < bytes.length; index++) {
                int high = Character.digit(parts[1].charAt(index * 2), 16);
                int low = Character.digit(parts[1].charAt(index * 2 + 1), 16);
                if (high < 0 || low < 0) throw new IOException("The GitHub release digest is malformed.");
                bytes[index] = (byte) ((high << 4) | low);
            }
            if (bytes.length != ("SHA-256".equals(algorithm) ? 32 : 64)) {
                throw new IOException("The GitHub release digest has an invalid length.");
            }
            return new Digest(algorithm, bytes);
        }
    }
}
