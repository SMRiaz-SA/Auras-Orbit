package com.auras.orbit.extensions;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.AtomicFile;
import android.util.Log;

import com.auras.orbit.OrbitApplication;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Orbit-owned CloudStream repository, catalog, and CS3 package store. */
public final class CloudStreamExtensionManager {
    private static final String PREFS = "orbit_cloudstream_extensions_v1";
    private static final String REPOSITORIES_KEY = "repositories_json";
    private static final String INSTALLED_KEY = "installed_plugins_json";
    private static final String ENABLED_KEY = "profile_enabled_plugins_json";
    private static final String INSTALL_TRANSACTION_KEY = "plugin_install_transaction_json";
    private static final String UNINSTALL_TRANSACTION_KEY = "plugin_uninstall_transaction_json";
    private static final long MAX_JSON_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_PLUGIN_BYTES = 512L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    private static final Object PROCESS_STORAGE_LOCK = new Object();
    private static final Set<String> ACTIVE_STAGING_PATHS = new HashSet<>();
    private final Context appContext;
    private final SharedPreferences preferences;
    private final Object lock = PROCESS_STORAGE_LOCK;
    private final Map<String, ActivePackageDownload> activePackageDownloads = new LinkedHashMap<>();
    private final File repositoryCacheDir;
    private final File pluginDir;
    private final ProfileGrantRecoveryJournal profileGrantJournal;
    private volatile boolean storageRecoveryReady;

    public interface PackageInstallListener {
        void onDownloadStarted();
        void onCommitStarted();
    }

    interface ProfileGrantRecoveryJournal {
        boolean writePrevious(String encodedGrants);
        String readPrevious();
        boolean hasPending();
        boolean clear();
    }

    enum ProfileGrantUpdateOutcome { COMMITTED, ROLLED_BACK, PREPARE_FAILED, RECOVERY_REQUIRED }

    private static final class ActivePackageDownload {
        final String operationId;
        final File stagingFile;
        HttpURLConnection connection;
        boolean cancellationRequested;
        boolean commitStarted;

        ActivePackageDownload(String operationId, File stagingFile) {
            this.operationId = operationId;
            this.stagingFile = stagingFile;
        }
    }

    static final class AtomicProfileGrantRecoveryJournal implements ProfileGrantRecoveryJournal {
        private final AtomicFile file;
        private final File baseFile;
        private final File backupFile;

        AtomicProfileGrantRecoveryJournal(File baseFile) {
            this.baseFile = baseFile;
            backupFile = new File(baseFile.getPath() + ".bak");
            file = new AtomicFile(baseFile);
        }

        @Override public boolean writePrevious(String encodedGrants) {
            byte[] data = encodedGrants.getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_JSON_BYTES) return false;
            FileOutputStream stream = null;
            try {
                stream = file.startWrite();
                stream.write(data);
                file.finishWrite(stream);
                return hasPending();
            } catch (IOException | RuntimeException failure) {
                if (stream != null) {
                    try { file.failWrite(stream); }
                    catch (RuntimeException ignored) { }
                }
                Log.e("OrbitCS3Storage", "Unable to persist the profile-grant recovery journal.", failure);
                return false;
            }
        }

