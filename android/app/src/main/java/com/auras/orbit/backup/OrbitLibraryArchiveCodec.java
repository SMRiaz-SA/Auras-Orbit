package com.auras.orbit.backup;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Encrypted, portable codec for explicit Orbit library backup and file-based sync. */
public final class OrbitLibraryArchiveCodec {
    private static final byte[] MAGIC = new byte[]{'A', 'U', 'R', 'O', 'R', 'B', 'L', 'B'};
    private static final int FORMAT_VERSION = 1;
    private static final int KDF_PBKDF2_HMAC_SHA256 = 1;
    private static final int ITERATIONS = 250_000;
    private static final int MIN_IMPORT_ITERATIONS = 100_000;
    private static final int MAX_IMPORT_ITERATIONS = 1_000_000;
    private static final int MIN_IMPORT_PASSPHRASE_UTF16_UNITS = 12;
    private static final int MIN_PASSPHRASE_CODE_POINTS = 12;
    private static final int MAX_PASSPHRASE_CODE_POINTS = 1024;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final int HEADER_BYTES = 8 + 2 + 2 + 4 + SALT_BYTES + NONCE_BYTES;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int MAX_PLAINTEXT_BYTES = 64 * 1024 * 1024;
    public static final int MAX_ARCHIVE_BYTES = HEADER_BYTES + MAX_PLAINTEXT_BYTES + TAG_BYTES;
    private static final SecureRandom RANDOM = new SecureRandom();

    private OrbitLibraryArchiveCodec() { }

    /** Writes one encrypted archive. The caller owns and closes the output stream. */
    public static void write(JSONObject payload, OutputStream output, char[] passphrase)
            throws IOException, ArchiveException {
        if (payload == null) throw new ArchiveException("The backup data is missing.");
        if (output == null) throw new ArchiveException("The selected destination is unavailable.");
        validatePassphrase(passphrase);

        // Validate the encoded UTF-8 byte length before allocating a large array.
        // Even a rejected backup must not exhaust a small Android heap merely
        // because an oversized JSONObject was supplied by a storage subsystem.
        String json = payload.toString();
        requirePlaintextBudget(json);
        byte[] plaintext = json.getBytes(StandardCharsets.UTF_8);
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] passwordBytes = null;
        byte[] keyBytes = null;
        byte[] encrypted = null;
        byte[] header = null;
        try {
            if (plaintext.length > MAX_PLAINTEXT_BYTES) {
                throw new ArchiveException("This Orbit backup is larger than the supported archive limit.");
            }
            RANDOM.nextBytes(salt);
            RANDOM.nextBytes(nonce);
            header = createHeader(salt, nonce, ITERATIONS);
            passwordBytes = encodePassphrase(passphrase);
            keyBytes = deriveKey(passwordBytes, salt, ITERATIONS);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header);
            encrypted = cipher.doFinal(plaintext);

