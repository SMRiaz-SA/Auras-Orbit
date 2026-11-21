package com.auras.orbit.metadata;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores a user-supplied TMDB v3 key encrypted with a device-local Android Keystore key. */
public final class TmdbApiKeyStore {
    public static final String DEFAULT_PUBLIC_API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb";

    private static final String PREFERENCES = "orbit_tmdb_api_key_v1";
    private static final String KEY_ALIAS = "com.auras.orbit.tmdb.api_key.v1";
    private static final String IV = "iv";
    private static final String CIPHERTEXT = "ciphertext";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final Pattern V3_KEY = Pattern.compile("[A-Fa-f0-9]{32}");

    private static volatile String currentApiKey = DEFAULT_PUBLIC_API_KEY;
    private static volatile String loadFailure;

    private TmdbApiKeyStore() { }

    /**
     * Loads the selected key once during application startup. Network clients read the volatile
     * value at request time, so later saves and resets take effect without recreating clients.
     */
    public static synchronized void initialize(Context context) {
        if (context == null) return;
        SharedPreferences preferences = preferences(context);
        String encodedIv = preferences.getString(IV, null);
        String encodedCiphertext = preferences.getString(CIPHERTEXT, null);
        loadFailure = null;
        currentApiKey = DEFAULT_PUBLIC_API_KEY;
        if (encodedIv == null && encodedCiphertext == null) return;
        if (isBlank(encodedIv) || isBlank(encodedCiphertext)) {
            loadFailure = "The saved TMDB key could not be read. Restore the app default to replace it.";
            return;
        }

        byte[] plaintext = null;
        try {
            byte[] iv = Base64.decode(encodedIv, Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP);
            if (iv.length != IV_BYTES || ciphertext.length <= 16) {
                throw new GeneralSecurityException("Invalid encrypted TMDB key data.");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            plaintext = cipher.doFinal(ciphertext);
            String key = new String(plaintext, StandardCharsets.UTF_8);
            if (!isValidV3Key(key)) throw new GeneralSecurityException("Invalid TMDB v3 key format.");
            currentApiKey = key;
        } catch (GeneralSecurityException | IOException | IllegalArgumentException error) {
            loadFailure = "The saved TMDB key could not be read. Restore the app default to replace it.";
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    public static String getCurrentApiKey() {
        return currentApiKey;
    }

    public static boolean isUsingCustomKey() {
        return !DEFAULT_PUBLIC_API_KEY.equals(currentApiKey);
    }

    /** A non-secret status message for Settings; never contains key material. */
    public static String getLoadFailure() {
        return loadFailure;
    }

    public static boolean isValidV3Key(String value) {
        return value != null && V3_KEY.matcher(value.trim()).matches();
    }

    public static synchronized void save(Context context, String rawKey) throws IOException {
        String key = rawKey == null ? "" : rawKey.trim();
        if (!isValidV3Key(key)) {
            throw new IOException("Enter a 32-character TMDB v3 API key.");
        }
        byte[] plaintext = key.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) {
                throw new GeneralSecurityException("Android Keystore returned an invalid nonce.");
            }
            byte[] ciphertext = cipher.doFinal(plaintext);
            boolean saved = preferences(context).edit()
                    .putString(IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .commit();
            Arrays.fill(ciphertext, (byte) 0);
            Arrays.fill(iv, (byte) 0);
            if (!saved) throw new IOException("Could not save the encrypted TMDB API key.");
            currentApiKey = key;
            loadFailure = null;
        } catch (GeneralSecurityException error) {
            throw new IOException("Android Keystore could not encrypt the TMDB API key.", error);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public static synchronized void restoreDefault(Context context) throws IOException {
        boolean cleared = preferences(context).edit().remove(IV).remove(CIPHERTEXT).commit();
        if (!cleared) throw new IOException("Could not restore the app's TMDB API key.");
        currentApiKey = DEFAULT_PUBLIC_API_KEY;
        loadFailure = null;
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (GeneralSecurityException ignored) {
            // The encrypted value is already removed; a leftover unused Keystore key is harmless.
        } catch (IOException ignored) {
            // The encrypted value is already removed; a leftover unused Keystore key is harmless.
        }
    }

    private static SharedPreferences preferences(Context context) {
        if (context == null) throw new IllegalArgumentException("Application context is required.");
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
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
        if (!(existing instanceof SecretKey)) {
            throw new GeneralSecurityException("TMDB encryption key is unavailable.");
        }
        return (SecretKey) existing;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
