package com.auras.orbit.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class SubtitleSearchStatusTest {
    @Test public void onlyReportsAuthenticationWhenARequiredAccountIsMissing() {
        assertTrue(SubtitleSearchStatus.requiresAuthentication(true, false));
        assertFalse(SubtitleSearchStatus.requiresAuthentication(true, true));
        assertFalse(SubtitleSearchStatus.requiresAuthentication(false, false));
    }

    @Test public void successfulEmptyResponseRemainsAValidNoMatch() {
        List<String> empty = Collections.emptyList();
        assertSame(empty, SubtitleSearchStatus.requireProviderResponse(empty, "subsource"));
    }

    @Test(expected = IllegalStateException.class)
    public void nullProviderResponseRemainsAFailureInsteadOfBecomingNoMatches() {
        SubtitleSearchStatus.requireProviderResponse(null, "subsource");
    }

    @Test public void typedAuthenticationFailureSurvivesWrappedProviderResult() {
        assertTrue(SubtitleSearchStatus.isAuthenticationFailure(
                new SubtitleAuthenticationException("Provider rejected credentials.")));
        assertTrue(SubtitleSearchStatus.isAuthenticationFailure(new IllegalStateException(
                "Provider failure", new SubtitleAuthenticationException("Provider rejected credentials."))));
        assertFalse(SubtitleSearchStatus.isAuthenticationFailure(
                new IllegalStateException("Provider returned no response.")));
    }
}
