package com.auras.orbit.backup;

import android.content.Context;

import com.auras.orbit.library.LocalLibraryStore;
import com.auras.orbit.profile.LocalProfileStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Serializes profile-scoped Orbit library data to the encrypted .orbitlib container. */
public final class OrbitLibraryArchiveService {
    public static final String FORMAT = "com.auras.orbit.library";
    public static final int VERSION = 1;
    private static final Object IMPORT_LOCK = new Object();

    private final LocalProfileStore profileStore;
    private final LocalLibraryStore libraryStore;
    private volatile boolean recoveryBlocked;

    public OrbitLibraryArchiveService(Context context) {
        this(new LocalProfileStore(context.getApplicationContext()),
                new LocalLibraryStore(context.getApplicationContext()));
    }

    public OrbitLibraryArchiveService(LocalProfileStore profileStore, LocalLibraryStore libraryStore) {
        if (profileStore == null || libraryStore == null) {
            throw new IllegalArgumentException("Orbit archive stores are required.");
        }
        this.profileStore = profileStore;
        this.libraryStore = libraryStore;
    }

    /** Writes current local profiles and library tables; credentials and app-wide packages are excluded. */
    public void exportEncrypted(OutputStream output, char[] passphrase)
            throws IOException, OrbitLibraryArchiveCodec.ArchiveException {
        requireArchiveOperationsAvailable();
        synchronized (IMPORT_LOCK) {
            synchronized (LocalProfileStore.getArchiveImportLock()) {
                requireArchiveOperationsAvailable();
                LocalProfileStore.ArchiveImportJournal pending;
                try {
                    pending = profileStore.readArchiveImportJournal();
                } catch (RuntimeException error) {
                    blockArchiveRecovery();
                    throw recoveryFailure(error);
                }
                if (pending != null) {
                    blockArchiveRecovery();
                    throw new OrbitLibraryArchiveCodec.ArchiveException(
                            "Orbit must recover the previous library import before archive export can continue.");
                }
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("format", FORMAT);
                    payload.put("version", VERSION);
                    payload.put("createdAt", System.currentTimeMillis());
                    payload.put("profiles", profileStore.exportArchiveProfiles());
                    LocalProfileStore.Profile active = profileStore.getActiveProfile();
                    payload.put("activeProfileId", active == null ? "" : active.id);
                    payload.put("library", libraryStore.exportArchiveState());
                    validatePayload(payload);
                    OrbitLibraryArchiveCodec.write(payload, output, passphrase);
                } catch (JSONException error) {
                    throw new OrbitLibraryArchiveCodec.ArchiveException("Orbit could not prepare the library backup.", error);
                }
            }
        }
    }

    /** Decrypts, validates, and prepares a read-only preview. This method never changes local data. */
    public PreparedImport prepareImport(InputStream input, char[] passphrase)
            throws IOException, OrbitLibraryArchiveCodec.ArchiveException {
        requireArchiveOperationsAvailable();
        synchronized (IMPORT_LOCK) {
            requireArchiveOperationsAvailable();
            JSONObject payload = OrbitLibraryArchiveCodec.read(input, passphrase);
            return validatePayload(payload);
        }
    }

    /** Applies a previously previewed archive only after the caller obtains explicit confirmation. */
    public void applyImport(PreparedImport prepared) throws OrbitLibraryArchiveCodec.ArchiveException {
        requireArchiveOperationsAvailable();
        if (prepared == null || prepared.payload == null) {
            throw new OrbitLibraryArchiveCodec.ArchiveException("The Orbit backup preview has expired.");
        }
        PreparedImport validated = validatePayload(prepared.payload);
        synchronized (IMPORT_LOCK) {
            synchronized (LocalProfileStore.getArchiveImportLock()) {
                requireArchiveOperationsAvailable();
                applyValidatedImport(validated);
            }
        }
    }

    private void applyValidatedImport(PreparedImport validated)
            throws OrbitLibraryArchiveCodec.ArchiveException {
        LocalProfileStore.ArchiveImportJournal pending;
        try {
            pending = profileStore.readArchiveImportJournal();
        } catch (RuntimeException error) {
            blockArchiveRecovery();
            throw recoveryFailure(error);
        }
        if (pending != null) {
            blockArchiveRecovery();
            throw new OrbitLibraryArchiveCodec.ArchiveException(
                    "Orbit must recover the previous library import before another import can begin.");
        }

        HashSet<String> preexistingProfileIds = new HashSet<>();
        for (LocalProfileStore.Profile profile : profileStore.getProfiles()) {
            preexistingProfileIds.add(profile.id);
        }
        String previousActiveId = profileStore.getActiveProfileId();
        HashSet<String> importedOnlyIds = new HashSet<>(validated.profileIds);
        importedOnlyIds.removeAll(preexistingProfileIds);
        String operationId = UUID.randomUUID().toString();
        if (!profileStore.writeArchiveImportJournal(operationId, importedOnlyIds, previousActiveId)) {
            blockArchiveRecovery();
            throw new OrbitLibraryArchiveCodec.ArchiveException(
                    "Orbit could not durably prepare the library import. Restart the app before retrying.");
        }

        LocalProfileStore.ArchiveImportJournal journal = new LocalProfileStore.ArchiveImportJournal(
                operationId, importedOnlyIds, previousActiveId);
        try {
            if (!profileStore.mergeArchiveProfiles(validated.payload.optJSONArray("profiles"),
                    validated.payload.optString("activeProfileId", ""))) {
                failAndRecover("Orbit could not save the imported profiles.", null, journal);
            }
            try {
                libraryStore.mergeArchiveState(validated.payload.getJSONObject("library"),
                        validated.profileIds, operationId);
            } catch (JSONException | RuntimeException error) {
                failAndRecover("Orbit could not merge the library backup.", error, journal);
            }

            boolean committed;
            try {
                committed = libraryStore.hasArchiveImportCommit(operationId);
            } catch (RuntimeException error) {
                failAndRecover("Orbit could not confirm the library import commit.", error, journal);
                return;
            }
            if (!committed) {
                failAndRecover("Orbit could not confirm the library import commit.", null, journal);
            }

            if (!profileStore.clearArchiveImportJournal()) {
                preserveJournal(journal);
                recoveryBlocked = true;
                throw new OrbitLibraryArchiveCodec.ArchiveException(
                        "The library data was committed, but recovery cleanup is pending. Restart before another import.");
            }
            try {
                libraryStore.removeArchiveImportCommit(operationId);
            } catch (RuntimeException ignored) {
                // The committed rows are complete. Startup removes any marker left without a journal.
            }
            allowArchiveRecovery();
        } catch (OrbitLibraryArchiveCodec.ArchiveException error) {
            throw error;
        } catch (RuntimeException error) {
            failAndRecover("Orbit could not merge the library backup.", error, journal);
        }
    }

    /** Synchronously resolves any prior operation before the app exposes profile state. */
    public void recoverInterruptedImport() throws OrbitLibraryArchiveCodec.ArchiveException {
        requireArchiveOperationsAvailable();
        synchronized (IMPORT_LOCK) {
            synchronized (LocalProfileStore.getArchiveImportLock()) {
                requireArchiveOperationsAvailable();
                recoverInterruptedImportLocked();
            }
        }
    }

    private void recoverInterruptedImportLocked() throws OrbitLibraryArchiveCodec.ArchiveException {
        try {
            LocalProfileStore.ArchiveImportJournal journal = profileStore.readArchiveImportJournal();
            if (journal == null) {
                libraryStore.clearArchiveImportCommitMarkers();
                allowArchiveRecovery();
                return;
            }

            if (libraryStore.hasArchiveImportCommit(journal.operationId)) {
                if (!profileStore.clearArchiveImportJournal()) {
                    preserveJournal(journal);
                    throw new IllegalStateException("Could not finalize the committed library import.");
                }
                try {
                    libraryStore.removeArchiveImportCommit(journal.operationId);
                } catch (RuntimeException ignored) {
                    // No journal plus a stale marker is safe; the next startup retries marker cleanup.
                }
            } else {
                if (!profileStore.rollbackArchiveProfileMerge(journal.newlyIntroducedProfileIds,
                        journal.previousActiveProfileId)) {
                    throw new IllegalStateException("Could not restore profiles after the interrupted import.");
                }
                if (!profileStore.clearArchiveImportJournal()) {
                    preserveJournal(journal);
                    throw new IllegalStateException("Could not clear the recovered library import journal.");
                }
            }
            allowArchiveRecovery();
        } catch (RuntimeException error) {
            blockArchiveRecovery();
            throw recoveryFailure(error);
        }
    }

    private void requireArchiveOperationsAvailable() throws OrbitLibraryArchiveCodec.ArchiveException {
        if (recoveryBlocked || LocalProfileStore.isArchiveRecoveryBlocked()) {
            throw new OrbitLibraryArchiveCodec.ArchiveException(
                    "Orbit must restart and recover the previous library import before archive operations can continue.");
        }
    }

    private void blockArchiveRecovery() {
        recoveryBlocked = true;
        LocalProfileStore.setArchiveRecoveryBlocked(true);
    }

    private void allowArchiveRecovery() {
        recoveryBlocked = false;
        LocalProfileStore.setArchiveRecoveryBlocked(false);
    }

    private void failAndRecover(String message, Throwable cause,
            LocalProfileStore.ArchiveImportJournal journal)
            throws OrbitLibraryArchiveCodec.ArchiveException {
        try {
            recoverInterruptedImportLocked();
        } catch (OrbitLibraryArchiveCodec.ArchiveException recoveryError) {
            throw recoveryError;
        }
        throw cause == null
                ? new OrbitLibraryArchiveCodec.ArchiveException(message)
                : new OrbitLibraryArchiveCodec.ArchiveException(message, cause);
    }

    private void preserveJournal(LocalProfileStore.ArchiveImportJournal journal) {
        profileStore.writeArchiveImportJournal(journal.operationId,
                journal.newlyIntroducedProfileIds, journal.previousActiveProfileId);
    }

    private static OrbitLibraryArchiveCodec.ArchiveException recoveryFailure(Throwable cause) {
        return new OrbitLibraryArchiveCodec.ArchiveException(
                "Orbit could not safely recover the library import. Restart the app before trying again.", cause);
    }

    private static PreparedImport validatePayload(JSONObject payload)
            throws OrbitLibraryArchiveCodec.ArchiveException {
        try {
            if (payload == null || !FORMAT.equals(requiredString(payload, "format", 1, 128))) {
                throw invalid("This file is not an Orbit library backup.");
            }
            if (requiredInt(payload, "version") != VERSION) {
                throw invalid("This Orbit backup version is not supported by this app.");
            }
            if (requiredLong(payload, "createdAt", 0L, Long.MAX_VALUE) <= 0L) {
                throw invalid("The Orbit backup has an invalid creation time.");
            }

            JSONArray profiles = requiredArray(payload, "profiles");
            if (profiles.length() > 1_000) throw invalid("The Orbit backup contains too many profiles.");
            Set<String> profileIds = new HashSet<>();
            ArrayList<String> profileNames = new ArrayList<>();
            for (int index = 0; index < profiles.length(); index++) {
                JSONObject profile = requiredObject(profiles, index, "profile");
                String id = requiredExactString(profile, "id", 1, 64);
                requireUuid(id, "profile id");
                String name = requiredString(profile, "name", 1, 256).trim();
                if (name.isEmpty() || !profileIds.add(id.toLowerCase(java.util.Locale.ROOT))) {
                    throw invalid("The Orbit backup contains a duplicate or invalid profile.");
                }
                profileNames.add(name);
            }
            String activeId = optionalExactString(payload, "activeProfileId", 64);
            // Profile IDs are required to be canonical lowercase UUIDs. Keep the exact
            // case here so validation and the profile/library stores share one identity rule.
            if (!activeId.isEmpty() && !profileIds.contains(activeId)) {
                throw invalid("The active profile in this Orbit backup is missing.");
            }

            JSONObject library = requiredObject(payload, "library");
            JSONArray savedTitles = requiredArray(library, "savedTitles");
            JSONArray playback = requiredArray(library, "playbackProgress");
            JSONArray collections = requiredArray(library, "collections");
            JSONArray memberships = requiredArray(library, "collectionMemberships");
            JSONArray episodeWatchStates = optionalArray(library, "episodeWatchStates");
            if (savedTitles.length() > 100_000 || playback.length() > 100_000
                    || collections.length() > 100_000 || memberships.length() > 250_000
                    || episodeWatchStates.length() > 500_000) {
                throw invalid("The Orbit backup contains too many library records.");
            }

            HashSet<String> titleKeys = new HashSet<>();
            for (int index = 0; index < savedTitles.length(); index++) {
                JSONObject row = requiredObject(savedTitles, index, "saved title");
                sanitizeTitleUrls(row);
                validateTitleRow(row, profileIds);
                if (!titleKeys.add(compositeKey(row.getString("profileId"), row.getString("stableId")))) {
                    throw invalid("The Orbit backup contains a duplicate saved title.");
                }
            }
            HashSet<String> progressKeys = new HashSet<>();
            for (int index = 0; index < playback.length(); index++) {
                JSONObject row = requiredObject(playback, index, "playback record");
                sanitizeTitleUrls(row);
                validateTitleRow(row, profileIds);
                if (!progressKeys.add(compositeKey(row.getString("profileId"), row.getString("stableId")))) {
                    throw invalid("The Orbit backup contains duplicate playback history.");
                }
            }
            HashSet<String> episodeWatchKeys = new HashSet<>();
            for (int index = 0; index < episodeWatchStates.length(); index++) {
                JSONObject row = requiredObject(episodeWatchStates, index, "episode watch state");
                String profileId = requiredExactString(row, "profileId", 1, 64);
                requireProfile(profileIds, profileId);
                String titleStableId = requiredExactString(row, "titleStableId", 1, 8_192);
                String episodeStableId = requiredExactString(row, "episodeStableId", 1, 4_096);
                requiredBoolean(row, "watched");
                requiredLong(row, "updatedAt", 0L, Long.MAX_VALUE);
                String key = compositeKey(profileId, titleStableId) + '\u0000' + episodeStableId;
                if (!episodeWatchKeys.add(key)) {
                    throw invalid("The Orbit backup contains duplicate episode watch states.");
                }
            }
            HashSet<String> collectionKeys = new HashSet<>();
            for (int index = 0; index < collections.length(); index++) {
                JSONObject row = requiredObject(collections, index, "collection");
                requireProfile(profileIds, requiredExactString(row, "profileId", 1, 64));
                String id = requiredExactString(row, "collectionId", 1, 64);
                requireUuid(id, "collection id");
                String name = requiredString(row, "name", 1, 512).trim();
                if (name.isEmpty()) throw invalid("The Orbit backup contains an unnamed collection.");
                requiredLong(row, "createdAt", 0L, Long.MAX_VALUE);
                requiredLong(row, "updatedAt", 0L, Long.MAX_VALUE);
                if (!collectionKeys.add(compositeKey(row.getString("profileId"), id))) {
                    throw invalid("The Orbit backup contains a duplicate collection.");
                }
            }
            HashSet<String> membershipKeys = new HashSet<>();
            for (int index = 0; index < memberships.length(); index++) {
                JSONObject row = requiredObject(memberships, index, "collection membership");
                String profileId = requiredExactString(row, "profileId", 1, 64);
                requireProfile(profileIds, profileId);
                String collectionId = requiredExactString(row, "collectionId", 1, 64);
                String stableId = requiredExactString(row, "stableId", 1, 4_096);
                requiredLong(row, "addedAt", 0L, Long.MAX_VALUE);
                String collectionKey = compositeKey(profileId, collectionId);
                String titleKey = compositeKey(profileId, stableId);
                if (!collectionKeys.contains(collectionKey) || !titleKeys.contains(titleKey)) {
                    throw invalid("The Orbit backup contains an unlinked collection item.");
                }
                if (!membershipKeys.add(collectionKey + '\u0000' + stableId)) {
                    throw invalid("The Orbit backup contains a duplicate collection item.");
                }
            }

            return new PreparedImport(payload, profileNames,
                    profiles.length(), savedTitles.length(), playback.length(), collections.length(),
                    memberships.length(), episodeWatchStates.length(), new HashSet<>(profileIds));
        } catch (JSONException | IllegalArgumentException error) {
            throw invalid("The Orbit backup contains invalid or incomplete data.", error);
        }
    }

    private static void validateTitleRow(JSONObject row, Set<String> profileIds)
            throws JSONException, OrbitLibraryArchiveCodec.ArchiveException {
        String profileId = requiredExactString(row, "profileId", 1, 64);
        requireProfile(profileIds, profileId);
        requiredExactString(row, "stableId", 1, 8_192);
        requiredString(row, "sourceId", 0, 8_192);
        requiredString(row, "title", 1, 512);
        requiredString(row, "mediaType", 1, 40);
        requiredString(row, "detailUrl", 0, 65_536);
        requiredString(row, "posterUrl", 0, 65_536);
        requiredString(row, "year", 0, 32);
        requiredString(row, "synopsis", 0, 256 * 1024);
        optionalExactStringIfPresent(row, "creator", 512);
        String resumeArtworkRef = optionalExactStringIfPresent(row, "resumeArtworkRef", 200);
        if (!resumeArtworkRef.isEmpty()
                && !isSafeLibraryArtworkReference(resumeArtworkRef, profileId)) {
            throw invalid("The Orbit backup contains an invalid resume artwork reference.");
        }
        requiredLong(row, "savedAt", 0L, Long.MAX_VALUE);
        requiredBoolean(row, "favorite");
        String status = requiredString(row, "watchStatus", 1, 32);
        if (!isWatchStatus(status)) throw invalid("The Orbit backup contains an unknown watch status.");
        long progress = requiredLong(row, "progressMs", 0L, Long.MAX_VALUE);
        long duration = requiredLong(row, "durationMs", 0L, Long.MAX_VALUE);
        if (duration > 0L && progress > duration) throw invalid("The Orbit backup contains invalid playback progress.");
        requiredString(row, "resumeEpisodeId", 0, 4_096);
        requiredString(row, "resumeEpisodeTitle", 0, 512);
        requiredIntRange(row, "resumeSeason", -1, 10_000);
        requiredIntRange(row, "resumeEpisodeNumber", -1, 100_000);
        requiredLong(row, "updatedAt", 0L, Long.MAX_VALUE);
    }

    private static JSONArray requiredArray(JSONObject object, String field) throws OrbitLibraryArchiveCodec.ArchiveException {
        JSONArray value = object.optJSONArray(field);
        if (value == null) throw invalid("The Orbit backup is missing required library data.");
        return value;
    }

    private static JSONArray optionalArray(JSONObject object, String field)
            throws OrbitLibraryArchiveCodec.ArchiveException {
        if (!object.has(field)) return new JSONArray();
        JSONArray value = object.optJSONArray(field);
        if (value == null) throw invalid("The Orbit backup contains invalid " + field + " data.");
        return value;
    }

    private static JSONObject requiredObject(JSONObject object, String field) throws OrbitLibraryArchiveCodec.ArchiveException {
        JSONObject value = object.optJSONObject(field);
        if (value == null) throw invalid("The Orbit backup contains invalid " + field + " data.");
        return value;
    }

    private static JSONObject requiredObject(JSONArray array, int index, String label)
            throws OrbitLibraryArchiveCodec.ArchiveException {
        JSONObject value = array.optJSONObject(index);
        if (value == null) throw invalid("The Orbit backup contains an invalid " + label + ".");
        return value;
    }

    private static String requiredString(JSONObject object, String field, int min, int max)
            throws OrbitLibraryArchiveCodec.ArchiveException {
        Object value = object.opt(field);
        if (!(value instanceof String)) throw invalid("The Orbit backup contains invalid " + field + " data.");
        String text = ((String) value).trim();
        if (text.length() < min || text.length() > max) throw invalid("The Orbit backup contains invalid " + field + " data.");
        return text;
    }

    /** Identity fields participate in joins and are later consumed verbatim by SQLite. */
    private static String requiredExactString(JSONObject object, String field, int min, int max)
            throws OrbitLibraryArchiveCodec.ArchiveException {
        String value = requiredString(object, field, min, max);
        Object raw = object.opt(field);
        if (!value.equals(raw)) throw invalid("The Orbit backup contains noncanonical " + field + " data.");
        return value;
    }

    private static String optionalExactString(JSONObject object, String field, int max)
            throws OrbitLibraryArchiveCodec.ArchiveException {
        Object raw = object.opt(field);
        if (raw == null || raw == JSONObject.NULL) return "";
        if (!(raw instanceof String)) throw invalid("The Orbit backup contains invalid " + field + " data.");
        String value = (String) raw;
        if (value.length() > max || !value.equals(value.trim())) {
            throw invalid("The Orbit backup contains noncanonical " + field + " data.");
        }
        return value;
    }

    private static String optionalExactStringIfPresent(JSONObject object, String field, int max)
            throws OrbitLibraryArchiveCodec.ArchiveException {
        if (!object.has(field)) return "";
        Object raw = object.opt(field);
        if (!(raw instanceof String)) throw invalid("The Orbit backup contains invalid " + field + " data.");
        String value = (String) raw;
        if (value.length() > max || !value.equals(value.trim())) {
            throw invalid("The Orbit backup contains noncanonical " + field + " data.");
        }
        return value;
    }

    private static long requiredLong(JSONObject object, String field, long min, long max)
            throws OrbitLibraryArchiveCodec.ArchiveException {
        Object value = object.opt(field);
        if (!(value instanceof Number)) throw invalid("The Orbit backup contains invalid " + field + " data.");
        Number numeric = (Number) value;
        long result = numeric.longValue();
        if ((numeric instanceof Double || numeric instanceof Float)
                && (Double.isNaN(numeric.doubleValue()) || Double.isInfinite(numeric.doubleValue())
                || numeric.doubleValue() != (double) result)) {
            throw invalid("The Orbit backup contains invalid " + field + " data.");
        }
        if (result < min || result > max) {
            throw invalid("The Orbit backup contains invalid " + field + " data.");
        }
        return result;
    }

    private static int requiredInt(JSONObject object, String field)
            throws OrbitLibraryArchiveCodec.ArchiveException {
        long value = requiredLong(object, field, Integer.MIN_VALUE, Integer.MAX_VALUE);
        return (int) value;
    }

    private static void requiredIntRange(JSONObject object, String field, int min, int max)
            throws OrbitLibraryArchiveCodec.ArchiveException {
        requiredLong(object, field, min, max);
    }

    private static void requiredBoolean(JSONObject object, String field)
            throws OrbitLibraryArchiveCodec.ArchiveException {
        if (!(object.opt(field) instanceof Boolean)) throw invalid("The Orbit backup contains invalid " + field + " data.");
    }

    private static void requireUuid(String value, String label) throws OrbitLibraryArchiveCodec.ArchiveException {
        if (value == null || value.length() > 64) throw invalid("The Orbit backup contains an invalid " + label + ".");
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw invalid("The Orbit backup contains an invalid " + label + ".", error);
        }
    }

    private static void requireProfile(Set<String> profileIds, String profileId)
            throws OrbitLibraryArchiveCodec.ArchiveException {
        if (!profileIds.contains(profileId)) {
            throw invalid("The Orbit backup references an unknown profile.");
        }
    }

    private static boolean isWatchStatus(String value) {
        return LocalLibraryStore.STATUS_NONE.equals(value)
                || LocalLibraryStore.STATUS_WATCHING.equals(value)
                || LocalLibraryStore.STATUS_PLAN_TO_WATCH.equals(value)
                || LocalLibraryStore.STATUS_ON_HOLD.equals(value)
                || LocalLibraryStore.STATUS_DROPPED.equals(value)
                || LocalLibraryStore.STATUS_COMPLETED.equals(value);
    }

    /** Keeps eligible HTTP(S) re-resolution URLs and strips userinfo, query, and fragment. */
    private static void sanitizeTitleUrls(JSONObject row) throws JSONException, OrbitLibraryArchiveCodec.ArchiveException {
        String detailUrl = requiredString(row, "detailUrl", 0, 65_536);
        row.put("detailUrl", safeArchiveUrl(detailUrl));
        String posterUrl = requiredString(row, "posterUrl", 0, 65_536);
        row.put("posterUrl", safeArchiveUrl(posterUrl));
        // Cached artwork files are device-local and aren't included in the encrypted archive.
        row.remove("resumeArtworkRef");
    }

    private static String safeArchiveUrl(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            URI parsed = new URI(value.trim());
            String scheme = parsed.getScheme();
            if (scheme == null || parsed.getHost() == null || parsed.getUserInfo() != null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return "";
            return new URI(scheme.toLowerCase(java.util.Locale.ROOT) + "://"
                    + parsed.getRawAuthority() + parsed.getRawPath()).toASCIIString();
        } catch (URISyntaxException ignored) {
            return "";
        }
    }

    private static boolean isSafeLibraryArtworkReference(String value, String profileId) {
        final String prefix = "orbit-library-artwork:";
        if (value == null || profileId == null || !value.startsWith(prefix)) return false;
        String encoded = value.substring(prefix.length());
        if (encoded.length() != 129 || encoded.charAt(64) != ':') return false;
        String profileHash = encoded.substring(0, 64);
        if (!profileHash.equals(sha256Hex(profileId))) return false;
        for (int index = 0; index < encoded.length(); index++) {
            if (index == 64) continue;
            char current = encoded.charAt(index);
            if (!((current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'f'))) return false;
        }
        return true;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte current : digest) hex.append(String.format(java.util.Locale.ROOT,
                    "%02x", current & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Orbit could not validate artwork references.", impossible);
        }
    }

    private static String compositeKey(String profileId, String stableId) {
        return profileId.toLowerCase(java.util.Locale.ROOT) + '\u0000' + stableId;
    }

    private static OrbitLibraryArchiveCodec.ArchiveException invalid(String message) {
        return new OrbitLibraryArchiveCodec.ArchiveException(message);
    }

    private static OrbitLibraryArchiveCodec.ArchiveException invalid(String message, Throwable cause) {
        return new OrbitLibraryArchiveCodec.ArchiveException(message, cause);
    }

    public static final class PreparedImport {
        private final JSONObject payload;
        public final List<String> profileNames;
        public final int profileCount;
        public final int savedTitleCount;
        public final int playbackCount;
        public final int collectionCount;
        public final int membershipCount;
        public final int episodeWatchStateCount;
        private final Set<String> profileIds;

        private PreparedImport(JSONObject payload, List<String> profileNames, int profileCount,
                int savedTitleCount, int playbackCount, int collectionCount, int membershipCount,
                int episodeWatchStateCount, Set<String> profileIds) {
            this.payload = payload;
            this.profileNames = Collections.unmodifiableList(new ArrayList<>(profileNames));
            this.profileCount = profileCount;
            this.savedTitleCount = savedTitleCount;
            this.playbackCount = playbackCount;
            this.collectionCount = collectionCount;
            this.membershipCount = membershipCount;
            this.episodeWatchStateCount = episodeWatchStateCount;
            this.profileIds = Collections.unmodifiableSet(new HashSet<>(profileIds));
        }

        public String getPreviewText() {
            return countLabel(profileCount, "profile", "profiles") + ", "
                    + countLabel(savedTitleCount, "saved title", "saved titles") + ", "
                    + countLabel(playbackCount, "playback record", "playback records") + ", "
                    + countLabel(collectionCount, "collection", "collections") + ", and "
                    + countLabel(membershipCount, "collection item", "collection items") + ", and "
                    + countLabel(episodeWatchStateCount, "episode watch state", "episode watch states")
                    + ". Import merges records and does not remove local data.";
        }

        private static String countLabel(int count, String singular, String plural) {
            return count + " " + (count == 1 ? singular : plural);
        }
    }
}
