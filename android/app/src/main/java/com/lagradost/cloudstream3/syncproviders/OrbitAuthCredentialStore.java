package com.lagradost.cloudstream3.syncproviders;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private, encrypted storage for CS3 provider credentials. Never reads legacy CS3 keys. */
final class OrbitAuthCredentialStore {
    private static final String PREFS = "orbit_cs3_sync_auth_v1";
    private static final String KEY_ALIAS = "orbit.cs3.sync.auth.aesgcm.v1";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final Object KEY_LOCK = new Object();

    private OrbitAuthCredentialStore() { }

    static AuthData[] readAccounts(Context context, String profileId, String providerId) {
        String encrypted = preferences(context).getString(storageKey("accounts", profileId, providerId), null);
        if (encrypted == null) return new AuthData[0];
        try {
            JSONArray accounts = new JSONArray(decrypt(profileId, providerId, encrypted));
            AuthData[] result = new AuthData[accounts.length()];
            for (int index = 0; index < accounts.length(); index++) {
                result[index] = decodeAuthData(accounts.getJSONObject(index));
            }
            return result;
        } catch (Exception invalidOrUnavailableCiphertext) {
            // Fail closed without deleting data: a missing/replaced keystore key can be recoverable.
            return new AuthData[0];
        }
    }

    static void writeAccounts(Context context, String profileId, String providerId, AuthData[] accounts) {
        if (accounts == null) throw new IllegalArgumentException("Accounts are required.");
        JSONArray encoded = new JSONArray();
        try {
            for (AuthData account : accounts) {
                if (account == null) throw new IllegalArgumentException("Account entries cannot be null.");
                encoded.put(encodeAuthData(account));
            }
        } catch (JSONException invalidAccount) {
            throw new IllegalArgumentException("Could not encode provider credentials.", invalidAccount);
        }
        String encrypted = encrypt(profileId, providerId, encoded.toString());
        boolean committed = preferences(context).edit()
                .putString(storageKey("accounts", profileId, providerId), encrypted)
                .commit();
        if (!committed) throw new IllegalStateException("Could not persist provider credentials.");
    }

    static int readSelectedAccountId(Context context, String profileId, String providerId, int fallback) {
        return preferences(context).getInt(storageKey("selected", profileId, providerId), fallback);
    }

    static void writeSelectedAccountId(Context context, String profileId, String providerId, int id) {
        boolean committed = preferences(context).edit()
                .putInt(storageKey("selected", profileId, providerId), id)
                .commit();
        if (!committed) throw new IllegalStateException("Could not persist the selected provider account.");
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String storageKey(String kind, String profileId, String providerId) {
        return kind + "/" + encodeKeyPart(profileId) + "/" + encodeKeyPart(providerId);
    }

    private static String encodeKeyPart(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String encrypt(String profileId, String providerId, String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            cipher.updateAAD(associatedData(profileId, providerId));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP | Base64.NO_PADDING)
                    + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception unavailable) {
            throw new IllegalStateException("Could not encrypt provider credentials.", unavailable);
        }
    }

    private static String decrypt(String profileId, String providerId, String envelope) throws Exception {
        String[] parts = envelope.split(":", -1);
        if (parts.length != 3 || !"v1".equals(parts[0])) {
            throw new IllegalArgumentException("Unsupported credential record.");
        }
        byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP | Base64.NO_PADDING);
        if (iv.length != GCM_IV_BYTES) throw new IllegalArgumentException("Invalid credential nonce.");
        byte[] ciphertext = Base64.decode(parts[2], Base64.NO_WRAP | Base64.NO_PADDING);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(associatedData(profileId, providerId));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private static byte[] associatedData(String profileId, String providerId) {
        return ("orbit-cs3-auth-v1\u0000" + profileId + "\u0000" + providerId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        synchronized (KEY_LOCK) {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            Key existing = keyStore.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;

            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return generator.generateKey();
        }
    }

    private static SecretKey getExistingKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        throw new IllegalStateException("Provider credential key is unavailable.");
    }

    private static JSONObject encodeAuthData(AuthData auth) throws JSONException {
        JSONObject user = new JSONObject();
        putNullable(user, "name", auth.getUser().getName());
        user.put("id", auth.getUser().getId());
        putNullable(user, "profilePicture", auth.getUser().getProfilePicture());
        Map<String, String> headers = auth.getUser().getProfilePictureHeaders();
        if (headers != null) user.put("profilePictureHeaders", new JSONObject(headers));

        AuthToken token = auth.getToken();
        JSONObject encodedToken = new JSONObject();
        putNullable(encodedToken, "accessToken", token.getAccessToken());
        putNullable(encodedToken, "refreshToken", token.getRefreshToken());
        putNullable(encodedToken, "accessTokenLifetime", token.getAccessTokenLifetime());
        putNullable(encodedToken, "refreshTokenLifetime", token.getRefreshTokenLifetime());
        putNullable(encodedToken, "payload", token.getPayload());
        return new JSONObject().put("user", user).put("token", encodedToken);
    }

    private static AuthData decodeAuthData(JSONObject value) throws JSONException {
        JSONObject userJson = value.getJSONObject("user");
        JSONObject tokenJson = value.getJSONObject("token");
        Map<String, String> headers = null;
        JSONObject encodedHeaders = userJson.optJSONObject("profilePictureHeaders");
        if (encodedHeaders != null) {
            headers = new HashMap<>();
            java.util.Iterator<String> names = encodedHeaders.keys();
            while (names.hasNext()) {
                String name = names.next();
                Object headerValue = encodedHeaders.get(name);
                if (!(headerValue instanceof String)) throw new JSONException("Invalid profile picture header.");
                headers.put(name, (String) headerValue);
            }
        }
        AuthUser user = new AuthUser(nullableString(userJson, "name"), userJson.getInt("id"),
                nullableString(userJson, "profilePicture"), headers);
        AuthToken token = new AuthToken(nullableString(tokenJson, "accessToken"),
                nullableString(tokenJson, "refreshToken"), nullableLong(tokenJson, "accessTokenLifetime"),
                nullableLong(tokenJson, "refreshTokenLifetime"), nullableString(tokenJson, "payload"));
        return new AuthData(user, token);
    }

    private static void putNullable(JSONObject object, String key, Object value) throws JSONException {
        object.put(key, value == null ? JSONObject.NULL : value);
    }

    private static String nullableString(JSONObject object, String key) throws JSONException {
        if (!object.has(key) || object.isNull(key)) return null;
        Object value = object.get(key);
        if (!(value instanceof String)) throw new JSONException("Invalid credential field.");
        return (String) value;
    }

    private static Long nullableLong(JSONObject object, String key) throws JSONException {
        if (!object.has(key) || object.isNull(key)) return null;
        Object value = object.get(key);
        if (!(value instanceof Number)) throw new JSONException("Invalid credential expiry.");
        return ((Number) value).longValue();
    }
}
