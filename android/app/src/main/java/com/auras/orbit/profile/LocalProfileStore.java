package com.auras.orbit.profile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** A small, isolated profile store for this shell prototype. */
public final class LocalProfileStore {
    private static final Object ARCHIVE_IMPORT_LOCK = new Object();
    private static final String PREFERENCES_NAME = "orbit_shell_local_profiles_v1";
    private static final String PROFILES_KEY = "profiles_json";
    private static final String ACTIVE_PROFILE_KEY = "active_profile_id";
    private static final String ARCHIVE_IMPORT_JOURNAL_KEY = "archive_import_recovery_journal_v1";

    private final SharedPreferences preferences;

    private static volatile boolean archiveRecoveryBlocked;

    /** Shared by every profile-store instance and the complete archive import critical section. */
    public static Object getArchiveImportLock() {
        return ARCHIVE_IMPORT_LOCK;
    }

    public static void setArchiveRecoveryBlocked(boolean blocked) {
        archiveRecoveryBlocked = blocked;
    }

    public static boolean isArchiveRecoveryBlocked() {
        return archiveRecoveryBlocked;
    }

    public static void requireArchiveRecoveryAvailable() {
        if (archiveRecoveryBlocked) {
            throw new IllegalStateException("Orbit profile and library state is blocked pending archive recovery.");
        }
    }

    public LocalProfileStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public List<Profile> getProfiles() {
        synchronized (ARCHIVE_IMPORT_LOCK) {
            requireArchiveRecoveryAvailable();
            String encoded = preferences.getString(PROFILES_KEY, "[]");
            if (encoded == null) return Collections.emptyList();

            ArrayList<Profile> profiles = new ArrayList<>();
            try {
                JSONArray array = new JSONArray(encoded);
                for (int index = 0; index < array.length(); index++) {
                    JSONObject item = array.optJSONObject(index);
                    if (item == null) continue;
                    String id = item.optString("id", "");
                    String name = item.optString("name", "").trim();
                    if (!id.isEmpty() && !name.isEmpty()) profiles.add(new Profile(id, name));
                }
            } catch (JSONException ignored) {
                // Keep this prototype store self-contained; malformed shell state starts empty.
            }
            return Collections.unmodifiableList(profiles);
        }
    }

    public Profile getActiveProfile() {
        synchronized (ARCHIVE_IMPORT_LOCK) {
            requireArchiveRecoveryAvailable();
            String activeId = preferences.getString(ACTIVE_PROFILE_KEY, "");
            if (activeId == null || activeId.isEmpty()) return null;
            for (Profile profile : getProfiles()) {
                if (profile.id.equals(activeId)) return profile;
            }
            return null;
        }
    }

    /** Returns the exact persisted active ID, including an empty or currently unresolved value. */
    public String getActiveProfileId() {
        synchronized (ARCHIVE_IMPORT_LOCK) {
            requireArchiveRecoveryAvailable();
            String activeId = preferences.getString(ACTIVE_PROFILE_KEY, "");
            return activeId == null ? "" : activeId;
        }
    }

    public Profile createProfile(String name) {
        synchronized (ARCHIVE_IMPORT_LOCK) {
            requireArchiveRecoveryAvailable();
            String cleanName = name == null ? "" : name.trim();
            if (cleanName.isEmpty()) return null;

            ArrayList<Profile> profiles = new ArrayList<>(getProfiles());
            Profile created = new Profile(UUID.randomUUID().toString(), cleanName);
            profiles.add(created);
            persist(profiles);
            return created;
        }
    }

    /** Returns the profile records for an encrypted Orbit library archive. */
    public JSONArray exportArchiveProfiles() {
        synchronized (ARCHIVE_IMPORT_LOCK) {
            requireArchiveRecoveryAvailable();
            JSONArray profiles = new JSONArray();
            for (Profile profile : getProfiles()) {
                JSONObject item = new JSONObject();
                try {
                    item.put("id", profile.id);
                    item.put("name", profile.name);
                    profiles.put(item);
                } catch (JSONException ignored) {
                    // Profile values are primitive strings; a failed item is skipped.
                }
            }
            return profiles;
        }
    }

