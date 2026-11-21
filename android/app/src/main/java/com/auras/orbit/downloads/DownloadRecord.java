package com.auras.orbit.downloads;

/** Immutable UI-safe snapshot of one queue or offline item. It never contains the source URL. */
public final class DownloadRecord {
    public static final String QUEUED = "QUEUED";
    public static final String DOWNLOADING = "DOWNLOADING";
    public static final String PAUSED = "PAUSED";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String REMOVING = "REMOVING";

    public final String id;
    public final String profileId;
    public final String stableItemId;
    public final String sourceId;
    public final String title;
    public final String fileName;
    public final String mimeType;
    public final String status;
    public final long bytesDownloaded;
    public final long totalBytes;
    public final long createdAt;
    public final String fileUri;
    public final String folderUri;
    public final String errorMessage;
    public final long queueOrder;
    public final String seasonLabel;
    public final String episodeLabel;
    public final long knownSizeBytes;
    public final String artworkReference;
    /** Measured in-memory rate. Zero means that no reliable sample exists yet. */
    public final long bytesPerSecond;
    /** Remaining seconds from measured samples, or -1 while unknown. */
    public final long etaSeconds;

    DownloadRecord(String id, String profileId, String stableItemId, String sourceId,
            String title, String fileName, String mimeType, String status,
            long bytesDownloaded, long totalBytes, long createdAt, String fileUri,
            String folderUri, String errorMessage, long queueOrder, String seasonLabel,
            String episodeLabel, long knownSizeBytes, String artworkReference) {
        this(id, profileId, stableItemId, sourceId, title, fileName, mimeType, status,
                bytesDownloaded, totalBytes, createdAt, fileUri, folderUri, errorMessage,
                queueOrder, seasonLabel, episodeLabel, knownSizeBytes, artworkReference, 0L, -1L);
    }

    private DownloadRecord(String id, String profileId, String stableItemId, String sourceId,
            String title, String fileName, String mimeType, String status,
            long bytesDownloaded, long totalBytes, long createdAt, String fileUri,
            String folderUri, String errorMessage, long queueOrder, String seasonLabel,
            String episodeLabel, long knownSizeBytes, String artworkReference,
            long bytesPerSecond, long etaSeconds) {
        this.id = id;
        this.profileId = profileId;
        this.stableItemId = stableItemId;
        this.sourceId = sourceId;
        this.title = title;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.status = status;
        this.bytesDownloaded = bytesDownloaded;
        this.totalBytes = totalBytes;
        this.createdAt = createdAt;
        this.fileUri = fileUri;
        this.folderUri = folderUri;
        this.errorMessage = errorMessage;
        this.queueOrder = queueOrder;
        this.seasonLabel = seasonLabel == null ? "" : seasonLabel;
        this.episodeLabel = episodeLabel == null ? "" : episodeLabel;
        this.knownSizeBytes = knownSizeBytes;
        this.artworkReference = artworkReference == null ? "" : artworkReference;
        this.bytesPerSecond = Math.max(0L, bytesPerSecond);
        this.etaSeconds = etaSeconds < -1L ? -1L : etaSeconds;
    }

    DownloadRecord withTransferEstimate(long measuredBytesPerSecond, long remainingSeconds) {
        return new DownloadRecord(id, profileId, stableItemId, sourceId, title, fileName,
                mimeType, status, bytesDownloaded, totalBytes, createdAt, fileUri, folderUri,
                errorMessage, queueOrder, seasonLabel, episodeLabel, knownSizeBytes,
                artworkReference, measuredBytesPerSecond, remainingSeconds);
    }

    public boolean isComplete() { return COMPLETED.equals(status); }
    public int getProgressPercent() {
        if (totalBytes <= 0L) return 0;
        return (int) Math.min(100L, (bytesDownloaded * 100L) / totalBytes);
    }
}
