package com.lagradost.cloudstream3.plugins;

import com.auras.orbit.OrbitApplication;
import com.auras.orbit.extensions.CloudStreamExtensionManager;
import com.auras.orbit.extensions.OrbitCs3HostServices;

import java.util.ArrayList;

/** Orbit-owned installed-plugin metadata/lifecycle facade; it does not load packages. */
public final class PluginManager {
    public static final PluginManager INSTANCE = new PluginManager();

    private PluginManager() { }

    public PluginData[] getPluginsOnline() {
        ArrayList<PluginData> plugins = new ArrayList<>();
        CloudStreamExtensionManager extensionManager = services();
        for (CloudStreamExtensionManager.InstalledPlugin item
                : extensionManager.getInstalledPluginsInStoredOrder()) {
            int version = item.version > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) item.version;
            String downloadUrl = item.downloadUrl != null
                    ? item.downloadUrl : findLegacyInstalledDownloadUrl(extensionManager, item);
            plugins.add(new PluginData(item.internalName, downloadUrl,
                    true, item.filePath, version));
        }
        return plugins.toArray(new PluginData[0]);
    }

    /**
     * Compatibility fallback for package records written before Orbit persisted the original
     * download URL. Recover only from a cached catalog entry that still matches the installed
     * version and published package hash. Never substitute the repository URL.
     */
    private static String findLegacyInstalledDownloadUrl(
            CloudStreamExtensionManager extensionManager,
            CloudStreamExtensionManager.InstalledPlugin installed) {
        if (extensionManager == null || installed == null || !installed.hashWasPublished
                || installed.sha256 == null || installed.sha256.isEmpty()) return null;
        for (CloudStreamExtensionManager.PluginInfo candidate
                : extensionManager.getCatalog(installed.repositoryUrl)) {
            if (candidate == null || !installed.id.equals(candidate.id)
                    || installed.version != candidate.version
                    || candidate.expectedSha256 == null
                    || !installed.sha256.equalsIgnoreCase(candidate.expectedSha256)) continue;
            return candidate.url;
        }
        return null;
    }

    public void unloadPlugin(String pluginPath) {
        if (pluginPath == null) return;
        OrbitCs3HostServices host = OrbitCs3HostServices.get();
        android.content.Context context = host.getApplicationContext();
        if (context instanceof OrbitApplication) {
            ((OrbitApplication) context).getCS3PluginRuntime().deactivateByPath(pluginPath);
        } else {
            host.unloadPluginByPath(pluginPath);
        }
    }

    private static CloudStreamExtensionManager services() {
        return OrbitCs3HostServices.get().getExtensionManager();
    }
}
