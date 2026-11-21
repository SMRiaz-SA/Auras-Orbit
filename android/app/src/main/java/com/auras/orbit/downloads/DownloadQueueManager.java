package com.auras.orbit.downloads;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import com.auras.orbit.player.PlaybackDnsPinning;
import com.auras.orbit.providers.Cs3PlaybackHooks;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/**
 * Two-worker, user-started direct-file download queue. This is not a foreground service:
 * downloads pause if Android stops Orbit and require an explicit retry after restart.
 */
public final class DownloadQueueManager implements AutoCloseable {
    private static final String KEY_ALIAS = "auras.orbit.download-source.v1";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 45_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_URL_LENGTH = 16_384;
    private static final long MAX_DOWNLOAD_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final int MAX_ACTIVE_TRANSFERS = 2;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final String CLEAR_HTTP_UNSUPPORTED =
            "Orbit cannot use an HTTP link with a query string or provider headers.";
    private static final String VERIFIER_STATE_NONE = "NONE";
    private static final String VERIFIER_STATE_TOKEN = "TOKEN";
    private static final String VERIFIER_STATE_LEGACY = "LEGACY";
    private static final int MAX_VERIFIER_TOKEN_BYTES = 64 * 1024;

    private final Context appContext;
    private final ContentResolver resolver;
    private final DownloadQueueStore store;
    private final Dns testDnsOverride;
    private final ExecutorService admission = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "orbit-download-admission");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService transfers = Executors.newFixedThreadPool(MAX_ACTIVE_TRANSFERS, task -> {
        Thread thread = new Thread(task, "orbit-download-transfer");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final Object stateLock = new Object();
    private final StartOrderGate requestStartGate = new StartOrderGate();
    private long nextStartOrder = 1L;
    private final LinkedHashMap<String, ActiveTask> activeTasks = new LinkedHashMap<>();
    /** Live plugin owners stay in memory; only bounded encrypted recovery data reaches the queue. */
    private final LinkedHashMap<String, Cs3PlaybackHooks> providerHookSessions = new LinkedHashMap<>();
    private volatile ProviderHookRestorer providerHookRestorer;
    private volatile boolean queuePaused = true;
    private volatile boolean closed;

    public DownloadQueueManager(Context context) {
        this(context, null);
    }

    /**
     * Installs the current-profile CS3 rebinder. It must return a fresh, activated hook that
     * exactly matches the persisted token, or throw; a failed rebind always blocks the transfer.
     */
    public void setProviderHookRestorer(ProviderHookRestorer restorer) {
        providerHookRestorer = restorer;
    }

    /** Private test seam; production callers always use the public constructor above. */
    private DownloadQueueManager(Context context, Dns testDnsOverride) {
        appContext = context.getApplicationContext();
        resolver = appContext.getContentResolver();
        store = new DownloadQueueStore(appContext);
        this.testDnsOverride = testDnsOverride;
        for (DownloadQueueStore.InterruptedFile interrupted : store.recoverInterrupted()) {
            boolean cleaned = cleanupRetainedDocuments(interrupted.id, null, null,
                    "Orbit could not remove the interrupted download file. Restore folder access, then remove or retry this item.",
                    !isBlank(interrupted.partialName));
            if (DownloadRecord.REMOVING.equals(interrupted.status)) {
                if (cleaned) store.delete(interrupted.id);
            } else if (cleaned) {
                store.clearPartialAfterCleanup(interrupted.id, DownloadRecord.PAUSED,
                        "Download paused when Orbit closed. Retry to start again.");
            }
        }
        for (DownloadQueueStore.PendingRename pendingRename : store.getPendingRenames()) {
            reconcilePendingRename(pendingRename);
        }
    }

    /** Adds a supported direct file to the queue. It does not begin network activity. */
    public synchronized String enqueue(DownloadRequest request, Uri destinationTree) throws IOException {
        ensureOpen();
        validateRequest(request);
        validateDestination(destinationTree);
        DownloadRecord existing = store.getByIdentity(request.profileId.trim(), clean(request.sourceId),
                request.stableItemId.trim());
        if (existing != null) return existing.id;

        String id = UUID.randomUUID().toString();
        String filename = uniqueFileName(sanitizeFileName(request.fileName), id);
        String mime = safeMimeType(request.mimeType);
        EncryptedValue source = encryptSourceUrl(request.sourceUrl.trim());
        EncryptedValue headers = null;
        EncryptedValue verifierToken = null;
        String verifierState = VERIFIER_STATE_NONE;
        try {
            headers = encryptHeaders(request.headers, id);
            if (request.providerHookToken != null) {
                verifierToken = encryptVerifierToken(request.providerHookToken, id);
                verifierState = VERIFIER_STATE_TOKEN;
            }
            store.insert(request, id, filename, mime, source.nonce, source.ciphertext,
                    headers.nonce, headers.ciphertext, verifierState,
                    verifierToken == null ? new byte[0] : verifierToken.nonce,
                    verifierToken == null ? new byte[0] : verifierToken.ciphertext,
                    destinationTree.toString(), System.currentTimeMillis());
        } finally {
            java.util.Arrays.fill(source.nonce, (byte) 0);
            java.util.Arrays.fill(source.ciphertext, (byte) 0);
            if (headers != null) {
                java.util.Arrays.fill(headers.nonce, (byte) 0);
                java.util.Arrays.fill(headers.ciphertext, (byte) 0);
            }
            if (verifierToken != null) {
                java.util.Arrays.fill(verifierToken.nonce, (byte) 0);
                java.util.Arrays.fill(verifierToken.ciphertext, (byte) 0);
            }
        }
        notifyChanged();
        return id;
    }

    /** Enqueues a source-confirmed file and starts the queue from this explicit user action. */
    public String enqueueAndStart(DownloadRequest request, Uri destinationTree) throws IOException {
        String id = enqueue(request, destinationTree);
        if (request.providerHooks != null) {
            Cs3PlaybackHooks previous = null;
            Cs3PlaybackHooks hookToAttach = null;
            ActiveTask taskToAttach = null;
            boolean retained = false;
            boolean alreadyOwnedByQueue = false;
            PersistedVerifierMatch verifierMatch = inspectPersistedVerifier(id, request);
            boolean persistedVerifierMatches = verifierMatch.matches;
            boolean cancellationOnlyMatches = VERIFIER_STATE_NONE.equals(verifierMatch.state)
                    && verifierMatch.sourceMatches
                    && request.providerHooks.isInterceptorOnlyOwnerForProfile(request.profileId.trim());
            boolean canRetain = persistedVerifierMatches || cancellationOnlyMatches;
            synchronized (stateLock) {
                DownloadRecord item = store.get(id);
                ActiveTask active = activeTasks.get(id);
                Cs3PlaybackHooks current = providerHookSessions.get(id);
                boolean rowCanRetain = item != null
                        && !DownloadRecord.COMPLETED.equals(item.status)
                        && !DownloadRecord.REMOVING.equals(item.status);
                if (canRetain && !closed && rowCanRetain) {
                    if (current == null || current == request.providerHooks || current.isClosed()) {
                        // Replace only a closed owner. A live owner may already be carrying the
                        // same row's revocation callback and must not be displaced by a duplicate.
                        if (current == null || current != request.providerHooks) {
                            previous = current;
                            providerHookSessions.put(id, request.providerHooks);
                        }
                        retained = true;
                        if (active != null && cancellationOnlyMatches
                                && (active.providerHooks == null || active.providerHooks == current)
                                && !active.pause && !active.cancel && !active.remove && !queuePaused) {
                            active.providerHooks = request.providerHooks;
                            taskToAttach = active;
                            hookToAttach = request.providerHooks;
                        }
                    } else {
                        // Keep the already-live owner. It is the exact row's current cancellation
                        // owner; a duplicate request must not replace it.
                        retained = false;
                        if (active != null && cancellationOnlyMatches
                                && active.providerHooks == null && !active.pause && !active.cancel
                                && !active.remove && !queuePaused) {
                            // This mapped session was accepted against the same persisted source
                            // identity earlier; reuse it instead of displacing a live owner.
                            active.providerHooks = current;
                            taskToAttach = active;
                            hookToAttach = current;
                        }
                    }
                    alreadyOwnedByQueue = current == request.providerHooks
                            || (active != null && active.providerHooks == request.providerHooks);
                } else {
                    alreadyOwnedByQueue = (active != null && active.providerHooks == request.providerHooks)
                            || current == request.providerHooks;
                }
            }
            // `previous` is known closed. Preserve its listener: close() may have marked the
            // owner closed but not yet delivered the callback that stops its active transfer.
            if (previous != null && previous != request.providerHooks) previous.close();
            if ((!retained && !alreadyOwnedByQueue)
                    && request.providerHooks != hookToAttach) {
                closeDiscardedHook(request.providerHooks);
            }
            if (taskToAttach != null && hookToAttach != null) {
                hookToAttach.setRevocationListener(taskToAttach::onProviderHookRevoked);
                boolean stillAttached;
                synchronized (stateLock) {
                    stillAttached = activeTasks.get(id) == taskToAttach
                            && taskToAttach.providerHooks == hookToAttach
                            && !taskToAttach.pause && !taskToAttach.cancel && !taskToAttach.remove
                            && !queuePaused && !closed;
                }
                if (!stillAttached && !hookToAttach.isClosed()) {
                    hookToAttach.setRevocationListener(null);
                }
            }
        }
        startQueue();
        return id;
    }

    /** Retains only a live hook whose stable identity and source URL match the persisted token. */
    private PersistedVerifierMatch inspectPersistedVerifier(String id, DownloadRequest request) {
        DownloadQueueStore.PendingSource saved = store.getPendingSource(id);
        if (saved == null) return new PersistedVerifierMatch(null, false, false);
        String verifierState = saved.verifierState;
        try {
            DownloadProviderHookToken requested = request.providerHookToken;
            String savedUrl = decryptSourceUrl(saved.nonce, saved.ciphertext);
            boolean sourceMatches = !isBlank(request.sourceUrl) && !isBlank(request.sourceId)
                    && saved.sourceId.equals(request.sourceId.trim())
                    && savedUrl.equals(request.sourceUrl.trim());
            if (!VERIFIER_STATE_TOKEN.equals(verifierState)) {
                boolean unexpectedNoneToken = VERIFIER_STATE_NONE.equals(verifierState)
                        && ((saved.verifierNonce != null && saved.verifierNonce.length != 0)
                        || (saved.encryptedVerifierToken != null && saved.encryptedVerifierToken.length != 0));
                return new PersistedVerifierMatch(verifierState, false,
                        sourceMatches && !unexpectedNoneToken);
            }
            if (requested == null || !sourceMatches) {
                return new PersistedVerifierMatch(verifierState, false, sourceMatches);
            }
            DownloadProviderHookToken persisted = decryptVerifierToken(saved, id);
            boolean matches = persisted.sourceId.equals(saved.sourceId)
                    && persisted.sourceUrlSha256.equals(sha256Hex(savedUrl))
                    && persisted.sourceUrlSha256.equals(sha256Hex(request.sourceUrl.trim()))
                    && sameVerifierToken(persisted, requested);
            return new PersistedVerifierMatch(verifierState, matches, sourceMatches);
        } catch (IOException | RuntimeException failure) {
            return new PersistedVerifierMatch(verifierState, false, false);
        } finally {
            clearSavedQueueCiphertext(saved);
        }
    }

    private static void closeDiscardedHook(Cs3PlaybackHooks hooks) {
        if (hooks == null) return;
        hooks.setRevocationListener(null);
        hooks.close();
    }

    /** Starts queued work only after an explicit user action. */
    public void startQueue() {
        synchronized (stateLock) {
            if (closed) return;
            queuePaused = false;
        }
        scheduleDrain();
    }

    public boolean isQueuePaused() { return queuePaused; }

    /** Stops starting new items and discards the in-flight partial file. */
    public void pauseQueue() {
        ArrayList<ActiveTask> stop = new ArrayList<>();
        synchronized (stateLock) {
            queuePaused = true;
            for (ActiveTask running : activeTasks.values()) {
                running.pause = true;
                stop.add(running);
            }
        }
        for (ActiveTask task : stop) task.stopConnection();
        notifyChanged();
    }

    /** Restarts paused records from byte zero, then resumes the ordered queue. */
    public void resumeQueue() {
        synchronized (stateLock) {
            if (closed) return;
            for (ActiveTask running : activeTasks.values()) {
                if (running.pause) running.resumeAfterPause = true;
            }
            for (String id : store.listIds(DownloadRecord.PAUSED)) {
                if (!activeTasks.containsKey(id)) store.resetForRetry(id);
            }
            queuePaused = false;
        }
        scheduleDrain();
        notifyChanged();
    }

    public void pause(String id) {
        if (isBlank(id)) return;
        ActiveTask stop = null;
        synchronized (stateLock) {
            ActiveTask running = activeTasks.get(id);
            if (running != null) {
                if (running.remove || DownloadRecord.REMOVING.equals(store.get(id).status)) return;
                running.pause = true;
                stop = running;
            } else {
                DownloadRecord item = store.get(id);
                if (item != null && DownloadRecord.QUEUED.equals(item.status)) {
                    store.setStatus(id, DownloadRecord.PAUSED, "Paused by you.");
                }
            }
        }
        if (stop != null) stop.stopConnection();
        notifyChanged();
    }

    public void cancel(String id) {
        if (isBlank(id)) return;
        ActiveTask stop = null;
        synchronized (stateLock) {
            ActiveTask running = activeTasks.get(id);
            if (running != null) {
                if (running.remove || DownloadRecord.REMOVING.equals(store.get(id).status)) return;
                running.cancel = true;
                stop = running;
            } else {
                DownloadRecord item = store.get(id);
                if (item != null && !DownloadRecord.COMPLETED.equals(item.status)
                        && !DownloadRecord.REMOVING.equals(item.status)
                        && !(DownloadRecord.FAILED.equals(item.status) && hasRetainedDocuments(id))) {
                    store.setStatus(id, DownloadRecord.CANCELLED, "Download canceled.");
                }
            }
        }
        if (stop != null) stop.stopConnection();
        notifyChanged();
    }

    public void retry(String id) {
        if (isBlank(id)) return;
        boolean cleanFirst = false;
        synchronized (stateLock) {
            DownloadRecord item = store.get(id);
            if (activeTasks.containsKey(id) || item == null || DownloadRecord.COMPLETED.equals(item.status)
                    || DownloadRecord.DOWNLOADING.equals(item.status)
                    || DownloadRecord.REMOVING.equals(item.status)) return;
            if (DownloadRecord.FAILED.equals(item.status) && hasRetainedDocuments(id)) {
                if (!store.setRemovalPending(id)) return;
                queuePaused = false;
                cleanFirst = true;
            } else {
                store.resetForRetry(id);
                queuePaused = false;
            }
        }
        if (cleanFirst) {
            if (!cleanupRetainedDocuments(id, null, null,
                    "Orbit could not remove the retained file before retrying. Restore folder access, then retry.")) {
                notifyChanged();
                return;
            }
            boolean retryQueued = false;
            synchronized (stateLock) {
                DownloadRecord item = store.get(id);
                if (item != null && DownloadRecord.REMOVING.equals(item.status)
                        && !activeTasks.containsKey(id)) {
                    store.resetForRetry(id);
                    retryQueued = true;
                }
            }
            if (!retryQueued) {
                notifyChanged();
                return;
            }
        }
        scheduleDrain();
        notifyChanged();
    }

    /** Removes a queue entry and deletes its associated saved file, if one exists. */
    public boolean remove(String id) {
        if (isBlank(id)) return false;
        ActiveTask removingActive = null;
        synchronized (stateLock) {
            ActiveTask running = activeTasks.get(id);
            if (running != null) {
                if (running.remove) return false;
                running.remove = true;
                running.cancel = true;
                if (!store.setTaskRemovalPending(id, running.runToken)) {
                    running.remove = false;
                    running.cancel = false;
                    return false;
                }
                removingActive = running;
            } else {
                DownloadRecord item = store.get(id);
                if (item == null || DownloadRecord.REMOVING.equals(item.status)
                        || findPendingRename(id) != null) return false;
                if (!store.setRemovalPending(id)) return false;
            }
        }
        closeProviderHooksFor(id);
        if (removingActive != null) {
            removingActive.stopConnection();
            notifyChanged();
            return true;
        }
        if (cleanupRetainedDocuments(id, null, null,
                "Orbit could not remove the file from the selected folder. Restore folder access and try again.")) {
            if (!store.deleteIfStatus(id, DownloadRecord.REMOVING)) {
                notifyChanged();
                return false;
            }
            notifyChanged();
            return true;
        }
        notifyChanged();
        return false;
    }

    public List<DownloadRecord> getItems() {
        HashMap<String, ActiveTask.Estimate> estimates = new HashMap<>();
        synchronized (stateLock) {
            for (ActiveTask task : activeTasks.values()) estimates.put(task.id, task.estimate());
        }
        ArrayList<DownloadRecord> result = new ArrayList<>();
        for (DownloadRecord item : store.listAll()) {
            ActiveTask.Estimate estimate = estimates.get(item.id);
            result.add(estimate == null || !DownloadRecord.DOWNLOADING.equals(item.status)
                    ? item : item.withTransferEstimate(estimate.bytesPerSecond, estimate.etaSeconds));
        }
        return java.util.Collections.unmodifiableList(result);
    }
    public List<DownloadRecord> getOfflineItems() { return store.listOffline(); }

    /** Returns only a completed local-file URI, suitable for the Orbit player handoff. */
    public Uri getOfflineUri(String id) {
        synchronized (stateLock) {
            DownloadRecord item = store.get(id);
            if (item == null || !item.isComplete() || isBlank(item.fileUri)) return null;
            Uri uri = Uri.parse(item.fileUri);
            try (android.os.ParcelFileDescriptor ignored = resolver.openFileDescriptor(uri, "r")) {
                return uri;
            } catch (IOException | SecurityException error) {
                return null;
            }
        }
    }

    /**
     * Renames only a completed saved file. The requested value is a base name; the current file
     * extension is retained. The caller should invoke this synchronous SAF operation off the UI
     * thread. The queue state lock covers validation, provider mutation, and journal commit so a
     * remove or retry cannot race this row.
     */
    public void renameCompletedFile(String id, String requestedBaseName) throws IOException {
        boolean changed = false;
        boolean succeeded = false;
        IOException failure = null;
        synchronized (stateLock) {
            ensureOpen();
            if (isBlank(id)) throw new IOException("Choose a saved file to rename.");
            if (activeTasks.containsKey(id)) {
                throw new IOException("A file can be renamed after its download has completed.");
            }
            DownloadRecord item = store.get(id);
            if (item == null || !DownloadRecord.COMPLETED.equals(item.status)) {
                throw new IOException("Only completed saved files can be renamed.");
            }
            if (findPendingRename(id) != null) {
                throw new IOException("Orbit is still reconciling this file name. Try again shortly.");
            }

            String targetName = completedRenameFileName(requestedBaseName, item.fileName);
            if (targetName.equals(item.fileName)) return;
            Uri tree = parseTree(item.folderUri);
            validateDestination(tree);
            TreeChildren beforeChildren = listTreeChildren(tree);
            if (!beforeChildren.succeeded) {
                throw new IOException("Orbit could not verify the file in its selected folder.");
            }
            Uri originalUri = canonicalChildUri(Uri.parse(item.fileUri), tree, beforeChildren);
            if (originalUri == null) {
                throw new IOException("The saved file is not inside its selected folder.");
            }
            String originalDocumentId = documentId(originalUri);
            TreeChild originalChild = beforeChildren.byId.get(originalDocumentId);
            if (originalChild == null || !item.fileName.equals(originalChild.name)) {
                throw new IOException("Orbit could not verify the saved file name in its selected folder.");
            }
            if (containsName(beforeChildren, targetName, originalDocumentId)) {
                throw new IOException("A file with that name already exists in the selected folder.");
            }
            DocumentMetadata originalMetadata = queryDocumentMetadata(originalUri);
            if (!item.fileName.equals(originalMetadata.displayName)
                    || (originalMetadata.size >= 0L && item.bytesDownloaded >= 0L
                    && originalMetadata.size != item.bytesDownloaded)) {
                throw new IOException("Orbit could not verify the saved file before renaming it.");
            }
            if (!store.beginPendingRename(id, item.fileUri, item.fileName, targetName)) {
                throw new IOException("Orbit could not prepare this saved file for renaming.");
            }
            DownloadQueueStore.PendingRename pending = findPendingRename(id);
            if (pending == null) {
                throw new IOException("Orbit could not prepare this saved file for renaming.");
            }

            Uri providerResult = null;
            try {
                providerResult = DocumentsContract.renameDocument(resolver, originalUri, targetName);
                if (providerResult == null) {
                    throw new IOException("The selected folder could not rename this file.");
                }
                Uri renamedUri = verifyRenamedDocument(providerResult, tree, targetName, originalMetadata);
                if (renamedUri == null) {
                    if (rollbackProviderRename(pending, providerResult, tree, originalMetadata)) {
                        changed = true;
                        failure = new IOException("The selected folder did not keep the requested file name.");
                    } else {
                        RenameReconciliation reconciliation = reconcilePendingRename(pending);
                        changed = reconciliation.changed;
                        if (reconciliation.result == RenameReconciliation.TARGET_COMMITTED) {
                            succeeded = true;
                        } else if (reconciliation.result == RenameReconciliation.ORIGINAL_RETAINED) {
                            failure = new IOException("The selected folder did not keep the requested file name.");
                        } else {
                            failure = new IOException("Orbit could not confirm the renamed file. Restore access to its selected folder to reconcile it.");
                        }
                    }
                } else if (store.finishPendingRename(id, pending.originalUri, pending.originalName,
                        pending.targetName, renamedUri.toString(), targetName)) {
                    changed = true;
                    succeeded = true;
                } else {
                    RenameReconciliation reconciliation = reconcilePendingRename(pending);
                    changed = reconciliation.changed;
                    succeeded = reconciliation.result == RenameReconciliation.TARGET_COMMITTED;
                    if (!succeeded) {
                        failure = new IOException("Orbit could not save the renamed file reference. Restore folder access to reconcile it.");
                    }
                }
            } catch (Exception providerFailure) {
                RenameReconciliation reconciliation = reconcilePendingRename(pending);
                changed |= reconciliation.changed;
                if (reconciliation.result == RenameReconciliation.TARGET_COMMITTED) {
                    succeeded = true;
                } else if (reconciliation.result == RenameReconciliation.ORIGINAL_RETAINED) {
                    failure = new IOException("The selected folder could not rename this file.");
                } else if (providerResult != null
                        && rollbackProviderRename(pending, providerResult, tree, originalMetadata)) {
                    changed = true;
                    failure = new IOException("The selected folder could not rename this file.");
                } else {
                    failure = new IOException("Orbit could not confirm the file name. Restore access to its selected folder to reconcile it.");
                }
            }
        }
        if (changed) notifyChanged();
        if (!succeeded && failure != null) throw failure;
    }

    public void addListener(Listener listener) {
        if (listener == null) return;
        listeners.addIfAbsent(listener);
        main.post(() -> {
            if (listeners.contains(listener)) listener.onQueueChanged(getItems());
        });
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }

    private void scheduleDrain() {
        if (closed || queuePaused || !drainScheduled.compareAndSet(false, true)) return;
        try {
            admission.execute(() -> {
                try { admitQueuedItems(); }
                catch (RuntimeException ignored) { }
                finally {
                    drainScheduled.set(false);
                    if (hasAdmissionCapacity()) scheduleDrain();
                }
            });
        } catch (RejectedExecutionException ignored) {
            drainScheduled.set(false);
        }
    }

    private boolean hasAdmissionCapacity() {
        synchronized (stateLock) {
            return !closed && !queuePaused && activeTasks.size() < MAX_ACTIVE_TRANSFERS
                    && !store.listIds(DownloadRecord.QUEUED).isEmpty();
        }
    }

    private void admitQueuedItems() {
        ArrayList<ActiveTask> admitted = new ArrayList<>();
        synchronized (stateLock) {
            while (!closed && !queuePaused && activeTasks.size() < MAX_ACTIVE_TRANSFERS) {
                List<String> ids = store.listIds(DownloadRecord.QUEUED);
                if (ids.isEmpty()) break;
                String id = ids.get(0);
                String token = UUID.randomUUID().toString();
                ActiveTask task = new ActiveTask(id, token, nextStartOrder++, requestStartGate,
                        providerHookSessions.get(id));
                if (!store.claimQueued(id, token)) break;
                activeTasks.put(id, task);
                admitted.add(task);
            }
        }
        for (ActiveTask task : admitted) {
            notifyChanged();
            try { transfers.execute(() -> runTransfer(task)); }
            catch (RejectedExecutionException rejected) {
                task.pause = true;
                task.advanceStartOrder();
                finishPartialStatus(task, DownloadRecord.PAUSED,
                        "Download paused before it could start. Retry to start again.");
                synchronized (stateLock) {
                    if (activeTasks.get(task.id) == task) activeTasks.remove(task.id);
                }
            }
        }
    }

    private void runTransfer(ActiveTask task) {
        String id = task.id;
        Uri partial = null;
        Uri renamed = null;
        try {
            prepareTaskHookRevocation(task);
            checkCanceled(task);
            restoreVerifierContext(task);
            checkCanceled(task);
            if (task.providerHooks != null && task.providerHooks.hasVerifierData()) {
                task.providerHooks.startVerifierJob();
            }
            partial = performDownload(id, task);
            if (task.remove) {
                removeItemDocuments(task, partial);
            } else if (task.cancel) {
                finishPartialStatus(task, DownloadRecord.CANCELLED, "Download canceled.");
            } else if (task.pause || queuePaused || closed) {
                if (task.resumeAfterPause && !queuePaused && !closed) retryAfterPartialCleanup(task);
                else finishPartialStatus(task, DownloadRecord.PAUSED, "Paused. Retry to start again.");
            } else {
                renamed = renameDocument(partial, itemFileName(id));
                if (renamed == null) throw new IOException("The selected folder could not finalize completed files.");
                boolean completionCommitted = false;
                synchronized (stateLock) {
                    if (activeTasks.get(id) == task && !task.remove && !task.cancel && !task.pause
                            && !queuePaused && !closed) {
                        DownloadRecord completed = store.get(id);
                        long total = completed == null ? 0L : completed.bytesDownloaded;
                        completionCommitted = store.markComplete(id, task.runToken,
                                renamed.toString(), total);
                    }
                }
                if (!completionCommitted) finishRenamedAfterAction(task, partial, renamed);
            }
        } catch (InterruptedIOException canceled) {
            if (renamed != null) finishRenamedAfterError(task, partial, renamed, canceled);
            else if (task.remove) removeItemDocuments(task, partial);
            else if (task.cancel) finishPartialStatus(task, DownloadRecord.CANCELLED, "Download canceled.");
            else if (task.resumeAfterPause && !queuePaused && !closed) retryAfterPartialCleanup(task);
            else finishPartialStatus(task, DownloadRecord.PAUSED, "Paused. Retry to start again.");
        } catch (Exception error) {
            if (renamed != null) finishRenamedAfterError(task, partial, renamed, error);
            else if (task.remove) removeItemDocuments(task, partial);
            else if (task.cancel) finishPartialStatus(task, DownloadRecord.CANCELLED, "Download canceled.");
            else if (task.pause || queuePaused || closed) {
                if (task.resumeAfterPause && !queuePaused && !closed) retryAfterPartialCleanup(task);
                else finishPartialStatus(task, DownloadRecord.PAUSED, "Paused. Retry to start again.");
            } else finishPartialStatus(task, DownloadRecord.FAILED, safeErrorMessage(error));
        } finally {
            if (task.providerHooks != null) {
                task.providerHooks.cancelVerifierJob();
                task.providerHooks.setRevocationListener(null);
            }
            task.advanceStartOrder();
            notifyChanged();
            synchronized (stateLock) {
                if (activeTasks.get(id) == task) activeTasks.remove(id);
            }
            DownloadRecord finalRecord = store.get(id);
            if (finalRecord == null || DownloadRecord.COMPLETED.equals(finalRecord.status)) {
                closeProviderHooksFor(id);
            }
            scheduleDrain();
        }
    }

    private void prepareTaskHookRevocation(ActiveTask task) {
        Cs3PlaybackHooks hooks = task.providerHooks;
        if (hooks == null) return;
        if (hooks.isActiveOwner()) {
            hooks.setRevocationListener(task::onProviderHookRevoked);
            return;
        }
        boolean tokenlessOwner = !hooks.hasVerifierData();
        synchronized (stateLock) {
            if (providerHookSessions.get(task.id) == hooks) providerHookSessions.remove(task.id);
            if (task.providerHooks == hooks) task.providerHooks = null;
        }
        hooks.close();
        if (tokenlessOwner) task.onProviderHookRevoked();
    }

    /** Rebinds verifier ownership on the transfer worker before performDownload can open a socket. */
    private void restoreVerifierContext(ActiveTask task) throws IOException {
        DownloadQueueStore.PendingSource saved = store.getPendingSource(task.id);
        if (saved == null) throw new ProtectedQueueDataException();
        if (VERIFIER_STATE_NONE.equals(saved.verifierState)) {
            boolean unexpectedToken = (saved.verifierNonce != null && saved.verifierNonce.length != 0)
                    || (saved.encryptedVerifierToken != null && saved.encryptedVerifierToken.length != 0);
            Cs3PlaybackHooks detachedTaskHook = null;
            Cs3PlaybackHooks detachedSessionHook = null;
            Cs3PlaybackHooks listenerHook = null;
            boolean cancellationOnlyOwner = false;
            synchronized (stateLock) {
                Cs3PlaybackHooks attached = task.providerHooks;
                Cs3PlaybackHooks mapped = providerHookSessions.get(task.id);
                boolean activeTask = activeTasks.get(task.id) == task;
                boolean mappedOwnerValid = !unexpectedToken && activeTask && mapped != null
                        && !mapped.hasVerifierData() && !mapped.isClosed();
                if (mappedOwnerValid) {
                    // Reconcile task and session under the same lock used by enqueueAndStart.
                    // An owner attached after transfer start is therefore not lost by this restore.
                    if (attached != mapped) {
                        task.providerHooks = mapped;
                        listenerHook = mapped;
                        detachedTaskHook = attached;
                    }
                    cancellationOnlyOwner = true;
                } else if (attached != null) {
                    task.providerHooks = null;
                    if (attached != mapped || activeTask || unexpectedToken) {
                        detachedTaskHook = attached;
                    }
                }
                if (unexpectedToken || (activeTask && mapped != null && !mappedOwnerValid)) {
                    if (providerHookSessions.get(task.id) == mapped) {
                        providerHookSessions.remove(task.id);
                        detachedSessionHook = mapped;
                    }
                }
            }
            clearSavedQueueCiphertext(saved);
            // Do not detach listeners from active-task owners before closing them. A close already
            // in progress may still be delivering the callback that cancels the old transfer.
            if (detachedTaskHook != null) detachedTaskHook.close();
            if (detachedSessionHook != null && detachedSessionHook != detachedTaskHook) {
                detachedSessionHook.close();
            }
            if (unexpectedToken) {
                throw new ProtectedQueueDataException();
            }
            // Tokenless rows never run a verifier. Only a source-matched interceptor owner already
            // in the session map may remain attached to carry the transfer revocation listener.
            if (listenerHook != null) {
                listenerHook.setRevocationListener(task::onProviderHookRevoked);
                boolean stillAttached;
                synchronized (stateLock) {
                    stillAttached = activeTasks.get(task.id) == task
                            && task.providerHooks == listenerHook
                            && providerHookSessions.get(task.id) == listenerHook;
                }
                if (!stillAttached && !listenerHook.isClosed()) {
                    listenerHook.setRevocationListener(null);
                }
            }
            return;
        }
        if (VERIFIER_STATE_LEGACY.equals(saved.verifierState)) {
            clearSavedQueueCiphertext(saved);
            discardTaskHooks(task);
            throw new LegacyProviderVerifierRecoveryException();
        }
        if (!VERIFIER_STATE_TOKEN.equals(saved.verifierState)) {
            clearSavedQueueCiphertext(saved);
            discardTaskHooks(task);
            throw new ProtectedQueueDataException();
        }

        Cs3PlaybackHooks restored = null;
        try {
            DownloadProviderHookToken token = decryptVerifierToken(saved, task.id);
            String savedUrl = decryptSourceUrl(saved.nonce, saved.ciphertext);
            if (!token.sourceId.equals(saved.sourceId)
                    || !token.sourceUrlSha256.equals(sha256Hex(savedUrl))) {
                throw new ProviderVerifierRecoveryException();
            }
            ProviderHookRestorer restorer = providerHookRestorer;
            if (restorer == null) throw new ProviderVerifierRecoveryException();
            restored = restorer.restore(token);
            if (restored == null || !restored.isActiveOwner()) {
                throw new ProviderVerifierRecoveryException();
            }
            DownloadProviderHookToken rebound = restored.toDownloadProviderHookToken(
                    token.sourceId, savedUrl);
            if (rebound == null || !sameVerifierToken(token, rebound)) {
                throw new ProviderVerifierRecoveryException();
            }
            restored.setRevocationListener(task::onProviderHookRevoked);

            Cs3PlaybackHooks previous;
            synchronized (stateLock) {
                if (closed || queuePaused || activeTasks.get(task.id) != task
                        || task.pause || task.cancel || task.remove) {
                    throw new InterruptedIOException("Download stopped.");
                }
                if (restored == task.providerHooks || restored == providerHookSessions.get(task.id)) {
                    throw new ProviderVerifierRecoveryException();
                }
                previous = providerHookSessions.put(task.id, restored);
                task.providerHooks = restored;
                if (previous != null && previous != restored) previous.setRevocationListener(null);
            }
            if (previous != null && previous != restored) previous.close();
            restored = null;
        } catch (ProviderVerifierRecoveryException | ProtectedQueueDataException error) {
            discardTaskHooks(task);
            throw error;
        } catch (InterruptedIOException stopped) {
            discardTaskHooks(task);
            throw stopped;
        } catch (Exception failure) {
            discardTaskHooks(task);
            throw new ProviderVerifierRecoveryException(failure);
        } finally {
            if (restored != null) restored.close();
            clearSavedQueueCiphertext(saved);
        }
    }

    private static void clearSavedQueueCiphertext(DownloadQueueStore.PendingSource saved) {
        if (saved.nonce != null) java.util.Arrays.fill(saved.nonce, (byte) 0);
        if (saved.ciphertext != null) java.util.Arrays.fill(saved.ciphertext, (byte) 0);
        if (saved.headersNonce != null) java.util.Arrays.fill(saved.headersNonce, (byte) 0);
        if (saved.encryptedHeaders != null) java.util.Arrays.fill(saved.encryptedHeaders, (byte) 0);
        if (saved.verifierNonce != null) java.util.Arrays.fill(saved.verifierNonce, (byte) 0);
        if (saved.encryptedVerifierToken != null) java.util.Arrays.fill(saved.encryptedVerifierToken, (byte) 0);
    }

    private void discardTaskHooks(ActiveTask task) {
        Cs3PlaybackHooks previous;
        Cs3PlaybackHooks attached;
        synchronized (stateLock) {
            previous = providerHookSessions.remove(task.id);
            attached = task.providerHooks;
            task.providerHooks = null;
        }
        closeDiscardedHook(previous);
        if (attached != previous) closeDiscardedHook(attached);
    }

    private static boolean sameVerifierToken(DownloadProviderHookToken first,
            DownloadProviderHookToken second) {
        return first.version == second.version
                && first.registrationIndex == second.registrationIndex
                && first.profileId.equals(second.profileId)
                && first.pluginId.equals(second.pluginId)
                && first.providerId.equals(second.providerId)
                && first.apiClassName.equals(second.apiClassName)
                // apiName can be the installed-plugin fallback or cached API name.
                && first.pluginArchiveSha256.equals(second.pluginArchiveSha256)
                && first.sourceId.equals(second.sourceId)
                && first.sourceUrlSha256.equals(second.sourceUrlSha256)
                && first.extractorData.equals(second.extractorData);
    }

    private static String sha256Hex(String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte item : digest) encoded.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return encoded.toString();
        } catch (GeneralSecurityException failure) {
            throw new IOException("Orbit could not verify the saved provider context.", failure);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
            if (digest != null) java.util.Arrays.fill(digest, (byte) 0);
        }
    }

    private Uri performDownload(String id, ActiveTask task) throws Exception {
        checkCanceled(task);
        DownloadQueueStore.PendingSource source = store.getPendingSource(id);
        if (source == null) throw new ProtectedQueueDataException();
        String sourceUrl = null;
        Map<String, String> requestHeaders = null;
        OkHttpClient client = null;
        Response response = null;
        Uri partial = null;
        try {
            sourceUrl = decryptSourceUrl(source.nonce, source.ciphertext);
            requestHeaders = decryptHeaders(source.headersNonce, source.encryptedHeaders, id);
            if (!DownloadRequest.supportsHeaders(sourceUrl, requestHeaders)) {
                throw new IOException(CLEAR_HTTP_UNSUPPORTED);
            }
            Dns pinnedDns = testDnsOverride == null
                    ? PlaybackDnsPinning.newDns(false) : testDnsOverride;
            client = newDownloadClient(pinnedDns);
            response = openResponseFollowingRedirects(sourceUrl, requestHeaders, task, client,
                    pinnedDns);
            if (response.code() != 200) {
                throw new IOException(response.code() >= 400 ? "The media server rejected this download." : "The server did not return a complete file.");
            }
            String contentEncoding = response.header("Content-Encoding");
            if (!isBlank(contentEncoding) && !"identity".equalsIgnoreCase(contentEncoding.trim())) {
                throw new IOException("The media source returned an unsupported encoded file.");
            }
            long total = getContentLength(response);
            DownloadRecord queuedItem = store.get(id);
            if (total <= 0L && queuedItem != null && queuedItem.knownSizeBytes > 0L) {
                total = queuedItem.knownSizeBytes;
            }
            if (total > MAX_DOWNLOAD_BYTES) throw new IOException("This file exceeds Orbit's 8 GB download limit.");

            Uri folder = Uri.parse(source.folderUri);
            String parentId = DocumentsContract.getTreeDocumentId(folder);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(folder, parentId);
            String partialName = ".orbit-part-" + id;
            checkCanceled(task);
            if (!store.setPartialReservation(id, task.runToken, partialName)) {
                throw new InterruptedIOException("Download stopped.");
            }
            partial = DocumentsContract.createDocument(resolver, parent, source.mimeType, partialName);
            if (partial == null) throw new IOException("The selected folder could not create a download file.");
            if (!store.setPartialUri(id, task.runToken, partial.toString())) {
                throw new InterruptedIOException("Download stopped.");
            }

            if (response.body() == null) throw new IOException("The media source returned an empty response.");
            try (InputStream input = response.body().byteStream();
                 OutputStream output = resolver.openOutputStream(partial, "wt")) {
                if (output == null) throw new IOException("The selected folder could not write a download file.");
                byte[] buffer = new byte[BUFFER_SIZE];
                long written = 0L;
                long lastUpdate = 0L;
                try {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (count == 0) throw new IOException("Input stream made no progress.");
                        checkCanceled(task);
                        if (count == 0) throw new IOException("Input stream made no progress.");
                        if (written > MAX_DOWNLOAD_BYTES - count) {
                            throw new IOException("This file exceeds Orbit's 8 GB download limit.");
                        }
                        output.write(buffer, 0, count);
                        written += count;
                        long now = System.currentTimeMillis();
                        task.recordProgress(written, total, now);
                        if (now - lastUpdate >= 300L) {
                            if (!store.updateProgress(id, task.runToken, written, total)) {
                                throw new InterruptedIOException("Download stopped.");
                            }
                            lastUpdate = now;
                            notifyChanged();
                        }
                    }
                    output.flush();
                } finally {
                    java.util.Arrays.fill(buffer, (byte) 0);
                }
                checkCanceled(task);
                task.recordProgress(written, written, System.currentTimeMillis());
                if (!store.updateProgress(id, task.runToken, written, written)) {
                    throw new InterruptedIOException("Download stopped.");
                }
            }
            return partial;
        } catch (Exception error) {
            throw error;
        } finally {
            if (response != null) response.close();
            task.clearCall();
            if (client != null) client.connectionPool().evictAll();
            java.util.Arrays.fill(source.nonce, (byte) 0);
            java.util.Arrays.fill(source.ciphertext, (byte) 0);
            java.util.Arrays.fill(source.headersNonce, (byte) 0);
            java.util.Arrays.fill(source.encryptedHeaders, (byte) 0);
            if (requestHeaders != null) requestHeaders.clear();
            // URLs may contain provider-signed query values. They remain local to this call.
            sourceUrl = null;
        }
    }

    private static Response openResponseFollowingRedirects(String source,
            Map<String, String> requestHeaders, ActiveTask task, OkHttpClient client, Dns pinnedDns)
            throws Exception {
        URI current = safeHttpUri(source);
        Map<String, String> redirectHeaders = new LinkedHashMap<>(requestHeaders);
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                checkCanceled(task);
                if ("http".equalsIgnoreCase(current.getScheme())
                        && (current.getRawQuery() != null || !redirectHeaders.isEmpty())) {
                    throw new IOException(CLEAR_HTTP_UNSUPPORTED);
                }
                Request.Builder request = new Request.Builder().url(current.toASCIIString()).get()
                        .header("Accept-Encoding", "identity")
                        .header("User-Agent", "AurasOrbit/0.1");
                for (Map.Entry<String, String> header : redirectHeaders.entrySet()) {
                    request.header(header.getKey(), header.getValue());
                }
                Request builtRequest = request.build();
                // OkHttp skips its custom Dns resolver for IP literals; preflight the normalized host.
                pinnedDns.lookup(builtRequest.url().host());
                Call call = client.newCall(builtRequest);
                try {
                    Response response = enqueueAndAwait(call, task, hop == 0);
                    int code = response.code();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = response.header("Location");
                        response.close();
                        task.clearCall();
                        if (hop == MAX_REDIRECTS || isBlank(location)) throw new IOException("The media source redirected too many times.");
                        URI next;
                        try { next = current.resolve(new URI(location)); }
                        catch (URISyntaxException error) { throw new IOException("The media source returned an invalid redirect.", error); }
                        next = safeHttpUri(next.toString());
                        if ("https".equalsIgnoreCase(current.getScheme())
                                && "http".equalsIgnoreCase(next.getScheme())) {
                            throw new IOException("Orbit rejected an insecure downgrade redirect.");
                        }
                        if (!sameOrigin(current, next)) redirectHeaders.clear();
                        current = next;
                        continue;
                    }
                    if (code != 200) {
                        response.close();
                        task.clearCall();
                        throw new IOException(code >= 400 ? "The media server rejected this download." : "The media source did not return a complete file.");
                    }
                    long length = getContentLength(response);
                    if (length > MAX_DOWNLOAD_BYTES) {
                        response.close();
                        task.clearCall();
                        throw new IOException("This file exceeds Orbit's 8 GB download limit.");
                    }
                    return response;
                } catch (Exception error) {
                    task.clearCall();
                    call.cancel();
                    throw error;
                }
            }
            throw new IOException("The media source redirected too many times.");
        } finally {
            redirectHeaders.clear();
        }
    }

    private static Response enqueueAndAwait(Call call, ActiveTask task, boolean initialRequest)
            throws Exception {
        CountDownLatch responseReady = new CountDownLatch(1);
        AtomicReference<Response> responseRef = new AtomicReference<>();
        AtomicReference<IOException> failureRef = new AtomicReference<>();
        Object callbackLock = new Object();
        boolean[] abandoned = {false};
        Callback callback = new Callback() {
            @Override public void onFailure(Call ignored, IOException error) {
                synchronized (callbackLock) {
                    if (!abandoned[0]) failureRef.set(error);
                }
                if (initialRequest) task.advanceStartOrder();
                responseReady.countDown();
            }

            @Override public void onResponse(Call ignored, Response response) {
                boolean close;
                synchronized (callbackLock) {
                    close = abandoned[0];
                    if (!close) responseRef.set(response);
                }
                if (close) response.close();
                if (initialRequest) task.advanceStartOrder();
                responseReady.countDown();
            }
        };
        task.setCall(call);
        Runnable enqueue = () -> call.enqueue(callback);
        if (initialRequest && task.startGate != null) {
            task.startGate.enqueueInOrder(task, enqueue);
        } else {
            enqueue.run();
        }

        final boolean ready;
        try {
            ready = responseReady.await(CONNECT_TIMEOUT_MS + READ_TIMEOUT_MS + 5_000L,
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            synchronized (callbackLock) { abandoned[0] = true; }
            call.cancel();
            closeQuietly(responseRef.getAndSet(null));
            throw new InterruptedIOException("Download stopped.");
        }
        if (!ready) {
            synchronized (callbackLock) { abandoned[0] = true; }
            call.cancel();
            closeQuietly(responseRef.getAndSet(null));
            throw new SocketTimeoutException("The download server did not respond in time.");
        }
        IOException failure = failureRef.get();
        if (failure != null) throw failure;
        Response response = responseRef.getAndSet(null);
        if (response == null) throw new IOException("The download server returned no response.");
        return response;
    }

    private static void closeQuietly(Response response) {
        if (response != null) response.close();
    }

    /** Creates a private direct-only client; tests reach resolver injection only through reflection. */
    private static OkHttpClient newDownloadClient(okhttp3.Dns dns) {
        return new OkHttpClient.Builder()
                .dns(dns)
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void validateRequest(DownloadRequest request) throws IOException {
        if (request == null) throw new IllegalArgumentException("A download request is required.");
        if (!request.sourceSupportsDirectDownload) {
            throw new IOException("This source does not support direct file downloads.");
        }
        requireText(request.profileId, "profile id", 128);
        requireText(request.stableItemId, "title id", 4096);
        requireText(request.sourceId, "source id", 4096);
        requireText(request.title, "title", 512);
        if (request.sourceUrl == null || request.sourceUrl.length() > MAX_URL_LENGTH) {
            throw new IOException("The source did not provide a supported direct file URL.");
        }
        safeHttpUri(request.sourceUrl);
        if (!DownloadRequest.supportsHeaders(request.sourceUrl, request.headers)) {
            throw new IOException(CLEAR_HTTP_UNSUPPORTED);
        }
    }

    private void validateDestination(Uri tree) throws IOException {
        if (tree == null || !DocumentsContract.isTreeUri(tree)) {
            throw new IOException("Choose a writable Android folder before adding a download.");
        }
        boolean writeGrant = false;
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (tree.equals(permission.getUri()) && permission.isWritePermission()) {
                writeGrant = true;
                break;
            }
        }
        if (!writeGrant) throw new IOException("Orbit needs persistent write access to the selected folder. Choose it again from Downloads.");
    }

    private static URI safeHttpUri(String value) throws IOException {
        if (value == null || value.length() > MAX_URL_LENGTH) throw new IOException("The source URL is too long.");
        try {
            URI parsed = new URI(value.trim());
            String scheme = parsed.getScheme();
            if (scheme == null || parsed.getHost() == null || parsed.getUserInfo() != null
                    || parsed.getFragment() != null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IOException("Only direct HTTP(S) file links without embedded credentials are supported.");
            }
            return parsed;
        } catch (URISyntaxException error) {
            throw new IOException("The source did not provide a valid HTTP(S) file link.", error);
        }
    }

    private static void checkCanceled(ActiveTask task) throws InterruptedIOException {
        // Pause and close publish task.pause before stopping a call, including a call published later.
        if (task.cancel || task.pause || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Download stopped.");
        }
    }

    private boolean hasQueuedItems() { return !store.listIds(DownloadRecord.QUEUED).isEmpty(); }

    private void closeProviderHooksFor(String id) {
        Cs3PlaybackHooks hooks;
        synchronized (stateLock) { hooks = providerHookSessions.remove(id); }
        if (hooks != null) hooks.close();
    }

    private boolean hasRetainedDocuments(String id) {
        DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(id);
        return retained != null && (!isBlank(retained.partialUri) || !isBlank(retained.fileUri)
                || !isBlank(retained.partialName));
    }

    private void notifyChanged() {
        if (listeners.isEmpty()) return;
        List<DownloadRecord> snapshot = getItems();
        main.post(() -> {
            for (Listener listener : listeners) listener.onQueueChanged(snapshot);
        });
    }

    private void removeItemDocuments(ActiveTask task, Uri partialCandidate) {
        boolean cleaned = cleanupRetainedDocuments(task.id, partialCandidate, null,
                "Orbit could not remove the file from the selected folder. Restore folder access and try again.",
                false, task.runToken);
        if (cleaned) {
            store.deleteTask(task.id, task.runToken, DownloadRecord.REMOVING);
        }
    }

    private void finishPartialStatus(ActiveTask task, String status, String message) {
        if (cleanupRetainedDocuments(task.id, null, null,
                "Orbit could not remove the partial file from the selected folder. Restore folder access; "
                        + (message == null ? "the download needs attention." : message), false, task.runToken)) {
            store.setTaskStatus(task.id, task.runToken, status, message);
        }
    }

    private void retryAfterPartialCleanup(ActiveTask task) {
        if (cleanupRetainedDocuments(task.id, null, null,
                "Orbit could not remove the partial file from the selected folder. Restore folder access before retrying.",
                false, task.runToken)) {
            store.resetTaskForRetry(task.id, task.runToken);
        }
    }

    private void finishRenamedAfterAction(ActiveTask task, Uri partial, Uri renamed) {
        boolean cleaned = cleanupRetainedDocuments(task.id, partial, renamed,
                "Orbit could not remove the finalized file after the download was stopped. Restore folder access, then remove or retry this item.",
                false, task.runToken);
        synchronized (stateLock) {
            if (activeTasks.get(task.id) != task) return;
            if (cleaned) {
                if (task.remove) {
                    store.deleteTask(task.id, task.runToken, DownloadRecord.REMOVING);
                } else if (task.cancel) {
                    store.setTaskStatus(task.id, task.runToken,
                            DownloadRecord.CANCELLED, "Download canceled.");
                } else if (task.pause || queuePaused || closed) {
                    if (task.resumeAfterPause && !queuePaused && !closed) {
                        store.resetTaskForRetry(task.id, task.runToken);
                    } else {
                        store.setTaskStatus(task.id, task.runToken,
                                DownloadRecord.PAUSED, "Paused. Retry to start again.");
                    }
                } else {
                    store.setTaskStatus(task.id, task.runToken, DownloadRecord.FAILED,
                            "The download stopped after its file was finalized. Retry to start again.");
                }
            }
        }
    }

    private void finishRenamedAfterError(ActiveTask task, Uri partial, Uri renamed, Exception error) {
        boolean cleaned = cleanupRetainedDocuments(task.id, partial, renamed,
                "Orbit could not remove the finalized file after the download failed. Restore folder access, then remove or retry this item.",
                false, task.runToken);
        synchronized (stateLock) {
            if (activeTasks.get(task.id) != task) return;
            if (cleaned) {
                if (task.remove) {
                    store.deleteTask(task.id, task.runToken, DownloadRecord.REMOVING);
                } else if (task.cancel) {
                    store.setTaskStatus(task.id, task.runToken,
                            DownloadRecord.CANCELLED, "Download canceled.");
                } else if (task.pause || queuePaused || closed) {
                    if (task.resumeAfterPause && !queuePaused && !closed) {
                        store.resetTaskForRetry(task.id, task.runToken);
                    } else {
                        store.setTaskStatus(task.id, task.runToken,
                                DownloadRecord.PAUSED, "Paused. Retry to start again.");
                    }
                } else {
                    store.setTaskStatus(task.id, task.runToken,
                            DownloadRecord.FAILED, safeErrorMessage(error));
                }
            }
        }
    }

    /** Deletes every persisted or reservation-discovered file before its queue references are cleared. */
    private boolean cleanupRetainedDocuments(String id, Uri extraPartial, Uri extraFile, String error) {
        return cleanupRetainedDocuments(id, extraPartial, extraFile, error, false, null);
    }

    /** Name-based final-file recovery is limited to startup reconciliation with a persisted reservation. */
    private boolean cleanupRetainedDocuments(String id, Uri extraPartial, Uri extraFile, String error,
            boolean includeFinalNameDuringRecovery) {
        return cleanupRetainedDocuments(id, extraPartial, extraFile, error,
                includeFinalNameDuringRecovery, null);
    }

    private boolean cleanupRetainedDocuments(String id, Uri extraPartial, Uri extraFile, String error,
            boolean includeFinalNameDuringRecovery, String expectedRunToken) {
        DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(id);
        if (retained == null) return true;

        LinkedHashMap<String, Uri> candidates = new LinkedHashMap<>();
        addCandidate(candidates, retained.partialUri);
        addCandidate(candidates, retained.fileUri);
        addCandidate(candidates, extraPartial);
        addCandidate(candidates, extraFile);

        boolean reservationLookupSucceeded = true;
        boolean reservationRequired = !isBlank(retained.partialName);
        boolean shouldLookup = !isBlank(retained.partialName) || !isBlank(retained.partialUri)
                || !isBlank(retained.fileUri) || extraPartial != null || extraFile != null;
        DocumentLookup lookup = new DocumentLookup(true, new ArrayList<>(), new HashSet<>());
        if (shouldLookup) {
            lookup = findReservedDocuments(retained.folderUri,
                    retained.partialName,
                    includeFinalNameDuringRecovery ? retained.fileName : "");
            reservationLookupSucceeded = lookup.succeeded;
            for (Uri candidate : lookup.documents) addCandidate(candidates, candidate);
        }

        Set<String> failed = new HashSet<>();
        for (Map.Entry<String, Uri> candidate : candidates.entrySet()) {
            Uri document = candidate.getValue();
            if (!lookup.succeeded || !isWithinSelectedTree(document, retained.folderUri,
                    lookup.childDocumentIds)) {
                failed.add(candidate.getKey());
                continue;
            }
            deleteDocument(document);
            DocumentLookup afterDelete = findReservedDocuments(retained.folderUri, "", "");
            if (!afterDelete.succeeded || isListedChild(document, afterDelete.childDocumentIds)) {
                failed.add(candidate.getKey());
            }
        }
        if ((!reservationRequired || reservationLookupSucceeded) && failed.isEmpty()) {
            return expectedRunToken == null ? store.clearRetainedDocuments(id)
                    : store.clearTaskRetainedDocuments(id, expectedRunToken);
        }

        String failedPartial = failedReference(failed, retained.partialUri, extraPartial);
        String failedFile = failedReference(failed, retained.fileUri, extraFile);
        String failure = error == null ? "Orbit could not remove the retained download file." : error;
        if (expectedRunToken == null) {
            store.setRemovalFailure(id, failedPartial, failedFile, failure);
        } else {
            store.setTaskRemovalFailure(id, expectedRunToken, failedPartial, failedFile, failure);
        }
        return false;
    }

    private DownloadQueueStore.PendingRename findPendingRename(String id) {
        for (DownloadQueueStore.PendingRename pending : store.getPendingRenames()) {
            if (id.equals(pending.id)) return pending;
        }
        return null;
    }

    /** Reconciles only a unique exact-name child in the row's persisted tree; it never deletes. */
    private RenameReconciliation reconcilePendingRename(DownloadQueueStore.PendingRename pending) {
        if (pending == null || !DownloadRecord.COMPLETED.equals(pending.status)
                || !pending.originalUri.equals(pending.fileUri)
                || !pending.originalName.equals(pending.fileName)) {
            return RenameReconciliation.pending();
        }
        try {
            Uri tree = parseTree(pending.folderUri);
            TreeChildren children = listTreeChildren(tree);
            if (!children.succeeded) return RenameReconciliation.pending();
            Uri original = canonicalChildUri(Uri.parse(pending.originalUri), tree, children);
            String originalId = documentIdFromStoredUri(pending.originalUri, tree);
            TreeChild originalById = originalId == null ? null : children.byId.get(originalId);
            ArrayList<TreeChild> oldNames = namedChildrenExact(children, pending.originalName);
            ArrayList<TreeChild> targetNames = namedChildrenIgnoringCase(children, pending.targetName);

            if (originalById != null) {
                Uri current = canonicalChildUri(original, tree, children);
                if (current == null) return RenameReconciliation.pending();
                // The original ID/name/metadata prove that this file was never renamed. A sibling
                // may have appeared after preflight. Clear only when every such sibling is
                // distinguishable from the original by known MIME and size metadata.
                if (pending.originalName.equals(originalById.name)) {
                    DocumentMetadata metadata = queryDocumentMetadata(current);
                    if (!matchesJournalContent(pending, metadata)) return RenameReconciliation.pending();
                    for (TreeChild target : targetNames) {
                        if (originalById.id.equals(target.id)) continue;
                        Uri targetUri = DocumentsContract.buildDocumentUriUsingTree(tree, target.id);
                        DocumentMetadata targetMetadata = queryDocumentMetadata(targetUri);
                        if (!provablyDifferentContentMetadata(metadata, targetMetadata)) {
                            return RenameReconciliation.pending();
                        }
                    }
                    return store.clearPendingRename(pending.id, pending.originalUri,
                            pending.originalName, pending.targetName)
                            ? RenameReconciliation.originalRetained(true)
                            : RenameReconciliation.pending();
                }
                if (pending.targetName.equals(originalById.name) && targetNames.size() == 1
                        && pending.targetName.equals(targetNames.get(0).name)
                        && targetNames.get(0).id.equals(originalById.id)) {
                    DocumentMetadata metadata = queryDocumentMetadata(current);
                    if (!matchesJournalContent(pending, metadata)) return RenameReconciliation.pending();
                    return store.finishPendingRename(pending.id, pending.originalUri,
                            pending.originalName, pending.targetName, current.toString(), pending.targetName)
                            ? RenameReconciliation.targetCommitted(true)
                            : RenameReconciliation.pending();
                }
                return RenameReconciliation.pending();
            }

            // Some providers change document IDs on rename. Use exact old-name candidates so a
            // case-only target is not mistaken for the original filename. Case-insensitive target
            // candidates still participate in the ambiguity check.
            if (oldNames.size() == 1) {
                Uri current = DocumentsContract.buildDocumentUriUsingTree(tree, oldNames.get(0).id);
                DocumentMetadata metadata = queryDocumentMetadata(current);
                if (!matchesJournalContent(pending, metadata)) return RenameReconciliation.pending();
                for (TreeChild target : targetNames) {
                    if (oldNames.get(0).id.equals(target.id)) continue;
                    Uri targetUri = DocumentsContract.buildDocumentUriUsingTree(tree, target.id);
                    DocumentMetadata targetMetadata = queryDocumentMetadata(targetUri);
                    if (!provablyDifferentContentMetadata(metadata, targetMetadata)) {
                        return RenameReconciliation.pending();
                    }
                }
                return store.finishPendingRename(pending.id, pending.originalUri,
                        pending.originalName, pending.targetName, current.toString(), pending.originalName)
                        ? RenameReconciliation.originalRetained(true)
                        : RenameReconciliation.pending();
            }
            if (oldNames.isEmpty() && targetNames.size() == 1
                    && pending.targetName.equals(targetNames.get(0).name)) {
                Uri current = DocumentsContract.buildDocumentUriUsingTree(tree, targetNames.get(0).id);
                DocumentMetadata metadata = queryDocumentMetadata(current);
                if (!matchesJournalContent(pending, metadata)) return RenameReconciliation.pending();
                return store.finishPendingRename(pending.id, pending.originalUri,
                        pending.originalName, pending.targetName, current.toString(), pending.targetName)
                        ? RenameReconciliation.targetCommitted(true)
                        : RenameReconciliation.pending();
            }
            return RenameReconciliation.pending();
        } catch (Exception ignored) {
            // Missing grants, provider failures, and ambiguous names keep the journal for a later retry.
            return RenameReconciliation.pending();
        }
    }

    /** Attempts a reversible rename only for the exact URI returned by the provider operation. */
    private boolean rollbackProviderRename(DownloadQueueStore.PendingRename pending, Uri providerUri,
            Uri tree, DocumentMetadata originalMetadata) {
        try {
            TreeChildren children = listTreeChildren(tree);
            if (!children.succeeded) return false;
            Uri current = canonicalChildUri(providerUri, tree, children);
            if (current == null) return false;
            String currentId = documentId(current);
            TreeChild currentChild = children.byId.get(currentId);
            if (currentChild == null) return false;
            String originalId = documentIdFromStoredUri(pending.originalUri, tree);
            if (originalId != null && children.byId.containsKey(originalId)
                    && !originalId.equals(currentId)) return false;
            if (pending.originalName.equals(currentChild.name)) {
                DocumentMetadata currentMetadata = queryDocumentMetadata(current);
                if (!sameContentMetadata(originalMetadata, currentMetadata)) return false;
                return store.finishPendingRename(pending.id, pending.originalUri,
                        pending.originalName, pending.targetName, current.toString(), pending.originalName);
            }
            for (TreeChild child : children.documents) {
                if (!child.id.equals(currentId) && pending.originalName.equalsIgnoreCase(child.name)) return false;
            }
            DocumentMetadata currentMetadata = queryDocumentMetadata(current);
            if (!sameContentMetadata(originalMetadata, currentMetadata)) return false;
            Uri rolledBack = DocumentsContract.renameDocument(resolver, current, pending.originalName);
            if (rolledBack == null) return false;
            TreeChildren after = listTreeChildren(tree);
            if (!after.succeeded) return false;
            Uri restored = canonicalChildUri(rolledBack, tree, after);
            if (restored == null) return false;
            TreeChild restoredChild = after.byId.get(documentId(restored));
            if (restoredChild == null || !pending.originalName.equals(restoredChild.name)) return false;
            DocumentMetadata restoredMetadata = queryDocumentMetadata(restored);
            return sameContentMetadata(originalMetadata, restoredMetadata)
                    && store.finishPendingRename(pending.id, pending.originalUri,
                            pending.originalName, pending.targetName, restored.toString(), pending.originalName);
        } catch (Exception ignored) {
            return false;
        }
    }

    private Uri verifyRenamedDocument(Uri providerUri, Uri tree, String expectedName,
            DocumentMetadata originalMetadata) {
        try {
            TreeChildren children = listTreeChildren(tree);
            if (!children.succeeded) return null;
            Uri current = canonicalChildUri(providerUri, tree, children);
            if (current == null) return null;
            TreeChild child = children.byId.get(documentId(current));
            if (child == null || !expectedName.equals(child.name)
                    || namedChildrenIgnoringCase(children, expectedName).size() != 1) return null;
            DocumentMetadata metadata = queryDocumentMetadata(current);
            return expectedName.equals(metadata.displayName)
                    && sameContentMetadata(originalMetadata, metadata) ? current : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private TreeChildren listTreeChildren(Uri tree) {
        ArrayList<TreeChild> documents = new ArrayList<>();
        HashMap<String, TreeChild> byId = new HashMap<>();
        if (tree == null || !DocumentsContract.isTreeUri(tree)) {
            return new TreeChildren(false, documents, byId);
        }
        try {
            String parentId = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
            try (Cursor cursor = resolver.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor == null) return new TreeChildren(false, documents, byId);
                while (cursor.moveToNext()) {
                    String id = cursor.getString(0);
                    String name = cursor.getString(1);
                    if (isBlank(id) || isBlank(name)) return new TreeChildren(false, documents, byId);
                    TreeChild child = new TreeChild(id, name);
                    documents.add(child);
                    byId.put(id, child);
                }
            }
            return new TreeChildren(true, documents, byId);
        } catch (Exception ignored) {
            return new TreeChildren(false, documents, byId);
        }
    }

    /** Returns a tree-qualified URI only after the document ID appears under the selected tree. */
    private static Uri canonicalChildUri(Uri document, Uri tree, TreeChildren children) {
        if (document == null || tree == null || children == null || !children.succeeded) return null;
        try {
            if (!tree.getAuthority().equals(document.getAuthority())) return null;
            if (DocumentsContract.isTreeUri(document)
                    && !DocumentsContract.getTreeDocumentId(tree)
                    .equals(DocumentsContract.getTreeDocumentId(document))) return null;
            String id = DocumentsContract.getDocumentId(document);
            if (isBlank(id) || id.equals(DocumentsContract.getTreeDocumentId(tree))
                    || !children.byId.containsKey(id)) return null;
            return DocumentsContract.buildDocumentUriUsingTree(tree, id);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String documentId(Uri document) {
        try { return DocumentsContract.getDocumentId(document); }
        catch (Exception ignored) { return ""; }
    }

    private static String documentIdFromStoredUri(String rawUri, Uri tree) {
        try {
            Uri document = Uri.parse(rawUri);
            if (!tree.getAuthority().equals(document.getAuthority())) return null;
            if (DocumentsContract.isTreeUri(document)
                    && !DocumentsContract.getTreeDocumentId(tree)
                    .equals(DocumentsContract.getTreeDocumentId(document))) return null;
            String id = DocumentsContract.getDocumentId(document);
            return isBlank(id) || id.equals(DocumentsContract.getTreeDocumentId(tree)) ? null : id;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean containsName(TreeChildren children, String name, String exceptId) {
        for (TreeChild child : children.documents) {
            if (!child.id.equals(exceptId) && name.equalsIgnoreCase(child.name)) return true;
        }
        return false;
    }

    private static ArrayList<TreeChild> namedChildrenIgnoringCase(TreeChildren children, String name) {
        ArrayList<TreeChild> result = new ArrayList<>();
        for (TreeChild child : children.documents) {
            if (name.equalsIgnoreCase(child.name)) result.add(child);
        }
        return result;
    }

    private static ArrayList<TreeChild> namedChildrenExact(TreeChildren children, String name) {
        ArrayList<TreeChild> result = new ArrayList<>();
        for (TreeChild child : children.documents) {
            if (name.equals(child.name)) result.add(child);
        }
        return result;
    }

    private DocumentMetadata queryDocumentMetadata(Uri document) throws IOException {
        try (Cursor cursor = resolver.query(document,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IOException("The selected folder could not read saved-file metadata.");
            }
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            String name = nameIndex < 0 ? "" : cursor.getString(nameIndex);
            String mime = mimeIndex < 0 || cursor.isNull(mimeIndex) ? "" : cursor.getString(mimeIndex);
            if (isBlank(mime)) mime = resolver.getType(document);
            long size = sizeIndex < 0 || cursor.isNull(sizeIndex) ? -1L : cursor.getLong(sizeIndex);
            return new DocumentMetadata(name, mime == null ? "" : mime, size);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("The selected folder could not read saved-file metadata.");
        }
    }

    private static boolean sameContentMetadata(DocumentMetadata first, DocumentMetadata second) {
        if (first == null || second == null) return false;
        if (!isBlank(first.mimeType) && !isBlank(second.mimeType)
                && !first.mimeType.equalsIgnoreCase(second.mimeType)) return false;
        return first.size < 0L || second.size < 0L || first.size == second.size;
    }

    /** Missing MIME or size cannot distinguish a late sibling from the renamed original. */
    private static boolean provablyDifferentContentMetadata(DocumentMetadata original,
            DocumentMetadata candidate) {
        if (original == null || candidate == null || isBlank(original.mimeType)
                || isBlank(candidate.mimeType) || original.size < 0L || candidate.size < 0L) {
            return false;
        }
        return original.size != candidate.size
                || !original.mimeType.equalsIgnoreCase(candidate.mimeType);
    }

    private static boolean matchesJournalContent(DownloadQueueStore.PendingRename pending,
            DocumentMetadata metadata) {
        return metadata != null && !isBlank(metadata.displayName)
                && (isBlank(pending.mimeType) || isBlank(metadata.mimeType)
                || pending.mimeType.equalsIgnoreCase(metadata.mimeType))
                && (pending.bytesDownloaded < 0L || metadata.size == pending.bytesDownloaded);
    }

    private static Uri parseTree(String folderUri) throws IOException {
        if (isBlank(folderUri)) throw new IOException("Choose the file's selected folder again.");
        try {
            Uri tree = Uri.parse(folderUri);
            if (!DocumentsContract.isTreeUri(tree)) throw new IOException("The saved file has no selected SAF folder.");
            return tree;
        } catch (IllegalArgumentException error) {
            throw new IOException("The saved file has no valid selected SAF folder.");
        }
    }

    private static String completedRenameFileName(String requestedBaseName, String originalName)
            throws IOException {
        String base = requestedBaseName == null ? "" : requestedBaseName.trim();
        if (base.isEmpty() || base.equals(".") || base.equals("..")
                || base.indexOf('/') >= 0 || base.indexOf('\\') >= 0) {
            throw new IOException("Enter a valid file name.");
        }
        base = base.replaceAll("[^A-Za-z0-9._() -]", "_").replaceAll("\\s+", " ").trim();
        while (base.startsWith(".")) base = base.substring(1);
        while (base.endsWith(".")) base = base.substring(0, base.length() - 1).trim();
        String extension = fileExtension(originalName);
        if (!extension.isEmpty() && base.toLowerCase(Locale.ROOT)
                .endsWith(extension.toLowerCase(Locale.ROOT))) {
            base = base.substring(0, base.length() - extension.length()).trim();
        }
        if (base.isEmpty()) throw new IOException("Enter a valid file name.");
        String finalName = base + extension;
        if (finalName.length() > 112) throw new IOException("The file name is too long.");
        return finalName;
    }

    private static String fileExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot) : "";
    }

    private DocumentLookup findReservedDocuments(String folderUri, String reservedName, String recoveryFileName) {
        ArrayList<Uri> documents = new ArrayList<>();
        HashSet<String> childDocumentIds = new HashSet<>();
        if (isBlank(folderUri) || !DocumentsContract.isTreeUri(Uri.parse(folderUri))) {
            return new DocumentLookup(false, documents, childDocumentIds);
        }
        Uri tree = Uri.parse(folderUri);
        try {
            String parentId = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
            try (Cursor cursor = resolver.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (cursor == null) return new DocumentLookup(false, documents, childDocumentIds);
                while (cursor.moveToNext()) {
                    String name = cursor.getString(1);
                    String documentId = cursor.getString(0);
                    if (!isBlank(documentId)) childDocumentIds.add(documentId);
                    boolean matchesPartialReservation = !isBlank(reservedName)
                            && name.equals(reservedName);
                    boolean matchesInterruptedFinalName = !isBlank(recoveryFileName)
                            && name.equals(recoveryFileName);
                    if (isBlank(name) || (!matchesPartialReservation && !matchesInterruptedFinalName)) continue;
                    if (!isBlank(documentId)) {
                        documents.add(DocumentsContract.buildDocumentUriUsingTree(tree, documentId));
                    }
                }
                return new DocumentLookup(true, documents, childDocumentIds);
            }
        } catch (Exception ignored) {
            return new DocumentLookup(false, documents, childDocumentIds);
        }
    }

    private static void addCandidate(Map<String, Uri> candidates, String uriString) {
        if (isBlank(uriString)) return;
        Uri uri = Uri.parse(uriString);
        candidates.put(candidateKey(uri), uri);
    }

    private static void addCandidate(Map<String, Uri> candidates, Uri uri) {
        if (uri != null) candidates.put(candidateKey(uri), uri);
    }

    private static String failedReference(Set<String> failed, String stored, Uri extra) {
        if (!isBlank(stored) && failed.contains(candidateKey(Uri.parse(stored)))) return stored;
        return extra != null && failed.contains(candidateKey(extra)) ? extra.toString() : "";
    }

    private static String candidateKey(Uri uri) {
        try {
            return uri.getAuthority() + ":" + DocumentsContract.getDocumentId(uri);
        } catch (Exception ignored) {
            return uri.toString();
        }
    }

    private static boolean isListedChild(Uri uri, Set<String> childDocumentIds) {
        try {
            return childDocumentIds.contains(DocumentsContract.getDocumentId(uri));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isWithinSelectedTree(Uri document, String folderUri,
            Set<String> listedChildIds) {
        if (document == null || isBlank(folderUri)) return false;
        try {
            Uri tree = Uri.parse(folderUri);
            if (!DocumentsContract.isTreeUri(tree)
                    || !tree.getAuthority().equals(document.getAuthority())) return false;
            if (!DocumentsContract.isTreeUri(document)) {
                return isListedChild(document, listedChildIds);
            }
            return DocumentsContract.getTreeDocumentId(tree)
                    .equals(DocumentsContract.getTreeDocumentId(document));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean deleteDocument(Uri uri) {
        if (uri == null) return true;
        try { return DocumentsContract.deleteDocument(resolver, uri); }
        catch (Exception ignored) { return false; }
    }

    private static final class DocumentLookup {
        final boolean succeeded;
        final List<Uri> documents;
        final Set<String> childDocumentIds;
        DocumentLookup(boolean succeeded, List<Uri> documents, Set<String> childDocumentIds) {
            this.succeeded = succeeded;
            this.documents = documents;
            this.childDocumentIds = childDocumentIds;
        }
    }

    private static final class TreeChildren {
        final boolean succeeded;
        final List<TreeChild> documents;
        final Map<String, TreeChild> byId;
        TreeChildren(boolean succeeded, List<TreeChild> documents, Map<String, TreeChild> byId) {
            this.succeeded = succeeded;
            this.documents = documents;
            this.byId = byId;
        }
    }

    private static final class TreeChild {
        final String id;
        final String name;
        TreeChild(String id, String name) { this.id = id; this.name = name; }
    }

    private static final class DocumentMetadata {
        final String displayName;
        final String mimeType;
        final long size;
        DocumentMetadata(String displayName, String mimeType, long size) {
            this.displayName = displayName == null ? "" : displayName;
            this.mimeType = mimeType == null ? "" : mimeType;
            this.size = size;
        }
    }

    private static final class RenameReconciliation {
        static final int PENDING = 0;
        static final int ORIGINAL_RETAINED = 1;
        static final int TARGET_COMMITTED = 2;
        final int result;
        final boolean changed;
        private RenameReconciliation(int result, boolean changed) {
            this.result = result;
            this.changed = changed;
        }
        static RenameReconciliation pending() { return new RenameReconciliation(PENDING, false); }
        static RenameReconciliation originalRetained(boolean changed) {
            return new RenameReconciliation(ORIGINAL_RETAINED, changed);
        }
        static RenameReconciliation targetCommitted(boolean changed) {
            return new RenameReconciliation(TARGET_COMMITTED, changed);
        }
    }

    private Uri renameDocument(Uri uri, String name) throws IOException {
        if (uri == null) return null;
        try { return DocumentsContract.renameDocument(resolver, uri, name); }
        catch (Exception error) { throw new IOException("The selected folder could not finalize this download.", error); }
    }

    private String itemFileName(String id) {
        DownloadRecord item = store.get(id);
        return item == null ? "Orbit-download-" + id.substring(0, 8) : item.fileName;
    }

    private static EncryptedValue encryptSourceUrl(String url) throws IOException {
        byte[] clear = url.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey());
            byte[] encrypted = cipher.doFinal(clear);
            return new EncryptedValue(cipher.getIV(), encrypted);
        } catch (GeneralSecurityException error) {
            throw new IOException("Orbit could not protect the provider download link on this device.", error);
        } finally {
            java.util.Arrays.fill(clear, (byte) 0);
        }
    }

    private static EncryptedValue encryptHeaders(Map<String, String> headers, String id) throws IOException {
        byte[] clear = serializeHeaders(headers);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey());
            cipher.updateAAD(headerAdditionalData(id));
            byte[] encrypted = cipher.doFinal(clear);
            return new EncryptedValue(cipher.getIV(), encrypted);
        } catch (GeneralSecurityException error) {
            throw new IOException("Orbit could not protect provider request headers on this device.", error);
        } finally {
            java.util.Arrays.fill(clear, (byte) 0);
        }
    }

    private static EncryptedValue encryptVerifierToken(DownloadProviderHookToken token, String id)
            throws IOException {
        byte[] clear = serializeVerifierToken(token);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey());
            cipher.updateAAD(verifierTokenAdditionalData(id));
            byte[] encrypted = cipher.doFinal(clear);
            return new EncryptedValue(cipher.getIV(), encrypted);
        } catch (GeneralSecurityException error) {
            throw new IOException("Orbit could not protect provider verifier data on this device.", error);
        } finally {
            java.util.Arrays.fill(clear, (byte) 0);
        }
    }

    private static byte[] serializeVerifierToken(DownloadProviderHookToken token) throws IOException {
        if (token == null) throw new IOException("The provider verifier token is missing.");
        String[] fields = {token.profileId, token.pluginId, token.providerId, token.apiClassName,
                token.apiName, token.pluginArchiveSha256, token.sourceId,
                token.sourceUrlSha256, token.extractorData};
        byte[][] encoded = new byte[fields.length][];
        long payloadLength = 1L + Integer.BYTES;
        ByteBuffer output = null;
        try {
            for (int index = 0; index < fields.length; index++) {
                encoded[index] = fields[index].getBytes(StandardCharsets.UTF_8);
                if (!fields[index].equals(new String(encoded[index], StandardCharsets.UTF_8))) {
                    throw new IOException("The provider verifier token contains invalid text encoding.");
                }
                if (encoded[index].length > 0xffff) {
                    throw new IOException("The provider verifier token exceeds its storage limit.");
                }
                payloadLength += Short.BYTES + encoded[index].length;
            }
            if (payloadLength > MAX_VERIFIER_TOKEN_BYTES) {
                throw new IOException("The provider verifier token exceeds its storage limit.");
            }
            output = ByteBuffer.allocate((int) payloadLength).order(ByteOrder.BIG_ENDIAN);
            output.put((byte) token.version);
            output.putInt(token.registrationIndex);
            for (byte[] value : encoded) {
                output.putShort((short) value.length);
                output.put(value);
            }
            return output.array();
        } catch (IOException error) {
            if (output != null) java.util.Arrays.fill(output.array(), (byte) 0);
            throw error;
        } catch (RuntimeException error) {
            if (output != null) java.util.Arrays.fill(output.array(), (byte) 0);
            throw new IOException("Orbit could not encode provider verifier data.", error);
        } finally {
            for (byte[] value : encoded) {
                if (value != null) java.util.Arrays.fill(value, (byte) 0);
            }
        }
    }

    private static DownloadProviderHookToken decryptVerifierToken(
            DownloadQueueStore.PendingSource saved, String id) throws IOException {
        byte[] nonce = saved.verifierNonce;
        byte[] encrypted = saved.encryptedVerifierToken;
        if (nonce == null || encrypted == null || nonce.length != 12
                || encrypted.length < 16 || encrypted.length > MAX_VERIFIER_TOKEN_BYTES + 16) {
            throw new ProtectedQueueDataException();
        }
        byte[] clear = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(verifierTokenAdditionalData(id));
            clear = cipher.doFinal(encrypted);
            try {
                return deserializeVerifierToken(clear);
            } catch (IOException | IllegalArgumentException error) {
                throw new ProtectedQueueDataException(error);
            }
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new ProtectedQueueDataException(error);
        } finally {
            if (clear != null) java.util.Arrays.fill(clear, (byte) 0);
        }
    }

    private static DownloadProviderHookToken deserializeVerifierToken(byte[] clear) throws IOException {
        try {
            ByteBuffer input = ByteBuffer.wrap(clear).order(ByteOrder.BIG_ENDIAN);
            if (input.remaining() < 1 + Integer.BYTES) throw new IOException("Invalid token.");
            int version = input.get() & 0xff;
            int registrationIndex = input.getInt();
            String profileId = readTokenString(input, 128);
            String pluginId = readTokenString(input, 512);
            String providerId = readTokenString(input, 512);
            String apiClassName = readTokenString(input, 512);
            String apiName = readTokenString(input, 256);
            String pluginArchiveSha256 = readTokenString(input, 64);
            String sourceId = readTokenString(input, 4096);
            String sourceUrlSha256 = readTokenString(input, 64);
            String extractorData = readTokenString(input, DownloadProviderHookToken.MAX_FIELD_LENGTH);
            if (input.hasRemaining()) throw new IOException("Invalid token.");
            return new DownloadProviderHookToken(version, profileId, pluginId, providerId,
                    registrationIndex, apiClassName, apiName, pluginArchiveSha256,
                    sourceId, sourceUrlSha256, extractorData);
        } catch (java.nio.BufferUnderflowException error) {
            throw new IOException("Invalid token.", error);
        }
    }

    private static String readTokenString(ByteBuffer input, int maximumCharacters) throws IOException {
        if (input.remaining() < Short.BYTES) throw new IOException("Invalid token.");
        int length = input.getShort() & 0xffff;
        if (length > input.remaining() || length > MAX_VERIFIER_TOKEN_BYTES) {
            throw new IOException("Invalid token.");
        }
        byte[] bytes = new byte[length];
        try {
            input.get(bytes);
            String value = new String(bytes, StandardCharsets.UTF_8);
            if (value.length() > maximumCharacters) throw new IOException("Invalid token.");
            return value;
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static byte[] verifierTokenAdditionalData(String id) {
        return ("auras.orbit.download.verifier.v1:" + id).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] serializeHeaders(Map<String, String> headers) throws IOException {
        final Map<String, String> safeHeaders;
        try {
            safeHeaders = DownloadRequest.canonicalizeHeaders(headers);
        } catch (IllegalArgumentException error) {
            throw new IOException("The provider returned invalid request headers.");
        }
        int length = 3;
        for (Map.Entry<String, String> entry : safeHeaders.entrySet()) {
            length += entry.getKey().getBytes(StandardCharsets.UTF_8).length
                    + entry.getValue().getBytes(StandardCharsets.UTF_8).length + 4;
        }
        ByteBuffer output = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        try {
            output.put((byte) 1);
            output.putShort((short) safeHeaders.size());
            for (Map.Entry<String, String> entry : safeHeaders.entrySet()) {
                byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] value = entry.getValue().getBytes(StandardCharsets.UTF_8);
                try {
                    output.putShort((short) name.length);
                    output.put(name);
                    output.putShort((short) value.length);
                    output.put(value);
                } finally {
                    java.util.Arrays.fill(name, (byte) 0);
                    java.util.Arrays.fill(value, (byte) 0);
                }
            }
            return output.array();
        } catch (RuntimeException error) {
            java.util.Arrays.fill(output.array(), (byte) 0);
            throw new IOException("Orbit could not encode provider request headers.", error);
        }
    }

    private static Map<String, String> decryptHeaders(byte[] nonce, byte[] encrypted, String id)
            throws IOException {
        if (nonce == null || encrypted == null) {
            throw new ProtectedQueueDataException();
        }
        if (nonce.length == 0 && encrypted.length == 0) {
            // Rows created before schema version 3 never had request headers.
            return new LinkedHashMap<>();
        }
        byte[] clear = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(headerAdditionalData(id));
            clear = cipher.doFinal(encrypted);
            try {
                return deserializeHeaders(clear);
            } catch (IOException error) {
                throw new ProtectedQueueDataException(error);
            }
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new ProtectedQueueDataException(error);
        } finally {
            if (clear != null) java.util.Arrays.fill(clear, (byte) 0);
        }
    }

    private static Map<String, String> deserializeHeaders(byte[] clear) throws IOException {
        try {
            ByteBuffer input = ByteBuffer.wrap(clear).order(ByteOrder.BIG_ENDIAN);
            if (input.remaining() < 3 || (input.get() & 0xff) != 1) {
                throw new IOException("Orbit could not read the saved request headers.");
            }
            int count = input.getShort() & 0xffff;
            if (count > DownloadRequest.MAX_HEADER_COUNT) {
                throw new IOException("Orbit could not read the saved request headers.");
            }
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String name = readHeaderString(input, DownloadRequest.MAX_HEADER_NAME_LENGTH);
                String value = readHeaderString(input, DownloadRequest.MAX_HEADER_VALUE_LENGTH);
                headers.put(name, value);
            }
            if (input.hasRemaining()) throw new IOException("Orbit could not read the saved request headers.");
            return new LinkedHashMap<>(DownloadRequest.canonicalizeHeaders(headers));
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException error) {
            throw new IOException("Orbit could not read the saved request headers.", error);
        }
    }

    private static String readHeaderString(ByteBuffer input, int maxLength) throws IOException {
        if (input.remaining() < 2) throw new IOException("Orbit could not read the saved request headers.");
        int length = input.getShort() & 0xffff;
        if (length > maxLength || length > input.remaining()) {
            throw new IOException("Orbit could not read the saved request headers.");
        }
        byte[] bytes = new byte[length];
        try {
            input.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static byte[] headerAdditionalData(String id) {
        return ("auras.orbit.download.headers.v1:" + id).getBytes(StandardCharsets.UTF_8);
    }

    private static String decryptSourceUrl(byte[] nonce, byte[] encrypted) throws IOException {
        byte[] clear = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), new GCMParameterSpec(128, nonce));
            clear = cipher.doFinal(encrypted);
            String url = new String(clear, StandardCharsets.UTF_8);
            try {
                safeHttpUri(url);
            } catch (IOException error) {
                throw new ProtectedQueueDataException(error);
            }
            return url;
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new ProtectedQueueDataException(error);
        } finally {
            if (clear != null) java.util.Arrays.fill(clear, (byte) 0);
        }
    }

    private static SecretKey loadOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        try { keyStore.load(null); }
        catch (java.io.IOException error) { throw new GeneralSecurityException("Android Keystore unavailable", error); }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((SecretKey) keyStore.getKey(KEY_ALIAS, null));
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static String sanitizeFileName(String input) throws IOException {
        String value = input == null ? "" : input.trim();
        value = value.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        value = value.replaceAll("[^A-Za-z0-9._() -]", "_");
        value = value.replaceAll("\\s+", " ").trim();
        while (value.startsWith(".")) value = value.substring(1);
        if (value.isEmpty()) throw new IOException("The provider did not supply a usable download file name.");
        if (value.length() > 112) value = value.substring(0, 112);
        return value;
    }

    private static String uniqueFileName(String name, String id) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(0, dot) + "-" + id.substring(0, 8) + name.substring(dot);
        }
        return name + "-" + id.substring(0, 8);
    }

    private static String safeMimeType(String mimeType) {
        if (mimeType == null || !mimeType.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) {
            return "application/octet-stream";
        }
        return mimeType.toLowerCase(Locale.ROOT);
    }

    private static long getContentLength(Response response) {
        String value = response.header("Content-Length");
        if (value == null) return -1L;
        try {
            long length = Long.parseLong(value.trim());
            return length < 0L ? -1L : length;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static String safeErrorMessage(Exception error) {
        if (error instanceof LegacyProviderVerifierRecoveryException) {
            return "This older download has no saved provider verifier context. Remove and add it again from the provider.";
        }
        if (error instanceof ProviderVerifierRecoveryException) {
            return "The selected provider or plugin version could not be verified. Restore the same provider version and retry, or add this download again.";
        }
        if (error instanceof ProtectedQueueDataException) {
            return "Remove and add this download again to restore access.";
        }
        if (CLEAR_HTTP_UNSUPPORTED.equals(error == null ? null : error.getMessage())) {
            return CLEAR_HTTP_UNSUPPORTED;
        }
        // Network and provider exceptions can echo request-header or signed URL values.
        return "Orbit could not finish this download. Retry when the source is available.";
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
    private static void requireText(String value, String label, int max) throws IOException {
        if (value == null || value.trim().isEmpty() || value.length() > max || value.indexOf('\n') >= 0) {
            throw new IOException("The provider returned an invalid " + label + ".");
        }
    }
    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Download queue has been closed.");
    }

    @Override public synchronized void close() {
        ArrayList<ActiveTask> stop = new ArrayList<>();
        ArrayList<Cs3PlaybackHooks> hooks;
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
            queuePaused = true;
            for (ActiveTask running : activeTasks.values()) {
                running.pause = true;
                stop.add(running);
            }
            hooks = new ArrayList<>(providerHookSessions.values());
            providerHookSessions.clear();
        }
        for (ActiveTask task : stop) task.stopConnection();
        for (Cs3PlaybackHooks providerHooks : hooks) providerHooks.close();
        admission.shutdown();
        Thread closer = new Thread(() -> {
            awaitTermination(admission);
            transfers.shutdown();
            awaitTermination(transfers);
            store.close();
        }, "orbit-download-close");
        closer.setDaemon(true);
        closer.start();
    }

    private static void awaitTermination(ExecutorService executor) {
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try { executor.awaitTermination(1L, TimeUnit.DAYS); }
            catch (InterruptedException error) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public interface Listener {
        void onQueueChanged(List<DownloadRecord> items);
    }

    /** Rebinds data-only verifier metadata to a fresh, active object from the current registration. */
    public interface ProviderHookRestorer {
        Cs3PlaybackHooks restore(DownloadProviderHookToken token) throws Exception;
    }

    private static final class ActiveTask {
        final String id;
        final String runToken;
        final long startOrder;
        final StartOrderGate startGate;
        volatile Cs3PlaybackHooks providerHooks;
        volatile boolean pause;
        volatile boolean resumeAfterPause;
        volatile boolean cancel;
        volatile boolean remove;
        private final Object callLock = new Object();
        private final Object estimateLock = new Object();
        private Call call;
        private boolean startOrderAdvanced;
        private long lastRateSampleBytes = -1L;
        private long latestBytes;
        private long lastSampleAt;
        private double smoothedBytesPerSecond;
        private long lastTotalBytes;

        ActiveTask(String id) { this(id, "", 0L, null, null); }
        ActiveTask(String id, String runToken, long startOrder, StartOrderGate startGate) {
            this(id, runToken, startOrder, startGate, null);
        }
        ActiveTask(String id, String runToken, long startOrder, StartOrderGate startGate,
                   Cs3PlaybackHooks providerHooks) {
            this.id = id;
            this.runToken = runToken;
            this.startOrder = startOrder;
            this.startGate = startGate;
            this.providerHooks = providerHooks;
        }

        void recordProgress(long bytes, long total, long now) {
            synchronized (estimateLock) {
                lastTotalBytes = Math.max(0L, total);
                latestBytes = Math.max(0L, bytes);
                if (lastRateSampleBytes < 0L) {
                    lastRateSampleBytes = latestBytes;
                    lastSampleAt = now;
                    return;
                }
                if (now > lastSampleAt && now - lastSampleAt >= 250L) {
                    long elapsedMs = now - lastSampleAt;
                    long delta = latestBytes - lastRateSampleBytes;
                    double sample = delta > 0L ? delta * 1000.0d / elapsedMs : 0.0d;
                    if (!Double.isNaN(sample) && !Double.isInfinite(sample) && sample > 0.0d) {
                        smoothedBytesPerSecond = smoothedBytesPerSecond <= 0.0d
                                ? sample : (smoothedBytesPerSecond * 0.65d) + (sample * 0.35d);
                    }
                    lastRateSampleBytes = latestBytes;
                    lastSampleAt = now;
                }
            }
        }

        Estimate estimate() {
            synchronized (estimateLock) {
                long rate = smoothedBytesPerSecond >= 1.0d
                        ? (long) Math.min(Long.MAX_VALUE, smoothedBytesPerSecond) : 0L;
                long eta = -1L;
                if (rate > 0L && lastTotalBytes > latestBytes) {
                    long remaining = lastTotalBytes - latestBytes;
                    eta = remaining / rate + (remaining % rate == 0L ? 0L : 1L);
                }
                return new Estimate(rate, eta);
            }
        }

        void advanceStartOrder() {
            if (startGate == null) return;
            synchronized (this) {
                if (startOrderAdvanced) return;
                startOrderAdvanced = true;
            }
            startGate.skip(this);
        }
        void setCall(Call next) {
            synchronized (callLock) {
                call = next;
                if (pause || cancel) next.cancel();
            }
        }
        void clearCall() {
            synchronized (callLock) { call = null; }
        }
        void stopConnection() {
            synchronized (callLock) {
                if (call != null) call.cancel();
            }
            if (startGate != null) startGate.wakeWaiters();
        }

        void onProviderHookRevoked() {
            pause = true;
            stopConnection();
        }

        static final class Estimate {
            final long bytesPerSecond;
            final long etaSeconds;
            Estimate(long bytesPerSecond, long etaSeconds) {
                this.bytesPerSecond = bytesPerSecond;
                this.etaSeconds = etaSeconds;
            }
        }
    }

    private static final class StartOrderGate {
        private long nextOrder = 1L;
        private final Set<Long> advancedOutOfOrder = new HashSet<>();

        synchronized void enqueueInOrder(ActiveTask task, Runnable enqueue) throws IOException {
            while (task.startOrder > nextOrder && !task.pause && !task.cancel) {
                try { wait(250L); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Download stopped.");
                }
            }
            if (task.pause || task.cancel || task.startOrder < nextOrder) {
                throw new InterruptedIOException("Download stopped.");
            }
            try { enqueue.run(); }
            catch (RuntimeException failure) {
                advanceLocked(task.startOrder);
                throw failure;
            }
        }

        synchronized void skip(ActiveTask task) { advanceLocked(task.startOrder); }
        synchronized void wakeWaiters() { notifyAll(); }

        private void advanceLocked(long order) {
            if (order < nextOrder) return;
            if (order > nextOrder) advancedOutOfOrder.add(order);
            else {
                nextOrder++;
                while (advancedOutOfOrder.remove(nextOrder)) nextOrder++;
            }
            notifyAll();
        }
    }

    private static final class EncryptedValue {
        final byte[] nonce;
        final byte[] ciphertext;
        EncryptedValue(byte[] nonce, byte[] ciphertext) { this.nonce = nonce; this.ciphertext = ciphertext; }
    }

    private static final class PersistedVerifierMatch {
        final String state;
        final boolean matches;
        final boolean sourceMatches;
        PersistedVerifierMatch(String state, boolean matches, boolean sourceMatches) {
            this.state = state;
            this.matches = matches;
            this.sourceMatches = sourceMatches;
        }
    }

    private static final class ProtectedQueueDataException extends IOException {
        ProtectedQueueDataException() { super("Protected download data is unreadable."); }
        ProtectedQueueDataException(Throwable cause) {
            super("Protected download data is unreadable.", cause);
        }
    }

    private static class ProviderVerifierRecoveryException extends IOException {
        ProviderVerifierRecoveryException() { super("Provider verifier context could not be safely restored."); }
        ProviderVerifierRecoveryException(Throwable cause) {
            super("Provider verifier context could not be safely restored.", cause);
        }
    }

    private static final class LegacyProviderVerifierRecoveryException
            extends ProviderVerifierRecoveryException {
        LegacyProviderVerifierRecoveryException() {
            super();
        }
    }
}
