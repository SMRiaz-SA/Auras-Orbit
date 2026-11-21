package com.auras.orbit.player;

/** Keeps CS3 subtitle admission aligned with the URI, header, and transport safety gates. */
public final class SubtitleCandidatePolicy {
    private SubtitleCandidatePolicy() { }

    public static boolean isAccepted(boolean uriValid, boolean headersValid,
            boolean transportSafe) {
        return uriValid && headersValid && transportSafe;
    }

    public static boolean shouldCountAsSkipped(boolean uriValid, boolean headersValid,
            boolean transportSafe) {
        return !isAccepted(uriValid, headersValid, transportSafe);
    }

    /** Compatibility overload retaining the former MIME-aware policy for legacy callers. */
    @Deprecated
    public static boolean isAccepted(boolean uriValid, boolean mimeSupported,
            boolean headersValid, boolean transportSafe) {
        return uriValid && mimeSupported && headersValid && transportSafe;
    }

    /** Compatibility overload retaining the former MIME-aware skip diagnostic. */
    @Deprecated
    public static boolean shouldCountAsSkipped(boolean uriValid, boolean mimeSupported,
            boolean headersValid, boolean transportSafe) {
        return !isAccepted(uriValid, mimeSupported, headersValid, transportSafe);
    }
}
