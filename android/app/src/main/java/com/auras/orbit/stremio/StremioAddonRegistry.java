package com.auras.orbit.stremio;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * App-private, encrypted registry for explicitly installed Stremio manifest URLs. This registry
 * is intentionally separate from CloudStream repository and CS3 package storage.
 */
public final class StremioAddonRegistry {
    private static final String PREFS = "orbit_stremio_addons_v1";
    private static final String CIPHERTEXT_KEY = "installed_addons_ciphertext";
    private static final String IV_KEY = "installed_addons_iv";
    private static final String KEY_ALIAS = "com.auras.orbit.stremio.addons.v1";
    private static final String AAD = "AurasOrbit/StremioAddonRegistry/v1";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final int MAX_ADDONS = 64;
    private static final int MAX_STORE_BYTES = 512 * 1024;
    private static final Object PROCESS_LOCK = new Object();

    private final SharedPreferences preferences;

    public StremioAddonRegistry(Context context) {
        if (context == null) throw new IllegalArgumentException("An application context is required.");
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Persist only when the caller confirms installation after reviewing a preview. */
    public InstalledAddon install(String normalizedManifestUrl, StremioModels.Manifest manifest)
            throws IOException {
        if (manifest == null) throw new IOException("Preview the Stremio manifest before installing it.");
        boolean hasSupportedResource = false;
        for (StremioModels.ResourceCapability resource : manifest.resources) {
            if (resource.disposition == StremioModels.CapabilityDisposition.SUPPORTED) {
                hasSupportedResource = true;
                break;
            }
        }
        if (!hasSupportedResource) throw new IOException("This manifest declares no Stremio capabilities supported by Orbit.");
        String safeUrl = normalizeStoredUrl(normalizedManifestUrl);
        String installationId = digest(safeUrl);
        synchronized (PROCESS_LOCK) {
            ArrayList<InstalledAddon> installed = new ArrayList<>(readInstalled());
            long now = System.currentTimeMillis();
            int existingIndex = findIndex(installed, installationId);
            long installedAt = existingIndex >= 0 ? installed.get(existingIndex).installedAtMillis : now;
            InstalledAddon replacement = new InstalledAddon(installationId, manifest.id, manifest.name,
                    manifest.description, manifest.version, sourceHost(safeUrl), safeUrl, installedAt,
                    resourceCapabilities(manifest));
            if (existingIndex >= 0) installed.set(existingIndex, replacement);
            else {
                if (installed.size() >= MAX_ADDONS) throw new IOException("Orbit supports up to 64 installed Stremio add-ons.");
                installed.add(replacement);
            }
            saveInstalled(installed);
            return replacement;
        }
    }

    public List<InstalledAddon> getInstalled() throws IOException {
        synchronized (PROCESS_LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(readInstalled()));
        }
    }

    public InstalledAddon findInstalled(String installationId) throws IOException {
        if (installationId == null || installationId.trim().isEmpty()) return null;
        synchronized (PROCESS_LOCK) {
            for (InstalledAddon addon : readInstalled()) {
                if (addon.installationId.equals(installationId)) return addon;
            }
        }
        return null;
    }

    public void remove(String installationId) throws IOException {
        if (installationId == null || installationId.trim().isEmpty()) {
            throw new IOException("Choose an installed Stremio add-on to remove.");
        }
        synchronized (PROCESS_LOCK) {
            ArrayList<InstalledAddon> installed = new ArrayList<>(readInstalled());
            boolean removed = false;
            for (int index = installed.size() - 1; index >= 0; index--) {
                if (installed.get(index).installationId.equals(installationId)) {
                    installed.remove(index);
                    removed = true;
                }
            }
            if (removed) saveInstalled(installed);
        }
    }

