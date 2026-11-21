package com.lagradost.cloudstream3.extractors.helper;

import android.util.Base64;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import kotlin.coroutines.Continuation;
import kotlinx.coroutines.JobKt;

/**
 * Bounded compatibility implementation of Cloudstream's legacy media AES helper.
 * MD5 and AES-CBC here are for legacy media decoding only, never Orbit user data.
 */
public final class AesHelper {
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    // Safety bound on unknown nested JSON values while skipping them iteratively.
    private static final int MAX_JSON_NESTING = 64;
    private static final int MAX_CIPHER_BYTES = 1024 * 1024;
    private static final int MAX_PLAINTEXT_BYTES = 1024 * 1024;
    private static final int MAX_PASSWORD_BYTES = 4 * 1024;
    private static final int MAX_SALT_BYTES = 64;
    private static final int MAX_KEY_BYTES = 64;
    private static final int AES_KEY_BYTES = 32;
    private static final int AES_IV_BYTES = 16;
    private static final int MD5_BYTES = 16;
    private static final int LEGACY_ITERATIONS = 1;
    private static final int MAX_ITERATIONS = 100;

    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private AesHelper() { super(); }

    public static final AesHelper INSTANCE = new AesHelper();

    /** Orbit's pre-existing non-suspending boolean-padding default bridge. */
    public static String cryptoAESHandler$default(
            AesHelper receiver,
            String data,
            byte[] password,
            boolean encrypt,
            boolean padding,
            int mask,
            Object marker) {
        if ((mask & 0x4) != 0) {
            encrypt = true;
        }
        if ((mask & 0x8) != 0) {
            padding = true;
        }
        return cryptoAESHandler(data, password, encrypt, padding);
    }

    /** Stable v4.8.0 non-suspending default-argument ABI form. */
    public static String cryptoAESHandler$default(
            AesHelper receiver,
            String data,
            byte[] password,
            boolean encrypt,
            String padding,
            int mask,
            Object marker) {
        if ((mask & 0x4) != 0) {
            encrypt = true;
        }
        if (padding == null) {
            return null;
        }
        // The pinned string overload treats every transformation name except
        // one ending in NoPadding as PKCS5Padding.
        boolean usePadding = !padding.endsWith("NoPadding");
        return cryptoAESHandler(data, password, encrypt, usePadding);
    }

    /** Pre-release suspend overload's exact static default-argument ABI form. */
    public static Object cryptoAESHandler$default(
            AesHelper receiver,
            String data,
            byte[] password,
            boolean encrypt,
            boolean padding,
            Continuation<?> continuation,
            int mask,
            Object marker) {
        if (continuation == null) {
            throw new NullPointerException("continuation");
        }
        if ((mask & 0x4) != 0) {
            encrypt = true;
        }
        if ((mask & 0x8) != 0) {
            padding = true;
        }

        // The operation is deliberately bounded and synchronous; check before
        // and after it so cancellation is observed at both coroutine boundaries.
        JobKt.ensureActive(continuation.getContext());
        String result = cryptoAESHandler(data, password, encrypt, padding);
        JobKt.ensureActive(continuation.getContext());
        return result;
    }

