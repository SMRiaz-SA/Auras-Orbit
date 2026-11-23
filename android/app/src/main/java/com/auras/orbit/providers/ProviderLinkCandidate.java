package com.auras.orbit.providers;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable stream metadata returned by a provider's link resolution operation. */
public final class ProviderLinkCandidate {
    public static final int MAX_AUDIO_TRACKS = 128;
    /** Match downloader and playback boundaries before retaining raw plugin-supplied fields. */
    public static final int MAX_URI_CHARS = 16_384;
    public static final int MAX_VERIFIER_CHARS = 16_384;
    public final String id;
    public final String name;
    public final String source;
    public final String uri;
    public final String referer;
    public final int quality;
    public final String type;
    /** Optional provider MIME hint, used when the URI does not reveal its container. */
    public final String mimeType;
    /** Why Orbit cannot safely use some provider-supplied metadata, while retaining the row. */
    public final String unavailableReason;
    public final String extractorData;
    public final Map<String, String> headers;
    public final List<AudioTrackCandidate> audioTracks;
    /** Ephemeral CS3 interceptor/verifier ownership; never queue or persistence metadata. */
    public final Cs3PlaybackHooks cs3PlaybackHooks;

    public ProviderLinkCandidate(String id, String name, String source, String uri, String referer,
                                 int quality, String type, String extractorData,
                                 Map<String, String> headers) {
        this(id, name, source, uri, referer, quality, type, extractorData, headers,
                Collections.emptyList(), null, "", "");
    }

    public ProviderLinkCandidate(String id, String name, String source, String uri, String referer,
                                 int quality, String type, String extractorData,
                                 Map<String, String> headers, List<AudioTrackCandidate> audioTracks) {
        this(id, name, source, uri, referer, quality, type, extractorData, headers,
                audioTracks, null, "", "");
    }

    public ProviderLinkCandidate(String id, String name, String source, String uri, String referer,
                                 int quality, String type, String extractorData,
                                 Map<String, String> headers, List<AudioTrackCandidate> audioTracks,
                                 Cs3PlaybackHooks cs3PlaybackHooks) {
        this(id, name, source, uri, referer, quality, type, extractorData, headers,
                audioTracks, cs3PlaybackHooks, "", "");
    }

    public ProviderLinkCandidate(String id, String name, String source, String uri, String referer,
                                 int quality, String type, String extractorData,
                                 Map<String, String> headers, List<AudioTrackCandidate> audioTracks,
                                 Cs3PlaybackHooks cs3PlaybackHooks, String mimeType) {
        this(id, name, source, uri, referer, quality, type, extractorData, headers,
                audioTracks, cs3PlaybackHooks, mimeType, "");
    }

    public ProviderLinkCandidate(String id, String name, String source, String uri, String referer,
                                 int quality, String type, String extractorData,
                                 Map<String, String> headers, List<AudioTrackCandidate> audioTracks,
                                 Cs3PlaybackHooks cs3PlaybackHooks, String mimeType,
                                 String unavailableReason) {
        this.id = requiredWithin(id, "link id", 4096);
        this.name = boundedOptional(name, 512);
        this.source = boundedOptional(source, 512);
        String candidateUri = uri == null ? "" : uri.trim();
        boolean uriTooLong = candidateUri.length() > MAX_URI_CHARS;
        this.uri = uriTooLong ? "" : candidateUri;
        this.referer = boundedOptional(referer, 4096);
        this.quality = quality;
        this.type = boundedOptional(type, 128);
        this.mimeType = boundedOptional(mimeType, 255);
        this.extractorData = boundedOptional(extractorData, MAX_VERIFIER_CHARS);
        String suppliedIssue = boundedOptional(unavailableReason, 512);
        this.unavailableReason = !suppliedIssue.isEmpty() ? suppliedIssue
                : uriTooLong ? "The provider URL exceeds Orbit's URI safety limit." : "";
        this.headers = immutableHeaders(headers);
        this.audioTracks = immutableAudioTracks(audioTracks);
        this.cs3PlaybackHooks = cs3PlaybackHooks;
    }

    private static List<AudioTrackCandidate> immutableAudioTracks(List<AudioTrackCandidate> tracks) {
        if (tracks == null) return Collections.emptyList();
        int count = tracks.size();
        if (count < 0 || count > MAX_AUDIO_TRACKS) throw new IllegalArgumentException("Too many audio tracks.");
        if (count == 0) return Collections.emptyList();
        ArrayList<AudioTrackCandidate> copy = new ArrayList<>(count);
        // The count is checked before allocation; indexed access also prevents an
        // inconsistent plugin List iterator from yielding unlimited entries.
        for (int index = 0; index < count; index++) {
            AudioTrackCandidate track = tracks.get(index);
            if (track == null) throw new IllegalArgumentException("Audio track is required.");
            copy.add(track);
        }
        return Collections.unmodifiableList(copy);
    }

    /** URL and request headers for one separate CS3 audio file. Never use the URL as a display label. */
    public static final class AudioTrackCandidate {
        public final String id;
        public final String uri;
        public final Map<String, String> headers;

        public AudioTrackCandidate(String id, String uri, Map<String, String> headers) {
            this.id = boundedRequired(id, "audio track id", 160);
            this.uri = boundedRequired(uri, "audio track URI", 4096);
            if (headers != null && headers.size() > 64) {
                throw new IllegalArgumentException("Too many audio track headers.");
            }
            Map<String, String> copied = immutableHeaders(headers);
            for (Map.Entry<String, String> entry : copied.entrySet()) {
                if (entry.getValue().length() > 8192) {
                    throw new IllegalArgumentException("Audio track header is too long.");
                }
            }
            this.headers = copied;
        }
    }

    private static String boundedRequired(String value, String field, int maximum) {
        String result = requiredWithin(value, field, maximum);
        if (result.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Invalid " + field + ".");
        }
        return result;
    }

    private static String requiredWithin(String value, String field, int maximum) {
        if (value == null || value.length() > maximum) {
            throw new IllegalArgumentException("Invalid " + field + ".");
        }
        String cleaned = value.trim();
        if (cleaned.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return cleaned;
    }

    private static String boundedOptional(String value, int maximum) {
        if (value == null) return "";
        String cleaned = value.trim();
        return cleaned.length() <= maximum ? cleaned : cleaned.substring(0, maximum);
    }

    private static Map<String, String> immutableHeaders(Map<String, String> value) {
        return ProviderHeaderBudget.immutableSnapshot(value);
    }
}
