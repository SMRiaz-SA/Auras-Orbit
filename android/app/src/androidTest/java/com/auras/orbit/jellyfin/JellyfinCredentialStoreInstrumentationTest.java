package com.auras.orbit.jellyfin;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.jellyfin.JellyfinModels.Connection;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exercises the encrypted Jellyfin credential round trip on the isolated test application. */
@RunWith(AndroidJUnit4.class)
public final class JellyfinCredentialStoreInstrumentationTest {
    private static final String TARGET_PACKAGE = "com.auras.orbit.queuerender";
    private static final String PREFS = "orbit_jellyfin_connection_v1";
    private static final String ACCESS_TOKEN = "synthetic-roundtrip-fixture-token";
    private JellyfinCredentialStore store;
    private Context target;

    @Before public void prepareIsolatedStore() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(TARGET_PACKAGE, target.getPackageName());
        store = new JellyfinCredentialStore(target);
        // This package is the existing disposable test variant; reset only its Jellyfin key/blob.
        store.disconnect();
    }

    @After public void removeSyntheticConnection() throws Exception {
        if (store != null) store.disconnect();
    }

    @Test public void saveAndLoadRoundTripsSyntheticConnectionWithoutPlaintextSecrets() throws Exception {
        Connection expected = new Connection("https://127.0.0.1:8443/jellyfin/",
                "Living Room Server", "fixture-user-id", "Alex", "fixture-server-id",
                "fixture-device-id", ACCESS_TOKEN);

        store.save(expected);

        Connection restored = store.load();
        assertNotNull("saved Jellyfin connection can be decrypted", restored);
        assertEquals(expected.serverUrl, restored.serverUrl);
        assertEquals(expected.serverName, restored.serverName);
        assertEquals(expected.userId, restored.userId);
        assertEquals(expected.userName, restored.userName);
        assertEquals(expected.serverId, restored.serverId);
        assertEquals(expected.deviceId, restored.deviceId);
        assertEquals(expected.accessToken, restored.accessToken);
        assertFalse("connection summaries redact the token", restored.toString().contains(ACCESS_TOKEN));

        SharedPreferences preferences = target.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        assertEquals("only the nonce and ciphertext are persisted", 2, preferences.getAll().size());
        for (Object value : preferences.getAll().values()) {
            String encoded = String.valueOf(value);
            assertFalse("the access token remains encrypted at rest", encoded.contains(ACCESS_TOKEN));
            assertFalse("the server password is never persisted", encoded.contains("fixture-password"));
        }
        System.out.println("JELLYFIN_CREDENTIAL_ROUNDTRIP_PASSED encryptedFields="
                + preferences.getAll().size() + " tokenRedacted=true");
    }

    @Test public void serverNameIsSanitizedAndBoundedBeforePersistence() throws Exception {
        StringBuilder unsafeServerName = new StringBuilder("Living\n\u202e\ud800 Room");
        StringBuilder unsafeUserName = new StringBuilder("\u202eAlex\n\udc00");
        for (int index = 0; index < 130; index++) {
            unsafeServerName.appendCodePoint(0x1f642);
            unsafeUserName.appendCodePoint(0x1f642);
        }
        Connection sanitized = new Connection("https://127.0.0.1:8443/jellyfin/",
                unsafeServerName.toString(), "fixture-user-id", unsafeUserName.toString(),
                "fixture-server-id", "fixture-device-id", ACCESS_TOKEN);

        assertTrue(sanitized.serverName.startsWith("Living Room"));
        assertTrue(sanitized.userName.startsWith("Alex"));
        assertEquals(128, sanitized.serverName.codePointCount(0, sanitized.serverName.length()));
        assertEquals(128, sanitized.userName.codePointCount(0, sanitized.userName.length()));
        assertSafeDisplayName("server name", sanitized.serverName);
        assertSafeDisplayName("user name", sanitized.userName);
        Connection emptyFallback = new Connection("https://127.0.0.1:8443/jellyfin/",
                "\n\t\u0001\u202e", "fixture-user-id", "\n\t\u202e", "fixture-server-id",
                "fixture-device-id", ACCESS_TOKEN);
        assertEquals("", emptyFallback.serverName);
        assertEquals("", emptyFallback.userName);

        store.save(sanitized);
        Connection restored = store.load();
        assertNotNull(restored);
        assertEquals(sanitized.serverName, restored.serverName);
        assertEquals(sanitized.userName, restored.userName);
    }

    private static void assertSafeDisplayName(String label, String value) {
        assertTrue(label + " is bounded to 128 code points",
                value.codePointCount(0, value.length()) <= 128);
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int category = Character.getType(codePoint);
            assertFalse(label + " excludes control, format, and malformed surrogate code points",
                    Character.isISOControl(codePoint) || category == Character.FORMAT
                            || category == Character.SURROGATE);
            offset += Character.charCount(codePoint);
        }
    }
}
