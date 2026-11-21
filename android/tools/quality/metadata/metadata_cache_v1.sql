-- Auras Orbit disposable metadata cache research schema v1.
-- DOES NOT contain Library, user corrections, tokens, headers, action payloads or source URLs.
-- This is a migration/test fixture. Android SQLiteOpenHelper integration and corruption
-- recovery remain pending. Never execute this against orbit_local_library.db.
PRAGMA foreign_keys = ON;
CREATE TABLE canonical_work (
    work_id TEXT PRIMARY KEY CHECK(length(work_id) BETWEEN 1 AND 96),
    kind TEXT NOT NULL CHECK(kind IN ('MOVIE', 'SERIES')),
    created_at_ms INTEGER NOT NULL CHECK(created_at_ms >= 0)
);
CREATE TABLE canonical_external_id (
    namespace TEXT NOT NULL CHECK(namespace IN ('TMDB_MOVIE','TMDB_TV','IMDB_TITLE','TVDB_SERIES','ANILIST','MAL')),
    kind TEXT NOT NULL CHECK(kind IN ('MOVIE', 'SERIES')),
    external_id TEXT NOT NULL CHECK(length(external_id) BETWEEN 1 AND 256),
    work_id TEXT NOT NULL REFERENCES canonical_work(work_id) ON DELETE CASCADE,
    verification_method TEXT NOT NULL CHECK(length(verification_method) BETWEEN 1 AND 120),
    verified_at_ms INTEGER NOT NULL CHECK(verified_at_ms >= 0),
    PRIMARY KEY(namespace, kind, external_id),
    CHECK((namespace <> 'TMDB_MOVIE' OR kind = 'MOVIE')
        AND (namespace <> 'TMDB_TV' OR kind = 'SERIES')
        AND (namespace <> 'TVDB_SERIES' OR kind = 'SERIES'))
);
CREATE INDEX canonical_external_by_work ON canonical_external_id(work_id);
CREATE TRIGGER canonical_external_kind_insert BEFORE INSERT ON canonical_external_id BEGIN
    SELECT RAISE(ABORT, 'canonical external kind mismatch') WHERE
        (SELECT kind FROM canonical_work WHERE work_id = NEW.work_id) <> NEW.kind;
END;
CREATE TRIGGER canonical_external_kind_update BEFORE UPDATE OF kind, work_id ON canonical_external_id BEGIN
    SELECT RAISE(ABORT, 'canonical external kind mismatch') WHERE
        (SELECT kind FROM canonical_work WHERE work_id = NEW.work_id) <> NEW.kind;
END;
CREATE TRIGGER canonical_work_kind_restrict BEFORE UPDATE OF kind ON canonical_work BEGIN
    SELECT RAISE(ABORT, 'canonical kind cannot invalidate external IDs') WHERE
        NEW.kind <> OLD.kind AND EXISTS (
          SELECT 1 FROM canonical_external_id WHERE work_id = OLD.work_id);
END;

-- Scope key is an opaque digest of profile / provider instance / account, NEVER a raw auth token.
CREATE TABLE source_listing (
    source_key TEXT PRIMARY KEY CHECK(length(source_key) BETWEEN 8 AND 128),
    profile_scope TEXT NOT NULL CHECK(length(profile_scope) BETWEEN 8 AND 128),
    provider_id TEXT NOT NULL CHECK(length(provider_id) BETWEEN 1 AND 180),
    provider_generation TEXT NOT NULL CHECK(length(provider_generation) BETWEEN 1 AND 128),
    kind TEXT NOT NULL CHECK(kind IN ('MOVIE','SERIES','LIVE')),
    created_at_ms INTEGER NOT NULL CHECK(created_at_ms >= 0)
);
CREATE INDEX source_listing_profile ON source_listing(profile_scope, provider_id);
CREATE TABLE identity_decision (
    source_key TEXT PRIMARY KEY REFERENCES source_listing(source_key) ON DELETE CASCADE,
    work_id TEXT REFERENCES canonical_work(work_id) ON DELETE SET NULL,
    state TEXT NOT NULL CHECK(state IN ('ACCEPTED','SUGGESTED','AMBIGUOUS','REJECTED','UNMATCHED')),
    evidence_digest TEXT NOT NULL CHECK(length(evidence_digest) BETWEEN 16 AND 128),
    rule_version INTEGER NOT NULL CHECK(rule_version >= 1),
    revision INTEGER NOT NULL CHECK(revision >= 1),
    decided_at_ms INTEGER NOT NULL CHECK(decided_at_ms >= 0),
    CHECK((state='ACCEPTED' AND work_id IS NOT NULL) OR state <> 'ACCEPTED')
);
CREATE TRIGGER identity_decision_kind_insert BEFORE INSERT ON identity_decision BEGIN
    SELECT RAISE(ABORT, 'source and canonical kind conflict') WHERE NEW.work_id IS NOT NULL
        AND (SELECT kind FROM source_listing WHERE source_key=NEW.source_key)
            <> (SELECT kind FROM canonical_work WHERE work_id=NEW.work_id);