        @Override public String readPrevious() {
            try (InputStream input = file.openRead();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_JSON_BYTES) return null;
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            } catch (IOException | RuntimeException failure) {
                return null;
            }
        }

        @Override public boolean hasPending() {
            try { return baseFile.exists() || backupFile.exists(); }
            catch (RuntimeException failure) { return true; }
        }

        @Override public boolean clear() {
            try {
                file.delete();
                return !baseFile.exists() && !backupFile.exists();
            } catch (RuntimeException failure) {
                return false;
            }
        }
    }

    public CloudStreamExtensionManager(Context context) {
        appContext = context.getApplicationContext();
        preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        repositoryCacheDir = new File(appContext.getFilesDir(), "cloudstream/repositories");
        pluginDir = new File(appContext.getFilesDir(), "cloudstream/plugins");
        profileGrantJournal = new AtomicProfileGrantRecoveryJournal(
                new File(appContext.getNoBackupFilesDir(), "orbit-profile-grants-transaction.json"));
        synchronized (lock) {
            storageRecoveryReady = recoverProfileGrantJournalLocked() && recoverPackageStorageLocked();
        }
    }

    /** Re-runs package journal/orphan recovery; false means activation and package changes must remain blocked. */
    public boolean isStorageRecoveryReady() {
        synchronized (lock) {
            storageRecoveryReady = recoverProfileGrantJournalLocked() && recoverPackageStorageLocked();
            return storageRecoveryReady;
        }
    }

    public List<RepositoryInfo> getRepositories() {
        JSONArray encoded;
        try {
            encoded = new JSONArray(preferences.getString(REPOSITORIES_KEY, "[]"));
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }
        ArrayList<RepositoryInfo> result = new ArrayList<>();
        for (int index = 0; index < encoded.length(); index++) {
            JSONObject item = encoded.optJSONObject(index);
            if (item == null) continue;
            RepositoryInfo record = RepositoryInfo.fromJson(item);
            if (record != null) result.add(record);
        }
        return Collections.unmodifiableList(result);
    }

    public RepositoryInfo findRepository(String canonicalUrl) {
        String canonical = canonicalize(canonicalUrl);
        if (canonical == null) return null;
        for (RepositoryInfo repository : getRepositories()) {
            if (repository.url.equals(canonical)) return repository;
        }
        return null;
    }

    /** Reads a remote CloudStream descriptor without persisting or enabling it. */
    public RepositoryInfo previewRepository(String input) throws IOException {
        RepositoryDocument descriptor = previewRepositoryDescriptor(input);
        return new RepositoryInfo(descriptor.repositoryUrl, descriptor.name, descriptor.description,
                descriptor.iconUrl, descriptor.pluginListUrls.size(), System.currentTimeMillis(), "Preview");
    }

    /** Stores repository metadata supplied by the caller without performing network requests. */
    public RepositoryInfo addRepositoryMetadata(String iconUrl, String name, String url,
                                               String description) throws IOException {
        String canonical = canonicalize(url);
        if (canonical == null) throw new IOException("Repository URL must be a resolved HTTP(S) address.");
        synchronized (lock) {
            ArrayList<RepositoryInfo> repositories = new ArrayList<>(getRepositories());
            for (int index = 0; index < repositories.size(); index++) {
                RepositoryInfo existing = repositories.get(index);
                if (existing.url.equals(canonical)) {
                    RepositoryInfo updated = new RepositoryInfo(canonical, name, description, iconUrl,
                            existing.pluginListCount, existing.lastRefreshed, existing.status);
                    repositories.set(index, updated);
                    if (!saveRepositories(repositories)) throw new IOException("Could not save repository metadata.");
                    return updated;
                }
            }
            RepositoryInfo added = new RepositoryInfo(canonical, name, description, iconUrl,
                    0, 0L, "Saved; catalog not loaded");
            repositories.add(added);
            if (!saveRepositories(repositories)) throw new IOException("Could not save repository metadata.");
            return added;
        }
    }

    /** Reads the manifest fields needed by the CS3 Repository facade without saving them. */
    public RepositoryDocument previewRepositoryDescriptor(String input) throws IOException {
        return readRepositoryDescriptor(resolveRepositoryInput(input));
    }

    /** Resolves CloudStream URL forms and shortcuts, then adds a repository once by canonical URL. */
    public AddRepositoryResult addRepository(String input) throws IOException {
        String resolved = resolveRepositoryInput(input);
        String canonical = canonicalize(resolved);
        if (canonical == null) throw new IOException("Enter an HTTP(S) repository URL or supported shortcut.");
        RepositoryInfo existing = findRepository(canonical);
        if (existing != null) {
            return new AddRepositoryResult(existing, getCatalog(canonical).size(), true);
        }

        RepositoryDocument descriptor = readRepositoryDescriptor(canonical);
        CatalogResult catalog = readPluginCatalog(descriptor);
        if (catalog.successfulLists == 0) {
            throw new IOException("The repository descriptor loaded, but none of its plugin lists could be read.");
        }
        RepositoryInfo repository = new RepositoryInfo(
                canonical,
                nonEmpty(descriptor.name, hostLabel(descriptor.finalUrl)),
                descriptor.description,
                descriptor.iconUrl,
                descriptor.pluginListUrls.size(),
                System.currentTimeMillis(),
                catalog.failedLists == 0 ? "Available" : "Some plugin lists unavailable");
        synchronized (lock) {
            ArrayList<RepositoryInfo> repositories = new ArrayList<>(getRepositories());
            for (RepositoryInfo item : repositories) {
                if (item.url.equals(canonical)) return new AddRepositoryResult(item, getCatalog(canonical).size(), true);
            }
            repositories.add(repository);
            saveCatalog(canonical, catalog.plugins);
            if (!saveRepositories(repositories)) throw new IOException("Could not save repository metadata.");
        }
        return new AddRepositoryResult(repository, catalog.plugins.size(), false);
    }

    public RepositoryInfo refreshRepository(String canonicalUrl) throws IOException {
        String canonical = canonicalize(canonicalUrl);
        if (canonical == null) throw new IOException("Repository URL is invalid.");
        RepositoryDocument descriptor = readRepositoryDescriptor(canonical);
        CatalogResult catalog = readPluginCatalog(descriptor);
        if (catalog.successfulLists == 0) {
            throw new IOException("No plugin lists could be read. The saved repository and previous catalog were kept.");
        }
        RepositoryInfo refreshed = new RepositoryInfo(
                canonical,
                nonEmpty(descriptor.name, hostLabel(descriptor.finalUrl)),
                descriptor.description,
                descriptor.iconUrl,
                descriptor.pluginListUrls.size(),
                System.currentTimeMillis(),
                catalog.failedLists == 0 ? "Available" : "Some plugin lists unavailable");
        synchronized (lock) {
            ArrayList<RepositoryInfo> repositories = new ArrayList<>(getRepositories());
            boolean found = false;
            for (int index = 0; index < repositories.size(); index++) {
                if (repositories.get(index).url.equals(canonical)) {
                    repositories.set(index, refreshed);
                    found = true;
                    break;
                }
            }
            if (!found) throw new IOException("This repository was removed before refresh completed; its catalog remains removed.");
            saveCatalog(canonical, catalog.plugins);
            if (!saveRepositories(repositories)) throw new IOException("Could not save refreshed repository metadata.");
        }
        return refreshed;
    }

    /** Removing a repository does not remove its installed plugins or plugin settings. */
    public boolean removeRepository(String canonicalUrl) {
        String canonical = canonicalize(canonicalUrl);
        if (canonical == null) return false;
        synchronized (lock) {
            ArrayList<RepositoryInfo> previous = new ArrayList<>(getRepositories());
            ArrayList<RepositoryInfo> remaining = new ArrayList<>(previous);
            boolean wasRegistered = false;
            for (int index = remaining.size() - 1; index >= 0; index--) {
                if (remaining.get(index).url.equals(canonical)) {
                    remaining.remove(index);
                    wasRegistered = true;
                }
            }
            File cache = catalogFile(canonical);
            if (!deleteRepositoryCacheFile(cache)) return false;
            File backup = new File(repositoryCacheDir, cache.getName() + ".previous");
            if (!deleteRepositoryCacheFile(backup)) return false;
            if (!wasRegistered) return true;

            if (!saveRepositories(remaining)) {
                // SharedPreferences updates its in-memory view before the disk write. Restore
                // the registered record before reporting failure so callers can safely retry.
                boolean restored = saveRepositories(previous);
                if (isRepositoryRegisteredLocked(canonical)) {
                    if (!restored) persistRepositoriesAsync(previous);
                    return false;
                }
                // A failed restore must not report failure while the live repository is absent.
                persistRepositoriesAsync(remaining);
                return true;
            }
        }
        return true;
    }

    private static boolean deleteRepositoryCacheFile(File file) {
        if (!file.exists()) return true;
        return file.isFile() && file.delete() && !file.exists();
    }

    public List<PluginInfo> getCatalog(String repositoryUrl) {
        File file = catalogFile(repositoryUrl);
        if (!file.isFile()) return Collections.emptyList();
        try (FileInputStream input = new FileInputStream(file)) {
            JSONArray encoded = new JSONArray(new String(readLimited(input, MAX_JSON_BYTES), java.nio.charset.StandardCharsets.UTF_8));
            RepositoryCatalogLimits.checkCatalogRecords(encoded.length());
            ArrayList<PluginInfo> result = new ArrayList<>();
            for (int index = 0; index < encoded.length(); index++) {
                JSONObject item = encoded.optJSONObject(index);
                if (item == null) continue;
                PluginInfo plugin = PluginInfo.fromJson(item);
                if (plugin != null) result.add(plugin);
            }
            return Collections.unmodifiableList(result);
        } catch (IOException | JSONException ignored) {
            return Collections.emptyList();
        }
    }

    public List<InstalledPlugin> getInstalledPlugins() {
        return getInstalledPlugins(false);
    }

    /** Returns installed packages in the order committed to the persisted package list. */
    public List<InstalledPlugin> getInstalledPluginsInStoredOrder() {
        return getInstalledPlugins(true);
    }

    private List<InstalledPlugin> getInstalledPlugins(boolean preserveStoredOrder) {
        JSONArray encoded;
        try {
            encoded = new JSONArray(preferences.getString(INSTALLED_KEY, "[]"));
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }
        ArrayList<InstalledPlugin> result = new ArrayList<>();
        for (int index = 0; index < encoded.length(); index++) {
            JSONObject item = encoded.optJSONObject(index);
            if (item == null) continue;
            InstalledPlugin plugin = InstalledPlugin.fromJson(item, pluginFile(item.optString("id", "")));
            if (plugin != null) result.add(plugin);
        }
        if (!preserveStoredOrder) {
            Collections.sort(result, (left, right) -> left.name.toLowerCase(Locale.ROOT)
                    .compareTo(right.name.toLowerCase(Locale.ROOT)));
        }
        return Collections.unmodifiableList(result);
    }

    public InstalledPlugin getInstalledPlugin(String pluginId) {
        if (pluginId == null) return null;
        for (InstalledPlugin plugin : getInstalledPlugins()) {
            if (plugin.id.equals(pluginId)) return plugin;
        }
        return null;
    }

    /** Downloads privately and commits package bytes plus installed metadata as a recoverable transaction. */
    public InstalledPlugin installPlugin(PluginInfo plugin) throws IOException {
        return installPlugin(plugin, null, null);
    }

    /** Downloads a package under a controller operation ID; cancellation is accepted only before commit starts. */
    public InstalledPlugin installPlugin(PluginInfo plugin, String operationId,
            PackageInstallListener phaseListener) throws IOException {
        if (plugin == null) throw new IOException("Plugin metadata is unavailable.");
        if (!isStorageRecoveryReady()) {
            throw new IOException("A previous plugin package transaction could not be recovered. Install, update, and uninstall are blocked. Restart Orbit to recover before retrying.");
        }
        String repositoryUrl = canonicalize(plugin.repositoryUrl);
        if (repositoryUrl == null) throw new IOException("The plugin's repository address is invalid.");
        synchronized (lock) {
            if (!isRepositoryRegisteredLocked(repositoryUrl)) {
                throw new IOException("This plugin's repository was removed before installation began.");
            }
        }
        URL downloadUrl = parseWebUrl(plugin.url);
        if (downloadUrl == null) throw new IOException("Plugin download URL is invalid.");
        File destination = pluginFile(plugin.id);
        File temporary;
        ActivePackageDownload activeDownload;
        synchronized (lock) {
            if (operationId != null && activePackageDownloads.containsKey(operationId)) {
                throw new IOException("This package download operation is already active.");
            }
            if (!pluginDir.isDirectory() && !pluginDir.mkdirs()) {
                throw new IOException("Orbit could not create its private plugin folder.");
            }
            temporary = File.createTempFile("cs3-", ".staging", pluginDir);
            ACTIVE_STAGING_PATHS.add(temporary.getAbsolutePath());
            String key = operationId == null ? temporary.getAbsolutePath() : operationId;
            activeDownload = new ActivePackageDownload(key, temporary);
            activePackageDownloads.put(key, activeDownload);
        }
        boolean transactionRecorded = false;
        boolean uncertainMetadataCommit = false;
        try {
            notifyPackageDownloadStarted(phaseListener);
            DownloadResult download = downloadToStaging(downloadUrl, temporary, plugin.expectedSize, activeDownload);
            throwIfPackageDownloadCancelled(activeDownload);
            if (plugin.expectedSha256 != null && !plugin.expectedSha256.equals(download.sha256)) {
                throw new IOException("SHA-256 mismatch. The repository metadata and downloaded package do not match.");
            }
            PluginManifest manifest = validatePluginArchive(temporary);
            beginPackageCommit(activeDownload, phaseListener);
            InstalledPlugin installed = new InstalledPlugin(
                    plugin.id, plugin.repositoryUrl, plugin.name, plugin.internalName,
                    plugin.url, plugin.version, download.sha256, plugin.expectedSha256 != null, manifest.pluginClassName,
                    manifest.requiresResources, destination.getAbsolutePath(), null, System.currentTimeMillis());
            // Run plugin cleanup outside the storage lock: beforeUnload is plugin code
            // and must not be able to hold Orbit's package-transaction monitor.
            if (!deactivatePluginBeforeArtifactChange(plugin.id)) {
                throw new IOException("Orbit could not finish unregistering this plugin. Package replacement is blocked until cleanup succeeds.");
            }
            synchronized (lock) {
                if (!ensurePackageStorageReadyLocked()) {
                    throw new IOException("A previous plugin package transaction could not be recovered. Install, update, and uninstall are blocked. Restart Orbit to recover before retrying.");
                }
                if (!isRepositoryRegisteredLocked(repositoryUrl)) {
                    throw new IOException("This plugin's repository was removed before the package could be committed.");
                }
                File backup = new File(pluginDir, destination.getName() + ".txn-previous");
                if (backup.exists()) throw new IOException("A previous plugin package backup still needs recovery.");
                if (destination.exists() && !destination.isFile()) {
                    throw new IOException("Orbit's plugin destination is not a regular file.");
                }
                String previousInstalledJson = preferences.getString(INSTALLED_KEY, "[]");
                try { new JSONArray(previousInstalledJson); }
                catch (JSONException error) { throw new IOException("Saved plugin metadata is invalid; replacement is blocked.", error); }
                boolean previousFileExisted = destination.isFile();
                String previousSha256 = previousFileExisted ? hashFileQuietly(destination) : "";
                if (previousFileExisted && previousSha256.isEmpty()) {
                    throw new IOException("Orbit could not verify the currently installed package before replacement.");
                }
                JSONObject transaction = new JSONObject();
                try {
                    transaction.put("pluginId", plugin.id);
                    transaction.put("destinationName", destination.getName());
                    transaction.put("stagingName", temporary.getName());
                    transaction.put("backupName", backup.getName());
                    transaction.put("newSha256", installed.sha256);
                    transaction.put("newVersion", installed.version);
                    transaction.put("newInstalledAt", installed.installedAt);
                    transaction.put("previousFileExisted", previousFileExisted);
                    transaction.put("previousSha256", previousSha256);
                    transaction.put("previousInstalledJson", previousInstalledJson);
                } catch (JSONException error) {
                    throw new IOException("Could not describe the package install transaction.", error);
                }
                if (!preferences.edit().putString(INSTALL_TRANSACTION_KEY, transaction.toString()).commit()) {
                    storageRecoveryReady = false;
                    throw new IOException("Orbit could not confirm the plugin install recovery record. Restart Orbit to recover before activation or another package change.");
                }
                transactionRecorded = true;

                if (previousFileExisted && !destination.renameTo(backup)) {
                    throw new IOException("Orbit could not preserve the currently installed package.");
                }
                if (!temporary.renameTo(destination)) {
                    throw new IOException("Orbit could not place the validated plugin package.");
                }

                String updatedInstalledJson = withInstalledPlugin(previousInstalledJson, installed);
                if (!preferences.edit().putString(INSTALLED_KEY, updatedInstalledJson).commit()) {
                    // Keep the journal and both package versions. Startup recovery will decide from
                    // the persisted installed metadata whether the old or new version won commit.
                    uncertainMetadataCommit = true;
                    storageRecoveryReady = false;
                    throw new IOException("Orbit could not confirm the installed-plugin metadata commit. Restart Orbit to recover the package transaction.");
                }

                if (!finishInstallTransaction(transaction, temporary, backup)) {
                    storageRecoveryReady = recoverPackageStorageLocked();
                    if (!storageRecoveryReady) {
                        throw new IOException("Orbit installed the package metadata but could not finish package transaction cleanup. Restart Orbit to recover before activation or another package change.");
                    }
                }
                storageRecoveryReady = true;
                return installed;
            }
        } catch (IOException error) {
            if (transactionRecorded && !uncertainMetadataCommit) {
                synchronized (lock) {
                    // Recovery is safe here only when it can restore a coherent state. If it
                    // cannot, the journal and any needed files are deliberately left in place.
                    storageRecoveryReady = recoverPackageStorageLocked();
                    if (!storageRecoveryReady && (error.getMessage() == null
                            || !error.getMessage().contains("Restart Orbit to recover"))) {
                        throw new IOException(error.getMessage() + " Restart Orbit to recover before activation or another package change.", error);
                    }
                }
            }
            throw error;
        } finally {
            IOException stagingCleanupError = null;
            synchronized (lock) {
                if (temporary.exists() && (!transactionRecorded || !preferences.contains(INSTALL_TRANSACTION_KEY))) {
                    if (!temporary.delete() && temporary.exists()) {
                        storageRecoveryReady = false;
                        stagingCleanupError = new IOException(
                                "Orbit could not remove the staged package. Restart Orbit to recover before another package change.");
                    }
                }
                ACTIVE_STAGING_PATHS.remove(temporary.getAbsolutePath());
                activePackageDownloads.remove(activeDownload.operationId);
            }
            if (stagingCleanupError != null) throw stagingCleanupError;
        }
    }

    /** Disconnects the active package transfer. Returns false once cleanup/commit has started. */
    public boolean cancelPackageDownload(String operationId) {
        if (operationId == null) return false;
        HttpURLConnection connection;
        synchronized (lock) {
            ActivePackageDownload active = activePackageDownloads.get(operationId);
            if (active == null || active.commitStarted || active.cancellationRequested) return false;
            active.cancellationRequested = true;
            connection = active.connection;
        }
        if (connection != null) connection.disconnect();
        return true;
    }

    private void notifyPackageDownloadStarted(PackageInstallListener listener) {
        if (listener == null) return;
        try { listener.onDownloadStarted(); }
        catch (RuntimeException ignored) { }
    }

    private void beginPackageCommit(ActivePackageDownload active, PackageInstallListener listener) {
        synchronized (lock) {
            if (active.cancellationRequested) {
                throw new CancellationException("Plugin download canceled before package commit.");
            }
            active.commitStarted = true;
            active.connection = null;
        }
        if (listener != null) {
            try { listener.onCommitStarted(); }
            catch (RuntimeException ignored) { }
        }
    }

    private void throwIfPackageDownloadCancelled(ActivePackageDownload active) {
        if (active == null) return;
        synchronized (lock) {
            if (active.cancellationRequested) {
                throw new CancellationException("Plugin download canceled before package commit.");
            }
        }
    }

    /** Unloads any Orbit instance, removes the package, and clears all profile grants. */
    public boolean removePlugin(String pluginId) {
        if (pluginId == null || pluginId.isEmpty() || !isStorageRecoveryReady()) return false;
        // Keep the existing unload/beforeUnload barrier ahead of any package or metadata change.
        if (!deactivatePluginBeforeArtifactChange(pluginId)) return false;
        synchronized (lock) {
            if (!ensurePackageStorageReadyLocked()) return false;
            String previousInstalledJson = preferences.getString(INSTALLED_KEY, "[]");
            String previousEnabledJson = preferences.getString(ENABLED_KEY, "{}");
            String targetInstalledJson;
            String targetEnabledJson;
            try {
                targetInstalledJson = withoutInstalledPlugin(previousInstalledJson, pluginId);
                targetEnabledJson = withoutPluginGrants(previousEnabledJson, pluginId);
            } catch (JSONException error) {
                return false;
            }
            File destination = pluginFile(pluginId);
            if (destination.exists() && !destination.isFile()) return false;
            File backup = new File(pluginDir, destination.getName() + ".uninstall-previous");
            if (backup.exists()) return false;
            boolean previousFileExisted = destination.isFile();
            String previousSha256 = previousFileExisted ? hashFileQuietly(destination) : "";
            if (previousFileExisted && previousSha256.isEmpty()) return false;

            JSONObject transaction = new JSONObject();
            try {
                transaction.put("pluginId", pluginId);
                transaction.put("destinationName", destination.getName());
                transaction.put("backupName", backup.getName());
                transaction.put("previousFileExisted", previousFileExisted);
                transaction.put("previousSha256", previousSha256);
                transaction.put("previousInstalledJson", previousInstalledJson);
                transaction.put("previousEnabledJson", previousEnabledJson);
                transaction.put("targetInstalledJson", targetInstalledJson);
                transaction.put("targetEnabledJson", targetEnabledJson);
            } catch (JSONException error) {
                return false;
            }
            if (!preferences.edit().putString(UNINSTALL_TRANSACTION_KEY, transaction.toString()).commit()) {
                storageRecoveryReady = false;
                return false;
            }

            if (previousFileExisted && !destination.renameTo(backup)) {
                storageRecoveryReady = recoverPackageStorageLocked();
                return storageRecoveryReady && isPluginRemovedStateLocked(pluginId);
            }
            boolean metadataCommitted = preferences.edit()
                    .putString(INSTALLED_KEY, targetInstalledJson)
                    .putString(ENABLED_KEY, targetEnabledJson)
                    .commit();
            if (!metadataCommitted || !finishUninstallTransaction(destination, backup)) {
                storageRecoveryReady = recoverPackageStorageLocked();
                return storageRecoveryReady && isPluginRemovedStateLocked(pluginId);
            }
            storageRecoveryReady = true;
        }
        return true;
    }

    public boolean isEnabledForProfile(String profileId, String pluginId) {
        return getEnabledPluginIds(profileId).contains(pluginId);
    }

    public Set<String> getEnabledPluginIds(String profileId) {
        if (profileId == null || profileId.isEmpty() || profileGrantJournal.hasPending()) {
            return Collections.emptySet();
        }
        JSONObject all;
        try { all = new JSONObject(preferences.getString(ENABLED_KEY, "{}")); }
        catch (JSONException ignored) { return Collections.emptySet(); }
        JSONArray ids = all.optJSONArray(profileId);
        if (ids == null) return Collections.emptySet();
        HashSet<String> result = new HashSet<>();
        for (int index = 0; index < ids.length(); index++) {
            String id = ids.optString(index, "");
            if (!id.isEmpty() && getInstalledPlugin(id) != null) result.add(id);
        }
        return Collections.unmodifiableSet(result);
    }

    @FunctionalInterface
    interface ProfileGrantWriter {
        boolean write(String encodedGrants);
    }

    static ProfileGrantUpdateOutcome commitProfileGrantUpdate(
            String previousEncoded, String updatedEncoded, ProfileGrantWriter writer,
            ProfileGrantRecoveryJournal journal) {
        boolean journalSaved;
        try { journalSaved = journal.writePrevious(previousEncoded); }
        catch (RuntimeException failure) { journalSaved = false; }
        if (!journalSaved) return ProfileGrantUpdateOutcome.PREPARE_FAILED;

        boolean updateSaved = writeProfileGrantValue(writer, updatedEncoded);
        if (updateSaved && clearProfileGrantJournal(journal)) {
            return ProfileGrantUpdateOutcome.COMMITTED;
        }

        // SharedPreferences updates memory before disk I/O and false can follow a write.
        // Keep the durable journal until the prior grant is restored and confirmed.
        boolean priorRestored = writeProfileGrantValue(writer, previousEncoded);
        if (priorRestored && clearProfileGrantJournal(journal)) {
            return ProfileGrantUpdateOutcome.ROLLED_BACK;
        }
        return ProfileGrantUpdateOutcome.RECOVERY_REQUIRED;
    }

    static boolean recoverProfileGrantUpdate(ProfileGrantWriter writer,
                                             ProfileGrantRecoveryJournal journal) {
        boolean pending;
        try { pending = journal.hasPending(); }
        catch (RuntimeException failure) { return false; }
        if (!pending) return true;
        String previous;
        try { previous = journal.readPrevious(); }
        catch (RuntimeException failure) { return false; }
        return previous != null && writeProfileGrantValue(writer, previous)
                && clearProfileGrantJournal(journal);
    }

    private static boolean writeProfileGrantValue(ProfileGrantWriter writer, String encodedGrants) {
        try { return writer.write(encodedGrants); }
        catch (RuntimeException failure) { return false; }
    }

    private static boolean clearProfileGrantJournal(ProfileGrantRecoveryJournal journal) {
        try { return journal.clear(); }
        catch (RuntimeException failure) { return false; }
    }

    private boolean recoverProfileGrantJournalLocked() {
        return recoverProfileGrantUpdate(
                encoded -> preferences.edit().putString(ENABLED_KEY, encoded).commit(),
                profileGrantJournal);
    }

    /** Persist profile grants synchronously because they gate plugin runtime restoration and cleanup. */
    public boolean setEnabledForProfile(String profileId, String pluginId, boolean enabled) {
        if (profileId == null || profileId.isEmpty() || pluginId == null || getInstalledPlugin(pluginId) == null) return false;
        if (!isStorageRecoveryReady()) return false;
        synchronized (lock) {
            if (!ensurePackageStorageReadyLocked()) return false;
            if (getInstalledPlugin(pluginId) == null) return false;
            String previousEncoded = preferences.getString(ENABLED_KEY, "{}");
            JSONObject all;
            try { all = new JSONObject(previousEncoded); }
            catch (JSONException ignored) { all = new JSONObject(); }
            JSONArray previous = all.optJSONArray(profileId);
            LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
            if (previous != null) {
                for (int index = 0; index < previous.length(); index++) {
                    String id = previous.optString(index, "");
                    if (!id.isEmpty()) ids.put(id, true);
                }
            }
            if (enabled) ids.put(pluginId, true);
            else ids.remove(pluginId);
            JSONArray updated = new JSONArray();
            for (String id : ids.keySet()) updated.put(id);
            try { all.put(profileId, updated); }
            catch (JSONException ignored) { return false; }
            ProfileGrantUpdateOutcome outcome = commitProfileGrantUpdate(
                    previousEncoded, all.toString(),
                    encoded -> preferences.edit().putString(ENABLED_KEY, encoded).commit(),
                    profileGrantJournal);
            if (outcome == ProfileGrantUpdateOutcome.RECOVERY_REQUIRED
                    || profileGrantJournal.hasPending()) {
                storageRecoveryReady = false;
            }
            return outcome == ProfileGrantUpdateOutcome.COMMITTED;
        }
    }

    public void recordActivationError(String pluginId, String error) {
        if (pluginId == null) return;
        if (!isStorageRecoveryReady()) return;
        synchronized (lock) {
            if (!ensurePackageStorageReadyLocked()) return;
            JSONArray current;
            try { current = new JSONArray(preferences.getString(INSTALLED_KEY, "[]")); }
            catch (JSONException ignored) { return; }
            JSONArray updated = new JSONArray();
            for (int index = 0; index < current.length(); index++) {
                JSONObject item = current.optJSONObject(index);
                if (item == null) continue;
                if (pluginId.equals(item.optString("id", ""))) {
                    try { item.put("activationError", error == null ? JSONObject.NULL : error); }
                    catch (JSONException ignored) { }
                }
                updated.put(item);
            }
            preferences.edit().putString(INSTALLED_KEY, updated.toString()).apply();
        }
    }

    public void clearActivationError(String pluginId) {
        if (pluginId == null) return;
        recordActivationError(pluginId, null);
    }

    private String resolveRepositoryInput(String rawInput) throws IOException {
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isEmpty()) throw new IOException("Enter a repository URL or cloudstreamrepo link.");
        String lower = input.toLowerCase(Locale.ROOT);
        if (com.auras.orbit.BuildConfig.DEBUG && "megarepo".equals(lower)) {
            return "https://raw.githubusercontent.com/self-similarity/MegaRepo/builds/repo.json";
        }
        if (com.auras.orbit.BuildConfig.DEBUG && "phisherrepo".equals(lower)) {
            return "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json";
        }
        if (lower.startsWith("cloudstreamrepo://")) {
            input = input.substring("cloudstreamrepo://".length());
            if (!input.matches("(?i)^https?://.*")) input = "https://" + input;
            return canonicalRequired(input);
        }
        if (lower.startsWith("https://cs.repo/")) {
            input = input.substring("https://cs.repo/".length());
            if (input.startsWith("?")) input = input.substring(1);
            if (!input.matches("(?i)^https?://.*")) input = "https://" + input;
            return canonicalRequired(input);
        }
        if (input.matches("^[A-Za-z0-9!_-]+$")) return canonicalRequired(resolveShortcode(input));
        return canonicalRequired(input);
    }

    private String resolveShortcode(String shortcut) throws IOException {
        String service = shortcut.startsWith("!")
                ? "https://py.md/" + shortcut.substring(1)
                : "https://cutt.ly/" + shortcut;
        String current = service;
        for (int hops = 0; hops < 5; hops++) {
            URL url = parseWebUrl(current);
            if (url == null) throw new IOException("Shortcut service returned an invalid URL.");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            int code;
            String location;
            try {
                code = connection.getResponseCode();
                location = connection.getHeaderField("Location");
            }
            finally { connection.disconnect(); }
            if (code >= 300 && code < 400 && location != null) {
                current = new URL(url, location).toString();
                if (url.getProtocol().equalsIgnoreCase("https") && current.toLowerCase(Locale.ROOT).startsWith("http://")) {
                    throw new IOException("Shortcut attempted an HTTPS-to-HTTP downgrade.");
                }
                if (current.contains("/404") || current.matches("https://(cutt\\.ly|py\\.md)/?$")) {
                    throw new IOException("The repository shortcut was not found.");
                }
                if (!current.startsWith("https://cutt.ly/") && !current.startsWith("https://py.md/")) {
                    return canonicalRequired(current);
                }
            } else {
                throw new IOException("The repository shortcut did not resolve (HTTP " + code + ").");
            }
        }
        throw new IOException("The repository shortcut redirected too many times.");
    }

    private RepositoryDocument readRepositoryDescriptor(String url) throws IOException {
        HttpDocument document = fetchDocument(url, MAX_JSON_BYTES);
        JSONObject json;
        try { json = new JSONObject(document.body); }
        catch (JSONException error) { throw new IOException("Repository descriptor is not a JSON object.", error); }
        JSONArray lists = json.optJSONArray("pluginLists");
        if (lists == null) throw new IOException("Repository descriptor has no pluginLists array.");
        RepositoryCatalogLimits.checkRepositoryLists(lists.length());
        ArrayList<String> pluginListUrls = new ArrayList<>();
        for (int index = 0; index < lists.length(); index++) {
            String item = lists.optString(index, "").trim();
            if (item.isEmpty()) continue;
            String resolved;
            try { resolved = new URL(new URL(document.finalUrl), item).toString(); }
            catch (Exception error) { throw new IOException("Repository contains an invalid plugin list URL.", error); }
            String canonical = canonicalize(resolved);
            if (canonical == null) throw new IOException("Repository plugin list must use HTTP or HTTPS.");
            if (!pluginListUrls.contains(canonical)) pluginListUrls.add(canonical);
        }
        if (pluginListUrls.isEmpty()) throw new IOException("Repository descriptor has no usable plugin list URLs.");
        return new RepositoryDocument(
                canonicalize(url),
                document.finalUrl,
                nonEmpty(json.optString("name", ""), hostLabel(document.finalUrl)),
                json.optString("description", ""), json.optString("iconUrl", ""),
                json.optInt("manifestVersion", 1), pluginListUrls);
    }

    private CatalogResult readPluginCatalog(RepositoryDocument descriptor) {
        LinkedHashMap<String, PluginInfo> plugins = new LinkedHashMap<>();
        int successful = 0;
        int failed = 0;
        for (String listUrl : descriptor.pluginListUrls) {
            try {
                HttpDocument document = fetchDocument(listUrl, MAX_JSON_BYTES);
                JSONArray array = new JSONArray(document.body);
                RepositoryCatalogLimits.checkListRecords(array.length());
                // Validate an entire list's resource footprint before committing any of
                // its records. A hostile oversized list must not poison a partial catalog.
                LinkedHashMap<String, PluginInfo> listPlugins = new LinkedHashMap<>();
                for (int index = 0; index < array.length(); index++) {
                    JSONObject item = array.optJSONObject(index);
                    if (item == null) continue;
                    try {
                        PluginInfo plugin = PluginInfo.fromRemote(item, descriptor.repositoryUrl, document.finalUrl);
                        if (plugin != null) listPlugins.put(plugin.id, plugin);
                    } catch (IOException ignored) {
                        // One malformed record does not hide the rest of a published catalog.
                    }
                }
                int uniqueNewRecords = 0;
                for (String id : listPlugins.keySet()) {
                    if (!plugins.containsKey(id)) uniqueNewRecords++;
                }
                RepositoryCatalogLimits.checkCatalogRecords(plugins.size() + uniqueNewRecords);
                plugins.putAll(listPlugins);
                successful++;
            } catch (Exception ignored) {
                failed++;
            }
        }
        ArrayList<PluginInfo> result = new ArrayList<>(plugins.values());
        Collections.sort(result, (left, right) -> left.name.toLowerCase(Locale.ROOT)
                .compareTo(right.name.toLowerCase(Locale.ROOT)));
        return new CatalogResult(result, successful, failed);
    }

    private HttpDocument fetchDocument(String url, long maximumBytes) throws IOException {
        URL parsed = parseWebUrl(url);
        if (parsed == null) throw new IOException("Only HTTP(S) URLs can be retrieved.");
        HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json, application/octet-stream, */*");
        connection.setRequestProperty("User-Agent", "AurasOrbit/0.1 CS3 repository manager");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Server returned HTTP " + status + ".");
            if (parsed.getProtocol().equalsIgnoreCase("https")
                    && !"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new IOException("Repository request was redirected from HTTPS to HTTP.");
            }
            long contentLength = connection.getContentLength();
            if (contentLength > maximumBytes) throw new IOException("Remote file exceeds the allowed size.");
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = readLimited(input, maximumBytes);
                return new HttpDocument(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), connection.getURL().toString());
            }
        } finally {
            connection.disconnect();
        }
    }

    private DownloadResult downloadToStaging(URL url, File temporary, long expectedSize,
            ActivePackageDownload active) throws IOException {
        if (expectedSize > MAX_PLUGIN_BYTES) throw new IOException("Plugin exceeds Orbit's 512 MiB size limit.");
        throwIfPackageDownloadCancelled(active);
        MessageDigest digest = newSha256();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "AurasOrbit/0.1 CS3 plugin installer");
        synchronized (lock) {
            if (active.cancellationRequested) {
                connection.disconnect();
                throw new CancellationException("Plugin download canceled before package commit.");
            }
            active.connection = connection;
        }
        long total = 0;
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Plugin server returned HTTP " + status + ".");
            if (url.getProtocol().equalsIgnoreCase("https")
                    && !"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new IOException("Plugin download was redirected from HTTPS to HTTP.");
            }
            long contentLength = connection.getContentLength();
            if (contentLength > MAX_PLUGIN_BYTES) throw new IOException("Plugin exceeds Orbit's 512 MiB size limit.");
            if (expectedSize > 0 && contentLength >= 0 && expectedSize != contentLength) {
                throw new IOException("The server's file size does not match the repository metadata.");
            }
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                if (Build.VERSION.SDK_INT >= 34 && !temporary.setReadOnly()) {
                    throw new IOException("Orbit could not make the staging package read-only before writing.");
                }
                byte[] buffer = new byte[32768];
                int count;
                while (true) {
                    throwIfPackageDownloadCancelled(active);
                    count = input.read(buffer);
                    if (count == -1) break;
                    if (count == 0) throw new IOException("Plugin stream made no progress.");
                    total += count;
                    if (total > MAX_PLUGIN_BYTES) throw new IOException("Plugin exceeds Orbit's 512 MiB size limit.");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
                output.flush();
                output.getFD().sync();
            }
            throwIfPackageDownloadCancelled(active);
        } catch (IOException error) {
            if (isPackageDownloadCancellationRequested(active)) {
                CancellationException cancelled = new CancellationException(
                        "Plugin download canceled before package commit.");
                cancelled.initCause(error);
                throw cancelled;
            }
            throw error;
        } finally {
            connection.disconnect();
            synchronized (lock) {
                if (active.connection == connection) active.connection = null;
            }
        }
        if (total == 0) throw new IOException("The plugin server returned an empty package.");
        if (expectedSize > 0 && total != expectedSize) throw new IOException("Downloaded plugin size does not match repository metadata.");
        if (Build.VERSION.SDK_INT < 34 && !temporary.setReadOnly()) throw new IOException("Orbit could not make the plugin package read-only.");
        return new DownloadResult(total, hex(digest.digest()));
    }

    private boolean isPackageDownloadCancellationRequested(ActivePackageDownload active) {
        if (active == null) return false;
        synchronized (lock) { return active.cancellationRequested; }
    }

    private PluginManifest validatePluginArchive(File file) throws IOException {
        try (ZipFile archive = new ZipFile(file)) {
            ZipEntry manifest = archive.getEntry("manifest.json");
            if (manifest == null || manifest.getSize() > 1024L * 1024L) {
                throw new IOException("CS3 package has no valid root manifest.json.");
            }
            if (archive.getEntry("classes.dex") == null) throw new IOException("CS3 package has no root classes.dex.");
            String json;
            try (InputStream input = archive.getInputStream(manifest)) {
                json = new String(readLimited(input, 1024L * 1024L), java.nio.charset.StandardCharsets.UTF_8);
            }
            JSONObject parsed = new JSONObject(json);
            String className = parsed.optString("pluginClassName", "").trim();
            if (!className.matches("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+$")) {
                throw new IOException("CS3 manifest has an invalid pluginClassName.");
            }
            return new PluginManifest(className, parsed.optBoolean("requiresResources", false));
        } catch (JSONException error) {
            throw new IOException("CS3 manifest is invalid JSON.", error);
        }
    }

    private static String withInstalledPlugin(String installedJson, InstalledPlugin installed) throws IOException {
        JSONArray current;
        try { current = new JSONArray(installedJson); }
        catch (JSONException error) { throw new IOException("Saved plugin metadata is invalid JSON.", error); }
        JSONArray updated = new JSONArray();
        for (int index = 0; index < current.length(); index++) {
            JSONObject item = current.optJSONObject(index);
            if (item != null && !installed.id.equals(item.optString("id", ""))) updated.put(item);
        }
        updated.put(installed.toJson());
        return updated.toString();
    }

    private boolean saveRepositories(List<RepositoryInfo> repositories) {
        return preferences.edit().putString(REPOSITORIES_KEY, encodedRepositories(repositories)).commit();
    }

    private void persistRepositoriesAsync(List<RepositoryInfo> repositories) {
        preferences.edit().putString(REPOSITORIES_KEY, encodedRepositories(repositories)).apply();
    }

    private static String encodedRepositories(List<RepositoryInfo> repositories) {
        JSONArray encoded = new JSONArray();
        for (RepositoryInfo repository : repositories) encoded.put(repository.toJson());
        return encoded.toString();
    }

    private void saveCatalog(String repositoryUrl, List<PluginInfo> plugins) throws IOException {
        RepositoryCatalogLimits.checkCatalogRecords(plugins.size());
        if (!repositoryCacheDir.isDirectory() && !repositoryCacheDir.mkdirs()) {
            throw new IOException("Could not create the repository cache folder.");
        }
        File destination = catalogFile(repositoryUrl);
        File temporary = File.createTempFile("catalog-", ".tmp", repositoryCacheDir);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            JSONArray encoded = new JSONArray();
            for (PluginInfo plugin : plugins) encoded.put(plugin.toJson());
            output.write(encoded.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    return;
                } catch (AtomicMoveNotSupportedException ignored) { }
            }
            File backup = new File(repositoryCacheDir, destination.getName() + ".previous");
            if (backup.exists() && !backup.delete()) throw new IOException("Could not clear the previous catalog backup.");
            boolean hadDestination = destination.exists();
            if (hadDestination && !destination.renameTo(backup)) throw new IOException("Could not preserve the previous catalog cache.");
            if (!temporary.renameTo(destination)) {
                if (hadDestination) backup.renameTo(destination);
                throw new IOException("Could not store the repository catalog.");
            }
            if (backup.exists()) backup.delete();
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    /** Shared recovery gate for both package transaction types and orphan cleanup. Caller holds lock. */
    private boolean recoverPackageStorageLocked() {
        boolean hasInstall = preferences.contains(INSTALL_TRANSACTION_KEY);
        boolean hasUninstall = preferences.contains(UNINSTALL_TRANSACTION_KEY);
        if (hasInstall && hasUninstall) return false;
        if (hasInstall && !recoverInstallTransaction()) return false;
        if (hasUninstall && !recoverUninstallTransaction()) return false;
        return recoverPluginStorage();
    }

    private boolean ensurePackageStorageReadyLocked() {
        storageRecoveryReady = recoverPackageStorageLocked();
        return storageRecoveryReady;
    }

    /** Resolves an interrupted install before generic orphan/staging cleanup is allowed to run. */
    private boolean recoverInstallTransaction() {
        String encoded = preferences.getString(INSTALL_TRANSACTION_KEY, null);
        if (encoded == null || encoded.trim().isEmpty()) return true;

        JSONObject transaction;
        try { transaction = new JSONObject(encoded); }
        catch (JSONException error) { return false; }
        String pluginId = transaction.optString("pluginId", "");
        String destinationName = transaction.optString("destinationName", "");
        String stagingName = transaction.optString("stagingName", "");
        String backupName = transaction.optString("backupName", "");
        String newSha256 = transaction.optString("newSha256", "");
        String previousSha256 = transaction.optString("previousSha256", "");
        boolean previousFileExisted = transaction.optBoolean("previousFileExisted", false);
        String previousInstalledJson = transaction.optString("previousInstalledJson", "");
        if (pluginId.isEmpty() || !pluginFile(pluginId).getName().equals(destinationName)
                || !isChildFileName(stagingName) || !stagingName.startsWith("cs3-") || !stagingName.endsWith(".staging")
                || !isChildFileName(backupName) || !backupName.equals(destinationName + ".txn-previous")
                || !newSha256.matches("^[0-9a-f]{64}$")
                || (previousFileExisted && !previousSha256.matches("^[0-9a-f]{64}$"))) {
            return false;
        }
        try { new JSONArray(previousInstalledJson); }
        catch (JSONException error) { return false; }

        File destination = new File(pluginDir, destinationName);
        File staging = new File(pluginDir, stagingName);
        File backup = new File(pluginDir, backupName);
        boolean metadataCommitted = isNewInstallMetadataCurrent(transaction);
        if (metadataCommitted && newSha256.equals(hashFileQuietly(destination))) {
            return finishInstallTransaction(transaction, staging, backup);
        }
        if (metadataCommitted && newSha256.equals(hashFileQuietly(staging))) {
            if (previousFileExisted) {
                if (backup.exists()) {
                    if (!previousSha256.equals(hashFileQuietly(backup))) return rollbackInstallTransaction(
                            transaction, destination, staging, backup);
                }
                if (destination.exists()) {
                    if (!previousSha256.equals(hashFileQuietly(destination))) return false;
                    if (backup.exists()) {
                        if (!destination.delete()) return false;
                    } else if (!destination.renameTo(backup)) {
                        return false;
                    }
                } else if (!backup.exists()) {
                    return false;
                }
            } else if (backup.exists()) {
                return false;
            } else if (destination.exists()) {
                return false;
            }
            if (!staging.renameTo(destination) || !newSha256.equals(hashFileQuietly(destination))) {
                return rollbackInstallTransaction(transaction, destination, staging, backup);
            }
            return finishInstallTransaction(transaction, staging, backup);
        }
        return rollbackInstallTransaction(transaction, destination, staging, backup);
    }

    private boolean isNewInstallMetadataCurrent(JSONObject transaction) {
        String pluginId = transaction.optString("pluginId", "");
        String newSha256 = transaction.optString("newSha256", "");
        long newVersion = transaction.optLong("newVersion", Long.MIN_VALUE);
        long newInstalledAt = transaction.optLong("newInstalledAt", Long.MIN_VALUE);
        JSONArray installed;
        try { installed = new JSONArray(preferences.getString(INSTALLED_KEY, "[]")); }
        catch (JSONException error) { return false; }
        for (int index = 0; index < installed.length(); index++) {
            JSONObject item = installed.optJSONObject(index);
            if (item != null && pluginId.equals(item.optString("id", ""))) {
                return newSha256.equals(item.optString("sha256", ""))
                        && newVersion == item.optLong("version", Long.MIN_VALUE)
                        && newInstalledAt == item.optLong("installedAt", Long.MIN_VALUE);
            }
        }
        return false;
    }

    private boolean rollbackInstallTransaction(JSONObject transaction, File destination, File staging, File backup) {
        boolean previousFileExisted = transaction.optBoolean("previousFileExisted", false);
        String previousSha256 = transaction.optString("previousSha256", "");
        String newSha256 = transaction.optString("newSha256", "");
        String previousInstalledJson = transaction.optString("previousInstalledJson", "[]");

        if (previousFileExisted) {
            if (!previousSha256.equals(hashFileQuietly(destination))) {
                if (!previousSha256.equals(hashFileQuietly(backup))) return false;
                if (destination.exists() && !newSha256.equals(hashFileQuietly(destination))) return false;
                if (destination.exists() && !destination.delete()) return false;
                if (!backup.renameTo(destination)) return false;
            } else if (backup.exists()) {
                if (!previousSha256.equals(hashFileQuietly(backup))) return false;
                if (!backup.delete() && backup.exists()) return false;
            }
        } else {
            if (backup.exists()) return false;
            if (destination.exists()) {
                String destinationHash = hashFileQuietly(destination);
                if (!destinationHash.equals(newSha256)) return false;
                if (destination.exists() && !destination.delete()) return false;
            }
        }

        if (!preferences.edit().putString(INSTALLED_KEY, previousInstalledJson).commit()) return false;
        if (staging.exists() && !staging.delete() && staging.exists()) return false;
        if (backup.exists() && !backup.delete() && backup.exists()) return false;
        return preferences.edit().remove(INSTALL_TRANSACTION_KEY).commit();
    }

    private boolean finishInstallTransaction(JSONObject transaction, File staging, File backup) {
        String pluginId = transaction.optString("pluginId", "");
        String destinationName = transaction.optString("destinationName", "");
        String newSha256 = transaction.optString("newSha256", "");
        boolean previousFileExisted = transaction.optBoolean("previousFileExisted", false);
        String previousSha256 = transaction.optString("previousSha256", "");
        if (pluginId.isEmpty() || !pluginFile(pluginId).getName().equals(destinationName)
                || !newSha256.matches("^[0-9a-f]{64}$")) return false;
        File destination = new File(pluginDir, destinationName);
        if (!destination.isFile() || !newSha256.equals(hashFileQuietly(destination))) return false;
        if (backup.exists()) {
            if (!previousFileExisted || !previousSha256.equals(hashFileQuietly(backup))) return false;
        }
        if (staging.exists() && !staging.delete() && staging.exists()) return false;
        if (backup.exists() && !backup.delete() && backup.exists()) return false;
        return preferences.edit().remove(INSTALL_TRANSACTION_KEY).commit();
    }

    private boolean recoverUninstallTransaction() {
        String encoded = preferences.getString(UNINSTALL_TRANSACTION_KEY, null);
        if (encoded == null || encoded.trim().isEmpty()) return true;
        JSONObject transaction;
        try { transaction = new JSONObject(encoded); }
        catch (JSONException error) { return false; }

        String pluginId = transaction.optString("pluginId", "");
        String destinationName = transaction.optString("destinationName", "");
        String backupName = transaction.optString("backupName", "");
        boolean previousFileExisted = transaction.optBoolean("previousFileExisted", false);
        String previousSha256 = transaction.optString("previousSha256", "");
        String previousInstalledJson = transaction.optString("previousInstalledJson", "");
        String previousEnabledJson = transaction.optString("previousEnabledJson", "");
        String targetInstalledJson = transaction.optString("targetInstalledJson", "");
        String targetEnabledJson = transaction.optString("targetEnabledJson", "");
        if (pluginId.isEmpty() || !pluginFile(pluginId).getName().equals(destinationName)
                || !isChildFileName(backupName) || !backupName.equals(destinationName + ".uninstall-previous")
                || (previousFileExisted && !previousSha256.matches("^[0-9a-f]{64}$"))
                || (!previousFileExisted && !previousSha256.isEmpty())) return false;
        try {
            new JSONArray(previousInstalledJson);
            new JSONObject(previousEnabledJson);
            new JSONArray(targetInstalledJson);
            new JSONObject(targetEnabledJson);
        } catch (JSONException error) { return false; }

        File destination = new File(pluginDir, destinationName);
        File backup = new File(pluginDir, backupName);
        String currentInstalledJson = preferences.getString(INSTALLED_KEY, "[]");
        String currentEnabledJson = preferences.getString(ENABLED_KEY, "{}");
        boolean targetMetadataCurrent = targetInstalledJson.equals(currentInstalledJson)
                && targetEnabledJson.equals(currentEnabledJson);

        if (targetMetadataCurrent) {
            if (previousFileExisted) {
                if (backup.exists() && !previousSha256.equals(hashFileQuietly(backup))) return false;
                if (destination.exists()) {
                    if (!previousSha256.equals(hashFileQuietly(destination))) return false;
                    if (!destination.delete() && destination.exists()) return false;
                }
                if (backup.exists() && !backup.delete() && backup.exists()) return false;
            } else if (backup.exists() || destination.exists()) {
                // A file appeared after the transaction recorded that no prior artifact existed.
                return false;
            }
            return preferences.edit().remove(UNINSTALL_TRANSACTION_KEY).commit();
        }

        // Uninstall metadata did not commit as a complete pair; restore old bytes and both old maps.
        if (previousFileExisted) {
            if (!previousSha256.equals(hashFileQuietly(destination))) {
                if (!previousSha256.equals(hashFileQuietly(backup))) return false;
                // Uninstall never creates a replacement artifact: only an absent destination is safe to restore into.
                if (destination.exists()) return false;
                if (!backup.renameTo(destination)) return false;
            } else if (backup.exists()) {
                if (!previousSha256.equals(hashFileQuietly(backup))) return false;
                if (!backup.delete() && backup.exists()) return false;
            }
        } else if (backup.exists() || destination.exists()) {
            return false;
        }
        if (!preferences.edit()
                .putString(INSTALLED_KEY, previousInstalledJson)
                .putString(ENABLED_KEY, previousEnabledJson)
                .commit()) return false;
        if (backup.exists() && !backup.delete() && backup.exists()) return false;
        return preferences.edit().remove(UNINSTALL_TRANSACTION_KEY).commit();
    }

    private boolean finishUninstallTransaction(File destination, File backup) {
        if (destination.exists()) return false;
        if (backup.exists() && !backup.delete() && backup.exists()) return false;
        return preferences.edit().remove(UNINSTALL_TRANSACTION_KEY).commit();
    }

    private static boolean isChildFileName(String value) {
        return value != null && !value.isEmpty() && new File(value).getName().equals(value)
                && !value.equals(".") && !value.equals("..");
    }

    private boolean isRepositoryRegisteredLocked(String canonicalUrl) {
        for (RepositoryInfo repository : getRepositories()) {
            if (repository.url.equals(canonicalUrl)) return true;
        }
        return false;
    }

    private static String withoutInstalledPlugin(String encoded, String pluginId) throws JSONException {
        JSONArray source = new JSONArray(encoded);
        JSONArray kept = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            Object value = source.get(index);
            JSONObject item = source.optJSONObject(index);
            if (item == null || !pluginId.equals(item.optString("id", ""))) kept.put(value);
        }
        return kept.toString();
    }

    private static String withoutPluginGrants(String encoded, String pluginId) throws JSONException {
        JSONObject source = new JSONObject(encoded);
        JSONObject updated = new JSONObject();
        java.util.Iterator<String> profiles = source.keys();
        while (profiles.hasNext()) {
            String profileId = profiles.next();
            Object value = source.get(profileId);
            JSONArray grants = source.optJSONArray(profileId);
            if (grants == null) {
                updated.put(profileId, value);
                continue;
            }
            JSONArray kept = new JSONArray();
            for (int index = 0; index < grants.length(); index++) {
                Object grant = grants.get(index);
                if (!pluginId.equals(grants.optString(index, ""))) kept.put(grant);
            }
            updated.put(profileId, kept);
        }
        return updated.toString();
    }

    private boolean isPluginRemovedStateLocked(String pluginId) {
        if (pluginFile(pluginId).exists()) return false;
        try {
            JSONArray installed = new JSONArray(preferences.getString(INSTALLED_KEY, "[]"));
            for (int index = 0; index < installed.length(); index++) {
                JSONObject item = installed.optJSONObject(index);
                if (item != null && pluginId.equals(item.optString("id", ""))) return false;
            }
            JSONObject profiles = new JSONObject(preferences.getString(ENABLED_KEY, "{}"));
            java.util.Iterator<String> profileIds = profiles.keys();
            while (profileIds.hasNext()) {
                JSONArray grants = profiles.optJSONArray(profileIds.next());
                if (grants == null) continue;
                for (int index = 0; index < grants.length(); index++) {
                    if (pluginId.equals(grants.optString(index, ""))) return false;
                }
            }
            return true;
        } catch (JSONException error) {
            return false;
        }
    }

    private boolean recoverPluginStorage() {
        JSONArray installed;
        try { installed = new JSONArray(preferences.getString(INSTALLED_KEY, "[]")); }
        catch (JSONException ignored) { return false; }
        if (!pluginDir.exists()) return installed.length() == 0;
        if (!pluginDir.isDirectory()) return false;
        LinkedHashMap<String, String> expectedHashes = new LinkedHashMap<>();
        HashSet<String> expectedNames = new HashSet<>();
        for (int index = 0; index < installed.length(); index++) {
            JSONObject item = installed.optJSONObject(index);
            if (item == null) return false;
            String id = item.optString("id", "");
            String sha256 = item.optString("sha256", "");
            if (id.isEmpty() || !sha256.matches("^[0-9a-f]{64}$")) return false;
            String filename = pluginFile(id).getName();
            if (!expectedNames.add(filename)) return false;
            expectedHashes.put(filename, sha256);
        }
        File[] files = pluginDir.listFiles();
        if (files == null) return false;
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".staging")) {
                // A live download is protected in-process; after a crash the set is empty and stale staging is reclaimed.
                if (ACTIVE_STAGING_PATHS.contains(file.getAbsolutePath())) continue;
                if (!file.delete() && file.exists()) return false;
                continue;
            }
            if (name.endsWith(".uninstall-previous")) return false;
            if (name.endsWith(".txn-previous")) {
                String destinationName = name.substring(0, name.length() - ".txn-previous".length());
                File destination = new File(pluginDir, destinationName);
                String expectedHash = expectedHashes.get(destinationName);
                if (expectedHash == null || expectedHash.equals(hashFileQuietly(destination))) {
                    if (!file.delete() && file.exists()) return false;
                } else if (expectedHash.equals(hashFileQuietly(file))) {
                    if (destination.exists() && !destination.delete()) return false;
                    if (!file.renameTo(destination)) return false;
                } else return false;
                continue;
            }
            if (name.endsWith(".previous")) {
                String destinationName = name.substring(0, name.length() - ".previous".length());
                File destination = new File(pluginDir, destinationName);
                String expectedHash = expectedHashes.get(destinationName);
                if (expectedHash == null || expectedHash.isEmpty()) {
                    if (!destination.exists()) {
                        if (!file.renameTo(destination)) return false;
                    } else if (!file.delete() && file.exists()) return false;
                } else if (destination.exists() && expectedHash.equals(hashFileQuietly(destination))) {
                    if (!file.delete() && file.exists()) return false;
                } else if (expectedHash.equals(hashFileQuietly(file))) {
                    if (destination.exists() && !destination.delete()) return false;
                    if (!file.renameTo(destination)) return false;
                } else if (!destination.exists()) {
                    return false;
                } else {
                    return false;
                }
                continue;
            }
            if (name.endsWith(".cs3") && !expectedNames.contains(name)
                    && !file.delete() && file.exists()) return false;
        }
        for (Map.Entry<String, String> artifact : expectedHashes.entrySet()) {
            File file = new File(pluginDir, artifact.getKey());
            if (!file.isFile() || !artifact.getValue().equals(hashFileQuietly(file))) return false;
        }
        return true;
    }

    private static String hashFileQuietly(File file) {
        try {
            MessageDigest digest = newSha256();
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) throw new IOException("Input stream made no progress.");
                    digest.update(buffer, 0, count);
                }
            }
            return hex(digest.digest());
        } catch (IOException ignored) { return ""; }
    }

    private boolean deactivatePluginBeforeArtifactChange(String pluginId) {
        if (pluginId == null) return false;
        if (appContext instanceof OrbitApplication) {
            OrbitApplication application = (OrbitApplication) appContext;
            application.getCS3PluginRuntime().deactivate(pluginId);
            return !application.getCS3PluginRuntime().isLoaded(pluginId)
                    && !OrbitCs3HostServices.get().hasOutstandingRegistrations(pluginId);
        } else {
            try { OrbitCs3HostServices.get().unloadPlugin(pluginId); }
            catch (Throwable ignored) { return false; }
            return !OrbitCs3HostServices.get().hasOutstandingRegistrations(pluginId);
        }
    }

    private File catalogFile(String repositoryUrl) {
        return new File(repositoryCacheDir, sha256String(canonicalize(repositoryUrl) == null
                ? repositoryUrl : canonicalize(repositoryUrl)) + ".json");
    }

    private File pluginFile(String pluginId) {
        String idHash = sha256String(pluginId == null ? "missing" : pluginId);
        return new File(pluginDir, "plugin-" + idHash.substring(0, Math.min(20, idHash.length())) + ".cs3");
    }

    private static String canonicalRequired(String url) throws IOException {
        String canonical = canonicalize(url);
        if (canonical == null) throw new IOException("Repository URL must be a valid HTTP(S) address.");
        return canonical;
    }

    public static String canonicalize(String raw) {
        if (raw == null) return null;
        try {
            URI uri = new URI(raw.trim()).normalize();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || uri.getUserInfo() != null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
            host = host.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (port == 0 || port > 65535) return null;
            if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) port = -1;
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            StringBuilder result = new StringBuilder(scheme).append("://").append(host);
            if (port >= 0) result.append(':').append(port);
            result.append(path);
            if (uri.getRawQuery() != null) result.append('?').append(uri.getRawQuery());
            return result.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static URL parseWebUrl(String value) {
        try {
            URL url = new URL(value);
            String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
            if (!"http".equals(protocol) && !"https".equals(protocol)) return null;
            if (url.getHost() == null || url.getHost().isEmpty() || url.getUserInfo() != null) return null;
            return url;
        } catch (Exception ignored) { return null; }
    }

    private static byte[] readLimited(InputStream input, long maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) throw new IOException("Input stream made no progress.");
            total += count;
            if (total > maxBytes) throw new IOException("Remote document exceeds the allowed size.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static MessageDigest newSha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IOException("SHA-256 is unavailable.", error); }
    }

    private static String sha256String(String value) {
        try {
            MessageDigest digest = newSha256();
            digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return hex(digest.digest());
        } catch (IOException ignored) { return ""; }
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return output.toString();
    }

    private static String normalizeHash(String hash) throws IOException {
        if (hash == null || hash.trim().isEmpty()) return null;
        String normalized = hash.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("sha256-")) normalized = normalized.substring(7);
        else if (normalized.startsWith("sha256:")) normalized = normalized.substring(7);
        if (!normalized.matches("^[0-9a-f]{64}$")) throw new IOException("Repository contains an unsupported or malformed fileHash.");
        return normalized;
    }

    private static String hostLabel(String url) {
        URL parsed = parseWebUrl(url);
        return parsed == null ? "CloudStream repository" : parsed.getHost();
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String safeSlug(String value) {
        String slug = value == null ? "plugin" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (slug.isEmpty()) slug = "plugin";
        return slug.length() > 48 ? slug.substring(0, 48) : slug;
    }

    public static final class AddRepositoryResult {
        public final RepositoryInfo repository;
        public final int pluginCount;
        public final boolean alreadyPresent;
        AddRepositoryResult(RepositoryInfo repository, int pluginCount, boolean alreadyPresent) {
            this.repository = repository; this.pluginCount = pluginCount; this.alreadyPresent = alreadyPresent;
        }
    }

    public static final class RepositoryInfo {
        public final String url, name, description, iconUrl, status;
        public final int pluginListCount;
        public final long lastRefreshed;
        RepositoryInfo(String url, String name, String description, String iconUrl,
                       int pluginListCount, long lastRefreshed, String status) {
            this.url = url; this.name = name; this.description = description; this.iconUrl = iconUrl;
            this.pluginListCount = pluginListCount; this.lastRefreshed = lastRefreshed; this.status = status;
        }
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("url", url); json.put("name", name); json.put("description", description);
                json.put("iconUrl", iconUrl); json.put("pluginListCount", pluginListCount);
                json.put("lastRefreshed", lastRefreshed); json.put("status", status);
            } catch (JSONException ignored) { }
            return json;
        }
        static RepositoryInfo fromJson(JSONObject json) {
            String url = canonicalize(json.optString("url", ""));
            if (url == null) return null;
            return new RepositoryInfo(url, json.optString("name", hostLabel(url)),
                    json.optString("description", ""), json.optString("iconUrl", ""),
                    json.optInt("pluginListCount", 0), json.optLong("lastRefreshed", 0),
                    json.optString("status", "Saved"));
        }
    }

    public static final class PluginInfo {
        public final String id, repositoryUrl, name, internalName, url, description, authors, language;
        public final String hash, expectedSha256;
        public final long version, expectedSize;
        public final int status;
        PluginInfo(String id, String repositoryUrl, String name, String internalName, String url,
                   String description, String authors, String language, String hash, long version,
                   long expectedSize, int status) {
            this.id = id; this.repositoryUrl = repositoryUrl; this.name = name; this.internalName = internalName;
            this.url = url; this.description = description; this.authors = authors; this.language = language;
            this.hash = hash; this.expectedSha256 = hash; this.version = version;
            this.expectedSize = expectedSize; this.status = status;
        }
        static PluginInfo fromRemote(JSONObject json, String repoUrl, String listUrl) throws IOException {
            String name = json.optString("name", "").trim();
            String internalName = json.optString("internalName", name).trim();
            String rawUrl = json.optString("url", "").trim();
            if (name.isEmpty() || internalName.isEmpty() || rawUrl.isEmpty()) return null;
            String resolved;
            try { resolved = new URL(new URL(listUrl), rawUrl).toString(); }
            catch (Exception error) { throw new IOException("Plugin record contains an invalid URL.", error); }
            resolved = canonicalize(resolved);
            if (resolved == null) throw new IOException("Plugin URL must use HTTP or HTTPS.");
            long version = json.optLong("version", 0);
            long size = json.optLong("fileSize", 0);
            if (size < 0 || size > MAX_PLUGIN_BYTES) throw new IOException("Plugin metadata contains an invalid fileSize.");
            String rawHash = json.optString("fileHash", "");
            String hash = normalizeHash(rawHash);
            String canonicalRepo = canonicalize(repoUrl);
            String id = canonicalRepo + "|" + internalName.toLowerCase(Locale.ROOT);
            JSONArray authorArray = json.optJSONArray("authors");
            ArrayList<String> authorNames = new ArrayList<>();
            if (authorArray != null) for (int index = 0; index < authorArray.length(); index++) {
                String author = authorArray.optString(index, "").trim();
                if (!author.isEmpty()) authorNames.add(author);
            }
            return new PluginInfo(id, canonicalRepo, name, internalName, resolved,
                    json.optString("description", ""), join(authorNames, ", "),
                    json.optString("language", ""), hash, version, size, json.optInt("status", 1));
        }
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id); json.put("repositoryUrl", repositoryUrl); json.put("name", name);
                json.put("internalName", internalName); json.put("url", url); json.put("description", description);
                json.put("authors", authors); json.put("language", language); json.put("hash", hash);
                json.put("version", version); json.put("expectedSize", expectedSize); json.put("status", status);
            } catch (JSONException ignored) { }
            return json;
        }
        static PluginInfo fromJson(JSONObject json) {
            String id = json.optString("id", "");
            String repo = canonicalize(json.optString("repositoryUrl", ""));
            String url = canonicalize(json.optString("url", ""));
            String name = json.optString("name", "");
            String internal = json.optString("internalName", name);
            String hash = json.isNull("hash") ? null : json.optString("hash", null);
            if (id.isEmpty() || repo == null || url == null || name.isEmpty() || internal.isEmpty()) return null;
            return new PluginInfo(id, repo, name, internal, url, json.optString("description", ""),
                    json.optString("authors", ""), json.optString("language", ""), hash,
                    json.optLong("version", 0), json.optLong("expectedSize", 0), json.optInt("status", 1));
        }
        private static String join(List<String> strings, String separator) {
            StringBuilder output = new StringBuilder();
            for (String item : strings) { if (output.length() > 0) output.append(separator); output.append(item); }
            return output.toString();
        }
    }

    public static final class InstalledPlugin {
        public final String id, repositoryUrl, name, internalName, downloadUrl, sha256, pluginClassName, filePath, activationError;
        public final long version, installedAt;
        public final boolean requiresResources, hashWasPublished;
        InstalledPlugin(String id, String repositoryUrl, String name, String internalName, String downloadUrl, long version,
                        String sha256, boolean hashWasPublished, String pluginClassName,
                        boolean requiresResources, String filePath,
                        String activationError, long installedAt) {
            this.id = id; this.repositoryUrl = repositoryUrl; this.name = name; this.internalName = internalName;
            this.downloadUrl = canonicalize(downloadUrl);
            this.version = version; this.sha256 = sha256; this.hashWasPublished = hashWasPublished;
            this.pluginClassName = pluginClassName;
            this.requiresResources = requiresResources; this.filePath = filePath;
            this.activationError = activationError; this.installedAt = installedAt;
        }
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id); json.put("repositoryUrl", repositoryUrl); json.put("name", name);
                json.put("internalName", internalName); json.put("downloadUrl", downloadUrl == null ? JSONObject.NULL : downloadUrl);
                json.put("version", version); json.put("sha256", sha256);
                json.put("hashWasPublished", hashWasPublished);
                json.put("pluginClassName", pluginClassName); json.put("requiresResources", requiresResources);
                json.put("fileName", new File(filePath).getName());
                json.put("activationError", activationError == null ? JSONObject.NULL : activationError);
                json.put("installedAt", installedAt);
            } catch (JSONException ignored) { }
            return json;
        }
        static InstalledPlugin fromJson(JSONObject json, File file) {
            String id = json.optString("id", "");
            String repo = canonicalize(json.optString("repositoryUrl", ""));
            String name = json.optString("name", "");
            String internal = json.optString("internalName", name);
            if (id.isEmpty() || repo == null || name.isEmpty() || internal.isEmpty()) return null;
            String downloadUrl = canonicalize(json.optString("downloadUrl", ""));
            String filename = json.optString("fileName", "");
            String path = file == null ? filename : file.getAbsolutePath();
            String error = json.isNull("activationError") ? null : json.optString("activationError", null);
            return new InstalledPlugin(id, repo, name, internal, downloadUrl, json.optLong("version", 0),
                    json.optString("sha256", ""), json.optBoolean("hashWasPublished", false),
                    json.optString("pluginClassName", ""),
                    json.optBoolean("requiresResources", false), path, error, json.optLong("installedAt", 0));
        }
    }

    public static final class RepositoryDocument {
        public final String repositoryUrl, finalUrl, name, description, iconUrl;
        public final int manifestVersion;
        public final List<String> pluginListUrls;
        RepositoryDocument(String repositoryUrl, String finalUrl, String name, String description,
                           String iconUrl, int manifestVersion, List<String> lists) {
            this.repositoryUrl = repositoryUrl; this.finalUrl = finalUrl; this.name = name;
            this.description = description; this.iconUrl = iconUrl; this.manifestVersion = manifestVersion;
            this.pluginListUrls = lists;
        }
    }
    private static final class CatalogResult {
        final List<PluginInfo> plugins;
        final int successfulLists, failedLists;
        CatalogResult(List<PluginInfo> plugins, int successfulLists, int failedLists) {
            this.plugins = plugins; this.successfulLists = successfulLists; this.failedLists = failedLists;
        }
    }
    private static final class HttpDocument {
        final String body, finalUrl;
        HttpDocument(String body, String finalUrl) { this.body = body; this.finalUrl = finalUrl; }
    }
    private static final class DownloadResult {
        final long size;
        final String sha256;
        DownloadResult(long size, String sha256) { this.size = size; this.sha256 = sha256; }
    }
    private static final class PluginManifest {
        final String pluginClassName;
        final boolean requiresResources;
        PluginManifest(String pluginClassName, boolean requiresResources) {
            this.pluginClassName = pluginClassName; this.requiresResources = requiresResources;
        }
    }
}