    private static String cryptoAESHandler(
            String data,
            byte[] password,
            boolean encrypt,
            boolean padding) {
        if (data == null || password == null || password.length > MAX_PASSWORD_BYTES
                || data.length() > MAX_JSON_BYTES) {
            return null;
        }

        byte[] salt = null;
        byte[] cipherInput = null;
        byte[] cipherOutput = null;
        KeyMaterial material = null;
        try {
            // Bound UTF-8 input bytes too; a Java String's character count is not
            // the same as its serialized JSON size.
            if (data.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                return null;
            }

            AesData parsed = parseData(data);
            if (parsed == null || parsed.ct == null || parsed.iv == null || parsed.s == null
                    || parsed.iv.length() / 2 != AES_IV_BYTES
                    || parsed.s.length() > MAX_SALT_BYTES * 2
                    || (parsed.s.length() & 1) != 0) {
                return null;
            }

            // The pinned implementation uses only this field's length when
            // sizing the password+salt-derived IV; its contents are ignored.
            salt = decodeHex(parsed.s);

            byte[] textBytes = parsed.ct.getBytes(StandardCharsets.UTF_8);
            if (textBytes.length > (encrypt ? MAX_PLAINTEXT_BYTES : MAX_CIPHER_BYTES)) {
                Arrays.fill(textBytes, (byte) 0);
                return null;
            }

            if (encrypt) {
                cipherInput = textBytes;
            } else {
                Arrays.fill(textBytes, (byte) 0);
                cipherInput = decodeBase64Bounded(parsed.ct);
                if (cipherInput == null || cipherInput.length > MAX_CIPHER_BYTES) {
                    return null;
                }
            }

            material = deriveKeyAndIv(password, salt, AES_KEY_BYTES, AES_IV_BYTES, LEGACY_ITERATIONS);
            if (material == null) {
                return null;
            }

            String transformation = padding ? "AES/CBC/PKCS5Padding" : "AES/CBC/NoPadding";
            Cipher cipher = Cipher.getInstance(transformation);
            cipher.init(
                    encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                    new SecretKeySpec(material.key, "AES"),
                    new IvParameterSpec(material.iv));
            cipherOutput = cipher.doFinal(cipherInput);

            if (encrypt) {
                if (cipherOutput.length > MAX_CIPHER_BYTES) {
                    return null;
                }
                String encoded = Base64.encodeToString(cipherOutput, Base64.NO_WRAP);
                return encoded.length() <= MAX_JSON_BYTES ? encoded : null;
            }

            if (cipherOutput.length > MAX_PLAINTEXT_BYTES) {
                return null;
            }
            return new String(cipherOutput, StandardCharsets.UTF_8);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException
                 | IllegalStateException malformedInput) {
            // Upstream can throw for malformed hex, bad Base64, and cipher
            // failures. Orbit returns null for these bounded malformed inputs.
            return null;
        } finally {
            if (salt != null) Arrays.fill(salt, (byte) 0);
            if (cipherInput != null) Arrays.fill(cipherInput, (byte) 0);
            if (cipherOutput != null) Arrays.fill(cipherOutput, (byte) 0);
            if (material != null) material.clear();
        }
    }

