package com.auras.orbit.player;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.auras.orbit.providers.Cs3PlaybackHooks;
import com.auras.orbit.providers.ProviderLinkCandidate;
import com.auras.orbit.providers.ProviderLinkResult;
import com.auras.orbit.providers.ProviderSubtitleCandidate;
import com.auras.orbit.providers.ProviderOperationCoordinator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Versioned, in-process contract for handing resolved media playback to Orbit's player.
 * URLs and headers are deliberately never included in display labels or diagnostic messages.
 */
public final class PlaybackRequest {
    public static final int SCHEMA_VERSION = 1;
    public static final String EXTRA_REQUEST_KEY = "com.auras.orbit.player.extra.PLAYBACK_REQUEST_KEY_V1";
    private static final int MAX_JSON_CHARS = 262_144;
    private static final int MAX_SOURCES = ProviderLinkResult.MAX_LINKS;
    /** Bounds the in-process object graph; Intent handoff itself contains only a random key. */
    private static final long MAX_IN_PROCESS_SOURCE_METADATA_CHARS = 32L * 1024L * 1024L;
    private static final int MAX_SUBTITLES = 30;
    private static final int MAX_REJECTED_PROVIDER_SUBTITLES =
            ProviderLinkResult.MAX_SUBTITLES * 2;
    private static final int MAX_PROVIDER_URI_CHARS = 4096;
    public static final int MAX_AUDIO_TRACKS = 16;
    private static final int MAX_QUEUE_ITEMS = 50;
    private static final int MAX_HEADERS = 64;
    private static final long MAX_SKIP_TIME_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final String LIBRARY_ARTWORK_REFERENCE_PREFIX = "orbit-library-artwork:";
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,256}");
    private static final Pattern MIME_TYPE = Pattern.compile("[A-Za-z0-9!#$&^_.+-]{1,127}/[A-Za-z0-9!#$&^_.+-]{1,127}");
    private static final Set<String> SUBTITLE_MIMES;

    static {
        HashSet<String> types = new HashSet<>();
        types.add("text/vtt");
        types.add("application/x-subrip");
        types.add("application/ttml+xml");
        types.add("text/x-ssa");
        types.add(SubtitleFormat.UNKNOWN_MIME_TYPE);
        SUBTITLE_MIMES = Collections.unmodifiableSet(types);
    }

    public final String profileId;
    /** Random, in-process correlation key; it is not a playback URL or provider credential. */
    public final String observerKey;
    public final MediaMetadata media;
    public final EpisodeMetadata episode;
    /** Immutable snapshot replaced as a progressive provider publishes additional candidates. */
    public volatile List<Source> sources;
    public volatile String defaultSourceId;
    /** Requested in-episode resume position for the first source load. */
    public final long initialPositionMs;
    public final List<SkipSegment> skipSegments;
    public final List<QueueItem> queue;
    private final Map<Source, Cs3PlaybackHooks> providerHookSessions = new IdentityHashMap<>();
    private ProviderOperationCoordinator providerHookCoordinator;

    public PlaybackRequest(String profileId, MediaMetadata media, EpisodeMetadata episode,
            List<Source> sources, String defaultSourceId, List<SkipSegment> skipSegments,
            List<QueueItem> queue) {
        this(profileId, "", media, episode, sources, defaultSourceId, skipSegments, queue, 0L);
    }

    public PlaybackRequest(String profileId, MediaMetadata media, EpisodeMetadata episode,
            List<Source> sources, String defaultSourceId, List<SkipSegment> skipSegments,
            List<QueueItem> queue, long initialPositionMs) {
        this(profileId, "", media, episode, sources, defaultSourceId, skipSegments, queue,
                initialPositionMs);
    }

    public PlaybackRequest(String profileId, String observerKey, MediaMetadata media,
            EpisodeMetadata episode, List<Source> sources, String defaultSourceId,
            List<SkipSegment> skipSegments, List<QueueItem> queue) {
        this(profileId, observerKey, media, episode, sources, defaultSourceId, skipSegments,
                queue, 0L);
    }

    public PlaybackRequest(String profileId, String observerKey, MediaMetadata media,
            EpisodeMetadata episode, List<Source> sources, String defaultSourceId,
            List<SkipSegment> skipSegments, List<QueueItem> queue, long initialPositionMs) {
        this.profileId = identity(profileId, "profileId", 160);
        this.observerKey = observerKey == null || observerKey.isEmpty() ? "" : identity(observerKey, "observerKey", 80);
        if (media == null) throw new IllegalArgumentException("Media metadata is required.");
        this.media = media;
        this.episode = episode == null ? EpisodeMetadata.EMPTY : episode;
        this.sources = immutableSources(sources);
        if (this.sources.isEmpty()) throw new IllegalArgumentException("At least one source is required.");
        String selectedId = identity(defaultSourceId, "defaultSourceId", 160);
        if (!containsSource(this.sources, selectedId)) {
            throw new IllegalArgumentException("The default source must identify a source in this request.");
        }
        this.defaultSourceId = selectedId;
        this.initialPositionMs = Math.max(0L, initialPositionMs);
        this.skipSegments = immutableList(skipSegments, 200, "skipSegments");
        this.queue = immutableList(queue, MAX_QUEUE_ITEMS, "queue");
        requireUniqueIds(this.sources, this.skipSegments, this.queue);
    }

    public Source getDefaultSource() {
        for (Source source : sources) if (source.id.equals(defaultSourceId)) return source;
        return sources.get(0);
    }

    /** Create an explicit non-exported Activity handoff. */
    public Intent intent(Context context) {
        validateInProcessSourceMetadataBudget();
        return OrbitPlayerActivity.createPlaybackIntent(context, this);
    }

    /** Returns a copy tied to an observer previously registered in this app process. */
    public PlaybackRequest withObserverKey(String key) {
        return new PlaybackRequest(profileId, key, media, episode, sources, defaultSourceId,
                skipSegments, queue, initialPositionMs);
    }

    /** Remember the hook coordinator and prepare only the initially selected source. */
    public synchronized void activateProviderHooks(ProviderOperationCoordinator coordinator) {
        if (coordinator == null) throw new IllegalArgumentException("Provider coordinator is required.");
        providerHookCoordinator = coordinator;
        try {
            activateProviderHooksFor(getDefaultSource(), coordinator);
        } catch (RuntimeException error) {
            providerHookCoordinator = null;
            throw error;
        }
    }

    private Cs3PlaybackHooks activateProviderHooksFor(Source source,
            ProviderOperationCoordinator coordinator) {
        if (source == null || source.cs3PlaybackHooks == null) return null;
        Cs3PlaybackHooks existing = providerHookSessions.get(source);
        if (existing != null) return existing;
        Cs3PlaybackHooks session = source.cs3PlaybackHooks.fork();
        try {
            session.activate(coordinator);
            providerHookSessions.put(source, session);
            return session;
        } catch (RuntimeException error) {
            session.close();
            throw error;
        }
    }

    /**
     * Builds an in-memory request from provider results, using CloudStream's descending quality
     * priority. The stable sort retains callback order among equal-quality candidates.
     */
    public static PlaybackRequest fromProviderLinkResult(String profileId, MediaMetadata media,
            EpisodeMetadata episode, ProviderLinkResult result, String providerName,
            long initialPositionMs) {
        if (result == null) throw new IllegalArgumentException("Provider links are required.");
        List<Subtitle> providerSubtitles = normalizeProviderSubtitles(result.subtitles);
        ArrayList<Source> sources = providerSources(result, providerName, providerSubtitles);
        if (sources.isEmpty()) throw new IllegalArgumentException("No Orbit-compatible provider links were returned.");
        return new PlaybackRequest(profileId, media, episode, sources, sources.get(0).id,
                Collections.emptyList(), Collections.emptyList(), initialPositionMs);
    }

    /** Merges one full provider callback snapshot while preserving published Source identities. */
    public synchronized void mergeProviderLinkResult(ProviderLinkResult result, String providerName,
            ProviderOperationCoordinator coordinator) {
        if (result == null) throw new IllegalArgumentException("Provider links are required.");
        List<Subtitle> providerSubtitles = normalizeProviderSubtitles(result.subtitles);
        List<Source> previousSources = sources;
        LinkedHashMap<String, Source> previousById = new LinkedHashMap<>();
        for (Source source : previousSources) previousById.put(source.id, source);

        ArrayList<Source> incomingSources = providerSources(result, providerName, providerSubtitles);
        ArrayList<Source> mergedSources = new ArrayList<>();
        HashSet<String> includedIds = new HashSet<>();
        LinkedHashMap<Source, Source> pendingMetadataUpdates = new LinkedHashMap<>();
        for (Source incoming : incomingSources) {
            Source existing = previousById.get(incoming.id);
            Source selected = existing == null ? incoming : existing;
            if (existing != null) pendingMetadataUpdates.put(existing, incoming);
            if (includedIds.add(selected.id)) {
                if (mergedSources.size() >= MAX_SOURCES) {
                    throw new IllegalArgumentException("Merged provider sources are too large.");
                }
                mergedSources.add(selected);
            }
        }

        // Keep earlier safe candidates if a provider publishes a non-monotonic callback snapshot.
        for (Source existing : previousSources) {
            if (includedIds.add(existing.id)) {
                if (mergedSources.size() >= MAX_SOURCES) {
                    throw new IllegalArgumentException("Merged provider sources are too large.");
                }
                mergedSources.add(existing);
            }
        }

        // Validate the complete bounded union before changing any published Source objects.
        for (Map.Entry<Source, Source> update : pendingMetadataUpdates.entrySet()) {
            update.getKey().mergeProviderMetadata(update.getValue());
        }

        // CS3 subtitles are provider-level callbacks, so attach them to already-published links
        // even when the newest callback contains subtitles but no new link candidates.
        if (!providerSubtitles.isEmpty()) {
            for (Source source : mergedSources) {
                source.mergeProviderSubtitles(providerSubtitles(providerSubtitles));
            }
        }

        // Preserve the Source instances that playback and provider hooks already reference.
        sources = immutableSourceSnapshot(mergedSources);
        if (coordinator != null) activateProviderHooks(coordinator);
    }

    private static ArrayList<Source> providerSources(ProviderLinkResult result,
            String providerName, List<Subtitle> providerSubtitles) {
        ArrayList<IndexedProviderCandidate> candidates = new ArrayList<>();
        int index = 0;
        for (ProviderLinkCandidate candidate : result.links) {
            if (candidate != null) candidates.add(new IndexedProviderCandidate(candidate, index));
            index++;
        }
        Collections.sort(candidates, (left, right) -> Integer.compare(
                right.candidate.quality, left.candidate.quality));
        ArrayList<Source> sources = new ArrayList<>();
        for (IndexedProviderCandidate indexed : candidates) {
            if (sources.size() >= MAX_SOURCES) break;
            Source source = providerSource(indexed.candidate, indexed.callbackIndex,
                    providerSubtitles, providerName);
            if (source != null) sources.add(source);
        }
        int unsupportedSubtitleCount = countRejectedProviderSubtitles(result.subtitles,
                result.rejectedSubtitleCount);
        for (Source source : sources) source.unsupportedSubtitleCount = unsupportedSubtitleCount;
        return sources;
    }

    /** Counts only unsafe candidates and safe candidates beyond the accepted-track ceiling. */
    public static int countRejectedProviderSubtitles(List<ProviderSubtitleCandidate> candidates) {
        return countRejectedProviderSubtitles(candidates, 0);
    }

    /** Adds callbacks rejected before candidate construction to the bounded candidate diagnostics. */
    public static int countRejectedProviderSubtitles(List<ProviderSubtitleCandidate> candidates,
            int preMaterializationRejectedCount) {
        int skipped = Math.max(0, Math.min(ProviderLinkResult.MAX_SUBTITLES,
                preMaterializationRejectedCount));
        int accepted = 0;
        if (candidates != null) {
            for (ProviderSubtitleCandidate candidate : candidates) {
                ProviderSubtitleAssessment assessment = assessProviderSubtitle(candidate);
                if (!assessment.accepted || accepted >= MAX_SUBTITLES) {
                    skipped++;
                } else {
                    accepted++;
                }
            }
        }
        return Math.min(MAX_REJECTED_PROVIDER_SUBTITLES, skipped);
    }

    /** Compatibility alias for older in-process diagnostic callers. */
    @Deprecated
    static int countSkippedProviderSubtitles(List<ProviderSubtitleCandidate> candidates) {
        return countRejectedProviderSubtitles(candidates);
    }

    private static ProviderSubtitleAssessment assessProviderSubtitle(
            ProviderSubtitleCandidate candidate) {
        URI uri = candidate == null ? null : providerHttpUri(candidate.uri);
        boolean uriValid = uri != null;
        boolean headersValid = candidate != null
                && (candidate.headers == null || candidate.headers.size() <= MAX_HEADERS);
        String mimeType = uriValid && candidate != null
                ? SubtitleFormat.providerMimeType(uri.toASCIIString(), candidate.mimeType) : null;
        Map<String, String> headers = candidate == null || !headersValid
                ? Collections.emptyMap() : providerHeaders(candidate.headers, null);
        boolean transportSafe = uriValid && headersValid
                && !isUnsafeProviderSubtitleTransport(uri, headers);
        boolean accepted = SubtitleCandidatePolicy.isAccepted(uriValid, headersValid, transportSafe);
        return new ProviderSubtitleAssessment(uri, mimeType, headers, uriValid,
                headersValid, transportSafe, accepted);
    }

    private static boolean isUnsafeProviderSubtitleTransport(URI uri,
            Map<String, String> headers) {
        return uri != null && "http".equalsIgnoreCase(uri.getScheme())
                && ((headers != null && !headers.isEmpty()) || uri.getRawQuery() != null);
    }

    private static final class ProviderSubtitleAssessment {
        final URI uri;
        final String mimeType;
        final Map<String, String> headers;
        final boolean uriValid;
        final boolean headersValid;
        final boolean transportSafe;
        final boolean accepted;

        ProviderSubtitleAssessment(URI uri, String mimeType, Map<String, String> headers,
                boolean uriValid, boolean headersValid,
                boolean transportSafe, boolean accepted) {
            this.uri = uri;
            this.mimeType = mimeType;
            this.headers = headers;
            this.uriValid = uriValid;
            this.headersValid = headersValid;
            this.transportSafe = transportSafe;
            this.accepted = accepted;
        }
    }

    private static final class IndexedProviderCandidate {
        final ProviderLinkCandidate candidate;
        final int callbackIndex;
        IndexedProviderCandidate(ProviderLinkCandidate candidate, int callbackIndex) {
            this.candidate = candidate;
            this.callbackIndex = callbackIndex;
        }
    }

    private static Source providerSource(ProviderLinkCandidate candidate, int index,
            List<Subtitle> providerSubtitles, String providerName) {
        URI uri = providerHttpUri(candidate.uri);
        if (uri == null) return null;
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        String type = candidate.type == null ? "" : candidate.type.trim().toUpperCase(Locale.ROOT);
        String mimeType;
        String format;
        if ("M3U8".equals(type) && path.endsWith(".m3u8")) {
            mimeType = "application/x-mpegurl";
            format = "HLS";
        } else if ("DASH".equals(type) && path.endsWith(".mpd")) {
            mimeType = "application/dash+xml";
            format = "DASH";
        } else if ("VIDEO".equals(type)) {
            if (path.endsWith(".mp4") || path.endsWith(".m4v")) {
                mimeType = "video/mp4";
                format = path.endsWith(".m4v") ? "M4V" : "MP4";
            } else if (path.endsWith(".mkv")) {
                mimeType = "video/x-matroska";
                format = "Matroska";
            } else if (path.endsWith(".webm")) {
                mimeType = "video/webm";
                format = "WebM";
            } else if (path.endsWith(".ts")) {
                mimeType = "video/mp2t";
                format = "MPEG-TS";
            } else if (path.endsWith(".mpg") || path.endsWith(".mpeg")) {
                mimeType = "video/mpeg";
                format = "MPEG";
            } else {
                return null;
            }
        } else {
            return null;
        }

        Map<String, String> sourceHeaders = providerHeaders(candidate.headers, candidate.referer);
        if ("http".equalsIgnoreCase(uri.getScheme())
                && (!sourceHeaders.isEmpty() || uri.getRawQuery() != null)) return null;
        List<Subtitle> subtitles = providerSubtitles(providerSubtitles);
        List<AudioTrack> audioTracks = providerAudioTracks(candidate.audioTracks);
        String label = providerSourceLabel(providerName, candidate.name, candidate.source,
                candidate.quality, format);
        try {
            return new Source("provider-source-" + index, label, uri.toASCIIString(), mimeType,
                    sourceHeaders, subtitles, audioTracks, candidate.cs3PlaybackHooks);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Normalizes safe provider subtitle callbacks for either CS3 playback entry point. */
    public static List<Subtitle> normalizeProviderSubtitles(
            List<ProviderSubtitleCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        ArrayList<Subtitle> subtitles = new ArrayList<>();
        int candidateIndex = 0;
        for (ProviderSubtitleCandidate candidate : candidates) {
            candidateIndex++;
            ProviderSubtitleAssessment assessment = assessProviderSubtitle(candidate);
            if (!assessment.accepted || subtitles.size() >= MAX_SUBTITLES) continue;
            String language = safeSubtitleLanguage(candidate.language);
            String label = language.isEmpty() ? "Subtitle " + candidateIndex : language;
            try {
                Subtitle subtitle = new Subtitle("provider-subtitle-" + candidateIndex, label,
                        language, assessment.uri.toASCIIString(), assessment.mimeType, 0,
                        assessment.headers);
                subtitles.add(subtitle);
            } catch (RuntimeException ignored) {
                // Invalid optional subtitle metadata must not discard a playable video source.
            }
        }
        return subtitles.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(subtitles);
    }

    private static List<Subtitle> providerSubtitles(List<Subtitle> candidates) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        ArrayList<Subtitle> subtitles = new ArrayList<>();
        for (Subtitle candidate : candidates) {
            if (subtitles.size() >= MAX_SUBTITLES) break;
            subtitles.add(candidate);
        }
        return subtitles.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(subtitles);
    }

    private static List<AudioTrack> providerAudioTracks(
            List<ProviderLinkCandidate.AudioTrackCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        ArrayList<AudioTrack> tracks = new ArrayList<>();
        for (ProviderLinkCandidate.AudioTrackCandidate candidate : candidates) {
            if (tracks.size() >= MAX_AUDIO_TRACKS) break;
            if (candidate == null || candidate.headers != null && candidate.headers.size() > MAX_HEADERS) continue;
            URI uri = providerHttpUri(candidate.uri);
            if (uri == null) continue;
            try {
                tracks.add(new AudioTrack(candidate.id, uri.toASCIIString(),
                        providerHeaders(candidate.headers, null)));
            } catch (RuntimeException ignored) {
                // Invalid optional audio metadata must not discard a playable video source.
            }
        }
        return tracks.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(tracks);
    }

    private static Map<String, String> providerHeaders(Map<String, String> candidateHeaders,
            String referer) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        boolean safeReferer = providerHttpUri(referer) != null;
        int maximum = safeReferer ? MAX_HEADERS - 1 : MAX_HEADERS;
        if (candidateHeaders != null) {
            for (Map.Entry<String, String> entry : candidateHeaders.entrySet()) {
                if (headers.size() >= maximum || entry == null) break;
                String name = entry.getKey();
                String value = entry.getValue();
                if (!safeProviderHeaderName(name) || !safeProviderHeaderValue(value)
                        || hopByHopHeader(name)) continue;
                if ("referer".equalsIgnoreCase(name) && providerHttpUri(value) == null) continue;
                putProviderHeader(headers, name, value);
            }
        }
        if (safeReferer) putProviderHeader(headers, "Referer", referer.trim());
        return headers.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(headers);
    }

    private static URI providerHttpUri(String value) {
        if (value == null || value.length() > MAX_PROVIDER_URI_CHARS || value.trim().isEmpty()
                || value.contains("\\")
                || value.chars().anyMatch(Character::isWhitespace)) return null;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getUserInfo() != null || uri.getHost() == null
                    || uri.getHost().isEmpty() || uri.getPort() < -1 || uri.getPort() > 65535
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return null;
            return uri;
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private static boolean safeProviderHeaderName(String name) {
        if (name == null || name.trim().isEmpty() || name.length() > 256) return false;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (!(ch >= 'A' && ch <= 'Z') && !(ch >= 'a' && ch <= 'z')
                    && !(ch >= '0' && ch <= '9') && "!#$%&'*+-.^_`|~".indexOf(ch) < 0) return false;
        }
        return true;
    }

    private static boolean safeProviderHeaderValue(String value) {
        if (value == null || value.length() > 8192) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\r' || ch == '\n' || ch == '\u0000'
                    || Character.isISOControl(ch) && ch != '\t') return false;
        }
        return true;
    }

    private static boolean hopByHopHeader(String name) {
        return name.regionMatches(true, 0, "proxy-", 0, 6)
                || "expect".equalsIgnoreCase(name) || "proxy-connection".equalsIgnoreCase(name)
                || "connection".equalsIgnoreCase(name) || "host".equalsIgnoreCase(name)
                || "content-length".equalsIgnoreCase(name) || "transfer-encoding".equalsIgnoreCase(name)
                || "keep-alive".equalsIgnoreCase(name) || "te".equalsIgnoreCase(name)
                || "trailer".equalsIgnoreCase(name) || "upgrade".equalsIgnoreCase(name);
    }

    private static void putProviderHeader(Map<String, String> headers, String name, String value) {
        String existing = null;
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) { existing = key; break; }
        }
        if (existing != null) headers.remove(existing);
        headers.put(name, value);
    }

    private static String safeSubtitleLanguage(String language) {
        if (language == null || language.isEmpty() || language.length() > 24) return "";
        for (int i = 0; i < language.length(); i++) {
            char ch = language.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_')) return "";
        }
        return language.trim();
    }

    /** Builds a user-facing source label while retaining CloudStream's per-link names. */
    public static String providerSourceLabel(String providerName, String linkName, String linkSource,
            int quality, String format) {
        ArrayList<String> parts = new ArrayList<>(3);
        appendProviderLabelPart(parts, linkName);
        appendProviderLabelPart(parts, linkSource);
        appendProviderLabelPart(parts, providerName);
        if (parts.isEmpty()) parts.add("Provider");

        StringBuilder label = new StringBuilder();
        for (String part : parts) {
            if (label.length() > 0) label.append(" · ");
            label.append(part);
        }
        if (quality > 0) label.append(" · ").append(quality).append('p');
        String safeFormat = safeProviderLabelPart(format);
        if (!safeFormat.isEmpty()) label.append(" · ").append(safeFormat);
        return label.toString();
    }

    private static void appendProviderLabelPart(List<String> parts, String value) {
        String part = safeProviderLabelPart(value);
        if (part.isEmpty()) return;
        for (String existing : parts) {
            if (existing.equalsIgnoreCase(part)) return;
        }
        parts.add(part);
    }

    private static String safeProviderLabelPart(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder cleaned = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                pendingSpace = cleaned.length() > 0;
            } else {
                if (pendingSpace) cleaned.append(' ');
                cleaned.append(character);
                pendingSpace = false;
            }
        }
        String result = cleaned.toString().trim();
        String lower = result.toLowerCase(Locale.ROOT);
        if (result.isEmpty() || lower.contains("://") || lower.contains("magnet:")) return "";
        int codePoints = result.codePointCount(0, result.length());
        if (codePoints > 48) {
            result = result.substring(0, result.offsetByCodePoints(0, 47)) + "…";
        }
        return result;
    }

    /** Returns the runtime owner for a source, or null for a native/non-CS3 source. */
    public synchronized Cs3PlaybackHooks providerHooksFor(Source source) {
        Cs3PlaybackHooks existing = providerHookSessions.get(source);
        if (existing != null || source == null || providerHookCoordinator == null) return existing;
        try {
            return activateProviderHooksFor(source, providerHookCoordinator);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** Releases all plugin references and verifier jobs when playback ends or its handoff expires. */
    public void closeProviderHooks() {
        List<Cs3PlaybackHooks> sessions;
        synchronized (this) {
            sessions = new ArrayList<>(providerHookSessions.values());
            providerHookSessions.clear();
            providerHookCoordinator = null;
            for (Source source : sources) source.cs3PlaybackHooks = null;
        }
        for (Cs3PlaybackHooks session : sessions) session.close();
    }

    /** Parse only the versioned string extra. Invalid requests return null and disclose no URL data. */
    public static PlaybackRequest fromIntent(Intent intent) {
        if (intent == null) return null;
        try {
            String requestKey = intent.getStringExtra(EXTRA_REQUEST_KEY);
            if (requestKey == null || requestKey.length() > 80) return null;
            return OrbitPlayerActivity.consumePlaybackRequest(requestKey);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Internal serialization for aggregate-size validation only; never log or forward this string. */
    private String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("profileId", profileId);
            root.put("observerKey", observerKey);
            root.put("media", media.toJson());
            root.put("episode", episode.toJson());
            root.put("sources", sourcesToJson(sources));
            root.put("defaultSourceId", defaultSourceId);
            root.put("initialPositionMs", initialPositionMs);
            JSONArray skips = new JSONArray();
            for (SkipSegment segment : skipSegments) skips.put(segment.toJson());
            root.put("skipSegments", skips);
            JSONArray next = new JSONArray();
            for (QueueItem item : queue) next.put(item.toJson());
            root.put("queue", next);
            String json = root.toString();
            if (json.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("Playback request is too large.");
            return json;
        } catch (JSONException impossible) {
            throw new IllegalStateException("Playback request could not be encoded.");
        }
    }

    private static PlaybackRequest fromJson(String json) throws JSONException {
        if (json == null || json.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("Invalid playback request.");
        JSONObject root = new JSONObject(json);
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported playback request version.");
        }
        List<Source> sources = parseSources(root.optJSONArray("sources"));
        List<SkipSegment> skips = new ArrayList<>();
        JSONArray skipArray = root.optJSONArray("skipSegments");
        if (skipArray != null) {
            if (skipArray.length() > 200) throw new IllegalArgumentException("Too many skip segments.");
            for (int i = 0; i < skipArray.length(); i++) skips.add(SkipSegment.fromJson(skipArray.getJSONObject(i)));
        }
        List<QueueItem> queue = new ArrayList<>();
        JSONArray queueArray = root.optJSONArray("queue");
        if (queueArray != null) {
            if (queueArray.length() > MAX_QUEUE_ITEMS) throw new IllegalArgumentException("Queue is too large.");
            for (int i = 0; i < queueArray.length(); i++) queue.add(QueueItem.fromJson(queueArray.getJSONObject(i)));
        }
        return new PlaybackRequest(root.getString("profileId"), root.optString("observerKey", ""),
                MediaMetadata.fromJson(root.getJSONObject("media")),
                EpisodeMetadata.fromJson(root.optJSONObject("episode")), sources,
                root.getString("defaultSourceId"), skips, queue,
                Math.max(0L, root.optLong("initialPositionMs", 0L)));
    }

    public PlaybackRequest startNext(QueueItem item) {
        if (item == null || queue.isEmpty() || !queue.get(0).id.equals(item.id)) {
            throw new IllegalArgumentException("The next episode is not at the head of the queue.");
        }
        List<QueueItem> remaining = new ArrayList<>(queue.subList(1, queue.size()));
        return new PlaybackRequest(profileId, observerKey, item.media, item.episode, item.sources,
                item.defaultSourceId, item.skipSegments, remaining);
    }

    private static JSONArray sourcesToJson(List<Source> sources) throws JSONException {
        JSONArray result = new JSONArray();
        for (Source source : sources) result.put(source.toJson());
        return result;
    }

    private static List<Source> parseSources(JSONArray array) throws JSONException {
        if (array == null || array.length() < 1 || array.length() > MAX_SOURCES) {
            throw new IllegalArgumentException("Invalid source list.");
        }
        List<Source> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(Source.fromJson(array.getJSONObject(i)));
        return result;
    }

    private static boolean containsSource(List<Source> sources, String id) {
        for (Source source : sources) if (source.id.equals(id)) return true;
        return false;
    }

    private static void requireUniqueIds(List<Source> sources, List<SkipSegment> skips, List<QueueItem> queue) {
        ensureUnique(sources, "source id");
        ensureUnique(skips, "skip segment id");
        ensureUnique(queue, "queue item id");
    }

    private static void ensureUnique(List<? extends HasId> items, String label) {
        Set<String> ids = new HashSet<>();
        for (HasId item : items) if (!ids.add(item.id())) throw new IllegalArgumentException("Duplicate " + label + ".");
    }

    private static <T> List<T> immutableList(List<T> input, int maximum, String label) {
        if (input == null) return Collections.emptyList();
        // A caller-provided List may report a small size but expose an unbounded
        // iterator or a pathological toArray(). Snapshot the size once and
        // perform exactly that many indexed reads after validating the budget.
        int count = input.size();
        if (count < 0 || count > maximum) throw new IllegalArgumentException(label + " is too large.");
        if (count == 0) return Collections.emptyList();
        ArrayList<T> copy = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            T item = input.get(index);
            if (item == null) throw new IllegalArgumentException(label + " contains an empty entry.");
            copy.add(item);
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<Source> immutableSources(List<Source> input) {
        if (input == null) return Collections.emptyList();
        int count = input.size();
        if (count < 0 || count > MAX_SOURCES) throw new IllegalArgumentException("sources is too large.");
        if (count == 0) return Collections.emptyList();
        ArrayList<Source> copy = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Source source = input.get(index);
            if (source == null) throw new IllegalArgumentException("sources contains an empty entry.");
            copy.add(new Source(source.id, source.label, source.uri, source.mimeType,
                    source.headers, source.subtitles, source.audioTracks, source.cs3PlaybackHooks));
            copy.get(copy.size() - 1).unsupportedSubtitleCount = source.unsupportedSubtitleCount;
        }
        return Collections.unmodifiableList(copy);
    }

    /** Bounded immutable list copy that deliberately preserves published Source identities. */
    private static List<Source> immutableSourceSnapshot(List<Source> input) {
        if (input == null) return Collections.emptyList();
        int count = input.size();
        if (count > MAX_SOURCES) throw new IllegalArgumentException("sources is too large.");
        if (count == 0) return Collections.emptyList();
        ArrayList<Source> copy = new ArrayList<>(count);
        for (Source source : input) {
            if (source == null) throw new IllegalArgumentException("sources contains an empty entry.");
            copy.add(source);
        }
        return Collections.unmodifiableList(copy);
    }

    /**
     * The player handoff stays key-only, so do not serialize every link to test its size.
     * All source fields are individually validated and capped; this aggregate budget prevents
     * a large combination of sources, headers, subtitles, and audio tracks from retaining an
     * unreasonable in-process request graph. Provider-level subtitles shared between sources
     * are counted once because the request stores those immutable objects once.
     */
    private void validateInProcessSourceMetadataBudget() {
        long[] total = {0L};
        IdentityHashMap<Source, Boolean> countedSources = new IdentityHashMap<>();
        IdentityHashMap<Subtitle, Boolean> countedSubtitles = new IdentityHashMap<>();
        IdentityHashMap<AudioTrack, Boolean> countedAudioTracks = new IdentityHashMap<>();
        for (Source source : sources) {
            addSourceMetadataBudget(source, total, countedSources, countedSubtitles,
                    countedAudioTracks);
        }
        for (QueueItem item : queue) {
            for (Source source : item.sources) {
                addSourceMetadataBudget(source, total, countedSources, countedSubtitles,
                        countedAudioTracks);
            }
        }
    }

    private static void addSourceMetadataBudget(Source source, long[] total,
            IdentityHashMap<Source, Boolean> countedSources,
            IdentityHashMap<Subtitle, Boolean> countedSubtitles,
            IdentityHashMap<AudioTrack, Boolean> countedAudioTracks) {
        if (source == null || countedSources.put(source, Boolean.TRUE) != null) return;
        addMetadataBudget(total, 256L); // conservative allowance for the source and its list entries
        addMetadataTextBudget(total, source.id);
        addMetadataTextBudget(total, source.label);
        addMetadataTextBudget(total, source.uri);
        addMetadataTextBudget(total, source.mimeType);
        addHeaderMetadataBudget(total, source.headers);
        for (Subtitle subtitle : source.subtitles) {
            if (subtitle == null || countedSubtitles.put(subtitle, Boolean.TRUE) != null) continue;
            addMetadataBudget(total, 128L);
            addMetadataTextBudget(total, subtitle.id);
            addMetadataTextBudget(total, subtitle.label);
            addMetadataTextBudget(total, subtitle.language);
            addMetadataTextBudget(total, subtitle.uri);
            addMetadataTextBudget(total, subtitle.mimeType);
            addHeaderMetadataBudget(total, subtitle.headers);
        }
        for (AudioTrack track : source.audioTracks) {
            if (track == null || countedAudioTracks.put(track, Boolean.TRUE) != null) continue;
            addMetadataBudget(total, 96L);
            addMetadataTextBudget(total, track.id);
            addMetadataTextBudget(total, track.uri);
            addHeaderMetadataBudget(total, track.headers);
        }
    }

    private static void addHeaderMetadataBudget(long[] total, Map<String, String> headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            addMetadataBudget(total, 32L);
            addMetadataTextBudget(total, entry.getKey());
            addMetadataTextBudget(total, entry.getValue());
        }
    }

    private static void addMetadataTextBudget(long[] total, String value) {
        if (value != null) addMetadataBudget(total, value.length());
    }

    private static void addMetadataBudget(long[] total, long amount) {
        total[0] += amount;
        if (total[0] > MAX_IN_PROCESS_SOURCE_METADATA_CHARS) {
            throw new IllegalArgumentException("Playback source metadata is too large.");
        }
    }

    private static String text(String value, String label, int max, boolean required) {
        String clean = value == null ? "" : value.trim();
        if (required && clean.isEmpty()) throw new IllegalArgumentException(label + " is required.");
        if (clean.length() > max) throw new IllegalArgumentException(label + " is too long.");
        for (int i = 0; i < clean.length(); i++) {
            char ch = clean.charAt(i);
            if (ch == '\u0000' || (Character.isISOControl(ch) && ch != '\n' && ch != '\t')) {
                throw new IllegalArgumentException(label + " contains an invalid character.");
            }
        }
        return clean;
    }

    private static String required(String value, String label, int max) {
        return text(value, label, max, true);
    }

    private static String identity(String value, String label, int max) {
        String result = required(value, label, max);
        for (int i = 0; i < result.length(); i++) {
            if (Character.isISOControl(result.charAt(i))) {
                throw new IllegalArgumentException(label + " contains an invalid character.");
            }
        }
        return result;
    }

    private static String optional(String value, String label, int max) {
        return text(value, label, max, false);
    }

    private static String mime(String value, boolean subtitle) {
        String result = required(value, "mimeType", 255).toLowerCase(Locale.ROOT);
        if (!MIME_TYPE.matcher(result).matches() || (subtitle && !SUBTITLE_MIMES.contains(result))) {
            throw new IllegalArgumentException("Unsupported media type.");
        }
        return result;
    }

    private static Uri safeUri(String value) {
        String source = required(value, "uri", 4096);
        if (source.contains("\\") || source.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Invalid media URI.");
        }
        try {
            URI parsed = new URI(source);
            String scheme = parsed.getScheme();
            if (scheme == null || parsed.getUserInfo() != null) throw new IllegalArgumentException("Invalid media URI.");
            String lower = scheme.toLowerCase(Locale.ROOT);
            if ("http".equals(lower) || "https".equals(lower)) {
                if (parsed.getHost() == null || parsed.getHost().isEmpty()
                        || (parsed.getPort() != -1 && (parsed.getPort() < 1 || parsed.getPort() > 65535))) {
                    throw new IllegalArgumentException("Invalid media URI.");
                }
            } else if ("orbit-library-artwork".equals(lower)) {
                if (!isSafeLibraryArtworkReference(source)) {
                    throw new IllegalArgumentException("Invalid artwork reference.");
                }
            } else if (!("content".equals(lower) || "file".equals(lower))) {
                throw new IllegalArgumentException("Unsupported media URI scheme.");
            }
            if ("content".equals(lower) && parsed.getAuthority() == null) throw new IllegalArgumentException("Invalid media URI.");
            return Uri.parse(parsed.toASCIIString());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid media URI.");
        }
    }

    /** True only for an opaque, profile-scoped retained-artwork cache reference. */
    public static boolean isSafeLibraryArtworkReference(String value) {
        if (value == null || !value.startsWith(LIBRARY_ARTWORK_REFERENCE_PREFIX)) return false;
        String encoded = value.substring(LIBRARY_ARTWORK_REFERENCE_PREFIX.length());
        if (encoded.length() != 129 || encoded.charAt(64) != ':') return false;
        for (int index = 0; index < encoded.length(); index++) {
            if (index == 64) continue;
            char current = encoded.charAt(index);
            if (!((current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'f'))) return false;
        }
        return true;
    }

    private static Map<String, String> headers(Map<String, String> input) {
        if (input == null || input.isEmpty()) return Collections.emptyMap();
        if (input.size() > MAX_HEADERS) throw new IllegalArgumentException("Too many request headers.");
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> lowerNames = new HashSet<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            String name = required(entry.getKey(), "header name", 256);
            String value = entry.getValue();
            if (value == null || value.length() > 8192) throw new IllegalArgumentException("Invalid request header.");
            if (!HEADER_NAME.matcher(name).matches() || !lowerNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Invalid request header.");
            }
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == '\r' || ch == '\n' || ch == '\u0000' || (Character.isISOControl(ch) && ch != '\t')) {
                    throw new IllegalArgumentException("Invalid request header.");
                }
            }
            result.put(name, value);
        }
        return Collections.unmodifiableMap(result);
    }

    private interface HasId { String id(); }

    public static final class MediaMetadata {
        public final String stableId, providerId, title, mediaType, year, summary, posterUri,
                creator, resumeArtworkRef;
        public MediaMetadata(String stableId, String providerId, String title, String mediaType,
                String year, String summary, String posterUri) {
            this(stableId, providerId, title, mediaType, year, summary, posterUri, "", "");
        }
        public MediaMetadata(String stableId, String providerId, String title, String mediaType,
                String year, String summary, String posterUri, String creator) {
            this(stableId, providerId, title, mediaType, year, summary, posterUri, creator, "");
        }
        public MediaMetadata(String stableId, String providerId, String title, String mediaType,
                String year, String summary, String posterUri, String creator,
                String resumeArtworkRef) {
            this.stableId = identity(stableId, "stableId", 256);
            this.providerId = optional(providerId, "providerId", 256);
            this.title = required(title, "title", 512);
            this.mediaType = required(mediaType, "mediaType", 32);
            this.year = optional(year, "year", 20);
            this.summary = optional(summary, "summary", 5000);
            this.posterUri = posterUri == null || posterUri.trim().isEmpty() ? "" : safeUri(posterUri).toString();
            this.creator = optional(creator, "creator", 512);
            String retainedArtwork = optional(resumeArtworkRef, "resume artwork reference", 200);
            if (!retainedArtwork.isEmpty() && !isSafeLibraryArtworkReference(retainedArtwork)) {
                throw new IllegalArgumentException("Resume artwork must use a retained local reference.");
            }
            this.resumeArtworkRef = retainedArtwork;
        }
        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject(); o.put("stableId", stableId); o.put("providerId", providerId);
            o.put("title", title); o.put("mediaType", mediaType); o.put("year", year);
            o.put("summary", summary); o.put("posterUri", posterUri); o.put("creator", creator);
            o.put("resumeArtworkRef", resumeArtworkRef); return o;
        }
        static MediaMetadata fromJson(JSONObject o) throws JSONException {
            return new MediaMetadata(o.getString("stableId"), o.optString("providerId", ""),
                    o.getString("title"), o.getString("mediaType"), o.optString("year", ""),
                    o.optString("summary", ""), o.optString("posterUri", ""),
                    o.optString("creator", ""), o.optString("resumeArtworkRef", ""));
        }
    }

    public static final class EpisodeMetadata {
        static final EpisodeMetadata EMPTY = new EpisodeMetadata("", "", -1, -1);
        public final String id, title, artworkRef;
        /** Season and episode numbers are conventional one-based values; -1 means unknown. */
        public final int season, number;
        public EpisodeMetadata(String id, String title, int season, int number) {
            this(id, title, season, number, "");
        }
        public EpisodeMetadata(String id, String title, int season, int number, String artworkRef) {
            this.id = id == null || id.isEmpty() ? "" : identity(id, "episodeId", 256);
            this.title = optional(title, "episodeTitle", 512);
            if (season < -1 || season > 999 || number < -1 || number > 9999) {
                throw new IllegalArgumentException("Episode numbering is outside supported bounds.");
            }
            this.season = season; this.number = number;
            String retainedArtwork = optional(artworkRef, "episode artwork reference", 200);
            if (!retainedArtwork.isEmpty() && !isSafeLibraryArtworkReference(retainedArtwork)) {
                throw new IllegalArgumentException("Episode artwork must use a retained local reference.");
            }
            this.artworkRef = retainedArtwork;
        }
        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject(); o.put("id", id); o.put("title", title);
            o.put("season", season); o.put("number", number);
            o.put("artworkRef", artworkRef); return o;
        }
        static EpisodeMetadata fromJson(JSONObject o) throws JSONException {
            if (o == null) return EMPTY;
            return new EpisodeMetadata(o.optString("id", ""), o.optString("title", ""),
                    o.optInt("season", -1), o.optInt("number", -1),
                    o.optString("artworkRef", ""));
        }
    }

    public static final class Subtitle implements HasId {
        public final String id, label, language, uri, mimeType;
        public final Map<String, String> headers;
        public final int selectionFlags;
        public Subtitle(String id, String label, String language, String uri, String mimeType, int selectionFlags) {
            this(id, label, language, uri, mimeType, selectionFlags, Collections.emptyMap());
        }
        public Subtitle(String id, String label, String language, String uri, String mimeType,
                int selectionFlags, Map<String, String> headers) {
            this.id = identity(id, "subtitle id", 160);
            this.label = required(label, "subtitle label", 200);
            this.language = optional(language, "subtitle language", 64);
            this.uri = safeUri(uri).toString();
            this.mimeType = mime(mimeType, true);
            this.headers = PlaybackRequest.headers(headers);
            if ((selectionFlags & ~7) != 0) throw new IllegalArgumentException("Invalid subtitle flags.");
            this.selectionFlags = selectionFlags;
        }
        @Override public String id() { return id; }
        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject(); o.put("id", id); o.put("label", label);
            o.put("language", language); o.put("uri", uri); o.put("mimeType", mimeType);
            o.put("selectionFlags", selectionFlags);
            JSONObject h = new JSONObject();
            for (Map.Entry<String, String> entry : headers.entrySet()) h.put(entry.getKey(), entry.getValue());
            o.put("headers", h);
            return o;
        }
        static Subtitle fromJson(JSONObject o) throws JSONException {
            Map<String, String> headers = new LinkedHashMap<>();
            JSONObject h = o.optJSONObject("headers");
            if (h != null) {
                if (h.length() > MAX_HEADERS) throw new IllegalArgumentException("Too many request headers.");
                JSONArray names = h.names();
                if (names != null) for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i);
                    headers.put(name, h.getString(name));
                }
            }
            return new Subtitle(o.getString("id"), o.getString("label"), o.optString("language", ""),
                    o.getString("uri"), o.getString("mimeType"), o.optInt("selectionFlags", 0), headers);
        }
    }

    /** A separate audio file supplied alongside a CS3 video link. URLs are never displayed. */
    public static final class AudioTrack implements HasId {
        public final String id;
        public final String uri;
        public final Map<String, String> headers;

        public AudioTrack(String id, String uri, Map<String, String> headers) {
            this.id = identity(id, "audio track id", 160);
            this.uri = safeUri(uri).toString();
            this.headers = PlaybackRequest.headers(headers);
        }

        @Override public String id() { return id; }
        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("uri", uri);
            JSONObject h = new JSONObject();
            for (Map.Entry<String, String> entry : headers.entrySet()) h.put(entry.getKey(), entry.getValue());
            o.put("headers", h);
            return o;
        }

        static AudioTrack fromJson(JSONObject o) throws JSONException {
            Map<String, String> headers = new LinkedHashMap<>();
            JSONObject h = o.optJSONObject("headers");
            if (h != null) {
                if (h.length() > MAX_HEADERS) throw new IllegalArgumentException("Too many request headers.");
                JSONArray names = h.names();
                if (names != null) for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i);
                    headers.put(name, h.getString(name));
                }
            }
            return new AudioTrack(o.getString("id"), o.getString("uri"), headers);
        }
    }

    /** Subtitle requests keep their own headers, which playback scopes to the subtitle resource. */
    public static final class Source implements HasId {
        public final String id, uri, mimeType;
        public volatile String label;
        /** Unsupported provider subtitle candidates for a visible player diagnostic. */
        public volatile int unsupportedSubtitleCount;
        public final Map<String, String> headers;
        public volatile List<Subtitle> subtitles;
        public volatile List<AudioTrack> audioTracks;
        /** Ephemeral provider hook seed; it is intentionally excluded from the request JSON. */
        public Cs3PlaybackHooks cs3PlaybackHooks;
        public Source(String id, String label, String uri, String mimeType,
                Map<String, String> headers, List<Subtitle> subtitles) {
            this(id, label, uri, mimeType, headers, subtitles, Collections.emptyList(), null);
        }
        public Source(String id, String label, String uri, String mimeType,
                Map<String, String> headers, List<Subtitle> subtitles, List<AudioTrack> audioTracks) {
            this(id, label, uri, mimeType, headers, subtitles, audioTracks, null);
        }
        public Source(String id, String label, String uri, String mimeType,
                Map<String, String> headers, List<Subtitle> subtitles, List<AudioTrack> audioTracks,
                Cs3PlaybackHooks cs3PlaybackHooks) {
            this.id = identity(id, "source id", 160);
            this.label = required(label, "source label", 200);
            this.uri = safeUri(uri).toString();
            this.mimeType = mimeType == null || mimeType.trim().isEmpty() ? "" : mime(mimeType, false);
            this.headers = PlaybackRequest.headers(headers);
            this.subtitles = immutableList(subtitles, MAX_SUBTITLES, "subtitles");
            this.audioTracks = immutableList(audioTracks, MAX_AUDIO_TRACKS, "audioTracks");
            this.cs3PlaybackHooks = cs3PlaybackHooks;
            ensureUnique(this.subtitles, "subtitle id");
            ensureUnique(this.audioTracks, "audio track id");
        }
        @Override public String id() { return id; }
        private synchronized void mergeProviderMetadata(Source incoming) {
            if (incoming == null) return;
            if (incoming.label != null && !incoming.label.isEmpty()) label = incoming.label;
            unsupportedSubtitleCount = Math.max(unsupportedSubtitleCount,
                    incoming.unsupportedSubtitleCount);
            mergeProviderSubtitles(incoming.subtitles);
            mergeProviderAudioTracks(incoming.audioTracks);
            if (cs3PlaybackHooks == null) cs3PlaybackHooks = incoming.cs3PlaybackHooks;
        }
        private synchronized void mergeProviderSubtitles(List<Subtitle> incoming) {
            if (incoming == null || incoming.isEmpty()) return;
            LinkedHashMap<String, Subtitle> merged = new LinkedHashMap<>();
            for (Subtitle subtitle : subtitles) merged.put(subtitle.id, subtitle);
            for (Subtitle subtitle : incoming) {
                if (merged.size() >= MAX_SUBTITLES) break;
                if (subtitle != null) merged.putIfAbsent(subtitle.id, subtitle);
            }
            subtitles = immutableList(new ArrayList<>(merged.values()), MAX_SUBTITLES, "subtitles");
        }
        public synchronized void addSubtitles(List<Subtitle> incoming) {
            mergeProviderSubtitles(incoming);
        }
        private synchronized void mergeProviderAudioTracks(List<AudioTrack> incoming) {
            if (incoming == null || incoming.isEmpty()) return;
            LinkedHashMap<String, AudioTrack> merged = new LinkedHashMap<>();
            for (AudioTrack track : audioTracks) merged.put(track.id, track);
            for (AudioTrack track : incoming) {
                if (merged.size() >= MAX_AUDIO_TRACKS) break;
                if (track != null) merged.putIfAbsent(track.id, track);
            }
            audioTracks = immutableList(new ArrayList<>(merged.values()), MAX_AUDIO_TRACKS,
                    "audioTracks");
        }
        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject(); o.put("id", id); o.put("label", label);
            o.put("uri", uri); o.put("mimeType", mimeType);
            JSONObject h = new JSONObject(); for (Map.Entry<String, String> entry : headers.entrySet()) h.put(entry.getKey(), entry.getValue());
            o.put("headers", h); JSONArray caps = new JSONArray();
            for (Subtitle subtitle : subtitles) caps.put(subtitle.toJson()); o.put("subtitles", caps);
            o.put("unsupportedSubtitleCount", unsupportedSubtitleCount);
            JSONArray audio = new JSONArray();
            for (AudioTrack track : audioTracks) audio.put(track.toJson());
            o.put("audioTracks", audio);
            return o;
        }
        static Source fromJson(JSONObject o) throws JSONException {
            Map<String, String> headers = new LinkedHashMap<>();
            JSONObject h = o.optJSONObject("headers");
            if (h != null) {
                if (h.length() > MAX_HEADERS) throw new IllegalArgumentException("Too many request headers.");
                JSONArray names = h.names(); if (names != null) for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i); headers.put(name, h.getString(name));
                }
            }
            List<Subtitle> subtitles = new ArrayList<>(); JSONArray a = o.optJSONArray("subtitles");
            if (a != null) {
                if (a.length() > MAX_SUBTITLES) throw new IllegalArgumentException("Too many subtitles.");
                for (int i = 0; i < a.length(); i++) subtitles.add(Subtitle.fromJson(a.getJSONObject(i)));
            }
            List<AudioTrack> audioTracks = new ArrayList<>();
            JSONArray audio = o.optJSONArray("audioTracks");
            if (audio != null) {
                if (audio.length() > MAX_AUDIO_TRACKS) throw new IllegalArgumentException("Too many audio tracks.");
                for (int i = 0; i < audio.length(); i++) audioTracks.add(AudioTrack.fromJson(audio.getJSONObject(i)));
            }
            Source source = new Source(o.getString("id"), o.getString("label"), o.getString("uri"),
                    o.optString("mimeType", ""), headers, subtitles, audioTracks);
            source.unsupportedSubtitleCount = Math.max(0, Math.min(MAX_REJECTED_PROVIDER_SUBTITLES,
                    o.optInt("unsupportedSubtitleCount", 0)));
            return source;
        }
    }

    public static final class SkipSegment implements HasId {
        public final String id, kind, label;
        public final long startMs, endMs;
        public SkipSegment(String id, String kind, String label, long startMs, long endMs) {
            this.id = identity(id, "skip segment id", 160);
            this.kind = required(kind, "skip segment kind", 32).toLowerCase(Locale.ROOT);
            if (!("intro".equals(this.kind) || "recap".equals(this.kind) || "credits".equals(this.kind))) {
                throw new IllegalArgumentException("Unsupported skip segment kind.");
            }
            this.label = required(label, "skip segment label", 100);
            if (startMs < 0 || endMs <= startMs || endMs > MAX_SKIP_TIME_MS) {
                throw new IllegalArgumentException("Skip segment timing is outside supported bounds.");
            }
            this.startMs = startMs; this.endMs = endMs;
        }
        @Override public String id() { return id; }
        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject(); o.put("id", id); o.put("kind", kind); o.put("label", label);
            o.put("startMs", startMs); o.put("endMs", endMs); return o;
        }
        static SkipSegment fromJson(JSONObject o) throws JSONException {
            return new SkipSegment(o.getString("id"), o.getString("kind"), o.getString("label"),
                    o.getLong("startMs"), o.getLong("endMs"));
        }
    }

    public static final class QueueItem implements HasId {
        public final String id;
        public final MediaMetadata media;
        public final EpisodeMetadata episode;
        public final List<Source> sources;
        public final String defaultSourceId;
        public final List<SkipSegment> skipSegments;
        public QueueItem(String id, MediaMetadata media, EpisodeMetadata episode, List<Source> sources,
                String defaultSourceId, List<SkipSegment> skipSegments) {
            this.id = identity(id, "queue item id", 160);
            if (media == null) throw new IllegalArgumentException("Media metadata is required.");
            this.media = media; this.episode = episode == null ? EpisodeMetadata.EMPTY : episode;
            this.sources = immutableList(sources, MAX_SOURCES, "sources");
            if (this.sources.isEmpty()) throw new IllegalArgumentException("A queued episode needs a source.");
            this.defaultSourceId = identity(defaultSourceId, "defaultSourceId", 160);
            if (!containsSource(this.sources, this.defaultSourceId)) throw new IllegalArgumentException("Invalid default source.");
            this.skipSegments = immutableList(skipSegments, 200, "skipSegments");
            ensureUnique(this.sources, "source id"); ensureUnique(this.skipSegments, "skip segment id");
        }
        @Override public String id() { return id; }
        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject(); o.put("id", id); o.put("media", media.toJson());
            o.put("episode", episode.toJson()); o.put("sources", sourcesToJson(sources));
            o.put("defaultSourceId", defaultSourceId); JSONArray skips = new JSONArray();
            for (SkipSegment segment : skipSegments) skips.put(segment.toJson()); o.put("skipSegments", skips); return o;
        }
        static QueueItem fromJson(JSONObject o) throws JSONException {
            List<Source> sources = parseSources(o.optJSONArray("sources"));
            List<SkipSegment> skips = new ArrayList<>(); JSONArray a = o.optJSONArray("skipSegments");
            if (a != null) {
                if (a.length() > 200) throw new IllegalArgumentException("Too many skip segments.");
                for (int i = 0; i < a.length(); i++) skips.add(SkipSegment.fromJson(a.getJSONObject(i)));
            }
            return new QueueItem(o.getString("id"), MediaMetadata.fromJson(o.getJSONObject("media")),
                    EpisodeMetadata.fromJson(o.optJSONObject("episode")), sources,
                    o.getString("defaultSourceId"), skips);
        }
    }
}
