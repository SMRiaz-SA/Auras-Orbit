package com.auras.orbit.metadata;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ConservativeIdentityMatcherTest {
    private static IdentityProvenance issuerClaim(String id, String reference) {
        return IdentityProvenance.issuerClaim(IdentityProvenance.Namespace.TMDB_MOVIE,
                IdentityProvenance.Kind.MOVIE, id, reference, 1_000L);
    }

    @Test public void issuerClaimIsTypedButCannotBecomeAutomaticMatchWithoutPolicy() {
        IdentityProvenance source = issuerClaim("603", "https://api.themoviedb.org/3/movie/603");
        IdentityProvenance candidate = issuerClaim("603", "https://api.themoviedb.org/3/movie/603");
        ConservativeIdentityMatcher.Result result = ConservativeIdentityMatcher.match(
                IdentityProvenance.Kind.MOVIE, Collections.singletonList(source),
                Collections.singletonList(new ConservativeIdentityMatcher.Candidate(
                        "work-1", IdentityProvenance.Kind.MOVIE, Collections.singletonList(candidate))));

        assertFalse(source.isIssuerBacked());
        assertEquals(IdentityProvenance.EvidenceType.ISSUER_CLAIM, source.evidenceType);
        assertEquals(ConservativeIdentityMatcher.Decision.UNMATCHED, result.decision);
        assertNull(result.workId);
        assertTrue(result.reason.contains("no issuer-backed identity evidence"));
    }

    @Test public void providerClaimsNeverCountAsIssuerEvidence() {
        IdentityProvenance providerClaim = IdentityProvenance.providerClaim(
                IdentityProvenance.Namespace.TMDB_MOVIE, IdentityProvenance.Kind.MOVIE,
                "603", "Example provider", 2_000L);
        ConservativeIdentityMatcher.Result result = ConservativeIdentityMatcher.match(
                IdentityProvenance.Kind.MOVIE, Collections.singletonList(providerClaim),
                Collections.singletonList(new ConservativeIdentityMatcher.Candidate("work-1",
                        IdentityProvenance.Kind.MOVIE, Collections.singletonList(issuerClaim("603",
                                "https://api.themoviedb.org/3/movie/603")))));

        assertFalse(providerClaim.isIssuerBacked());
        assertEquals(ConservativeIdentityMatcher.Decision.UNMATCHED, result.decision);
        assertTrue(result.reason.contains("provider claims are unverified"));
    }

    @Test public void duplicateClaimedIssuerCandidatesCannotCreateAnAmbiguousAutoMatch() {
        IdentityProvenance source = issuerClaim("603", "https://api.themoviedb.org/3/movie/603");
        ConservativeIdentityMatcher.Result result = ConservativeIdentityMatcher.match(
                IdentityProvenance.Kind.MOVIE, Collections.singletonList(source), Arrays.asList(
                        new ConservativeIdentityMatcher.Candidate("work-1", IdentityProvenance.Kind.MOVIE,
                                Collections.singletonList(issuerClaim("603", "https://api.themoviedb.org/3/movie/603"))),
                        new ConservativeIdentityMatcher.Candidate("work-2", IdentityProvenance.Kind.MOVIE,
                                Collections.singletonList(issuerClaim("603", "https://api.themoviedb.org/3/movie/603")))));

        assertEquals(ConservativeIdentityMatcher.Decision.UNMATCHED, result.decision);
        assertNull(result.workId);
        assertTrue(result.candidateWorkIds.isEmpty());
    }

    @Test public void claimedIssuerUrlIsNotProofAndContradictoryClaimsCannotMatch() {
        IdentityProvenance claimedAttackerRef = issuerClaim("603", "https://example.invalid/movie/603");
        assertFalse(claimedAttackerRef.isIssuerBacked());
        assertEquals("https://example.invalid", claimedAttackerRef.sourceRef);
        ConservativeIdentityMatcher.Result result = ConservativeIdentityMatcher.match(
                IdentityProvenance.Kind.MOVIE,
                Arrays.asList(issuerClaim("603", "https://example.invalid/movie/603"),
                        issuerClaim("604", "https://example.invalid/movie/604")), Collections.emptyList());
        assertEquals(ConservativeIdentityMatcher.Decision.UNMATCHED, result.decision);
    }

    @Test public void rejectsMediaKindAndNamespaceMismatches() {
        assertThrows(IllegalArgumentException.class, () -> IdentityProvenance.issuerClaim(
                IdentityProvenance.Namespace.TMDB_MOVIE, IdentityProvenance.Kind.SERIES, "603",
                "https://api.themoviedb.org/3/movie/603", 1_000L));
    }
}
