package com.auras.orbit.extensions;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Live MegaRepo-to-Phisher-to-StreamPlay catalog smoke test; retains the real repositories and caches. */
@RunWith(AndroidJUnit4.class)
public final class MegaRepoPhisherRepositoryChainInstrumentationTest {
    private static final String MEGA_REPO_URL =
            "https://raw.githubusercontent.com/self-similarity/MegaRepo/builds/repo.json";
    private static final String PHISHER_REPO_URL =
            "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json";

    @Test
    public void megarepoAndPhisherCatalogsRemainAvailableWithoutChangingInstalledPackages() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        assertEquals("repository fixtures must persist in the primary debug app",
                "com.auras.orbit", context.getPackageName());
        CloudStreamExtensionManager manager = new CloudStreamExtensionManager(context);
        assertTrue("package recovery must complete before repository operations", manager.isStorageRecoveryReady());

        Set<String> repositoriesBefore = repositoryUrls(manager.getRepositories());
        Set<String> installedBefore = installedPluginIds(manager.getInstalledPlugins());
        Map<String, String> packageArchivesBefore = packageArchiveHashes(
                new File(context.getFilesDir(), "cloudstream/plugins"));

        // Use the user-provided shortcut, and refresh even when an earlier run already saved it.
        CloudStreamExtensionManager.AddRepositoryResult mega = manager.addRepository("megarepo");
        assertEquals(MEGA_REPO_URL, mega.repository.url);
        manager.refreshRepository(mega.repository.url);
        CloudStreamExtensionManager.PluginInfo megaProvider = findPlugin(
                manager.getCatalog(mega.repository.url), "MegaProvider");
        assertNotNull("MegaRepo must publish MegaProvider", megaProvider);
        assertEquals("MegaProvider v2 remains the intended package fixture", 2L, megaProvider.version);

        // Keep the source catalog current but never install or execute its package here.
        CloudStreamExtensionManager.AddRepositoryResult phisher = manager.addRepository("phisherrepo");
        assertEquals(PHISHER_REPO_URL, phisher.repository.url);
        manager.refreshRepository(phisher.repository.url);
        CloudStreamExtensionManager.PluginInfo streamPlay = findPlugin(
                manager.getCatalog(phisher.repository.url), "StreamPlay");
        assertNotNull("Phisher must publish a StreamPlay catalog entry", streamPlay);
        assertTrue("the current published StreamPlay version must be present", streamPlay.version > 0L);

        assertTrue("all pre-existing repository records must be retained",
                repositoryUrls(manager.getRepositories()).containsAll(repositoriesBefore));
        assertTrue("MegaRepo must remain registered", manager.findRepository(MEGA_REPO_URL) != null);
        assertTrue("Phisher must remain registered", manager.findRepository(PHISHER_REPO_URL) != null);
        assertEquals("repository discovery must not install, replace, or remove a plugin",
                installedBefore, installedPluginIds(manager.getInstalledPlugins()));
        assertEquals("repository discovery must preserve every installed or staged package archive byte-for-byte",
                packageArchivesBefore,
                packageArchiveHashes(new File(context.getFilesDir(), "cloudstream/plugins")));
    }

    private static Set<String> repositoryUrls(List<CloudStreamExtensionManager.RepositoryInfo> repositories) {
        HashSet<String> urls = new HashSet<>();
        for (CloudStreamExtensionManager.RepositoryInfo repository : repositories) urls.add(repository.url);
        return urls;
    }

    private static Set<String> installedPluginIds(List<CloudStreamExtensionManager.InstalledPlugin> plugins) {
        HashSet<String> ids = new HashSet<>();
        for (CloudStreamExtensionManager.InstalledPlugin plugin : plugins) ids.add(plugin.id);
        return ids;
    }

    private static Map<String, String> packageArchiveHashes(File directory) throws Exception {
        TreeMap<String, String> hashes = new TreeMap<>();
        if (!directory.exists()) return hashes;
        if (!directory.isDirectory()) throw new AssertionError("package archive path is not a directory");
        File[] files = directory.listFiles();
        if (files == null) throw new AssertionError("package archive directory could not be listed");
        for (File file : files) {
            if (!file.isFile()) continue;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
            hashes.put(file.getName(), file.length() + ":" + hex);
        }
        return hashes;
    }

    private static CloudStreamExtensionManager.PluginInfo findPlugin(
            List<CloudStreamExtensionManager.PluginInfo> plugins, String internalName) {
        for (CloudStreamExtensionManager.PluginInfo plugin : plugins) {
            if (internalName.equalsIgnoreCase(plugin.internalName)) return plugin;
        }
        return null;
    }
}
