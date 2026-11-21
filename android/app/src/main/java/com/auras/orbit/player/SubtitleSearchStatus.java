package com.auras.orbit.player;

import java.util.List;

/** Small policy helper that separates missing credentials from a successful empty search. */
public final class SubtitleSearchStatus {
    private SubtitleSearchStatus() { }

    public static boolean requiresAuthentication(boolean providerRequiresLogin, boolean accountAvailable) {
        return providerRequiresLogin && !accountAvailable;
    }

    /** Keeps a provider's null/error response distinct from a successful empty result list. */
    public static <T> List<T> requireProviderResponse(List<T> response, String providerId) {
        if (response == null) {
            String label = providerId == null || providerId.trim().isEmpty()
                    ? "Subtitle provider" : providerId.trim();
            throw new IllegalStateException(label + " returned no search response.");
        }
        return response;
    }

    public static boolean isAuthenticationFailure(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (current instanceof SubtitleAuthenticationException) return true;
            current = current.getCause();
        }
        return false;
    }
}
