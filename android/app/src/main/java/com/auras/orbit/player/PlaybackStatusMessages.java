package com.auras.orbit.player;

import androidx.media3.common.PlaybackException;

/** User-facing player status text for source selection and automatic failover. */
final class PlaybackStatusMessages {
    private static final String PREPARING_PLAYBACK = "Preparing playback…";
    private static final String STILL_LOOKING_FOR_LINKS = "Still looking for links…";
    private static final String UNSUPPORTED_SOURCE =
            "This source format isn't supported on this device. Trying the next source…";
    private static final String FAILED_SOURCE =
            "This source failed. Trying the next available source…";

    private PlaybackStatusMessages() {}

    static String sourceFailureMessage() {
        return FAILED_SOURCE;
    }

    static String sourceFailureMessage(int errorCode) {
        if (errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                || errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) {
            return UNSUPPORTED_SOURCE;
        }
        return FAILED_SOURCE;
    }

    static String sourceLoadingMessage(String sourceFailureMessage) {
        if (sourceFailureMessage == null || sourceFailureMessage.isEmpty()) {
            return PREPARING_PLAYBACK;
        }
        return sourceFailureMessage + "\n" + PREPARING_PLAYBACK;
    }

    static String waitingForNextSource(String sourceFailureMessage) {
        if (sourceFailureMessage == null || sourceFailureMessage.isEmpty()) {
            return STILL_LOOKING_FOR_LINKS;
        }
        return sourceFailureMessage + "\n" + STILL_LOOKING_FOR_LINKS;
    }
}
