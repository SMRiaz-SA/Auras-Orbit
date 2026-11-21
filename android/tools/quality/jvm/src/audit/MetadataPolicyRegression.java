package audit;

import com.auras.orbit.metadata.ConservativeIdentityMatcher;
import com.auras.orbit.metadata.MetadataFieldPolicy;
import java.util.Arrays;
import java.util.Collections;

public final class MetadataPolicyRegression {
    private static long assertions;
    private static void check(boolean value, String why) {
        assertions++; if (!value) throw new AssertionError(why);
    }
    public static void main(String[] args) {
        ConservativeIdentityMatcher.Claim movie = claim(ConservativeIdentityMatcher.Namespace.TMDB_MOVIE,
                ConservativeIdentityMatcher.Kind.MOVIE, "123", true);
        ConservativeIdentityMatcher.Claim untrusted = claim(ConservativeIdentityMatcher.Namespace.TMDB_MOVIE,
                ConservativeIdentityMatcher.Kind.MOVIE, "123", false);
        ConservativeIdentityMatcher.Claim different = claim(ConservativeIdentityMatcher.Namespace.TMDB_MOVIE,
                ConservativeIdentityMatcher.Kind.MOVIE, "456", true);
        ConservativeIdentityMatcher.Candidate match = new ConservativeIdentityMatcher.Candidate("work-1",
                ConservativeIdentityMatcher.Kind.MOVIE, Collections.singletonList(movie));
        check(ConservativeIdentityMatcher.match(ConservativeIdentityMatcher.Kind.MOVIE,
                Collections.singletonList(movie), Collections.singletonList(match)).decision
                == ConservativeIdentityMatcher.Decision.ACCEPTED, "verified matching movie ID");
        check(ConservativeIdentityMatcher.match(ConservativeIdentityMatcher.Kind.MOVIE,
                Collections.singletonList(untrusted), Collections.singletonList(match)).decision
                == ConservativeIdentityMatcher.Decision.UNMATCHED, "unverified plugin sync data is not proof");
        check(ConservativeIdentityMatcher.match(ConservativeIdentityMatcher.Kind.MOVIE,
                Arrays.asList(movie, different), Collections.singletonList(match)).decision
                == ConservativeIdentityMatcher.Decision.REJECTED, "contradictory verified IDs");
        check(ConservativeIdentityMatcher.match(ConservativeIdentityMatcher.Kind.MOVIE,
                Collections.singletonList(movie), Arrays.asList(match,
                        new ConservativeIdentityMatcher.Candidate("work-2", ConservativeIdentityMatcher.Kind.MOVIE,
                                Collections.singletonList(movie)))).decision
                == ConservativeIdentityMatcher.Decision.AMBIGUOUS, "two candidates with one ID");
        check(ConservativeIdentityMatcher.match(ConservativeIdentityMatcher.Kind.SERIES,
                Collections.emptyList(), Collections.singletonList(match)).decision
                == ConservativeIdentityMatcher.Decision.UNMATCHED, "title alone never auto-merges");
        ConservativeIdentityMatcher.Claim tv = claim(ConservativeIdentityMatcher.Namespace.TMDB_TV,
                ConservativeIdentityMatcher.Kind.SERIES, "123", true);
        check(ConservativeIdentityMatcher.match(ConservativeIdentityMatcher.Kind.SERIES,
                Collections.singletonList(tv), Collections.singletonList(match)).decision
                == ConservativeIdentityMatcher.Decision.UNMATCHED, "TMDB movie 123 != TMDB TV 123");
        for (int i = 0; i < 5000; i++) {
            String id = "id-" + i;
            ConservativeIdentityMatcher.Claim item = claim(ConservativeIdentityMatcher.Namespace.IMDB_TITLE,
                    ConservativeIdentityMatcher.Kind.MOVIE, id, true);
            check(ConservativeIdentityMatcher.match(ConservativeIdentityMatcher.Kind.MOVIE,
                    Collections.singletonList(item), Collections.singletonList(match)).decision
                    == ConservativeIdentityMatcher.Decision.UNMATCHED, "no invented canonical link");
        }
        MetadataFieldPolicy.Fact provider = fact("Provider", MetadataFieldPolicy.Origin.PROVIDER, true);
        MetadataFieldPolicy.Fact catalog = fact("Catalog", MetadataFieldPolicy.Origin.CATALOG, true);
        MetadataFieldPolicy.Fact user = fact("My text", MetadataFieldPolicy.Origin.USER, true);
        MetadataFieldPolicy.Fact jellyfin = fact("Server edit", MetadataFieldPolicy.Origin.JELLYFIN_LOCAL, true);
        check(MetadataFieldPolicy.choose(MetadataFieldPolicy.Field.SOURCE_ACTION,
                MetadataFieldPolicy.Mode.CATALOG_FIRST, true, false, provider, catalog, user, jellyfin) == provider,
                "source action cannot be replaced by catalog or user");
        check(MetadataFieldPolicy.choose(MetadataFieldPolicy.Field.SYNOPSIS,
                MetadataFieldPolicy.Mode.INTELLIGENT_HYBRID, false, false, provider, catalog, null, null) == provider,
                "unmatched catalogue cannot override source");
        check(MetadataFieldPolicy.choose(MetadataFieldPolicy.Field.SYNOPSIS,
                MetadataFieldPolicy.Mode.INTELLIGENT_HYBRID, true, false, provider, catalog, null, null) == catalog,
                "verified hybrid enriches synopsis");
        check(MetadataFieldPolicy.choose(MetadataFieldPolicy.Field.TITLE,
                MetadataFieldPolicy.Mode.PROVIDER_FIRST, true, false, provider, catalog, null, null) == provider,
                "provider first stays provider first");
        check(MetadataFieldPolicy.choose(MetadataFieldPolicy.Field.TITLE,
                MetadataFieldPolicy.Mode.CATALOG_FIRST, true, false, provider, catalog, null, null) == catalog,
                "catalog first changes display only");
        check(MetadataFieldPolicy.choose(MetadataFieldPolicy.Field.SYNOPSIS,
                MetadataFieldPolicy.Mode.CATALOG_FIRST, true, true, provider, catalog, null, jellyfin) == jellyfin,
                "Jellyfin-local edits override external catalog");
        check(MetadataFieldPolicy.choose(MetadataFieldPolicy.Field.SYNOPSIS,
                MetadataFieldPolicy.Mode.CATALOG_FIRST, true, true, provider, catalog, user, jellyfin) == user,
                "user display override precedence");
        System.out.println("METADATA_IDENTITY_AND_FIELD_POLICY_PASS assertions=" + assertions);
    }
    private static ConservativeIdentityMatcher.Claim claim(ConservativeIdentityMatcher.Namespace ns,
            ConservativeIdentityMatcher.Kind kind, String id, boolean verified) {
        return new ConservativeIdentityMatcher.Claim(ns, kind, id, verified);
    }
    private static MetadataFieldPolicy.Fact fact(String value, MetadataFieldPolicy.Origin origin,
            boolean verified) { return new MetadataFieldPolicy.Fact(value, origin, verified); }
}
