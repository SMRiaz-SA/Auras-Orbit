package com.auras.orbit.downloads;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Runtime acceptance after the host selects a folder through the app's real SAF picker,
 * force-stops the app, and relaunches it. The host supplies that picker URI as an argument;
 * this test verifies the resulting preference and grant without performing those host actions.
 */
@RunWith(AndroidJUnit4.class)
public final class DownloadFolderPersistedGrantRuntimeAcceptanceTest {
    private static final String EXPECTED_TREE_URI_ARG = "expectedTreeUri";
    private static final String DOWNLOADS_PREFERENCES = "orbit_downloads_shell_v1";
    private static final String FOLDER_URI_KEY = "folder_uri";
    private static final byte[] SENTINEL = "orbit-saf-grant-v1".getBytes(StandardCharsets.UTF_8);

    @Test public void hostSelectedPickerTreeGrantRemainsWritableAfterAppRestart() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String expectedTreeUri = InstrumentationRegistry.getArguments()
                .getString(EXPECTED_TREE_URI_ARG);
        assertNotNull("host must provide the exact picker result as expectedTreeUri",
                expectedTreeUri);
        assertFalse("expectedTreeUri must not be blank", expectedTreeUri.trim().isEmpty());

        String storedTreeUri = target.getSharedPreferences(DOWNLOADS_PREFERENCES,
                Context.MODE_PRIVATE).getString(FOLDER_URI_KEY, null);
        assertEquals("the production folder preference must match the host's picker result",
                expectedTreeUri, storedTreeUri);

        Uri tree = Uri.parse(expectedTreeUri);
        assertTrue("expectedTreeUri must be a SAF tree URI", DocumentsContract.isTreeUri(tree));

        ContentResolver resolver = target.getContentResolver();
        boolean hasExactPersistedWriteGrant = false;
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (tree.equals(permission.getUri()) && permission.isWritePermission()) {
                hasExactPersistedWriteGrant = true;
                break;
            }
        }
        assertTrue("the exact selected tree must still have a persisted write grant after restart",
                hasExactPersistedWriteGrant);

        Uri treeRoot = DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        String syntheticName = "orbit-saf-grant-check-" + UUID.randomUUID() + ".txt";
        Uri createdDocument = null;
        try {
            createdDocument = DocumentsContract.createDocument(resolver, treeRoot,
                    "text/plain", syntheticName);
            assertNotNull("the persisted grant must allow creating a document", createdDocument);

            try (OutputStream output = resolver.openOutputStream(createdDocument, "wt")) {
                assertNotNull("the created document must be writable", output);
                output.write(SENTINEL);
            }

            byte[] actual;
            try (InputStream input = resolver.openInputStream(createdDocument);
                 ByteArrayOutputStream output = new ByteArrayOutputStream(SENTINEL.length)) {
                assertNotNull("the created document must be readable", input);
                byte[] buffer = new byte[64];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                actual = output.toByteArray();
            }
            assertArrayEquals("the created document must return the exact sentinel bytes",
                    SENTINEL, actual);
        } finally {
            if (createdDocument != null) {
                assertTrue("the test's unique synthetic document must be deleted",
                        DocumentsContract.deleteDocument(resolver, createdDocument));
            }
        }
    }
}
