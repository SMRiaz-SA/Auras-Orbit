package com.auras.orbit.jellyfin;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Immutable values returned by the Orbit-owned Jellyfin HTTP integration. */
public final class JellyfinModels {
    private JellyfinModels() { }

    /** Authenticated session. The bearer token is package-private and never included in toString(). */
    public static final class Connection {
        private static final int MAX_DISPLAY_NAME_CODE_POINTS = 128;

        public final String serverUrl;
        public final String serverName;
        public final String userId;
        public final String userName;
        public final String serverId;
        public final String deviceId;
        final String accessToken;

        Connection(String serverUrl, String serverName, String userId, String userName,
                   String serverId,
                   String deviceId, String accessToken) {
            this.serverUrl = serverUrl;
            this.serverName = sanitizeDisplayName(serverName);
            this.userId = userId;
            this.userName = sanitizeDisplayName(userName);
            this.serverId = serverId;
            this.deviceId = deviceId;
            this.accessToken = accessToken;
        }

        private static String sanitizeDisplayName(String value) {
            if (value == null || value.isEmpty()) return "";
            StringBuilder sanitized = new StringBuilder();
            int codePointCount = 0;
            for (int offset = 0; offset < value.length();) {
                int codePoint = value.codePointAt(offset);
                offset += Character.charCount(codePoint);
                int category = Character.getType(codePoint);
                if (Character.isISOControl(codePoint)
                        || category == Character.FORMAT
                        || category == Character.SURROGATE) continue;
                if (codePointCount >= MAX_DISPLAY_NAME_CODE_POINTS) break;
                sanitized.appendCodePoint(codePoint);
                codePointCount++;
            }
            return sanitized.toString().trim();
        }

        @Override public String toString() {
            return "Connection{serverUrl='" + serverUrl + "', userId='" + userId
                    + "', userName='" + userName + "'}";
        }
    }

    public static final class User {
        public final String id;
        public final String name;

        User(String id, String name) { this.id = id; this.name = name; }
    }

    public static final class LibraryView {
        public final int childCount;
        public final boolean hasChildCount;
        public final String id;
        public final String name;
        public final String collectionType;
        public final String primaryImageTag;

        LibraryView(String id, String name, String collectionType, String primaryImageTag,
                    long childCount) {
            this.id = id;
            this.name = name;
            this.collectionType = collectionType;
            this.primaryImageTag = primaryImageTag;
            hasChildCount = childCount >= 0L;
            this.childCount = hasChildCount
                    ? (int) Math.min(Integer.MAX_VALUE, childCount) : 0;
        }
    }

    public static final class MediaItem {
        public final String id;
        public final String name;
        public final String type;
        public final String overview;
        public final String parentId;
        public final String primaryImageTag;
        public final long runtimeTicks;
        public final long playbackPositionTicks;
        public final boolean played;
        public final int productionYear;
        public final int seasonNumber;
        public final int episodeNumber;

