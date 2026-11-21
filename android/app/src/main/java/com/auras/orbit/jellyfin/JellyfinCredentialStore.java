package com.auras.orbit.jellyfin;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.auras.orbit.jellyfin.JellyfinModels.Connection;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores only an AES-GCM ciphertext in app-private preferences. The password is never retained;
 * the Jellyfin access token and connection metadata are encrypted with an Android Keystore key.
 * Call load/save away from the UI thread because Keystore operations are synchronous.
 */
public final class JellyfinCredentialStore {
    private static final String PREFS = "orbit_jellyfin_connection_v1";
    private static final String KEY_ALIAS = "com.auras.orbit.jellyfin.connection.v1";
    private static final String CIPHERTEXT = "ciphertext";
    private static final String IV = "iv";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final SharedPreferences preferences;

    public JellyfinCredentialStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(Connection connection) throws IOException {
        if (connection == null || isBlank(connection.serverUrl) || isBlank(connection.userId)
                || isBlank(connection.deviceId) || isBlank(connection.accessToken)) {
            throw new IOException("A complete authenticated Jellyfin connection is required.");
        }
        String normalizedServerUrl = JellyfinClient.normalizeServerUrl(connection.serverUrl);
        final byte[] plaintext;
        try {
            JSONObject value = new JSONObject();
            value.put("version", 1);
            value.put("serverUrl", normalizedServerUrl);
            value.put("serverName", connection.serverName == null ? "" : connection.serverName);
            value.put("userId", connection.userId);
            value.put("userName", connection.userName == null ? "" : connection.userName);
            value.put("serverId", connection.serverId == null ? "" : connection.serverId);
            value.put("deviceId", connection.deviceId);
            value.put("accessToken", connection.accessToken);
            plaintext = value.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException error) {
            throw new IOException("Could not encode Jellyfin connection data.", error);
        }

        final byte[] iv;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) {
                throw new GeneralSecurityException("Android Keystore did not provide a valid AES-GCM nonce.");
            }
            byte[] encrypted = cipher.doFinal(plaintext);
            boolean saved = preferences.edit()
                    .putString(IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit();
            if (!saved) throw new IOException("Could not store the encrypted Jellyfin connection.");
        } catch (GeneralSecurityException error) {
            throw new IOException("Android Keystore could not encrypt the Jellyfin connection.", error);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Returns null when disconnected. Decryption errors are surfaced without deleting the blob. */
    public synchronized Connection load() throws IOException {
        String encodedIv = preferences.getString(IV, null);
        String encodedCiphertext = preferences.getString(CIPHERTEXT, null);
        if (isBlank(encodedIv) && isBlank(encodedCiphertext)) return null;
        if (isBlank(encodedIv) || isBlank(encodedCiphertext)) {
            throw new IOException("Stored Jellyfin connection data is incomplete.");
        }

        byte[] plaintext = null;
        try {
            byte[] iv = Base64.decode(encodedIv, Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP);
            if (iv.length != IV_BYTES || ciphertext.length < 16) {
                throw new IOException("Stored Jellyfin connection data is invalid.");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            plaintext = cipher.doFinal(ciphertext);
            JSONObject value = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
            if (value.optInt("version", -1) != 1) {
                throw new IOException("Stored Jellyfin connection uses an unsupported format.");
            }
            String serverUrl = JellyfinClient.normalizeServerUrl(value.optString("serverUrl", ""));
            String serverName = value.optString("serverName", "");
            String userId = value.optString("userId", "").trim();
            String userName = value.optString("userName", "");
            String serverId = value.optString("serverId", "");
            String deviceId = value.optString("deviceId", "").trim();
            String token = value.optString("accessToken", "");
            if (isBlank(serverUrl) || isBlank(userId) || isBlank(deviceId) || isBlank(token)) {
                throw new IOException("Stored Jellyfin connection is missing required fields.");
            }
            return new Connection(serverUrl, serverName, userId, userName, serverId, deviceId, token);
        } catch (GeneralSecurityException | IllegalArgumentException | JSONException error) {
            throw new IOException("Could not decrypt the stored Jellyfin connection.", error);
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * Clears this device's encrypted connection and rotates away its Keystore key. This is local
     * sign-out; it does not revoke the corresponding token in Jellyfin server administration.
     */
    public synchronized void disconnect() throws IOException {
        if (!preferences.edit().remove(IV).remove(CIPHERTEXT).commit()) {
            throw new IOException("Could not clear the saved Jellyfin connection.");
        }
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (GeneralSecurityException error) {
            throw new IOException("The encrypted connection was cleared, but its Keystore key could not be removed.", error);
        }
    }

    /** Convenience alias for Settings → Connections. */
    public void clear() throws IOException { disconnect(); }

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
        if (!(existing instanceof SecretKey)) {
            throw new GeneralSecurityException("Jellyfin encryption key is unavailable.");
        }
        return (SecretKey) existing;
    }

    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
