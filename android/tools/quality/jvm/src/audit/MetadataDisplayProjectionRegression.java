package audit;

import com.auras.orbit.metadata.MetadataDisplayProjection;
import com.auras.orbit.metadata.MetadataDisplayProjection.Provenance;

public final class MetadataDisplayProjectionRegression {
    private static void check(boolean condition) { if (!condition) throw new AssertionError("metadata projection"); }
    public static void main(String[] args) {
        MetadataDisplayProjection.Value current = MetadataDisplayProjection.choose(null, "Provider new",
                "Cache old", false, "Search name");
        check(current.text.equals("Provider new") && current.provenance == Provenance.CURRENT_PROVIDER);
        MetadataDisplayProjection.Value saved = MetadataDisplayProjection.choose(null, null,
                "Cached synopsis", false, null);
        check(saved.text.equals("Cached synopsis") && saved.provenance == Provenance.CACHED_PROVIDER
                && !saved.cacheFresh);
        MetadataDisplayProjection.Value user = MetadataDisplayProjection.choose("My title", "Provider new",
                "Cache old", true, "Search name");
        check(user.text.equals("My title") && user.provenance == Provenance.USER);
        MetadataDisplayProjection.Value listing = MetadataDisplayProjection.choose("  ", null,
                null, false, "Listing");
        check(listing.provenance == Provenance.LISTING);
        check(MetadataDisplayProjection.choose(null, "", "", false, "").provenance == Provenance.MISSING);
        MetadataDisplayProjection.Value hybrid = MetadataDisplayProjection.choose(null,
                "Provider title", "Old cache", true, null, "TMDB title",
                com.auras.orbit.metadata.MetadataFieldPolicy.Field.TITLE,
                com.auras.orbit.metadata.MetadataFieldPolicy.Mode.INTELLIGENT_HYBRID, true);
        check(hybrid.provenance == Provenance.CURRENT_PROVIDER);
        MetadataDisplayProjection.Value enriched = MetadataDisplayProjection.choose(null,
                "Provider synopsis", null, false, null, "TMDB synopsis",
                com.auras.orbit.metadata.MetadataFieldPolicy.Field.SYNOPSIS,
                com.auras.orbit.metadata.MetadataFieldPolicy.Mode.INTELLIGENT_HYBRID, true);
        check(enriched.provenance == Provenance.CATALOG && enriched.text.equals("TMDB synopsis"));
        MetadataDisplayProjection.Value unlinked = MetadataDisplayProjection.choose(null,
                "Provider synopsis", null, false, null, "TMDB synopsis",
                com.auras.orbit.metadata.MetadataFieldPolicy.Field.SYNOPSIS,
                com.auras.orbit.metadata.MetadataFieldPolicy.Mode.CATALOG_FIRST, false);
        check(unlinked.provenance == Provenance.CURRENT_PROVIDER);
        MetadataDisplayProjection.Value personal = MetadataDisplayProjection.choose("My name",
                "Provider title", null, true, null, "TMDB title",
                com.auras.orbit.metadata.MetadataFieldPolicy.Field.TITLE,
                com.auras.orbit.metadata.MetadataFieldPolicy.Mode.CATALOG_FIRST, true);
        check(personal.provenance == Provenance.USER);
        MetadataDisplayProjection.Value catalogFirst = MetadataDisplayProjection.choose(null,
                "Provider title", null, true, null, "TMDB title",
                com.auras.orbit.metadata.MetadataFieldPolicy.Field.TITLE,
                com.auras.orbit.metadata.MetadataFieldPolicy.Mode.CATALOG_FIRST, true);
        check(catalogFirst.provenance == Provenance.CATALOG);
        System.out.println("METADATA_DISPLAY_PROJECTION_PASS");
    }
}
