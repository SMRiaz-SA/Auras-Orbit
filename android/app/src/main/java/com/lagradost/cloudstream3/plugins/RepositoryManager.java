package com.lagradost.cloudstream3.plugins;

import com.auras.orbit.extensions.CloudStreamExtensionManager;
import com.auras.orbit.extensions.OrbitCs3HostServices;
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData;

import java.util.ArrayList;

/** Orbit-owned repository facade backed by the unrestricted CloudStream catalog store. */
public final class RepositoryManager {
    public static final RepositoryManager INSTANCE = new RepositoryManager();

    private RepositoryManager() { }

    public Object addRepository(RepositoryData repository, kotlin.coroutines.Continuation<?> continuation) {
        return OrbitCs3HostServices.get().addRepositoryFromPlugin(repository, continuation);
    }

    public RepositoryData[] getRepositories() {
        ArrayList<RepositoryData> result = new ArrayList<>();
        for (CloudStreamExtensionManager.RepositoryInfo item : services().getRepositories()) {
            result.add(new RepositoryData(item.iconUrl, item.name, item.url, item.description));
        }
        return result.toArray(new RepositoryData[0]);
    }

    public Object parseRepository(String url, kotlin.coroutines.Continuation<?> continuation) {
        if (url == null) return null;
        return OrbitCs3HostServices.get().runSuspendIo(continuation, () -> {
            try {
                CloudStreamExtensionManager.RepositoryDocument item = services().previewRepositoryDescriptor(url);
                return new Repository(item.iconUrl, item.name, item.description,
                        item.manifestVersion, item.pluginListUrls);
            } catch (java.io.IOException error) {
                return null;
            }
        });
    }

    private static CloudStreamExtensionManager services() {
        return OrbitCs3HostServices.get().getExtensionManager();
    }
}