END;
CREATE TRIGGER identity_decision_kind_update BEFORE UPDATE ON identity_decision BEGIN
    SELECT RAISE(ABORT, 'source and canonical kind conflict') WHERE NEW.work_id IS NOT NULL
        AND (SELECT kind FROM source_listing WHERE source_key=NEW.source_key)
            <> (SELECT kind FROM canonical_work WHERE work_id=NEW.work_id);
END;

-- All display fields are explicitly allowlisted. No CS3 callback, referer, cookie,
-- bearer token, source episode data or authentication URL can be stored in these tables.
CREATE TABLE provider_display_snapshot (
    source_key TEXT PRIMARY KEY REFERENCES source_listing(source_key) ON DELETE CASCADE,
    title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 1024),
    synopsis TEXT NOT NULL DEFAULT '' CHECK(length(synopsis) <= 24000),
    year TEXT NOT NULL DEFAULT '' CHECK(length(year) <= 16),
    poster_url_public TEXT NOT NULL DEFAULT '' CHECK(length(poster_url_public) <= 2048),
    fetched_at_ms INTEGER NOT NULL CHECK(fetched_at_ms >= 0),
    fresh_until_ms INTEGER NOT NULL,
    stale_until_ms INTEGER NOT NULL,
    retain_until_ms INTEGER NOT NULL,
    measured_bytes INTEGER NOT NULL CHECK(measured_bytes > 0),
    CHECK(fetched_at_ms <= fresh_until_ms AND fresh_until_ms <= stale_until_ms
        AND stale_until_ms <= retain_until_ms)
);
CREATE INDEX snapshots_expire ON provider_display_snapshot(retain_until_ms);
CREATE TABLE public_field_fact (
    work_id TEXT NOT NULL REFERENCES canonical_work(work_id) ON DELETE CASCADE,
    field_name TEXT NOT NULL CHECK(field_name IN ('title','synopsis','year','release_date',
        'genre','cast','backdrop','poster','rating','language','original_title')),
    locale TEXT NOT NULL DEFAULT '' CHECK(length(locale) <= 32),
    origin TEXT NOT NULL CHECK(origin IN ('TMDB','JELLYFIN_PUBLIC','OTHER_CATALOG')),
    display_value TEXT NOT NULL CHECK(length(display_value) <= 24000),
    source_ref TEXT NOT NULL CHECK(length(source_ref) BETWEEN 1 AND 256),
    fetched_at_ms INTEGER NOT NULL CHECK(fetched_at_ms >= 0),
    verified_at_ms INTEGER NOT NULL CHECK(verified_at_ms >= 0),
    revision INTEGER NOT NULL CHECK(revision >= 1),
    PRIMARY KEY(work_id, field_name, locale, origin, source_ref)
);
-- Display-only episode records: the source_key/episode_key are opaque scoped digests,
-- not playable URLs, episode.data or credentials. Source ordering remains authoritative.
CREATE TABLE provider_episode_display (
    source_key TEXT NOT NULL REFERENCES source_listing(source_key) ON DELETE CASCADE,
    episode_key TEXT NOT NULL CHECK(length(episode_key) BETWEEN 8 AND 128),
    variant TEXT NOT NULL DEFAULT '' CHECK(length(variant) <= 96),
    season INTEGER NOT NULL,
    number INTEGER NOT NULL,
    source_ordinal INTEGER NOT NULL CHECK(source_ordinal >= 0),
    title TEXT NOT NULL DEFAULT '' CHECK(length(title) <= 1024),
    PRIMARY KEY(source_key, episode_key)
);
CREATE INDEX provider_episode_order ON provider_episode_display(source_key, variant, season, source_ordinal);
PRAGMA user_version = 1;
