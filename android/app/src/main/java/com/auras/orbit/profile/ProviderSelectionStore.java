package com.auras.orbit.profile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stores the ordered provider selection independently for each Orbit profile. */
public final class ProviderSelectionStore {
    private static final String PREFERENCES_NAME = "orbit_provider_selection_v1";
    private static final String ORDER_KEY_PREFIX = "provider_order_profile_";
    private static final String SELECTED_KEY_PREFIX = "provider_selected_profile_";
    private static final String KNOWN_KEY_PREFIX = "provider_known_profile_";
    private static final int MAX_PROFILE_ID_LENGTH = 128;
    private static final int MAX_PROVIDER_ID_LENGTH = 1024;
    private static final int MAX_PROVIDER_COUNT = 4096;

    private final SharedPreferences preferences;

    public ProviderSelectionStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** Returns all saved IDs in their stored order, including currently unavailable providers. */
    public synchronized List<String> getOrderedProviderIds(String profileId) {
        return Collections.unmodifiableList(readOrder(requireProfileId(profileId)));
    }

    /** Replaces a profile's order. IDs are trimmed and duplicate IDs keep their first position. */
    public synchronized void setOrderedProviderIds(String profileId, List<String> providerIds) {
        String key = orderKey(requireProfileId(profileId));
        List<String> normalized = normalizeProviderIds(providerIds);
        if (!preferences.edit().putString(key, encode(normalized)).commit()) {
            throw new IllegalStateException("Could not store the provider order.");
        }
    }

    /**
     * Returns the selected providers that are currently available, in the profile's saved order.
     * A profile with no saved selection starts with every available provider selected. Newly
     * discovered providers are selected once and then remembered; unavailable and deselected IDs
     * remain in their respective stored lists so temporary provider changes do not reset choices.
     */
    public synchronized List<String> getSelectedProviderIds(String profileId,
                                                              List<String> availableProviderIds) {
        String normalizedProfileId = requireProfileId(profileId);
        List<String> available = normalizeProviderIds(availableProviderIds);
        String selectedKey = selectedKey(normalizedProfileId);
        String knownKey = knownKey(normalizedProfileId);
        ArrayList<String> selected = preferences.contains(selectedKey)
                ? new ArrayList<>(readIds(selectedKey)) : new ArrayList<>(available);
        ArrayList<String> known = new ArrayList<>(readIds(knownKey));
        Set<String> knownSet = new HashSet<>(known);
        Set<String> selectedSet = new HashSet<>(selected);
        for (String id : available) {
            if (!knownSet.contains(id)) {
                if (known.size() >= MAX_PROVIDER_COUNT) continue;
                known.add(id);
                knownSet.add(id);
                if (selected.size() < MAX_PROVIDER_COUNT && selectedSet.add(id)) selected.add(id);
            }
        }
        if (!preferences.contains(selectedKey) || !preferences.contains(knownKey)
                || !encode(selected).equals(preferences.getString(selectedKey, ""))
                || !encode(known).equals(preferences.getString(knownKey, ""))) {
            SharedPreferences.Editor editor = preferences.edit()
                    .putString(selectedKey, encode(selected))
                    .putString(knownKey, encode(known));
            if (!editor.commit()) throw new IllegalStateException("Could not store provider selections.");
        }
        Set<String> activeSelection = new HashSet<>(selected);
        ArrayList<String> orderedAvailable = new ArrayList<>();
        for (String id : available) if (activeSelection.contains(id)) orderedAvailable.add(id);
        return Collections.unmodifiableList(orderedAvailable);
    }

    /** Saves selection changes for currently available IDs while retaining selected unavailable IDs. */
    public synchronized void setSelectedProviderIds(String profileId,
                                                     List<String> selectedProviderIds,
                                                     List<String> availableProviderIds) {
        String normalizedProfileId = requireProfileId(profileId);
        List<String> available = normalizeProviderIds(availableProviderIds);
        List<String> requested = normalizeProviderIds(selectedProviderIds);
        String selectedKey = selectedKey(normalizedProfileId);
        String knownKey = knownKey(normalizedProfileId);
        if (!preferences.contains(selectedKey)) {
            getSelectedProviderIds(normalizedProfileId, available);
        }
        Set<String> availableSet = new HashSet<>(available);
        Set<String> requestedSet = new HashSet<>(requested);
        ArrayList<String> selected = new ArrayList<>();
        for (String id : readIds(selectedKey)) {
            if (!availableSet.contains(id) && selected.size() < MAX_PROVIDER_COUNT) selected.add(id);
        }
        for (String id : available) {
            if (requestedSet.contains(id) && selected.size() < MAX_PROVIDER_COUNT) selected.add(id);
        }

        ArrayList<String> known = new ArrayList<>(readIds(knownKey));
        Set<String> knownSet = new HashSet<>(known);
        for (String id : available) {
            if (known.size() >= MAX_PROVIDER_COUNT) break;
            if (knownSet.add(id)) known.add(id);
        }
        SharedPreferences.Editor editor = preferences.edit()
                .putString(selectedKey, encode(selected))
                .putString(knownKey, encode(known));
        if (!editor.commit()) throw new IllegalStateException("Could not store provider selections.");
    }

