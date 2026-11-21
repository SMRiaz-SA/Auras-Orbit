package com.auras.orbit.player;

/** Typed provider response indicating its saved subtitle credentials were missing or rejected. */
public final class SubtitleAuthenticationException extends IllegalStateException {
    public SubtitleAuthenticationException(String message) {
        super(message == null || message.trim().isEmpty()
                ? "Subtitle provider credentials were rejected." : message);
    }
}
