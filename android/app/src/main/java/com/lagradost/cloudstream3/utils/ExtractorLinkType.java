package com.lagradost.cloudstream3.utils;

/** Pinned CloudStream enum order is part of the serialized and Java enum ABI. */
public enum ExtractorLinkType {
    VIDEO,
    M3U8,
    DASH,
    TORRENT,
    MAGNET;

    public final String getMimeType() {
        switch (this) {
            case VIDEO: return "video/mp4";
            case M3U8: return "application/x-mpegURL";
            case DASH: return "application/dash+xml";
            case TORRENT:
            case MAGNET: return "application/x-bittorrent";
            default: throw new AssertionError(this);
        }
    }
}