    /**
     * Orders available provider IDs by the saved profile order. Valid IDs that have not previously
     * appeared are appended in their available-list order and remembered at the end of the saved
     * order. Saved IDs absent from the available list remain stored for later reactivation.
     */
    public synchronized List<String> orderAvailableProviderIds(String profileId,
                                                                 List<String> availableProviderIds) {
        String normalizedProfileId = requireProfileId(profileId);
        String key = orderKey(normalizedProfileId);
        List<String> savedOrder = readOrder(normalizedProfileId);
        List<String> available = normalizeProviderIds(availableProviderIds);
        Set<String> remaining = new HashSet<>(available);
        ArrayList<String> orderedAvailable = new ArrayList<>(available.size());

        for (String id : savedOrder) {
            if (remaining.remove(id)) orderedAvailable.add(id);
        }
        for (String id : available) {
            if (remaining.remove(id)) orderedAvailable.add(id);
        }

        ArrayList<String> extendedOrder = new ArrayList<>(savedOrder);
        Set<String> savedIds = new HashSet<>(savedOrder);
        for (String id : available) {
            if (savedIds.add(id)) extendedOrder.add(id);
        }
        if (!extendedOrder.equals(savedOrder)
                && !preferences.edit().putString(key, encode(extendedOrder)).commit()) {
            throw new IllegalStateException("Could not store newly available providers.");
        }
        return Collections.unmodifiableList(orderedAvailable);
    }

    private List<String> readOrder(String profileId) {
        return readIds(orderKey(profileId));
    }

    private List<String> readIds(String key) {
        String encoded = preferences.getString(key, "[]");
        if (encoded == null) return new ArrayList<>();

        ArrayList<String> ids = new ArrayList<>();
        Set<String> knownIds = new HashSet<>();
        try {
            JSONArray array = new JSONArray(encoded);
            int limit = Math.min(array.length(), MAX_PROVIDER_COUNT);
            for (int index = 0; index < limit; index++) {
                Object value = array.opt(index);
                if (!(value instanceof String)) continue;
                String id = normalizeProviderId((String) value);
                if (id != null && knownIds.add(id)) ids.add(id);
            }
        } catch (JSONException ignored) {
            // Malformed local metadata is treated as an empty order and replaced on the next save.
        }
        return ids;
    }

    private static List<String> normalizeProviderIds(List<String> providerIds) {
        if (providerIds == null) throw new IllegalArgumentException("provider IDs are required");
        if (providerIds.size() > MAX_PROVIDER_COUNT) {
            throw new IllegalArgumentException("too many provider IDs");
        }

        ArrayList<String> normalized = new ArrayList<>(providerIds.size());
        Set<String> knownIds = new HashSet<>();
        for (String providerId : providerIds) {
            String id = normalizeProviderId(providerId);
            if (id == null) throw new IllegalArgumentException("provider ID is invalid");
            if (knownIds.add(id)) normalized.add(id);
        }
        return normalized;
    }

    private static String normalizeProviderId(String providerId) {
        if (providerId == null) return null;
        String id = providerId.trim();
        if (id.isEmpty() || id.length() > MAX_PROVIDER_ID_LENGTH) return null;
        for (int index = 0; index < id.length(); index++) {
            if (Character.isISOControl(id.charAt(index))) return null;
        }
        return id;
    }

    private static String requireProfileId(String profileId) {
        if (profileId == null) throw new IllegalArgumentException("profile ID is required");
        String id = profileId.trim();
        if (id.isEmpty() || id.length() > MAX_PROFILE_ID_LENGTH) {
            throw new IllegalArgumentException("profile ID is invalid");
        }
        for (int index = 0; index < id.length(); index++) {
            if (Character.isISOControl(id.charAt(index))) {
                throw new IllegalArgumentException("profile ID is invalid");
            }
        }
        return id;
    }

    private static String orderKey(String profileId) {
        return ORDER_KEY_PREFIX + profileId;
    }

    private static String selectedKey(String profileId) {
        return SELECTED_KEY_PREFIX + profileId;
    }

    private static String knownKey(String profileId) {
        return KNOWN_KEY_PREFIX + profileId;
    }

    private static String encode(List<String> ids) {
        JSONArray array = new JSONArray();
        for (String id : ids) array.put(id);
        return array.toString();
    }
}