    private List<InstalledAddon> readInstalled() throws IOException {
        String encodedIv = preferences.getString(IV_KEY, null);
        String encodedCiphertext = preferences.getString(CIPHERTEXT_KEY, null);
        if (isBlank(encodedIv) && isBlank(encodedCiphertext)) return Collections.emptyList();
        if (isBlank(encodedIv) || isBlank(encodedCiphertext)) {
            throw new IOException("Stored Stremio add-on data is incomplete.");
        }
        byte[] plaintext = null;
        try {
            byte[] iv = Base64.decode(encodedIv, Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP);
            if (iv.length != IV_BYTES || ciphertext.length < 16 || ciphertext.length > MAX_STORE_BYTES + 16) {
                throw new IOException("Stored Stremio add-on data is invalid.");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            plaintext = cipher.doFinal(ciphertext);
            if (plaintext.length > MAX_STORE_BYTES) throw new IOException("Stored Stremio add-on data is too large.");
            JSONObject root = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
            if (root.optInt("version", -1) != 1) throw new IOException("Stored Stremio add-on data has an unsupported version.");
            JSONArray rows = root.getJSONArray("addons");
            if (rows.length() > MAX_ADDONS) throw new IOException("Stored Stremio add-on data exceeds the supported limit.");
            ArrayList<InstalledAddon> result = new ArrayList<>();
            Set<String> identities = new HashSet<>();
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.getJSONObject(index);
                String url = normalizeStoredUrl(row.getString("manifestUrl"));
                String id = digest(url);
                String storedId = row.getString("installationId");
                if (!id.equals(storedId) || !identities.add(id)) {
                    throw new IOException("Stored Stremio add-on identity is invalid.");
                }
                String manifestId = requiredStoredString(row, "manifestId", 256);
                String name = requiredStoredString(row, "name", 256);
                String description = requiredStoredString(row, "description", 4_096);
                String version = requiredStoredString(row, "version", 128);
                long installedAt = row.getLong("installedAtMillis");
                if (installedAt < 0) throw new IOException("Stored Stremio installation time is invalid.");
                JSONArray capabilityArray = row.getJSONArray("capabilities");
                if (capabilityArray.length() > 64) throw new IOException("Stored Stremio capabilities are invalid.");
                ArrayList<StremioModels.ResourceCapability> capabilities = new ArrayList<>();
                for (int resourceIndex = 0; resourceIndex < capabilityArray.length(); resourceIndex++) {
                    JSONObject capability = capabilityArray.getJSONObject(resourceIndex);
                    String resource = requiredStoredString(capability, "name", 64);
                    String disposition = requiredStoredString(capability, "disposition", 64);
                    StremioModels.CapabilityDisposition expected = StremioModels.dispositionFor(resource);
                    if (!expected.name().equals(disposition)) throw new IOException("Stored Stremio capability disposition is invalid.");
                    boolean inheritsFilters = capability.getBoolean("inheritsManifestFilters");
                    capabilities.add(new StremioModels.ResourceCapability(resource,
                            readStoredStrings(capability.getJSONArray("types"), 128, 64),
                            readStoredStrings(capability.getJSONArray("idPrefixes"), 256, 64), expected,
                            inheritsFilters));
                }
                result.add(new InstalledAddon(id, manifestId, name, description, version,
                        sourceHost(url), url, installedAt, capabilities));
            }
            return result;
        } catch (GeneralSecurityException | IllegalArgumentException | JSONException error) {
            throw new IOException("Could not decrypt the installed Stremio add-ons.");
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private void saveInstalled(List<InstalledAddon> installed) throws IOException {
        if (installed.size() > MAX_ADDONS) throw new IOException("Too many installed Stremio add-ons.");
        byte[] plaintext = null;
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            JSONArray rows = new JSONArray();
            for (InstalledAddon addon : installed) {
                JSONObject row = new JSONObject();
                row.put("installationId", addon.installationId);
                row.put("manifestId", addon.manifestId);
                row.put("name", addon.name);
                row.put("description", addon.description);
                row.put("version", addon.version);
                row.put("manifestUrl", addon.manifestUrlInternal());
                row.put("installedAtMillis", addon.installedAtMillis);
                JSONArray capabilities = new JSONArray();
                for (StremioModels.ResourceCapability resource : addon.capabilities) {
                    JSONObject capability = new JSONObject();
                    capability.put("name", resource.name);
                    capability.put("disposition", resource.disposition.name());
                    capability.put("inheritsManifestFilters", resource.inheritsManifestFilters);
                    capability.put("types", toJsonArray(resource.types));
                    capability.put("idPrefixes", toJsonArray(resource.idPrefixes));
                    capabilities.put(capability);
                }
                row.put("capabilities", capabilities);
                rows.put(row);
            }
            root.put("addons", rows);
            plaintext = root.toString().getBytes(StandardCharsets.UTF_8);
            if (plaintext.length > MAX_STORE_BYTES) throw new IOException("Installed Stremio add-on data is too large.");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) throw new GeneralSecurityException("Invalid Keystore nonce.");
            byte[] ciphertext = cipher.doFinal(plaintext);
            boolean saved = preferences.edit()
                    .putString(IV_KEY, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(CIPHERTEXT_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .commit();
            Arrays.fill(ciphertext, (byte) 0);
            if (!saved) throw new IOException("Could not save the installed Stremio add-ons.");
        } catch (GeneralSecurityException | JSONException error) {
            throw new IOException("Could not encrypt the installed Stremio add-ons.");
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static List<StremioModels.ResourceCapability> resourceCapabilities(StremioModels.Manifest manifest) {
        return new ArrayList<>(manifest.resources);
    }

    private static JSONArray toJsonArray(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    private static List<String> readStoredStrings(JSONArray array, int maxChars, int maxItems)
            throws JSONException, IOException {
        if (array.length() > maxItems) throw new IOException("Stored Stremio capability data is too large.");
        ArrayList<String> values = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            String value = array.getString(index);
            if (value.isEmpty() || value.length() > maxChars) throw new IOException("Stored Stremio capability data is invalid.");
            values.add(value);
        }
        return values;
    }

    private static int findIndex(List<InstalledAddon> installed, String id) {
        for (int index = 0; index < installed.size(); index++) {
            if (installed.get(index).installationId.equals(id)) return index;
        }
        return -1;
    }

    private static String normalizeStoredUrl(String value) throws IOException {
        String canonical = new StremioAddonClient().normalizeManifestUrl(value);
        if (!canonical.equals(value)) throw new IOException("Stored Stremio manifest URL is not canonical.");
        return canonical;
    }

    private static String sourceHost(String url) throws IOException {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) throw new IOException("Manifest host is missing.");
            return host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (java.net.URISyntaxException error) {
            throw new IOException("Stored Stremio manifest URL is invalid.");
        }
    }

    private static String digest(String value) throws IOException {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) hex.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            Arrays.fill(bytes, (byte) 0);
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("Could not identify the installed Stremio add-on.");
        }
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static SecretKey getExistingKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (!(existing instanceof SecretKey)) throw new GeneralSecurityException("Stremio encryption key is unavailable.");
        return (SecretKey) existing;
    }

    private static String requiredStoredString(JSONObject object, String key, int maximum)
            throws JSONException, IOException {
        String value = object.getString(key);
        if (value.trim().isEmpty() || value.length() > maximum) throw new IOException("Stored Stremio add-on metadata is invalid.");
        return value;
    }

    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }

    public static final class InstalledAddon {
        public final String installationId;
        public final String manifestId;
        public final String name;
        public final String description;
        public final String version;
        /** Host and optional port only; query credentials and manifest path are never displayed here. */
        public final String sourceHost;
        public final long installedAtMillis;
        public final List<StremioModels.ResourceCapability> capabilities;
        private final String manifestUrl;

        private InstalledAddon(String installationId, String manifestId, String name,
                               String description, String version, String sourceHost,
                               String manifestUrl, long installedAtMillis,
                               List<StremioModels.ResourceCapability> capabilities) {
            this.installationId = installationId;
            this.manifestId = manifestId;
            this.name = name;
            this.description = description;
            this.version = version;
            this.sourceHost = sourceHost;
            this.manifestUrl = manifestUrl;
            this.installedAtMillis = installedAtMillis;
            this.capabilities = Collections.unmodifiableList(new ArrayList<>(capabilities));
        }

        String manifestUrlInternal() { return manifestUrl; }
    }
}