        MediaItem(String id, String name, String type, String overview, String parentId,
                  String primaryImageTag, long runtimeTicks, long playbackPositionTicks,
                  boolean played, int productionYear, int seasonNumber, int episodeNumber) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.overview = overview;
            this.parentId = parentId;
            this.primaryImageTag = primaryImageTag;
            this.runtimeTicks = runtimeTicks;
            this.playbackPositionTicks = playbackPositionTicks;
            this.played = played;
            this.productionYear = productionYear;
            this.seasonNumber = seasonNumber;
            this.episodeNumber = episodeNumber;
        }
    }

    public static final class MediaPage {
        public final List<MediaItem> items;
        public final int startIndex;
        public final int totalRecordCount;

        MediaPage(List<MediaItem> items, int startIndex, int totalRecordCount) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.startIndex = Math.max(0, startIndex);
            this.totalRecordCount = Math.max(0, totalRecordCount);
        }
    }

    public static final class MediaStream {
        public final int index;
        public final String type;
        public final String codec;
        public final String language;
        public final String displayTitle;
        public final boolean isDefault;
        public final boolean isExternal;

        MediaStream(int index, String type, String codec, String language, String displayTitle,
                    boolean isDefault, boolean isExternal) {
            this.index = index;
            this.type = type;
            this.codec = codec;
            this.language = language;
            this.displayTitle = displayTitle;
            this.isDefault = isDefault;
            this.isExternal = isExternal;
        }
    }

    public static final class PlaybackSource {
        public final String id;
        public final String name;
        public final String container;
        public final String protocol;
        public final boolean remote;
        public final boolean supportsDirectPlay;
        public final boolean supportsDirectStream;
        public final boolean supportsTranscoding;
        public final String directStreamUrl;
        public final String transcodingUrl;
        public final List<MediaStream> streams;

        PlaybackSource(String id, String name, String container, String protocol, boolean remote,
                       boolean supportsDirectPlay, boolean supportsDirectStream, boolean supportsTranscoding,
                       String directStreamUrl, String transcodingUrl, List<MediaStream> streams) {
            this.id = id;
            this.name = name;
            this.container = container;
            this.protocol = protocol;
            this.remote = remote;
            this.supportsDirectPlay = supportsDirectPlay;
            this.supportsDirectStream = supportsDirectStream;
            this.supportsTranscoding = supportsTranscoding;
            this.directStreamUrl = directStreamUrl;
            this.transcodingUrl = transcodingUrl;
            this.streams = Collections.unmodifiableList(new ArrayList<>(streams));
        }
    }

    public static final class PlaybackInfo {
        public final String playSessionId;
        public final String errorCode;
        public final long startPositionTicks;
        public final List<PlaybackSource> sources;

        PlaybackInfo(String playSessionId, String errorCode, long startPositionTicks,
                     List<PlaybackSource> sources) {
            this.playSessionId = playSessionId;
            this.errorCode = errorCode;
            this.startPositionTicks = Math.max(0L, startPositionTicks);
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
        }
    }

    /** A server-authorized stream URL and transient request headers for an Orbit player adapter. */
    public static final class PlaybackRequest {
        public final URI url;
        private final Map<String, String> headers;

        PlaybackRequest(URI url, Map<String, String> headers) {
            this.url = url;
            this.headers = Collections.unmodifiableMap(headers);
        }

        /** Contains the Jellyfin authorization header. Keep in memory; never log or persist it. */
        public Map<String, String> getHeadersForPlayer() { return headers; }

        @Override public String toString() { return "PlaybackRequest{url='[redacted]'}"; }
    }

    public static final class PlaybackEvent {
        public final String itemId;
        public final String mediaSourceId;
        public final String playSessionId;
        public final long positionTicks;
        public final boolean canSeek;
        public final boolean paused;
        public final boolean muted;
        public final Integer audioStreamIndex;
        public final Integer subtitleStreamIndex;
        public final String playMethod;

        public PlaybackEvent(String itemId, String mediaSourceId, String playSessionId,
                             long positionTicks, boolean canSeek, boolean paused, boolean muted,
                             Integer audioStreamIndex, Integer subtitleStreamIndex) {
            this(itemId, mediaSourceId, playSessionId, positionTicks, canSeek, paused, muted,
                    audioStreamIndex, subtitleStreamIndex, "DirectPlay");
        }

        public PlaybackEvent(String itemId, String mediaSourceId, String playSessionId,
                             long positionTicks, boolean canSeek, boolean paused, boolean muted,
                             Integer audioStreamIndex, Integer subtitleStreamIndex, String playMethod) {
            this.itemId = itemId;
            this.mediaSourceId = mediaSourceId;
            this.playSessionId = playSessionId;
            this.positionTicks = Math.max(0L, positionTicks);
            this.canSeek = canSeek;
            this.paused = paused;
            this.muted = muted;
            this.audioStreamIndex = audioStreamIndex;
            this.subtitleStreamIndex = subtitleStreamIndex;
            this.playMethod = playMethod;
        }
    }
}
