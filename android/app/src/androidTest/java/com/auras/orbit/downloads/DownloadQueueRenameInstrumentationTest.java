package com.auras.orbit.downloads;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Focused completed-file rename, persisted identity, and restart-recovery coverage. */
@RunWith(AndroidJUnit4.class)
public final class DownloadQueueRenameInstrumentationTest {
    private static final String TEST_PROFILE = "download-rename-test-profile";
    private static final String TEST_STABLE_PREFIX = "orbit-rename-fixture-";
    private static final String TEST_FILE_PREFIX = "Orbit-Queue-Rename-Fixture-";
    private static final String TREE_DOCUMENT_ID = "primary:Download/Orbit Download Queue Fixture";
    private static final String STORAGE_AUTHORITY = "com.android.externalstorage.documents";
    private static final byte[] CONTENT = "orbit-rename-file-bytes-v1".getBytes(StandardCharsets.UTF_8);

    private Context target;
    private Uri tree;
    private DownloadQueueManager manager;
    private final Set<String> ownedIds = new HashSet<>();
    private final Set<String> ownedNames = new HashSet<>();

    @Before public void setUp() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        tree = selectedFixtureTree();
        assertTrue("the test must use the dedicated exact SAF tree", isFixtureTree(tree));
        assertPersistedReadWriteGrant(tree);
        manager = new DownloadQueueManager(target);
    }

    @After public void cleanOwnedFixtureRowsAndFiles() throws Exception {
        if (manager != null) {
            manager.close();
            manager = null;
        }
        if (target == null || !isFixtureTree(tree)) return;

        // The test owns only names it generated under this unique fixture prefix.
        for (FixtureDocument document : listChildren()) {
            if (!ownedNames.contains(document.name)) continue;
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, document.id);
            DocumentsContract.deleteDocument(target.getContentResolver(), uri);
            assertFalse("owned SAF fixture file should be removed", documentIdExists(document.id));
        }
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            for (String id : ownedIds) store.delete(id);
        } finally {
            store.close();
        }
    }

    @Test public void completedRenamePreservesExtensionBytesMimeIdentityAndPlaybackUri() throws Exception {
        String id = createCompletedItem("success");
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            DownloadRecord before = store.get(id);
            assertNotNull(before);
            Uri oldUri = Uri.parse(before.fileUri);
            DocumentMetadata beforeMetadata = metadata(oldUri);
            String targetBase = TEST_FILE_PREFIX + "renamed-" + UUID.randomUUID();
            String expectedName = targetBase + extensionOf(before.fileName);
            ownedNames.add(expectedName);

            manager.renameCompletedFile(id, targetBase);

            DownloadRecord after = store.get(id);
            assertNotNull(after);
            assertEquals(expectedName, after.fileName);
            assertEquals(before.id, after.id);
            assertEquals(before.profileId, after.profileId);
            assertEquals(before.stableItemId, after.stableItemId);
            assertEquals(before.createdAt, after.createdAt);
            assertEquals(before.mimeType, after.mimeType);
            assertEquals(before.bytesDownloaded, after.bytesDownloaded);
            assertEquals(before.totalBytes, after.totalBytes);
            assertEquals(DownloadRecord.COMPLETED, after.status);
            assertArrayEquals(CONTENT, readDocument(Uri.parse(after.fileUri)));
            assertNull("old sibling name must no longer exist", findByName(before.fileName));
            DocumentMetadata afterMetadata = metadata(Uri.parse(after.fileUri));
            assertEquals(expectedName, afterMetadata.displayName);
            assertEquals(beforeMetadata.mimeType, afterMetadata.mimeType);
            assertEquals(beforeMetadata.size, afterMetadata.size);
            assertEquals("the Offline playback handoff must use the persisted renamed URI",
                    Uri.parse(after.fileUri), manager.getOfflineUri(id));
        } finally {
            store.close();
        }
    }

    @Test public void completedRenameRejectsSiblingCollisionWithoutChangingEitherFile() throws Exception {
        String id = createCompletedItem("collision");
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            DownloadRecord before = store.get(id);
            assertNotNull(before);
            String targetBase = TEST_FILE_PREFIX + "collision-" + UUID.randomUUID();
            String targetName = targetBase + extensionOf(before.fileName);
            ownedNames.add(targetName);
            Uri sibling = createDocument(targetName, CONTENT);
            String originalUri = before.fileUri;

            try {
                manager.renameCompletedFile(id, targetBase);
                fail("rename must reject an existing sibling name");
            } catch (IOException expected) {
                assertTrue("collision should have a safe user-facing message",
                        expected.getMessage().toLowerCase(java.util.Locale.ROOT).contains("already exists"));
            }

            DownloadRecord after = store.get(id);
            assertNotNull(after);
            assertEquals(DownloadRecord.COMPLETED, after.status);
            assertEquals(before.fileName, after.fileName);
            assertEquals(originalUri, after.fileUri);
            assertArrayEquals(CONTENT, readDocument(Uri.parse(originalUri)));
            assertArrayEquals(CONTENT, readDocument(sibling));
        } finally {
            store.close();
        }
    }

    @Test public void renameRejectsDocumentUriOutsidePersistedItemTree() throws Exception {
        String id = createCompletedItem("foreign-uri");
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            DownloadRecord before = store.get(id);
            assertNotNull(before);
            Uri otherTree = Uri.parse("content://" + STORAGE_AUTHORITY
                    + "/tree/primary%3ADownload%2FOutside");
            Uri foreign = DocumentsContract.buildDocumentUriUsingTree(otherTree,
                    "primary:Download/Outside/fake.mp4");
            store.getWritableDatabase().execSQL("UPDATE downloads SET file_uri=? WHERE id=?",
                    new Object[]{foreign.toString(), id});

            try {
                manager.renameCompletedFile(id, TEST_FILE_PREFIX + "foreign-" + UUID.randomUUID());
                fail("rename must reject a URI that is not a child of the persisted row tree");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("selected folder"));
            }

            DownloadRecord after = store.get(id);
            assertNotNull(after);
            assertEquals(DownloadRecord.COMPLETED, after.status);
            assertEquals(foreign.toString(), after.fileUri);
            assertEquals(before.fileName, after.fileName);
            assertArrayEquals(CONTENT, readDocument(Uri.parse(before.fileUri)));
        } finally {
            store.close();
        }
    }

    @Test public void startupReconcilesRenameCommittedInSafBeforeProcessRestart() throws Exception {
        String id = createCompletedItem("restart");
        DownloadQueueStore store = new DownloadQueueStore(target);
        String targetName;
        String oldUri;
        String oldName;
        try {
            DownloadRecord before = store.get(id);
            assertNotNull(before);
            targetName = TEST_FILE_PREFIX + "recovered-" + UUID.randomUUID()
                    + extensionOf(before.fileName);
            ownedNames.add(targetName);
            oldUri = before.fileUri;
            oldName = before.fileName;
            assertTrue("rename journal must be durable before SAF mutation",
                    store.beginPendingRename(id, before.fileUri, before.fileName, targetName));
        } finally {
            store.close();
        }

        manager.close();
        manager = null;
        Uri renamed = DocumentsContract.renameDocument(target.getContentResolver(),
                Uri.parse(oldUri), targetName);
        assertNotNull("fixture provider must rename the file within its exact tree", renamed);
        assertEquals(targetName, metadata(renamed).displayName);
        assertArrayEquals(CONTENT, readDocument(renamed));

        manager = new DownloadQueueManager(target);
        DownloadQueueStore recoveredStore = new DownloadQueueStore(target);
        try {
            DownloadRecord recovered = recoveredStore.get(id);
            assertNotNull(recovered);
            assertEquals(DownloadRecord.COMPLETED, recovered.status);
            assertEquals(targetName, recovered.fileName);
            Uri storedUri = Uri.parse(recovered.fileUri);
            assertEquals("startup must persist the SAF document ID resolved under its selected tree",
                    DocumentsContract.getDocumentId(renamed), DocumentsContract.getDocumentId(storedUri));
            assertTrue("recovered URI must remain qualified by the item's exact selected tree",
                    DocumentsContract.isTreeUri(storedUri));
            assertEquals(TREE_DOCUMENT_ID, DocumentsContract.getTreeDocumentId(storedUri));
            assertEquals(storedUri, manager.getOfflineUri(id));
            assertArrayEquals(CONTENT, readDocument(Uri.parse(recovered.fileUri)));
            assertTrue("successful recovery must clear the journal",
                    recoveredStore.getPendingRenames().isEmpty());
            assertNull("the old exact child name must be absent", findByName(oldName));
        } finally {
            recoveredStore.close();
        }
    }

    @Test public void startupClearsUnchangedSourceJournalWhenSiblingCollisionAppears() throws Exception {
        String id = createCompletedItem("recovery-collision");
        DownloadQueueStore store = new DownloadQueueStore(target);
        String originalUri;
        String originalName;
        String targetName;
        byte[] siblingBytes = "different-collision-sibling".getBytes(StandardCharsets.UTF_8);
        try {
            DownloadRecord before = store.get(id);
            assertNotNull(before);
            originalUri = before.fileUri;
            originalName = before.fileName;
            targetName = TEST_FILE_PREFIX + "recovery-collision-" + UUID.randomUUID()
                    + extensionOf(before.fileName);
            ownedNames.add(targetName);
            assertTrue("the preflight decision must be durable before the race is injected",
                    store.beginPendingRename(id, originalUri, originalName, targetName));
        } finally {
            store.close();
        }

        Uri sibling = createDocument(targetName, siblingBytes);
        assertArrayEquals(siblingBytes, readDocument(sibling));
        manager.close();
        manager = null;

        manager = new DownloadQueueManager(target);
        DownloadQueueStore recoveredStore = new DownloadQueueStore(target);
        try {
            DownloadRecord recovered = recoveredStore.get(id);
            assertNotNull(recovered);
            assertEquals(DownloadRecord.COMPLETED, recovered.status);
            assertEquals("collision recovery must retain the original document URI",
                    originalUri, recovered.fileUri);
            assertEquals("collision recovery must retain the original display name",
                    originalName, recovered.fileName);
            assertArrayEquals(CONTENT, readDocument(Uri.parse(recovered.fileUri)));
            assertArrayEquals("recovery must not delete or alter the new sibling",
                    siblingBytes, readDocument(sibling));
            assertTrue("verified unchanged source must clear its pending journal",
                    recoveredStore.getPendingRenames().isEmpty());
        } finally {
            recoveredStore.close();
        }
        assertTrue("remove should be available after the unchanged source is reconciled",
                manager.remove(id));
    }

    @Test public void startupKeepsJournalWhenTargetSiblingCouldBeRenamedOriginal() throws Exception {
        String id = createCompletedItem("recovery-ambiguous");
        DownloadQueueStore store = new DownloadQueueStore(target);
        String originalUri;
        String originalName;
        String targetName;
        byte[] siblingBytes = new byte[CONTENT.length];
        Arrays.fill(siblingBytes, (byte) 0x5a);
        try {
            DownloadRecord before = store.get(id);
            assertNotNull(before);
            originalUri = before.fileUri;
            originalName = before.fileName;
            targetName = TEST_FILE_PREFIX + "recovery-ambiguous-" + UUID.randomUUID()
                    + extensionOf(before.fileName);
            ownedNames.add(targetName);
            assertTrue("rename intent should be durable before the ambiguous sibling appears",
                    store.beginPendingRename(id, originalUri, originalName, targetName));
        } finally {
            store.close();
        }

        Uri sibling = createDocument(targetName, siblingBytes);
        assertArrayEquals(siblingBytes, readDocument(sibling));
        manager.close();
        manager = null;

        manager = new DownloadQueueManager(target);
        DownloadQueueStore recoveredStore = new DownloadQueueStore(target);
        try {
            DownloadRecord recovered = recoveredStore.get(id);
            assertNotNull(recovered);
            assertEquals(DownloadRecord.COMPLETED, recovered.status);
            assertEquals(originalUri, recovered.fileUri);
            assertEquals(originalName, recovered.fileName);
            assertArrayEquals(CONTENT, readDocument(Uri.parse(recovered.fileUri)));
            assertArrayEquals("ambiguous recovery must not delete the target sibling",
                    siblingBytes, readDocument(sibling));
            assertEquals("matching MIME and size cannot prove whether the target is the renamed original",
                    1, recoveredStore.getPendingRenames().size());
        } finally {
            recoveredStore.close();
        }
        assertFalse("remove must stay blocked while recovery cannot prove file identity",
                manager.remove(id));
    }

    @Test public void startupClearsCaseOnlyJournalWhenOriginalIsTheTargetCandidate() throws Exception {
        String id = createCompletedItem("case-only");
        DownloadQueueStore store = new DownloadQueueStore(target);
        String originalUri;
        String originalName;
        String targetName;
        try {
            DownloadRecord before = store.get(id);
            assertNotNull(before);
            originalUri = before.fileUri;
            originalName = before.fileName;
            targetName = before.fileName.toLowerCase(java.util.Locale.ROOT);
            assertFalse("fixture case-only target must differ in case", originalName.equals(targetName));
            assertTrue("fixture case-only target must identify the same case-insensitive name",
                    originalName.equalsIgnoreCase(targetName));
            assertTrue("rename intent should be durable before simulated provider mutation",
                    store.beginPendingRename(id, originalUri, originalName, targetName));
        } finally {
            store.close();
        }

        manager.close();
        manager = null;
        manager = new DownloadQueueManager(target);
        DownloadQueueStore recoveredStore = new DownloadQueueStore(target);
        try {
            DownloadRecord recovered = recoveredStore.get(id);
            assertNotNull(recovered);
            assertEquals(DownloadRecord.COMPLETED, recovered.status);
            assertEquals(originalUri, recovered.fileUri);
            assertEquals(originalName, recovered.fileName);
            assertArrayEquals(CONTENT, readDocument(Uri.parse(recovered.fileUri)));
            assertTrue("the original document must clear its own case-insensitive target candidate",
                    recoveredStore.getPendingRenames().isEmpty());
        } finally {
            recoveredStore.close();
        }
        assertTrue("remove should be available after the original document is reconciled",
                manager.remove(id));
    }

    @Test public void startupRecoversCaseOnlyRenameWhenSafChangesDocumentId() throws Exception {
        String id = createCompletedItem("case-only-renamed");
        DownloadQueueStore store = new DownloadQueueStore(target);
        String originalUri;
        String originalName;
        String targetName;
        String intermediateName;
        try {
            DownloadRecord before = store.get(id);
            assertNotNull(before);
            originalUri = before.fileUri;
            originalName = before.fileName;
            targetName = before.fileName.toLowerCase(java.util.Locale.ROOT);
            assertFalse("fixture case-only target must differ in case", originalName.equals(targetName));
            assertTrue("fixture case-only target must identify the same case-insensitive name",
                    originalName.equalsIgnoreCase(targetName));
            ownedNames.add(targetName);
            intermediateName = TEST_FILE_PREFIX + "case-only-stage-" + UUID.randomUUID()
                    + extensionOf(before.fileName);
            ownedNames.add(intermediateName);
            assertTrue("rename intent should be durable before the SAF mutation",
                    store.beginPendingRename(id, originalUri, originalName, targetName));
        } finally {
            store.close();
        }

        manager.close();
        manager = null;
        Uri intermediate = DocumentsContract.renameDocument(target.getContentResolver(),
                Uri.parse(originalUri), intermediateName);
        assertNotNull("fixture SAF provider must move the document to a unique intermediate name", intermediate);
        Uri intermediateTreeUri = DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getDocumentId(intermediate));
        Uri renamed = DocumentsContract.renameDocument(target.getContentResolver(),
                intermediateTreeUri, targetName);
        assertNotNull("the fixture SAF provider must perform the final case-only rename", renamed);
        assertFalse("external-storage rename should produce a changed document ID",
                DocumentsContract.getDocumentId(Uri.parse(originalUri))
                        .equals(DocumentsContract.getDocumentId(renamed)));
        Uri renamedTreeUri = DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getDocumentId(renamed));
        assertEquals(targetName, metadata(renamedTreeUri).displayName);
        assertArrayEquals(CONTENT, readDocument(renamedTreeUri));

        manager = new DownloadQueueManager(target);
        DownloadQueueStore recoveredStore = new DownloadQueueStore(target);
        try {
            DownloadRecord recovered = recoveredStore.get(id);
            assertNotNull(recovered);
            assertEquals("case-only SAF rename should be committed after restart",
                    0, recoveredStore.getPendingRenames().size());
            assertEquals(DownloadRecord.COMPLETED, recovered.status);
            assertEquals(targetName, recovered.fileName);
            Uri storedUri = Uri.parse(recovered.fileUri);
            assertEquals(DocumentsContract.getDocumentId(renamed),
                    DocumentsContract.getDocumentId(storedUri));
            assertEquals(TREE_DOCUMENT_ID, DocumentsContract.getTreeDocumentId(storedUri));
            assertArrayEquals(CONTENT, readDocument(storedUri));
            assertNull("the original case-sensitive name must be absent", findByName(originalName));
        } finally {
            recoveredStore.close();
        }
        assertTrue("remove should be available after case-only recovery", manager.remove(id));
    }

    @Test public void storeRemovalCannotClaimRowWithPreparedRenameJournal() throws Exception {
        String id = createCompletedItem("remove-journal-race");
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            DownloadRecord before = store.get(id);
            assertNotNull(before);
            String targetName = TEST_FILE_PREFIX + "remove-journal-race-" + UUID.randomUUID()
                    + extensionOf(before.fileName);
            assertTrue("rename intent should be durable before removal races it",
                    store.beginPendingRename(id, before.fileUri, before.fileName, targetName));

            store.setRemovalPending(id);

            DownloadRecord after = store.get(id);
            assertNotNull(after);
            assertEquals("atomic remove claim must leave a row with pending rename in COMPLETED",
                    DownloadRecord.COMPLETED, after.status);
            assertEquals(before.fileUri, after.fileUri);
            assertEquals(before.fileName, after.fileName);
            assertEquals("the competing remove must not consume the rename journal",
                    1, store.getPendingRenames().size());
        } finally {
            store.close();
        }
    }

    @Test public void versionFourMigrationPreservesCompletedOfflineReference() throws Exception {
        String databaseName = "orbit_download_queue_v4_rename_" + UUID.randomUUID() + ".db";
        String id = "rename-migration-" + UUID.randomUUID();
        String storedUri = "content://fixture/tree/document/migration.mp4";
        SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(target.getDatabasePath(databaseName), null);
        try {
            old.execSQL("CREATE TABLE downloads ("
                    + "id TEXT PRIMARY KEY NOT NULL, profile_id TEXT NOT NULL, stable_item_id TEXT NOT NULL,"
                    + "source_id TEXT NOT NULL, title TEXT NOT NULL, file_name TEXT NOT NULL, mime_type TEXT NOT NULL,"
                    + "season_label TEXT NOT NULL DEFAULT '', episode_label TEXT NOT NULL DEFAULT '',"
                    + "known_size_bytes INTEGER NOT NULL DEFAULT -1, artwork_ref TEXT NOT NULL DEFAULT '',"
                    + "source_nonce BLOB NOT NULL, source_ciphertext BLOB NOT NULL,"
                    + "headers_nonce BLOB NOT NULL DEFAULT X'', headers_ciphertext BLOB NOT NULL DEFAULT X'',"
                    + "status TEXT NOT NULL, bytes_downloaded INTEGER NOT NULL DEFAULT 0,"
                    + "total_bytes INTEGER NOT NULL DEFAULT 0, queue_order INTEGER NOT NULL DEFAULT 0,"
                    + "run_token TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL,"
                    + "file_uri TEXT NOT NULL DEFAULT '', partial_uri TEXT NOT NULL DEFAULT '',"
                    + "partial_name TEXT NOT NULL DEFAULT '', folder_uri TEXT NOT NULL,"
                    + "error_message TEXT NOT NULL DEFAULT '')");
            old.execSQL("CREATE INDEX downloads_status_order ON downloads(status, queue_order ASC, created_at ASC, id ASC)");
            old.execSQL("CREATE INDEX downloads_profile_order ON downloads(profile_id, created_at DESC)");
            ContentValues row = new ContentValues();
            row.put("id", id);
            row.put("profile_id", TEST_PROFILE);
            row.put("stable_item_id", TEST_STABLE_PREFIX + id);
            row.put("source_id", "migration-fixture-source");
            row.put("title", "Completed rename migration fixture");
            row.put("file_name", TEST_FILE_PREFIX + "migration.mp4");
            row.put("mime_type", "video/mp4");
            row.put("source_nonce", new byte[]{1});
            row.put("source_ciphertext", new byte[]{2});
            row.put("status", DownloadRecord.COMPLETED);
            row.put("bytes_downloaded", 31L);
            row.put("total_bytes", 31L);
            row.put("queue_order", 7L);
            row.put("created_at", 1234L);
            row.put("file_uri", storedUri);
            row.put("folder_uri", "content://fixture/tree");
            old.insertOrThrow("downloads", null, row);
            old.setVersion(4);
        } finally {
            old.close();
        }

        DownloadQueueStore migrated = new DownloadQueueStore(target, databaseName);
        try {
            DownloadRecord record = migrated.get(id);
            assertNotNull(record);
            assertEquals(6, migrated.getReadableDatabase().getVersion());
            assertEquals(DownloadRecord.COMPLETED, record.status);
            assertEquals(TEST_FILE_PREFIX + "migration.mp4", record.fileName);
            assertEquals(storedUri, record.fileUri);
            assertEquals(31L, record.bytesDownloaded);
            assertTrue(migrated.getPendingRenames().isEmpty());
        } finally {
            migrated.close();
            target.deleteDatabase(databaseName);
        }
    }

    private String createCompletedItem(String suffix) throws Exception {
        String stableId = TEST_STABLE_PREFIX + suffix + "-" + UUID.randomUUID();
        String requestName = TEST_FILE_PREFIX + suffix + "-" + UUID.randomUUID() + ".mp4";
        DownloadRequest request = new DownloadRequest(TEST_PROFILE, stableId,
                "download-rename-fixture-source", "Rename fixture " + suffix,
                requestName, "video/mp4", "https://media.example.invalid/rename-fixture.mp4", true);
        String id = manager.enqueue(request, tree);
        ownedIds.add(id);

        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            DownloadRecord queued = store.get(id);
            assertNotNull(queued);
            ownedNames.add(queued.fileName);
            Uri file = createDocument(queued.fileName, CONTENT);
            store.markComplete(id, file.toString(), CONTENT.length);
            DownloadRecord completed = store.get(id);
            assertNotNull(completed);
            assertEquals(DownloadRecord.COMPLETED, completed.status);
            return id;
        } finally {
            store.close();
        }
    }

    private Uri createDocument(String name, byte[] bytes) throws Exception {
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, TREE_DOCUMENT_ID);
        Uri document = DocumentsContract.createDocument(target.getContentResolver(), root,
                "video/mp4", name);
        assertNotNull("exact SAF tree should create a synthetic document", document);
        try (OutputStream output = target.getContentResolver().openOutputStream(document, "wt")) {
            assertNotNull(output);
            output.write(bytes);
        }
        assertEquals(name, metadata(document).displayName);
        return document;
    }

    private byte[] readDocument(Uri uri) throws IOException {
        try (InputStream input = target.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertNotNull("document URI should remain readable", input);
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private DocumentMetadata metadata(Uri document) {
        try (Cursor cursor = target.getContentResolver().query(document,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            assertNotNull("SAF metadata query should succeed", cursor);
            assertTrue("SAF metadata row should exist", cursor.moveToFirst());
            return new DocumentMetadata(
                    cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)),
                    cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)));
        }
    }

    private List<FixtureDocument> listChildren() {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, TREE_DOCUMENT_ID);
        ArrayList<FixtureDocument> result = new ArrayList<>();
        try (Cursor cursor = target.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            assertNotNull("dedicated SAF fixture tree should remain readable", cursor);
            while (cursor.moveToNext()) result.add(new FixtureDocument(
                    cursor.getString(0), cursor.getString(1)));
        }
        return result;
    }

    private FixtureDocument findByName(String name) {
        for (FixtureDocument document : listChildren()) if (name.equals(document.name)) return document;
        return null;
    }

    private boolean documentIdExists(String id) {
        for (FixtureDocument document : listChildren()) if (id.equals(document.id)) return true;
        return false;
    }

    private void assertPersistedReadWriteGrant(Uri selectedTree) {
        boolean read = false;
        boolean write = false;
        for (android.content.UriPermission permission : target.getContentResolver().getPersistedUriPermissions()) {
            if (!selectedTree.equals(permission.getUri())) continue;
            read |= permission.isReadPermission();
            write |= permission.isWritePermission();
        }
        assertTrue("dedicated fixture tree must keep a persisted read grant", read);
        assertTrue("dedicated fixture tree must keep a persisted write grant", write);
    }

    private static Uri selectedFixtureTree() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String raw = context.getSharedPreferences("orbit_downloads_shell_v1", Context.MODE_PRIVATE)
                .getString("folder_uri", null);
        assertNotNull("select Download/Orbit Download Queue Fixture from Orbit > Downloads before this test", raw);
        return Uri.parse(raw);
    }

    private static boolean isFixtureTree(Uri candidate) {
        if (candidate == null || !STORAGE_AUTHORITY.equals(candidate.getAuthority())
                || !DocumentsContract.isTreeUri(candidate)) return false;
        try { return TREE_DOCUMENT_ID.equals(DocumentsContract.getTreeDocumentId(candidate)); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot) : "";
    }

    private static final class FixtureDocument {
        final String id;
        final String name;
        FixtureDocument(String id, String name) { this.id = id; this.name = name; }
    }

    private static final class DocumentMetadata {
        final String displayName;
        final String mimeType;
        final long size;
        DocumentMetadata(String displayName, String mimeType, long size) {
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.size = size;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof DocumentMetadata)) return false;
            DocumentMetadata that = (DocumentMetadata) other;
            return size == that.size && displayName.equals(that.displayName)
                    && mimeType.equals(that.mimeType);
        }
        @Override public int hashCode() { return Arrays.hashCode(new Object[]{displayName, mimeType, size}); }
    }
}