    /** Merges validated archive profiles without removing or renaming local profiles. */
    public boolean mergeArchiveProfiles(JSONArray imported, String importedActiveId) {
        synchronized (ARCHIVE_IMPORT_LOCK) {
            requireArchiveRecoveryAvailable();
            if (imported == null) return false;
            ArrayList<Profile> profiles = new ArrayList<>(getProfiles());
            java.util.HashSet<String> knownIds = new java.util.HashSet<>();
            for (Profile profile : profiles) knownIds.add(profile.id);
            String activeId = preferences.getString(ACTIVE_PROFILE_KEY, "");
            boolean currentActiveIsValid = false;
            if (activeId != null && !activeId.isEmpty()) {
                for (Profile profile : profiles) {
                    if (profile.id.equals(activeId)) {
                        currentActiveIsValid = true;
                        break;
                    }
                }
            }

            for (int index = 0; index < imported.length(); index++) {
                JSONObject item = imported.optJSONObject(index);
                if (item == null) return false;
                String id = item.optString("id", "").trim();
                String name = item.optString("name", "").trim();
                if (!isUuid(id) || name.isEmpty() || name.length() > 256) return false;
                if (knownIds.add(id)) profiles.add(new Profile(id, name));
            }

            if (!currentActiveIsValid) {
                activeId = isUuid(importedActiveId) && knownIds.contains(importedActiveId)
                        ? importedActiveId
                        : (profiles.isEmpty() ? "" : profiles.get(0).id);
            }

            JSONArray encodedProfiles = encodeProfiles(profiles);
            return preferences.edit().putString(PROFILES_KEY, encodedProfiles.toString())
                    .putString(ACTIVE_PROFILE_KEY, activeId).commit();
        }
    }

    /** Removes only profile IDs newly added by a failed archive merge, preserving concurrent local profiles. */
    public boolean rollbackArchiveProfileMerge(Set<String> importedOnlyIds, String previousActiveId) {
        synchronized (ARCHIVE_IMPORT_LOCK) {
            requireArchiveRecoveryAvailable();
            if (importedOnlyIds == null || previousActiveId == null) return false;
            ArrayList<Profile> profiles = new ArrayList<>();
            for (Profile profile : getProfiles()) {
                if (importedOnlyIds.contains(profile.id)) continue;
                profiles.add(profile);
            }
            return preferences.edit().putString(PROFILES_KEY, encodeProfiles(profiles).toString())
                    .putString(ACTIVE_PROFILE_KEY, previousActiveId).commit();
        }
    }

    /** Durably records only identifiers needed to reverse an interrupted archive import. */
    public boolean writeArchiveImportJournal(String operationId,
            Set<String> newlyIntroducedProfileIds, String previousActiveProfileId) {
        synchronized (ARCHIVE_IMPORT_LOCK) {
            requireArchiveRecoveryAvailable();
            if (!isCanonicalUuid(operationId) || newlyIntroducedProfileIds == null
                    || previousActiveProfileId == null
                    || (!previousActiveProfileId.isEmpty() && !isCanonicalUuid(previousActiveProfileId))) {
                return false;
            }
            TreeSet<String> sortedIds = new TreeSet<>();
            for (String profileId : newlyIntroducedProfileIds) {
                if (!isCanonicalUuid(profileId)) return false;
                sortedIds.add(profileId);
            }
            JSONObject journal = new JSONObject();
            JSONArray ids = new JSONArray();
            for (String profileId : sortedIds) ids.put(profileId);
            try {
                journal.put("operationId", operationId);
                journal.put("newlyIntroducedProfileIds", ids);
                journal.put("previousActiveProfileId", previousActiveProfileId);
            } catch (JSONException impossible) {
                return false;
            }
            return preferences.edit().putString(ARCHIVE_IMPORT_JOURNAL_KEY, journal.toString()).commit();
        }
    }

