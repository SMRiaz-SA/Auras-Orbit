package com.auras.orbit.stremio;

import com.auras.orbit.player.PlaybackRequest;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/** Maps only direct Stremio HTTP(S) stream URLs onto Orbit's existing player source model. */
public final class StremioPlaybackMapper {
    private StremioPlaybackMapper() { }

    /**
     * Convert a direct URL stream. Returns null for external, YouTube, torrent, or otherwise
     * non-direct variants; those variants have no accepted Orbit playback mapping in this layer.
     */
    public static PlaybackRequest.Source toDirectSource(
            StremioAddonRegistry.InstalledAddon addon, StremioModels.Stream stream) throws IOException {
        if (addon == null || stream == null || stream.url == null) return null;
        URI uri = validateDirectUrl(stream.url);
        Map<String, String> headers = stream.behaviorHints == null
                ? Collections.emptyMap() : stream.behaviorHints.requestHeaders;
        String label = buildLabel(addon.name, stream);
        String id = "stremio-" + digest(addon.installationId + "\n" + uri.toASCIIString());
        try {
            return new PlaybackRequest.Source(id, label, uri.toASCIIString(), inferMimeType(uri, stream),
                    headers, Collections.emptyList());
        } catch (RuntimeException error) {
            throw new IOException("This Stremio stream cannot be represented as an Orbit player source.");
        }
    }

    private static URI validateDirectUrl(String value) throws IOException {
        if (value.length() > StremioAddonClient.MAX_MANIFEST_URL_CHARS) {
            throw new IOException("The direct Stremio stream URL is too long.");
        }
        try {
            URI uri = new URI(value).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IOException("Only direct HTTP(S) Stremio streams can open in Orbit's player.");
            }
            if (uri.isOpaque() || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65_535) {
                throw new IOException("The direct Stremio stream URL is invalid.");
            }
            return uri;
        } catch (URISyntaxException error) {
            throw new IOException("The direct Stremio stream URL is invalid.");
        }
    }

    private static String inferMimeType(URI uri, StremioModels.Stream stream) {
        String candidate = uri.getPath() == null ? "" : uri.getPath();
        if (stream.behaviorHints != null && stream.behaviorHints.filename != null) {
            candidate = stream.behaviorHints.filename;
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (lower.endsWith(".mpd")) return "application/dash+xml";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".ts")) return "video/mp2t";
        return "";
    }

    private static String buildLabel(String addonName, StremioModels.Stream stream) {
        String detail = firstNonBlank(stream.title, stream.description, stream.name);
        String label = "Stremio: " + addonName + (detail == null ? "" : " · " + detail);
        return label.length() <= 190 ? label : label.substring(0, 190);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) return candidate.trim();
        }
        return null;
    }

    private static String digest(String value) throws IOException {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (int index = 0; index < 16; index++) result.append(String.format(Locale.ROOT, "%02x", bytes[index] & 0xff));
            java.util.Arrays.fill(bytes, (byte) 0);
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("Could not identify the Stremio playback source.");
        }
    }
}