    private static AesData parseData(String json) throws IOException {
        String ct = null;
        String iv = null;
        String salt = null;
        try (JsonParser parser = JSON_FACTORY.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token == null || token != JsonToken.FIELD_NAME) {
                    return null;
                }
                String name = parser.getCurrentName();
                token = parser.nextToken();
                if (token == null) {
                    return null;
                }
                if ("ct".equals(name)) {
                    if (token != JsonToken.VALUE_STRING) return null;
                    ct = parser.getText();
                    if (ct.length() > MAX_JSON_BYTES) return null;
                } else if ("iv".equals(name)) {
                    if (token != JsonToken.VALUE_STRING) return null;
                    iv = parser.getText();
                    if (iv.length() > MAX_JSON_BYTES) return null;
                } else if ("s".equals(name)) {
                    if (token != JsonToken.VALUE_STRING) return null;
                    salt = parser.getText();
                    if (salt.length() > MAX_JSON_BYTES) return null;
                } else {
                    // Match the pinned mapper's unknown-field behavior. The
                    // entire document is bounded to 1 MiB; nested values are
                    // traversed iteratively with a separate depth safety cap.
                    if (!skipUnknownValue(parser, token)) return null;
                }
            }
            if (parser.nextToken() != null || ct == null || iv == null || salt == null) {
                return null;
            }
        }
        return new AesData(ct, iv, salt);
    }

    private static boolean skipUnknownValue(JsonParser parser, JsonToken token) throws IOException {
        if (token != JsonToken.START_OBJECT && token != JsonToken.START_ARRAY) {
            return true;
        }

        int depth = 1;
        while (depth > 0) {
            JsonToken nested = parser.nextToken();
            if (nested == null) return false;
            if (nested == JsonToken.START_OBJECT || nested == JsonToken.START_ARRAY) {
                depth++;
                if (depth > MAX_JSON_NESTING) return false;
            } else if (nested == JsonToken.END_OBJECT || nested == JsonToken.END_ARRAY) {
                depth--;
            }
        }
        return true;
    }

    private static KeyMaterial deriveKeyAndIv(
            byte[] password,
            byte[] salt,
            int keyLength,
            int ivLength,
            int iterations) throws GeneralSecurityException {
        if (password == null || salt == null || salt.length > MAX_SALT_BYTES
                || keyLength <= 0 || keyLength > MAX_KEY_BYTES
                || ivLength != AES_IV_BYTES || iterations < 1 || iterations > MAX_ITERATIONS) {
            return null;
        }

        int targetLength = keyLength + ivLength;
        byte[] generated = new byte[targetLength];
        byte[] previous = new byte[0];
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        try {
            int offset = 0;
            while (offset < targetLength) {
                md5.reset();
                md5.update(previous);
                md5.update(password);
                md5.update(salt);
                byte[] digest = md5.digest();
                for (int iteration = 1; iteration < iterations; iteration++) {
                    byte[] nextDigest = md5.digest(digest);
                    Arrays.fill(digest, (byte) 0);
                    digest = nextDigest;
                }

                int copied = Math.min(MD5_BYTES, targetLength - offset);
                System.arraycopy(digest, 0, generated, offset, copied);
                Arrays.fill(previous, (byte) 0);
                previous = digest;
                offset += copied;
            }

            return new KeyMaterial(
                    Arrays.copyOfRange(generated, 0, keyLength),
                    Arrays.copyOfRange(generated, keyLength, targetLength));
        } finally {
            Arrays.fill(generated, (byte) 0);
            Arrays.fill(previous, (byte) 0);
        }
    }

    private static byte[] decodeHex(String value) {
        if (value == null || (value.length() & 1) != 0 || value.length() > MAX_SALT_BYTES * 2) {
            return null;
        }
        byte[] decoded = new byte[value.length() / 2];
        for (int index = 0; index < decoded.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                Arrays.fill(decoded, (byte) 0);
                return null;
            }
            decoded[index] = (byte) ((high << 4) | low);
        }
        return decoded;
    }

    private static byte[] decodeBase64Bounded(String value) {
        if (value == null || value.length() > MAX_JSON_BYTES || (value.length() & 3) != 0) {
            return null;
        }
        int padding = 0;
        int length = value.length();
        if (length > 0 && value.charAt(length - 1) == '=') padding++;
        if (length > 1 && value.charAt(length - 2) == '=') padding++;
        for (int index = 0; index < length; index++) {
            char c = value.charAt(index);
            boolean alphabet = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/';
            boolean finalPadding = c == '=' && index >= length - padding;
            if (!alphabet && !finalPadding) return null;
        }
        long decodedLength = (long) (length / 4) * 3L - padding;
        if (decodedLength < 0 || decodedLength > MAX_CIPHER_BYTES) return null;
        try {
            byte[] decoded = Base64.decode(value, Base64.NO_WRAP);
            if (decoded.length > MAX_CIPHER_BYTES) {
                Arrays.fill(decoded, (byte) 0);
                return null;
            }
            return decoded;
        } catch (IllegalArgumentException invalidBase64) {
            return null;
        }
    }

    private static final class AesData {
        final String ct;
        final String iv;
        final String s;

        AesData(String ct, String iv, String s) {
            this.ct = ct;
            this.iv = iv;
            this.s = s;
        }
    }

    private static final class KeyMaterial {
        final byte[] key;
        final byte[] iv;

        KeyMaterial(byte[] key, byte[] iv) {
            this.key = key;
            this.iv = iv;
        }

        void clear() {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }
}