    /** Reads and strictly validates the minimal recovery record; malformed data fails closed. */
    public ArchiveImportJournal readArchiveImportJournal() {
        synchronized (ARCHIVE_IMPORT_LOCK) {
            requireArchiveRecoveryAvailable();
            String encoded = preferences.getString(ARCHIVE_IMPORT_JOURNAL_KEY, null);
            if (encoded == null) return null;
            try {
                JSONObject journal = new JSONObject(encoded);
                HashSet<String> keys = new HashSet<>();
                Iterator<String> keyIterator = journal.keys();
                while (keyIterator.hasNext()) keys.add(keyIterator.next());
                if (keys.size() != 3 || !keys.contains("operationId")
                        || !keys.contains("newlyIntroducedProfileIds")
                        || !keys.contains("previousActiveProfileId")) {
                    throw new JSONException("Unexpected journal fields");
                }
                String operationId = journal.getString("operationId");
                String previousActiveId = journal.getString("previousActiveProfileId");
                JSONArray ids = journal.getJSONArray("newlyIntroducedProfileIds");
                if (!isCanonicalUuid(operationId) || previousActiveId == null
                        || (!previousActiveId.isEmpty() && !isCanonicalUuid(previousActiveId))) {
                    throw new JSONException("Invalid journal identifiers");
                }
                HashSet<String> introducedIds = new HashSet<>();
                for (int index = 0; index < ids.length(); index++) {
                    String profileId = ids.getString(index);
                    if (!isCanonicalUuid(profileId) || !introducedIds.add(profileId)) {
                        throw new JSONException("Invalid journal profile IDs");
                    }
                }
                return new ArchiveImportJournal(operationId, introducedIds, previousActiveId);
            } catch (JSONException | RuntimeException malformed) {
                throw new IllegalStateException("Orbit could not read its library import recovery record.");
            }
        }
    }

    /** Removes the recovery record only after the caller has resolved its database marker. */
    public boolean clearArchiveImportJournal() {
        synchronized (ARCHIVE_IMPORT_LOCK) {
            requireArchiveRecoveryAvailable();
            if (!preferences.contains(ARCHIVE_IMPORT_JOURNAL_KEY)) return true;
            return preferences.edit().remove(ARCHIVE_IMPORT_JOURNAL_KEY).commit();
        }
    }

    private static boolean isCanonicalUuid(String value) {
        if (!isUuid(value)) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public void selectProfile(String profileId) {
        synchronized (ARCHIVE_IMPORT_LOCK) {
            requireArchiveRecoveryAvailable();
            if (profileId == null) return;
            for (Profile profile : getProfiles()) {
                if (profile.id.equals(profileId)) {
                    preferences.edit().putString(ACTIVE_PROFILE_KEY, profileId).apply();
                    return;
                }
            }
        }
    }

    private void persist(List<Profile> profiles) {
        JSONArray array = encodeProfiles(profiles);
        preferences.edit().putString(PROFILES_KEY, array.toString()).apply();
    }

    private static JSONArray encodeProfiles(List<Profile> profiles) {
        JSONArray array = new JSONArray();
        for (Profile profile : profiles) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", profile.id);
                item.put("name", profile.name);
                array.put(item);
            } catch (JSONException ignored) {
                // Profile values are primitive strings; a failed item is skipped.
            }
        }
        return array;
    }

    private static boolean isUuid(String value) {
        if (value == null || value.length() > 64) return false;
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static final class Profile {
        public final String id;
        public final String name;

        private Profile(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class ArchiveImportJournal {
        public final String operationId;
        public final Set<String> newlyIntroducedProfileIds;
        public final String previousActiveProfileId;

        public ArchiveImportJournal(String operationId, Set<String> newlyIntroducedProfileIds,
                String previousActiveProfileId) {
            this.operationId = operationId;
            this.newlyIntroducedProfileIds = Collections.unmodifiableSet(
                    new HashSet<>(newlyIntroducedProfileIds));
            this.previousActiveProfileId = previousActiveProfileId;
        }
    }
}
