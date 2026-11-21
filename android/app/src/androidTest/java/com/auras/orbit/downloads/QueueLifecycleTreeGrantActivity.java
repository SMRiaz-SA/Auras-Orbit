package com.auras.orbit.downloads;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Host-launched AndroidTest activity that obtains SAF grants for the test APK, then delegates the
 * selected fixture tree to Orbit without writing Orbit's selected-folder preference.
 */
public final class QueueLifecycleTreeGrantActivity extends Activity {
    static final String EXPECTED_TREE_DOCUMENT_ID = "expectedTreeDocumentId";
    static final String GRANT_PREFERENCES = "orbit_queue_lifecycle_grants_v1";
    static final String ACTION_CLEANUP_ALL =
            "com.auras.orbit.test.downloadqueue.CLEANUP_ALL";
    static final String ACTION_CLEANUP_RESULT =
            "com.auras.orbit.test.downloadqueue.CLEANUP_RESULT";
    static final String ACTION_DELEGATE_SELECTED_TREES =
            "com.auras.orbit.test.downloadqueue.DELEGATE_SELECTED_TREES";
    static final String ACTION_DELEGATE_RESULT =
            "com.auras.orbit.test.downloadqueue.DELEGATE_RESULT";
    static final String ACTION_REVOKE_ORBIT_TREE =
            "com.auras.orbit.test.downloadqueue.REVOKE_ORBIT_TREE";
    static final String ACTION_REVOKE_ORBIT_TREE_RESULT =
            "com.auras.orbit.test.downloadqueue.REVOKE_ORBIT_TREE_RESULT";
    static final String ACTION_VERIFY_NO_FIXTURE_GRANTS =
            "com.auras.orbit.test.downloadqueue.VERIFY_NO_FIXTURE_GRANTS";
    static final String ACTION_VERIFY_NO_FIXTURE_GRANTS_RESULT =
            "com.auras.orbit.test.downloadqueue.VERIFY_NO_FIXTURE_GRANTS_RESULT";
    static final String ACTION_READ_FIXTURE_DOCUMENT =
            "com.auras.orbit.test.downloadqueue.READ_FIXTURE_DOCUMENT";
    static final String ACTION_READ_FIXTURE_DOCUMENT_RESULT =
            "com.auras.orbit.test.downloadqueue.READ_FIXTURE_DOCUMENT_RESULT";
    static final String ACTION_VALIDATE_RECOVERY_TREE_A =
            "com.auras.orbit.test.downloadqueue.VALIDATE_RECOVERY_TREE_A";
    static final String ACTION_VALIDATE_RECOVERY_TREE_A_RESULT =
            "com.auras.orbit.test.downloadqueue.VALIDATE_RECOVERY_TREE_A_RESULT";
    static final String EXTRA_CLEANUP_NONCE = "queueLifecycleCleanupNonce";
    static final String EXTRA_CLEANUP_ERRORS = "queueLifecycleCleanupErrors";
    static final String EXTRA_DELEGATE_NONCE = "queueLifecycleDelegateNonce";
    static final String EXTRA_DELEGATE_ERRORS = "queueLifecycleDelegateErrors";
    static final String EXTRA_REVOKE_NONCE = "queueLifecycleRevokeNonce";
    static final String EXTRA_REVOKE_ROOT = "queueLifecycleRevokeRoot";
    static final String EXTRA_REVOKE_ERRORS = "queueLifecycleRevokeErrors";
    static final String EXTRA_VERIFY_NONCE = "queueLifecycleVerifyNonce";
    static final String EXTRA_VERIFY_ERRORS = "queueLifecycleVerifyErrors";
    static final String EXTRA_READ_NONCE = "queueLifecycleReadNonce";
    static final String EXTRA_READ_DOCUMENT_URI = "queueLifecycleReadDocumentUri";
    static final String EXTRA_READ_DOCUMENT_BYTES = "queueLifecycleReadDocumentBytes";
    static final String EXTRA_READ_ERRORS = "queueLifecycleReadErrors";
    static final String EXTRA_READ_HELPER_UID = "queueLifecycleReadHelperUid";
    static final String EXTRA_READ_HELPER_PID = "queueLifecycleReadHelperPid";
    static final String EXTRA_RECOVERY_ROW_ID = "queueLifecycleRecoveryRowId";
    static final String EXTRA_RECOVERY_ROW_PRESENT = "queueLifecycleRecoveryRowPresent";
    static final String EXTRA_RECOVERY_PARTIAL_ID = "queueLifecycleRecoveryPartialId";
    static final String EXTRA_RECOVERY_PARTIAL_NAME = "queueLifecycleRecoveryPartialName";
    static final String EXTRA_RECOVERY_FINAL_NAME = "queueLifecycleRecoveryFinalName";
    static final String EXTRA_RECOVERY_PARTIAL_BYTES = "queueLifecycleRecoveryPartialBytes";
    static final String EXTRA_RECOVERY_FINAL_BYTES = "queueLifecycleRecoveryFinalBytes";
    static final String EXTRA_RECOVERY_NONCE = "queueLifecycleRecoveryNonce";
    static final String EXTRA_RECOVERY_ERRORS = "queueLifecycleRecoveryErrors";
    static final String EXTRA_RECOVERY_HELPER_UID = "queueLifecycleRecoveryHelperUid";
    static final String EXTRA_RECOVERY_HELPER_PID = "queueLifecycleRecoveryHelperPid";
    private static final int REQUEST_TREE = 7104;
    private static final String TARGET_PACKAGE = "com.auras.orbit";
    private static final String TAG = "QueueLifecycleGrant";
    private static final int MODE_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private String expectedRoot;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ACTION_CLEANUP_ALL.equals(getIntent().getAction())) {
            cleanupAllDelegatedTrees(getIntent().getStringExtra(EXTRA_CLEANUP_NONCE));
            return;
        }
        if (ACTION_DELEGATE_SELECTED_TREES.equals(getIntent().getAction())) {
            delegateSelectedTreesToOrbit(getIntent().getStringExtra(EXTRA_DELEGATE_NONCE));
            return;
        }
        if (ACTION_REVOKE_ORBIT_TREE.equals(getIntent().getAction())) {
            revokeOrbitFixtureTree(getIntent().getStringExtra(EXTRA_REVOKE_ROOT),
                    getIntent().getStringExtra(EXTRA_REVOKE_NONCE));
            return;
        }
        if (ACTION_VERIFY_NO_FIXTURE_GRANTS.equals(getIntent().getAction())) {
            verifyNoFixtureGrants(getIntent().getStringExtra(EXTRA_VERIFY_NONCE));
            return;
        }
        if (ACTION_READ_FIXTURE_DOCUMENT.equals(getIntent().getAction())) {
            readFixtureDocument(getIntent().getStringExtra(EXTRA_READ_NONCE),
                    getIntent().getStringExtra(EXTRA_READ_DOCUMENT_URI));
            return;
        }
        if (ACTION_VALIDATE_RECOVERY_TREE_A.equals(getIntent().getAction())) {
            validateRecoveryTreeA(getIntent());
            return;
        }

        expectedRoot = getIntent().getStringExtra(EXPECTED_TREE_DOCUMENT_ID);
        if (!isFixtureRoot(expectedRoot)) {
            finishWithError("Pass one exact queue lifecycle fixture root ID.");
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        picker.addFlags(MODE_FLAGS | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUriUsingTree(
                        DocumentsContract.buildTreeDocumentUri(
                                QueueLifecycleDocumentsProvider.AUTHORITY, expectedRoot),
                        expectedRoot));
        startActivityForResult(picker, REQUEST_TREE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finishWithError("The system picker did not return a folder tree.");
            return;
        }

        Uri tree = data.getData();
        int modeFlags = data.getFlags() & MODE_FLAGS;
        SharedPreferences grants = getSharedPreferences(GRANT_PREFERENCES, MODE_PRIVATE);
        String previousSelection = grants.getString(treePreferenceKey(expectedRoot), null);
        boolean helperPersisted = false;
        try {
            if (!QueueLifecycleDocumentsProvider.isCanonicalTreeRoot(tree, expectedRoot)) {
                finishWithError("Select the requested Queue Lifecycle fixture root.");
                return;
            }
            if (previousSelection != null && !previousSelection.equals(tree.toString())) {
                Uri previousTree = Uri.parse(previousSelection);
                if (!QueueLifecycleDocumentsProvider.isCanonicalTreeRoot(previousTree, expectedRoot)) {
                    finishWithError("The previous selection is not a canonical fixture root; preserve it for host cleanup.");
                    return;
                }
            }
            if ((modeFlags & MODE_FLAGS) != MODE_FLAGS) {
                finishWithError("The picker did not grant both read and write access.");
                return;
            }

            // Keep the helper's incoming picker grant so it can later revoke the exact
            // URI grant it delegates to Orbit after instrumentation starts.
            getContentResolver().takePersistableUriPermission(tree, modeFlags);
            helperPersisted = true;

            boolean recorded = grants.edit()
                    .putString(treePreferenceKey(expectedRoot), tree.toString())
                    .commit();
            if (!recorded) {
                if (!tree.toString().equals(previousSelection)) {
                    rollbackNewHelperGrant(tree, modeFlags, true);
                }
                finishWithError("Could not record the selected fixture tree in the test APK.");
                return;
            }

            setResult(RESULT_OK, new Intent().setData(tree));
            finish();
        } catch (RuntimeException error) {
            if (!tree.toString().equals(previousSelection)) {
                rollbackNewHelperGrant(tree, modeFlags, helperPersisted);
            }
            finishWithError("Could not persist and record the selected fixture tree: "
                    + error.getClass().getSimpleName());
        }
    }

    static String treePreferenceKey(String rootDocumentId) {
        return "tree_uri:" + rootDocumentId;
    }

    private static boolean isFixtureRoot(String root) {
        return QueueLifecycleDocumentsProvider.TREE_A.equals(root)
                || QueueLifecycleDocumentsProvider.TREE_B.equals(root);
    }

    private void rollbackNewHelperGrant(Uri tree, int modeFlags,
            boolean helperPersisted) {
        if (helperPersisted) {
            try { getContentResolver().releasePersistableUriPermission(tree, modeFlags); }
            catch (RuntimeException releaseError) {
                Log.e(TAG, "Could not release failed helper picker grant", releaseError);
            }
        }
    }

    private void delegateSelectedTreesToOrbit(String nonce) {
        List<String> errors = new ArrayList<>();
        SharedPreferences grants = null;
        try { grants = getSharedPreferences(GRANT_PREFERENCES, MODE_PRIVATE); }
        catch (RuntimeException error) {
            errors.add("Could not open delegation handoff preferences: "
                    + error.getClass().getSimpleName());
        }
        attemptDelegateRecordedTree(grants, QueueLifecycleDocumentsProvider.TREE_A, errors);
        attemptDelegateRecordedTree(grants, QueueLifecycleDocumentsProvider.TREE_B, errors);

        Intent result = new Intent(ACTION_DELEGATE_RESULT)
                .setPackage(TARGET_PACKAGE)
                .putStringArrayListExtra(EXTRA_DELEGATE_ERRORS, new ArrayList<>(errors));
        if (nonce != null) result.putExtra(EXTRA_DELEGATE_NONCE, nonce);
        if (errors.isEmpty()) {
            setResult(RESULT_OK, result);
        } else {
            setResult(RESULT_CANCELED, result);
            Log.e(TAG, "Fixture grant delegation completed with errors: " + errors);
        }
        finish();
        if (nonce != null) sendBroadcast(result);
    }

    /** Revokes only Orbit's transient grant for exact Tree A; the helper keeps its own grant. */
    private void revokeOrbitFixtureTree(String root, String nonce) {
        List<String> errors = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 26) {
            errors.add("package-scoped revoke requires API 26 or later");
        } else if (!QueueLifecycleDocumentsProvider.TREE_A.equals(root)) {
            errors.add("only exact Tree A may be revoked by the grant-loss fixture");
        } else {
            Uri tree = DocumentsContract.buildTreeDocumentUri(
                    QueueLifecycleDocumentsProvider.AUTHORITY, root);
            try {
                SharedPreferences grants = getSharedPreferences(GRANT_PREFERENCES, MODE_PRIVATE);
                String recorded = grants.getString(treePreferenceKey(root), null);
                if (!tree.toString().equals(recorded)
                        || !QueueLifecycleDocumentsProvider.isCanonicalTreeRoot(tree, root)) {
                    throw new IllegalStateException("helper does not retain the exact canonical Tree A selection");
                }
                if ((persistedModeFlags(tree) & MODE_FLAGS) != MODE_FLAGS) {
                    throw new SecurityException("helper must keep its own read/write Tree A grant");
                }
                revokeUriPermission(TARGET_PACKAGE, tree, MODE_FLAGS);
                if ((persistedModeFlags(tree) & MODE_FLAGS) != MODE_FLAGS) {
                    throw new SecurityException("revocation unexpectedly changed the helper's Tree A grant");
                }
            } catch (RuntimeException error) {
                errors.add("Tree A revoke failed: " + error.getClass().getSimpleName());
            }
        }

        Intent result = new Intent(ACTION_REVOKE_ORBIT_TREE_RESULT)
                .setPackage(TARGET_PACKAGE)
                .putStringArrayListExtra(EXTRA_REVOKE_ERRORS, new ArrayList<>(errors));
        if (nonce != null) result.putExtra(EXTRA_REVOKE_NONCE, nonce);
        if (errors.isEmpty()) setResult(RESULT_OK, result);
        else {
            setResult(RESULT_CANCELED, result);
            Log.e(TAG, "Orbit Tree A grant revocation failed: " + errors);
        }
        finish();
        if (nonce != null) sendBroadcast(result);
    }

    /** Verifies this helper package has neither persisted nor transient Tree A/B access. */
    private void verifyNoFixtureGrants(String nonce) {
        List<String> errors = new ArrayList<>();
        for (String root : new String[]{QueueLifecycleDocumentsProvider.TREE_A,
                QueueLifecycleDocumentsProvider.TREE_B}) {
            Uri tree = DocumentsContract.buildTreeDocumentUri(
                    QueueLifecycleDocumentsProvider.AUTHORITY, root);
            int persisted = persistedModeFlags(tree);
            if (persisted != 0) {
                errors.add(root + ": helper persisted modes remain 0x"
                        + Integer.toHexString(persisted));
            }
            for (int mode : new int[]{Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION}) {
                if (checkUriPermission(tree, Process.myPid(), Process.myUid(), mode)
                        != android.content.pm.PackageManager.PERMISSION_DENIED) {
                    errors.add(root + ": helper still has transient mode 0x"
                            + Integer.toHexString(mode));
                }
            }
        }

        Intent result = new Intent(ACTION_VERIFY_NO_FIXTURE_GRANTS_RESULT)
                .setPackage(TARGET_PACKAGE)
                .putStringArrayListExtra(EXTRA_VERIFY_ERRORS, new ArrayList<>(errors));
        if (nonce != null) result.putExtra(EXTRA_VERIFY_NONCE, nonce);
        if (errors.isEmpty()) setResult(RESULT_OK, result);
        else {
            setResult(RESULT_CANCELED, result);
            Log.e(TAG, "Helper retained queue fixture grants after cleanup: " + errors);
        }
        finish();
        if (nonce != null) sendBroadcast(result);
    }

    /** Reads the marked Tree A child from this helper package's own UID after Orbit loses access. */
    private void readFixtureDocument(String nonce, String rawDocumentUri) {
        List<String> errors = new ArrayList<>();
        byte[] documentBytes = null;
        try {
            if (rawDocumentUri == null) throw new IllegalArgumentException("missing document URI");
            Uri document = Uri.parse(rawDocumentUri);
            Uri tree = DocumentsContract.buildTreeDocumentUri(
                    QueueLifecycleDocumentsProvider.AUTHORITY,
                    QueueLifecycleDocumentsProvider.TREE_A);
            if (!DocumentsContract.isTreeUri(document)
                    || !QueueLifecycleDocumentsProvider.TREE_A.equals(
                            DocumentsContract.getTreeDocumentId(document))) {
                throw new SecurityException("document is outside exact Tree A");
            }
            String documentId = DocumentsContract.getDocumentId(document);
            if (documentId == null || !documentId.startsWith(
                    QueueLifecycleDocumentsProvider.TREE_A + "-")
                    || !document.equals(DocumentsContract.buildDocumentUriUsingTree(
                            tree, documentId))) {
                throw new SecurityException("document URI is not an exact Tree A fixture child");
            }
            if ((persistedModeFlags(tree) & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
                throw new SecurityException("helper lacks its persisted Tree A read grant");
            }
            try (InputStream input = getContentResolver().openInputStream(document);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new IOException("provider returned no document stream");
                byte[] buffer = new byte[256];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, count);
                    if (output.size() > 65_536) {
                        throw new IOException("fixture document exceeded the bounded read size");
                    }
                }
                documentBytes = output.toByteArray();
            }
        } catch (Exception error) {
            errors.add("Tree A document read failed: " + error.getClass().getSimpleName());
        }

        Intent result = new Intent(ACTION_READ_FIXTURE_DOCUMENT_RESULT)
                .setPackage(TARGET_PACKAGE)
                .putStringArrayListExtra(EXTRA_READ_ERRORS, new ArrayList<>(errors))
                .putExtra(EXTRA_READ_HELPER_UID, Process.myUid())
                .putExtra(EXTRA_READ_HELPER_PID, Process.myPid());
        if (nonce != null) result.putExtra(EXTRA_READ_NONCE, nonce);
        if (documentBytes != null) result.putExtra(EXTRA_READ_DOCUMENT_BYTES, documentBytes);
        if (errors.isEmpty()) setResult(RESULT_OK, result);
        else {
            setResult(RESULT_CANCELED, result);
            Log.e(TAG, "Fixture document read failed: " + errors);
        }
        finish();
        if (nonce != null) sendBroadcast(result);
    }

    /** Validates the exact recoverable Tree A contents using this helper app's own URI grant. */
    private void validateRecoveryTreeA(Intent request) {
        String nonce = request.getStringExtra(EXTRA_RECOVERY_NONCE);
        List<String> errors = new ArrayList<>();
        try {
            Uri tree = DocumentsContract.buildTreeDocumentUri(
                    QueueLifecycleDocumentsProvider.AUTHORITY,
                    QueueLifecycleDocumentsProvider.TREE_A);
            if ((persistedModeFlags(tree) & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
                throw new SecurityException("helper lacks persisted Tree A read access");
            }
            String rowId = request.getStringExtra(EXTRA_RECOVERY_ROW_ID);
            boolean rowPresent = request.getBooleanExtra(EXTRA_RECOVERY_ROW_PRESENT, false);
            String partialId = request.getStringExtra(EXTRA_RECOVERY_PARTIAL_ID);
            String markedPartialName = request.getStringExtra(EXTRA_RECOVERY_PARTIAL_NAME);
            String finalName = request.getStringExtra(EXTRA_RECOVERY_FINAL_NAME);
            byte[] expectedPartial = request.getByteArrayExtra(EXTRA_RECOVERY_PARTIAL_BYTES);
            byte[] expectedFinal = request.getByteArrayExtra(EXTRA_RECOVERY_FINAL_BYTES);
            if (expectedPartial == null || expectedFinal == null) {
                throw new IllegalArgumentException("recovery byte expectations are missing");
            }
            if (rowId != null && !rowId.isEmpty() && !isUuid(rowId)) {
                throw new IllegalArgumentException("recovery row ID is not canonical");
            }
            if (rowPresent && (rowId == null || rowId.isEmpty())) {
                throw new IllegalArgumentException("a present recovery row requires its exact row ID");
            }

            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            List<TreeChild> fixtureChildren = new ArrayList<>();
            try (Cursor cursor = getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor == null) throw new IOException("Tree A child listing returned no cursor");
                int idIndex = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameIndex = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                while (cursor.moveToNext()) {
                    String childId = cursor.getString(idIndex);
                    String childName = cursor.getString(nameIndex);
                    if (".nomedia".equals(childName)) continue;
                    if (!isCanonicalTreeAChildId(childId)) {
                        throw new IOException("Tree A contains a child outside its exact ID namespace");
                    }
                    fixtureChildren.add(new TreeChild(childId, childName));
                }
            }

            String exactPartialName = rowId == null || rowId.isEmpty()
                    ? "" : ".orbit-part-" + rowId;
            if (markedPartialName != null && !markedPartialName.isEmpty()
                    && (!exactPartialName.equals(markedPartialName)
                    && !(exactPartialName + ".mp4").equals(markedPartialName))) {
                throw new IOException("marked partial name does not match the exact queue row");
            }
            if (!rowPresent && (rowId == null || rowId.isEmpty())) {
                if ((markedPartialName != null && !markedPartialName.isEmpty())
                        || (partialId != null && !partialId.isEmpty())
                        || (finalName != null && !finalName.isEmpty())
                        || !fixtureChildren.isEmpty()) {
                    throw new IOException("stable-ID-only no-row setup state must have an empty Tree A");
                }
            } else if (fixtureChildren.isEmpty()) {
                // Cleanup can stop after deleting the exact child but before deleting its row,
                // or after deleting both while the marker is still present. The caller has
                // already validated the exact row/stable identity; an empty inventory proves
                // there is no untracked child left to widen cleanup onto.
            } else if (finalName != null && !finalName.isEmpty()) {
                requireOneNamedChild(fixtureChildren, finalName);
                TreeChild completed = fixtureChildren.get(0);
                if (!sameBytes(expectedFinal, readTreeChild(tree, completed.id))) {
                    throw new IOException("completed fixture file bytes do not match the exact retry payload");
                }
            } else if (partialId != null && !partialId.isEmpty()) {
                requireOneNamedChild(fixtureChildren,
                        markedPartialName == null || markedPartialName.isEmpty()
                                ? exactPartialName : markedPartialName);
                TreeChild partial = fixtureChildren.get(0);
                if (!partial.id.equals(partialId)) {
                    throw new IOException("the marked partial document ID is not the listed exact child");
                }
                if (!sameBytes(expectedPartial, readTreeChild(tree, partial.id))) {
                    throw new IOException("the marked interrupted partial bytes are not exact");
                }
            } else if (!fixtureChildren.isEmpty()) {
                requireOnePartialChild(fixtureChildren, exactPartialName);
                TreeChild partial = fixtureChildren.get(0);
                if (!isPrefix(expectedPartial, readTreeChild(tree, partial.id))) {
                    throw new IOException("an uncommitted partial contains bytes outside the exact source prefix");
                }
            }
        } catch (Exception error) {
            errors.add("Tree A recovery inventory failed: " + error.getClass().getSimpleName());
        }

        Intent result = new Intent(ACTION_VALIDATE_RECOVERY_TREE_A_RESULT)
                .setPackage(TARGET_PACKAGE)
                .putStringArrayListExtra(EXTRA_RECOVERY_ERRORS, new ArrayList<>(errors))
                .putExtra(EXTRA_RECOVERY_HELPER_UID, Process.myUid())
                .putExtra(EXTRA_RECOVERY_HELPER_PID, Process.myPid());
        if (nonce != null) result.putExtra(EXTRA_RECOVERY_NONCE, nonce);
        if (errors.isEmpty()) setResult(RESULT_OK, result);
        else {
            setResult(RESULT_CANCELED, result);
            Log.e(TAG, "Tree A recovery inventory failed: " + errors);
        }
        finish();
        if (nonce != null) sendBroadcast(result);
    }

    private void requireOneNamedChild(List<TreeChild> children, String exactName)
            throws IOException {
        if (exactName == null || exactName.isEmpty() || children.size() != 1) {
            throw new IOException("Tree A does not contain exactly one expected fixture child");
        }
        String withMp4 = exactName.endsWith(".mp4") ? exactName : exactName + ".mp4";
        String withoutMp4 = exactName.endsWith(".mp4")
                ? exactName.substring(0, exactName.length() - 4) : exactName;
        String actualName = children.get(0).name;
        if (!withoutMp4.equals(actualName) && !withMp4.equals(actualName)) {
            throw new IOException("Tree A child name does not match the exact fixture reservation");
        }
    }

    private void requireOnePartialChild(List<TreeChild> children, String exactPartialName)
            throws IOException {
        if (children.size() > 1) {
            throw new IOException("Tree A contains more than one child during partial creation");
        }
        if (children.size() == 1) requireOneNamedChild(children, exactPartialName);
    }

    private byte[] readTreeChild(Uri tree, String documentId) throws IOException {
        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
        try (InputStream input = getContentResolver().openInputStream(document);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("Tree A returned no fixture document stream");
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                if (output.size() > 65_536) {
                    throw new IOException("fixture document exceeded the bounded read size");
                }
            }
            return output.toByteArray();
        }
    }

    private static boolean sameBytes(byte[] expected, byte[] actual) {
        if (expected == null || actual == null || expected.length != actual.length) return false;
        for (int index = 0; index < expected.length; index++) {
            if (expected[index] != actual[index]) return false;
        }
        return true;
    }

    private static boolean isPrefix(byte[] expected, byte[] actual) {
        if (expected == null || actual == null || actual.length > expected.length) return false;
        for (int index = 0; index < actual.length; index++) {
            if (expected[index] != actual[index]) return false;
        }
        return true;
    }

    private static boolean isUuid(String value) {
        try { return value.equals(UUID.fromString(value).toString()); }
        catch (IllegalArgumentException malformed) { return false; }
    }

    private static boolean isCanonicalTreeAChildId(String childId) {
        String prefix = QueueLifecycleDocumentsProvider.TREE_A + "-";
        return childId != null && childId.startsWith(prefix)
                && isUuid(childId.substring(prefix.length()));
    }

    private static final class TreeChild {
        final String id;
        final String name;
        TreeChild(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private void attemptDelegateRecordedTree(SharedPreferences grants, String root,
            List<String> errors) {
        Uri canonicalTree = DocumentsContract.buildTreeDocumentUri(
                QueueLifecycleDocumentsProvider.AUTHORITY, root);
        try {
            if (grants == null) throw new IllegalStateException("preferences unavailable");
            String recorded = grants.getString(treePreferenceKey(root), null);
            if (!canonicalTree.toString().equals(recorded)
                    || !QueueLifecycleDocumentsProvider.isCanonicalTreeRoot(canonicalTree, root)) {
                throw new IllegalStateException("no exact canonical picker URI is recorded");
            }
            int ownModes = persistedModeFlags(canonicalTree);
            if ((ownModes & MODE_FLAGS) != MODE_FLAGS) {
                throw new SecurityException("helper lacks persisted read/write picker modes");
            }
            int delegatedFlags = MODE_FLAGS | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
            grantUriPermission(TARGET_PACKAGE, canonicalTree, delegatedFlags);
        } catch (RuntimeException error) {
            errors.add(root + ": delegation failed: " + error.getClass().getSimpleName());
        }
    }

    private void cleanupAllDelegatedTrees(String nonce) {
        List<String> errors = new ArrayList<>();
        SharedPreferences grants = null;
        try { grants = getSharedPreferences(GRANT_PREFERENCES, MODE_PRIVATE); }
        catch (RuntimeException error) {
            errors.add("Could not open cleanup handoff preferences: " + error.getClass().getSimpleName());
        }
        attemptCleanupRecordedTree(grants, QueueLifecycleDocumentsProvider.TREE_A, errors);
        attemptCleanupRecordedTree(grants, QueueLifecycleDocumentsProvider.TREE_B, errors);
        verifyOwnFixtureGrantsAbsent(QueueLifecycleDocumentsProvider.TREE_A, errors);
        verifyOwnFixtureGrantsAbsent(QueueLifecycleDocumentsProvider.TREE_B, errors);

        Intent result = new Intent(ACTION_CLEANUP_RESULT)
                .setPackage(TARGET_PACKAGE)
                .putStringArrayListExtra(EXTRA_CLEANUP_ERRORS, new ArrayList<>(errors));
        if (nonce != null) {
            result.putExtra(EXTRA_CLEANUP_NONCE, nonce);
        }
        if (errors.isEmpty()) {
            setResult(RESULT_OK, result);
        } else {
            setResult(RESULT_CANCELED, result);
            Log.e(TAG, "Fixture grant cleanup completed with errors: " + errors);
        }
        finish();
        if (nonce != null) sendBroadcast(result);
    }

    private void attemptCleanupRecordedTree(SharedPreferences grants, String root,
            List<String> errors) {
        try { cleanupRecordedTree(grants, root, errors); }
        catch (RuntimeException error) {
            errors.add(root + ": cleanup failed: " + error.getClass().getSimpleName());
        }
    }

    private void verifyOwnFixtureGrantsAbsent(String root, List<String> errors) {
        Uri canonicalTree = DocumentsContract.buildTreeDocumentUri(
                QueueLifecycleDocumentsProvider.AUTHORITY, root);
        try {
            int remaining = persistedModeFlags(canonicalTree);
            if (remaining != 0) {
                errors.add(root + ": helper still has persisted modes after cleanup: 0x"
                        + Integer.toHexString(remaining));
            }
        } catch (RuntimeException error) {
            errors.add(root + ": helper persisted-grant verification failed: "
                    + error.getClass().getSimpleName());
        }
    }

    private void cleanupRecordedTree(SharedPreferences grants, String root, List<String> errors) {
        String key = treePreferenceKey(root);
        Uri canonicalTree = DocumentsContract.buildTreeDocumentUri(
                QueueLifecycleDocumentsProvider.AUTHORITY, root);
        String rawTree;
        boolean preferencesRead = true;
        try {
            if (grants == null) throw new IllegalStateException("preferences unavailable");
            rawTree = grants.getString(key, null);
        }
        catch (RuntimeException error) {
            errors.add(root + ": could not read its recorded tree URI: " + error.getClass().getSimpleName());
            rawTree = null;
            preferencesRead = false;
        }

        boolean recorded = rawTree != null;
        boolean recordedUriValid = !recorded;
        if (recorded) {
            Uri recordedTree = null;
            try { recordedTree = Uri.parse(rawTree); }
            catch (RuntimeException malformed) {
                errors.add(root + ": recorded tree URI is malformed; record retained");
            }
            if (recordedTree != null
                    && (!QueueLifecycleDocumentsProvider.isCanonicalTreeRoot(recordedTree, root)
                    || !canonicalTree.equals(recordedTree))) {
                errors.add(root + ": recorded tree URI is not its canonical root; record retained");
            } else if (recordedTree != null) {
                recordedUriValid = true;
            }
        }

        boolean cleanupSucceeded = preferencesRead && recordedUriValid;
        if (Build.VERSION.SDK_INT >= 26) {
            try { revokeUriPermission(TARGET_PACKAGE, canonicalTree, MODE_FLAGS); }
            catch (RuntimeException revokeError) {
                cleanupSucceeded = false;
                errors.add(root + ": Orbit outgoing grant revoke failed: "
                        + revokeError.getClass().getSimpleName());
            }
        }
        // The fixture suite is skipped before delegation on API <26; avoid the older unscoped
        // overload there because it could revoke a different package's grant.

        int ownPersistedFlags = persistedModeFlags(canonicalTree);
        if (ownPersistedFlags != 0) {
            try { getContentResolver().releasePersistableUriPermission(canonicalTree, ownPersistedFlags); }
            catch (RuntimeException releaseError) {
                cleanupSucceeded = false;
                errors.add(root + ": helper persisted grant release failed: "
                        + releaseError.getClass().getSimpleName());
            }
        }
        int remainingFlags = persistedModeFlags(canonicalTree);
        if (remainingFlags != 0) {
            cleanupSucceeded = false;
            errors.add(root + ": helper still owns persisted URI modes 0x"
                    + Integer.toHexString(remainingFlags));
        }

        if (recorded && preferencesRead && recordedUriValid && cleanupSucceeded) {
            try {
                if (!grants.edit().remove(key).commit()) {
                    cleanupSucceeded = false;
                    errors.add(root + ": cleanup completed but its handoff record could not be cleared");
                }
            } catch (RuntimeException removeError) {
                cleanupSucceeded = false;
                errors.add(root + ": cleanup completed but its handoff record could not be cleared");
            }
        }
    }

    private int persistedModeFlags(Uri tree) {
        int flags = 0;
        for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (!tree.equals(permission.getUri())) continue;
            if (permission.isReadPermission()) flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (permission.isWritePermission()) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        }
        return flags;
    }

    private void finishWithError(String message) {
        Log.e(TAG, message);
        setResult(RESULT_CANCELED, new Intent().putExtra("error", message));
        finish();
    }
}