            DataOutputStream data = new DataOutputStream(output);
            data.write(header);
            data.write(encrypted);
            data.flush();
        } catch (GeneralSecurityException error) {
            throw new ArchiveException("Orbit could not encrypt the backup on this device.", error);
        } finally {
            wipe(plaintext);
            wipe(salt);
            wipe(nonce);
            wipe(passwordBytes);
            wipe(keyBytes);
            wipe(encrypted);
            wipe(header);
        }
    }

    /** Reads and authenticates one archive before parsing its JSON payload. */
    public static JSONObject read(InputStream input, char[] passphrase)
            throws IOException, ArchiveException {
        if (input == null) throw new ArchiveException("The selected backup file is unavailable.");
        validatePassphraseForImport(passphrase);

        byte[] archive = readBounded(input);
        byte[] salt = null;
        byte[] nonce = null;
        byte[] header = null;
        byte[] encrypted = null;
        byte[] passwordBytes = null;
        byte[] keyBytes = null;
        byte[] plaintext = null;
        try {
            if (archive.length < HEADER_BYTES + TAG_BYTES) {
                throw new ArchiveException("This file is not a complete Orbit backup.");
            }
            ByteBuffer fields = ByteBuffer.wrap(archive, 0, HEADER_BYTES);
            byte[] magic = new byte[MAGIC.length];
            fields.get(magic);
            if (!Arrays.equals(MAGIC, magic)) throw new ArchiveException("This file is not an Orbit backup.");
            int version = Short.toUnsignedInt(fields.getShort());
            int kdf = Short.toUnsignedInt(fields.getShort());
            int iterations = fields.getInt();
            if (version != FORMAT_VERSION || kdf != KDF_PBKDF2_HMAC_SHA256) {
                throw new ArchiveException("This Orbit backup uses an unsupported format version.");
            }
            if (iterations < MIN_IMPORT_ITERATIONS || iterations > MAX_IMPORT_ITERATIONS) {
                throw new ArchiveException("This Orbit backup has invalid encryption parameters.");
            }
            salt = new byte[SALT_BYTES];
            nonce = new byte[NONCE_BYTES];
            fields.get(salt);
            fields.get(nonce);
            header = Arrays.copyOfRange(archive, 0, HEADER_BYTES);
            encrypted = Arrays.copyOfRange(archive, HEADER_BYTES, archive.length);
            passwordBytes = encodePassphrase(passphrase);
            keyBytes = deriveKey(passwordBytes, salt, iterations);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header);
            plaintext = cipher.doFinal(encrypted);
            if (plaintext.length > MAX_PLAINTEXT_BYTES) {
                throw new ArchiveException("This Orbit backup is larger than the supported archive limit.");
            }
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plaintext)).toString();
            return new JSONObject(json);
        } catch (GeneralSecurityException error) {
            // Authentication failures intentionally do not distinguish a wrong password from tampering.
            throw new ArchiveException("The password is incorrect or the Orbit backup has been changed.", error);
        } catch (CharacterCodingException error) {
            throw new ArchiveException("The decrypted file does not contain valid UTF-8.", error);
        } catch (org.json.JSONException error) {
            throw new ArchiveException("The decrypted file does not contain a valid Orbit backup.", error);
        } finally {
            wipe(archive);
            wipe(salt);
            wipe(nonce);
            wipe(header);
            wipe(encrypted);
            wipe(passwordBytes);
            wipe(keyBytes);
            wipe(plaintext);
        }
    }

    /** Reject oversized UTF-8 JSON without first allocating its encoded byte array. */
    private static void requirePlaintextBudget(String json) throws ArchiveException {
        if (json == null) throw new ArchiveException("The backup data is missing.");
        long bytes = 0;
        for (int index = 0; index < json.length(); index++) {
            char c = json.charAt(index);
            if (c <= 0x7f) bytes += 1;
            else if (c <= 0x7ff) bytes += 2;
            else if (Character.isHighSurrogate(c)
                    && index + 1 < json.length()
                    && Character.isLowSurrogate(json.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else if (Character.isSurrogate(c)) bytes += 1; // UTF-8 replacement byte
            else bytes += 3;
            if (bytes > MAX_PLAINTEXT_BYTES) {
                throw new ArchiveException("This Orbit backup is larger than the supported archive limit.");
            }
        }
    }

    private static byte[] createHeader(byte[] salt, byte[] nonce, int iterations) {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.put(MAGIC);
        header.putShort((short) FORMAT_VERSION);
        header.putShort((short) KDF_PBKDF2_HMAC_SHA256);
        header.putInt(iterations);
        header.put(salt);
        header.put(nonce);
        return header.array();
    }

    private static byte[] readBounded(InputStream input) throws IOException, ArchiveException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        try {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) throw new IOException("Input stream made no progress.");
                total += count;
                if (total > MAX_ARCHIVE_BYTES) {
                    throw new ArchiveException("This file is larger than the supported Orbit backup limit.");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            wipe(buffer);
        }
    }

    /** PBKDF2-HMAC-SHA256 implemented with platform Mac so the format also works on API 23. */
    private static byte[] deriveKey(byte[] password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        byte[] blockInput = Arrays.copyOf(salt, salt.length + 4);
        blockInput[blockInput.length - 1] = 1;
        byte[] u = mac.doFinal(blockInput);
        byte[] result = u.clone();
        for (int iteration = 1; iteration < iterations; iteration++) {
            byte[] next = mac.doFinal(u);
            wipe(u);
            u = next;
            for (int index = 0; index < result.length; index++) result[index] ^= u[index];
        }
        wipe(blockInput);
        wipe(u);
        byte[] key = Arrays.copyOf(result, KEY_BYTES);
        wipe(result);
        return key;
    }

    private static byte[] encodePassphrase(char[] passphrase) throws ArchiveException {
        char[] validated = copyExactPassphrase(passphrase);
        ByteBuffer encoded = null;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(validated));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException error) {
            throw new ArchiveException("The backup password contains unsupported characters.", error);
        } finally {
            if (encoded != null && encoded.hasArray()) wipe(encoded.array());
            wipe(validated);
        }
    }

    /** Validates the password contract for new exports: 12–1,024 Unicode code points. */
    public static void validatePassphrase(char[] passphrase) throws ArchiveException {
        if (passphrase == null) throw invalidPassphrase();
        int codePoints = countCodePointsRejectUnpairedSurrogates(passphrase);
        if (codePoints < MIN_PASSPHRASE_CODE_POINTS
                || codePoints > MAX_PASSPHRASE_CODE_POINTS) {
            throw invalidPassphrase();
        }
    }

    /** Validates the legacy v1 import contract: at least 12 UTF-16 code units, with no maximum. */
    public static void validatePassphraseForImport(char[] passphrase) throws ArchiveException {
        if (passphrase == null || passphrase.length < MIN_IMPORT_PASSPHRASE_UTF16_UNITS) {
            throw new ArchiveException("Use an archive password with at least 12 characters.");
        }
        countCodePointsRejectUnpairedSurrogates(passphrase);
    }

    private static char[] copyExactPassphrase(char[] passphrase) throws ArchiveException {
        if (passphrase == null) throw invalidPassphrase();
        countCodePointsRejectUnpairedSurrogates(passphrase);
        return passphrase.clone();
    }

    private static int countCodePointsRejectUnpairedSurrogates(char[] value) throws ArchiveException {
        int codePoints = 0;
        for (int index = 0; index < value.length; index++) {
            char current = value[index];
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                    throw new ArchiveException("The backup password contains an unpaired surrogate.");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new ArchiveException("The backup password contains an unpaired surrogate.");
            }
            codePoints++;
        }
        return codePoints;
    }

    private static ArchiveException invalidPassphrase() {
        return new ArchiveException("Use a backup password with 12 to 1024 Unicode code points.");
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    private static void wipe(char[] value) {
        if (value != null) Arrays.fill(value, '\u0000');
    }

    public static final class ArchiveException extends Exception {
        public ArchiveException(String message) { super(message); }
        public ArchiveException(String message, Throwable cause) { super(message, cause); }
    }
}
