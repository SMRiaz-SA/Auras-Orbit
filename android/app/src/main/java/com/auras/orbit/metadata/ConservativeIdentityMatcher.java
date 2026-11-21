package com.auras.orbit.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure, conservative matching over typed claims with issuer provenance. */
public final class ConservativeIdentityMatcher {
    private ConservativeIdentityMatcher() { }

    public enum Decision { ACCEPTED, AMBIGUOUS, UNMATCHED, REJECTED }

    public static final class Candidate {
        public final String workId;
        public final IdentityProvenance.Kind kind;
        public final List<IdentityProvenance> claims;

        public Candidate(String workId, IdentityProvenance.Kind kind,
                List<IdentityProvenance> claims) {
            if (workId == null || workId.trim().isEmpty() || workId.length() > 256)
                throw new IllegalArgumentException("Bounded workId required");
            this.workId = workId.trim();
            this.kind = Objects.requireNonNull(kind, "kind");
            this.claims = Collections.unmodifiableList(new ArrayList<>(
                    Objects.requireNonNull(claims, "claims")));
        }
    }

    /** The exact source and candidate issuer claims that explain an accepted match. */
    public static final class Evidence {
        public final IdentityProvenance.Namespace namespace;
        public final String externalId;
        public final IdentityProvenance sourceClaim;
        public final IdentityProvenance candidateClaim;

        Evidence(IdentityProvenance sourceClaim, IdentityProvenance candidateClaim) {
            this.namespace = sourceClaim.namespace;
            this.externalId = sourceClaim.externalId;
            this.sourceClaim = sourceClaim;
            this.candidateClaim = candidateClaim;
        }
    }

    public static final class Result {
        public final Decision decision;
        public final String workId;
        public final String reason;
        public final List<Evidence> evidence;
        public final List<String> candidateWorkIds;

        Result(Decision decision, String workId, String reason,
                List<Evidence> evidence, List<String> candidateWorkIds) {
            this.decision = decision;
            this.workId = workId;
            this.reason = reason;
            this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
            this.candidateWorkIds = Collections.unmodifiableList(new ArrayList<>(candidateWorkIds));
        }
    }

    /** Accept one conflict-free candidate only when a trusted issuer agrees on typed IDs. */
    public static Result match(IdentityProvenance.Kind sourceKind,
            List<IdentityProvenance> sourceClaims, List<Candidate> candidates) {
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(sourceClaims, "sourceClaims");
        Objects.requireNonNull(candidates, "candidates");

        Map<IdentityProvenance.Namespace, IdentityProvenance> trustedSource =
                new EnumMap<>(IdentityProvenance.Namespace.class);
        for (IdentityProvenance claim : sourceClaims) {
            if (claim == null || !claim.isIssuerBacked()) continue;
            if (claim.kind != sourceKind)
                return result(Decision.REJECTED, null,
                        "verified issuer claim conflicts with source media kind", null, null);
            IdentityProvenance prior = trustedSource.putIfAbsent(claim.namespace, claim);
            if (prior != null && !prior.externalId.equals(claim.externalId))
                return result(Decision.REJECTED, null,
                        "contradictory verified IDs from the same issuer namespace", null, null);
        }
        if (trustedSource.isEmpty())
            return result(Decision.UNMATCHED, null,
                    "no issuer-backed identity evidence; provider claims are unverified", null, null);

        IdentityProvenance.Kind candidateKind = sourceKind;
        String acceptedId = null;
        List<Evidence> acceptedEvidence = Collections.emptyList();
        Set<String> matches = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.kind != candidateKind) continue;
            Map<IdentityProvenance.Namespace, IdentityProvenance> trustedCandidate =
                    new EnumMap<>(IdentityProvenance.Namespace.class);
            boolean candidateConflict = false;
            for (IdentityProvenance claim : candidate.claims) {
                if (claim == null || !claim.isIssuerBacked() || claim.kind != candidate.kind) continue;
                IdentityProvenance prior = trustedCandidate.putIfAbsent(claim.namespace, claim);
                if (prior != null && !prior.externalId.equals(claim.externalId)) candidateConflict = true;
            }
            if (candidateConflict) continue;

            List<Evidence> common = new ArrayList<>();
            boolean contradictory = false;
            for (Map.Entry<IdentityProvenance.Namespace, IdentityProvenance> source : trustedSource.entrySet()) {
                IdentityProvenance other = trustedCandidate.get(source.getKey());
                if (other == null) continue;
                if (other.externalId.equals(source.getValue().externalId))
                    common.add(new Evidence(source.getValue(), other));
                else
                    contradictory = true;
            }
            if (common.isEmpty() || contradictory) continue;
            matches.add(candidate.workId);
            if (acceptedId == null) {
                acceptedId = candidate.workId;
                acceptedEvidence = common;
            }
        }
        if (matches.size() > 1)
            return result(Decision.AMBIGUOUS, null,
                    "multiple candidates share verified issuer IDs", null, matches);
        if (acceptedId == null)
            return result(Decision.UNMATCHED, null,
                    "no candidate has a conflict-free issuer-backed ID match", null, null);
        return result(Decision.ACCEPTED, acceptedId,
                "exact verified issuer ID match with compatible media kind", acceptedEvidence, matches);
    }

    private static Result result(Decision decision, String workId, String reason,
            List<Evidence> evidence, Iterable<String> candidates) {
        List<String> ids = new ArrayList<>();
        if (candidates != null) for (String id : candidates) ids.add(id);
        return new Result(decision, workId, reason,
                evidence == null ? Collections.emptyList() : evidence, ids);
    }
}
